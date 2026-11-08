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

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 预算压缩测试：验证 GoalPromptBuilder 根据预算剩余比率切换完整/精简/极简模式。
 *
 * <p>预算阈值：
 * <ul>
 *   <li>剩余 ≥ 30% → 完整 7 章节（含目标延续、证据驱动、忠于目标、审计完成、阻塞审计等）</li>
 *   <li>15% ≤ 剩余 &lt; 30% → 精简 3 章节</li>
 *   <li>剩余 &lt; 15% → 极简单段落</li>
 * </ul>
 *
 * @since 3.9.3
 */
class GoalPromptBuilderTest {

    private final GoalPromptBuilder builder = new GoalPromptBuilder(3);

    // ===== 辅助方法 =====

    /**
     * 创建指定预算消耗的 Goal 任务
     *
     * @param maxTokens       Token 预算上限
     * @param consumedTokens  已消耗 Token
     * @param iteration       迭代次数（影响上一轮摘要是否出现）
     */
    private Goal createGoalSnapshot(long maxTokens, long consumedTokens, int iteration) {
        Goal gs = new Goal("test goal objective here", maxTokens);
        if (consumedTokens > 0) {
            gs.addTokens(consumedTokens);
        }
        for (int i = 0; i < iteration; i++) {
            gs.incrementIteration();
        }
        return gs;
    }

    /**
     * 计算当前 budgetRatio（用于辅助断言）
     */
    private double computeRemainingRatio(Goal gs) {
        if (gs.getMaxTokens() <= 0) return 1.0;
        return (double) (gs.getMaxTokens() - gs.getConsumedTokens()) / gs.getMaxTokens();
    }

    // ===== 完整模式（剩余 ≥ 30%） =====

    @Test
    void fullModeWhenBudgetAbove30Percent() {
        // 消耗 2000 / 10000 → 剩余 80% → 完整模式
        Goal task = createGoalSnapshot(10000, 2000, 0);
        double ratio = computeRemainingRatio(task);
        assertTrue(ratio >= 0.30, "budget ratio should be >= 0.30 for full mode, got " + ratio);

        String result = builder.buildEffectivePrompt(task);

        // 7 章节标记
        assertTrue(result.contains("目标延续 (Goal Continuation)"), "should contain Goal Continuation chapter");
        assertTrue(result.contains("证据驱动 (Evidence-Based)"), "should contain Evidence-Based chapter");
        assertTrue(result.contains("忠于目标 (Goal Fidelity)"), "should contain Goal Fidelity chapter");
        assertTrue(result.contains("审计完成 (Audit Check)"), "should contain Audit Check chapter");
        assertTrue(result.contains("阻塞审计 (Blocked Audit)"), "should contain Blocked Audit chapter");

        // 目标条件
        assertTrue(result.contains("test goal objective here"), "should contain goal condition");

        // 预算信息
        assertTrue(result.contains("已消耗 2.0k / 10.0k"), "should contain budget info");
    }

    @Test
    void fullModeWhenBudgetExactlyAtThreshold() {
        // 消耗 7000 / 10000 → 剩余 0.30 → 完整模式（阈值边界，≥ 0.30 走 full）
        Goal task = createGoalSnapshot(10000, 7000, 0);
        double ratio = computeRemainingRatio(task);
        assertTrue(ratio >= 0.30, "ratio at boundary should be full mode");

        String result = builder.buildEffectivePrompt(task);
        assertTrue(result.contains("目标延续 (Goal Continuation)"), "should be full mode at 0.30 boundary");
    }

    @Test
    void fullModeIncludesStagnationCheckWhenTriggered() {
        GoalPromptBuilder builderWithLowThreshold = new GoalPromptBuilder(2);
        Goal task = createGoalSnapshot(10000, 2000, 3);
        // 模拟 3 次停滞
        task.recordStagnation();
        task.recordStagnation();
        task.recordStagnation();

        String result = builderWithLowThreshold.buildEffectivePrompt(task);
        assertTrue(result.contains("进展质疑 (Stagnation Check)"), "should include stagnation check");
        assertTrue(result.contains("3 轮执行未产生实质性进展"), "should mention stagnation count");
    }

