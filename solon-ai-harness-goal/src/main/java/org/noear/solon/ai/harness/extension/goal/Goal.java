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

import org.noear.solon.ai.harness.util.HarnessIds;
import org.noear.solon.ai.i18n.AiI18n;
import org.noear.solon.lang.Nullable;
import org.noear.snack4.ONode;

import java.util.Locale;
import java.util.Map;

/**
 * Goal 本体（包内可变实体；对外只暴露只读快照 {@link GoalSnapshot}）。
 *
 * <p>命名：插件名 + 插件里装的那个东西（queue 装 {@code QueueItem}、loop 装 {@code LoopTask}）；
 * goal 每会话只有一个目标，这个东西就是目标本身，故名 {@code Goal}。同包其余三个各管一件事：
 * {@link GoalStatus} 状态取值、{@link GoalSnapshot} 只读副本、{@link GoalProjection} 落盘投影。</p>
 *
 * <p>状态机见 {@link GoalSnapshot}；BLOCKED 是模型主动声明的阻塞（{@code goal_update("blocked")}）
 * 或错误熔断，可与 PAUSED 区分。所有修改都在 {@link GoalRuntime} 的会话锁内完成并落盘。</p>
 *
 * @author noear
 * @since 3.9.2
 */
class Goal {

    private String id;
    private volatile String objective;
    private volatile GoalStatus status;
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
    /**
     * 最近一轮是否有工具调用证据（瞬态，不参与快照投影）。
     *
     * <p>由 {@code GoalRuntime.executeRound} 从上一轮返回结果直接赋值，不经会话 attrs 旁路。
     * null = 无信息（重启 hydrate 后或该轮未产生结果）。</p>
     */
    private volatile Boolean lastHasToolCalls;
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
    /**
     * 目标的语言（创建时从用户输入记下，随快照持久化；null = 跟随引擎实例的语言）。
     *
     * <p>轮次由后台调度发起、不带输入语言，提示词与这一轮的智能体语言只能从这里取。</p>
     */
    private volatile Locale locale;

    /** 预算提示阈值（实例级，避免多工作区互相覆盖）。 */
    private BudgetThresholds thresholds = BudgetThresholds.defaults();

    Goal(String objective, long maxTokens) {
        this(objective, maxTokens, BudgetThresholds.defaults());
    }

    Goal(String objective, long maxTokens, BudgetThresholds thresholds) {
        this.id = HarnessIds.newId(); // 完整 128 bit：截断 ID 落共享存储会撞号
        this.objective = objective;
        this.status = GoalStatus.PURSUING;
        this.consumedTokens = 0;
        this.maxTokens = maxTokens;
        this.startEpochMs = System.currentTimeMillis();
        if (thresholds != null) {
            this.thresholds = thresholds;
        }
    }

    private Goal() {} // for deserialization

    /** 注入预算阈值（由 GoalRuntime 从本工作区配置注入）。 */
    public void setThresholds(BudgetThresholds thresholds) {
        if (thresholds != null) {
            this.thresholds = thresholds;
        }
    }

    public BudgetThresholds getThresholds() {
        return thresholds;
    }

    public boolean pause() {
        if (status != GoalStatus.PURSUING) return false;
        this.status = GoalStatus.PAUSED;
        return true;
    }

    public boolean resume() {
        if (!status.isResumable()) return false;
        this.status = GoalStatus.PURSUING;
        return true;
    }

    public void achieve() {
        if (status == GoalStatus.PURSUING) this.status = GoalStatus.ACHIEVED;
    }

    /** 完成声明未通过运行时证据校验时，退回继续执行。 */
    public boolean rejectAchievement() {
        if (status != GoalStatus.ACHIEVED) return false;
        this.status = GoalStatus.PURSUING;
        return true;
    }

    public void markBudgetLimited() {
        if (status == GoalStatus.PURSUING) this.status = GoalStatus.BUDGET_LIMITED;
    }

    public void markIterationLimited() {
        if (status == GoalStatus.PURSUING) this.status = GoalStatus.ITERATION_LIMITED;
    }

