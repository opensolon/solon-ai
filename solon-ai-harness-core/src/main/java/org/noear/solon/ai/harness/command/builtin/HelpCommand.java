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

import org.noear.solon.ai.harness.i18n.HarnessI18n;
import org.noear.solon.ai.harness.i18n.HarnessI18nKeys;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.ai.harness.command.AbsCommand;
import org.noear.solon.ai.harness.command.Command;
import org.noear.solon.ai.harness.command.CommandInput;
import org.noear.solon.ai.harness.command.CommandOutput;
import org.noear.solon.ai.harness.command.CommandDispatcher;
import org.noear.solon.ai.harness.command.CommandRegistry;
import org.noear.solon.lang.Nullable;

import java.util.List;
import java.util.Locale;

/**
 * /help 命令：在 CLI、Web 和 IM 中查看当前工作区可用的斜杠命令。
 *
 * <p>IM 没有前端的命令补全入口，因此帮助内容直接从当前工作区的
 * {@link CommandRegistry} 生成，能够同时覆盖内置命令和用户自定义 commands/ 命令。</p>
 */
public class HelpCommand extends AbsCommand {
    @Override
    public String name() {
        return "help";
    }

    @Override
    public String description(@Nullable Locale locale) {
        return HarnessI18n.get(locale, HarnessI18nKeys.COMMAND_HELP_DESCRIPTION);
    }

    @Override
    public String[] examples(@Nullable Locale locale) {
        return new String[]{
                "/help",
                "/help interrupt"
        };
    }

    @Override
    public boolean runnableWhenBusy() {
        return true;
    }

    @Override
    public boolean requiresSession() {
        return false;
    }

    @Override
    public void execute(HarnessEngine engine, CommandInput input, CommandOutput output) {
        boolean terminal = input.isTerminal();
        String topic = input.argAt(0);
        List<Command> commands = engine.getCommandRegistry().all();

        for (String line : formatHelp(commands, topic, terminal, input.getLocale()).split("\\n", -1)) {
            output.println(line);
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
        return formatHelp(commands, topic, terminal, null);
    }

    /**
     * 生成帮助文本（按语言；内置命令的描述同样按语言输出，见 {@link Command#description(Locale)}）
     *
     * @param locale 语言（null 为默认语言）
     * @since 4.2
     */
    public static String formatHelp(List<Command> commands, String topic, boolean terminal, Locale locale) {
        if (topic != null && !topic.trim().isEmpty()) {
            String name = topic.trim();

            for (Command command : commands) {
                if (command.name().equalsIgnoreCase(name) == false) {
                    continue;
                }

                if (!terminal && command.terminalOnly()) {
                    return CommandDispatcher.unavailableHint(command.name(), locale);
                }

                StringBuilder out = new StringBuilder();
                appendCommandLine(out, command, locale);

                String[] examples = command.examples(locale);
                if (examples != null && examples.length > 0) {
                    out.append(HarnessI18n.get(locale, HarnessI18nKeys.COMMAND_HELP_USAGE)).append("\n");
                    for (String example : examples) {
                        if (example != null && !example.trim().isEmpty()) {
                            out.append("  ").append(example).append("\n");
                        }
                    }
                }
                return trimTrailingNewline(out).toString();
            }

            return HarnessI18n.fmt(locale, HarnessI18nKeys.COMMAND_HELP_NOT_FOUND, "name", name);
        }

        StringBuilder out = new StringBuilder(HarnessI18n.get(locale, HarnessI18nKeys.COMMAND_HELP_TITLE)).append("\n");
        for (Command command : commands) {
            if (!terminal && command.terminalOnly()) {
                continue;
            }
            appendCommandLine(out, command, locale);
        }
        out.append("\n").append(HarnessI18n.get(locale, HarnessI18nKeys.COMMAND_HELP_FOOTER)).append("\n");
        return trimTrailingNewline(out).toString();
    }

    private static void appendCommandLine(StringBuilder out, Command command, Locale locale) {
        out.append("/").append(command.name()).append(" - ")
                .append(safeDescription(command, locale)).append("\n");
    }

    private static String safeDescription(Command command, Locale locale) {
        String description = command.description(locale);
        return description == null || description.trim().isEmpty()
                ? HarnessI18n.get(locale, HarnessI18nKeys.COMMAND_HELP_NO_DESCRIPTION) : description.trim();
    }

    private static StringBuilder trimTrailingNewline(StringBuilder text) {
        while (text.length() > 0 && text.charAt(text.length() - 1) == '\n') {
            text.setLength(text.length() - 1);
        }
        return text;
    }
}