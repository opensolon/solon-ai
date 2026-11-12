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
package org.noear.solon.ai.harness.extension.loop;

import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.ai.harness.InterruptMode;
import org.noear.solon.ai.harness.command.AbsCommand;
import org.noear.solon.ai.harness.command.CommandInput;
import org.noear.solon.ai.harness.command.CommandOutput;
import org.noear.solon.ai.harness.extension.loop.i18n.LoopI18n;
import org.noear.solon.ai.harness.extension.loop.i18n.LoopI18nKeys;
import org.noear.solon.lang.Nullable;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;

/**
 * /loop 命令 —— 会话定时任务管理。
 *
 * <pre>
 *   /loop check ci status                     → 缺省每 5 分钟
 *   /loop --every 5m check deployment          → 固定间隔（s / m / h）
 *   /loop --cron "0 *&#47;5 * * * ? *" check     → cron 表达式（7 段）
 *   /loop --every 5m --now check status        → 立即跑首轮
 *   /loop list                                 → 列出任务
 *   /loop remove &lt;id&gt;                          → 删除任务
 *   /loop clear                                → 删除本会话全部任务
 *   /loop pause &lt;id&gt; | /loop resume &lt;id&gt;       → 暂停 / 恢复
 *   /loop -- list the files                    → 「--」之后一律按提示词处理
 * </pre>
 *
 * <p>旧写法继续可用（帮助里只展示新写法）：{@code ls}、{@code stop <id>}、{@code stop-all}、
 * 首参 {@code 5m}、首参 {@code cron:"expr"}。子命令只在参数个数完全匹配时生效
 * （{@code list}/{@code clear} 独占整行、{@code remove/pause/resume} 后恰好一个 id），
 * 「list the files every hour」这类自然语言照常作为提示词。</p>
 *
 * <p>停止任务只在该任务<b>正在执行</b>时才停流，且用程序级取消（{@code InterruptMode.CANCEL_STREAM}）：
 * 定时轮次是程序发起的，不应清空用户的排队，也不应在历史里留下「用户已取消」。</p>
 *
 * <p><b>语言</b>：回执按命令上下文的语言输出；新建的任务记下这个语言，之后每一轮都用它（见 {@link LoopTask#getLocale()}）。
 * 列表里的 {@code [heartbeat]}、{@code [now]}、{@code cron:} 是终端标记（机器可读的形态），不随语言翻译。</p>
 *
 * @author noear
 * @since 4.2.0
 */
final class LoopCommand extends AbsCommand {
    private static final String BOLD = "\033[1m";
    private static final String DIM = "\033[2m";
    private static final String GREEN = "\033[32m";
    private static final String YELLOW = "\033[33m";
    private static final String RED = "\033[31m";
    private static final String CYAN = "\033[36m";
    private static final String MAGENTA = "\033[35m";
    private static final String RESET = "\033[0m";

    private final LoopScheduler tasks;

    LoopCommand(LoopScheduler tasks) {
        this.tasks = tasks;
    }

    @Override
    public String name() {
        return "loop";
    }

    @Override
    public String description(@Nullable Locale locale) {
        return LoopI18n.get(locale, LoopI18nKeys.COMMAND_LOOP_DESCRIPTION);
    }

    @Override
    public String[] examples(@Nullable Locale locale) {
        return new String[]{
                "/loop --every 5m <prompt>",
                "/loop --cron \"<expr>\" <prompt>",
                "/loop list",
                "/loop remove <id>",
                "/loop clear",
                "/loop pause <id>",
                "/loop resume <id>",
        };
    }

    @Override
    public void execute(HarnessEngine engine, CommandInput input, CommandOutput output) throws Exception {
        String sessionId = input.getSession().getSessionId();
        Locale locale = input.getLocale();
        int argc = input.argCount();
        String sub = input.argAt(0);

        if (argc == 0 || (argc == 1 && ("list".equals(sub) || "ls".equals(sub)))) {
            doList(locale, output, sessionId);
        } else if (argc == 2 && ("remove".equals(sub) || "stop".equals(sub))) {
            doRemove(engine, input, output, sessionId, input.argAt(1));
        } else if (argc == 1 && ("clear".equals(sub) || "stop-all".equals(sub))) {
            boolean anyRunning = false;
            for (LoopTask t : tasks.list(sessionId)) {
                anyRunning |= t.isRunning();
            }
            tasks.removeAll(sessionId);
            if (anyRunning) {
                // 程序级取消：不清用户的排队、不写「用户已取消」消息
                engine.interrupt(input.getSession(), InterruptMode.CANCEL_STREAM);
            }
            output.println(output.color(GREEN + LoopI18n.get(locale, LoopI18nKeys.COMMAND_LOOP_CLEARED) + RESET));
        } else if (argc == 2 && ("pause".equals(sub) || "resume".equals(sub))) {
            doPauseResume(input, output, sessionId, "resume".equals(sub));
        } else {
            doSchedule(input, output, sessionId);
        }
    }

