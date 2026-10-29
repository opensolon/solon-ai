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
package org.noear.solon.ai.harness.session.goal;

/**
 * Goal 提示词构建器 — 只依赖 {@link GoalState}。
 *
 * <p>职责：根据预算剩余比率自动切换完整/精简/极简三种引导词模式，以及预算耗尽收尾引导词。
 *
 * <p>预算阈值：
 * <ul>
 *   <li>剩余 ≥ 30% → 完整 7 章节引导</li>
 *   <li>15% ≤ 剩余 &lt; 30% → 精简 3 章节</li>
 *   <li>剩余 &lt; 15% → 极简单段落</li>
 * </ul>
 *
 * @author noear
 * @since 3.9.3
 */
public class GoalPromptBuilder {

    private final int stagnationThreshold;

    public GoalPromptBuilder(int stagnationThreshold) {
        this.stagnationThreshold = stagnationThreshold;
    }

    // ==================== 主入口 ====================

    /**
     * 构建完整的 effective prompt（goal 引导词注入）
     *
     * <p>根据预算剩余自动切换精简模式：
     * <ul>
     *   <li>预算 &gt; 30%：完整 7 章节</li>
     *   <li>预算 15%-30%：精简 3 章节</li>
     *   <li>预算 &lt; 15%：极简单段落</li>
     * </ul>
     */
    public String buildEffectivePrompt(GoalState gs) {
        String prompt = gs.getCondition();
        int iter = gs.getCurrentIteration();
        boolean isFirstIter = iter == 0;

        String budgetInfo = buildBudgetInfo(gs) + buildIterationInfo(gs);

        // 预算感知精简模式
        double budgetRatio = budgetRatio(gs);

        if (budgetRatio < 0.15) {
            return buildMinimalPrompt(prompt, gs, budgetInfo);
        } else if (budgetRatio < 0.30) {
            return buildCompactPrompt(prompt, gs, budgetInfo, iter, isFirstIter);
        }

        // 完整模式（7 章节）
        StringBuilder sb = new StringBuilder();
        sb.append("\n\n");
        sb.append("--- 目标延续 (Goal Continuation) ---\n");
        sb.append("你正在朝向以下目标工作: ").append(gs.getCondition()).append("\n");
        sb.append("你的目标是完成此任务。这是持续性的工作 — 每一轮执行都是同一个目标的延续。\n");
        sb.append("\n");

        // Chapter 3: Work from evidence
        sb.append("--- 证据驱动 (Evidence-Based) ---\n");
        sb.append("不要依赖记忆或假设来判断当前状态。在采取行动前，先检查实际的文件内容、\n");
        sb.append("测试结果、构建输出等客观证据。你的判断必须基于最新的事实，而非上轮的记忆。\n");
        sb.append("\n");

        // Chapter 5: Fidelity
        sb.append("--- 忠于目标 (Goal Fidelity) ---\n");
        sb.append("不要缩小目标范围或降低完成标准。目标中的每一项都必须完成。\n");
        sb.append("不要留占位符、TODO 或 stub。如果某个部分很难，要投入精力解决，而非跳过。\n");
        sb.append("\n");

        // Chapter 6: Completion audit
        sb.append("--- 审计完成 (Audit Check) ---\n");
        sb.append("在继续之前，请完成以下步骤：\n");
        sb.append("1. 回顾：目标是什么？检查已有的进展。\n");
        sb.append("2. 核查：针对目标中的每一项，通过运行测试、检查文件等客观手段验证其是否已完成。\n");
        sb.append("   不要仅凭推理 — 必须有权威证据（测试通过、构建成功、文件存在且内容正确）。\n");
        sb.append("   对创建、修改、运行类目标，至少完成一次实际工具操作；goal_get/goal_update 不算执行证据。\n");
        sb.append("3. 如果你已完成所有项，说明你是如何实现每一项的，\n");
        sb.append("   然后调用 goal_update(complete) 标记完成。\n");
        sb.append("\n");

        // Chapter 7: Blocked audit
        sb.append("--- 阻塞审计 (Blocked Audit) ---\n");
        sb.append("如果你遇到阻碍（同一困境尝试了 3 次），调用 goal_update(blocked) 声明阻塞。\n");
        sb.append("不要因为工作困难、进展慢或不确定就声明阻塞 — 仅当同一问题反复尝试仍无法解决时才使用。\n");
        sb.append("resume 后阻塞计数重置为 0。\n");
        sb.append("\n");

        // 停滞质疑（运行时兜底，仅触发时注入）
        if (gs.getStagnationCount() >= stagnationThreshold) {
            sb.append("--- 进展质疑 (Stagnation Check) ---\n");
            sb.append("系统检测到最近 ").append(gs.getStagnationCount())
                    .append(" 轮执行未产生实质性进展。\n");
            sb.append("请认真评估：你是否在同一问题上反复尝试但无法推进？\n");
            sb.append("如果是，请调用 goal_update(blocked) 声明阻塞。\n");
            sb.append("如果不是，请在下一步采取明显不同的策略。\n");
            sb.append("\n");
        }

        // ★ 错误上下文注入 — 当有连续错误时，告知 Agent 错误类型和不可重试提示
        if (gs.getConsecutiveSameTypeErrors() > 0 && gs.getLastErrorType() != null) {
            sb.append("--- 错误警告 (Error Warning) ---\n");
            sb.append("最近连续 ").append(gs.getConsecutiveSameTypeErrors())
                    .append(" 次执行遇到相同类型的错误：\n");
            sb.append("- 错误类型: ").append(gs.getLastErrorType()).append("\n");
            if (gs.getLastErrorSummary() != null) {
                sb.append("- 错误摘要: ").append(gs.getLastErrorSummary()).append("\n");
            }

            // 根据错误类型给出具体建议
            String errorType = gs.getLastErrorType();
            if ("SSL".equals(errorType)) {
                sb.append("- 这是 SSL/TLS 证书验证错误，属于运行环境问题，无法通过重试或更换 URL 解决。\n");
                sb.append("- 请使用 websearch 或 codesearch 工具替代 webfetch 来获取信息。\n");
            } else if ("HTTP_4XX".equals(errorType)) {
                sb.append("- 这是 HTTP 4xx 客户端错误（如 404/403/401），通常表示资源不存在或无权限。\n");
                sb.append("- 重试相同请求不会成功，请更换目标 URL 或使用其他工具。\n");
            } else if ("NETWORK".equals(errorType)) {
                sb.append("- 这是网络连接错误，可能是临时性的。\n");
                sb.append("- 可以稍后重试，但如果连续多次失败请换用其他策略。\n");
            } else if ("TOOL_EXECUTION".equals(errorType)) {
                sb.append("- 工具调用执行失败。请分析错误原因，考虑换用其他工具或调整参数。\n");
            } else {
                sb.append("- 请分析错误原因，如果同一问题已尝试 3 次以上，请调用 goal_update(blocked)。\n");
            }
            sb.append("\n");
        }

        // Chapter 2: Budget
        if (gs.isBudgetCritical()) {
            sb.append("[紧急] 你的 Token 预算即将耗尽。请专注于高效完成目标。\n");
        }

        // Chapter 4: Progress visibility — 上一轮摘要
        if (!isFirstIter && gs.getLastResult() != null) {
            String lastSummary = truncateForPrompt(gs.getLastResult(), 300);
            sb.append("\n--- 上一轮执行摘要（第 ").append(iter).append(" 轮）---\n");
            sb.append(lastSummary).append("\n");
            sb.append("请基于以上进展继续推进，避免重复已尝试过的方案。\n");
        }

        sb.append(budgetInfo);

        return prompt + sb.toString();
    }

