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
package org.noear.solon.ai.skills.openapi;

import org.noear.snack4.ONode;
import org.noear.solon.Utils;
import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.chat.skill.AbsSkill;
import org.noear.solon.ai.chat.tool.FunctionTool;
import org.noear.solon.annotation.Param;
import org.noear.solon.lang.Preview;
import org.noear.solon.net.http.HttpUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 智能 OpenAPI 调用技能
 *
 * @author noear
 * @since 3.9.1
 */
@Preview("3.9.1")
public class OpenApiSkill extends AbsSkill {
    private static final Logger log = LoggerFactory.getLogger(OpenApiSkill.class);

    private final String openApiUrl;
    private final String apiBaseUrl;
    private List<ApiTool> dynamicTools;
    private ApiAuthenticator authenticator;

    private ONode rootSchema; // 保存根节点用于 JSONPath 解析
    private SchemaMode schemaMode = SchemaMode.FULL;
    private int maxContextLength = 8000;

    public OpenApiSkill(String openApiUrl, String apiBaseUrl) {
        this.openApiUrl = openApiUrl;
        this.apiBaseUrl = apiBaseUrl;
    }

    public OpenApiSkill schemaMode(SchemaMode mode) {
        this.schemaMode = mode;
        return this;
    }

    public OpenApiSkill maxContextLength(int length) {
        this.maxContextLength = length;
        return this;
    }

    public OpenApiSkill authenticator(ApiAuthenticator authenticator) {
        this.authenticator = authenticator;
        return this;
    }

    private void init() {
        if (dynamicTools != null) {
            return;
        }

        Utils.locker().lock();
        try {
            if (dynamicTools != null) {
                return;
            }

            // 1. 加载 (支持初始化鉴权)
            HttpUtils http = HttpUtils.http(openApiUrl);
            if (authenticator != null) {
                authenticator.apply(http, null);
            }

            String json = http.get();
            this.rootSchema = ONode.ofJson(json);

            // 2. 获取解析器并解析
            OpenApiParser parser = OpenApiParserFactory.create(rootSchema);
            log.info("OpenApiSkill: Using {} for {}", parser.getName(), openApiUrl);

            List<ApiTool> allTools = parser.parse(this, rootSchema);

            // 3. 过滤废弃接口并赋值（保证原子性）
            this.dynamicTools = allTools.stream()
                    .filter(t -> !t.isDeprecated())
                    .collect(Collectors.toList());

            // 4. 根据接口规模决定运行模式
            this.schemaMode = dynamicTools.size() > 30 ? SchemaMode.DYNAMIC : SchemaMode.FULL;
        } catch (Exception e) {
            log.error("OpenAPI schema loading failed: {}", openApiUrl, e);
            this.schemaMode = SchemaMode.DYNAMIC;
            this.dynamicTools = new ArrayList<>();
        } finally {
            Utils.locker().unlock();
        }
    }

    protected String resolveRef(ONode node) {
        return resolveRefNode(node, new HashSet<>()).toJson();
    }

    protected ONode resolveRefNode(ONode node, Set<String> visited) {
        if (node == null || node.isNull()) return new ONode();

        if (node.hasKey("$ref")) {
            String ref = node.get("$ref").getString();
            if (visited.contains(ref)) {
                return ONode.ofBean("_Circular_Reference_");
            }
            visited.add(ref);

            String jsonPath = ref.replace("#/", "$.").replace("/", ".");
            ONode refNode = rootSchema.select(jsonPath);
            return resolveRefNode(refNode, visited);
        }

        if (node.isObject()) {
            ONode cleanNode = new ONode().asObject();
            node.getObjectUnsafe().forEach((k, v) -> {
                if ("type".equals(k) || "properties".equals(k) || "items".equals(k) ||
                        "required".equals(k) || "description".equals(k) || "enum".equals(k)) {
                    if ("properties".equals(k)) {
                        ONode props = cleanNode.get("properties");
                        v.getObjectUnsafe().forEach((pk, pv) -> props.set(pk, resolveRefNode(pv, new HashSet<>(visited))));
                    } else if ("items".equals(k)) {
                        cleanNode.set("items", resolveRefNode(v, new HashSet<>(visited)));
                    } else {
                        cleanNode.set(k, v);
                    }
                }
            });
            return cleanNode;
        } else if (node.isArray()) {
            ONode cleanArray = new ONode().asArray();
            node.getArrayUnsafe().forEach(n -> cleanArray.add(resolveRefNode(n, new HashSet<>(visited))));
            return cleanArray;
        }

        return node;
    }

