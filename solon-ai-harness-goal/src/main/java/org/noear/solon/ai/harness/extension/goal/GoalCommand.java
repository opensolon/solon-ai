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
package org.noear.solon.ai.harness.extension.goal;

import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.ai.harness.command.AbsCommand;
import org.noear.solon.ai.harness.command.CommandContext;
import org.noear.solon.ai.harness.input.InputOrigin;

/**
 * /goal 命令 —— 会话级目标的独立命令（不再经由 loop 命令转发）。
 *
 * <p>Goal 属于会话且每会话最多一个：创建时若已有进行中的目标会被拒绝，
 * 需先用 {@code /goal clear} 清除。状态落在会话快照中，随对话一起持久化。</p>
 *
 * <p><b>只持 {@link GoalExtension} 一个引用。</b>命令与编程 API 走同一条路径：
 * 创建时把 flag 解析成 {@link GoalSpec} 后调 {@code extension.create(session, spec)}，
 * pause/resume/clear 直接转调扩展的方法（含调度注销/重挂）。
 * 此前命令自行持有 {@code GoalRuntime + GoalScheduler} 并逐行复刻扩展的三个子命令，
 * 导致「命令可用但编程门面行为不同」的双实现漂移风险。</p>
 *
 * @author noear
 */
class GoalCommand extends AbsCommand {
    private static final String BOLD = "\033[1m";
    private static final String DIM = "\033[2m";
    private static final String GREEN = "\033[32m";
    private static final String YELLOW = "\033[33m";
    private static final String RED = "\033[31m";
    private static final String CYAN = "\033[36m";
    private static final String MAGENTA = "\033[35m";
    private static final String RESET = "\033[0m";

    private final GoalExtension goal;

    /**
     * @param goal goal 扩展（由 {@link GoalExtension#onAttach} 以自身实例注入）
     */
    GoalCommand(GoalExtension goal) {
        this.goal = goal;
    }

    @Override
    public String name() {
        return "goal";
    }

    @Override
    public String description() {
        return "设置并追踪会话目标（每会话一个）";
    }

    @Override
    public String[] examples() {
        return new String[]{
                "/goal <objective>",
                "/goal --max-tokens 2000 <objective>",
                "/goal --max-duration 30m <objective>",
                "/goal --max-iterations 20 <objective>",
                "/goal pause | resume | clear",
                "/goal -- <objective starting with a sub-command word>",
                "/goal"
        };
    }

    @Override
    public void execute(HarnessEngine engine, CommandContext ctx, InputOrigin origin) throws Exception {
        AgentSession session = ctx.getSession();
        if (session == null) {
            ctx.println(ctx.color(RED + "No active session." + RESET));
            return;
        }

        String first = ctx.argAt(0);

        // 无参：展示当前目标状态
        if (first == null) {
            printStatus(ctx, session);
            return;
        }

        // 子命令只在「恰好一个参数」时生效："/goal pause the build" 是目标正文，不是暂停。
        // 与 /loop、/queue 同口径；全部转调 GoalExtension（避免与门面实现分叉）
        if (ctx.argCount() == 1) {
            switch (first.toLowerCase()) {
                case "pause": {
                    pauseSub(ctx, session);
                    return;
                }
                case "resume": {
                    resumeSub(ctx, session);
                    return;
                }
                case "clear":
                case "remove": {
                    clearSub(ctx, session);
                    return;
                }
                default:
                    break;
            }
        }

        // 创建目标：flag → GoalSpec → 与编程 API 同一条路径
        GoalSpec spec;
        try {
            spec = parseSpec(ctx);
        } catch (IllegalArgumentException e) {
            ctx.println(ctx.color(RED + e.getMessage() + RESET));
            ctx.println(ctx.color(DIM + "  Usage: /goal [--max-tokens N] [--max-duration 30m] [--max-iterations N] <objective>" + RESET));
            ctx.println(ctx.color(DIM + "  /goal 修复登录模块的鉴权缺陷" + RESET));
            return;
        }

        try {
            GoalSnapshot task = goal.create(session, spec);
            printCreated(ctx, task);
        } catch (IllegalStateException e) {
            GoalSnapshot existing = goal.snapshot(session);
            ctx.println(ctx.color(YELLOW + "WARN: " + e.getMessage() + RESET));
            if (existing != null) {
                ctx.println(ctx.color(DIM + "  Active: " + existing.getObjective() + RESET));
                ctx.println(ctx.color(DIM + "  Use /goal clear first to replace the goal." + RESET));
            }
        }
    }

