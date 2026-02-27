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
package org.noear.solon.ai.skills.restapi.resolver;

import org.noear.snack4.ONode;
import org.noear.solon.ai.skills.restapi.ApiTool;
import org.noear.solon.lang.Preview;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * OpenAPI 3.0 规范解析器
 * <p>适配新版规范，支持路径级参数继承、requestBody 解析以及标准 JSON Schema 提取</p>
 *
 * @author noear
 * @since 3.9.1
 */
@Preview("3.9.1")
public class OpenApiV3Resolver extends AbsOpenApiResolver {
    public String getName() { return "OpenApi 3.0 Resolver"; }

    @Override
    public List<ApiTool> resolve(String definitionUrl, String source) {
        ONode root = ONode.ofJson(source);
        return doResolve(root);
    }

    protected List<ApiTool> doResolve(ONode root) {
        List<ApiTool> tools = new ArrayList<>();
        ONode paths = root.get("paths");
        if (paths.isNull()) return tools;

        paths.getObject().forEach((path, pathNode) -> {
            // 规范对齐：提取 Path Item 级别的公共参数
            ONode pathCommonParams = pathNode.get("parameters");

            pathNode.getObject().forEach((method, detail) -> {
                if (!isValidMethod(method)) return;

                // 规范对齐：合并参数（方法级覆盖路径级）
                ONode mergedParams = mergeParameters(pathCommonParams, detail.get("parameters"));
                doResolveMethod(tools, root, path, method, detail, mergedParams);
            });
        });
        return tools;
    }

    /**
     * 规范合并逻辑：如果方法级参数与路径级参数的 name 和 in 均相同，则忽略路径级参数
     */
    private ONode mergeParameters(ONode common, ONode specific) {
        if (common.isNull() || !common.isArray()) return specific;
        if (specific.isNull() || !specific.isArray()) return common;

        ONode result = ONode.ofJson(specific.toJson());
        Set<String> specificKeys = new HashSet<>();
        specific.getArrayUnsafe().forEach(p ->
                specificKeys.add(p.get("name").getString() + ":" + p.get("in").getString()));

        common.getArrayUnsafe().forEach(p -> {
            String key = p.get("name").getString() + ":" + p.get("in").getString();
            if (!specificKeys.contains(key)) {
                result.add(p);
            }
        });
        return result;
    }

    protected void doResolveMethod(List<ApiTool> tools, ONode root, String path, String method, ONode detail, ONode parameters) {
        ApiTool tool = new ApiTool();
        tool.setPath(path);
        tool.setMethod(method.toUpperCase());
        tool.setName(generateName(detail, tool.getMethod(), path));
        tool.setDescription(extractDescription(detail));

        ONode headerProps = new ONode().asObject();
        ONode pathProps = new ONode().asObject();
        ONode dataSchema = new ONode().asObject().set("type", "object");
        ONode dataProps = dataSchema.getOrNew("properties");
        ONode dataRequired = dataSchema.getOrNew("required").asArray();

        // 1. 解析合并后的 Parameters
        if (parameters.isArray()) {
            for (ONode param : parameters.getArrayUnsafe()) {
                ONode pNode = resolveRefNode(root, param, new HashSet<>());
                String in = pNode.get("in").getString();
                String name = pNode.get("name").getString();
                boolean isRequired = pNode.get("required").getBoolean();

                ONode schema = pNode.get("schema");
                if (schema.isNull()) continue;

                // 补充描述信息
                if (!pNode.get("description").isNull() && schema.get("description").isNull()) {
                    schema.set("description", pNode.get("description").getString());
                }

                if ("header".equals(in)) {
                    headerProps.set(name, schema);
                } else if ("path".equals(in)) {
                    pathProps.set(name, schema);
                } else if ("query".equals(in)) {
                    dataProps.set(name, schema);
                    if (isRequired) dataRequired.add(name);
                }
            }
        }

        // 2. 解析 requestBody
        if (detail.hasKey("requestBody")) {
            ONode bodySchema = extractBodySchema(root, detail.get("requestBody"));
            if (!bodySchema.isNull()) {
                if (bodySchema.hasKey("properties")) {
                    dataProps.setAll(bodySchema.get("properties").getObject());
                    if (bodySchema.hasKey("required")) {
                        dataRequired.addAll(bodySchema.get("required").getArray());
                    }
                } else {
                    dataProps.set("body", bodySchema);
                    dataRequired.add("body");
                }
            }
        }

        // 3. 序列化赋值
        if (dataRequired.size() == 0) dataSchema.remove("required");
        if (headerProps.size() > 0) tool.setHeaderSchema(headerProps.toJson());
        if (pathProps.size() > 0) tool.setPathSchema(pathProps.toJson());
        if (dataProps.size() > 0) tool.setDataSchema(dataSchema.toJson());

        // 4. 解析 Responses
        parseResponses(root, detail, tool);

        tool.setDeprecated(detail.get("deprecated").getBoolean());
        tools.add(tool);
    }

    private void parseResponses(ONode root, ONode detail, ApiTool tool) {
        ONode responses = detail.get("responses");
        if (responses.isNull()) return;

        ONode okRes = responses.get("200");
        if (okRes.isNull()) okRes = responses.get("201");
        if (okRes.isNull()) okRes = responses.get("default");

        if (!okRes.isNull()) {
            okRes = resolveRefNode(root, okRes, new HashSet<>());
            ONode content = okRes.get("content");
            if (!content.isNull() && content.isObject()) {
                ONode mediaType = content.get("application/json");
                if (mediaType.isNull() && content.size() > 0) {
                    mediaType = content.getObject().values().iterator().next();
                }
                if (!mediaType.isNull()) {
                    tool.setOutputSchema(resolveRef(root, mediaType.get("schema")));
                }
            }
        }
    }

    private ONode extractBodySchema(ONode root, ONode requestBody) {
        ONode bodyNode = resolveRefNode(root, requestBody, new HashSet<>());
        ONode content = bodyNode.get("content");
        if (content.isNull() || !content.isObject()) return new ONode();

        ONode schemaNode = content.get("application/json").get("schema");
        if (schemaNode.isNull() && content.size() > 0) {
            schemaNode = content.getObject().values().iterator().next().get("schema");
        }
        return resolveRefNode(root, schemaNode, new HashSet<>());
    }
}