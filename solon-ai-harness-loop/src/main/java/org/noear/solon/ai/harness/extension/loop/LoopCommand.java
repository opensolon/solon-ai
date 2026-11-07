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
import org.noear.solon.ai.harness.command.CommandContext;
import org.noear.solon.ai.harness.input.InputOrigin;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

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
    public String description() {
        return "定时任务 (<prompt>, --every <interval>, --cron <expr>, --now, list, remove, clear, pause, resume)";
    }

    @Override
    public String[] examples() {
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
    public void execute(HarnessEngine engine, CommandContext ctx, InputOrigin origin) throws Exception {
        String sessionId = ctx.getSession().getSessionId();
        int argc = ctx.argCount();
        String sub = ctx.argAt(0);

        if (argc == 0 || (argc == 1 && ("list".equals(sub) || "ls".equals(sub)))) {
            doList(ctx, sessionId);
        } else if (argc == 2 && ("remove".equals(sub) || "stop".equals(sub))) {
            doRemove(engine, ctx, sessionId, ctx.argAt(1));
        } else if (argc == 1 && ("clear".equals(sub) || "stop-all".equals(sub))) {
            boolean anyRunning = false;
            for (LoopTask t : tasks.list(sessionId)) {
                anyRunning |= t.isRunning();
            }
            tasks.removeAll(sessionId);
            if (anyRunning) {
                // 程序级取消：不清用户的排队、不写「用户已取消」消息
                engine.interrupt(ctx.getSession(), InterruptMode.CANCEL_STREAM);
            }
            ctx.println(ctx.color(GREEN + "All loop tasks removed." + RESET));
        } else if (argc == 2 && ("pause".equals(sub) || "resume".equals(sub))) {
            doPauseResume(ctx, sessionId, "resume".equals(sub));
        } else {
            doSchedule(ctx, sessionId);
        }
    }

    private void doRemove(HarnessEngine engine, CommandContext ctx, String sessionId, String taskId) {
        LoopTask task = tasks.get(sessionId, taskId);
        if (task == null) {
            ctx.println(ctx.color(RED + "Loop task '" + taskId + "' not found." + RESET));
            return;
        }
        boolean wasRunning = task.isRunning();
        tasks.remove(sessionId, taskId);
        if (wasRunning) {
            // 只停正在执行的那一轮（程序级取消：不清用户的排队、不写「用户已取消」消息）
            engine.interrupt(ctx.getSession(), InterruptMode.CANCEL_STREAM);
        }
        ctx.println(ctx.color(GREEN + "Loop task '" + taskId + "' removed." + RESET));
    }

    private void doPauseResume(CommandContext ctx, String sessionId, boolean resume) {
        String verb = resume ? "resume" : "pause";
        String taskId = ctx.argAt(1);
        if (taskId == null || taskId.isEmpty()) {
            ctx.println(ctx.color(RED + "Usage: /loop " + verb + " <id>" + RESET));
            return;
        }
        if (!tasks.setEnabled(sessionId, taskId, resume)) {
            ctx.println(ctx.color(RED + "Loop task '" + taskId + "' not found." + RESET));
            return;
        }
        ctx.println(ctx.color(GREEN + "Loop task '" + taskId + "' " + (resume ? "resumed." : "paused.") + RESET));
    }

    /** 解析并创建新的 loop 任务 */
    private void doSchedule(CommandContext ctx, String sessionId) {
        int intervalMinutes = 5; // default
        String cronExpr = null;
        boolean runNow = false;
        StringBuilder promptBuilder = new StringBuilder();

        int argc = ctx.argCount();
        for (int i = 0; i < argc; i++) {
            String arg = ctx.argAt(i);
            if ("--".equals(arg)) {
                // 之后一律是提示词
                for (int j = i + 1; j < argc; j++) {
                    appendWord(promptBuilder, ctx.argAt(j));
                }
                break;
            }
            if ("--every".equals(arg) || "--cron".equals(arg)) {
                String value = i + 1 < argc ? ctx.argAt(++i) : null;
                if ("--every".equals(arg)) {
                    Integer parsed = parseInterval(value);
                    if (parsed == null) {
                        ctx.println(ctx.color(RED + "Invalid interval: " + value + " (e.g. 30s, 5m, 2h)" + RESET));
                        return;
                    }
                    intervalMinutes = parsed;
                } else {
                    cronExpr = unquote(value);
                    if (cronExpr == null || cronExpr.isEmpty()) {
                        printUsage(ctx);
                        return;
                    }
                }
            } else if ("--now".equals(arg)) {
                runNow = true;
            } else if (arg.startsWith("--")) {
                ctx.println(ctx.color(YELLOW + "Unknown flag: " + arg + RESET));
            } else if (i == 0 && arg.startsWith("cron:")) {
                // 旧写法：cron:"expr"
                cronExpr = unquote(arg.substring(5).trim());
                if (cronExpr.isEmpty()) {
                    printUsage(ctx);
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
            printUsage(ctx);
            return;
        }

        LoopTask task = new LoopTask(prompt, intervalMinutes, cronExpr, runNow);
        try {
            tasks.schedule(sessionId, task);
        } catch (IllegalArgumentException | IllegalStateException e) {
            ctx.println(ctx.color(RED + "Failed: " + e.getMessage() + RESET));
            return;
        }

        ctx.println(ctx.color(GREEN + "Loop task registered:" + RESET));
        ctx.println(ctx.color("  " + BOLD + "ID:" + RESET + " " + task.getId()));
        if (task.isCronMode()) {
            ctx.println(ctx.color("  " + BOLD + "Cron:" + RESET + " " + task.getCron()));
        } else {
            ctx.println(ctx.color("  " + BOLD + "Interval:" + RESET + " " + formatInterval(task.getIntervalMinutes())));
        }
        ctx.println(ctx.color("  " + BOLD + "Prompt:" + RESET + " " + prompt));
        if (runNow) {
            ctx.println(ctx.color("  " + MAGENTA + "Run Now:" + RESET + " first execution immediately"));
        }
        if (tasks.isPersistent()) {
            ctx.println(ctx.color("  " + DIM + "State:" + RESET + " persisted"));
        }
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

    private void doList(CommandContext ctx, String sessionId) {
        List<LoopTask> list = tasks.list(sessionId);
        if (list.isEmpty()) {
            ctx.println(ctx.color(DIM + "No active loop tasks." + RESET));
            ctx.println(ctx.color(DIM + "Usage: /loop [--every 5m | --cron <expr>] [--now] <prompt>" + RESET));
            return;
        }
        ctx.println(ctx.color(BOLD + "Active Loop Tasks:" + RESET));
        for (LoopTask t : list) {
            String status = !t.isEnabled() ? DIM + "paused" + RESET
                    : (t.isRunning() ? YELLOW + "running" + RESET : GREEN + "idle" + RESET);
            String scheduleInfo = t.isCronMode()
                    ? CYAN + "cron:" + t.getCron() + RESET
                    : formatInterval(t.getIntervalMinutes());
            String lastInfo = t.getLastExecutedAt() != null
                    ? DIM + " (last: " + formatAgo(t.getLastExecutedAt()) + ": "
                    + (t.getLastResult() != null ? t.getLastResult() : "-") + ")" + RESET
                    : "";

            StringBuilder line = new StringBuilder();
            line.append("  ").append(CYAN).append(t.getId()).append(RESET);
            line.append(" ").append(CYAN).append("[heartbeat]").append(RESET);
            line.append(" ").append(scheduleInfo);
            line.append(" ").append(status);
            if (t.isRunNow()) {
                line.append(" ").append(MAGENTA).append("[now]").append(RESET);
            }
            line.append(" ").append(DIM).append("iter:").append(t.getCurrentIteration()).append(RESET);
            line.append(" ").append(DIM).append(t.getPrompt()).append(RESET);
            line.append(lastInfo);
            ctx.println(ctx.color(line.toString()));
        }
        ctx.println(ctx.color(DIM + "\nUsage: /loop remove <id> | /loop pause <id> | /loop resume <id> | /loop clear" + RESET));
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

    private void printUsage(CommandContext ctx) {
        ctx.println(ctx.color(RED + "Usage: /loop [--every <interval> | --cron <expr>] [--now] <prompt>" + RESET));
        ctx.println(ctx.color(DIM + "Basic:" + RESET));
        ctx.println(ctx.color(DIM + "  /loop --every 5m check deployment" + RESET));
        ctx.println(ctx.color(DIM + "  /loop --every 30s check CI status" + RESET));
        ctx.println(ctx.color(DIM + "  /loop check CI status   (auto 5m)" + RESET));
        ctx.println(ctx.color(DIM + "  /loop --cron \"0 */5 * * * ? *\" check status" + RESET));
    }

    private static String formatInterval(int minutes) {
        if (minutes >= 60 && minutes % 60 == 0) {
            return "every " + (minutes / 60) + "h";
        }
        return "every " + minutes + "m";
    }

    private static String formatAgo(Instant instant) {
        long seconds = Duration.between(instant, Instant.now()).getSeconds();
        if (seconds < 60) return seconds + "s ago";
        if (seconds < 3600) return (seconds / 60) + "m ago";
        return (seconds / 3600) + "h ago";
    }
}