    /**
     * 构建 budget_limit 引导词（对齐 Codex budget_limit.md）
     */
    public String buildBudgetLimitPrompt(GoalState gs) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n\n--- 预算耗尽 (Budget Limit) ---\n");
        sb.append("你的目标 Token 预算已耗尽。\n\n");
        sb.append("目标: ").append(gs.getCondition()).append("\n\n");

        sb.append("预算:\n");
        long elapsed = (System.currentTimeMillis() - gs.getStartEpochMs()) / 1000;
        sb.append("- 耗时: ").append(formatDuration(elapsed * 1000)).append("\n");
        sb.append("- 已消耗: ").append(formatTokens(gs.getConsumedTokens()));
        if (gs.getMaxTokens() > 0) {
            sb.append(" / ").append(formatTokens(gs.getMaxTokens())).append("\n");
        } else {
            sb.append("\n");
        }
        sb.append("\n");

        sb.append("系统已将此目标标记为 budget_limited，请勿开始新的实质性工作。\n");
        sb.append("请在此轮回复中：\n");
        sb.append("1. 总结已完成的工作和进展\n");
        sb.append("2. 列出剩余未完成的工作\n");
        sb.append("3. 给出明确的下一步建议\n\n");
        sb.append("不要调用 goal_update 除非目标确实已完成。\n");

