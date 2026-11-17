/*
 * Copyright 2017-2026 noear.org and authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.noear.solon.ai.talents.gateway;

import org.noear.snack4.ONode;
import org.noear.solon.Utils;
import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.chat.talent.AbsTalent;
import org.noear.solon.ai.chat.tool.FunctionTool;
import org.noear.solon.ai.chat.tool.ToolResult;
import org.noear.solon.ai.i18n.AiI18n;
import org.noear.solon.ai.talents.gateway.i18n.OpenApiGatewayI18n;
import org.noear.solon.ai.talents.gateway.i18n.OpenApiGatewayI18nKeys;
import org.noear.solon.ai.talents.gateway.openapi.*;
import org.noear.solon.ai.talents.gateway.openapi.resolver.OpenApiResolver;
import org.noear.solon.annotation.Param;
import org.noear.solon.core.util.Assert;
import org.noear.solon.lang.Preview;
import org.noear.solon.net.http.HttpTimeout;
import org.noear.solon.net.http.HttpUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * OpenAPI 网关：按 OpenAPI(Swagger) 文档接入 REST 接口，支持四种自动发现模式。
 *
 * <p>模式按接口数切换（每个模式的阈值以模式命名，不超过它就用这个模式）：
 * <ol>
 *   <li>FULL (直接调用): 接口数 &lt;= fullThreshold。完整接口定义写进指令，只暴露 call_api。</li>
 *   <li>SUMMARY (摘要): 接口数 &lt;= summaryThreshold。按分组展示名称、描述及 Endpoint。</li>
 *   <li>LIST (名字): 接口数 &lt;= listThreshold。仅展示分组及接口名列表。</li>
 *   <li>SEARCH (搜索): 其余（最后一档，没有阈值）。强制搜索。</li>
 * </ol>
 *
 * <p>两个层次：开发者按「接口源」（一份文档，以 docUrl 为键）增删与刷新；模型看到的「分组」是接口的第一个
 * tag（没有 tag 时归入默认分组），与接口源无关。接口按名字（大小写不敏感）定位，不同接口源不能有同名接口
 * （后加的替换先加的）。
 *
 * <p>索引写时复制：增删、刷新只在新的一版完整建好后才替换，调用中的线程不会看到工具暂时消失。
 *
 * @author noear
 * @since 3.9.1
 */
@Preview("3.9.1")
public class OpenApiGatewayTalent extends AbsTalent {
    private static final Logger LOG = LoggerFactory.getLogger(OpenApiGatewayTalent.class);

    static final String TOOL_SEARCH = "search_apis";
    static final String TOOL_DETAIL = "get_api_detail";
    static final String TOOL_CALL = "call_api";

    // search_apis 单次最多列出的接口数
    private static final int SEARCH_LIMIT = 10;

    /** 工具索引（写时复制：每次修改都换一个新的不可变实例） */
    private static final class Index {
        static final Index EMPTY = new Index(Collections.emptyMap(), Collections.emptyMap());

        // 分组名 -> (接口名小写 -> 接口)
        final Map<String, Map<String, ApiTool>> categories;
        // 接口名小写 -> 接口
        final Map<String, ApiTool> all;

        Index(Map<String, Map<String, ApiTool>> categories, Map<String, ApiTool> all) {
            this.categories = categories;
            this.all = all;
        }
    }

    private volatile Index index = Index.EMPTY;
    // 接口源 docUrl -> 该源当前生效的接口（停用的源不在这里）；只在 indexLock 内读写
    private final Map<String, List<ApiTool>> sourceTools = new LinkedHashMap<>();
    private final Object indexLock = new Object();

    // 接口源的运行时注册表（含停用的源）
    private final Map<String, ApiSourceClient> sourceProviderMap = new ConcurrentHashMap<>();

    private ApiResolver resolver = OpenApiResolver.getInstance();
    private ApiAuthenticator defaultAuthenticator;
    private Duration defaultTimeout = Duration.ofSeconds(30);