    // ===== 精简模式（15% ≤ 剩余 < 30%） =====

    @Test
    void compactModeWhenBudgetBetween15And30Percent() {
        // 消耗 8000 / 10000 → 剩余 20% → 精简模式
        Goal task = createGoalSnapshot(10000, 8000, 2);
        task.updateLastExecution("previous step result with some data");
        double ratio = computeRemainingRatio(task);
        assertTrue(ratio >= 0.15 && ratio < 0.30, "ratio should be in compact range, got " + ratio);

        String result = builder.buildEffectivePrompt(task);

        // 精简模式应包含目标延续和审计完成
        assertTrue(result.contains("目标延续 (Goal Continuation)"), "compact should have Goal Continuation");
        assertTrue(result.contains("审计完成 (Audit Check)"), "compact should have Audit Check");

        // 不应包含完整模式的章节
        assertFalse(result.contains("证据驱动 (Evidence-Based)"), "compact should NOT have Evidence-Based");
        assertFalse(result.contains("忠于目标 (Goal Fidelity)"), "compact should NOT have Goal Fidelity");
        assertFalse(result.contains("阻塞审计 (Blocked Audit)"), "compact should NOT have Blocked Audit");

        // 应包含上一轮摘要（源码格式："上一轮（第 2轮）"，2 后面无空格）
        assertTrue(result.contains("上一轮（第 2轮）"), "compact should include last round summary");
    }

    @Test
    void compactModeShowsBudgetInfo() {
        Goal task = createGoalSnapshot(10000, 8000, 0);
        String result = builder.buildEffectivePrompt(task);
        assertTrue(result.contains("80%"), "should show consumed percentage");
    }

    // ===== 极简模式（剩余 < 15%） =====

    @Test
    void minimalModeWhenBudgetBelow15Percent() {
        // 消耗 9000 / 10000 → 剩余 10% → 极简模式
        Goal task = createGoalSnapshot(10000, 9000, 0);
        double ratio = computeRemainingRatio(task);
        assertTrue(ratio < 0.15, "ratio should be in minimal range, got " + ratio);

        String result = builder.buildEffectivePrompt(task);

        // 极简模式：单行，不含章节标题
        assertTrue(result.contains("目标:"), "minimal should contain '目标:'");
        assertTrue(result.contains("test goal objective here"), "minimal should contain condition");

        // 不应包含章节标题
        assertFalse(result.contains("目标延续 (Goal Continuation)"), "minimal should NOT have chapters");
        assertFalse(result.contains("审计完成 (Audit Check)"), "minimal should NOT have Audit Check");
    }

    @Test
    void minimalModeAtExactThresholdBoundary() {
        // 消耗 8501 / 10000 → 剩余 14.99% → < 15% → 极简
        Goal task = createGoalSnapshot(10000, 8501, 0);
        double ratio = computeRemainingRatio(task);
        assertTrue(ratio < 0.15, "ratio 0.1499 should trigger minimal, got " + ratio);

        String result = builder.buildEffectivePrompt(task);
        assertFalse(result.contains("审计完成"), "should be minimal at 0.1499 boundary");
    }

    @Test
    void minimalModeDoesNotShowPreviousSummary() {
        Goal task = createGoalSnapshot(10000, 9000, 5);
        task.updateLastExecution("some previous work");
        String result = builder.buildEffectivePrompt(task);
        // 极简模式不应包含上一轮摘要
        assertFalse(result.contains("上一轮"), "minimal should not show previous round summary");
    }

    // ===== 非 Goal 模式 =====
    // 说明：旧版 buildEffectivePrompt(LoopTask) 对非 goal 任务原样返回 prompt。
    // 新版 GoalPromptBuilder 只接受 Goal，该职责已由类型系统保证，
    // 因此对应用例（nonGoalSnapshotReturnsPromptAsIs）随之删除，属语义收敛而非覆盖丢失。

    // ===== budgetRatio 工具方法 =====

