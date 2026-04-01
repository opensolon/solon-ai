/*
 * Copyright 2017-2025 noear.org and authors
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
package org.noear.solon.ai.skills.restapi;

import org.noear.snack4.ONode;
import org.noear.solon.Utils;
import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.chat.skill.AbsSkill;
import org.noear.solon.ai.chat.tool.FunctionTool;
import org.noear.solon.ai.skills.restapi.resolver.OpenApiResolver;
import org.noear.solon.annotation.Param;
import org.noear.solon.core.util.Assert;
import org.noear.solon.core.util.ResourceUtil;
import org.noear.solon.lang.Preview;
import org.noear.solon.net.http.HttpUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URLEncoder;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 智能 REST API 接入技能：支持分组与四阶段自动发现模式。
 *
 * 逻辑档位：
 * 1. FULL: 数量 <= dynamicThreshold。平铺所有 API 的完整定义。
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
public class RestApiSkill extends AbsSkill {
    private static final Logger log = LoggerFactory.getLogger(RestApiSkill.class);

    private final Map<String, Map<String, ApiTool>> categoryTools = new LinkedHashMap<>();
    private final Map<String, ApiTool> allTools = new LinkedHashMap<>();

    private ApiResolver resolver = OpenApiResolver.getInstance();
    private ApiAuthenticator defaultAuthenticator;

    private int dynamicThreshold = 8;
    private int listThreshold = 30;
    private int searchThreshold = 100;

    private int maxContextLength = 8000;

    // --- 配置方法 ---

    public RestApiSkill dynamicThreshold(int dynamicThreshold) {
        this.dynamicThreshold = dynamicThreshold;
        return this;
    }

    public RestApiSkill listThreshold(int listThreshold) {
        this.listThreshold = listThreshold;
        return this;
    }

    public RestApiSkill searchThreshold(int searchThreshold) {
        this.searchThreshold = searchThreshold;
        return this;
    }

    public RestApiSkill maxContextLength(int length) {
        this.maxContextLength = length;
        return this;
    }

    public RestApiSkill defaultAuthenticator(ApiAuthenticator defaultAuthenticator) {
        this.defaultAuthenticator = defaultAuthenticator;
        return this;
    }

    /**
     * @deprecated 3.9.6 {@link #defaultAuthenticator(ApiAuthenticator)}
     *
     */
    @Deprecated
    public RestApiSkill authenticator(ApiAuthenticator authenticator) {
        return defaultAuthenticator(authenticator);
    }

    public RestApiSkill resolver(ApiResolver resolver) {
        if (resolver != null) {
            this.resolver = resolver;
        }
        return this;
    }

    /**
     * 添加 API 组
     *
     * @param docUrl     OpenAPI 定义地址 (http://... 或 classpath:...)
     * @param apiBaseUrl 实际接口执行基地址
     */
    public RestApiSkill addApi(String docUrl, String apiBaseUrl) {
        return addApi(docUrl, apiBaseUrl, null);
    }

    /**
     * 添加 API 组
     *
     * @param docUrl     OpenAPI 定义地址 (http://... 或 classpath:...)
     * @param apiBaseUrl 实际接口执行基地址
     */
    public RestApiSkill addApi(String docUrl, String apiBaseUrl, Map<String, String> headers) {
        return addApi(docUrl, apiBaseUrl, headers, null);
    }

    /**
     * 添加 API 组
     *
     * @param docUrl     OpenAPI 定义地址 (http://... 或 classpath:...)
     * @param apiBaseUrl 实际接口执行基地址
     */
    public RestApiSkill addApi(String docUrl, String apiBaseUrl, Map<String, String> headers, ApiAuthenticator authenticator) {

        ApiSource source = new ApiSource();
        source.docUrl = docUrl;
        source.apiBaseUrl = apiBaseUrl;
        source.headers = headers;
        source.authenticator = authenticator;

        return addApi(source);
    }


    /**
     * 添加 API 组
     *
     * @param apiSource 接口源
     */
    public RestApiSkill addApi(ApiSource apiSource) {
        try {
            loadApiFromDefinition(apiSource);
            return this;
        } catch (Exception e) {
            throw new RuntimeException("Failed to load API from: " + apiSource.docUrl, e);
        }
    }


    @Override
    public String description() {
        return "业务 API 专家：能够整合并精准调用多个微服务的 REST 接口。";
    }

    @Override
    public String getInstruction(Prompt prompt) {
        if (allTools.isEmpty()) {
            return "## API 专家\n当前未配置任何业务接口。若用户提问涉及业务数据，请告知无法查询。";
        }

        final int size = allTools.size();
        StringBuilder sb = new StringBuilder();
        sb.append("## 业务 API 发现规范 (共 ").append(size).append(" 个接口)\n");

        if (size <= dynamicThreshold) {
            // --- 模式 1: FULL ---
            sb.append("### 运行模式: 直接调用\n");
            sb.append("当前已加载全量接口定义。请分析需求并直接调用 `call_api`。\n\n");
            sb.append("#### 接口详细定义 (API Specs):\n");
            sb.append(formatApiDocs(allTools.values()));

        } else if (size <= listThreshold) {
            // --- 模式 2: SUMMARY ---
            sb.append("由于业务接口库较多，已开启**动态路由**模式。请严格遵循发现流程：\n");

            sb.append("- **Step 1 (锁定)**: 从下方“可用业务接口清单”中根据功能描述选定接口名。\n");
            sb.append("- **Step 2 (详情)**: 调用 `get_api_detail` 获取参数 Schema。\n");
            sb.append("- **Step 3 (执行)**: 通过 `call_api` 执行。\n\n");

            sb.append("### 可用业务接口清单 (按业务分组摘要):\n");
            categoryTools.forEach((cat, tools) -> {
                sb.append("- **分组: [").append(cat).append("]**:\n");
                tools.values().forEach(t ->
                        sb.append("  - `").append(t.getName()).append("`: ").append(t.getDescription())
                                .append(" (").append(t.getMethod()).append(" ").append(t.getPath()).append(")\n")
                );
            });
        } else if (size <= searchThreshold) {
            // --- 模式 3: LIST ---
            sb.append("由于业务接口库较多，已开启**动态路由**模式。请严格遵循发现流程：\n");

            sb.append("- **Step 1 (预选)**: 从下方“接口列表”中推断功能，或用 `search_apis` 检索。\n");
            sb.append("- **Step 2 (详情)**: 调用 `get_api_detail` 获取参数 Schema。\n");
            sb.append("- **Step 3 (执行)**: 通过 `call_api` 执行。\n\n");

            sb.append("### 可用业务接口列表 (按业务分组展示):\n");
            categoryTools.forEach((cat, tools) -> {
                String names = String.join(", ", tools.keySet());
                sb.append("- **分组: ").append(cat).append("** -> [").append(names).append("]\n");
            });
            sb.append("\n> 提示：分组名和接口名具有语义参考价值，锁定目标后务必先查详情。");
        } else {
            // --- 模式 4: SEARCH ---
            sb.append("由于业务接口库较多，已开启**动态路由**模式。请严格遵循发现流程：\n");

            sb.append("- **Step 1 (搜索)**: 清单已折叠。必须先使用 `search_apis` 通过关键词寻找匹配的接口。**注意**: 搜索结果将按“业务分组”返回，请结合分组语义判断接口准确性。\n");
            sb.append("- **Step 2 (详情)**: 调用 `get_api_detail` 获取参数 Schema。\n");
            sb.append("- **Step 3 (执行)**: 通过 `call_api` 执行。\n\n");

            sb.append("> **注意**: 接口库规模巨大。建议搜索关键词，如：search_apis('订单 查询')。");
        }

        sb.append("\n\n## 执行约束\n")
                .append("1. **响应处理**: 如果返回数据带 `[Data truncated]` 标记，请基于现有部分分析，不要尝试重复调用获取全量。\n")
                .append("2. **失败重试**: 若接口报错，请检查参数是否符合 Step 2 获取的 Schema，不要盲目重试。");

        return sb.toString();
    }

    @Override
    public Collection<FunctionTool> getTools(Prompt prompt) {
        int size = allTools.size();

        if (size <= dynamicThreshold) {
            return getToolAry().stream().filter(t -> "call_api".equals(t.name())).collect(Collectors.toList());
        }

        if (size <= listThreshold) {
            return getToolAry().stream().filter(t -> !"search_apis".equals(t.name())).collect(Collectors.toList());
        }

        return getToolAry();
    }

    // --- 内置工具映射 ---

    @ToolMapping(name = "search_apis", description = "在海量 API 库中通过关键词模糊搜索。支持多个关键词用空格隔开（如：'订单 查询'）")
    public Object searchApis(@Param("keyword") String keyword) {
        if (Utils.isEmpty(keyword)) return "错误：搜索关键词不能为空。";

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
                    map.put("category", t.getCategory());
                    map.put("description", t.getDescription());
                    map.put("endpoint", t.getMethod() + " " + t.getPath());
                    return map;
                })
                .collect(Collectors.toList());

        if (results.isEmpty()) {
            return "提醒：未找到完全匹配关键词 '" + keyword + "' 的业务接口。\n" +
                    "您可以尝试：\n" +
                    "1. 检查关键词是否使用了空格分隔（如：'杭州 旅游'）。\n" +
                    "2. 换用更通用的词汇，或减少关键词数量重新搜索。\n" +
                    "3. 如果确定系统无此功能，请告知用户。禁止重复尝试相似关键词搜索。";
        }

        return results;
    }

    @ToolMapping(name = "get_api_detail", description = "获取特定 API 的参数 Schema 和返回值定义")
    public String getApiDetail(@Param("api_name") String apiName) {
        if (Utils.isEmpty(apiName)) return "错误：api_name 不能为空";

        ApiTool tool = allTools.get(apiName.trim().toLowerCase());

        if (tool != null) {
            return formatApiDocs(Collections.singletonList(tool));
        } else {
            return "错误: 未找到 API '" + apiName + "'。请先通过 search_apis 确认名称。";
        }
    }

    @ToolMapping(name = "call_api", description = "代理执行特定的 REST 业务接口")
    public String callApi(
            @Param("api_name") String apiName,
            @Param("header_params") Map<String, Object> headerParams,
            @Param("path_params") Map<String, Object> pathParams,
            @Param("query_params") Map<String, Object> queryParams,
            @Param("body_params") Map<String, Object> bodyParams) throws IOException {

        ApiTool tool = allTools.get(apiName.trim().toLowerCase());

        if (tool == null) {
            return "错误: 未找到名为 [" + apiName + "] 的 API。请先通过 'search_apis' 确认正确的名称。";
        }

        String baseUrl = tool.getBaseUrl();
        String finalPath = tool.getPath();

        // 1. 路径参数替换 (Path Parameters)
        if (Assert.isNotEmpty(pathParams)) {
            for (Map.Entry<String, Object> entry : pathParams.entrySet()) {
                Object value = entry.getValue();
                if (value == null) continue;

                String valStr = String.valueOf(value);
                try {
                    // 必须进行 URL 编码，防止路径参数中包含特殊字符或中文
                    String encodedVal = URLEncoder.encode(valStr, "UTF-8");
                    finalPath = finalPath.replace("{" + entry.getKey() + "}", encodedVal);
                } catch (java.io.UnsupportedEncodingException e) {
                    finalPath = finalPath.replace("{" + entry.getKey() + "}", valStr);
                }
            }
        }

        // 如果 finalPath 依然包含 {xxx}，说明 AI 漏传了 pathSchema 中定义的必填路径参数
        if (finalPath.contains("{") && finalPath.contains("}")) {
            return "执行失败: 缺少必要的路径参数。当前路径仍存在占位符: " + finalPath +
                    "。请检查 'path_params' 是否提供了所有必需的变量。";
        }

        // 构建请求对象
        HttpUtils http = HttpUtils.http(baseUrl + finalPath);

        // 2. Header 参数设置
        if (Assert.isNotEmpty(headerParams)) {
            http.headers(headerParams);
        }

        // 3. 认证处理

        if (tool.getSource() != null && Assert.isNotEmpty(tool.getSource().headers)) {
            http.headers(tool.getSource().headers);
        }

        if (tool.getSource() != null && tool.getSource().authenticator != null) {
            tool.getSource().authenticator.apply(http, tool);
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
            log.debug("RestApiSkill calling: {} {} (API: {})", tool.getMethod(), baseUrl + finalPath, apiName);

            String result = http.exec(tool.getMethod()).bodyAsString();

            // 结果截断处理，防止撑爆 AI 上下文
            if (result != null && result.length() > maxContextLength) {
                return result.substring(0, maxContextLength) + "... [Data truncated]";
            }

            return Utils.isEmpty(result) ? "Success: API executed, but returned an empty response." : result;
        } catch (Exception e) {
            log.warn("API Call Failed: {} - {}", tool.getName(), e.getMessage());
            String errorMsg = e.getMessage() != null ? e.getMessage() : "远程服务未响应";
            return "接口执行异常: " + errorMsg + " (请检查服务可用性或参数正确性)";
        }
    }

    // --- 私有辅助 ---

    private void loadApiFromDefinition(ApiSource source) throws IOException {
        final String json;

        if (source.docUrl.startsWith("http://") || source.docUrl.startsWith("https://")) {
            HttpUtils http = HttpUtils.http(source.docUrl);

            if (Assert.isNotEmpty(source.headers)) {
                http.headers(source.headers);
            }

            if (source.authenticator != null) {
                source.authenticator.apply(http, null);
            } else {
                if (defaultAuthenticator != null) {
                    defaultAuthenticator.apply(http, null);
                }
            }

            json = http.get();
        } else {
            json = ResourceUtil.findResourceAsString(source.docUrl);
        }

        if (Utils.isEmpty(json)) {
            log.warn("RestApiSkill: Source empty for {}", source.docUrl);
            return;
        }

        List<ApiTool> tools = resolver.resolve(source.docUrl, json);
        for (ApiTool tool : tools) {
            if (!tool.isDeprecated()) {
                tool.setBaseUrl(source.apiBaseUrl);
                tool.setSource(source);

                String nameLower = tool.getName().toLowerCase();

                this.allTools.put(nameLower, tool);

                String cat = tool.getCategory();
                this.categoryTools.computeIfAbsent(cat, k -> new LinkedHashMap<>()).put(nameLower, tool);
            }
        }

        log.info("RestApiSkill: Loaded {} tools from {}", tools.size(), source.docUrl);
    }

    private String formatApiDocs(Collection<ApiTool> tools) {
        StringBuilder sb = new StringBuilder();
        for (ApiTool tool : tools) {
            sb.append("---\n").append("* **API: ").append(tool.getName()).append("**\n")
                    .append("  - 业务分组: ").append(String.join(", ", tool.getTags())).append("\n")
                    .append("  - 功能: ").append(tool.getDescription()).append("\n")
                    .append("  - 路径: ").append(tool.getMethod()).append(" ").append(tool.getPath()).append("\n");

            if (Utils.isNotEmpty(tool.getHeaderSchema())) {
                sb.append("  - Header 参数: ").append(tool.getHeaderSchema()).append("\n");
            }

            if (Utils.isNotEmpty(tool.getPathSchema())) {
                sb.append("  - Path 参数 (填充路径 {}): ").append(tool.getPathSchema()).append("\n");
            }

            // 分开展示 Query 和 Body
            if (Utils.isNotEmpty(tool.getQuerySchema())) {
                sb.append("  - Query 参数 (URL): ").append(tool.getQuerySchema()).append("\n");
            }

            if (Utils.isNotEmpty(tool.getBodySchema())) {
                String label = tool.isMultipart() ? "Body 参数 (Multipart/Form)" : "Body 参数 (JSON)";
                sb.append("  - ").append(label).append(": ").append(tool.getBodySchema()).append("\n");
            }

            sb.append("  - 返回结果: ").append(tool.getOutputSchemaOr("{}")).append("\n");
        }
        return sb.toString();
    }
}