    private void doRemove(HarnessEngine engine, CommandInput input, CommandOutput output, String sessionId, String taskId) {
        Locale locale = input.getLocale();
        LoopTask task = tasks.get(sessionId, taskId);
        if (task == null) {
            output.println(output.color(RED + LoopI18n.fmt(locale, LoopI18nKeys.COMMAND_LOOP_NOT_FOUND, "id", taskId) + RESET));
            return;
        }
        boolean wasRunning = task.isRunning();
        tasks.remove(sessionId, taskId);
        if (wasRunning) {
            // 只停正在执行的那一轮（程序级取消：不清用户的排队、不写「用户已取消」消息）
            engine.interrupt(input.getSession(), InterruptMode.CANCEL_STREAM);
        }
        output.println(output.color(GREEN + LoopI18n.fmt(locale, LoopI18nKeys.COMMAND_LOOP_REMOVED, "id", taskId) + RESET));
    }

    private void doPauseResume(CommandInput input, CommandOutput output, String sessionId, boolean resume) {
        Locale locale = input.getLocale();
        String taskId = input.argAt(1);
        if (taskId == null || taskId.isEmpty()) {
            output.println(output.color(RED + LoopI18n.fmt(locale, LoopI18nKeys.COMMAND_LOOP_ID_USAGE,
                    "action", resume ? "resume" : "pause") + RESET));
            return;
        }
        if (!tasks.setEnabled(sessionId, taskId, resume)) {
            output.println(output.color(RED + LoopI18n.fmt(locale, LoopI18nKeys.COMMAND_LOOP_NOT_FOUND, "id", taskId) + RESET));
            return;
        }
        String done = resume
                ? LoopI18n.fmt(locale, LoopI18nKeys.COMMAND_LOOP_RESUMED, "id", taskId)
                : LoopI18n.fmt(locale, LoopI18nKeys.COMMAND_LOOP_PAUSED, "id", taskId);
        output.println(output.color(GREEN + done + RESET));
    }

