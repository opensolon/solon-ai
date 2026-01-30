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
 * 智能 OpenAPI 调用技能（支持全动词交互）
 *
 * @author noear
 * @since 3.9.1
 */
public class OpenApiSkill extends AbsSkill {
    private static final Logger log = LoggerFactory.getLogger(OpenApiSkill.class);

    private final String openApiUrl;
    private final String apiBaseUrl;
    private final List<ApiTool> dynamicTools = new ArrayList<>();

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
            ONode schema = ONode.ofJson(json);

            schema.get("paths").getObject().forEach((path, methods) -> {
                methods.getObject().forEach((method, detail) -> {
                    if (!method.startsWith("x-") && !"options".equalsIgnoreCase(method)) {
                        dynamicTools.add(new ApiTool(path, method, detail));
                    }
                });
            });
            // 接口超过 30 个自动切换为动态模式
            this.schemaMode = dynamicTools.size() > 30 ? SchemaMode.DYNAMIC : SchemaMode.FULL;
        } catch (Exception e) {
            log.error("OpenAPI schema loading failed: {}", openApiUrl, e);
            throw new RuntimeException("OpenAPI 初始化失败", e);
        }
    }

    public OpenApiSkill schemaMode(SchemaMode mode) { this.schemaMode = mode; return this; }
    public OpenApiSkill maxContextLength(int length) { this.maxContextLength = length; return this; }

    @Override
    public String name() { return "api_expert"; }

    @Override
    public String description() {
        return "业务 API 专家：支持多维度的 REST 接口调用，能够根据业务逻辑组合 API 请求。";
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
                    .append("当前库接口较多，调用前请根据业务需求匹配接口名。**若不确定参数结构，请先通过 `get_api_detail` 获取详情**：\n\n");

            for (ApiTool t : dynamicTools) {
                sb.append("- **").append(t.name).append("**: ").append(t.description).append("\n");
            }
        }

        sb.append("\n\n##### 3. API 调用准则\n")
                .append("1. **参数构造**: \n")
                .append("   - 路径参数 (path_params) 映射 URL 中的占位符；Query/Body 参数放入 query_or_body_params。\n")
                .append("   - 严格遵循 Schema 定义的数据类型（如 Integer vs String）。\n")
                .append("2. **操作逻辑**: \n")
                .append("   - 对于增删改操作，优先通过返回的状态码解析结果。若返回 404，通常意味着目标资源不存在。\n")
                .append("3. **异常处理**: \n")
                .append("   - 若接口返回报错，请分析响应体中的 error 信息。如因参数缺失，可结合 `get_api_detail` 修正后再次尝试。\n")
                .append("4. **约束**: 仅支持调用目录中定义的接口，严禁编造或猜测。");

        return sb.toString();
    }

    @Override
    public Collection<FunctionTool> getTools(Prompt prompt) {
        if (schemaMode == SchemaMode.FULL) {
            // FULL 模式下屏蔽探测工具，减少干扰
            return tools.stream().filter(t -> "call_api".equals(t.name())).collect(Collectors.toList());
        }
        return super.getTools(prompt);
    }

    @ToolMapping(name = "get_api_detail", description = "获取特定 API 的参数 Schema 和返回值模型定义")
    public String getApiDetail(@Param("api_name") String apiName) {
        return dynamicTools.stream()
                .filter(t -> t.name.equals(apiName))
                .map(t -> formatApiDocs(Collections.singletonList(t)))
                .findFirst()
                .orElse("Error: API '" + apiName + "' not found.");
    }

    @ToolMapping(name = "call_api", description = "根据规范执行 REST 接口请求")
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

        public ApiTool(String path, String method, ONode detail) {
            this.path = path;
            this.method = method.toUpperCase();
            // 生成符合 AI 习惯的名称: post_v1_order_create
            this.name = (this.method + "_" + path.replaceAll("[^a-zA-Z0-9]", "_"))
                    .replaceAll("_+", "_").toLowerCase();

            // 描述：summary > description > path
            String summary = detail.get("summary").getString();
            String desc = detail.get("description").getString();
            this.description = Utils.valueOr(summary, desc, "接口路径: " + path);

            // 架构 Schema，只取核心结构，移除 example 等干扰信息
            this.inputSchema = cleanSchema(detail.get("parameters"));
            if (detail.hasKey("requestBody")) {
                this.inputSchema += " | Body: " + cleanSchema(detail.get("requestBody"));
            }
            this.outputSchema = cleanSchema(detail.get("responses").get("200"));
        }

        private String cleanSchema(ONode node) {
            if (node == null || node.isNull()) return "{}";
            // 实际应用中这里可以写一个简单的递归过滤器，只保留 type, properties, required
            return node.toJson();
        }
    }
}