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
package org.noear.solon.ai.harness.session.loop;

import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.ai.harness.command.Command;
import org.noear.solon.ai.harness.command.CommandContext;
import org.noear.solon.ai.harness.input.InputOrigin;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * /loop 命令 —— 会话定时任务管理。
 *
 * <pre>
 *   /loop 5m check if deployment finished    → fixed interval (5m)
 *   /loop check ci status                   → auto interval (5m default)
 *   /loop cron:"0 *&#47;5 * * * ? *" check status  → cron expression (7 fields)
 *   /loop 5m --now check status             → run first round immediately
 *   /loop ls                                → list tasks
 *   /loop stop &lt;id&gt;                         → stop (delete) a task
 *   /loop stop-all                          → stop all tasks of this session
 *   /loop pause &lt;id&gt;                        → disable a scheduled task
 *   /loop resume &lt;id&gt;                       → enable a scheduled task
 * </pre>
 *
 * <p>停止任务只在该任务<b>正在执行</b>时才中断会话：否则会无端打断用户当前这一轮并清空队列。</p>
 *
 * @author noear
 * @since 2026.4.28
 */
final class LoopCommand implements Command {
    private static final String BOLD = "\033[1m";
    private static final String DIM = "\033[2m";
    private static final String GREEN = "\033[32m";
    private static final String YELLOW = "\033[33m";
    private static final String RED = "\033[31m";
    private static final String CYAN = "\033[36m";
    private static final String MAGENTA = "\033[35m";
    private static final String RESET = "\033[0m";

    private final LoopScheduler tasks;
    private volatile boolean enabled = true;