        return sb.toString();
    }

    // ==================== 内部构建方法 ====================

    /**
     * 精简模式（预算 15%-30%）：3 章节
     */
    private String buildCompactPrompt(String prompt, GoalState gs,
                                      String budgetInfo, int iter, boolean isFirstIter) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n\n");
        sb.append("--- 目标延续 (Goal Continuation) ---\n");
        sb.append("目标: ").append(gs.getCondition()).append("\n");
        sb.append("持续工作直至完成。完成后调用 goal_update(complete) 标记完成。\n");
        sb.append("\n");

        sb.append("--- 审计完成 (Audit Check) ---\n");
        sb.append("逐条验证目标完成情况。必须有客观证据（测试通过/文件存在）。不要凭推理判定完成。\n");
        sb.append("创建、修改、运行类目标必须有实际工具操作，goal_get/goal_update 不算执行证据。\n");
        sb.append("\n");

        if (!isFirstIter && gs.getLastResult() != null) {
            String lastSummary = truncateForPrompt(gs.getLastResult(), 200);
            sb.append("上一轮（第 ").append(iter).append("轮）: ").append(lastSummary).append("\n");
        }

        sb.append(budgetInfo);
        return prompt + sb.toString();
    }

    /**
     * 极简模式（预算 < 15%）：单段落
     */
    private String buildMinimalPrompt(String prompt, GoalState gs,
                                      String budgetInfo) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n\n");
        sb.append("目标: ").append(gs.getCondition()).append(" | ");
        sb.append(budgetInfo.trim()).append("\n");
        sb.append("持续工作直至完成。执行型目标必须先产生实际工具证据，goal_get/goal_update 不算。完成后调用 goal_update(complete)；连续 3 轮无进展则调用 goal_update(blocked)。\n");
        return prompt + sb.toString();
    }

    // ==================== 静态辅助方法 ====================

    /**
     * 计算预算剩余比率
     */
    static double budgetRatio(GoalState gs) {
        return gs.getMaxTokens() > 0
                ? (double) (gs.getMaxTokens() - gs.getConsumedTokens()) / gs.getMaxTokens()
                : 1.0;
    }

    static String buildBudgetInfo(GoalState gs) {
        StringBuilder sb = new StringBuilder();

        if (gs.getMaxTokens() > 0) {
            long remainToken = gs.getMaxTokens() - gs.getConsumedTokens();
            sb.append("\n已消耗 ").append(formatTokens(gs.getConsumedTokens()))
                    .append(" / ").append(formatTokens(gs.getMaxTokens()))
                    .append(" (").append(budgetPercent(gs.getConsumedTokens(), gs.getMaxTokens())).append("%)");
            if (remainToken > 0 && gs.isBudgetCritical()) {
                sb.append(" (剩余: ").append(formatTokens(remainToken)).append(")");
            }
            if (gs.isBudgetWarning()) {
                sb.append("\n[预算提示] 已使用 ").append(budgetPercent(gs.getConsumedTokens(), gs.getMaxTokens()))
                        .append("%，请评估是否需要调整策略或申请扩容");
            }
        } else if (gs.getConsumedTokens() > 0) {
            sb.append("\n已消耗 Token: ").append(formatTokens(gs.getConsumedTokens()));
        }

        if (gs.getStartEpochMs() > 0) {
            long elapsed = System.currentTimeMillis() - gs.getStartEpochMs();
            if (elapsed > 1000) {
                sb.append("\n耗时: ").append(formatDuration(elapsed));
            }
        }

        return sb.toString();
    }

    static String buildIterationInfo(GoalState gs) {
        if (gs.getMaxIterations() <= 0) {
            return "";
        }
        int completed = Math.max(0, gs.getCurrentIteration());
        int current = Math.min(completed + 1, gs.getMaxIterations());
        int remainingAfterCurrent = Math.max(0, gs.getMaxIterations() - current);
        return "\n轮次限制: 当前为第 " + current + " / " + gs.getMaxIterations()
                + " 轮，本轮结束后最多还可执行 " + remainingAfterCurrent
                + " 轮。请优先完成并验证目标，不要把关键工作留到限制之后。";
    }

    static String budgetPercent(long value, long total) {
        if (total <= 0) return "0";
        return String.valueOf((int) (value * 100 / total));
    }

    static String formatTokens(long tokens) {
        if (tokens < 1000) return tokens + " tokens";
        if (tokens < 1_000_000) return String.format("%.1fk", tokens / 1000.0);
        return String.format("%.1fM", tokens / 1_000_000.0);
    }

    static String formatDuration(long ms) {
        if (ms < 60_000) return (ms / 1000) + "s";
        if (ms < 3_600_000) return (ms / 60_000) + "m " + ((ms % 60_000) / 1000) + "s";
        return (ms / 3_600_000) + "h " + ((ms % 3_600_000) / 60_000) + "m";
    }

    static String truncateForPrompt(String text, int maxLen) {
        if (text == null || text.isEmpty()) return "";
        if (text.length() <= maxLen) return text;
        int half = maxLen / 2;
        return text.substring(0, half) + "\n...(省略)...\n" + text.substring(text.length() - half);
    }
}
