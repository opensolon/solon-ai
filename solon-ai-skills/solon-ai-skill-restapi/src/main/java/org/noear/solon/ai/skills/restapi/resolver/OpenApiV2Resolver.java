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

import java.util.*;

/**
 * Swagger 2.0 (OpenAPI V2) 规范解析器
 * <p>适配旧版规范，清洗非标准元数据，提取标准 JSON Schema 供 AI 使用</p>
 *
 * @author noear
 * @since 3.9.1
 */
@Preview("3.9.1")
public class OpenApiV2Resolver extends AbsOpenApiResolver {
    public String getName() { return "Swagger 2.0 Resolver"; }

    @Override
    public List<ApiTool> resolve(String definitionUrl, String source) {
        ONode root = ONode.ofJson(source);
        return doResolve(root);
    }

    protected List<ApiTool> doResolve(ONode root) {
        List<ApiTool> tools = new ArrayList<>();
        root.get("paths").getObject().forEach((path, pathNode) -> {
            // 提取路径级别的全局参数 (V2 规范支持)
            ONode pathCommonParams = pathNode.get("parameters");

            pathNode.getObject().forEach((method, detail) -> {
                if (!isValidMethod(method)) return;

                // 将路径级参数与方法级参数合并
                ONode mergedParams = mergeParameters(pathCommonParams, detail.get("parameters"));
                doResolveMethod(tools, root, path, method, detail, mergedParams);
            });
        });
        return tools;
    }

    /**
     * 根据规范合并参数：方法级参数优先于路径级参数
     */
    private ONode mergeParameters(ONode common, ONode specific) {
        if (common.isNull() || !common.isArray()) return specific;
        if (specific.isNull() || !specific.isArray()) return common;

        ONode result = ONode.ofJson(specific.toJson());
        Set<String> specificNames = new HashSet<>();
        specific.getArrayUnsafe().forEach(p -> specificNames.add(p.get("name").getString() + "_" + p.get("in").getString()));

        common.getArrayUnsafe().forEach(p -> {
            String identifier = p.get("name").getString() + "_" + p.get("in").getString();
            if (!specificNames.contains(identifier)) {
                result.add(p);
            }
        });
        return result;
    }

    protected void doResolveMethod(List<ApiTool> tools, ONode root, String path, String method, ONode detail, ONode params) {
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

        if (params.isArray()) {
            for (ONode p : params.getArrayUnsafe()) {
                ONode pNode = resolveRefNode(root, p, new HashSet<>());
                String in = pNode.get("in").getString();
                String name = pNode.get("name").getString();
                boolean isRequired = pNode.get("required").getBoolean();

                if ("header".equals(in)) {
                    headerProps.set(name, cleanMeta(pNode));
                } else if ("path".equals(in)) {
                    pathProps.set(name, cleanMeta(pNode));
                } else if ("body".equals(in)) {
                    ONode bodySchema = resolveRefNode(root, pNode.get("schema"), new HashSet<>());
                    if (bodySchema.hasKey("properties")) {
                        dataProps.setAll(bodySchema.get("properties").getObject());
                        if (bodySchema.hasKey("required")) {
                            dataRequired.addAll(bodySchema.get("required").getArray());
                        }
                    } else {
                        dataProps.set(name, bodySchema);
                        if (isRequired) dataRequired.add(name);
                    }
                } else {
                    if("formData".equals(in)){
                        tool.setMultipart(true);
                    }

                    // query, formData
                    dataProps.set(name, cleanMeta(pNode));
                    if (isRequired) dataRequired.add(name);
                }
            }
        }

        if (dataRequired.size() == 0) dataSchema.remove("required");
        if (headerProps.size() > 0) tool.setHeaderSchema(headerProps.toJson());
        if (pathProps.size() > 0) tool.setPathSchema(pathProps.toJson());
        if (dataProps.size() > 0) tool.setDataSchema(dataSchema.toJson());

        // 响应解析保持不变...
        parseResponses(root, detail, tool);

        tool.setDeprecated(detail.get("deprecated").getBoolean());
        tools.add(tool);
    }

    private void parseResponses(ONode root, ONode detail, ApiTool tool) {
        ONode responses = detail.get("responses");
        if (!responses.isNull()) {
            ONode okRes = responses.get("200");
            if (okRes.isNull()) okRes = responses.get("201");
            if (okRes.isNull()) okRes = responses.get("default");

            if (!okRes.isNull()) {
                okRes = resolveRefNode(root, okRes, new HashSet<>());
                tool.setOutputSchema(resolveRef(root, okRes.get("schema")));
            }
        }
    }

    private ONode cleanMeta(ONode pNode) {
        ONode meta = new ONode().asObject();
        String[] keys = {"type", "description", "format", "items", "enum", "default", "maximum", "minimum"};
        for (String k : keys) {
            if (pNode.hasKey(k)) meta.set(k, pNode.get(k));
        }
        return meta;
    }
}