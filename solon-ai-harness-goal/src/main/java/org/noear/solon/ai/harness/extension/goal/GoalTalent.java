/*
 * Copyright 2017-2026 noear.org and authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.noear.solon.ai.harness.extension.goal;

import org.noear.snack4.Feature;
import org.noear.snack4.ONode;
import org.noear.snack4.Options;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.ai.chat.ChatSession;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.chat.talent.AbsTalent;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.annotation.Param;
import org.noear.solon.core.util.Assert;
import org.noear.solon.ai.harness.extension.goal.validate.GoalValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Goal 工具 —— 模型侧 Goal 生命周期控制。
 *
 * <p>提供两个工具（Goal 的创建由用户命令或界面驱动，模型侧只读 + 更新）：
 * <ul>
 *   <li>{@code goal_get} — 查询当前 goal 状态</li>
 *   <li>{@code goal_update} — 标记 complete 或 blocked（模型驱动）</li>
 * </ul>
 *
 * <p>public 仅为 {@code @ToolMapping} 反射；实例只由 {@link GoalExtension} 装配，宿主不直接持有。
 * {@link #isGoalTool} 供宿主在统计「行动证据」时排除 goal 自身的工具。</p>
 *
 * @author noear
 * @since 3.9.3
 */
public class GoalTalent extends AbsTalent {

    private static final Logger log = LoggerFactory.getLogger(GoalTalent.class);

    private final GoalRuntime goalRuntime;
    private final HarnessEngine engine;

    GoalTalent(GoalRuntime goalRuntime, HarnessEngine engine) {
        this.goalRuntime = goalRuntime;
        this.engine = engine;
    }

    @Override
    public boolean isSupported(Prompt prompt) {
        String sessionId = prompt.attrAs(ChatSession.ATTR_SESSIONID);
        if (sessionId == null) {
            return false;
        }
        AgentSession session = engine.getSessionOrNull(sessionId);
        return goalRuntime.hasGoal(session);
    }

    /**
     * 获取当前 goal 状态
     */
    @ToolMapping(name = "goal_get",
            description = "获取当前活跃目标的状态，若无活跃目标则返回 null。")
    public String goal_get(String __sessionId,
                          String __cwd) {
        AgentSession session = sessionOf(__sessionId);
        GoalState gs = goalRuntime.state(session);

        if (gs == null) {
            return errJson("NO_GOAL", "未找到目标");
        }

        ONode goalNode = new ONode(Options.of(Feature.Write_PrettyFormat));
        goalNode.set("taskId", gs.getId());
        goalNode.set("objective", gs.getObjective());
        goalNode.set("status", gs.getStatus().name().toLowerCase());
        goalNode.set("iteration", gs.getCurrentIteration());
        if (gs.getMaxIterations() > 0) {
            goalNode.set("maxIterations", gs.getMaxIterations());
            goalNode.set("remainingIterations",
                    Math.max(0, gs.getMaxIterations() - gs.getCurrentIteration()));
        }

        long elapsed = (System.currentTimeMillis() - gs.getStartEpochMs()) / 1000;
        goalNode.set("elapsedSeconds", elapsed);
        goalNode.set("consumedTokens", gs.getConsumedTokens());
        if (gs.getMaxTokens() > 0) {
            goalNode.set("maxTokens", gs.getMaxTokens());
        }
        goalNode.set("budgetExceeded", gs.isBudgetExceeded());

        // 时间预算信息，让 AI 感知 maxDurationMs 限制
        if (gs.getMaxDurationMs() > 0) {
            goalNode.set("maxDurationMs", gs.getMaxDurationMs());
            long remainingSec = (gs.getMaxDurationMs() / 1000) - elapsed;
            if (remainingSec < 0) remainingSec = 0;
            goalNode.set("remainingDurationSeconds", remainingSec);
        }

        // Codex 对齐：wrapper 结构 { goal: {...}, remaining_tokens: N, completionBudgetReport: null }
        ONode root = new ONode(Options.of(Feature.Write_PrettyFormat));
        root.set("goal", goalNode);
        root.set("remaining_tokens", gs.getMaxTokens() > 0
                ? gs.getMaxTokens() - gs.getConsumedTokens() : null);
        root.set("completionBudgetReport", null);

        return root.toJson();
    }

