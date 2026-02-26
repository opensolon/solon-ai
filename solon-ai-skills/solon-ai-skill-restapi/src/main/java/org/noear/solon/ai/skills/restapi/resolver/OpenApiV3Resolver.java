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
 * OpenAPI 3.0 规范解析器
 * <p>适配新版规范，支持更复杂的 requestBody 和多内容类型（Content-Type）映射</p>
 *
 * @author noear
 * @since 3.9.1
 */
@Preview("3.9.1")
public class OpenApiV3Resolver extends AbsOpenApiResolver {
    public String getName() {
        return "OpenApi 3.0 Resolver";
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

    protected void doResolveMethod(List<ApiTool> tools, ONode root, String path, String method, ONode detail){
        ApiTool tool = new ApiTool();
        tool.setPath(path);
        tool.setMethod(method.toUpperCase());
        tool.setName(generateName(detail, tool.getMethod(), path));
        tool.setDescription(extractDescription(detail));

        // 1. 分解 Parameters (Path vs Query)
        ONode parameters = detail.get("parameters");
        ONode pathProps = new ONode().asObject();
        ONode queryProps = new ONode().asObject();

        if (parameters.isArray()) {
            for (ONode param : parameters.getArrayUnsafe()) {
                // 解析引用（如果参数本身是引用）
                ONode pNode = resolveRefNode(root, param, new HashSet<>());
                String in = pNode.get("in").getString();
                String name = pNode.get("name").getString();

                if ("path".equals(in)) {
                    pathProps.set(name, pNode);
                } else if ("query".equals(in)) {
                    queryProps.set(name, pNode);
                }
            }
        }

        // 2. 解析 Body 并入 DataSchema
        if (detail.hasKey("requestBody")) {
            ONode bodySchema = extractBodySchema(root, detail.get("requestBody"));
            if (!bodySchema.isNull()) {
                // 如果既有 Query 又有 Body，合并到 dataSchema
                queryProps.getOrNew("properties").setAll(bodySchema.get("properties").getObject());
            }
        }

        if (pathProps.size() > 0) {
            tool.setPathSchema(pathProps.toJson());
        }
        if (queryProps.size() > 0) {
            tool.setDataSchema(queryProps.toJson());
        }

        // 3. 输出 Schema
        if (detail.hasKey("responses")) {
            tool.setOutputSchema(resolveRef(root, detail.get("responses").get("200").get("content").get("application/json").get("schema")));
        }

        tool.setDeprecated(detail.get("deprecated").getBoolean());
        tools.add(tool);
    }

    private ONode extractBodySchema(ONode root, ONode requestBody) {
        ONode content = requestBody.get("content");
        ONode schemaNode = content.get("application/json").get("schema");
        if (schemaNode.isNull()) {
            schemaNode = content.getObject().values().stream().findFirst().map(n -> n.get("schema")).orElse(new ONode());
        }
        return resolveRefNode(root, schemaNode, new HashSet<>());
    }
}