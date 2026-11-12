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
import org.noear.solon.ai.i18n.AiI18n;
import org.noear.solon.ai.talents.gateway.i18n.OpenApiGatewayI18n;
import org.noear.solon.ai.talents.gateway.i18n.OpenApiGatewayI18nKeys;
import org.noear.solon.ai.talents.gateway.openapi.*;
import org.noear.solon.ai.talents.gateway.openapi.resolver.OpenApiResolver;
import org.noear.solon.ai.util.RetryUtil;
import org.noear.solon.annotation.Param;
import org.noear.solon.core.util.Assert;
import org.noear.solon.lang.Preview;
import org.noear.solon.net.http.HttpTimeout;
import org.noear.solon.net.http.HttpUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URLEncoder;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * OpenAPI 网关：支持分组与四阶段自动发现模式。
 *
 * 逻辑档位：
 * 1. FULL: 数量 <= dynamicThreshold。平铺所有 OpenAPI 的完整定义。
 * 2. SUMMARY: 数量 <= listThreshold。展示分组、名称、描述及 Endpoint。
 * 3. LIST: 数量 <= searchThreshold。仅展示分组及接口名列表。
 * 4. SEARCH: 数量 > searchThreshold。强制搜索。
 *
 * 注意：不能有同名同方式接口
 *
 * @author noear
 * @since 3.9.1
 * @since 3.9.5
 */
@Preview("3.9.1")
public class OpenApiGatewayTalent extends AbsTalent {
    private static final Logger LOG = LoggerFactory.getLogger(OpenApiGatewayTalent.class);

    private final Map<String, Map<String, ApiTool>> categoryTools = new ConcurrentHashMap<>();
    private final Map<String, ApiTool> allTools = new ConcurrentHashMap<>();

    // ApiSource 的运行时注册表
    private final Map<String, ApiSourceClient> sourceProviderMap = new ConcurrentHashMap<>();

    private ApiResolver resolver = OpenApiResolver.getInstance();
    private ApiAuthenticator defaultAuthenticator;
    private Duration defaultTimeout = Duration.ofSeconds(30);

    private int dynamicThreshold = 8;
    private int listThreshold = 30;
    private int searchThreshold = 100;

    private int maxContextLength = 8000;

    private int maxRetries = 3;

    public OpenApiGatewayTalent retryConfig(int maxRetries, long retryDelayMs) {
        this.maxRetries = Math.max(1, maxRetries);
        return this;
    }

    public OpenApiGatewayTalent retryConfig(int maxRetries) {
        this.maxRetries = Math.max(1, maxRetries);
        return this;
    }

    // --- 配置方法 ---

    public OpenApiGatewayTalent dynamicThreshold(int dynamicThreshold) {
        this.dynamicThreshold = dynamicThreshold;
        return this;
    }

    public OpenApiGatewayTalent listThreshold(int listThreshold) {
        this.listThreshold = listThreshold;
        return this;
    }