    /** 解析并创建新的 loop 任务 */
    private void doSchedule(CommandInput input, CommandOutput output, String sessionId) {
        Locale locale = input.getLocale();
        int intervalMinutes = 5; // default
        String cronExpr = null;
        boolean runNow = false;
        StringBuilder promptBuilder = new StringBuilder();

        int argc = input.argCount();
        for (int i = 0; i < argc; i++) {
            String arg = input.argAt(i);
            if ("--".equals(arg)) {
                // 之后一律是提示词
                for (int j = i + 1; j < argc; j++) {
                    appendWord(promptBuilder, input.argAt(j));
                }
                break;
            }
            if ("--every".equals(arg) || "--cron".equals(arg)) {
                String value = i + 1 < argc ? input.argAt(++i) : null;
                if ("--every".equals(arg)) {
                    Integer parsed = parseInterval(value);
                    if (parsed == null) {
                        output.println(output.color(RED + LoopI18n.fmt(locale, LoopI18nKeys.COMMAND_LOOP_BAD_INTERVAL,
                                "value", value) + RESET));
                        return;
                    }
                    intervalMinutes = parsed;
                } else {
                    cronExpr = unquote(value);
                    if (cronExpr == null || cronExpr.isEmpty()) {
                        printUsage(locale, output);
                        return;
                    }
                }
            } else if ("--now".equals(arg)) {
                runNow = true;
            } else if (arg.startsWith("--")) {
                output.println(output.color(YELLOW + LoopI18n.fmt(locale, LoopI18nKeys.COMMAND_LOOP_UNKNOWN_FLAG,
                        "flag", arg) + RESET));
            } else if (i == 0 && arg.startsWith("cron:")) {
                // 旧写法：cron:"expr"
                cronExpr = unquote(arg.substring(5).trim());
                if (cronExpr.isEmpty()) {
                    printUsage(locale, output);
                    return;
                }
            } else if (i == 0 && parseInterval(arg) != null) {
                // 旧写法：首参 5m
                intervalMinutes = parseInterval(arg);
            } else {
                appendWord(promptBuilder, arg);
            }
        }

        String prompt = promptBuilder.toString().trim();
        if (prompt.isEmpty()) {
            printUsage(locale, output);
            return;
        }

        // 任务记下命令上下文的语言：之后的定时轮次由后台调度发起，没有输入可取语言
        LoopTask task = new LoopTask(prompt, intervalMinutes, cronExpr, runNow, locale);
        try {
            tasks.schedule(sessionId, task);
        } catch (IllegalArgumentException | IllegalStateException e) {
            output.println(output.color(RED + LoopI18n.fmt(locale, LoopI18nKeys.COMMAND_LOOP_FAILED,
                    "reason", LoopArgumentException.messageOf(e, locale)) + RESET));
            return;
        }

        output.println(output.color(GREEN + LoopI18n.get(locale, LoopI18nKeys.COMMAND_LOOP_REGISTERED) + RESET));
        output.println(output.color(field(BOLD, "ID:", task.getId())));
        if (task.isCronMode()) {
            output.println(output.color(field(BOLD, "Cron:", task.getCron())));
        } else {
            output.println(output.color(field(BOLD, LoopI18n.get(locale, LoopI18nKeys.COMMAND_LOOP_FIELD_INTERVAL),
                    formatInterval(task.getIntervalMinutes(), locale))));
        }
        output.println(output.color(field(BOLD, LoopI18n.get(locale, LoopI18nKeys.COMMAND_LOOP_FIELD_PROMPT), prompt)));
        if (runNow) {
            output.println(output.color(field(MAGENTA, LoopI18n.get(locale, LoopI18nKeys.COMMAND_LOOP_FIELD_RUN_NOW),
                    LoopI18n.get(locale, LoopI18nKeys.COMMAND_LOOP_RUN_NOW_FIRST))));
        }
        if (tasks.isPersistent()) {
            output.println(output.color(field(DIM, LoopI18n.get(locale, LoopI18nKeys.COMMAND_LOOP_FIELD_STATE),
                    LoopI18n.get(locale, LoopI18nKeys.COMMAND_LOOP_STATE_PERSISTED))));
        }
    }

    /** 详情行：两格缩进 + 着色的字段名 + 值 */
    private static String field(String labelColor, String label, String value) {
        return "  " + labelColor + label + RESET + " " + value;
    }

    private static void appendWord(StringBuilder buf, String word) {
        if (buf.length() > 0) buf.append(" ");
        buf.append(word);
    }

    private static String unquote(String value) {
        if (value == null) return null;
        String v = value.trim();
        if (v.length() >= 2 && ((v.startsWith("\"") && v.endsWith("\"")) || (v.startsWith("'") && v.endsWith("'")))) {
            v = v.substring(1, v.length() - 1);
        }
        return v;
    }

    private void doList(@Nullable Locale locale, CommandOutput output, String sessionId) {
        List<LoopTask> list = tasks.list(sessionId);
        if (list.isEmpty()) {
            output.println(output.color(DIM + LoopI18n.get(locale, LoopI18nKeys.COMMAND_LOOP_LIST_EMPTY) + RESET));
            output.println(output.color(DIM + LoopI18n.get(locale, LoopI18nKeys.COMMAND_LOOP_USAGE) + RESET));
            return;
        }
        output.println(output.color(BOLD + LoopI18n.get(locale, LoopI18nKeys.COMMAND_LOOP_LIST_TITLE) + RESET));
        for (LoopTask t : list) {
            output.println(output.color(formatTaskLine(t, locale)));
        }
        output.println(output.color(DIM + "\n" + LoopI18n.get(locale, LoopI18nKeys.COMMAND_LOOP_LIST_FOOTER) + RESET));
    }

