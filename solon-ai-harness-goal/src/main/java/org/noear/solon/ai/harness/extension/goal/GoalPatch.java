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
 * goal 修改补丁（与 loop 的 {@code LoopPatch} 对称）：null = 保持原值。
 *
 * <p>宿主把请求参数原样传入即可，不必先读旧值再合并：</p>
 * <pre>{@code
 * goal.update(sid, GoalPatch.create().objective(prompt).maxTokens(maxTokens), stopper);
 * }</pre>
 *
 * <p>空白正文视为未设置；预算负数按 0（不限制）处理。</p>
 *
 * @author noear
 * @since 4.2.0
 */
public final class GoalPatch {
    private String objective;
    private Long maxTokens;
    private Long maxDurationMs;
    private Integer maxIterations;

    private GoalPatch() {
    }

    public static GoalPatch create() {
        return new GoalPatch();
    }

    /** 目标正文（null 或空白 = 保持）。 */
    public GoalPatch objective(String objective) {
        this.objective = (objective == null || objective.trim().isEmpty()) ? null : objective.trim();
        return this;
    }

    /** token 预算（null = 保持；0 = 不限制）。 */
    public GoalPatch maxTokens(Long maxTokens) {
        this.maxTokens = maxTokens == null ? null : Math.max(0L, maxTokens);
        return this;
    }

    /** 时长预算毫秒（null = 保持；0 = 不限制）。 */
    public GoalPatch maxDurationMs(Long maxDurationMs) {
        this.maxDurationMs = maxDurationMs == null ? null : Math.max(0L, maxDurationMs);
        return this;
    }

    /** 轮次预算（null = 保持；0 = 不限制）。 */
    public GoalPatch maxIterations(Integer maxIterations) {
        this.maxIterations = maxIterations == null ? null : Math.max(0, maxIterations);
        return this;
    }

    String getObjective() {
        return objective;
    }

    Long getMaxTokens() {
        return maxTokens;
    }

    Long getMaxDurationMs() {
        return maxDurationMs;
    }

    Integer getMaxIterations() {
        return maxIterations;
    }

    /** 是否没有任何字段需要修改。 */
    boolean isEmpty() {
        return objective == null && maxTokens == null && maxDurationMs == null && maxIterations == null;
    }

    @Override
    public String toString() {
        return "GoalPatch{objective=" + objective + ", maxTokens=" + maxTokens
                + ", maxDurationMs=" + maxDurationMs + ", maxIterations=" + maxIterations + "}";
    }
}