    @Test
    void budgetRatioReturns1WhenMaxTokensIsZero() {
        Goal gs = new Goal("test", 0);
        gs.addTokens(99999);
        assertEquals(1.0, GoalPromptBuilder.budgetRatio(gs), 0.001,
                "maxTokens=0 means unlimited, ratio should be 1.0");
    }

    @Test
    void budgetRatioReturnsCorrectValue() {
        Goal gs = new Goal("test", 10000);
        gs.addTokens(2500);
        assertEquals(0.75, GoalPromptBuilder.budgetRatio(gs), 0.001);
    }

    // ===== buildBudgetLimitPrompt — tokens 重复 bug 修复专项测试 =====

    @Test
    void budgetLimitPrompt_smallTokens_noDuplication() {
        // consumed < 1000, maxTokens < 1000 — 最关键的 "tokens tokens" 修复场景
        Goal task = createGoalSnapshot(500, 300, 0);
        String result = builder.buildBudgetLimitPrompt(task);
        assertFalse(result.contains("tokens tokens"), "should not duplicate 'tokens'");
        assertTrue(result.contains("- 已消耗: 300 tokens / 500 tokens"),
                "should contain '300 tokens / 500 tokens'");
    }

    @Test
    void budgetLimitPrompt_smallConsumedLargeMax() {
        // consumed < 1000, maxTokens >= 1000
        Goal task = createGoalSnapshot(10000, 300, 0);
        String result = builder.buildBudgetLimitPrompt(task);
        assertFalse(result.contains("tokens tokens"), "should not duplicate 'tokens'");
        assertTrue(result.contains("- 已消耗: 300 tokens / 10.0k"),
                "consumed < 1000 shows tokens suffix, max >= 1000 shows k format");
    }

    @Test
    void budgetLimitPrompt_largeConsumedSmallMax() {
        // consumed >= 1000, maxTokens < 1000
        Goal task = createGoalSnapshot(500, 2000, 0);
        String result = builder.buildBudgetLimitPrompt(task);
        assertFalse(result.contains("tokens tokens"), "should not duplicate 'tokens'");
        assertTrue(result.contains("- 已消耗: 2.0k / 500 tokens"),
                "consumed >= 1000 shows k format, max < 1000 shows tokens suffix");
    }

    @Test
    void budgetLimitPrompt_largeTokensBoth() {
        // consumed >= 1000, maxTokens >= 1000
        Goal task = createGoalSnapshot(10000, 2000, 0);
        String result = builder.buildBudgetLimitPrompt(task);
        assertFalse(result.contains("tokens tokens"), "should not duplicate 'tokens'");
        assertTrue(result.contains("- 已消耗: 2.0k / 10.0k"),
                "both >= 1000 should use k format");
    }

    @Test
    void budgetLimitPrompt_unlimitedMaxTokens_smallConsumed() {
        // maxTokens = 0, consumed < 1000
        Goal task = createGoalSnapshot(0, 300, 0);
        String result = builder.buildBudgetLimitPrompt(task);
        assertFalse(result.contains("tokens tokens"), "should not duplicate 'tokens'");
        assertTrue(result.contains("- 已消耗: 300 tokens"),
                "unlimited budget, consumed < 1000 should show tokens suffix");
    }

    @Test
    void budgetLimitPrompt_unlimitedMaxTokens_largeConsumed() {
        // maxTokens = 0, consumed >= 1000
        Goal task = createGoalSnapshot(0, 2000, 0);
        String result = builder.buildBudgetLimitPrompt(task);
        assertFalse(result.contains("tokens tokens"), "should not duplicate 'tokens'");
        assertTrue(result.contains("- 已消耗: 2.0k"),
                "unlimited budget, consumed >= 1000 should use k format");
    }

    @Test
    void budgetLimitPrompt_zeroConsumed() {
        // 边界：consumed = 0
        Goal task = createGoalSnapshot(10000, 0, 0);
        String result = builder.buildBudgetLimitPrompt(task);
        assertFalse(result.contains("tokens tokens"), "should not duplicate 'tokens'");
        assertTrue(result.contains("- 已消耗: 0 tokens"),
                "0 tokens should still show '0 tokens'");
    }