    /** 模型主动声明阻塞（PURSUING → BLOCKED） */
    public void markBlocked() {
        if (status == GoalStatus.PURSUING) {
            this.status = GoalStatus.BLOCKED;
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
        if (status == GoalStatus.BUDGET_LIMITED) {
            this.status = GoalStatus.PURSUING;
        }
    }

    // ===== Getters =====

    public String getId() { return id; }
    public String getObjective() { return objective; }
    public void setObjective(String objective) { this.objective = objective; }
    public GoalStatus getStatus() { return status; }
    public void setStatus(GoalStatus status) { this.status = status; }
    public long getConsumedTokens() { return consumedTokens; }
    public long getMaxTokens() { return maxTokens; }
    public void setMaxTokens(long maxTokens) { this.maxTokens = maxTokens; }
    public int getMaxIterations() { return maxIterations; }
    public void setMaxIterations(int maxIterations) { this.maxIterations = Math.max(0, maxIterations); }
    public long getStartEpochMs() { return startEpochMs; }
    public @Nullable Locale getLocale() { return locale; }
    public void setLocale(@Nullable Locale locale) { this.locale = locale; }


    // ===== Goal 实体运行时字段访问 =====

    public int getCurrentIteration() { return currentIteration; }
    public void setCurrentIteration(int currentIteration) { this.currentIteration = currentIteration; }

    public int incrementIteration() { return ++currentIteration; }

    public long getMaxDurationMs() { return maxDurationMs; }
    public void setMaxDurationMs(long maxDurationMs) { this.maxDurationMs = maxDurationMs; }

    public String getLastResult() { return lastResult; }

    public Boolean getLastHasToolCalls() { return lastHasToolCalls; }
    public void setLastHasToolCalls(Boolean lastHasToolCalls) { this.lastHasToolCalls = lastHasToolCalls; }

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
        node.set("condition", objective);
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
        if (locale != null) node.set("locale", locale.toLanguageTag());
        return node;
    }

    /**
     * 从 ONode 重建
     * <p>
     * node 为 null 或缺少 status 时返回 null（与 {@link #fromMap(Map)} 对齐），
     * 调用方必须判空；旧版本此处对缺失 status 会抛 NPE。
     */
    public static Goal fromONode(ONode node) {
        if (node == null || node.getOrNull("status") == null) {
            return null;
        }

        Goal gs = new Goal();
        gs.id = node.get("id").getString();
        gs.objective = node.get("condition").getString();
        gs.status = GoalStatus.valueOf(node.get("status").getString());
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
        gs.locale = node.getOrNull("locale") != null
                ? AiI18n.toLocale(node.get("locale").getString()) : null;
        return gs;
    }

    // ===== 会话快照投影（FlowContext.data() 中以 Map 存放，存储实现无关） =====

    /** 转为投影 Map（键名即持久化契约，字段名唯一出处见 {@link GoalProjection}）。 */
    public Map<String, Object> toMap() {
        return GoalProjection.ofGoal(this).toMap();
    }

    /** 从会话快照投影重建（字段名解析委托 {@link GoalProjection}，与写入侧同源）。 */
    public static Goal fromMap(Map<?, ?> map) {
        GoalProjection projection = GoalProjection.from((Object) map);
        if (projection == null) return null;

        Goal gs = new Goal();
        gs.id = projection.getGoalId();
        gs.objective = projection.getObjective();
        gs.status = projection.getStatus();
        gs.consumedTokens = projection.getConsumedTokens();
        gs.maxTokens = projection.getMaxTokens();
        gs.maxIterations = projection.getMaxIterations();
        gs.startEpochMs = projection.getStartEpochMs();
        gs.maxDurationMs = projection.getMaxDurationMs();
        gs.currentIteration = projection.getCurrentIteration();
        gs.lastResult = projection.getLastResult();
        gs.lastExecutedAt = projection.getLastExecutedAt();
        gs.stagnationCount = projection.getStagnationCount();
        gs.consecutiveErrors = projection.getConsecutiveErrors();
        gs.locale = projection.getLocale();
        return gs;
    }
}
