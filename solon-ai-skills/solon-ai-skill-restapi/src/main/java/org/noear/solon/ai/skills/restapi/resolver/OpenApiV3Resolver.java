package org.noear.solon.ai.skills.restapi.resolver;

import io.swagger.parser.OpenAPIParser;
import io.swagger.v3.core.util.Json;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.parameters.RequestBody;
import io.swagger.v3.oas.models.responses.ApiResponse;
import org.noear.snack4.ONode;
import org.noear.solon.Utils;
import org.noear.solon.ai.skills.restapi.ApiResolver;
import org.noear.solon.ai.skills.restapi.ApiTool;
import org.noear.solon.lang.Preview;

import java.util.ArrayList;
import java.util.List;

/**
 * OpenAPI 3.0 规范解析器
 *
 * @author noear
 * @since 3.9.1
 */
@Preview("3.9.1")
public class OpenApiV3Resolver implements ApiResolver {

    @Override
    public String getName() {
        return "OpenApi 3.0 Resolver";
    }

    @Override
    public List<ApiTool> resolve(String definitionUrl, String source) {
        OpenAPI openAPI = new OpenAPIParser()
                .readContents(source, null, null)
                .getOpenAPI();

        List<ApiTool> tools = new ArrayList<>();
        if (openAPI == null || openAPI.getPaths() == null) {
            return tools;
        }

        String baseUrl = "";
        if (openAPI.getServers() != null && !openAPI.getServers().isEmpty()) {
            baseUrl = openAPI.getServers().get(0).getUrl();
        }

        final String finalBaseUrl = baseUrl;
        openAPI.getPaths().forEach((path, pathItem) -> {
            pathItem.readOperationsMap().forEach((method, operation) -> {
                if (operation != null) {
                    tools.add(convertToTool(path, method.name(), operation, finalBaseUrl));
                }
            });
        });

        return tools;
    }

    private ApiTool convertToTool(String path, String method, Operation op, String baseUrl) {
        ApiTool tool = new ApiTool();
        tool.setBaseUrl(baseUrl);
        tool.setPath(path);
        tool.setMethod(method.toUpperCase());

        String opId = op.getOperationId();
        tool.setName(Utils.isNotEmpty(opId) ? opId : generateName(method, path));

        String desc = op.getSummary();
        if (Utils.isEmpty(desc)) desc = op.getDescription();
        tool.setDescription(Utils.isEmpty(desc) ? "" : desc);

        tool.setDeprecated(Boolean.TRUE.equals(op.getDeprecated()));

        // --- 容器准备 ---
        ONode headerProps = new ONode().asObject();

        // Path 容器 (改为完整 Object 结构)
        ONode pathSchemaRoot = new ONode().asObject().set("type", "object");
        ONode pathProps = pathSchemaRoot.getOrNew("properties");
        ONode pathRequired = pathSchemaRoot.getOrNew("required").asArray();

        // Query 容器
        ONode querySchemaRoot = new ONode().asObject().set("type", "object");
        ONode queryProps = querySchemaRoot.getOrNew("properties");
        ONode queryRequired = querySchemaRoot.getOrNew("required").asArray();

        // Body 容器
        ONode bodySchemaRoot = new ONode().asObject().set("type", "object");
        ONode bodyProps = bodySchemaRoot.getOrNew("properties");
        ONode bodyRequired = bodySchemaRoot.getOrNew("required").asArray();

        // --- C. Parameters 解析 (Query, Path, Header) ---
        if (op.getParameters() != null) {
            for (Parameter p : op.getParameters()) {
                String in = p.getIn();
                String name = p.getName();
                Schema<?> schema = p.getSchema();
                if (schema == null) continue;

                ONode schemaNode = ONode.ofJson(Json.pretty(schema));
                if (Utils.isNotEmpty(p.getDescription()) && schemaNode.get("description").isNull()) {
                    schemaNode.set("description", p.getDescription());
                }

                if ("header".equals(in)) {
                    headerProps.set(name, schemaNode);
                } else if ("path".equals(in)) {
                    // 1. 放入 Path 专用容器并标记必填
                    pathProps.set(name, schemaNode);
                    pathRequired.add(name);
                    // 2. 冗余放入 Query 容器以便 AI 统一查看，同时也标记必填
                    queryProps.set(name, schemaNode);
                    queryRequired.add(name);
                } else if ("query".equals(in)) {
                    queryProps.set(name, schemaNode);
                    if (Boolean.TRUE.equals(p.getRequired())) {
                        queryRequired.add(name);
                    }
                }
            }
        }

        // --- D. RequestBody 解析 ---
        RequestBody rb = op.getRequestBody();
        if (rb != null && rb.getContent() != null) {
            MediaType mt = selectMediaType(rb.getContent(), tool);
            if (mt != null && mt.getSchema() != null) {
                ONode tempBodySchema = ONode.ofJson(Json.pretty(mt.getSchema()));

                if ("object".equals(tempBodySchema.get("type").getString())) {
                    if (tempBodySchema.hasKey("properties")) {
                        bodyProps.setAll(tempBodySchema.get("properties").getObject());
                    }
                    if (tempBodySchema.hasKey("required")) {
                        bodyRequired.addAll(tempBodySchema.get("required").getArray());
                    }
                } else {
                    bodyProps.set("body", tempBodySchema);
                    if (Boolean.TRUE.equals(rb.getRequired())) {
                        bodyRequired.add("body");
                    }
                }
            }
        }

        // --- E. Response 解析 ---
        if (op.getResponses() != null) {
            ApiResponse res = op.getResponses().get("200");
            if (res == null) res = op.getResponses().get("201");
            if (res == null) res = op.getResponses().get("default");

            if (res != null && res.getContent() != null) {
                MediaType resMt = selectMediaType(res.getContent(), null);
                if (resMt != null && resMt.getSchema() != null) {
                    tool.setOutputSchema(Json.pretty(resMt.getSchema()));
                }
            }
        }

        // --- F. 结果构建 ---
        if (headerProps.size() > 0) tool.setHeaderSchema(headerProps.toJson());

        if (pathProps.size() > 0) {
            if (pathRequired.size() == 0) pathSchemaRoot.remove("required");
            tool.setPathSchema(pathSchemaRoot.toJson());
        }

        if (queryProps.size() > 0) {
            if (queryRequired.size() == 0) querySchemaRoot.remove("required");
            tool.setQuerySchema(querySchemaRoot.toJson());
        }

        if (bodyProps.size() > 0) {
            if (bodyRequired.size() == 0) bodySchemaRoot.remove("required");
            tool.setBodySchema(bodySchemaRoot.toJson());
        }

        return tool;
    }

    private MediaType selectMediaType(Content content, ApiTool tool) {
        if (content.containsKey("application/json")) {
            return content.get("application/json");
        }
        for (String type : content.keySet()) {
            String lowerType = type.toLowerCase();
            if (lowerType.contains("multipart") || lowerType.contains("form-urlencoded")) {
                if (tool != null) tool.setMultipart(true);
                return content.get(type);
            }
        }
        return content.isEmpty() ? null : content.values().iterator().next();
    }

    private String generateName(String method, String path) {
        String name = method + "_" + path.replace("/", "_").replace("{", "").replace("}", "");
        return name.replaceAll("_+", "_");
    }
}