    /** 列表行 */
    private static String formatTaskLine(LoopTask t, @Nullable Locale locale) {
        String status;
        if (!t.isEnabled()) {
            status = DIM + LoopI18n.get(locale, LoopI18nKeys.COMMAND_LOOP_STATUS_PAUSED) + RESET;
        } else if (t.isRunning()) {
            status = YELLOW + LoopI18n.get(locale, LoopI18nKeys.COMMAND_LOOP_STATUS_RUNNING) + RESET;
        } else {
            status = GREEN + LoopI18n.get(locale, LoopI18nKeys.COMMAND_LOOP_STATUS_IDLE) + RESET;
        }
        String scheduleInfo = t.isCronMode()
                ? CYAN + "cron:" + t.getCron() + RESET
                : formatInterval(t.getIntervalMinutes(), locale);
        String lastInfo = t.getLastExecutedAt() != null
                ? DIM + " " + LoopI18n.fmt(locale, LoopI18nKeys.COMMAND_LOOP_LIST_LAST,
                "ago", formatAgo(t.getLastExecutedAt(), locale),
                "result", t.getLastResult() != null ? t.getLastResult() : "-") + RESET
                : "";

        StringBuilder line = new StringBuilder();
        line.append("  ").append(CYAN).append(t.getId()).append(RESET);
        line.append(" ").append(CYAN).append("[heartbeat]").append(RESET);
        line.append(" ").append(scheduleInfo);
        line.append(" ").append(status);
        if (t.isRunNow()) {
            line.append(" ").append(MAGENTA).append("[now]").append(RESET);
        }
        line.append(" ").append(DIM)
                .append(LoopI18n.fmt(locale, LoopI18nKeys.COMMAND_LOOP_LIST_ITERATIONS, "count", t.getCurrentIteration()))
                .append(RESET);
        line.append(" ").append(DIM).append(t.getPrompt()).append(RESET);
        line.append(lastInfo);
        return line.toString();
    }

    /**
     * 解析时间间隔字符串，支持: s/sec/seconds, m/min/minutes, h/hour/hours
     *
     * @return 间隔分钟数（秒数向上取整为分钟）；若格式不匹配返回 null
     */
    static Integer parseInterval(String s) {
        if (s == null || s.isEmpty()) return null;

        int splitIdx = 0;
        while (splitIdx < s.length() && Character.isDigit(s.charAt(splitIdx))) {
            splitIdx++;
        }
        if (splitIdx == 0) return null;

        int value;
        try {
            value = Integer.parseInt(s.substring(0, splitIdx));
        } catch (NumberFormatException e) {
            return null;
        }
        if (value <= 0) return null;

        int minutes;
        switch (s.substring(splitIdx).toLowerCase()) {
            case "s":
            case "sec":
            case "seconds":
                minutes = Math.max(1, (value + 59) / 60);
                break;
            case "m":
            case "min":
            case "minutes":
                minutes = value;
                break;
            case "h":
            case "hour":
            case "hours":
                minutes = value * 60;
                break;
            default:
                return null;
        }
        return Math.max(1, Math.min(1440, minutes));
    }

    private static void printUsage(@Nullable Locale locale, CommandOutput output) {
        output.println(output.color(RED + LoopI18n.get(locale, LoopI18nKeys.COMMAND_LOOP_USAGE) + RESET));
        // 示例多行：逐行着色（终端里每行各自生效，不依赖跨行的转义序列）
        for (String line : LoopI18n.get(locale, LoopI18nKeys.COMMAND_LOOP_USAGE_EXAMPLES).split("\n")) {
            output.println(output.color(DIM + line + RESET));
        }
    }

    /** 间隔展示 */
    private static String formatInterval(int minutes, @Nullable Locale locale) {
        if (minutes >= 60 && minutes % 60 == 0) {
            return LoopI18n.fmt(locale, LoopI18nKeys.COMMAND_LOOP_EVERY_HOURS, "n", minutes / 60);
        }
        return LoopI18n.fmt(locale, LoopI18nKeys.COMMAND_LOOP_EVERY_MINUTES, "n", minutes);
    }

    /** 距今时长 */
    private static String formatAgo(Instant instant, @Nullable Locale locale) {
        long seconds = Duration.between(instant, Instant.now()).getSeconds();
        if (seconds < 60) {
            return LoopI18n.fmt(locale, LoopI18nKeys.COMMAND_LOOP_AGO_SECONDS, "n", seconds);
        }
        if (seconds < 3600) {
            return LoopI18n.fmt(locale, LoopI18nKeys.COMMAND_LOOP_AGO_MINUTES, "n", seconds / 60);
        }
        return LoopI18n.fmt(locale, LoopI18nKeys.COMMAND_LOOP_AGO_HOURS, "n", seconds / 3600);
    }
}
