package org.noear.solon.ai.talents.gateway.openapi.resolver;

import io.swagger.models.*;
import io.swagger.models.parameters.BodyParameter;
import io.swagger.models.parameters.Parameter;
import io.swagger.parser.SwaggerParser;
import io.swagger.util.Json;
import org.noear.snack4.ONode;
import org.noear.solon.Utils;
import org.noear.solon.ai.talents.gateway.openapi.ApiResolver;
import org.noear.solon.ai.talents.gateway.openapi.ApiTool;
import org.noear.solon.lang.Preview;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Swagger 2.0 (OpenAPI V2) 规范解析器
 *
 * @author noear
 * @since 3.9.1
 */
@Preview("3.9.1")
public class OpenApiV2Resolver implements ApiResolver {

    @Override
    public String getName() {
        return "Swagger 2.0 Resolver";
    }

    @Override
    public List<ApiTool> resolve(String definitionUrl, String source) throws IOException {
        Swagger swagger = new SwaggerParser()
                .readWithInfo(source)
                .getSwagger();

        List<ApiTool> tools = new ArrayList<>();
        if (swagger == null || swagger.getPaths() == null) {
            return tools;
        }

        String baseUrl = extractBaseUrl(swagger);

        // 定义表只序列化一次，供所有 operation 共享（只读，不会被改写）
        SchemaRefFlattener flattener = buildFlattener(swagger.getDefinitions());

        swagger.getPaths().forEach((path, pathItem) -> {
            // PathItem 级别的公共参数（可被各 operation 共享，常见于 path 变量）
            List<Parameter> sharedParams = pathItem.getParameters();
            pathItem.getOperationMap().forEach((method, operation) -> {
                if (operation != null) {
                    tools.add(convertToTool(flattener, path, method.name(), operation, baseUrl, sharedParams));
                }
            });
        });

        return tools;
    }

    private SchemaRefFlattener buildFlattener(Map<String, Model> definitions) {
        if (definitions == null || definitions.isEmpty()) {
            return SchemaRefFlattener.of(null);
        }

        return SchemaRefFlattener.of(ONode.ofJson(Json.pretty(definitions)));
    }

    private ApiTool convertToTool(SchemaRefFlattener flattener, String path, String method, Operation op, String baseUrl, List<Parameter> sharedParams) {
        ApiTool tool = new ApiTool();
        tool.setBaseUrl(baseUrl);
        tool.setPath(path);
        tool.setMethod(method.toUpperCase());

        if (op.getTags() != null) {
            tool.getTags().addAll(op.getTags());
        }

        String opId = op.getOperationId();
        tool.setName(Utils.isNotEmpty(opId) ? opId : generateName(method, path));

        String desc = op.getSummary();
        if (Utils.isEmpty(desc)) desc = op.getDescription();
        tool.setDescription(Utils.isEmpty(desc) ? "" : desc);

        Object dep = op.getVendorExtensions().get("deprecated");
        tool.setDeprecated(Boolean.TRUE.equals(dep));

        // 预检查 Consumes
        if (op.getConsumes() != null) {
            for (String c : op.getConsumes()) {
                if (c.contains("multipart") || c.contains("form-urlencoded")) {
                    tool.setMultipart(true);
                    break;
                }
            }
        }

        // --- B. 容器准备 ---
        ONode headerProps = new ONode().asObject();

        // Path 容器 (升级为完整 Object 结构)
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

        for (Parameter p : mergeParameters(sharedParams, op.getParameters())) {
                String in = p.getIn();
                String name = p.getName();

                if ("body".equals(in) && p instanceof BodyParameter) {
                    Model model = ((BodyParameter) p).getSchema();
                    if (model != null) {
                        ONode modelNode = flattener.flatten(ONode.ofJson(Json.pretty(model)));
                        if ("object".equals(modelNode.get("type").getString()) && modelNode.hasKey("properties")) {
                            bodyProps.setAll(modelNode.get("properties").getObject());
                            if (modelNode.hasKey("required")) {
                                bodyRequired.addAll(modelNode.get("required").getArray());
                            }
                        } else {
                            bodyProps.set(name, modelNode);
                            if (p.getRequired()) bodyRequired.add(name);
                        }
                    }
                } else {
                    ONode pNode = ONode.ofJson(Json.pretty(p));
                    pNode.remove("in");
                    pNode.remove("name");
                    pNode.remove("required");
                    // 非 body 参数自身不会是 $ref，但其 items 可能引用定义
                    pNode = flattener.flatten(pNode);

                    if ("header".equals(in)) {
                        headerProps.set(name, pNode);
                    } else if ("path".equals(in)) {
                        pathProps.set(name, pNode);
                        pathRequired.add(name);
                    } else if ("query".equals(in)) {
                        queryProps.set(name, pNode);
                        if (p.getRequired()) queryRequired.add(name);
                    } else if ("formData".equals(in)) {
                        tool.setMultipart(true);
                        bodyProps.set(name, pNode);
                        if (p.getRequired()) bodyRequired.add(name);
                    }
                }
            }

        // --- D. Response 解析 ---
        if(op.getResponses() != null) {
            Response ok = op.getResponses().get("200");
            if (ok == null) ok = op.getResponses().get("201");
            if (ok == null) ok = op.getResponses().get("default");
            if (ok != null && ok.getResponseSchema() != null) {
                ONode modelNode = flattener.flatten(ONode.ofJson(Json.pretty(ok.getResponseSchema())));

                tool.setOutputSchema(modelNode.toJson());
            }
        }

        // --- E. 结果构建 ---
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

    private String extractBaseUrl(Swagger swagger) {
        StringBuilder sb = new StringBuilder();
        String host = swagger.getHost();
        if (Utils.isNotEmpty(host)) {
            String scheme = "http";
            if (swagger.getSchemes() != null && !swagger.getSchemes().isEmpty()) {
                scheme = swagger.getSchemes().get(0).toValue();
            }
            sb.append(scheme).append("://").append(host);
        }

        String basePath = swagger.getBasePath();
        if (Utils.isNotEmpty(basePath)) {
            if (!basePath.startsWith("/")) sb.append("/");
            sb.append(basePath);
            if (sb.charAt(sb.length() - 1) == '/') {
                sb.setLength(sb.length() - 1);
            }
        }
        return sb.toString();
    }

    private String generateName(String method, String path) {
        String name = method + "_" + path.replace("/", "_").replace("{", "").replace("}", "");
        return name.replaceAll("_+", "_");
    }

    /**
     * 合并 PathItem 级别与 Operation 级别参数。
     * 按 OpenAPI 规范：operation 级别参数会覆盖同名（name + in）的 path 级别参数。
     */
    private List<Parameter> mergeParameters(List<Parameter> shared, List<Parameter> operationLevel) {
        Map<String, Parameter> merged = new LinkedHashMap<>();

        if (shared != null) {
            for (Parameter p : shared) {
                if (p != null && p.getName() != null) {
                    merged.put(p.getIn() + ":" + p.getName(), p);
                }
            }
        }

        if (operationLevel != null) {
            for (Parameter p : operationLevel) {
                if (p != null && p.getName() != null) {
                    merged.put(p.getIn() + ":" + p.getName(), p);
                }
            }
        }

        return new ArrayList<>(merged.values());
    }
}