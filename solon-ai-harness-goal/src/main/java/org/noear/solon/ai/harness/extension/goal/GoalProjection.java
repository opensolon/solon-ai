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

import org.noear.solon.flow.FlowContext;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Goal 会话快照投影（{@code session.goal}）的读写门面。
 *
 * <p><b>为什么需要本类</b>：投影是一张 Map，此前宿主为了做启动恢复与历史数据迁移，
 * 必须自己 {@code data().get("session.goal")} 再 {@code map.get("status")}，甚至手工拼一张
 * 字段名齐全的 Map 写回去——等于把 harness 的持久化格式复制到了宿主代码里。键名一旦调整
 * 宿主就静默失效，字段语义变更（如 {@code condition}/{@code objective} 双写）宿主也无从得知。</p>
 *
 * <p>本类是该投影<b>字段名的唯一出处</b>：运行态的读写（{@code Goal.toMap/fromMap}）
 * 同样委托这里，宿主只面对类型化 getter 与 {@link #writeTo}，不再出现任何字段名字符串。</p>
 *
 * <p>只读值对象，不可变；由 {@link #from(FlowContext)} / {@link #builder()} 创建。</p>
 *
 * @author noear
 * @since 4.2.0
 */
public final class GoalProjection {
    // ===== 投影键名（唯一出处，宿主不应硬编码） =====
    static final String K_GOAL_ID = "goalId";
    static final String K_OBJECTIVE = "objective";
    static final String K_CONDITION = "condition";
    static final String K_STATUS = "status";
    static final String K_CONSUMED_TOKENS = "consumedTokens";
    static final String K_MAX_TOKENS = "maxTokens";
    static final String K_MAX_ITERATIONS = "maxIterations";
    static final String K_START_EPOCH_MS = "startEpochMs";
    static final String K_MAX_DURATION_MS = "maxDurationMs";
    static final String K_CURRENT_ITERATION = "currentIteration";
    static final String K_LAST_RESULT = "lastResult";
    static final String K_LAST_EXECUTED_AT = "lastExecutedAt";
    static final String K_STAGNATION_COUNT = "stagnationCount";
    static final String K_CONSECUTIVE_ERRORS = "consecutiveErrors";

    private final String goalId;
    private final String objective;
    private final GoalStatus status;
    private final long consumedTokens;
    private final long maxTokens;
    private final int maxIterations;
    private final long startEpochMs;
    private final long maxDurationMs;
    private final int currentIteration;
    private final String lastResult;
    private final long lastExecutedAt;
    private final int stagnationCount;
    private final int consecutiveErrors;

    private GoalProjection(Builder b) {
        this.goalId = b.goalId;
        this.objective = b.objective;
        this.status = b.status;
        this.consumedTokens = b.consumedTokens;
        this.maxTokens = b.maxTokens;
        this.maxIterations = b.maxIterations;
        this.startEpochMs = b.startEpochMs;
        this.maxDurationMs = b.maxDurationMs;
        this.currentIteration = b.currentIteration;
        this.lastResult = b.lastResult;
        this.lastExecutedAt = b.lastExecutedAt;
        this.stagnationCount = b.stagnationCount;
        this.consecutiveErrors = b.consecutiveErrors;
    }

    // ===== 类型化读取（宿主侧唯一合法手段） =====

    public String getGoalId() { return goalId; }

    /** 目标正文（持久化键 {@code objective}/{@code condition} 双写，读取时 condition 优先）。 */
    public String getObjective() { return objective; }

    /** 投影状态枚举（而非状态名字符串）。 */
    public GoalStatus getStatus() { return status; }

    /** 是否活跃（PURSUING）。 */
    public boolean isActive() { return status != null && status.isActive(); }

    /** 是否终态。 */
    public boolean isTerminal() { return status != null && status.isTerminal(); }

    public long getConsumedTokens() { return consumedTokens; }
    public long getMaxTokens() { return maxTokens; }
    public int getMaxIterations() { return maxIterations; }
    public long getStartEpochMs() { return startEpochMs; }
    public long getMaxDurationMs() { return maxDurationMs; }
    public int getCurrentIteration() { return currentIteration; }

    public String getLastResult() { return lastResult; }

    public long getLastExecutedAt() { return lastExecutedAt; }
    public int getStagnationCount() { return stagnationCount; }
    public int getConsecutiveErrors() { return consecutiveErrors; }

    // ===== 读写会话上下文 =====

    /**
     * 读取会话上下文中的 goal 投影。
     *
     * @return null = 无 goal、投影非 Map、或 status 缺失/非法（与 hydrate 同口径，交由守卫兜底）
     */
    public static GoalProjection from(FlowContext context) {
        if (context == null) {
            return null;
        }
        return from(context.data().get(GoalAttrs.GOAL));
    }

    public static GoalProjection from(Object projection) {
        if (!(projection instanceof Map)) {
            return null;
        }
        Map<?, ?> map = (Map<?, ?>) projection;
        Object statusValue = map.get(K_STATUS);
        if (statusValue == null) {
            return null;
        }
        GoalStatus status;
        try {
            status = GoalStatus.valueOf(String.valueOf(statusValue));
        } catch (Throwable e) {
            // 未知状态名：按「无有效投影」处理，避免宿主拿到半残对象
            return null;
        }
        return new Builder()
                .goalId(str(map.get(K_GOAL_ID)))
                .objective(map.get(K_CONDITION) != null ? str(map.get(K_CONDITION)) : str(map.get(K_OBJECTIVE)))
                .status(status)
                .consumedTokens(lng(map.get(K_CONSUMED_TOKENS)))
                .maxTokens(lng(map.get(K_MAX_TOKENS)))
                .maxIterations((int) lng(map.get(K_MAX_ITERATIONS)))
                .startEpochMs(lng(map.get(K_START_EPOCH_MS)))
                .maxDurationMs(lng(map.get(K_MAX_DURATION_MS)))
                .currentIteration((int) lng(map.get(K_CURRENT_ITERATION)))
                .lastResult(str(map.get(K_LAST_RESULT)))
                .lastExecutedAt(lng(map.get(K_LAST_EXECUTED_AT)))
                .stagnationCount((int) lng(map.get(K_STAGNATION_COUNT)))
                .consecutiveErrors((int) lng(map.get(K_CONSECUTIVE_ERRORS)))
                .build();
    }

    /** 轻读投影状态（宿主做启动恢复判定时的最常用操作）。 */
    public static GoalStatus statusOf(FlowContext context) {
        GoalProjection projection = from(context);
        return projection == null ? null : projection.getStatus();
    }

    /** 写入会话上下文（覆盖语义；不落盘，由调用方决定是否 updateSnapshot / 写文件）。 */
    public void writeTo(FlowContext context) {
        if (context == null) {
            return;
        }
        context.data().put(GoalAttrs.GOAL, toMap());
    }

    /** 转为投影 Map（键名即持久化契约）。 */
    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        if (goalId != null) map.put(K_GOAL_ID, goalId);
        if (objective != null) {
            map.put(K_OBJECTIVE, objective);
            map.put(K_CONDITION, objective);
        }
        if (status != null) map.put(K_STATUS, status.name());
        map.put(K_CONSUMED_TOKENS, consumedTokens);
        map.put(K_MAX_TOKENS, maxTokens);
        map.put(K_MAX_ITERATIONS, maxIterations);
        map.put(K_START_EPOCH_MS, startEpochMs);
        map.put(K_MAX_DURATION_MS, maxDurationMs);
        map.put(K_CURRENT_ITERATION, currentIteration);
        if (lastResult != null) map.put(K_LAST_RESULT, lastResult);
        if (lastExecutedAt > 0) map.put(K_LAST_EXECUTED_AT, lastExecutedAt);
        if (stagnationCount > 0) map.put(K_STAGNATION_COUNT, stagnationCount);
        if (consecutiveErrors > 0) map.put(K_CONSECUTIVE_ERRORS, consecutiveErrors);
        return map;
    }

    // ===== Goal 互通（字段名只在本类出现） =====

    static GoalProjection ofGoal(Goal gs) {
        return new Builder()
                .goalId(gs.getId())
                .objective(gs.getObjective())
                .status(gs.getStatus())
                .consumedTokens(gs.getConsumedTokens())
                .maxTokens(gs.getMaxTokens())
                .maxIterations(gs.getMaxIterations())
                .startEpochMs(gs.getStartEpochMs())
                .maxDurationMs(gs.getMaxDurationMs())
                .currentIteration(gs.getCurrentIteration())
                .lastResult(gs.getLastResult())
                .lastExecutedAt(gs.getLastExecutedAt())
                .stagnationCount(gs.getStagnationCount())
                .consecutiveErrors(gs.getConsecutiveErrors())
                .build();
    }

    public static Builder builder() {
        return new Builder();
    }

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static long lng(Object value) {
        return value instanceof Number ? ((Number) value).longValue() : 0L;
    }

    @Override
    public String toString() {
        return "GoalProjection{goalId=" + goalId + ", status=" + status
                + ", objective=" + objective + "}";
    }

    /** 投影构造器（历史数据迁移场景：宿主逐字段填充，字段集合由 harness 定义）。 */
    public static final class Builder {
        private String goalId;
        private String objective;
        private GoalStatus status;
        private long consumedTokens;
        private long maxTokens;
        private int maxIterations;
        private long startEpochMs;
        private long maxDurationMs;
        private int currentIteration;
        private String lastResult;
        private long lastExecutedAt;
        private int stagnationCount;
        private int consecutiveErrors;

        public Builder goalId(String goalId) { this.goalId = goalId; return this; }
        public Builder objective(String objective) { this.objective = objective; return this; }
        public Builder status(GoalStatus status) { this.status = status; return this; }
        /** 按状态名设置；非法名返回 null（调用方据此判定迁移数据不可用）。 */
        public Builder statusName(String statusName) {
            if (statusName == null) return this;
            try {
                this.status = GoalStatus.valueOf(statusName);
            } catch (Throwable e) {
                this.status = null;
            }
            return this;
        }
        public Builder consumedTokens(long consumedTokens) { this.consumedTokens = consumedTokens; return this; }
        public Builder maxTokens(long maxTokens) { this.maxTokens = maxTokens; return this; }
        public Builder maxIterations(int maxIterations) { this.maxIterations = maxIterations; return this; }
        public Builder startEpochMs(long startEpochMs) { this.startEpochMs = startEpochMs; return this; }
        public Builder maxDurationMs(long maxDurationMs) { this.maxDurationMs = maxDurationMs; return this; }
        public Builder currentIteration(int currentIteration) { this.currentIteration = currentIteration; return this; }
        public Builder lastResult(String lastResult) { this.lastResult = lastResult; return this; }
        public Builder lastExecutedAt(long lastExecutedAt) { this.lastExecutedAt = lastExecutedAt; return this; }
        public Builder stagnationCount(int stagnationCount) { this.stagnationCount = stagnationCount; return this; }
        public Builder consecutiveErrors(int consecutiveErrors) { this.consecutiveErrors = consecutiveErrors; return this; }

        public GoalProjection build() {
            return new GoalProjection(this);
        }
    }
}
