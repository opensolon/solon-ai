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

/**
 * Swagger 2.0 (OpenAPI V2) 规范解析器
 * <p>专门适配旧版 Swagger 规范，处理其特有的 parameters 数组结构</p>
 *
 * @author noear
 * @since 3.9.1
 */
@Preview("3.9.1")
public class OpenApiV2Resolver extends AbsOpenApiResolver {
    public String getName() {
        return "Swagger 2.0 Resolver";
    }

    @Override
    public List<ApiTool> resolve(String definitionUrl, String source) {
        ONode root = ONode.ofJson(source);
        return doResolve(root);
    }

    protected List<ApiTool> doResolve(ONode root) {
        List<ApiTool> tools = new ArrayList<>();
        root.get("paths").getObject().forEach((path, methods) -> {
            methods.getObject().forEach((method, detail) -> {
                if (!isValidMethod(method)) return;

                doResolveMethod(tools, root, path, method, detail);
            });
        });

        return tools;
    }

    protected void doResolveMethod(List<ApiTool> tools, ONode root, String path, String method, ONode detail) {
        ApiTool tool = new ApiTool();
        tool.setPath(path);
        tool.setMethod(method.toUpperCase());
        tool.setName(generateName(detail, tool.getMethod(), path));
        tool.setDescription(extractDescription(detail));

        // 1. Path 参数容器 (平铺)
        ONode pathProps = new ONode().asObject();
        // 2. Data 参数容器 (JSON Schema 对象结构)
        ONode dataSchema = new ONode().asObject().set("type", "object");
        ONode dataProps = dataSchema.getOrNew("properties");

        ONode params = detail.get("parameters");

        // 兼容非数组引用
        if (!params.isNull() && !params.isArray()) {
            ONode wrapper = new ONode().asArray();
            wrapper.add(params);
            params = wrapper;
        }

        if (params.isArray()) {
            for (ONode p : params.getArrayUnsafe()) {
                ONode pNode = resolveRefNode(root, p, new HashSet<>());
                String in = pNode.get("in").getString();
                String name = pNode.get("name").getString();

                if ("path".equals(in)) {
                    pathProps.set(name, pNode);
                } else if ("body".equals(in)) {
                    ONode bodySchema = resolveRefNode(root, pNode.get("schema"), new HashSet<>());
                    if (bodySchema.hasKey("properties")) {
                        // 合并 body 内的属性到 dataProps
                        dataProps.setAll(bodySchema.get("properties").getObject());
                        if (bodySchema.hasKey("required")) {
                            dataSchema.getOrNew("required").addAll(bodySchema.get("required").getArray());
                        }
                    } else {
                        // 非对象 body，以参数名作为 key
                        dataProps.set(name, bodySchema);
                    }
                } else if ("query".equals(in) || "formData".equals(in)) {
                    dataProps.set(name, pNode);
                }
            }
        }

        if (pathProps.size() > 0) {
            tool.setPathSchema(pathProps.toJson());
        }
        if (dataProps.size() > 0) {
            tool.setDataSchema(dataSchema.toJson());
        }

        // 3. 输出解析
        if (detail.hasKey("responses")) {
            ONode node200 = detail.get("responses").get("200");
            if (node200.isNull()) node200 = detail.get("responses").get("201");
            if (!node200.isNull()) {
                tool.setOutputSchema(resolveRef(root, node200.get("schema")));
            }
        }

        tool.setDeprecated(detail.get("deprecated").getBoolean());
        tools.add(tool);
    }
}