    // 模式阈值（接口数）：xxxThreshold 是 xxx 模式的阈值，不超过它（含）就用这个模式，超过往下一档；SEARCH 是最后一档
    private int fullThreshold = 8;
    private int summaryThreshold = 30;
    private int listThreshold = 100;
    // 是否已检查过阈值顺序（改阈值后重新检查）
    private volatile boolean thresholdsChecked;

    private int maxContextLength = 8000;

    private volatile int maxRetries = 3;
    private volatile long retryDelayMs = 1000L;

    /**
     * call_api 的重试配置（总尝试次数与初始退避）。
     *
     * <p>只在请求确定没有送达时重试（连接被拒、域名解析失败、连接超时等）。读超时、传输中断时请求可能已经执行，
     * 对创建类等非幂等接口重发会重复执行，不重试，交给模型看到报错后自行决定。
     */
    public OpenApiGatewayTalent retryConfig(int maxRetries, long retryDelayMs) {
        this.maxRetries = Math.max(1, maxRetries);
        this.retryDelayMs = Math.max(100, retryDelayMs);
        return this;
    }

    /**
     * call_api 的重试次数（语义同 {@link #retryConfig(int, long)}）
     */
    public OpenApiGatewayTalent retryConfig(int maxRetries) {
        this.maxRetries = Math.max(1, maxRetries);
        return this;
    }

    // --- 配置方法 ---

    /**
     * FULL（直接调用）模式的阈值（接口数，缺省 8）：不超过它时完整接口定义写进指令、只暴露 call_api
     * （接口不是原始工具，所以这里的 FULL 与 Tool / MCP 网关「直接交付原始工具」不同）
     *
     * @since 4.2.0
     */
    public OpenApiGatewayTalent fullThreshold(int fullThreshold) {
        this.fullThreshold = fullThreshold;
        this.thresholdsChecked = false;
        return this;
    }

    /**
     * SUMMARY（摘要）模式的阈值（接口数，缺省 30）：不超过它时按分组列出名称、描述与 Endpoint
     *
     * @since 4.2.0
     */
    public OpenApiGatewayTalent summaryThreshold(int summaryThreshold) {
        this.summaryThreshold = summaryThreshold;
        this.thresholdsChecked = false;
        return this;
    }

    /**
     * LIST（只列名字）模式的阈值（接口数，缺省 100）：不超过它时只列接口名，超过则进入 SEARCH（搜索）模式。
     * 三个阈值必须递增（{@code fullThreshold < summaryThreshold < listThreshold}），配反或相等时打 WARN（后一档取不到）。
     *
     * @since 4.2.0
     */
    public OpenApiGatewayTalent listThreshold(int listThreshold) {
        this.listThreshold = listThreshold;
        this.thresholdsChecked = false;
        return this;
    }

    /**
     * @deprecated 4.2.0 改名为 {@link #fullThreshold(int)}（阈值以它所在的模式命名）
     */
    @Deprecated
    public OpenApiGatewayTalent dynamicThreshold(int dynamicThreshold) {
        return fullThreshold(dynamicThreshold);
    }

    /**
     * @deprecated 4.2.0 改名为 {@link #listThreshold(int)}（含义相同：LIST 模式的接口数阈值）。
     * 注意 4.1.x 的 {@code listThreshold(n)} 指的是 SUMMARY 模式，现在叫 {@link #summaryThreshold(int)}，
     * 名字被 LIST 模式占用，无法保留旧方法
     */
    @Deprecated
    public OpenApiGatewayTalent searchThreshold(int searchThreshold) {
        return listThreshold(searchThreshold);
    }

    /**
     * call_api 返回内容的最大字符数（缺省 8000，超出截断并标注）
     */
    public OpenApiGatewayTalent maxContextLength(int length) {
        this.maxContextLength = length;
        return this;
    }

    public OpenApiGatewayTalent defaultAuthenticator(ApiAuthenticator defaultAuthenticator) {
        this.defaultAuthenticator = defaultAuthenticator;
        return this;
    }

    public OpenApiGatewayTalent defaultTimeout(Duration defaultTimeout) {
        if (defaultTimeout != null) {
            this.defaultTimeout = defaultTimeout;
        }
        return this;
    }

