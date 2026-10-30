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

import org.noear.snack4.ONode;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Goal 状态模型 — Codex CLI 对齐。
 *
 * <p>6 态状态机：
 * <pre>
 *   PURSUING ⇄ PAUSED → ACHIEVED | BUDGET_LIMITED | ITERATION_LIMITED
 *       ↓
 *     BLOCKED → (resume) → PURSUING
 * </pre>
 * BLOCKED 是模型主动声明的阻塞（通过 update_goal("blocked")），可与 PAUSED 区分。
 */
public class GoalState {

    public enum Status {
        PURSUING, PAUSED, BLOCKED, ACHIEVED, BUDGET_LIMITED, ITERATION_LIMITED;

        public boolean isActive() { return this == PURSUING; }
        public boolean isTerminal() {
            return this == ACHIEVED || this == BUDGET_LIMITED || this == ITERATION_LIMITED;
        }
        public boolean isResumable() { return this == PAUSED || this == BLOCKED; }
    }

    private String id;
    private volatile String condition;
    private volatile Status status;
    private volatile long consumedTokens;
    private volatile long maxTokens;
    private volatile int maxIterations;
    private long startEpochMs;

    // ===== Goal 实体运行时字段（原挂在 LoopTask 上，随 goal 会话化迁入） =====
    /** 时间预算毫秒（0 = 不限制）。 */
    private volatile long maxDurationMs;
    /** 已完成的轮次。 */
    private volatile int currentIteration;
    /** 最近一次执行结果摘要。 */
    private volatile String lastResult;
    /** 最近一次执行时间（epoch ms，0 = 未执行）。 */
    private volatile long lastExecutedAt;
    /** 连续无进展轮次。 */
    private volatile int stagnationCount;
    /** 上一轮执行指纹。 */
    private volatile String lastFingerprint;
    /** 连续异常计数。 */
    private volatile int consecutiveErrors;
    /** 最后一次错误分类。 */
    private volatile String lastErrorType;
    /** 最后一次错误摘要。 */
    private volatile String lastErrorSummary;
    /** 连续同类型错误计数。 */
    private volatile int consecutiveSameTypeErrors;

    /** 预算提示阈值（实例级，避免多工作区互相覆盖）。 */
    private BudgetThresholds thresholds = BudgetThresholds.defaults();

    public GoalState(String condition, long maxTokens) {
        this(condition, maxTokens, BudgetThresholds.defaults());
    }

    public GoalState(String condition, long maxTokens, BudgetThresholds thresholds) {
        this.id = UUID.randomUUID().toString().substring(0, 8);
        this.condition = condition;
        this.status = Status.PURSUING;
        this.consumedTokens = 0;
        this.maxTokens = maxTokens;
        this.startEpochMs = System.currentTimeMillis();
        if (thresholds != null) {
            this.thresholds = thresholds;
        }
    }

    private GoalState() {} // for deserialization

    /** 注入预算阈值（由 GoalEngine 从本工作区配置注入）。 */
    public void setThresholds(BudgetThresholds thresholds) {
        if (thresholds != null) {
            this.thresholds = thresholds;
        }
    }

    public BudgetThresholds getThresholds() {
        return thresholds;
    }

    public boolean pause() {
        if (status != Status.PURSUING) return false;
        this.status = Status.PAUSED;
        return true;
    }

    public boolean resume() {
        if (!status.isResumable()) return false;
        this.status = Status.PURSUING;
        return true;
    }

    public void achieve() {
        if (status == Status.PURSUING) this.status = Status.ACHIEVED;
    }

    /** 完成声明未通过运行时证据校验时，退回继续执行。 */
    public boolean rejectAchievement() {
        if (status != Status.ACHIEVED) return false;
        this.status = Status.PURSUING;
        return true;
    }

    public void markBudgetLimited() {
        if (status == Status.PURSUING) this.status = Status.BUDGET_LIMITED;
    }

    public void markIterationLimited() {
        if (status == Status.PURSUING) this.status = Status.ITERATION_LIMITED;
    }

    /** 模型主动声明阻塞（PURSUING → BLOCKED） */
    public void markBlocked() {
        if (status == Status.PURSUING) {
            this.status = Status.BLOCKED;
        }
    }

    public void addTokens(long tokens) {
        this.consumedTokens += tokens;
    }

    public boolean isBudgetExceeded() {
        return maxTokens > 0 && consumedTokens >= maxTokens;
    }

