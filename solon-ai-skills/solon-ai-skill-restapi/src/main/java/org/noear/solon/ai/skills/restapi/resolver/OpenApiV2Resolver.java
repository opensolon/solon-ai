package org.noear.solon.ai.skills.restapi.resolver;

import io.swagger.models.*;
import io.swagger.models.parameters.BodyParameter;
import io.swagger.models.parameters.Parameter;
import io.swagger.models.properties.*;
import io.swagger.parser.SwaggerParser;
import io.swagger.util.Json;
import org.noear.snack4.ONode;
import org.noear.solon.Utils;
import org.noear.solon.ai.skills.restapi.ApiResolver;
import org.noear.solon.ai.skills.restapi.ApiTool;
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

        swagger.getPaths().forEach((path, pathItem) -> {
            pathItem.getOperationMap().forEach((method, operation) -> {
                if (operation != null) {
                    tools.add(convertToTool(swagger, path, method.name(), operation, baseUrl));
                }
            });
        });

        return tools;
    }

    private ApiTool convertToTool(Swagger swagger, String path, String method, Operation op, String baseUrl) {
        ApiTool tool = new ApiTool();
        tool.setBaseUrl(baseUrl);
        tool.setPath(path);
        tool.setMethod(method.toUpperCase());

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

        if (op.getParameters() != null) {
            for (Parameter p : op.getParameters()) {
                String in = p.getIn();
                String name = p.getName();

                if ("body".equals(in) && p instanceof BodyParameter) {
                    Model model = ((BodyParameter) p).getSchema();
                    if (model != null) {
                        model = resolveModel(swagger.getDefinitions(), model, new ArrayList<>());

                        ONode modelNode = ONode.ofJson(Json.pretty(model));
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

                    if ("header".equals(in)) {
                        headerProps.set(name, pNode);
                    } else if ("path".equals(in)) {
                        // 1. 放入 Path 容器并标记必填
                        pathProps.set(name, pNode);
                        pathRequired.add(name);
                        // 2. 冗余放入 Query 容器以便 AI 感知，同时标记必填
                        queryProps.set(name, pNode);
                        queryRequired.add(name);
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
        }

        // --- D. Response 解析 ---
        if(op.getResponses() != null) {
            Response ok = op.getResponses().get("200");
            if (ok == null) ok = op.getResponses().get("201");
            if (ok == null) ok = op.getResponses().get("default");
            if (ok != null && ok.getResponseSchema() != null) {
                Model model = resolveModel(swagger.getDefinitions(), ok.getResponseSchema(), new ArrayList<>());

                tool.setOutputSchema(ONode.ofJson(Json.pretty(model)).toJson());
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

    private Model resolveModel(Map<String, Model> definitions, Model model, List<String> refs) {
        if (model instanceof RefModel) {
            String refName = ((RefModel) model).getSimpleRef();
            if (refs.contains(refName)) {
                ModelImpl loopModel = new ModelImpl();
                loopModel.setDescription("_Circular_Reference_");
                return loopModel;
            }

            refs.add(refName);
            Model realModel = (definitions == null) ? null : definitions.get(refName);
            return resolveModel(definitions, realModel, refs);
        }

        if (model instanceof ArrayModel) {
            ArrayModel arrayModel = (ArrayModel) model;
            if (arrayModel.getItems() != null) {
                // 深度解析数组项的属性
                arrayModel.setItems(resolveProperty(definitions, arrayModel.getItems(), new ArrayList<>(refs)));
            }
        }

        if (model instanceof ModelImpl) {
            ModelImpl modelImpl = (ModelImpl) model;
            if (modelImpl.getProperties() != null) {
                Map<String, Property> newProps = new LinkedHashMap<>();
                for (Map.Entry<String, Property> entry : modelImpl.getProperties().entrySet()) {
                    newProps.put(entry.getKey(), resolveProperty(definitions, entry.getValue(), new ArrayList<>(refs)));
                }
                modelImpl.setProperties(newProps);
            }
        }

        return model;
    }

    /**
     * 辅助方法：深度解析属性中的引用
     */
    private Property resolveProperty(Map<String, Model> definitions, Property prop, List<String> refs) {
        // A. 处理属性引用 (RefProperty)
        if (prop instanceof RefProperty) {
            String refName = ((RefProperty) prop).getSimpleRef();

            if (refs.contains(refName)) {
                StringProperty loopProp = new StringProperty();
                loopProp.setDescription("_Circular_Reference_");
                return loopProp;
            }

            Model realModel = (definitions == null) ? null : definitions.get(refName);
            if (realModel != null) {
                // 将 Model 转换为 Property 以便嵌入属性列表
                // 这里使用特殊的扩展逻辑或转换为 ObjectProperty
                Model resolved = resolveModel(definitions, realModel, refs);

                ObjectProperty objProp = new ObjectProperty();
                if (resolved instanceof ModelImpl) {
                    ModelImpl mi = (ModelImpl) resolved;
                    objProp.setProperties(mi.getProperties());
                    objProp.setDescription(mi.getDescription());
                }
                return objProp;
            }
        }

        // B. 处理数组中的引用 (ArrayProperty)
        if (prop instanceof ArrayProperty) {
            ArrayProperty arrayProp = (ArrayProperty) prop;
            if (arrayProp.getItems() != null) {
                arrayProp.setItems(resolveProperty(definitions, arrayProp.getItems(), refs));
            }
            return arrayProp;
        }

        return prop;
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
}