    /**
     * flag 解析：本类唯一的文本解析点，产出 {@link GoalSpec} 后文本形态即被丢弃。
     *
     * <p>写法：{@code --max-tokens N}、{@code --max-duration 30m}（s / m / h，纯数字按分钟）、
     * {@code --max-iterations N}；旧写法 {@code --key:value} 仍可用。{@code --} 之后全部当作正文。</p>
     *
     * @throws IllegalArgumentException 数字非法或缺值（调用方据此打印用法）
     */
    private GoalSpec parseSpec(CommandContext ctx) {
        StringBuilder objective = new StringBuilder();
        Long maxTokens = null;
        Long maxDurationMs = null;
        Integer maxIterations = null;

        int argc = ctx.argCount();
        for (int i = 0; i < argc; i++) {
            String arg = ctx.argAt(i);

            if ("--".equals(arg)) {
                // 转义：之后的全部当作正文（允许正文以 pause / -- 等开头）
                for (int j = i + 1; j < argc; j++) {
                    appendWord(objective, ctx.argAt(j));
                }
                break;
            }

            if (arg.startsWith("--") == false) {
                appendWord(objective, arg);
                continue;
            }

            String flag = arg.substring(2);
            String key;
            String val;
            int colonIdx = flag.indexOf(':');
            if (colonIdx > 0) {
                // 旧写法 --key:value
                key = flag.substring(0, colonIdx);
                val = flag.substring(colonIdx + 1);
            } else {
                key = flag;
                val = null;
            }

            if (isKnownFlag(key) == false) {
                ctx.println(ctx.color(YELLOW + "Unknown flag: --" + key + RESET));
                continue;
            }
            if (val == null) {
                val = i + 1 < argc ? ctx.argAt(++i) : null;
                if (val == null) {
                    throw new IllegalArgumentException("Missing value for --" + key);
                }
            }

            try {
                switch (key) {
                    case "max-tokens":
                        maxTokens = Long.parseLong(val);
                        break;
                    case "max-duration":
                        maxDurationMs = parseDurationMs(val);
                        break;
                    default:
                        maxIterations = Integer.parseInt(val);
                        break;
                }
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Invalid --" + key + " value: " + val);
            }
        }

        GoalSpec spec = GoalSpec.of(objective.toString());
        if (maxTokens != null) spec.maxTokens(maxTokens);
        if (maxDurationMs != null) spec.maxDurationMs(maxDurationMs);
        if (maxIterations != null) spec.maxIterations(maxIterations);
        return spec;
    }

    private static boolean isKnownFlag(String key) {
        return "max-tokens".equals(key) || "max-duration".equals(key) || "max-iterations".equals(key);
    }

    private static void appendWord(StringBuilder buf, String word) {
        if (buf.length() > 0) {
            buf.append(' ');
        }
        buf.append(word);
    }

    /**
     * 时长解析（毫秒）：{@code 90s}、{@code 30m}、{@code 2h}；纯数字按分钟（兼容旧写法 {@code --max-duration:30}）。
     *
     * @throws NumberFormatException 格式非法
     */
    static long parseDurationMs(String value) {
        String s = value.trim().toLowerCase();
        if (s.isEmpty()) {
            throw new NumberFormatException(value);
        }
        long unitMs;
        char last = s.charAt(s.length() - 1);
        if (last == 's') {
            unitMs = 1_000L;
        } else if (last == 'm') {
            unitMs = 60_000L;
        } else if (last == 'h') {
            unitMs = 3_600_000L;
        } else {
            return Long.parseLong(s) * 60_000L;
        }
        return Long.parseLong(s.substring(0, s.length() - 1)) * unitMs;
    }