    // ===== formatTokens 单测 =====

    @Test
    void formatTokens_below1000() {
        assertEquals("0 tokens", GoalPromptBuilder.formatTokens(0));
        assertEquals("1 tokens", GoalPromptBuilder.formatTokens(1));
        assertEquals("500 tokens", GoalPromptBuilder.formatTokens(500));
        assertEquals("999 tokens", GoalPromptBuilder.formatTokens(999));
    }

    @Test
    void formatTokens_1000to1M() {
        assertEquals("1.0k", GoalPromptBuilder.formatTokens(1000));
        assertEquals("1.5k", GoalPromptBuilder.formatTokens(1500));
        assertEquals("10.0k", GoalPromptBuilder.formatTokens(10000));
        assertEquals("999.9k", GoalPromptBuilder.formatTokens(999900));
    }

    @Test
    void formatTokens_1Mplus() {
        assertEquals("1.0M", GoalPromptBuilder.formatTokens(1_000_000));
        assertEquals("1.5M", GoalPromptBuilder.formatTokens(1_500_000));
        assertEquals("10.0M", GoalPromptBuilder.formatTokens(10_000_000));
    }

    // ===== buildBudgetInfo — 确保无 tokens 重复 =====

    @Test
    void buildBudgetInfo_noTokensDuplication() {
        Goal gs = new Goal("test", 10000);
        gs.addTokens(300);
        String info = GoalPromptBuilder.buildBudgetInfo(gs);
        assertFalse(info.contains("tokens tokens"), "buildBudgetInfo should not duplicate tokens");

        // maxTokens=0 分支
        Goal gs2 = new Goal("test", 0);
        gs2.addTokens(300);
        String info2 = GoalPromptBuilder.buildBudgetInfo(gs2);
        assertFalse(info2.contains("tokens tokens"), "buildBudgetInfo unlimited should not duplicate tokens");
    }

    // ===== buildBudgetLimitPrompt — 完整内容完整性 =====

    @Test
    void budgetLimitPrompt_containsAllSections() {
        Goal task = createGoalSnapshot(10000, 2000, 0);
        String result = builder.buildBudgetLimitPrompt(task);
        assertTrue(result.contains("预算耗尽 (Budget Limit)"), "should have header");
        assertTrue(result.contains("目标:"), "should contain goal label");
        assertTrue(result.contains("test goal objective here"), "should contain condition");
        assertTrue(result.contains("budget_limited"), "should mention budget_limited");
        assertTrue(result.contains("总结已完成的工作"), "should instruct summary");
        assertTrue(result.contains("剩余未完成的工作"), "should instruct remaining");
        assertTrue(result.contains("下一步建议"), "should instruct next steps");
    }

    // ===== 全面覆盖：完整模式剩余分支 =====

    @Test
    void fullModeWithPreviousSummary() {
        // 完整模式 + 非首轮 + 有上一轮结果（覆盖 "上一轮执行摘要" + truncateForPrompt 截断）
        Goal task = createGoalSnapshot(10000, 2000, 3);
        String longResult = "第一轮：分析了项目结构，发现主要模块。\n" +
                "第二轮：实现了核心功能，包括A、B、C三个组件。\n" +
                "第三轮：编写了单元测试，编写了大量测试用例确保覆盖率。";
        // 追加大量字符触发 truncate (maxLen=300, 当前字符约120)
        StringBuilder sb = new StringBuilder(longResult);
        while (sb.length() < 350) {
            sb.append(" 更多内容以确保文本长度超过300字符触发截断机制。");
        }
        task.updateLastExecution(sb.toString());
        String result = builder.buildEffectivePrompt(task);
        assertTrue(result.contains("上一轮执行摘要（第 3 轮）"), "full mode should show previous round summary");
        assertTrue(result.contains("请基于以上进展继续推进"), "should have progress instruction");
        assertTrue(result.contains("...(省略)..."), "should contain ellipsis when truncated");
    }

