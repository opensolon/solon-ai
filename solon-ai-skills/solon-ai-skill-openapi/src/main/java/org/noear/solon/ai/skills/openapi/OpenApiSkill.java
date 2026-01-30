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

    // 提供给 ApiTool 使用的解引用方法
    protected String resolveRef(ONode node) {
        if (node == null || node.isNull()) return "{}";

        // 如果包含引用
        if (node.hasKey("$ref")) {
            String ref = node.get("$ref").getString();
            // 将 #/components/schemas/User 转换为 $.components.schemas.User
            String jsonPath = ref.replace("#/", "$.").replace("/", ".");
            ONode refNode = rootSchema.select(jsonPath);
            if (!refNode.isNull()) {
                return refNode.toJson();
            }
        }

        // 如果是普通的 parameters 数组，尝试深度检查其内部的 schema 引用
        if (node.isArray()) {
            node.getArrayUnsafe().forEach(n -> {
                if (n.hasKey("schema") && n.get("schema").hasKey("$ref")) {
                    String ref = n.get("schema").get("$ref").getString();
                    ONode refNode = rootSchema.select(ref.replace("#/", "$.").replace("/", "."));
                    if (!refNode.isNull()) n.set("schema", refNode);
                }
            });
        }

        return node.toJson();
    }

    public OpenApiSkill schemaMode(SchemaMode mode) { this.schemaMode = mode; return this; }
    public OpenApiSkill maxContextLength(int length) { this.maxContextLength = length; return this; }

    @Override
    public String name() { return "api_expert"; }

    @Override
    public String description() {
        return "业务 API 专家：支持 REST 接口调用，自动解析 OpenAPI 2.0/3.0 模型引用。";
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
                    .append("接口较多，编写请求前请通过 `get_api_detail` 确认具体的字段 Schema：\n\n");

            for (ApiTool t : dynamicTools) {
                sb.append("- **").append(t.name).append("**: ").append(t.description).append("\n");
            }
        }

        sb.append("\n\n##### 3. API 调用准则\n")
                .append("1. **参数构造**: Path 参数映射 URL；Query/Body 参数放入 query_or_body_params。\n")
                .append("2. **类型检查**: 严格遵循 Schema 定义的数据类型。\n")
                .append("3. **探测**: 若结构含模糊引用，必须先执行 `get_api_detail`。");

        return sb.toString();
    }

    @Override
    public Collection<FunctionTool> getTools(Prompt prompt) {
        if (schemaMode == SchemaMode.FULL) {
            return tools.stream().filter(t -> "call_api".equals(t.name())).collect(Collectors.toList());
        }
        return super.getTools(prompt);
    }

    @ToolMapping(name = "get_api_detail", description = "获取特定 API 的参数 Schema 和返回值定义（含模型展开）")
    public String getApiDetail(@Param("api_name") String apiName) {
        return dynamicTools.stream()
                .filter(t -> t.name.equals(apiName))
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
                .filter(t -> t.name.equals(apiName))
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
            return result.length() > maxContextLength ? result.substring(0, maxContextLength) + "... [Truncated]" : result;
        } catch (Exception e) {
            return "HTTP Error: " + e.getMessage();
        }
    }

    private String formatApiDocs(List<ApiTool> tools) {
        StringBuilder sb = new StringBuilder();
        for (ApiTool tool : tools) {
            sb.append("* **API: ").append(tool.name).append("**\n")
                    .append("  - Summary: ").append(tool.description).append("\n")
                    .append("  - Endpoint: ").append(tool.method).append(" ").append(tool.path).append("\n")
                    .append("  - Input: ").append(tool.inputSchema).append("\n")
                    .append("  - Output: ").append(tool.outputSchema).append("\n");
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
            this.name = (this.method + "_" + path.replaceAll("[^a-zA-Z0-9]", "_"))
                    .replaceAll("_+", "_").toLowerCase();

            this.description = Utils.valueOr(detail.get("summary").getString(),
                    detail.get("description").getString(), "路径: " + path);

            // 1. 入参处理（带解引用）
            this.inputSchema = skill.resolveRef(detail.get("parameters"));
            if (detail.hasKey("requestBody")) {
                ONode content = detail.get("requestBody").get("content");
                ONode bodySchema = content.get("application/json").get("schema");
                if (bodySchema.isNull() && content.size() > 0) {
                    bodySchema = content.get(0).get("schema");
                }
                if (!bodySchema.isNull()) {
                    this.inputSchema += " | Body: " + skill.resolveRef(bodySchema);
                }
            }

            // 2. 出参处理（带解引用）
            ONode resp200 = detail.get("responses").get("200");
            ONode outSchemaNode = resp200.get("content").get("application/json").get("schema");
            if (outSchemaNode.isNull()) {
                outSchemaNode = resp200.get("schema");
            }
            this.outputSchema = skill.resolveRef(outSchemaNode);
        }
    }
}