    public OpenApiGatewayTalent resolver(ApiResolver resolver) {
        if (resolver != null) {
            this.resolver = resolver;
        }
        return this;
    }

    /**
     * 添加接口源
     *
     * @param docUrl     OpenAPI 定义地址（http/https，经 HTTP GET 拉取）
     * @param apiBaseUrl 实际接口执行基地址（调用时以它为准）
     */
    public OpenApiGatewayTalent addApi(String docUrl, String apiBaseUrl) {
        return addApi(docUrl, apiBaseUrl, null);
    }

    /**
     * 添加接口源
     *
     * @param docUrl     OpenAPI 定义地址（http/https，经 HTTP GET 拉取）
     * @param apiBaseUrl 实际接口执行基地址（调用时以它为准）
     * @param headers    拉取文档与调用接口时都带上的请求头
     */
    public OpenApiGatewayTalent addApi(String docUrl, String apiBaseUrl, Map<String, String> headers) {
        return addApi(docUrl, apiBaseUrl, headers, null);
    }

    /**
     * 添加接口源
     *
     * @param docUrl        OpenAPI 定义地址（http/https，经 HTTP GET 拉取）
     * @param apiBaseUrl    实际接口执行基地址（调用时以它为准）
     * @param headers       拉取文档与调用接口时都带上的请求头
     * @param authenticator 认证器（拉取文档时也会调用，此时 tool 参数为 null）
     */
    public OpenApiGatewayTalent addApi(String docUrl, String apiBaseUrl, Map<String, String> headers, ApiAuthenticator authenticator) {
        ApiSource source = new ApiSource();
        source.setDocUrl(docUrl);
        source.setApiBaseUrl(apiBaseUrl);
        source.setHeaders(headers);
        source.setAuthenticator(authenticator);

        return addApi(source);
    }


    /**
     * 添加接口源（docUrl 相同的源已存在时整体替换；新文档加载完成后才替换，期间旧接口仍可用）
     *
     * @param apiSource 接口源
     */
    public OpenApiGatewayTalent addApi(ApiSource apiSource) {
        if (apiSource == null || Utils.isEmpty(apiSource.getDocUrl())) {
            return this;
        }

        String docUrl = apiSource.getDocUrl();
        ApiSourceClient provider = new ApiSourceClient(apiSource, resolver, defaultAuthenticator, defaultTimeout);

        // 停用的源只登记到管理表，不进工具索引
        if (apiSource.isEnabled() == false) {
            sourceProviderMap.put(docUrl, provider);
            putSourceTools(docUrl, null);
            LOG.info("OpenAPI source '{}' is disabled, registered to management only", docUrl);
            return this;
        }

        List<ApiTool> tools;
        try {
            tools = new ArrayList<>(provider.getToolsActivated());
        } catch (Throwable e) {
            // 单个源的解析失败（含 Error）不应打断整个网关；替换语义下该源没有可用接口
            LOG.error("Failed to load OpenAPI from: {}", docUrl, e);
            tools = null;
        }

        sourceProviderMap.put(docUrl, provider);
        putSourceTools(docUrl, tools);
        return this;
    }

    /**
     * 移除接口源（连同它的接口）
     *
     * @param docUrl OpenAPI 定义地址
     */
    public OpenApiGatewayTalent removeApi(String docUrl) {
        if (Utils.isEmpty(docUrl)) {
            return this;
        }

        sourceProviderMap.remove(docUrl);
        int removed = putSourceTools(docUrl, null);

        if (removed > 0) {
            LOG.info("OpenApiTalent: Removed {} tools from {}", removed, docUrl);
        }

        return this;
    }

