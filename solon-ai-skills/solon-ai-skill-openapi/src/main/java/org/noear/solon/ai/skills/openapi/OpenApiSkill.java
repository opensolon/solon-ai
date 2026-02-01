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
 * 智能 OpenAPI 调用技能（支持 2.0/3.0 自适应及 $ref 模型解析）
 *
 * @author noear
 * @since 3.9.1
 */
@Preview("3.9.1")
public class OpenApiSkill extends AbsSkill {
    private static final Logger log = LoggerFactory.getLogger(OpenApiSkill.class);

    private final String openApiUrl;
    private final String apiBaseUrl;
    private final List<ApiTool> dynamicTools = new ArrayList<>();

    private ONode rootSchema; // 保存根节点用于 JSONPath 解析
    private SchemaMode schemaMode = SchemaMode.FULL;
    private int maxContextLength = 8000;

    public OpenApiSkill(String openApiUrl, String apiBaseUrl) {
        this.openApiUrl = openApiUrl;
        this.apiBaseUrl = apiBaseUrl;
        this.initTools();
    }

    private void initTools() {
        try {
            String json = HttpUtils.http(openApiUrl).get();
            this.rootSchema = ONode.ofJson(json);

            rootSchema.get("paths").getObject().forEach((path, methods) -> {
                methods.getObject().forEach((method, detail) -> {
                    if (!method.startsWith("x-") && !"options".equalsIgnoreCase(method)) {
                        dynamicTools.add(new ApiTool(this, path, method, detail));
                    }
                });
            });

            this.schemaMode = dynamicTools.size() > 30 ? SchemaMode.DYNAMIC : SchemaMode.FULL;
        } catch (Exception e) {
            log.error("OpenAPI schema loading failed: {}", openApiUrl, e);
            throw new RuntimeException("OpenAPI 初始化失败", e);
        }
    }

    /**
     * 优化点 1: 递归解引用与 Schema 瘦身
     * 剔除无关描述，只保留 AI 核心推理字段，防止多层引用断裂。
     */
    protected String resolveRef(ONode node) {
        return resolveRefNode(node, new HashSet<>()).toJson();
    }

    protected ONode resolveRefNode(ONode node, Set<String> visited) {
        if (node == null || node.isNull()) return new ONode();

        // 处理引用
        if (node.hasKey("$ref")) {
            String ref = node.get("$ref").getString();
            if (visited.contains(ref)) {
                return ONode.ofBean("_Circular_Reference_"); // 终止循环
            }
            visited.add(ref);

            String jsonPath = ref.replace("#/", "$.").replace("/", ".");
            ONode refNode = rootSchema.select(jsonPath);
            return resolveRefNode(refNode, visited);
        }

        // 瘦身与递归构建
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

    public OpenApiSkill schemaMode(SchemaMode mode) { this.schemaMode = mode; return this; }

    @Override
    public String name() { return "api_expert"; }

    @Override
    public String description() {
        return "业务 API 专家：支持 REST 接口精准调用，能够自动解析复杂的模型嵌套。";
    }

    @Override
    public String getInstruction(Prompt prompt) {
        StringBuilder sb = new StringBuilder();
        sb.append("##### 1. API 环境上下文\n")
                .append("- **Base URL**: ").append(apiBaseUrl).append("\n\n");

        if (schemaMode == SchemaMode.FULL) {
            sb.append("##### 2. 接口详细定义 (API Specs)\n").append(formatApiDocs(dynamicTools));
        } else {
            // 优化点 2: 增强型目录，包含 Method 和 Path，便于 AI 初步定位
            sb.append("##### 2. 接口清单 (API List)\n")
                    .append("接口较多。**调用前必须通过 `get_api_detail` 确认具体的 Schema 定义**：\n\n");

            for (ApiTool t : dynamicTools) {
                sb.append("- **").append(t.name).append("**: ").append(t.description)
                        .append(" (").append(t.method).append(" ").append(t.path).append(")\n");
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
                .filter(t -> t.name.equalsIgnoreCase(apiName))
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
                .filter(t -> t.name.equalsIgnoreCase(apiName))
                .findFirst()
                .orElse(null);

        if (tool == null) return "Error: API [" + apiName + "] not found.";

        String finalPath = tool.path;
        if (pathParams != null) {
            for (Map.Entry<String, Object> entry : pathParams.entrySet()) {
                finalPath = finalPath.replace("{" + entry.getKey() + "}", String.valueOf(entry.getValue()));
            }
        }

        HttpUtils http = HttpUtils.http(apiBaseUrl + finalPath);
        if ("GET".equalsIgnoreCase(tool.method)) {
            http.data(dataParams);
        } else {
            http.bodyJson(ONode.serialize(dataParams));
        }

        try {
            String result = http.exec(tool.method).bodyAsString();
            if (result.length() > maxContextLength) {
                return result.substring(0, maxContextLength) + "... [Data truncated for context safety]";
            }
            return result;
        } catch (Exception e) {
            log.warn("API 调用失败: {} {}", tool.name, e.getMessage());
            return "HTTP Execution Error: " + e.getMessage();
        }
    }

    private String formatApiDocs(List<ApiTool> tools) {
        StringBuilder sb = new StringBuilder();
        for (ApiTool tool : tools) {
            sb.append("* **API: ").append(tool.name).append("**\n")
                    .append("  - Description: ").append(tool.description).append("\n")
                    .append("  - Endpoint: ").append(tool.method).append(" ").append(tool.path).append("\n")
                    .append("  - Request Schema: ").append(tool.inputSchema).append("\n")
                    .append("  - Response Schema: ").append(tool.outputSchema).append("\n");
        }
        return sb.toString();
    }

    public static class ApiTool {
        public String name;
        public String description;
        public String path;
        public String method;
        public String inputSchema;
        public String outputSchema;

        public ApiTool(OpenApiSkill skill, String path, String method, ONode detail) {
            this.path = path;
            this.method = method.toUpperCase();

            // 优化点 3: 优先使用 operationId 作为唯一标识，增强模型认知一致性
            this.name = detail.get("operationId").getString();
            if (Utils.isEmpty(this.name)) {
                this.name = (this.method + "_" + path.replaceAll("[^a-zA-Z0-9]", "_"))
                        .replaceAll("_+", "_").toLowerCase();
            }

            this.description = Utils.valueOr(detail.get("summary").getString(),
                    detail.get("description").getString(), "No summary available");

            // 入参解析 (支持 Header/Path/Query 及 Body 深度展开)
            this.inputSchema = skill.resolveRef(detail.get("parameters"));
            if (detail.hasKey("requestBody")) {
                ONode content = detail.get("requestBody").get("content");
                // 默认取 application/json，兼容处理
                ONode bodySchema = content.get("application/json").get("schema");
                if (bodySchema.isNull() && content.size() > 0) {
                    bodySchema = content.get(0).get("schema");
                }
                if (!bodySchema.isNull()) {
                    this.inputSchema += " | JSON Body: " + skill.resolveRef(bodySchema);
                }
            }

            // 出参解析 (通常只解析 200 状态码)
            ONode resp200 = detail.get("responses").get("200");
            ONode outSchemaNode = resp200.get("content").get("application/json").get("schema");
            if (outSchemaNode.isNull()) {
                outSchemaNode = resp200.get("schema");
            }
            this.outputSchema = skill.resolveRef(outSchemaNode);
        }
    }
}