package org.noear.solon.ai.skills.openapi;

import org.noear.snack4.ONode;
import org.noear.solon.Utils;
import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.chat.skill.AbsSkill;
import org.noear.solon.annotation.Param;
import org.noear.solon.net.http.HttpUtils;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 智能 OpenAPI 调用技能
 */
public class OpenApiSkill extends AbsSkill {
    private final String openApiUrl;
    private final String apiBaseUrl;
    private final List<ApiTool> dynamicTools = new ArrayList<>();
    private String cachedApiDocs;

    public OpenApiSkill(String openApiUrl, String apiBaseUrl) {
        this.openApiUrl = openApiUrl;
        this.apiBaseUrl = apiBaseUrl;
        this.initTools();
    }

    private void initTools() {
        try {
            String json = HttpUtils.http(openApiUrl).get();
            ONode schema = ONode.ofJson(json);
            StringBuilder sb = new StringBuilder();

            schema.get("paths").getObject().forEach((path, methods) -> {
                methods.getObject().forEach((method, detail) -> {
                    ApiTool tool = new ApiTool(path, method, detail);
                    dynamicTools.add(new ApiTool(path, method, detail));

                    // 构造给 AI 读的接口文档
                    sb.append("### API: ").append(tool.name).append("\n")
                            .append("- **Description**: ").append(tool.description).append("\n")
                            .append("- **Endpoint**: ").append(tool.method).append(" ").append(tool.path).append("\n")
                            .append("- **Input Schema**: ").append(tool.inputSchema).append("\n")
                            .append("- **Output Schema**: ").append(tool.outputSchema).append("\n\n");
                });
            });
            this.cachedApiDocs = sb.toString();
        } catch (Exception e) {
            throw new RuntimeException("OpenAPI 初始化失败", e);
        }
    }

    @Override
    public String name() { return "api_expert"; }

    @Override
    public String description() {
        return "API 业务专家：能够通过 REST 接口与业务系统交互，支持深度参数构造与结果解析。";
    }

    @Override
    public String getInstruction(Prompt prompt) {
        return "##### 1. 可用接口定义 (API Specs)\n" + cachedApiDocs +
                "##### 2. 调用准则\n" +
                "1. **参数匹配**: 严格按照 Input Schema 构造参数，路径参数（如 {id}）必须通过 call_api 工具的 path_params 传入。\n" +
                "2. **结果分析**: 参考 Output Schema 解析返回的 JSON，若接口报错，根据错误信息调整参数重试。\n" +
                "3. **安全限制**: 严禁在没有上下文授权的情况下尝试调用敏感写接口。";
    }

    /**
     * 统一的工具映射方法
     * AI 将通过这个“单点”工具，根据 getInstruction 里的接口定义发起调用
     */
    @ToolMapping(name = "call_api", description = "根据接口定义发起 API 调用")
    public String callApi(
            @Param("api_name") String apiName,
            @Param("path_params") Map<String, Object> pathParams,
            @Param("query_or_body_params") Map<String, Object> dataParams) throws IOException {

        ApiTool tool = dynamicTools.stream()
                .filter(t -> t.name.equals(apiName))
                .findFirst()
                .orElse(null);

        if (tool == null) return "Error: 接口 [" + apiName + "] 不存在。";

        // 1. 路径参数替换 /order/{id} -> /order/123
        String finalPath = tool.path;
        if (pathParams != null) {
            for (Map.Entry<String, Object> entry : pathParams.entrySet()) {
                finalPath = finalPath.replace("{" + entry.getKey() + "}", String.valueOf(entry.getValue()));
            }
        }

        // 2. 发起请求
        HttpUtils http = HttpUtils.http(apiBaseUrl + finalPath);
        if ("GET".equalsIgnoreCase(tool.method)) {
            http.data(dataParams);
        } else {
            http.bodyJson(ONode.serialize(dataParams));
        }

        return http.exec(tool.method).bodyAsString();
    }

    public static class ApiTool {
        public String name;
        public String description;
        public String path;
        public String method;
        public String inputSchema;  // 参数详情
        public String outputSchema; // 返回详情

        public ApiTool(String path, String method, ONode detail) {
            this.name = (method + "_" + path.replaceAll("[^a-zA-Z0-9]", "_")).replaceAll("_+", "_").toLowerCase();
            this.description = Utils.valueOr(detail.get("summary").getString(), detail.get("description").getString());
            this.path = path;
            this.method = method.toUpperCase();

            // 提取输入架构 (Parameters + requestBody)
            this.inputSchema = detail.get("parameters").toJson();
            // 提取输出架构 (200 OK 的 schema)
            this.outputSchema = detail.get("responses").get("200").get("content").get("application/json").get("schema").toJson();

            if("null".equals(this.outputSchema)) {
                this.outputSchema = detail.get("responses").get("200").get("schema").toJson(); // 适配 Swagger 2.0
            }
        }
    }
}