    /**
     * 换上某个源的接口并重建索引（tools 为 null 或空表示该源没有生效的接口）
     *
     * @return 该源原有的接口数
     */
    private int putSourceTools(String docUrl, List<ApiTool> tools) {
        synchronized (indexLock) {
            List<ApiTool> old = (tools == null || tools.isEmpty())
                    ? sourceTools.remove(docUrl)
                    : sourceTools.put(docUrl, tools);

            Map<String, ApiTool> all = new LinkedHashMap<>();
            for (List<ApiTool> list : sourceTools.values()) {
                for (ApiTool tool : list) {
                    all.put(tool.getName().toLowerCase(), tool);
                }
            }

            Map<String, Map<String, ApiTool>> categories = new LinkedHashMap<>();
            all.forEach((key, tool) -> categories.computeIfAbsent(tool.getCategory(), k -> new LinkedHashMap<>()).put(key, tool));

            Map<String, Map<String, ApiTool>> frozen = new LinkedHashMap<>();
            categories.forEach((cat, map) -> frozen.put(cat, Collections.unmodifiableMap(map)));
            index = new Index(Collections.unmodifiableMap(frozen), Collections.unmodifiableMap(all));

            return old == null ? 0 : old.size();
        }
    }


    @Override
    public String description(Prompt prompt) {
        return OpenApiGatewayI18n.get(AiI18n.localeOf(prompt), OpenApiGatewayI18nKeys.GATEWAY_OPENAPI_DESCRIPTION);
    }

    /**
     * 分组的展示名（默认分组按语言显示，见 {@link ApiTool#getCategory()}）
     */
    private static String categoryOf(String category, Locale locale) {
        if (OpenApiGatewayI18n.messages().textsOfAllLocales(OpenApiGatewayI18nKeys.GATEWAY_OPENAPI_DEFAULT_CATEGORY).contains(category)) {
            return OpenApiGatewayI18n.get(locale, OpenApiGatewayI18nKeys.GATEWAY_OPENAPI_DEFAULT_CATEGORY);
        }
        return category;
    }


    @Override
    public boolean isSupported(Prompt prompt) {
        return index.all.size() > 0;
    }

    @Override
    public String getInstruction(Prompt prompt) {
        checkThresholds();

        final Locale locale = AiI18n.localeOf(prompt);
        final Index idx = index;
        final int size = idx.all.size();
        StringBuilder sb = new StringBuilder();
        sb.append(OpenApiGatewayI18n.fmt(locale, OpenApiGatewayI18nKeys.GATEWAY_OPENAPI_INSTRUCTION_TITLE, "count", size)).append("\n");

        if (size <= fullThreshold) {
            // --- 模式 1: FULL ---
            sb.append(OpenApiGatewayI18n.get(locale, OpenApiGatewayI18nKeys.GATEWAY_OPENAPI_INSTRUCTION_FULL)).append("\n");
            sb.append(formatApiDocs(idx.all.values(), locale));

        } else if (size <= summaryThreshold) {
            // --- 模式 2: SUMMARY ---
            sb.append(OpenApiGatewayI18n.get(locale, OpenApiGatewayI18nKeys.GATEWAY_OPENAPI_INSTRUCTION_SUMMARY)).append("\n");
            idx.categories.forEach((cat, tools) -> {
                sb.append(OpenApiGatewayI18n.fmt(locale, OpenApiGatewayI18nKeys.GATEWAY_OPENAPI_INSTRUCTION_SUMMARY_GROUP,
                        "category", categoryOf(cat, locale))).append("\n");
                tools.values().forEach(t ->
                        sb.append("  - `").append(t.getName()).append("`: ").append(t.getDescription())
                                .append(" (").append(t.getMethod()).append(" ").append(t.getPath()).append(")\n")
                );
            });
        } else if (size <= listThreshold) {
            // --- 模式 3: LIST（按原名显示） ---
            sb.append(OpenApiGatewayI18n.get(locale, OpenApiGatewayI18nKeys.GATEWAY_OPENAPI_INSTRUCTION_LIST)).append("\n");
            idx.categories.forEach((cat, tools) -> {
                String names = tools.values().stream().map(ApiTool::getName).collect(Collectors.joining(", "));
                sb.append(OpenApiGatewayI18n.fmt(locale, OpenApiGatewayI18nKeys.GATEWAY_OPENAPI_INSTRUCTION_LIST_GROUP,
                        "category", categoryOf(cat, locale), "names", names)).append("\n");
            });
            sb.append("\n").append(OpenApiGatewayI18n.get(locale, OpenApiGatewayI18nKeys.GATEWAY_OPENAPI_INSTRUCTION_LIST_TIP));
        } else {
            // --- 模式 4: SEARCH ---
            sb.append(OpenApiGatewayI18n.get(locale, OpenApiGatewayI18nKeys.GATEWAY_OPENAPI_INSTRUCTION_SEARCH));
        }

        sb.append("\n\n").append(OpenApiGatewayI18n.get(locale, OpenApiGatewayI18nKeys.GATEWAY_OPENAPI_INSTRUCTION_CONSTRAINTS));

        return sb.toString();
    }

