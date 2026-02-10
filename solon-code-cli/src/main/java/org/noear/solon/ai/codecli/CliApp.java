/*
 * Copyright 2017-2026 noear.org and authors
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
package org.noear.solon.ai.codecli;

import com.agentclientprotocol.sdk.agent.AcpAsyncAgent;
import com.agentclientprotocol.sdk.agent.transport.StdioAcpAgentTransport;
import com.agentclientprotocol.sdk.agent.transport.WebSocketSolonAcpAgentTransport;
import com.agentclientprotocol.sdk.spec.AcpAgentTransport;
import io.modelcontextprotocol.json.McpJsonMapper;
import org.noear.solon.Solon;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.AgentSessionProvider;
import org.noear.solon.ai.agent.react.intercept.SummarizationInterceptor;
import org.noear.solon.ai.agent.session.FileAgentSession;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.skills.cli.CodeCLI;
import org.noear.solon.core.util.Assert;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Cli 应用
 *
 * @author noear
 * @since 3.9.1
 */
public class CliApp {

    public static void main(String[] args) {
        Solon.start(CliApp.class, args, app -> {
            CliConfig c = app.cfg().toBean("solon.code.cli", CliConfig.class);

            app.enableHttp(false); //默认不启用 http

            if (c.enableWeb) {
                app.enableHttp(true);
            }

            if (c.enableAcp && "stdio".equals(c.acpTransport) == false) {
                app.enableHttp(true);
                app.enableWebSocket(true);
            }
        });

        CliConfig config = Solon.context().getBean(CliConfig.class);

        if (config == null || config.chatModel == null) {
            throw new RuntimeException("ChatModel config not found");
        }

        ChatModel chatModel = ChatModel.of(config.chatModel).build();
        Map<String, AgentSession> store = new ConcurrentHashMap<>();
        AgentSessionProvider sessionProvider = (sessionId) -> store.computeIfAbsent(sessionId, key -> new FileAgentSession(key, config.workDir + "/" + key));

        CodeCLI solonCodeCLI = new CodeCLI(chatModel)
                .name(config.name)
                .workDir(config.workDir)
                .session(sessionProvider)
                .enableWeb(config.enableWeb)
                .enableConsole(config.enableConsole)
                .enableHitl(config.enableHitl)
                .config(agent -> {
                    // 添加步数扩展
                    agent.maxStepsExtensible(config.enableHitl);
                    //添加步数
                    agent.maxSteps(config.maxSteps);
                    //添加会话窗口大小
                    agent.sessionWindowSize(config.sessionWindowSize);
                    //语义保护型上下文压缩拦截器
                    agent.defaultInterceptorAdd(new SummarizationInterceptor(12));
                });

        if (Assert.isNotEmpty(config.mountPool)) {
            config.mountPool.forEach((alias, dir) -> {
                solonCodeCLI.mountPool(alias, dir);
            });
        }

        if (config.enableConsole) {
            new Thread(solonCodeCLI, "CLI-Interactive-Thread").start();
        }

        if (config.enableWeb) {
            Solon.app().router().get(config.webEndpoint, solonCodeCLI);
        }

        if (config.enableAcp) {
            AcpConnector acpConnector = new AcpConnector(solonCodeCLI);
            AcpAgentTransport agentTransport;
            if ("stdio".equals(config.acpTransport)) {
                agentTransport = new StdioAcpAgentTransport();
            } else {
                agentTransport = new WebSocketSolonAcpAgentTransport(config.acpEndpoint, McpJsonMapper.getDefault());
            }


            AcpAsyncAgent acpAgent = acpConnector.createAgent(agentTransport);

            acpAgent.start().subscribe();
        }

        System.out.println(">>> Solon Code CLI 节点已全面启动。");
    }
}