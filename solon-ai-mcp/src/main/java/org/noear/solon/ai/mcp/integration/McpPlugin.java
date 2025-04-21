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

import org.noear.solon.Solon;
import org.noear.solon.Utils;
import org.noear.solon.ai.chat.tool.MethodToolProvider;
import org.noear.solon.ai.mcp.server.McpServerEndpointProvider;
import org.noear.solon.ai.mcp.server.McpServerProperties;
import org.noear.solon.ai.mcp.server.annotation.McpServerEndpoint;
import org.noear.solon.core.AppContext;
import org.noear.solon.core.Plugin;
import org.noear.solon.core.util.ConvertUtil;

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
            //添加代理支持（即拦截）
            bw.context().beanExtractOrProxy(bw, false, true);

            //构建属性
            McpServerProperties props = new McpServerProperties();

            //支持${配置}
            String name = Solon.cfg().getByTmpl(anno.name());
            String version = Solon.cfg().getByTmpl(anno.version());
            String sseEndpoint = Solon.cfg().getByTmpl(anno.sseEndpoint());
            String heartbeatInterval = Solon.cfg().getByTmpl(anno.heartbeatInterval());


            if (Utils.isEmpty(name)) {
                props.setName(clz.getSimpleName());
            } else {
                //支持${配置}
                props.setName(name);
            }

            props.setVersion(version);
            props.setSseEndpoint(sseEndpoint);

            if (Utils.isEmpty(heartbeatInterval)) {
                props.setHeartbeatInterval(null); //表示不启用
            } else {
                //支持${配置}
                props.setHeartbeatInterval(ConvertUtil.durationOf(heartbeatInterval));
            }

            //构建端点提供者
            McpServerEndpointProvider serverEndpointProvider = new McpServerEndpointProvider(props);

            //添加工具
            serverEndpointProvider.addTool(new MethodToolProvider(bw.rawClz(), bw.raw()));

            //加入容器生命周期
            bw.context().lifecycle(serverEndpointProvider);

            //按名字注册到容器（如果有名字）
            if (Utils.isNotEmpty(name)) {
                bw.context().wrapAndPut(name, serverEndpointProvider);
            }
        });
    }
}