    public boolean isIterationExceeded(int completedIterations) {
        return maxIterations > 0 && completedIterations >= maxIterations;
    }

    public boolean isBudgetCritical() {
        if (maxTokens <= 0) return false;
        double threshold = thresholds.getCriticalPercent() / 100.0;
        return (double) consumedTokens / maxTokens >= threshold;
    }

    public boolean isBudgetWarning() {
        if (maxTokens <= 0) return false;
        if (isBudgetCritical()) return false;
        double threshold = thresholds.getWarningPercent() / 100.0;
        return (double) consumedTokens / maxTokens >= threshold;
    }

    // ===== 预算扩容 =====

    public void extendBudget(long additionalTokens) {
        if (additionalTokens <= 0) return;
        this.maxTokens += additionalTokens;
        if (status == Status.BUDGET_LIMITED) {
            this.status = Status.PURSUING;
        }
    }

    // ===== Getters =====

    public String getId() { return id; }
    public String getCondition() { return condition; }
    public void setCondition(String condition) { this.condition = condition; }
    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }
    public long getConsumedTokens() { return consumedTokens; }
    public long getMaxTokens() { return maxTokens; }
    public void setMaxTokens(long maxTokens) { this.maxTokens = maxTokens; }
    public int getMaxIterations() { return maxIterations; }
    public void setMaxIterations(int maxIterations) { this.maxIterations = Math.max(0, maxIterations); }
    public long getStartEpochMs() { return startEpochMs; }


    // ===== Goal 实体运行时字段访问 =====

    public int getCurrentIteration() { return currentIteration; }
    public void setCurrentIteration(int currentIteration) { this.currentIteration = currentIteration; }

    public int incrementIteration() { return ++currentIteration; }

    public long getMaxDurationMs() { return maxDurationMs; }
    public void setMaxDurationMs(long maxDurationMs) { this.maxDurationMs = maxDurationMs; }

    public String getLastResult() { return lastResult; }

    /** 记录一次执行结果与时间。 */
    public void updateLastExecution(String result) {
        this.lastResult = result;
        this.lastExecutedAt = System.currentTimeMillis();
    }

    public long getLastExecutedAt() { return lastExecutedAt; }

    public int getStagnationCount() { return stagnationCount; }
    public void recordStagnation() { this.stagnationCount++; }
    public void resetStagnation() { this.stagnationCount = 0; }

    public String getLastFingerprint() { return lastFingerprint; }
    public void setLastFingerprint(String lastFingerprint) { this.lastFingerprint = lastFingerprint; }

    public int getConsecutiveErrors() { return consecutiveErrors; }
    public int incrementConsecutiveErrors() { return ++consecutiveErrors; }

    public void resetConsecutiveErrors() {
        this.consecutiveErrors = 0;
        this.consecutiveSameTypeErrors = 0;
        this.lastErrorType = null;
        this.lastErrorSummary = null;
    }

    public String getLastErrorType() { return lastErrorType; }
    public String getLastErrorSummary() { return lastErrorSummary; }
    public int getConsecutiveSameTypeErrors() { return consecutiveSameTypeErrors; }

    /**
     * 记录一次错误，更新错误分类与连续同类型计数。
     *
     * @return 连续同类型错误次数
     */
    public int recordError(String errorType, String errorSummary) {
        if (errorType != null && errorType.equals(this.lastErrorType)) {
            this.consecutiveSameTypeErrors++;
        } else {
            this.consecutiveSameTypeErrors = 1;
        }
        this.lastErrorType = errorType;
        this.lastErrorSummary = errorSummary;
        return this.consecutiveSameTypeErrors;
    }

    // ===== 序列化 =====

    /**
     * 序列化为 ONode（导出用）
     * <p>
     * 运行时连续性字段（lastFingerprint / lastErrorType / lastErrorSummary /
     * consecutiveSameTypeErrors）不持久化：它们描述的是单一进程内的执行连续性，
     * 跨进程保留会误判去重与错误熔断。与 {@link #toMap()} 保持同一取舍。
     */
    public ONode toONode() {
        ONode node = new ONode();
        node.set("id", id);
        node.set("condition", condition);
        node.set("status", status.name());
        node.set("consumedTokens", consumedTokens);
        node.set("maxTokens", maxTokens);
        node.set("maxIterations", maxIterations);
        node.set("startEpochMs", startEpochMs);

        // 运行时字段（缺失即默认值，保持旧 JSON 兼容）
        if (maxDurationMs > 0) node.set("maxDurationMs", maxDurationMs);
        if (currentIteration > 0) node.set("currentIteration", currentIteration);
        if (lastResult != null) node.set("lastResult", lastResult);
        if (lastExecutedAt > 0) node.set("lastExecutedAt", lastExecutedAt);
        if (stagnationCount > 0) node.set("stagnationCount", stagnationCount);
        if (consecutiveErrors > 0) node.set("consecutiveErrors", consecutiveErrors);
        return node;
    }

    /**
     * 从 ONode 重建
     * <p>
     * node 为 null 或缺少 status 时返回 null（与 {@link #fromMap(Map)} 对齐），
     * 调用方必须判空；旧版本此处对缺失 status 会抛 NPE。
     */
    public static GoalState fromONode(ONode node) {
        if (node == null || node.getOrNull("status") == null) {
            return null;
        }

        GoalState gs = new GoalState();
        gs.id = node.get("id").getString();
        gs.condition = node.get("condition").getString();
        gs.status = Status.valueOf(node.get("status").getString());
        gs.consumedTokens = node.get("consumedTokens").getLong();
        gs.maxTokens = node.get("maxTokens").getLong();
        gs.maxIterations = node.getOrNull("maxIterations") != null
                ? node.get("maxIterations").getInt() : 0;
        gs.startEpochMs = node.get("startEpochMs").getLong();

        gs.maxDurationMs = node.getOrNull("maxDurationMs") != null
                ? node.get("maxDurationMs").getLong() : 0L;
        gs.currentIteration = node.getOrNull("currentIteration") != null
                ? node.get("currentIteration").getInt() : 0;
        gs.lastResult = node.getOrNull("lastResult") != null
                ? node.get("lastResult").getString() : null;
        gs.lastExecutedAt = node.getOrNull("lastExecutedAt") != null
                ? node.get("lastExecutedAt").getLong() : 0L;
        gs.stagnationCount = node.getOrNull("stagnationCount") != null
                ? node.get("stagnationCount").getInt() : 0;
        gs.consecutiveErrors = node.getOrNull("consecutiveErrors") != null
                ? node.get("consecutiveErrors").getInt() : 0;
        return gs;
    }

    // ===== 会话快照投影（FlowContext.data() 中以 Map 存放，存储实现无关） =====

    /** 转为会话快照投影（键名与前端的 goal.* 消费字段对齐）。 */
    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("goalId", id);
        map.put("objective", condition);
        map.put("condition", condition);
        map.put("status", status.name());
        map.put("consumedTokens", consumedTokens);
        map.put("maxTokens", maxTokens);
        map.put("maxIterations", maxIterations);
        map.put("startEpochMs", startEpochMs);
        map.put("maxDurationMs", maxDurationMs);
        map.put("currentIteration", currentIteration);
        if (lastResult != null) map.put("lastResult", lastResult);
        if (lastExecutedAt > 0) map.put("lastExecutedAt", lastExecutedAt);
        if (stagnationCount > 0) map.put("stagnationCount", stagnationCount);
        if (consecutiveErrors > 0) map.put("consecutiveErrors", consecutiveErrors);
        return map;
    }

    /** 从会话快照投影重建。 */
    public static GoalState fromMap(Map<?, ?> map) {
        if (map == null) return null;
        Object statusValue = map.get("status");
        if (statusValue == null) return null;

        GoalState gs = new GoalState();
        gs.id = str(map.get("goalId"));
        gs.condition = map.get("condition") != null ? str(map.get("condition")) : str(map.get("objective"));
        gs.status = Status.valueOf(str(statusValue));
        gs.consumedTokens = lng(map.get("consumedTokens"));
        gs.maxTokens = lng(map.get("maxTokens"));
        gs.maxIterations = (int) lng(map.get("maxIterations"));
        gs.startEpochMs = lng(map.get("startEpochMs"));
        gs.maxDurationMs = lng(map.get("maxDurationMs"));
        gs.currentIteration = (int) lng(map.get("currentIteration"));
        gs.lastResult = map.get("lastResult") != null ? str(map.get("lastResult")) : null;
        gs.lastExecutedAt = lng(map.get("lastExecutedAt"));
        gs.stagnationCount = (int) lng(map.get("stagnationCount"));
        gs.consecutiveErrors = (int) lng(map.get("consecutiveErrors"));
        return gs;
    }

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static long lng(Object value) {
        return value instanceof Number ? ((Number) value).longValue() : 0L;
    }
}