    public OpenApiGatewayTalent searchThreshold(int searchThreshold) {
        this.searchThreshold = searchThreshold;
        return this;
    }

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
     * 添加 OpenAPI 组
     *
     * @param docUrl     OpenAPI 定义地址 (http://... 或 classpath:...)
     * @param apiBaseUrl 实际接口执行基地址
     */
    public OpenApiGatewayTalent addApi(String docUrl, String apiBaseUrl) {
        return addApi(docUrl, apiBaseUrl, null);
    }

    /**
     * 添加 OpenAPI 组
     *
     * @param docUrl     OpenAPI 定义地址 (http://... 或 classpath:...)
     * @param apiBaseUrl 实际接口执行基地址
     */
    public OpenApiGatewayTalent addApi(String docUrl, String apiBaseUrl, Map<String, String> headers) {
        return addApi(docUrl, apiBaseUrl, headers, null);
    }

    /**
     * 添加 OpenAPI 组
     *
     * @param docUrl     OpenAPI 定义地址 (http://... 或 classpath:...)
     * @param apiBaseUrl 实际接口执行基地址
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
     * 添加 API 组
     *
     * @param apiSource 接口源
     */
    public OpenApiGatewayTalent addApi(ApiSource apiSource) {
        // 同名已存在则先移除（幂等，不存在的 name 是空操作）
        removeApi(apiSource.getDocUrl());

        try {
            loadApiFromDefinition(apiSource);
        } catch (Throwable e) {
            LOG.error("Failed to load OpenAPI from: {}", apiSource.getDocUrl() , e);
        }

        return this;
    }

    /**
     * 移除 OpenAPI 组
     *
     * @param docUrl OpenAPI 定义地址 (http://... 或 classpath:...)
     */
    public OpenApiGatewayTalent removeApi(String docUrl) {
        if (Utils.isEmpty(docUrl)) {
            return this;
        }

        // 找出所有属于该 docUrl 的工具名称
        List<String> removedNames = new ArrayList<>();
        for (Map.Entry<String, ApiTool> entry : allTools.entrySet()) {
            ApiTool tool = entry.getValue();
            if (tool.getSource() != null && docUrl.equals(tool.getSource().getDocUrl())) {
                removedNames.add(entry.getKey());
            }
        }

        // 从 allTools 中批量移除
        for (String name : removedNames) {
            allTools.remove(name);
        }

        // 从 categoryTools 中移除
        for (Map.Entry<String, Map<String, ApiTool>> catEntry : categoryTools.entrySet()) {
            Map<String, ApiTool> catTools = catEntry.getValue();
            catTools.keySet().removeAll(removedNames);
            if (catTools.isEmpty()) {
                categoryTools.remove(catEntry.getKey());
            }
        }

        // 从 sourceProviderMap 中移除
        sourceProviderMap.remove(docUrl);

        if (!removedNames.isEmpty()) {
            LOG.info("OpenApiTalent: Removed {} tools from {}", removedNames.size(), docUrl);
        }

        return this;
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
        return allTools.size() > 0;
    }

    @Override
    public String getInstruction(Prompt prompt) {
        final Locale locale = AiI18n.localeOf(prompt);
        final int size = allTools.size();
        StringBuilder sb = new StringBuilder();
        sb.append(OpenApiGatewayI18n.fmt(locale, OpenApiGatewayI18nKeys.GATEWAY_OPENAPI_INSTRUCTION_TITLE, "count", size)).append("\n");

        if (size <= dynamicThreshold) {
            // --- 模式 1: FULL ---
            sb.append(OpenApiGatewayI18n.get(locale, OpenApiGatewayI18nKeys.GATEWAY_OPENAPI_INSTRUCTION_FULL)).append("\n");
            sb.append(formatApiDocs(allTools.values(), locale));

        } else if (size <= listThreshold) {
            // --- 模式 2: SUMMARY ---
            sb.append(OpenApiGatewayI18n.get(locale, OpenApiGatewayI18nKeys.GATEWAY_OPENAPI_INSTRUCTION_SUMMARY)).append("\n");
            categoryTools.forEach((cat, tools) -> {
                sb.append(OpenApiGatewayI18n.fmt(locale, OpenApiGatewayI18nKeys.GATEWAY_OPENAPI_INSTRUCTION_SUMMARY_GROUP,
                        "category", categoryOf(cat, locale))).append("\n");
                tools.values().forEach(t ->
                        sb.append("  - `").append(t.getName()).append("`: ").append(t.getDescription())
                                .append(" (").append(t.getMethod()).append(" ").append(t.getPath()).append(")\n")
                );
            });
        } else if (size <= searchThreshold) {
            // --- 模式 3: LIST ---
            sb.append(OpenApiGatewayI18n.get(locale, OpenApiGatewayI18nKeys.GATEWAY_OPENAPI_INSTRUCTION_LIST)).append("\n");
            categoryTools.forEach((cat, tools) -> {
                String names = String.join(", ", tools.keySet());
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
        int size = allTools.size();

        //内置工具按请求语言本地化
        Collection<FunctionTool> tools = super.getTools(prompt);

        if (size <= dynamicThreshold) {
            return tools.stream().filter(t -> "call_api".equals(t.name())).collect(Collectors.toList());
        }

        if (size <= listThreshold) {
            return tools.stream().filter(t -> !"search_apis".equals(t.name())).collect(Collectors.toList());
        }

        return tools;
    }

    // --- 内置工具映射 ---

    /**
     * 搜索接口（__locale 由工具上下文注入）
     *
     * @since 4.2
     */
    @ToolMapping(name = "search_apis", description = "通过关键词在 API 库中搜索匹配的接口。支持多关键词空格分隔（如：'订单 查询'），按 AND 逻辑匹配")
    public Object searchApis(@Param("keyword") String keyword, String __locale) {
        Locale locale = AiI18n.toLocale(__locale);
        if (Utils.isEmpty(keyword)) return OpenApiGatewayI18n.get(locale, OpenApiGatewayI18nKeys.GATEWAY_OPENAPI_SEARCH_BLANK);

        // 按空格及常见分隔符拆分关键词
        String[] keys = keyword.toLowerCase().split("[\\s,;，；]+");

        List<Map<String, String>> results = allTools.values().stream()
                .filter(t -> {
                    String content = (t.getName() + " " + t.getDescription() + " " + t.getPath()).toLowerCase();
                    // 多词 AND 匹配
                    return Arrays.stream(keys).allMatch(content::contains);
                })
                .limit(10)
                .map(t -> {
                    Map<String, String> map = new HashMap<>();
                    map.put("api_name", t.getName());
                    map.put("category", categoryOf(t.getCategory(), locale));
                    map.put("description", t.getDescription());
                    map.put("endpoint", t.getMethod() + " " + t.getPath());
                    return map;
                })
                .collect(Collectors.toList());

        if (results.isEmpty()) {
            return OpenApiGatewayI18n.fmt(locale, OpenApiGatewayI18nKeys.GATEWAY_OPENAPI_SEARCH_NONE, "keyword", keyword);
        }

        return results;
    }

    /**
     * 获取接口详情（__locale 由工具上下文注入）
     *
     * @since 4.2
     */
    @ToolMapping(name = "get_api_detail", description = "根据接口名称获取完整的参数定义（Header/Path/Query/Body Schema）及返回值结构")
    public String getApiDetail(@Param("api_name") String apiName, String __locale) {
        Locale locale = AiI18n.toLocale(__locale);
        if (Utils.isEmpty(apiName)) return OpenApiGatewayI18n.get(locale, OpenApiGatewayI18nKeys.GATEWAY_OPENAPI_DETAIL_BLANK);

        ApiTool tool = allTools.get(apiName.trim().toLowerCase());

        if (tool != null) {
            return formatApiDocs(Collections.singletonList(tool), locale);
        } else {
            return OpenApiGatewayI18n.fmt(locale, OpenApiGatewayI18nKeys.GATEWAY_OPENAPI_DETAIL_MISSING, "name", apiName);
        }
    }

    /**
     * 调用接口（__locale 由工具上下文注入）
     *
     * @since 4.2
     */
    @ToolMapping(name = "call_api", description = "根据接口名称执行 REST API 调用。需提供 api_name 及对应参数（path_params、query_params、body_params）")
    public String callApi(
            @Param("api_name") String apiName,
            @Param("header_params") Map<String, Object> headerParams,
            @Param("path_params") Map<String, Object> pathParams,
            @Param("query_params") Map<String, Object> queryParams,
            @Param("body_params") Map<String, Object> bodyParams,
            String __locale) throws IOException {
        Locale locale = AiI18n.toLocale(__locale);

        ApiTool tool = allTools.get(apiName.trim().toLowerCase());

        if (tool == null) {
            return OpenApiGatewayI18n.fmt(locale, OpenApiGatewayI18nKeys.GATEWAY_OPENAPI_CALL_MISSING, "name", apiName);
        }

        String baseUrl = tool.getBaseUrl();
        String finalPath = replacePathParams(tool, pathParams);

        // 如果 finalPath 依然包含 {xxx}，说明 AI 漏传了 pathSchema 中定义的必填路径参数
        if (finalPath.contains("{") && finalPath.contains("}")) {
            return OpenApiGatewayI18n.fmt(locale, OpenApiGatewayI18nKeys.GATEWAY_OPENAPI_CALL_NO_PATH, "path", finalPath);
        }

        // 构建请求对象
        HttpUtils http = HttpUtils.http(baseUrl + finalPath);

        if (tool.getSource() != null && tool.getSource().getTimeout() != null) {
            http.timeout(HttpTimeout.of(tool.getSource().getTimeout()));
        } else {
            http.timeout(HttpTimeout.of(defaultTimeout));
        }

        // 2. Header 参数设置
        if (Assert.isNotEmpty(headerParams)) {
            http.headers(headerParams);
        }

        // 3. 认证处理

        if (tool.getSource() != null && Assert.isNotEmpty(tool.getSource().getHeaders())) {
            http.headers(tool.getSource().getHeaders());
        }

        if (tool.getSource() != null && tool.getSource().getAuthenticator() != null) {
            tool.getSource().getAuthenticator().apply(http, tool);
        } else {
            if (defaultAuthenticator != null) {
                defaultAuthenticator.apply(http, tool);
            }
        }

        // 4. Query 参数设置 (URL 查询参数)
        if (Assert.isNotEmpty(queryParams)) {
            http.data(queryParams);
        }

        // 5. Body 参数设置
        if (Assert.isNotEmpty(bodyParams)) {
            if (tool.isMultipart()) {
                // 表单或多部分对象提交
                http.data(bodyParams).multipart(true);
            } else {
                // 标准 JSON 提交 (使用 ONode 序列化处理)
                http.bodyOfJson(ONode.serialize(bodyParams));
            }
        }

        // 6. 执行并处理响应
        try {
            LOG.debug("OpenApiTalent calling: {} {} (api: {})", tool.getMethod(), baseUrl + finalPath, apiName);

            String result = RetryUtil.callWithRetry(maxRetries, () -> http.exec(tool.getMethod()).bodyAsString());

            // 结果截断处理，防止撑爆 AI 上下文
            if (result != null && result.length() > maxContextLength) {
                return result.substring(0, maxContextLength) + "... [Data truncated]";
            }

            return Utils.isEmpty(result) ? "Success: OpenAPI executed, but returned an empty response." : result;
        } catch (Exception e) {
            LOG.warn("OpenAPI Call Failed: {} - {}", tool.getName(), e.getMessage());
            String errorMsg = e.getMessage() != null ? e.getMessage()
                    : OpenApiGatewayI18n.get(locale, OpenApiGatewayI18nKeys.GATEWAY_OPENAPI_CALL_NO_RESPONSE);
            return OpenApiGatewayI18n.fmt(locale, OpenApiGatewayI18nKeys.GATEWAY_OPENAPI_CALL_ERROR, "error", errorMsg);
        }
    }

    // --- 私有辅助 ---

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
        } catch (java.io.UnsupportedEncodingException e) {
            return String.valueOf(value);
        }
    }

     
    /**
     * 从 ApiSource 创建 Provider 并加载 API 定义，然后同步到全局索引
     */
    private void loadApiFromDefinition(ApiSource source) throws IOException {
        // 1. 创建 Provider（自主加载能力）
        ApiSourceClient provider = new ApiSourceClient(source, resolver, defaultAuthenticator, defaultTimeout);

        // 2. 注册 provider（无论 enabled 与否都注册到管理表）
        sourceProviderMap.put(source.getDocUrl(), provider);

        // 3. disabled 时仅注册管理，不加入 categoryTools 和 allTools
        if (source.isEnabled() == false) {
            LOG.info("OpenAPI server '{}' is disabled, registered to management only", source.getDocUrl());
            return;
        }

        // 4. 将过滤后的工具同步到全局索引
        for (ApiTool tool : provider.getToolsActivated()) {
            String nameLower = tool.getName().toLowerCase();
            this.allTools.put(nameLower, tool);
            String cat = tool.getCategory();
            this.categoryTools.computeIfAbsent(cat, k -> new ConcurrentHashMap<>()).put(nameLower, tool);
        }
    }

    // ========== 查询（供 HarnessEngine 及前端使用） ==========

    /**
     * 按 docUrl 获取 ApiSourceClient
     */
    public ApiSourceClient getApiSource(String docUrl) {
        return sourceProviderMap.get(docUrl);
    }

    /**
     * 是否包含指定 docUrl 的 API 源
     */
    public boolean hasApiSource(String docUrl) {
        return sourceProviderMap.containsKey(docUrl);
    }

    // ========== 刷新（权限变更后重新加载） ==========

    /**
     * 刷新指定 API 源（allowedTools/disallowedTools 变更后调用）
     *
     * <p>采用影子交换策略（参照 McpGatewayTalent.refreshMcpServer）
     * <p>如果是权限变更场景，可传 reloadDefinition=false，仅重新过滤即可；
     * <p>如果需要重新拉取远程文档，可传 reloadDefinition=true
     */
    public OpenApiGatewayTalent refreshApi(String docUrl) {
        return refreshApi(docUrl, false);
    }

    /**
     * 刷新指定 API 源
     *
     * @param docUrl            API 定义地址
     * @param reloadDefinition  是否重新从远程拉取定义文档（false=仅重新过滤权限）
     */
    public OpenApiGatewayTalent refreshApi(String docUrl, boolean reloadDefinition) {
        if (Utils.isEmpty(docUrl)) {
            return this;
        }

        ApiSourceClient provider = sourceProviderMap.get(docUrl);
        if (provider == null) {
            return this;
        }

        // disabled: 只从工具索引移除，保留 provider（不关闭连接）
        if (provider.getSource().isEnabled() == false) {
            removeToolsFromGlobalIndex(docUrl);
            return this;
        }

        if (reloadDefinition) {
            // 完全刷新：强制重新加载远程文档，再同步到全局索引
            removeToolsFromGlobalIndex(docUrl);
            try {
                provider.reloadApi();
                syncToolsToGlobalIndex(provider);
            } catch (Throwable e) {
                // 与 addApi 一致：单个源的解析失败（含 Error）不应打断整个网关
                LOG.error("OpenApiTalent: Failed to refresh API from {}", docUrl, e);
            }
        } else {
            // 权限刷新：rawTools 缓存未变，只需从全局索引移除旧工具，再用新的权限过滤重新同步
            removeToolsFromGlobalIndex(docUrl);
            syncToolsToGlobalIndex(provider);
        }

        return this;
    }

    /**
     * 刷新所有 API 源
     */
    public OpenApiGatewayTalent refreshApiAll() {
        for (String docUrl : new ArrayList<>(sourceProviderMap.keySet())) {
            refreshApi(docUrl);
        }
        return this;
    }

    // ========== 私有辅助 ==========

    /**
     * 从全局索引中移除指定 docUrl 的工具（不移除 provider）
     */
    private void removeToolsFromGlobalIndex(String docUrl) {
        List<String> removedNames = new ArrayList<>();
        for (Map.Entry<String, ApiTool> entry : allTools.entrySet()) {
            ApiTool tool = entry.getValue();
            if (tool.getSource() != null && docUrl.equals(tool.getSource().getDocUrl())) {
                removedNames.add(entry.getKey());
            }
        }
        for (String name : removedNames) {
            allTools.remove(name);
        }
        for (Map.Entry<String, Map<String, ApiTool>> catEntry : categoryTools.entrySet()) {
            Map<String, ApiTool> catTools = catEntry.getValue();
            catTools.keySet().removeAll(removedNames);
            if (catTools.isEmpty()) {
                categoryTools.remove(catEntry.getKey());
            }
        }
        if (!removedNames.isEmpty()) {
            LOG.info("OpenApiTalent: Removed {} tools from global index for {}", removedNames.size(), docUrl);
        }
    }

    /**
     * 将 provider 过滤后的工具同步到全局索引
     */
    private void syncToolsToGlobalIndex(ApiSourceClient provider) {
        for (ApiTool tool : provider.getToolsActivated()) {
            String nameLower = tool.getName().toLowerCase();
            this.allTools.put(nameLower, tool);
            String cat = tool.getCategory();
            this.categoryTools.computeIfAbsent(cat, k -> new ConcurrentHashMap<>()).put(nameLower, tool);
        }
    }

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