    @Override
    public Collection<FunctionTool> getTools(Prompt prompt) {
        checkThresholds();

        int size = index.all.size();

        // 中转工具（描述是固定文本，不随请求语言变化；指令与返回文案按请求语言）
        Collection<FunctionTool> tools = super.getTools(prompt);

        if (size <= fullThreshold) {
            return tools.stream().filter(t -> TOOL_CALL.equals(t.name())).collect(Collectors.toList());
        }

        if (size <= summaryThreshold) {
            return tools.stream().filter(t -> !TOOL_SEARCH.equals(t.name())).collect(Collectors.toList());
        }

        return tools;
    }

    /**
     * 阈值必须按档位递增：配反或配成相等时，后一档永远取不到。首次渲染时检查一次（改阈值后重新检查）。
     * <p>
     * 相等也算配错，因为「不超过」含本数：前两档相等时 SUMMARY 取不到，后两档相等时 LIST 取不到。
     * 0 是「关掉这一档」的显式写法，不提示。
     */
    private void checkThresholds() {
        if (thresholdsChecked) {
            return;
        }

        thresholdsChecked = true;
        if (summaryThreshold > 0 && fullThreshold >= summaryThreshold) {
            LOG.warn("OpenApiGatewayTalent: fullThreshold ({}) >= summaryThreshold ({}), SUMMARY mode is unreachable",
                    fullThreshold, summaryThreshold);
        }
        if (listThreshold > 0 && summaryThreshold >= listThreshold) {
            LOG.warn("OpenApiGatewayTalent: summaryThreshold ({}) >= listThreshold ({}), LIST mode is unreachable; if this is 4.1.x code, change listThreshold({}) to summaryThreshold({})",
                    summaryThreshold, listThreshold, listThreshold, listThreshold);
        }
    }

    // --- 内置工具映射 ---

    /**
     * 搜索接口（__locale 由工具上下文注入）。结果为列表（每项含 api_name、category、description、endpoint），最多 10 条。
     *
     * @since 4.2
     */
    @ToolMapping(name = TOOL_SEARCH, description = "通过关键词在 API 库中搜索匹配的接口。支持多关键词空格分隔（如：'订单 查询'），按 AND 逻辑匹配")
    public Object searchApis(@Param("keyword") String keyword, String __locale) {
        Locale locale = AiI18n.toLocale(__locale);
        String[] keys = GatewaySupport.keywordsOf(keyword);
        if (keys.length == 0) return OpenApiGatewayI18n.get(locale, OpenApiGatewayI18nKeys.GATEWAY_OPENAPI_SEARCH_BLANK);

        List<Map<String, String>> results = index.all.values().stream()
                // 多词 AND 匹配
                .filter(t -> GatewaySupport.containsAll(t.getName() + " " + t.getDescription() + " " + t.getPath(), keys))
                .limit(SEARCH_LIMIT)
                .map(t -> {
                    Map<String, String> map = new LinkedHashMap<>();
                    map.put("api_name", t.getName());
                    map.put("category", categoryOf(t.getCategory(), locale));
                    map.put("description", t.getDescription());
                    map.put("endpoint", t.getMethod() + " " + t.getPath());
                    return map;
                })
                .collect(Collectors.toList());

        if (results.isEmpty()) {
            return OpenApiGatewayI18n.fmt(locale, OpenApiGatewayI18nKeys.GATEWAY_OPENAPI_SEARCH_NONE, "keyword", keyword.trim());
        }

        return results;
    }

