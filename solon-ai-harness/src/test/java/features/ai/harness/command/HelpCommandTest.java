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
package features.ai.harness.command;

import org.junit.jupiter.api.Test;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.ai.harness.command.Command;
import org.noear.solon.ai.harness.command.CommandContext;
import org.noear.solon.ai.harness.command.builtin.HelpCommand;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HelpCommandTest {
    private static Command command(String name, String description, String... examples) {
        return command(name, description, false, examples);
    }

    private static Command cliOnlyCommand(String name, String description, String... examples) {
        return command(name, description, true, examples);
    }

    private static Command command(String name, String description, boolean cliOnly, String... examples) {
        return new Command() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public String description() {
                return description;
            }

            @Override
            public String[] examples() {
                return examples;
            }

            @Override
            public boolean cliOnly() {
                return cliOnly;
            }

            @Override
            public void execute(HarnessEngine engine, CommandContext ctx) {
            }
        };
    }

    @Test
    void remoteHelpListsOnlyCommandsAvailableToIm() {
        String help = HelpCommand.formatHelp(Arrays.asList(
                command("help", "查看帮助",  "/help"),
                command("queue", "排队消息",  "/queue <消息>"),
                cliOnlyCommand("exit", "退出进程",  "/exit")
        ), null, true);

        assertTrue(help.contains("/help - 查看帮助"));
        assertTrue(help.contains("/queue - 排队消息"));
        assertFalse(help.contains("/exit - 退出进程"));
    }

    @Test
    void helpTopicShowsExamplesAndRejectsCliOnlyCommandRemotely() {
        String topicHelp = HelpCommand.formatHelp(Arrays.asList(
                command("queue", "排队消息",  "/queue <消息>")
        ), "QUEUE", true);
        assertTrue(topicHelp.contains("/queue - 排队消息"));
        assertTrue(topicHelp.contains("/queue <消息>"));

        String cliOnly = HelpCommand.formatHelp(Arrays.asList(
                cliOnlyCommand("exit", "退出进程",  "/exit")
        ), "exit", true);
        assertTrue(cliOnly.contains("仅支持 CLI"));
        assertFalse(cliOnly.contains("/exit - 退出进程"));
    }

    @Test
    void cliHelpKeepsCliOnlyCommands() {
        String help = HelpCommand.formatHelp(Arrays.asList(
                command("queue", "排队消息",  "/queue <消息>"),
                cliOnlyCommand("exit", "退出进程",  "/exit")
        ), null, false);
        assertTrue(help.contains("/exit - 退出进程"));

        String topicHelp = HelpCommand.formatHelp(Arrays.asList(
                cliOnlyCommand("exit", "退出进程",  "/exit")
        ), "exit", false);
        assertTrue(topicHelp.contains("/exit - 退出进程"));
        assertTrue(topicHelp.contains("/exit"));
    }
}