    @Override
    public String name() {
        return "api_expert";
    }

    @Override
    public String description() {
        return "业务 API 专家：支持 REST 接口精准调用，能够自动解析复杂的模型嵌套。";
    }

    @Override
    public void onAttach(Prompt prompt) {
        init();
    }

    @Override
    public String getInstruction(Prompt prompt) {
        StringBuilder sb = new StringBuilder();
        sb.append("##### 1. API 环境上下文\n")
                .append("- **Base URL**: ").append(apiBaseUrl).append("\n\n");

        if (schemaMode == SchemaMode.FULL) {
            sb.append("##### 2. 接口详细定义 (API Specs)\n").append(formatApiDocs(dynamicTools));
        } else {
            sb.append("##### 2. 接口清单 (API List)\n")
                    .append("接口较多。**调用前必须通过 `get_api_detail` 确认具体的 Schema 定义**：\n\n");

            for (ApiTool t : dynamicTools) {
                sb.append("- **").append(t.getName()).append("**: ").append(t.getDescription())
                        .append(" (").append(t.getMethod()).append(" ").append(t.getPath()).append(")\n");
            }
        }

        sb.append("\n##### 3. API 调用准则\n")
                .append("1. **精准定位**: 优先根据 Summary 和 Path 锁定 API。\n")
                .append("2. **参数构建**: Path 参数直接替换 URL；Query/Body 放入 query_or_body_params。JSON 结构必须严格符合 Schema。\n")
                .append("3. **响应处理**: 默认返回 JSON 字符串。若结果过长将被截断，请关注 truncated 标记。");

        return sb.toString();
    }

    @Override
    public Collection<FunctionTool> getTools(Prompt prompt) {
        if (schemaMode == SchemaMode.FULL) {
            return tools.stream().filter(t -> "call_api".equals(t.name())).collect(Collectors.toList());
        }
        return super.getTools(prompt);
    }

    @ToolMapping(name = "get_api_detail", description = "获取特定 API 的参数 Schema 和返回值定义（含模型深度展开）")
    public String getApiDetail(@Param("api_name") String apiName) {
        return dynamicTools.stream()
                .filter(t -> t.getName().equalsIgnoreCase(apiName))
                .map(t -> formatApiDocs(Collections.singletonList(t)))
                .findFirst()
                .orElse("Error: API '" + apiName + "' not found.");
    }

    @ToolMapping(name = "call_api", description = "执行 REST 接口请求")
    public String callApi(
            @Param("api_name") String apiName,
            @Param("path_params") Map<String, Object> pathParams,
            @Param("query_or_body_params") Map<String, Object> dataParams) throws IOException {

        ApiTool tool = dynamicTools.stream()
                .filter(t -> t.getName().equalsIgnoreCase(apiName))
                .findFirst()
                .orElse(null);

        if (tool == null) return "Error: API [" + apiName + "] not found.";

        String finalPath = tool.getPath();
        if (pathParams != null) {
            for (Map.Entry<String, Object> entry : pathParams.entrySet()) {
                finalPath = finalPath.replace("{" + entry.getKey() + "}", String.valueOf(entry.getValue()));
            }
        }

        HttpUtils http = HttpUtils.http(apiBaseUrl + finalPath);
        if (authenticator != null) {
            authenticator.apply(http, tool);
        }

        if ("GET".equalsIgnoreCase(tool.getMethod())) {
            http.data(dataParams);
        } else {
            http.bodyJson(ONode.serialize(dataParams));
        }

        try {
            String result = http.exec(tool.getMethod()).bodyAsString();
            if (result.length() > maxContextLength) {
                return result.substring(0, maxContextLength) + "... [Data truncated]";
            }
            return Utils.isEmpty(result) ? "Success: No content returned." : result;
        } catch (Exception e) {
            log.warn("API 调用失败: {} {}", tool.getName(), e.getMessage());
            return "HTTP Execution Error: " + e.getMessage();
        }
    }