    LoopCommand(LoopScheduler tasks) {
        this.tasks = tasks;
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public void setEnabled(Boolean enabled) {
        if (enabled != null) {
            this.enabled = enabled;
        }
    }

    @Override
    public String name() {
        return "loop";
    }

    @Override
    public String description() {
        return "定时任务 (ls, stop, stop-all, pause, resume, <interval> <prompt>, cron:<expr> <prompt>)";
    }

    @Override
    public String[] examples() {
        return new String[]{
                "/loop ls",
                "/loop stop <id>",
                "/loop stop-all",
                "/loop pause <id>",
                "/loop resume <id>",
                "/loop 5m <prompt>",
                "/loop cron:\"<expr>\" <prompt> ",
        };
    }

    @Override
    public void execute(HarnessEngine engine, CommandContext ctx, InputOrigin origin) throws Exception {
        String sessionId = ctx.getSession().getSessionId();
        String sub = ctx.argAt(0);

        if (sub == null || sub.isEmpty() || "ls".equals(sub)) {
            doList(ctx, sessionId);
        } else if ("stop".equals(sub)) {
            String taskId = ctx.argAt(1);
            if (taskId == null || taskId.isEmpty()) {
                ctx.println(ctx.color(RED + "Usage: /loop stop <id>" + RESET));
                return;
            }
            LoopTask task = tasks.get(sessionId, taskId);
            if (task == null) {
                ctx.println(ctx.color(RED + "Loop task '" + taskId + "' not found." + RESET));
                return;
            }
            boolean wasRunning = task.isRunning();
            tasks.remove(sessionId, taskId);
            if (wasRunning) {
                // 只中断正在执行的那一轮（清队列 + 停流 + 补写取消消息与快照）
                engine.interrupt(ctx.getSession());
            }
            ctx.println(ctx.color(GREEN + "Loop task '" + taskId + "' stopped." + RESET));
        } else if ("stop-all".equals(sub)) {
            boolean anyRunning = false;
            for (LoopTask t : tasks.list(sessionId)) {
                anyRunning |= t.isRunning();
            }
            tasks.stopAll(sessionId);
            if (anyRunning) {
                engine.interrupt(ctx.getSession());
            }
            ctx.println(ctx.color(GREEN + "All loop tasks stopped." + RESET));
        } else if ("pause".equals(sub) || "resume".equals(sub)) {
            doPauseResume(ctx, sessionId, "resume".equals(sub));
        } else {
            doSchedule(ctx, sessionId);
        }
    }

    private void doPauseResume(CommandContext ctx, String sessionId, boolean resume) {
        String verb = resume ? "resume" : "pause";
        String taskId = ctx.argAt(1);
        if (taskId == null || taskId.isEmpty()) {
            ctx.println(ctx.color(RED + "Usage: /loop " + verb + " <id>" + RESET));
            return;
        }
        LoopTask task = tasks.get(sessionId, taskId);
        if (task == null) {
            ctx.println(ctx.color(RED + "Loop task '" + taskId + "' not found." + RESET));
            return;
        }
        if (task.isEnabled() != resume) {
            tasks.toggle(sessionId, taskId);
        }
        ctx.println(ctx.color(GREEN + "Loop task '" + taskId + "' " + (resume ? "resumed." : "paused.") + RESET));
    }

    /** 解析并创建新的 loop 任务 */
    private void doSchedule(CommandContext ctx, String sessionId) {
        int intervalMinutes = 5; // default
        String cronExpr = null;
        boolean runNow = false;

        int promptStartIndex = 0;
        String first = ctx.argAt(0);

        if (first != null && first.startsWith("cron:")) {
            cronExpr = first.substring(5).trim();
            if ((cronExpr.startsWith("\"") && cronExpr.endsWith("\"")) ||
                    (cronExpr.startsWith("'") && cronExpr.endsWith("'"))) {
                cronExpr = cronExpr.substring(1, cronExpr.length() - 1);
            }
            if (cronExpr.isEmpty()) {
                ctx.println(ctx.color(RED + "Usage: /loop cron:<expr> <prompt>" + RESET));
                ctx.println(ctx.color(DIM + "  /loop cron:\"0 */5 * * * ? *\" check status" + RESET));
                return;
            }
            promptStartIndex = 1;
        } else {
            Integer parsed = parseInterval(first);
            if (parsed != null) {
                intervalMinutes = parsed;
                promptStartIndex = 1;
            }
        }

        StringBuilder promptBuilder = new StringBuilder();
        for (int i = promptStartIndex; ; i++) {
            String arg = ctx.argAt(i);
            if (arg == null) break;

            if (arg.startsWith("--")) {
                String flag = arg.substring(2);
                if ("now".equals(flag)) {
                    runNow = true;
                } else {
                    ctx.println(ctx.color(YELLOW + "Unknown flag: --" + flag + RESET));
                }
            } else {
                if (promptBuilder.length() > 0) promptBuilder.append(" ");
                promptBuilder.append(arg);
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
        ctx.println(ctx.color("  " + DIM + "State:" + RESET + " persisted in workspace tasks.json"));
    }

    private void doList(CommandContext ctx, String sessionId) {
        List<LoopTask> list = tasks.list(sessionId);
        if (list.isEmpty()) {
            ctx.println(ctx.color(DIM + "No active loop tasks." + RESET));
            ctx.println(ctx.color(DIM + "Usage: /loop [interval] <prompt>" + RESET));
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
        ctx.println(ctx.color(DIM + "\nUsage: /loop stop <id> | /loop pause <id> | /loop resume <id> | /loop stop-all" + RESET));
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
        ctx.println(ctx.color(RED + "Usage: /loop [options] [interval|cron:<expr>] <prompt>" + RESET));
        ctx.println(ctx.color(DIM + "Basic:" + RESET));
        ctx.println(ctx.color(DIM + "  /loop 5m check deployment" + RESET));
        ctx.println(ctx.color(DIM + "  /loop 30s check CI status" + RESET));
        ctx.println(ctx.color(DIM + "  /loop check CI status   (auto 5m)" + RESET));
        ctx.println(ctx.color(DIM + "  /loop cron:\"0 */5 * * * ? *\" check status" + RESET));
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