    private void pauseSub(CommandContext ctx, AgentSession session) {
        GoalSnapshot gs = goal.snapshot(session);
        if (gs == null) {
            ctx.println(ctx.color(DIM + "No goal in this session." + RESET));
            return;
        }
        if (goal.pause(session) == false) {
            ctx.println(ctx.color(YELLOW + "Cannot pause goal in state: " + gs.getStatus() + RESET));
            return;
        }
        ctx.println(ctx.color(GREEN + "Goal paused." + RESET));
    }

    private void resumeSub(CommandContext ctx, AgentSession session) {
        GoalSnapshot gs = goal.snapshot(session);
        if (gs == null) {
            ctx.println(ctx.color(DIM + "No goal in this session." + RESET));
            return;
        }
        if (goal.resume(session) == false) {
            ctx.println(ctx.color(YELLOW + "Cannot resume goal in state: " + gs.getStatus() + RESET));
            return;
        }
        ctx.println(ctx.color(GREEN + "Goal resumed." + RESET));
    }

    private void clearSub(CommandContext ctx, AgentSession session) {
        if (goal.remove(session) == false) {
            ctx.println(ctx.color(DIM + "No goal in this session." + RESET));
            return;
        }
        ctx.println(ctx.color(GREEN + "Goal cleared." + RESET));
    }

    private void printCreated(CommandContext ctx, GoalSnapshot gs) {
        ctx.println(ctx.color(GREEN + "Goal registered:" + RESET));
        ctx.println(ctx.color("  " + BOLD + "Objective:" + RESET + " " + gs.getObjective()));
        if (gs.getMaxTokens() > 0) {
            ctx.println(ctx.color("  " + MAGENTA + "Max Tokens:" + RESET + " " + gs.getMaxTokens()));
        }
        if (gs.getMaxDurationMs() > 0) {
            ctx.println(ctx.color("  " + MAGENTA + "Max Duration:" + RESET + " " + (gs.getMaxDurationMs() / 60_000L) + "m"));
        }
        if (gs.getMaxIterations() > 0) {
            ctx.println(ctx.color("  " + MAGENTA + "Max Iterations:" + RESET + " " + gs.getMaxIterations()));
        }
        ctx.println(ctx.color("  " + DIM + "State:" + RESET + " saved with this conversation"));
    }

    private void printStatus(CommandContext ctx, AgentSession session) {
        GoalSnapshot gs = goal.snapshot(session);
        if (gs == null) {
            ctx.println(ctx.color(DIM + "No goal in this session." + RESET));
            ctx.println(ctx.color(DIM + "Usage: /goal <objective>" + RESET));
            return;
        }

        long elapsedSec = (System.currentTimeMillis() - gs.getStartEpochMs()) / 1000;
        ctx.println(ctx.color(BOLD + "Goal:" + RESET + " " + gs.getObjective()));
        ctx.println(ctx.color("  Status: " + statusColor(gs.getStatus()) + gs.getStatus() + RESET));
        ctx.println(ctx.color("  Iteration: " + gs.getCurrentIteration()
                + (gs.getMaxIterations() > 0 ? " / " + gs.getMaxIterations() : "")));
        ctx.println(ctx.color("  Tokens: " + gs.getConsumedTokens()
                + (gs.getMaxTokens() > 0 ? " / " + gs.getMaxTokens() : "")));
        ctx.println(ctx.color("  Elapsed: " + formatElapsed(elapsedSec)));
        if (gs.getLastResult() != null) {
            ctx.println(ctx.color(DIM + "  Last: " + truncate(gs.getLastResult(), 100) + RESET));
        }
    }

    private static String statusColor(GoalStatus status) {
        switch (status) {
            case ACHIEVED:
                return GREEN;
            case BLOCKED:
            case BUDGET_LIMITED:
            case ITERATION_LIMITED:
                return YELLOW;
            case PAUSED:
                return CYAN;
            default:
                return MAGENTA;
        }
    }

    private static String formatElapsed(long seconds) {
        if (seconds >= 3600) {
            return (seconds / 3600) + "h " + ((seconds % 3600) / 60) + "m";
        }
        if (seconds >= 60) {
            return (seconds / 60) + "m " + (seconds % 60) + "s";
        }
        return seconds + "s";
    }

    private static String truncate(String text, int max) {
        if (text == null) return "-";
        String flat = text.replace('\n', ' ');
        return flat.length() <= max ? flat : flat.substring(0, max) + "...";
    }
}
