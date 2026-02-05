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

import org.noear.solon.Solon;
import org.noear.solon.ai.agent.session.FileAgentSession;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.skills.claudecode.SolonCodeCLI;
import org.noear.solon.core.util.Assert;

/**
 * Cli 应用
 *
 * @author noear
 * @since 3.9.1
 */
public class CliApp {

    public static void main(String[] args) {
        Solon.start(CliApp.class, args);

        CliConfig config = Solon.context().getBean(CliConfig.class);

        if (config == null || config.chatModel == null) {
            throw new RuntimeException("ChatModel config not found");
        }

        ChatModel chatModel = ChatModel.of(config.chatModel).build();

        SolonCodeCLI solonCodeCLI = new SolonCodeCLI(chatModel)
                .name(config.name)
                .workDir(config.workDir)
                .session(new FileAgentSession("cli", config.workDir))
                .enableWeb(config.enableWeb)
                .enableConsole(config.enableConsole)
                .enableHitl(config.enableHitl)
                .config(agent -> {
                    agent.maxSteps(config.maxSteps)
                            .sessionWindowSize(config.sessionWindowSize);
                });

        if (Assert.isNotEmpty(config.mountPool)) {
            config.mountPool.forEach((alias, dir) -> {
                solonCodeCLI.mountPool(alias, dir);
            });
        }

        if (config.enableWeb) {
            Solon.app().router().get(config.webPath, solonCodeCLI);
        }

        if (config.enableConsole) {
            new Thread(solonCodeCLI, "CLI-Interactive-Thread").start();
        }

        System.out.println(">>> Solon Code CLI 节点已全面启动。");
    }
}