    /**
     * 获取接口详情（__locale 由工具上下文注入）
     *
     * @since 4.2
     */
    @ToolMapping(name = TOOL_DETAIL, description = "根据接口名称获取完整的参数定义（Header/Path/Query/Body Schema）及返回值结构")
    public String getApiDetail(@Param("api_name") String apiName, String __locale) {
        Locale locale = AiI18n.toLocale(__locale);
        if (Utils.isBlank(apiName)) return OpenApiGatewayI18n.get(locale, OpenApiGatewayI18nKeys.GATEWAY_OPENAPI_DETAIL_BLANK);

        ApiTool tool = index.all.get(apiName.trim().toLowerCase());

        if (tool != null) {
            return formatApiDocs(Collections.singletonList(tool), locale);
        } else {
            return OpenApiGatewayI18n.fmt(locale, OpenApiGatewayI18nKeys.GATEWAY_OPENAPI_DETAIL_MISSING, "name", apiName);
        }
    }

    /**
     * 调用接口（__locale 由工具上下文注入）。
     *
     * <p>调用本身的失败（名字为空、未找到、缺路径参数、请求异常）返回报错结果（isError），客户端据此标红；
     * 接口返回的内容（含 4xx/5xx 的响应体）按正常结果给出，超过 maxContextLength 截断并标注。
     *
     * @since 4.2
     */
    @ToolMapping(name = TOOL_CALL, description = "根据接口名称执行 REST API 调用。需提供 api_name 及对应参数（path_params、query_params、body_params）")
    public ToolResult callApi(
            @Param("api_name") String apiName,
            @Param("header_params") Map<String, Object> headerParams,
            @Param("path_params") Map<String, Object> pathParams,
            @Param("query_params") Map<String, Object> queryParams,
            @Param("body_params") Map<String, Object> bodyParams,
            String __locale) {
        Locale locale = AiI18n.toLocale(__locale);

        if (Utils.isBlank(apiName)) {
            return ToolResult.error(OpenApiGatewayI18n.get(locale, OpenApiGatewayI18nKeys.GATEWAY_OPENAPI_DETAIL_BLANK));
        }

        ApiTool tool = index.all.get(apiName.trim().toLowerCase());

        if (tool == null) {
            return ToolResult.error(OpenApiGatewayI18n.fmt(locale, OpenApiGatewayI18nKeys.GATEWAY_OPENAPI_CALL_MISSING, "name", apiName));
        }

        String baseUrl = tool.getBaseUrl();
        String finalPath = replacePathParams(tool, pathParams);

        // 如果 finalPath 依然包含 {xxx}，说明 AI 漏传了 pathSchema 中定义的必填路径参数
        if (finalPath.contains("{") && finalPath.contains("}")) {
            return ToolResult.error(OpenApiGatewayI18n.fmt(locale, OpenApiGatewayI18nKeys.GATEWAY_OPENAPI_CALL_NO_PATH, "path", finalPath));
        }

        // 6. 执行并处理响应
        try {
            HttpUtils http = buildRequest(tool, baseUrl + finalPath, headerParams, queryParams, bodyParams);

            LOG.debug("OpenApiTalent calling: {} {} (api: {})", tool.getMethod(), baseUrl + finalPath, apiName);

            String result = GatewaySupport.callWithRetry(maxRetries, retryDelayMs,
                    () -> http.exec(tool.getMethod()).bodyAsString());

            // 结果截断处理，防止撑爆 AI 上下文
            if (result != null && result.length() > maxContextLength) {
                return ToolResult.success(result.substring(0, maxContextLength) + "... [Data truncated]");
            }

            return ToolResult.success(Utils.isEmpty(result) ? "Success: OpenAPI executed, but returned an empty response." : result);
        } catch (Throwable e) {
            LOG.warn("OpenAPI Call Failed: {} - {}", tool.getName(), e.getMessage());
            String errorMsg = e.getMessage() != null ? e.getMessage()
                    : OpenApiGatewayI18n.get(locale, OpenApiGatewayI18nKeys.GATEWAY_OPENAPI_CALL_NO_RESPONSE);
            return ToolResult.error(OpenApiGatewayI18n.fmt(locale, OpenApiGatewayI18nKeys.GATEWAY_OPENAPI_CALL_ERROR, "error", errorMsg));
        }
    }

