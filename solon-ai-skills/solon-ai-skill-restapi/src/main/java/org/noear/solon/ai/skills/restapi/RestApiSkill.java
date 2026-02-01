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
package org.noear.solon.ai.skills.restapi;

import org.noear.snack4.ONode;
import org.noear.solon.Utils;
import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.chat.skill.AbsSkill;
import org.noear.solon.ai.chat.tool.FunctionTool;
import org.noear.solon.ai.skills.restapi.resolver.OpenApiResolver;
import org.noear.solon.annotation.Param;
import org.noear.solon.core.util.ResourceUtil;
import org.noear.solon.lang.Preview;
import org.noear.solon.net.http.HttpUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 智能 RestAPI 调用技能
 *
 * @author noear
 * @since 3.9.1
 */
@Preview("3.9.1")
public class RestApiSkill extends AbsSkill {
    private static final Logger log = LoggerFactory.getLogger(RestApiSkill.class);

    private final String definitionUrl;
    private final String apiBaseUrl;

    private ApiResolver resolver;
    private List<ApiTool> dynamicTools;
    private ApiAuthenticator authenticator;

    private SchemaMode schemaMode = null;
    private int maxContextLength = 8000;

    public RestApiSkill(String definitionUrl, String apiBaseUrl) {
        this.definitionUrl = definitionUrl;
        this.apiBaseUrl = apiBaseUrl;

    }

    public RestApiSkill schemaMode(SchemaMode mode) {
        this.schemaMode = mode;
        return this;
    }

    public RestApiSkill maxContextLength(int length) {
        this.maxContextLength = length;
        return this;
    }

    public RestApiSkill authenticator(ApiAuthenticator authenticator) {
        this.authenticator = authenticator;
        return this;
    }

    public RestApiSkill resolver(ApiResolver resolver) {
        this.resolver = resolver;
        return this;
    }

    private void init() {
        if (dynamicTools != null) return;

        Utils.locker().lock();
        try {
            if (dynamicTools != null) return;

            if (resolver == null) {
                resolver = OpenApiResolver.getInstance();
            }

            log.info("RestApiSkill: Using {} for {}", resolver.getName(), definitionUrl);

            final String source ;

            if(definitionUrl.startsWith("http://") || definitionUrl.startsWith("https://")) {
                //网络地址（http://..., https://...）
                HttpUtils http = HttpUtils.http(definitionUrl);
                if (authenticator != null) {
                    authenticator.apply(http, null);
                }
                source = http.get();
            } else {
                //资源地址（"classpath:demo.xxx" or "file:./demo.xxx" or "./demo.xxx" or "demo.xxx"）
                source = ResourceUtil.findResourceAsString(definitionUrl);
            }

            if (Utils.isEmpty(source)) {
                throw new IllegalArgumentException("API definition source is empty from: " + definitionUrl);
            }

            List<ApiTool> allTools = resolver.resolve(definitionUrl, source);

            this.dynamicTools = allTools.stream()
                    .filter(t -> !t.isDeprecated())
                    .collect(Collectors.toList());

            if (schemaMode == null) {
                this.schemaMode = dynamicTools.size() > 30 ? SchemaMode.DYNAMIC : SchemaMode.FULL;
            }
        } catch (Exception e) {
            log.error("Api schema loading failed: {}", definitionUrl, e);
            this.schemaMode = SchemaMode.DYNAMIC;
            this.dynamicTools = new ArrayList<>();
        } finally {
            Utils.locker().unlock();
        }
    }

    @Override
    public String name() {
        return "api_expert";
    }

    @Override
    public String description() {
        return "业务 API 专家：支持 REST 接口精准调用，能够自动解析复杂的模型嵌套。";
    }

    @Override
    public void onAttach(Prompt prompt) {
        init();
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
                    .append("接口较多。**调用前必须通过 `get_api_detail` 确认具体的 Schema 定义**：\n\n");
            for (ApiTool t : dynamicTools) {
                sb.append("- **").append(t.getName()).append("**: ").append(t.getDescription())
                        .append(" (").append(t.getMethod()).append(" ").append(t.getPath()).append(")\n");
            }
        }

        sb.append("\n##### 3. 调用规范\n")
                .append("1. **必须尝试**: 只要接口 Description 与需求相关，即使 Request/Response Schema 为空 {}，也必须调用。真实接口往往返回比 Schema 定义更多的动态字段。\n")
                .append("2. **数据提取**: 调用返回后，请从原始 JSON 中提取用户询问的字段（如 status, env 等），不要因为 Schema 没写就认为没有这些数据。\n")
                .append("3. **认证逻辑**: 若返回 401，说明 token 无效，请直接告知。");

        return sb.toString();
    }

    @Override
    public Collection<FunctionTool> getTools(Prompt prompt) {
        if (schemaMode == SchemaMode.FULL) {
            return tools.stream().filter(t -> "call_api".equals(t.name())).collect(Collectors.toList());
        }
        return super.getTools(prompt);
    }

    @ToolMapping(name = "get_api_detail", description = "获取特定 API 的参数 Schema 和返回值定义（含模型深度展开）")
    public String getApiDetail(@Param("api_name") String apiName) {
        return dynamicTools.stream()
                .filter(t -> t.getName().equalsIgnoreCase(apiName))
                .map(t -> formatApiDocs(Collections.singletonList(t)))
                .findFirst()
                .orElse("Error: API '" + apiName + "' not found.");
    }

    @ToolMapping(name = "call_api", description = "执行 REST 接口请求")
    public String callApi(
            @Param("api_name") String apiName,
            @Param("path_params") Map<String, Object> pathParams,
            @Param("query_or_body_params") Map<String, Object> dataParams) throws IOException {

        ApiTool tool = dynamicTools.stream()
                .filter(t -> t.getName().equalsIgnoreCase(apiName))
                .findFirst()
                .orElse(null);

        if (tool == null) return "Error: API [" + apiName + "] not found.";

        String finalPath = tool.getPath();
        if (pathParams != null) {
            for (Map.Entry<String, Object> entry : pathParams.entrySet()) {
                finalPath = finalPath.replace("{" + entry.getKey() + "}", String.valueOf(entry.getValue()));
            }
        }

        HttpUtils http = HttpUtils.http(apiBaseUrl + finalPath);
        if (authenticator != null) {
            authenticator.apply(http, tool);
        }

        if ("GET".equalsIgnoreCase(tool.getMethod())) {
            http.data(dataParams);
        } else {
            http.bodyOfJson(ONode.serialize(dataParams));
        }

        try {
            String result = http.exec(tool.getMethod()).bodyAsString();
            if (result.length() > maxContextLength) {
                return result.substring(0, maxContextLength) + "... [Data truncated]";
            }
            return Utils.isEmpty(result) ? "Success: No content returned." : result;
        } catch (Exception e) {
            log.warn("API 调用失败: {} {}", tool.getName(), e.getMessage());
            return "HTTP Execution Error: " + e.getMessage();
        }
    }

    private String formatApiDocs(List<ApiTool> tools) {
        StringBuilder sb = new StringBuilder();
        for (ApiTool tool : tools) {
            sb.append("* **API: ").append(tool.getName()).append("**\n")
                    .append("  - Description: ").append(tool.getDescription()).append("\n")
                    .append("  - Endpoint: ").append(tool.getMethod()).append(" ").append(tool.getPath()).append("\n")
                    .append("  - Request Schema: ").append(tool.getInputSchemaOr("{}")).append("\n")
                    .append("  - Response Schema: ").append(tool.getOutputSchemaOr("{}")).append("\n");
        }
        return sb.toString();
    }
}