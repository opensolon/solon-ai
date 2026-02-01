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
package org.noear.solon.ai.skills.restapi.resolve;

import org.noear.snack4.ONode;
import org.noear.solon.Utils;
import org.noear.solon.ai.skills.restapi.ApiTool;
import org.noear.solon.lang.Preview;

import java.util.ArrayList;
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
                if (isValidMethod(method)) {
                    ApiTool tool = new ApiTool();
                    tool.setPath(path);
                    tool.setMethod(method.toUpperCase());
                    tool.setName(generateName(detail, tool.getMethod(), path));
                    tool.setDescription(extractDescription(detail));

                    String params = resolveRef(root, detail.get("parameters"));
                    StringBuilder input = new StringBuilder();
                    if (!"[]".equals(params) && !Utils.isEmpty(params)) input.append(params);

                    if (detail.hasKey("requestBody")) {
                        ONode content = detail.get("requestBody").get("content");
                        ONode bodySchema = content.get("application/json").get("schema");
                        if (bodySchema.isNull() && content.size() > 0) {
                            bodySchema = content.get(0).get("schema");
                        }
                        if (!bodySchema.isNull()) {
                            if (input.length() > 0) input.append(" + ");
                            input.append("Body:").append(resolveRef(root, bodySchema));
                        }
                    }
                    tool.setInputSchema(input.toString());

                    if (detail.hasKey("responses")) {
                        ONode resps = detail.get("responses");
                        ONode node200 = resps.get("200");
                        if (node200.isNull()) node200 = resps.get("default");

                        ONode content = node200.get("content");
                        ONode outNode = new ONode();
                        if (!content.isNull() && content.size() > 0) {
                            outNode = content.get("application/json").get("schema");
                            if (outNode.isNull()) outNode = content.get(0).get("schema");
                        }
                        tool.setOutputSchema(resolveRef(root, outNode));
                    }

                    tool.setDeprecated(detail.get("deprecated").getBoolean());
                    tools.add(tool);
                }
            });
        });

        return tools;
    }
}