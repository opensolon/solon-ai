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

import org.noear.solon.ai.harness.extension.goal.i18n.GoalI18n;
import org.noear.solon.ai.harness.extension.goal.i18n.GoalI18nKeys;
import org.noear.solon.lang.Nullable;

import java.util.Locale;

/**
 * Goal 提示词构建器 — 只依赖 {@link Goal}。
 *
 * <p>职责：根据预算剩余比率自动切换完整/精简/极简三种引导词模式，以及预算耗尽收尾引导词。
 *
 * <p>文案按传入的语言渲染（goal 轮次用目标的语言，没有时用引擎实例的语言，见 {@link GoalI18n}）；null 为默认语言。
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
class GoalPromptBuilder {

    private final int stagnationThreshold;

    public GoalPromptBuilder(int stagnationThreshold) {
        this.stagnationThreshold = stagnationThreshold;
    }

    // ==================== 主入口 ====================

    /**
     * 构建完整的 effective prompt（默认语言）
     */
    public String buildEffectivePrompt(Goal gs) {
        return buildEffectivePrompt(gs, null);
    }

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
    public String buildEffectivePrompt(Goal gs, @Nullable Locale locale) {
        String prompt = gs.getObjective();
        int iter = gs.getCurrentIteration();
        boolean isFirstIter = iter == 0;

        String budgetInfo = buildBudgetInfo(gs, locale) + buildIterationInfo(gs, locale);

        // 预算感知精简模式
        double budgetRatio = budgetRatio(gs);

        if (budgetRatio < 0.15) {
            return buildMinimalPrompt(prompt, gs, budgetInfo, locale);
        } else if (budgetRatio < 0.30) {
            return buildCompactPrompt(prompt, gs, budgetInfo, iter, isFirstIter, locale);
        }

        // 完整模式（7 章节）
        StringBuilder sb = new StringBuilder();
        sb.append("\n\n");
        section(sb, GoalI18n.fmt(locale, GoalI18nKeys.GOAL_PROMPT_FULL_CONTINUATION, "objective", gs.getObjective()));

        // Chapter 3: Work from evidence
        section(sb, GoalI18n.get(locale, GoalI18nKeys.GOAL_PROMPT_FULL_EVIDENCE));

        // Chapter 5: Fidelity
        section(sb, GoalI18n.get(locale, GoalI18nKeys.GOAL_PROMPT_FULL_FIDELITY));

        // Chapter 6: Completion audit
        section(sb, GoalI18n.get(locale, GoalI18nKeys.GOAL_PROMPT_FULL_AUDIT));

        // Chapter 7: Blocked audit
        section(sb, GoalI18n.get(locale, GoalI18nKeys.GOAL_PROMPT_FULL_BLOCKED));

        // 停滞质疑（运行时兜底，仅触发时注入）
        if (gs.getStagnationCount() >= stagnationThreshold) {
            section(sb, GoalI18n.fmt(locale, GoalI18nKeys.GOAL_PROMPT_STAGNATION, "count", gs.getStagnationCount()));
        }

        // ★ 错误上下文注入 — 当有连续错误时，告知 Agent 错误类型和不可重试提示
        if (gs.getConsecutiveSameTypeErrors() > 0 && gs.getLastErrorType() != null) {
            line(sb, GoalI18n.fmt(locale, GoalI18nKeys.GOAL_PROMPT_ERROR_HEADER,
                    "count", gs.getConsecutiveSameTypeErrors(), "type", gs.getLastErrorType()));
            if (gs.getLastErrorSummary() != null) {
                line(sb, GoalI18n.fmt(locale, GoalI18nKeys.GOAL_PROMPT_ERROR_SUMMARY, "summary", gs.getLastErrorSummary()));
            }

            // 根据错误类型给出具体建议
            String errorType = gs.getLastErrorType();
            if ("SSL".equals(errorType)) {
                line(sb, GoalI18n.get(locale, GoalI18nKeys.GOAL_PROMPT_ERROR_SSL));
            } else if ("HTTP_4XX".equals(errorType)) {
                line(sb, GoalI18n.get(locale, GoalI18nKeys.GOAL_PROMPT_ERROR_HTTP_4XX));
            } else if ("NETWORK".equals(errorType)) {
                line(sb, GoalI18n.get(locale, GoalI18nKeys.GOAL_PROMPT_ERROR_NETWORK));
            } else if ("TOOL_EXECUTION".equals(errorType)) {
                line(sb, GoalI18n.get(locale, GoalI18nKeys.GOAL_PROMPT_ERROR_TOOL_EXECUTION));
            } else {
                line(sb, GoalI18n.get(locale, GoalI18nKeys.GOAL_PROMPT_ERROR_OTHER));
            }
            sb.append("\n");
        }

        // Chapter 2: Budget
        if (gs.isBudgetCritical()) {
            line(sb, GoalI18n.get(locale, GoalI18nKeys.GOAL_PROMPT_URGENT));
        }

        // Chapter 4: Progress visibility — 上一轮摘要
        if (!isFirstIter && gs.getLastResult() != null) {
            String lastSummary = truncateForPrompt(gs.getLastResult(), 300, locale);
            sb.append("\n");
            line(sb, GoalI18n.fmt(locale, GoalI18nKeys.GOAL_PROMPT_LAST_SUMMARY_TITLE, "iteration", iter));
            line(sb, lastSummary);
            line(sb, GoalI18n.get(locale, GoalI18nKeys.GOAL_PROMPT_LAST_SUMMARY_HINT));
        }

        sb.append(budgetInfo);

        return prompt + sb.toString();
    }

    /**
     * 构建 budget_limit 引导词（默认语言）
     */
    public String buildBudgetLimitPrompt(Goal gs) {
        return buildBudgetLimitPrompt(gs, null);
    }

    /**
     * 构建 budget_limit 引导词（对齐 Codex budget_limit.md）
     */
    public String buildBudgetLimitPrompt(Goal gs, @Nullable Locale locale) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n\n");
        section(sb, GoalI18n.get(locale, GoalI18nKeys.GOAL_PROMPT_BUDGET_LIMIT_HEADER));
        section(sb, GoalI18n.fmt(locale, GoalI18nKeys.GOAL_PROMPT_BUDGET_LIMIT_OBJECTIVE, "objective", gs.getObjective()));

        line(sb, GoalI18n.get(locale, GoalI18nKeys.GOAL_PROMPT_BUDGET_LIMIT_TITLE));
        long elapsed = (System.currentTimeMillis() - gs.getStartEpochMs()) / 1000;
        line(sb, GoalI18n.fmt(locale, GoalI18nKeys.GOAL_PROMPT_BUDGET_LIMIT_ELAPSED, "elapsed", formatDuration(elapsed * 1000)));
        String consumed = formatTokens(gs.getConsumedTokens());
        if (gs.getMaxTokens() > 0) {
            consumed = consumed + " / " + formatTokens(gs.getMaxTokens());
        }
        line(sb, GoalI18n.fmt(locale, GoalI18nKeys.GOAL_PROMPT_BUDGET_LIMIT_CONSUMED, "consumed", consumed));
        sb.append("\n");

        section(sb, GoalI18n.get(locale, GoalI18nKeys.GOAL_PROMPT_BUDGET_LIMIT_INSTRUCTION));
        line(sb, GoalI18n.get(locale, GoalI18nKeys.GOAL_PROMPT_BUDGET_LIMIT_NO_UPDATE));

        return sb.toString();
    }

    // ==================== 内部构建方法 ====================

    /**
     * 精简模式（预算 15%-30%）：3 章节
     */
    private String buildCompactPrompt(String prompt, Goal gs,
                                      String budgetInfo, int iter, boolean isFirstIter, Locale locale) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n\n");
        section(sb, GoalI18n.fmt(locale, GoalI18nKeys.GOAL_PROMPT_COMPACT_CONTINUATION, "objective", gs.getObjective()));
        section(sb, GoalI18n.get(locale, GoalI18nKeys.GOAL_PROMPT_COMPACT_AUDIT));

        if (!isFirstIter && gs.getLastResult() != null) {
            String lastSummary = truncateForPrompt(gs.getLastResult(), 200, locale);
            line(sb, GoalI18n.fmt(locale, GoalI18nKeys.GOAL_PROMPT_COMPACT_LAST, "iteration", iter, "summary", lastSummary));
        }

        sb.append(budgetInfo);
        return prompt + sb.toString();
    }

    /**
     * 极简模式（预算 < 15%）：单段落
     */
    private String buildMinimalPrompt(String prompt, Goal gs,
                                      String budgetInfo, Locale locale) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n\n");
        sb.append(GoalI18n.fmt(locale, GoalI18nKeys.GOAL_PROMPT_MINIMAL_OBJECTIVE, "objective", gs.getObjective())).append(" | ");
        line(sb, budgetInfo.trim());
        line(sb, GoalI18n.get(locale, GoalI18nKeys.GOAL_PROMPT_MINIMAL_INSTRUCTION));
        return prompt + sb.toString();
    }

    /** 一个章节：正文 + 换行 + 空行 */
    private static void section(StringBuilder sb, String text) {
        sb.append(text).append("\n\n");
    }

    /** 一行：正文 + 换行 */
    private static void line(StringBuilder sb, String text) {
        sb.append(text).append("\n");
    }

    // ==================== 静态辅助方法 ====================

    /**
     * 计算预算剩余比率
     */
    static double budgetRatio(Goal gs) {
        return gs.getMaxTokens() > 0
                ? (double) (gs.getMaxTokens() - gs.getConsumedTokens()) / gs.getMaxTokens()
                : 1.0;
    }

    static String buildBudgetInfo(Goal gs) {
        return buildBudgetInfo(gs, null);
    }

    static String buildBudgetInfo(Goal gs, @Nullable Locale locale) {
        StringBuilder sb = new StringBuilder();

        if (gs.getMaxTokens() > 0) {
            long remainToken = gs.getMaxTokens() - gs.getConsumedTokens();
            String percent = budgetPercent(gs.getConsumedTokens(), gs.getMaxTokens());
            sb.append("\n").append(GoalI18n.fmt(locale, GoalI18nKeys.GOAL_PROMPT_BUDGET_CONSUMED,
                    "consumed", formatTokens(gs.getConsumedTokens()),
                    "max", formatTokens(gs.getMaxTokens()),
                    "percent", percent));
            if (remainToken > 0 && gs.isBudgetCritical()) {
                sb.append(" ").append(GoalI18n.fmt(locale, GoalI18nKeys.GOAL_PROMPT_BUDGET_REMAINING,
                        "remaining", formatTokens(remainToken)));
            }
            if (gs.isBudgetWarning()) {
                sb.append("\n").append(GoalI18n.fmt(locale, GoalI18nKeys.GOAL_PROMPT_BUDGET_WARNING, "percent", percent));
            }
        } else if (gs.getConsumedTokens() > 0) {
            sb.append("\n").append(GoalI18n.fmt(locale, GoalI18nKeys.GOAL_PROMPT_BUDGET_CONSUMED_UNLIMITED,
                    "consumed", formatTokens(gs.getConsumedTokens())));
        }

        if (gs.getStartEpochMs() > 0) {
            long elapsed = System.currentTimeMillis() - gs.getStartEpochMs();
            if (elapsed > 1000) {
                sb.append("\n").append(GoalI18n.fmt(locale, GoalI18nKeys.GOAL_PROMPT_BUDGET_ELAPSED,
                        "elapsed", formatDuration(elapsed)));
            }
        }

        return sb.toString();
    }

    static String buildIterationInfo(Goal gs) {
        return buildIterationInfo(gs, null);
    }

    static String buildIterationInfo(Goal gs, @Nullable Locale locale) {
        if (gs.getMaxIterations() <= 0) {
            return "";
        }
        int completed = Math.max(0, gs.getCurrentIteration());
        int current = Math.min(completed + 1, gs.getMaxIterations());
        int remainingAfterCurrent = Math.max(0, gs.getMaxIterations() - current);
        return "\n" + GoalI18n.fmt(locale, GoalI18nKeys.GOAL_PROMPT_ITERATION,
                "current", current, "max", gs.getMaxIterations(), "remaining", remainingAfterCurrent);
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
        return truncateForPrompt(text, maxLen, null);
    }

    static String truncateForPrompt(String text, int maxLen, @Nullable Locale locale) {
        if (text == null || text.isEmpty()) return "";
        if (text.length() <= maxLen) return text;
        int half = maxLen / 2;
        return text.substring(0, half) + "\n" + GoalI18n.get(locale, GoalI18nKeys.GOAL_PROMPT_TRUNCATED) + "\n"
                + text.substring(text.length() - half);
    }
}
