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
package org.noear.solon.ai.harness.command.builtin;

import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.ai.harness.command.Command;
import org.noear.solon.ai.harness.command.CommandContext;
import org.noear.solon.ai.harness.command.CommandRegistry;
import org.noear.solon.ai.harness.input.InputOrigin;

import java.util.List;

/**
 * /help 命令：在 CLI、Web 和 IM 中查看当前工作区可用的斜杠命令。
 *
 * <p>IM 没有前端的命令补全入口，因此帮助内容直接从当前工作区的
 * {@link CommandRegistry} 生成，能够同时覆盖内置命令和用户自定义 commands/ 命令。</p>
 */
public class HelpCommand implements Command {
    @Override
    public String name() {
        return "help";
    }

    @Override
    public String description() {
        return "查看可用命令；/help <命令> 查看详情";
    }

    @Override
    public String[] examples() {
        return new String[]{
                "/help",
                "/help queue"
        };
    }

    @Override
    public boolean runnableWhenBusy() {
        return true;
    }

    @Override
    public void execute(HarnessEngine engine, CommandContext ctx, InputOrigin origin) {
        boolean terminal = ctx.isTerminal();
        String topic = ctx.argAt(0);
        List<Command> commands = engine.getCommandRegistry().all();

        for (String line : formatHelp(commands, topic, terminal).split("\\n", -1)) {
            ctx.println(line);
        }
    }

    /**
     * 生成帮助文本，单独抽出便于测试，也避免不同渠道各自维护命令清单。
     *
     * <p>具备终端能力的渠道（CLI）与无终端渠道（Web/IM/桌面）可见的命令集合不同：
     * {@link Command#terminalOnly()} 为 true 的命令只在有终端的渠道生效，
     * 无终端渠道既不列入清单，也不返回其用法细节，改为明确提示不可用。</p>
     */
    public static String formatHelp(List<Command> commands, String topic, boolean terminal) {
        if (topic != null && !topic.trim().isEmpty()) {
            String name = topic.trim();

            for (Command command : commands) {
                if (command.name().equalsIgnoreCase(name) == false) {
                    continue;
                }

                if (!terminal && command.terminalOnly()) {
                    return "/" + command.name() + " 仅支持 CLI，Web/IM 端不可用。发送 /help 查看可用命令。";
                }

                StringBuilder out = new StringBuilder();
                appendCommandLine(out, command);

                String[] examples = command.examples();
                if (examples != null && examples.length > 0) {
                    out.append("用法：\n");
                    for (String example : examples) {
                        if (example != null && !example.trim().isEmpty()) {
                            out.append("  ").append(example).append("\n");
                        }
                    }
                }
                return trimTrailingNewline(out).toString();
            }

            return "未找到命令 /" + name + "。发送 /help 查看可用命令。";
        }

        StringBuilder out = new StringBuilder("可用命令：\n");
        for (Command command : commands) {
            if (!terminal && command.terminalOnly()) {
                continue;
            }
            appendCommandLine(out, command);
        }
        out.append("\n发送 /help <命令> 查看用法。\n")
                .append("普通文本不会被当作命令处理。\n");
        return trimTrailingNewline(out).toString();
    }

    private static void appendCommandLine(StringBuilder out, Command command) {
        out.append("/").append(command.name()).append(" - ")
                .append(safeDescription(command)).append("\n");
    }

    private static String safeDescription(Command command) {
        String description = command.description();
        return description == null || description.trim().isEmpty() ? "暂无描述" : description.trim();
    }

    private static StringBuilder trimTrailingNewline(StringBuilder text) {
        while (text.length() > 0 && text.charAt(text.length() - 1) == '\n') {
            text.setLength(text.length() - 1);
        }
        return text;
    }
}