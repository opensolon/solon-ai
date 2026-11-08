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

/**
 * goal 状态（{@link GoalSnapshot}、{@link GoalProjection}、{@link GoalExtension} 共用）。
 *
 * <p>状态机（6 态）：活跃的只有 {@link #PURSUING}，可恢复的是 {@link #PAUSED} / {@link #BLOCKED}，
 * 其余为终态（只能清除，不能恢复）。</p>
 * <pre>
 *   PURSUING ⇄ PAUSED → ACHIEVED | BUDGET_LIMITED | ITERATION_LIMITED
 *       ↓
 *     BLOCKED → (resume) → PURSUING
 * </pre>
 *
 * @author noear
 * @since 4.2.0
 */
public enum GoalStatus {
    /** 进行中：调度器会推进它 */
    PURSUING,
    /** 暂停：停调度、不推进；可恢复 */
    PAUSED,
    /** 受阻：模型主动声明阻塞（{@code goal_update("blocked")}），或同类错误连续达到阈值后被熔断；可恢复 */
    BLOCKED,
    /** 已达成（终态）：目标完成，或模型声明完成且验证器通过 */
    ACHIEVED,
    /** 预算耗尽（终态）：token / 时长达到 {@code maxTokens} / {@code maxDurationMs} */
    BUDGET_LIMITED,
    /** 轮次上限（终态）：达到 {@code maxIterations} 仍未完成 */
    ITERATION_LIMITED;

    /** 是否活跃（会被调度推进）。 */
    public boolean isActive() {
        return this == PURSUING;
    }

    /** 是否终态（不可恢复，只能清除）。 */
    public boolean isTerminal() {
        return this == ACHIEVED || this == BUDGET_LIMITED || this == ITERATION_LIMITED;
    }

    /** 是否可恢复（PAUSED / BLOCKED）。 */
    public boolean isResumable() {
        return this == PAUSED || this == BLOCKED;
    }
}