    private String formatApiDocs(List<ApiTool> tools) {
        StringBuilder sb = new StringBuilder();
        for (ApiTool tool : tools) {
            sb.append("* **API: ").append(tool.getName()).append("**\n")
                    .append("  - Description: ").append(tool.getDescription()).append("\n")
                    .append("  - Endpoint: ").append(tool.getMethod()).append(" ").append(tool.getPath()).append("\n")
                    .append("  - Request Schema: ").append(tool.getInputSchema()).append("\n")
                    .append("  - Response Schema: ").append(tool.getOutputSchema()).append("\n");
        }
        return sb.toString();
    }

    // ==========================================
    // OpenAPI 解析适配体系
    // ==========================================

    public interface OpenApiParser {
        String getName();
        List<ApiTool> parse(OpenApiSkill skill, ONode root);
    }

    static class OpenApiParserFactory {
        static OpenApiParser create(ONode root) {
            if (root.hasKey("openapi") && root.get("openapi").getString().startsWith("3.")) {
                return new OpenApiV3Parser();
            }
            return new OpenApiV2Parser();
        }
    }

    static class OpenApiV2Parser implements OpenApiParser {
        public String getName() { return "Swagger 2.0 Parser"; }

        public List<ApiTool> parse(OpenApiSkill skill, ONode root) {
            List<ApiTool> tools = new ArrayList<>();
            root.get("paths").getObject().forEach((path, methods) -> {
                methods.getObject().forEach((method, detail) -> {
                    if (isValidMethod(method)) {
                        ApiTool tool = new ApiTool();
                        tool.setPath(path);
                        tool.setMethod(method.toUpperCase());
                        tool.setName(generateName(detail, tool.getMethod(), path));
                        tool.setDescription(extractDescription(detail));
                        tool.setInputSchema(skill.resolveRef(detail.get("parameters")));
                        tool.setOutputSchema(skill.resolveRef(detail.get("responses").get("200").get("schema")));
                        tool.setDeprecated(detail.get("deprecated").getBoolean());
                        tools.add(tool);
                    }
                });
            });
            return tools;
        }
    }

    static class OpenApiV3Parser implements OpenApiParser {
        public String getName() { return "OpenAPI 3.0 Parser"; }

        public List<ApiTool> parse(OpenApiSkill skill, ONode root) {
            List<ApiTool> tools = new ArrayList<>();
            root.get("paths").getObject().forEach((path, methods) -> {
                methods.getObject().forEach((method, detail) -> {
                    if (isValidMethod(method)) {
                        ApiTool tool = new ApiTool();
                        tool.setPath(path);
                        tool.setMethod(method.toUpperCase());
                        tool.setName(generateName(detail, tool.getMethod(), path));
                        tool.setDescription(extractDescription(detail));

                        String params = skill.resolveRef(detail.get("parameters"));
                        StringBuilder input = new StringBuilder();
                        if (!"[]".equals(params) && !"{}".equals(params) && !Utils.isEmpty(params)) {
                            input.append(params);
                        }

                        if (detail.hasKey("requestBody")) {
                            ONode content = detail.get("requestBody").get("content");
                            ONode bodySchema = content.get("application/json").get("schema");
                            if (bodySchema.isNull() && content.size() > 0) {
                                bodySchema = content.get(0).get("schema");
                            }
                            if (!bodySchema.isNull()) {
                                if (input.length() > 0) input.append(" | ");
                                input.append("JSON Body: ").append(skill.resolveRef(bodySchema));
                            }
                        }
                        tool.setInputSchema(input.toString());

                        ONode resp200 = detail.get("responses").get("200");
                        ONode outSchemaNode = resp200.get("content").get("application/json").get("schema");
                        if (outSchemaNode.isNull() && resp200.get("content").size() > 0) {
                            outSchemaNode = resp200.get("content").get(0).get("schema");
                        }
                        tool.setOutputSchema(skill.resolveRef(outSchemaNode));
                        tool.setDeprecated(detail.get("deprecated").getBoolean());
                        tools.add(tool);
                    }
                });
            });
            return tools;
        }
    }

    private static boolean isValidMethod(String method) {
        return !method.startsWith("x-") && !"options".equalsIgnoreCase(method);
    }

    private static String generateName(ONode detail, String method, String path) {
        String opId = detail.get("operationId").getString();
        if (Utils.isNotEmpty(opId)) return opId;
        return (method + "_" + path.replaceAll("[^a-zA-Z0-9]", "_")).replaceAll("_+", "_").toLowerCase();
    }

    private static String extractDescription(ONode detail) {
        return Utils.valueOr(detail.get("summary").getString(), detail.get("description").getString(), "No summary available");
    }
}