    @Test
    void fullModeWithoutStagnationCheck() {
        // 完整模式 + stagnationCount < threshold → 不应出现质疑章节
        Goal task = createGoalSnapshot(10000, 2000, 0);
        task.recordStagnation(); // 1 < 3
        String result = builder.buildEffectivePrompt(task);
        assertFalse(result.contains("进展质疑 (Stagnation Check)"), "should NOT show stagnation when count < threshold");
    }

    @Test
    void fullModeWithBudgetCritical() {
        // isBudgetCritical() 在完整模式下通常不可达（ratio >= 0.30 → consumed <= 70%，
        // 而 critical 默认需要 >= 85%）。改为实例级阈值注入（原静态 configure 已移除），
        // 设置 critical 阈值 1% 以覆盖该分支。
        Goal task = createGoalSnapshot(10000, 1000, 0);
        task.setThresholds(new BudgetThresholds(70, 1));
        String result = builder.buildEffectivePrompt(task);
        assertTrue(result.contains("[紧急]"), "full mode should show '[紧急]' when budget critical");
    }

    @Test
    void fullModeContainsGoalUpdateInstruction() {
        Goal task = createGoalSnapshot(10000, 2000, 0);
        String result = builder.buildEffectivePrompt(task);
        assertTrue(result.contains("goal_update(complete)"), "full mode should contain goal_update(complete) instruction");
        assertFalse(result.contains("[GOAL_ACHIEVED]"), "full mode should NOT contain [GOAL_ACHIEVED]");
    }

    // ===== 全面覆盖：精简模式剩余分支 =====

    @Test
    void compactModeFirstIter_noPreviousSummary() {
        // 精简模式 + isFirstIter → 不应有上一轮摘要
        Goal task = createGoalSnapshot(10000, 8000, 0); // iter=0
        String result = builder.buildEffectivePrompt(task);
        assertFalse(result.contains("上一轮"), "compact mode first iter should NOT show previous round");
    }

    @Test
    void compactModeNullLastResult_noPreviousSummary() {
        // 精简模式 + 非首轮但 lastResult=null → 不应有上一轮摘要
        Goal task = createGoalSnapshot(10000, 8000, 2);
        // lastResult 显式置 null
        task.updateLastExecution(null);
        String result = builder.buildEffectivePrompt(task);
        assertFalse(result.contains("上一轮"), "compact mode with null lastResult should NOT show previous round");
    }

    // ===== 全面覆盖：buildBudgetInfo =====

    @Test
    void buildBudgetInfo_withBudgetWarning() {
        // 消耗 75% (70% ≤ 75% < 85%) → isBudgetWarning() = true
        Goal gs = new Goal("test", 10000);
        gs.addTokens(7500);
        String info = GoalPromptBuilder.buildBudgetInfo(gs);
        assertTrue(info.contains("[预算提示]"), "should show budget warning at 75%");
        assertTrue(info.contains("75%"), "should show correct percentage 75%");
    }

    @Test
    void buildBudgetInfo_withBudgetCriticalAndRemaining() {
        // 消耗 90% + remainToken > 0 → 显示 (剩余: …)
        Goal gs = new Goal("test", 10000);
        gs.addTokens(9000);
        String info = GoalPromptBuilder.buildBudgetInfo(gs);
        assertTrue(info.contains("剩余: 1.0k"), "should show remaining tokens when critical");
    }

    @Test
    void buildBudgetInfo_withElapsedTime() throws Exception {
        Goal gs = new Goal("test", 10000);
        gs.addTokens(5000);
        // 反射设置 startEpochMs 为 5 秒前
        Field field = Goal.class.getDeclaredField("startEpochMs");
        field.setAccessible(true);
        field.setLong(gs, System.currentTimeMillis() - 5000);
        String info = GoalPromptBuilder.buildBudgetInfo(gs);
        assertTrue(info.contains("耗时:"), "should show elapsed time when > 1000ms");
    }

