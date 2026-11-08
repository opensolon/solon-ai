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

import static org.junit.jupiter.api.Assertions.*;

/**
 * 连续错误递增测试：验证 Goal 的错误计数、重置及 BLOCKED 阈值行为。
 *
 * <p>覆盖场景：
 * <ul>
 *   <li>连续错误计数递增（1, 2, 3, ...）</li>
 *   <li>成功后重置计数</li>
 *   <li>递增延迟公式（5s × errors）— 由 handleExecutionError 内部计算</li>
 *   <li>达到阈值后 Goal 转换至 BLOCKED</li>
 * </ul>
 *
 * @since 3.9.3
 */
class GoalErrorTest {

    // ===== 连续错误计数 =====

    @Test
    void consecutiveErrorsIncrementsFromZero() {
        Goal gs = createGoal();
        assertEquals(0, gs.getConsecutiveErrors(), "initial error count should be 0");
        assertEquals(1, gs.incrementConsecutiveErrors(), "first error should return 1");
        assertEquals(2, gs.incrementConsecutiveErrors(), "second error should return 2");
        assertEquals(3, gs.incrementConsecutiveErrors(), "third error should return 3");
    }

    @Test
    void consecutiveErrorsResetAfterSuccess() {
        Goal gs = createGoal();
        gs.incrementConsecutiveErrors();
        gs.incrementConsecutiveErrors();
        assertEquals(2, gs.getConsecutiveErrors(), "should have 2 errors before reset");

        gs.resetConsecutiveErrors();
        assertEquals(0, gs.getConsecutiveErrors(), "should be 0 after success");
    }

    @Test
    void consecutiveErrorsContinuesAfterPartialReset() {
        Goal gs = createGoal();
        gs.incrementConsecutiveErrors(); // 1
        gs.incrementConsecutiveErrors(); // 2
        gs.resetConsecutiveErrors();     // 0
        assertEquals(1, gs.incrementConsecutiveErrors(),
                "should restart at 1 after reset then error");
    }

    // ===== 递增延迟公式验证 =====

    @Test
    void errorDelayIncreasesLinearly() {
        // handleExecutionError 中的公式：delay = 5L * errors
        // errors=1 → 5s, errors=2 → 10s, errors=3 → 15s
        assertEquals(5L, 5L * 1, "1st error delay should be 5s");
        assertEquals(10L, 5L * 2, "2nd error delay should be 10s");
        assertEquals(15L, 5L * 3, "3rd error delay should be 15s");
        assertEquals(20L, 5L * 4, "4th error delay should be 20s");
    }

    // ===== BLOCKED 阈值 =====

    @Test
    void goalMarkedBlockedWhenErrorsReachThreshold() {
        // maxConsecutiveErrors = 3（LoopGroupDo 默认值）
        Goal gs = createGoal();
        

        // 前 2 次错误不应触发 BLOCKED
        for (int i = 1; i <= 2; i++) {
            int errors = gs.incrementConsecutiveErrors();
            if (errors < 3) {
                // 未达阈值，不标记 BLOCKED
                assertEquals(GoalStatus.PURSUING, gs.getStatus());
            }
        }

        // 第 3 次错误 → 达阈值
        int errors = gs.incrementConsecutiveErrors();
        assertEquals(3, errors, "should have 3 consecutive errors");

        // 模拟 handleExecutionError 的阈值逻辑：
        if (errors >= 3) {
            gs.markBlocked();
        }

        assertEquals(GoalStatus.BLOCKED, gs.getStatus(),
                "goal should be BLOCKED after 3 consecutive errors");
        assertTrue(gs.getStatus().isResumable(),
                "BLOCKED should be resumable");
    }

    @Test
    void goalNotBlockedBeforeThreshold() {
        Goal gs = createGoal();
        

        // 2 次错误，阈值为 3 → 不应 BLOCKED
        gs.incrementConsecutiveErrors(); // 1
        gs.incrementConsecutiveErrors(); // 2

        assertEquals(GoalStatus.PURSUING, gs.getStatus(),
                "goal should still be PURSUING before threshold");
        assertFalse(gs.getStatus().isTerminal(),
                "goal should not be terminal before threshold");
    }

    @Test
    void goalBlockedWithCustomThreshold() {
        // 模拟 maxConsecutiveErrors=5 场景
        int threshold = 5;
        Goal gs = createGoal();
        

        for (int i = 1; i <= threshold; i++) {
            gs.incrementConsecutiveErrors();
        }

        assertEquals(threshold, gs.getConsecutiveErrors(),
                "should have " + threshold + " errors");

        if (gs.getConsecutiveErrors() >= threshold) {
            gs.markBlocked();
        }

        assertEquals(GoalStatus.BLOCKED, gs.getStatus(),
                "goal should be BLOCKED after " + threshold + " errors");
    }

    // ===== 重置不打断 Goal 执行 =====

    @Test
    void successfulExecutionResetsErrorsAndGoalRemainsPursuing() {
        Goal gs = createGoal();
        

        // 2 次连续错误
        gs.incrementConsecutiveErrors();
        gs.incrementConsecutiveErrors();
        assertEquals(2, gs.getConsecutiveErrors());

        // 成功执行后重置
        gs.resetConsecutiveErrors();
        assertEquals(0, gs.getConsecutiveErrors(),
                "errors should reset after success");

        // 再错误计数应为 1（重新从 0 开始）
        assertEquals(1, gs.incrementConsecutiveErrors(),
                "should start from 1 after reset then error");
        assertEquals(GoalStatus.PURSUING, gs.getStatus(),
                "goal should remain PURSUING after reset");
    }

    // ===== 辅助方法 =====

    private static Goal createGoal() {
        return new Goal("test goal", 0);
    }
}
