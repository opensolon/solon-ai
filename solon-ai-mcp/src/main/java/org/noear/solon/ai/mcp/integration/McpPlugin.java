/*
 * Copyright 2017-2025 noear.org and authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.noear.solon.ai.mcp.integration;

import org.noear.solon.ai.chat.tool.MethodToolProvider;
import org.noear.solon.ai.mcp.server.McpServerEndpointProvider;
import org.noear.solon.ai.mcp.server.annotation.McpServerEndpoint;
import org.noear.solon.ai.chat.prompt.MethodPromptProvider;
import org.noear.solon.ai.chat.resource.MethodResourceProvider;
import org.noear.solon.core.AppContext;
import org.noear.solon.core.Plugin;

/**
 * Mcp 插件
 *
 * @author noear
 * @since 3.1
 * */
public class McpPlugin implements Plugin {
    @Override
    public void start(AppContext context) throws Throwable {
        context.beanBuilderAdd(McpServerEndpoint.class, (clz, bw, anno) -> {
            //添加代理和提取支持
            bw.context().beanExtractOrProxy(bw, true, true);

            //构建端点提供者
            McpServerEndpointProvider serverEndpointProvider = McpServerEndpointProvider.builder()
                    .from(clz, anno)
                    .build();

            //添加工具
            serverEndpointProvider.addTool(new MethodToolProvider(bw));
            //添加资源
            serverEndpointProvider.addResource(new MethodResourceProvider(bw));
            //添加提示语
            serverEndpointProvider.addPrompt(new MethodPromptProvider(bw));

            //加入容器生命周期
            bw.context().lifecycle(serverEndpointProvider);

            //按名字注册到容器
            bw.context().wrapAndPut(serverEndpointProvider.getName(), serverEndpointProvider);
        });
    }
}