    // --- 私有辅助 ---

    /** 构建请求：超时、Header（模型给的 → 源配置 → 认证器，后者覆盖前者）、Query、Body */
    private HttpUtils buildRequest(ApiTool tool, String url,
                                   Map<String, Object> headerParams,
                                   Map<String, Object> queryParams,
                                   Map<String, Object> bodyParams) {
        HttpUtils http = HttpUtils.http(url);

        if (tool.getSource() != null && tool.getSource().getTimeout() != null) {
            http.timeout(HttpTimeout.of(tool.getSource().getTimeout()));
        } else {
            http.timeout(HttpTimeout.of(defaultTimeout));
        }

        // Header 参数
        if (Assert.isNotEmpty(headerParams)) {
            http.headers(headerParams);
        }

        // 源配置的 Header 与认证（放在模型给的之后，模型不能覆盖认证信息）
        if (tool.getSource() != null && Assert.isNotEmpty(tool.getSource().getHeaders())) {
            http.headers(tool.getSource().getHeaders());
        }

        if (tool.getSource() != null && tool.getSource().getAuthenticator() != null) {
            tool.getSource().getAuthenticator().apply(http, tool);
        } else if (defaultAuthenticator != null) {
            defaultAuthenticator.apply(http, tool);
        }

        // Query 参数 (URL 查询参数)
        if (Assert.isNotEmpty(queryParams)) {
            http.data(queryParams);
        }

        // Body 参数
        if (Assert.isNotEmpty(bodyParams)) {
            if (tool.isMultipart()) {
                // 表单或多部分对象提交
                http.data(bodyParams).multipart(true);
            } else {
                // 标准 JSON 提交 (使用 ONode 序列化处理)
                http.bodyOfJson(ONode.serialize(bodyParams));
            }
        }

        return http;
    }

    private String replacePathParams(ApiTool tool, Map<String, Object> pathParams) {
        String finalPath = tool.getPath();

        if (Assert.isEmpty(tool.getPathSchema())) {
            return finalPath;
        }

        ONode pathSchema = ONode.ofJson(tool.getPathSchema());
        ONode required = pathSchema.get("required");
        if (required.isArray()) {
            for (ONode nameNode : required.getArray()) {
                String name = nameNode.getString();
                Object value = pathParams == null ? null : pathParams.get(name);
                if (value != null) {
                    finalPath = finalPath.replace("{" + name + "}", encodePathValue(value));
                }
            }
        }

        return finalPath;
    }

    private String encodePathValue(Object value) {
        try {
            return URLEncoder.encode(String.valueOf(value), "UTF-8").replace("+", "%20");
        } catch (UnsupportedEncodingException e) {
            return String.valueOf(value);
        }
    }

    // ========== 查询（供宿主与前端使用） ==========

    /**
     * 按 docUrl 获取接口源的运行时客户端（含停用的源；可读全量接口、调整 allowed/disallowed 名单，改完调 {@link #refreshApi(String)}）
     */
    public ApiSourceClient getApiSource(String docUrl) {
        return docUrl == null ? null : sourceProviderMap.get(docUrl);
    }

    /**
     * 是否包含指定 docUrl 的接口源（含停用的源）
     */
    public boolean hasApiSource(String docUrl) {
        return docUrl != null && sourceProviderMap.containsKey(docUrl);
    }

    // ========== 刷新（权限变更后重新加载） ==========

    /**
     * 刷新指定接口源（allowedTools / disallowedTools 或启用状态变更后调用），只重新过滤，不重新拉取文档
     */
    public OpenApiGatewayTalent refreshApi(String docUrl) {
        return refreshApi(docUrl, false);
    }

