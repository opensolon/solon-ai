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

import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.ai.harness.command.Command;
import org.noear.solon.ai.harness.command.CommandContext;
import org.noear.solon.ai.harness.input.InputOrigin;
import org.noear.solon.ai.harness.session.goal.GoalEngine;
import org.noear.solon.ai.harness.session.goal.GoalScheduler;
import org.noear.solon.ai.harness.session.goal.GoalState;

/**
 * /goal 命令 —— 会话级目标的独立命令（不再经由 loop 命令转发）。
 *
 * <p>Goal 属于会话且每会话最多一个：创建时若已有进行中的目标会被拒绝，
 * 需先用 {@code /goal clear} 清除。状态落在会话快照中，随对话一起持久化。</p>
 *
 * @author noear
 */
public class GoalCommand implements Command {
    private static final String BOLD = "\033[1m";
    private static final String DIM = "\033[2m";
    private static final String GREEN = "\033[32m";
    private static final String YELLOW = "\033[33m";
    private static final String RED = "\033[31m";
    private static final String CYAN = "\033[36m";
    private static final String MAGENTA = "\033[35m";
    private static final String RESET = "\033[0m";

    private static final long DEFAULT_MAX_TOKENS = 0;
    private static final long DEFAULT_MAX_DURATION_MS = 0;
    private static final int DEFAULT_MAX_ITERATIONS = 0;

    private final GoalEngine goalEngine;
    private final GoalScheduler goalScheduler;

    public GoalCommand(GoalEngine goalEngine, GoalScheduler goalScheduler) {
        this.goalEngine = goalEngine;
        this.goalScheduler = goalScheduler;
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
                "/goal --max-tokens:2000 <objective>",
                "/goal --max-duration:30 <objective>",
                "/goal pause | resume | clear",
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

        String sessionId = session.getSessionId();
        String first = ctx.argAt(0);

        // 无参：展示当前目标状态
        if (first == null) {
            printStatus(ctx, session);
            return;
        }

        // 子命令
        switch (first.toLowerCase()) {
            case "pause": {
                GoalState gs = goalEngine.state(session);
                if (gs == null) {
                    ctx.println(ctx.color(DIM + "No goal in this session." + RESET));
                    return;
                }
                if (!goalEngine.pause(session, gs)) {
                    ctx.println(ctx.color(YELLOW + "Cannot pause goal in state: " + gs.getStatus() + RESET));
                    return;
                }
                goalScheduler.unschedule(sessionId);
                ctx.println(ctx.color(GREEN + "Goal paused." + RESET));
                return;
            }
            case "resume": {
                GoalState gs = goalEngine.state(session);
                if (gs == null) {
                    ctx.println(ctx.color(DIM + "No goal in this session." + RESET));
                    return;
                }
                if (!goalEngine.resume(session, gs)) {
                    ctx.println(ctx.color(YELLOW + "Cannot resume goal in state: " + gs.getStatus() + RESET));
                    return;
                }
                goalScheduler.schedule(sessionId, true);
                ctx.println(ctx.color(GREEN + "Goal resumed." + RESET));
                return;
            }
            case "clear": {
                GoalState gs = goalEngine.state(session);
                if (gs == null) {
                    ctx.println(ctx.color(DIM + "No goal in this session." + RESET));
                    return;
                }
                goalScheduler.unschedule(sessionId);
                goalEngine.clear(session);
                ctx.println(ctx.color(GREEN + "Goal cleared." + RESET));
                return;
            }
            default:
                break;
        }

        // 创建目标：解析 --flag:value 与正文
        Long maxTokens = null;
        Long maxDurationMs = null;
        Integer maxIterations = null;
        StringBuilder objective = new StringBuilder();

        for (int i = 0; ; i++) {
            String arg = ctx.argAt(i);
            if (arg == null) break;

            if (arg.startsWith("--")) {
                String flag = arg.substring(2);
                int colonIdx = flag.indexOf(':');
                if (colonIdx <= 0) {
                    ctx.println(ctx.color(YELLOW + "Unknown flag: --" + flag + RESET));
                    continue;
                }
                String key = flag.substring(0, colonIdx);
                String val = flag.substring(colonIdx + 1);
                try {
                    switch (key) {
                        case "max-tokens":
                            maxTokens = Long.parseLong(val);
                            break;
                        case "max-duration":
                            maxDurationMs = Long.parseLong(val) * 60_000L;
                            break;
                        case "max-iterations":
                            maxIterations = Integer.parseInt(val);
                            break;
                        default:
                            ctx.println(ctx.color(YELLOW + "Unknown flag: --" + key + RESET));
                    }
                } catch (NumberFormatException e) {
                    ctx.println(ctx.color(RED + "Invalid --" + key + " value: " + val + RESET));
                    return;
                }
            } else {
                if (objective.length() > 0) objective.append(" ");
                objective.append(arg);
            }
        }

        String prompt = objective.toString().trim();
        if (prompt.isEmpty()) {
            ctx.println(ctx.color(RED + "Usage: /goal <objective>" + RESET));
            ctx.println(ctx.color(DIM + "  /goal 修复登录模块的鉴权缺陷" + RESET));
            return;
        }

        try {
            GoalState gs = goalEngine.attach(session, prompt,
                    maxTokens != null ? maxTokens : DEFAULT_MAX_TOKENS,
                    maxDurationMs != null ? maxDurationMs : DEFAULT_MAX_DURATION_MS,
                    maxIterations != null ? maxIterations : DEFAULT_MAX_ITERATIONS);

            goalScheduler.schedule(sessionId, true);

            ctx.println(ctx.color(GREEN + "Goal registered:" + RESET));
            ctx.println(ctx.color("  " + BOLD + "Objective:" + RESET + " " + prompt));
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
        } catch (IllegalStateException e) {
            GoalState existing = goalEngine.state(session);
            ctx.println(ctx.color(YELLOW + "WARN: " + e.getMessage() + RESET));
            if (existing != null) {
                ctx.println(ctx.color(DIM + "  Active: " + existing.getCondition() + RESET));
                ctx.println(ctx.color(DIM + "  Use /goal clear first to replace the goal." + RESET));
            }
        }
    }

    private void printStatus(CommandContext ctx, AgentSession session) {
        GoalState gs = goalEngine.state(session);
        if (gs == null) {
            ctx.println(ctx.color(DIM + "No goal in this session." + RESET));
            ctx.println(ctx.color(DIM + "Usage: /goal <objective>" + RESET));
            return;
        }

        long elapsedSec = (System.currentTimeMillis() - gs.getStartEpochMs()) / 1000;
        ctx.println(ctx.color(BOLD + "Goal:" + RESET + " " + gs.getCondition()));
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

    private static String statusColor(GoalState.Status status) {
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