    /**
     * 更新 goal 状态
     *
     * <p>complete: 通过实例级验证器执行客观校验后标记完成。
     * blocked: 模型主动声明阻塞（同一困境尝试了 3 次后），暂停调度等待 resume。
     */
    @ToolMapping(name = "goal_update",
            description = "更新当前活跃目标的状态。" +
                    "status='complete' 标记目标已完成；" +
                    "status='blocked' 声明遇到阻塞（同一困境尝试了3次后使用）。")
    public String goal_update(
            @Param(name = "status", description = "状态值：complete 或 blocked") String status,
            String __sessionId,
            String __cwd) {

        if (status == null) {
            return errJson("INVALID_PARAMETER", "status 参数必填（complete 或 blocked）");
        }

        if (__sessionId == null) {
            return errJson("NO_SESSION", "无活跃会话");
        }

        AgentSession session = sessionOf(__sessionId);
        GoalState gs = goalRuntime.state(session);
        if (gs == null) {
            return errJson("NO_GOAL", "未找到活跃目标");
        }

        if (!gs.getStatus().isActive()) {
            return errJson("GOAL_NOT_ACTIVE",
                    "目标不在活跃状态（" + gs.getStatus() + "），无法更新");
        }

        ONode root = new ONode(Options.of(Feature.Write_PrettyFormat));

        // ---- complete ----
        if ("complete".equals(status)) {
            // 提示性优化，不是权威校验：本方法在工具执行阶段运行，此时本轮结果尚未回流，
            // 证据恒为 null（executeRound 已在调用 execute 前置空），故这里一般放行。
            // 仅当引擎已为「上一轮」写入 FALSE 时提前拦截，省掉模型白跑一轮；
            // 真正的完成判定是 GoalRuntime.executeRound 对返回结果的复核（rejectAchievement）。
            Boolean hasActionEvidence = goalRuntime.currentRoundHasActionEvidence(session);
            if (GoalRuntime.requiresActionEvidence(gs.getObjective())
                    && Boolean.FALSE.equals(hasActionEvidence)) {
                return errJson("ACTION_EVIDENCE_REQUIRED",
                        "目标尚未完成：本轮没有成功的实际工具操作。"
                                + "请先创建、修改、运行或验证目标产物；goal_get/goal_update 不算执行证据。");
            }

            // 本轮会话工作目录：与文件工具、todowrite 同口径。会话目录可以不是引擎工作区
            // （如桌面端 goal 把会话目录设为目标项目），验证必须在这里跑，不能回落到引擎工作区。
            String workDir = Assert.isNotEmpty(__cwd) ? __cwd : engine.getSessionCwd(session);

            // Todo/Goal 完成判定联动：TODO 清单尚有未完成项时拒绝完成
            int unfinishedTodos = goalRuntime.countUnfinishedTodos(
                    engine.getTodoTalent(), workDir, __sessionId);
            if (unfinishedTodos > 0) {
                return errJson("TODO_UNFINISHED",
                        "目标尚未完成：任务清单还有 " + unfinishedTodos + " 项未完成（`- [ ]` 或 `- [/]`）。"
                                + "请先完成或用 todowrite 更新清单状态后再调用 goal_update(complete)。");
            }

            if (goalRuntime.isValidatorEnabled()) {
                // 已按 ValidatorPolicy 处理「无法判定」：这里只会拿到通过/未通过
                GoalValidator.ValidationResult vr = goalRuntime.validate(gs.getObjective(), workDir);
                if (!vr.isPassed()) {
                    log.warn("updateGoal: 验证失败 for goal '{}': {}", gs.getId(), vr.getDetail());
                    return errJson("VALIDATION_FAILED",
                            "目标尚未完成：验证失败 - " + vr.getDetail()
                                    + "。请继续改进后重新调用 goal_update(complete)。");
                }
            }

            gs.achieve();
            goalRuntime.commit(session, gs);

            ONode goalNode = new ONode();
            goalNode.set("taskId", gs.getId());
            goalNode.set("objective", gs.getObjective());
            goalNode.set("status", "achieved");
            goalNode.set("consumedTokens", gs.getConsumedTokens());
            if (gs.getMaxTokens() > 0) {
                goalNode.set("maxTokens", gs.getMaxTokens());
            }

            root.set("goal", goalNode);
            root.set("remaining_tokens", gs.getMaxTokens() > 0
                    ? gs.getMaxTokens() - gs.getConsumedTokens() : null);
            root.set("completionBudgetReport", buildCompletionBudgetReport(gs));

            return root.toJson();
        }

        // ---- blocked ----
        if ("blocked".equals(status)) {
            gs.markBlocked();
            goalRuntime.commit(session, gs);
            log.info("updateGoal: goal '{}' marked as BLOCKED by model", gs.getId());

            ONode goalNode = new ONode();
            goalNode.set("taskId", gs.getId());
            goalNode.set("objective", gs.getObjective());
            goalNode.set("status", "blocked");
            goalNode.set("consumedTokens", gs.getConsumedTokens());
            if (gs.getMaxTokens() > 0) {
                goalNode.set("maxTokens", gs.getMaxTokens());
            }

            root.set("goal", goalNode);
            root.set("remaining_tokens", gs.getMaxTokens() > 0
                    ? gs.getMaxTokens() - gs.getConsumedTokens() : null);
            root.set("completionBudgetReport", null);

            return root.toJson();
        }

        return errJson("INVALID_PARAMETER",
                "未知状态 '" + status + "'. 仅支持 'complete' 或 'blocked'.");
    }

    private AgentSession sessionOf(String sessionId) {
        return sessionId == null ? null : engine.getSessionOrNull(sessionId);
    }

    // ===== 辅助方法 =====

    /**
     * 构建 completion budget report（目标完成时的预算摘要）
     */
    private static String buildCompletionBudgetReport(GoalState gs) {
        StringBuilder sb = new StringBuilder();
        sb.append("Goal achieved. ");
        if (gs.getMaxTokens() > 0) {
            sb.append("Token usage: ").append(gs.getConsumedTokens())
              .append("/").append(gs.getMaxTokens());
            double pct = (double) gs.getConsumedTokens() * 100 / gs.getMaxTokens();
            sb.append(" (").append(String.format("%.1f", pct)).append("%). ");
        } else {
            sb.append("Token usage: ").append(gs.getConsumedTokens()).append(" tokens. ");
        }
        long elapsedSec = (System.currentTimeMillis() - gs.getStartEpochMs()) / 1000;
        if (elapsedSec >= 60) {
            sb.append("Duration: ").append(elapsedSec / 60).append("m ").append(elapsedSec % 60).append("s.");
        } else {
            sb.append("Duration: ").append(elapsedSec).append("s.");
        }
        return sb.toString();
    }

    /**
     * 统一错误 JSON 格式
     */
    private static String errJson(String code, String message) {
        ONode root = new ONode();
        root.set("error", true);
        root.set("code", code);
        root.set("message", message);
        return root.toJson();
    }

    public static boolean isGoalTool(String toolName) {
        return toolName != null && toolName.startsWith("goal_");
    }
}