    /**
     * 刷新指定接口源。
     *
     * <p>先建好新的一版接口再整体替换，调用中的线程不会看到接口暂时消失；重新拉取文档失败时保留原有接口。
     *
     * @param docUrl           API 定义地址
     * @param reloadDefinition 是否重新从远程拉取定义文档（false=仅按名单重新过滤）
     */
    public OpenApiGatewayTalent refreshApi(String docUrl, boolean reloadDefinition) {
        if (Utils.isEmpty(docUrl)) {
            return this;
        }

        ApiSourceClient provider = sourceProviderMap.get(docUrl);
        if (provider == null) {
            return this;
        }

        // disabled: 只从工具索引移除，保留 provider
        if (provider.getSource().isEnabled() == false) {
            int removed = putSourceTools(docUrl, null);
            if (removed > 0) {
                LOG.info("OpenApiTalent: Source {} is disabled, removed {} tools from index", docUrl, removed);
            }
            return this;
        }

        List<ApiTool> tools;
        try {
            if (reloadDefinition) {
                // 拉取失败会抛出，原有缓存不变
                provider.reloadApi();
            }
            tools = new ArrayList<>(provider.getToolsActivated());
        } catch (Throwable e) {
            // 与 addApi 一致：单个源的解析失败（含 Error）不应打断整个网关；保留原有接口
            LOG.error("OpenApiTalent: Failed to refresh API from {}", docUrl, e);
            return this;
        }

        putSourceTools(docUrl, tools);
        return this;
    }

    /**
     * 刷新所有接口源（只重新过滤）
     */
    public OpenApiGatewayTalent refreshApiAll() {
        for (String docUrl : new ArrayList<>(sourceProviderMap.keySet())) {
            refreshApi(docUrl);
        }
        return this;
    }

    // ========== 私有辅助 ==========

    private String formatApiDocs(Collection<ApiTool> tools, Locale locale) {
        StringBuilder sb = new StringBuilder();
        for (ApiTool tool : tools) {
            sb.append("---\n").append("* **OpenAPI: ").append(tool.getName()).append("**\n");
            docLine(sb, OpenApiGatewayI18n.fmt(locale, OpenApiGatewayI18nKeys.GATEWAY_OPENAPI_DOC_TAGS, "tags", String.join(", ", tool.getTags())));
            docLine(sb, OpenApiGatewayI18n.fmt(locale, OpenApiGatewayI18nKeys.GATEWAY_OPENAPI_DOC_DESCRIPTION, "description", tool.getDescription()));
            docLine(sb, OpenApiGatewayI18n.fmt(locale, OpenApiGatewayI18nKeys.GATEWAY_OPENAPI_DOC_PATH, "method", tool.getMethod(), "path", tool.getPath()));

            if (Utils.isNotEmpty(tool.getHeaderSchema())) {
                docLine(sb, OpenApiGatewayI18n.fmt(locale, OpenApiGatewayI18nKeys.GATEWAY_OPENAPI_DOC_HEADER, "schema", tool.getHeaderSchema()));
            }

            if (Utils.isNotEmpty(tool.getPathSchema())) {
                docLine(sb, OpenApiGatewayI18n.fmt(locale, OpenApiGatewayI18nKeys.GATEWAY_OPENAPI_DOC_PATH_PARAMS, "schema", tool.getPathSchema()));
            }

            // 分开展示 Query 和 Body
            if (Utils.isNotEmpty(tool.getQuerySchema())) {
                docLine(sb, OpenApiGatewayI18n.fmt(locale, OpenApiGatewayI18nKeys.GATEWAY_OPENAPI_DOC_QUERY, "schema", tool.getQuerySchema()));
            }

            if (Utils.isNotEmpty(tool.getBodySchema())) {
                docLine(sb, tool.isMultipart()
                        ? OpenApiGatewayI18n.fmt(locale, OpenApiGatewayI18nKeys.GATEWAY_OPENAPI_DOC_BODY_MULTIPART, "schema", tool.getBodySchema())
                        : OpenApiGatewayI18n.fmt(locale, OpenApiGatewayI18nKeys.GATEWAY_OPENAPI_DOC_BODY_JSON, "schema", tool.getBodySchema()));
            }

            docLine(sb, OpenApiGatewayI18n.fmt(locale, OpenApiGatewayI18nKeys.GATEWAY_OPENAPI_DOC_OUTPUT, "schema", tool.getOutputSchemaOr("{}")));
        }
        return sb.toString();
    }

    private static void docLine(StringBuilder sb, String text) {
        sb.append("  - ").append(text).append("\n");
    }
}