    @Test
    void buildBudgetInfo_startEpochMsZero() throws Exception {
        // startEpochMs = 0 → 不显示耗时，其余信息正常
        Goal gs = new Goal("test", 10000);
        gs.addTokens(300);
        Field field = Goal.class.getDeclaredField("startEpochMs");
        field.setAccessible(true);
        field.setLong(gs, 0);
        String info = GoalPromptBuilder.buildBudgetInfo(gs);
        assertFalse(info.contains("耗时:"), "should NOT show elapsed time when startEpochMs is 0");
        assertTrue(info.contains("已消耗 300 tokens / 10.0k"), "should still show budget info");
    }

    @Test
    void buildBudgetInfo_maxTokensZero_consumedZero_startEpochMsZero() throws Exception {
        // 三个零 → 空字符串
        Goal gs = new Goal("test", 0);
        Field field = Goal.class.getDeclaredField("startEpochMs");
        field.setAccessible(true);
        field.setLong(gs, 0);
        String info = GoalPromptBuilder.buildBudgetInfo(gs);
        assertEquals("", info, "should be empty when max=0, consumed=0, startEpochMs=0");
    }

    // ===== 全面覆盖：budgetPercent =====

    @Test
    void budgetPercent_zeroOrNegativeTotal() {
        assertEquals("0", GoalPromptBuilder.budgetPercent(100, 0));
        assertEquals("0", GoalPromptBuilder.budgetPercent(100, -1));
    }

    @Test
    void budgetPercent_normal() {
        assertEquals("25", GoalPromptBuilder.budgetPercent(2500, 10000));
        assertEquals("0", GoalPromptBuilder.budgetPercent(0, 10000));
        assertEquals("100", GoalPromptBuilder.budgetPercent(10000, 10000));
    }

    // ===== 全面覆盖：formatDuration =====

    @Test
    void formatDuration_seconds() {
        assertEquals("0s", GoalPromptBuilder.formatDuration(0));
        assertEquals("30s", GoalPromptBuilder.formatDuration(30_000));
        assertEquals("59s", GoalPromptBuilder.formatDuration(59_000));
    }

    @Test
    void formatDuration_minutes() {
        assertEquals("1m 0s", GoalPromptBuilder.formatDuration(60_000));
        assertEquals("1m 30s", GoalPromptBuilder.formatDuration(90_000));
        assertEquals("59m 59s", GoalPromptBuilder.formatDuration(3_599_000));
    }

    @Test
    void formatDuration_hours() {
        assertEquals("1h 0m", GoalPromptBuilder.formatDuration(3_600_000));
        assertEquals("2h 5m", GoalPromptBuilder.formatDuration(7_500_000));
        assertEquals("24h 0m", GoalPromptBuilder.formatDuration(86_400_000));
    }

    // ===== 全面覆盖：truncateForPrompt =====

    @Test
    void truncateForPrompt_nullOrEmpty() {
        assertEquals("", GoalPromptBuilder.truncateForPrompt(null, 100));
        assertEquals("", GoalPromptBuilder.truncateForPrompt("", 100));
    }

    @Test
    void truncateForPrompt_withinMaxLen() {
        assertEquals("hello", GoalPromptBuilder.truncateForPrompt("hello", 100));
        assertEquals("hello", GoalPromptBuilder.truncateForPrompt("hello", 5));
    }

    @Test
    void truncateForPrompt_truncated() {
        String text = "hello world this is a long text for testing truncation in loop prompt builder";
        String result = GoalPromptBuilder.truncateForPrompt(text, 10);
        assertTrue(result.startsWith("hello"), "should start with first half");
        assertTrue(result.contains("...(省略)..."), "should contain ellipsis");
        assertTrue(result.endsWith("lder"), "should end with last half");
    }

    // ===== 全面覆盖：buildBudgetLimitPrompt 耗时行 =====

    @Test
    void budgetLimitPrompt_containsDurationLine() {
        Goal task = createGoalSnapshot(10000, 2000, 0);
        String result = builder.buildBudgetLimitPrompt(task);
        assertTrue(result.contains("- 耗时:"), "budget limit prompt should contain duration line");
    }
}
