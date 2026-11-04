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

import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.talents.cli.TodoTalent;
import org.noear.solon.ai.harness.session.goal.validate.BuildPassValidator;
import org.noear.solon.ai.harness.session.goal.validate.GoalValidator;
import org.noear.solon.ai.harness.session.goal.validate.NoopValidator;
import org.noear.solon.ai.harness.session.goal.validate.TestsPassValidator;
import org.noear.solon.ai.harness.runtime.ErrorClassifier;
import org.noear.solon.ai.harness.runtime.ExecutionBridge;
import org.noear.solon.ai.harness.runtime.RoundResult;
import org.noear.solon.flow.FlowContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Goal 引擎 —— 会话级「有终止判据的持续执行」能力。
 *
 * <p><b>归属</b>：Goal 属于会话，且每个会话最多一个。状态以 Map 投影存放于
 * {@link AgentSession#getContext()} 的 {@code data()}（key：{@value #KEY_GOAL}），
 * 任何变更后调用 {@link AgentSession#updateSnapshot()} 随会话快照一并落盘。
 * 运行态实例存放于 {@code attrs()}（瞬态，不落盘），首次访问时从投影 hydrate。
 * 这与排队（QueueHelper）的既有分层完全一致：<b>存储介质是会话实现的细节</b>
 * （文件 / 内存 / Redis / JDBC），本类只依赖 {@link AgentSession} 接口。</p>
 *
 * <p><b>独立性</b>：本类不依赖任何调度体系的具体类型。与调度侧唯一的交汇点是
 * {@link ExecutionBridge}（执行一轮 + 繁忙判定），由组合根注入。</p>
 *
 * @author noear
 * @since 3.9.4
 */
public class GoalEngine {
    private static final Logger LOG = LoggerFactory.getLogger(GoalEngine.class);

    /** 会话上下文 data() 中的持久化投影 key（单槽：一条 goal 记录）。 */
    public static final String KEY_GOAL = "session.goal";
    /** 运行态实例的 attrs key（瞬态，不落盘）。 */
    private static final String ATTR_GOAL = "session.goal.state";
    /** 运行态防重入锁的 attrs key。 */
    private static final String ATTR_RUNNING = "session.goal.running";

    /** Goal 生命周期观察者。桌面端用它把后台多轮执行持续推回同一个对话流。 */
    @FunctionalInterface
    public interface GoalListener {
        /**
         * @param sessionId 会话
         * @param state     变更后的状态（removed=true 时仍为清除前的快照）
         * @param removed   是否因清除而移除
         */
        void onChanged(String sessionId, GoalState state, boolean removed);
    }

    private final ExecutionBridge bridge;
    private final List<GoalListener> listeners = new CopyOnWriteArrayList<>();
    private final BudgetThresholds budgetThresholds;
    private final GoalPromptBuilder promptBuilder;
    private final int stagnationThreshold;
    private final int maxConsecutiveErrors;
    private final boolean validatorEnabled;
    /**
     * 验证器注册表（实例级，绑定本引擎所属工作区）。
     *
     * <p>曾为全局静态（ValidatorFactory），而每个工作区都会注册一次默认验证器，
     * 导致注册项跨工作区累加：在 A 工作区跑 goal 会连 B 工作区的 mvn test 一起跑
     * 并按 AND 语义阻断完成。改为实例持有后，注册项与工作区一一对应。</p>
     */
    private final List<ValidatorRegistration> validators = new CopyOnWriteArrayList<>();

    public GoalEngine(ExecutionBridge bridge, BudgetThresholds budgetThresholds,
                      int stagnationThreshold, int maxConsecutiveErrors, boolean validatorEnabled) {
        this(bridge, budgetThresholds, stagnationThreshold, maxConsecutiveErrors, validatorEnabled, null);
    }

    /**
     * @param workspace 验证器执行目录（null 时不注册默认验证器，验证恒通过）
     */
    public GoalEngine(ExecutionBridge bridge, BudgetThresholds budgetThresholds,
                      int stagnationThreshold, int maxConsecutiveErrors, boolean validatorEnabled,
                      String workspace) {
        this.bridge = bridge;
        this.budgetThresholds = budgetThresholds != null ? budgetThresholds : BudgetThresholds.defaults();
        this.stagnationThreshold = stagnationThreshold;
        this.maxConsecutiveErrors = maxConsecutiveErrors;
        this.validatorEnabled = validatorEnabled;
        this.promptBuilder = new GoalPromptBuilder(stagnationThreshold);

        registerDefaultValidators(workspace);
    }

    /** 注册生命周期观察者（桌面端流式回推用）。 */
    public void addListener(GoalListener listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    private void notifyChanged(String sessionId, GoalState gs, boolean removed) {
        if (gs == null || listeners.isEmpty()) return;
        for (GoalListener listener : listeners) {
            try {
                listener.onChanged(sessionId, gs, removed);
            } catch (Throwable e) {
                LOG.debug("Goal listener failed for session '{}': {}", sessionId, e.getMessage());
            }
        }
    }

    /** 当前是否有一轮正在执行（供 UI 展示与编辑前的停等判定）。 */
    public boolean isRunning(AgentSession session) {
        if (session == null) return false;
        return Boolean.TRUE.equals(session.attrs().get(ATTR_RUNNING));
    }

    /** 按 sessionId 解析会话（委派执行桥，不另持 resolver；桥缺失或无会话时返回 null）。 */
    public AgentSession sessionOf(String sessionId) {
        return bridge == null ? null : bridge.sessionOf(sessionId);
    }

    // ==================== 验证器（实例级注册表） ====================

    /** 条件匹配器：返回 true 表示应应用该验证器。 */
    @FunctionalInterface
    public interface ConditionMatcher {
        boolean matches(String conditionLowercase);
    }

    private static final class ValidatorRegistration {
        final ConditionMatcher matcher;
        final GoalValidator validator;

        ValidatorRegistration(ConditionMatcher matcher, GoalValidator validator) {
            this.matcher = matcher;
            this.validator = validator;
        }
    }

    /** 注册默认验证器（按本引擎绑定的工作区执行）。 */
    private void registerDefaultValidators(String workspace) {
        if (workspace == null || workspace.isEmpty()) {
            return;
        }

        registerValidator(
                condition -> condition.contains("test")
                        || condition.contains("pass")
                        || condition.contains("check")
                        || condition.contains("ensure")
                        || condition.contains("verify"),
                new TestsPassValidator(workspace));

        registerValidator(
                condition -> condition.contains("build")
                        || condition.contains("compile")
                        || condition.contains("implement")
                        || condition.contains("refactor")
                        || condition.contains("fix")
                        || condition.contains("create")
                        || condition.contains("add")
                        || condition.contains("write"),
                new BuildPassValidator(workspace));
    }

    /** 注册一个带条件匹配的验证器（多次注册按 AND 语义链式生效）。 */
    public void registerValidator(ConditionMatcher matcher, GoalValidator validator) {
        if (matcher != null && validator != null) {
            validators.add(new ValidatorRegistration(matcher, validator));
        }
    }

    /** 当前已注册验证器数量（多工作区隔离回归用）。 */
    public int validatorCount() {
        return validators.size();
    }

    /**
     * 按目标条件匹配验证器链（AND 语义；无匹配时返回 {@link NoopValidator}）。
     */
    public GoalValidator resolveValidator(String condition) {
        if (condition == null || condition.isEmpty()) {
            return new NoopValidator();
        }

        String lower = condition.toLowerCase(Locale.ROOT);
        List<GoalValidator> matched = new java.util.ArrayList<>();
        for (ValidatorRegistration reg : validators) {
            try {
                if (reg.matcher.matches(lower)) {
                    matched.add(reg.validator);
                }
            } catch (Throwable e) {
                LOG.debug("Validator matcher failed: {}", e.getMessage());
            }
        }

        if (matched.isEmpty()) {
            return new NoopValidator();
        }
        if (matched.size() == 1) {
            return matched.get(0);
        }
        return new ChainValidator(matched);
    }

    /** 链式验证器 — 所有子验证器都通过才算通过。 */
    private static final class ChainValidator implements GoalValidator {
        private final List<GoalValidator> validators;

        ChainValidator(List<GoalValidator> validators) {
            this.validators = validators;
        }

        @Override
        public ValidationResult validate(String condition) {
            for (GoalValidator v : validators) {
                ValidationResult vr = v.validate(condition);
                if (!vr.isPassed()) {
                    return vr;
                }
            }
            return ValidationResult.passed();
        }
    }

    public boolean isValidatorEnabled() {
        return validatorEnabled;
    }



    // ==================== 单槽读写 ====================

    /**
     * 创建/覆盖本会话的 goal。单槽语义：已有活跃或可恢复的 goal 时拒绝。
     *
     * @param objective 目标描述
     * @param maxTokens Token 预算（0 = 不限制）
     * @param maxDurationMs 时间预算毫秒（0 = 不限制）
     * @param maxIterations 轮次预算（0 = 不限制）
     * @return 新建的 GoalState
     * @throws IllegalStateException 已有活跃 goal
     */
    public GoalState attach(AgentSession session, String objective,
                            long maxTokens, long maxDurationMs, int maxIterations) {
        if (session == null) {
            throw new IllegalArgumentException("session is required");
        }
        synchronized (session.attrs()) {
            GoalState existing = stateLocked(session);
            if (existing != null && !existing.getStatus().isTerminal()) {
                throw new IllegalStateException("当前会话已有进行中的 Goal，请先清除后再创建");
            }

            GoalState gs = new GoalState(objective, maxTokens, budgetThresholds);
            gs.setMaxDurationMs(maxDurationMs);
            gs.setMaxIterations(maxIterations);

            session.attrs().put(ATTR_GOAL, gs);
            saveLocked(session, gs);
            notifyChanged(session.getSessionId(), gs, false);
            return gs;
        }
    }

    /** 读取本会话 goal（无则返回 null）。 */
    public GoalState state(AgentSession session) {
        if (session == null) return null;
        synchronized (session.attrs()) {
            return stateLocked(session);
        }
    }

    /** 是否存在活跃或可恢复的 goal。 */
    public boolean hasGoal(AgentSession session) {
        GoalState gs = state(session);
        return gs != null && !gs.getStatus().isTerminal();
    }

    /** 持久化当前状态（状态转移后调用），并广播给观察者。 */
    public void commit(AgentSession session, GoalState gs) {
        if (session == null || gs == null) return;
        synchronized (session.attrs()) {
            session.attrs().put(ATTR_GOAL, gs);
            saveLocked(session, gs);
        }
        notifyChanged(session.getSessionId(), gs, false);
    }

    /**
     * 更新目标与预算（编辑场景）。调用方应先暂停并中断正在执行的旧轮次。
     */
    public void update(AgentSession session, String objective, long maxTokens, int maxIterations) {
        GoalState gs = state(session);
        if (gs == null) {
            throw new IllegalStateException("Goal not found");
        }
        synchronized (session.attrs()) {
            gs.setCondition(objective);
            gs.setMaxTokens(maxTokens);
            gs.setMaxIterations(maxIterations);
            session.attrs().put(ATTR_GOAL, gs);
            saveLocked(session, gs);
        }
        notifyChanged(session.getSessionId(), gs, false);
    }

    /**
     * 构建与 loop 任务同构的展示 Map（供 Web/Desktop 列表消费）。
     *
     * <p>字段名与前端 {@code goal.*} 约定严格对齐：变更键名会直接导致
     * 状态徽章、轮次进度与预算条失效。</p>
     */
    public Map<String, Object> toTaskMap(AgentSession session, GoalState gs) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", gs.getId());
        item.put("type", "GOAL");
        item.put("prompt", gs.getCondition());
        item.put("enabled", true);
        item.put("running", isRunning(session));
        item.put("currentIteration", gs.getCurrentIteration());
        if (gs.getMaxTokens() > 0) item.put("maxTokens", gs.getMaxTokens());
        if (gs.getMaxDurationMs() > 0) item.put("maxDurationMs", gs.getMaxDurationMs());
        if (gs.getLastResult() != null) item.put("lastResult", gs.getLastResult());
        if (gs.getLastExecutedAt() > 0) {
            item.put("lastExecutedAt", Instant.ofEpochMilli(gs.getLastExecutedAt()).toString());
        }

        Map<String, Object> goal = new LinkedHashMap<>();
        goal.put("condition", gs.getCondition());
        goal.put("status", gs.getStatus().name());
        goal.put("iteration", gs.getCurrentIteration());
        goal.put("maxIterations", gs.getMaxIterations());
        goal.put("consumedTokens", gs.getConsumedTokens());
        goal.put("maxTokens", gs.getMaxTokens());
        if (gs.getStartEpochMs() > 0) {
            goal.put("startedAt", Instant.ofEpochMilli(gs.getStartEpochMs()).toString());
        }
        item.put("goal", goal);
        return item;
    }

    /** 清除本会话 goal（移除投影并落盘）。 */
    public void clear(AgentSession session) {
        if (session == null) return;
        synchronized (session.attrs()) {
            GoalState removed = stateLocked(session);
            session.attrs().remove(ATTR_GOAL);
            if (session.getContext() != null) {
                session.getContext().data().remove(KEY_GOAL);
            }
            session.updateSnapshot();
            notifyChanged(session.getSessionId(), removed, true);
        }
    }

    /**
     * 暂停（PURSUING → PAUSED）。仅 PURSUING 可暂停：BLOCKED 是熔断态、
     * 其调度已随熔断停止，无需也不应经 pause 转换（Web 端点已前置同口径校验）。
     */
    public boolean pause(AgentSession session, GoalState gs) {
        if (gs == null) return false;
        boolean changed = gs.pause();
        if (changed) {
            commit(session, gs);
        }
        return changed;
    }

    /** 恢复（PAUSED/BLOCKED → PURSUING）。 */
    public boolean resume(AgentSession session, GoalState gs) {
        if (gs == null) return false;
        boolean changed = gs.resume();
        if (changed) {
            commit(session, gs);
        }
        return changed;
    }

    // ==================== 运行态存取（与快照投影的桥接） ====================

    /** 运行态优先；缺失时从 data() 投影 hydrate（调用方须持 attrs 锁）。 */
    private GoalState stateLocked(AgentSession session) {
        Object value = session.attrs().get(ATTR_GOAL);
        if (value instanceof GoalState) {
            return (GoalState) value;
        }
        GoalState hydrated = hydrate(session);
        if (hydrated != null) {
            session.attrs().put(ATTR_GOAL, hydrated);
        }
        return hydrated;
    }

    /** 从会话上下文投影重建 goal；无投影返回 null。 */
    private GoalState hydrate(AgentSession session) {
        if (session.getContext() == null) return null;
        GoalProjection projection = GoalProjection.from(session.getContext());
        if (projection == null) return null;
        try {
            GoalState gs = GoalState.fromMap(projection.toMap());
            if (gs != null) {
                gs.setThresholds(budgetThresholds);
            }
            return gs;
        } catch (Throwable e) {
            LOG.debug("Goal hydrate failed for session '{}': {}", session.getSessionId(), e.toString());
            return null;
        }
    }

    /** 写投影并落盘（调用方须持 attrs 锁）。 */
    private void saveLocked(AgentSession session, GoalState gs) {
        if (session.getContext() == null) return;
        session.getContext().data().put(KEY_GOAL, gs.toMap());
        session.updateSnapshot();
    }

    // ==================== 执行轮次 ====================

    /**
     * 执行一轮。
     *
     * @return true 表示仍可继续下一轮；false 表示已终止/暂停/预算耗尽
     */
    public boolean runRound(AgentSession session) {
        String sessionId = session.getSessionId();
        GoalState gs = state(session);

        // ① 守卫
        if (gs == null || !gs.getStatus().isActive()) {
            return false;
        }
        if (bridge == null || !bridge.hasTaskHandler()) {
            LOG.debug("Goal round skipped: execution bridge unavailable");
            return false;
        }
        if (bridge.isBusy(sessionId)) {
            LOG.debug("Goal round skipped: session '{}' busy", sessionId);
            return false;
        }

        // ② 轮前预算守卫（时间/Token/轮次）
        if (guardBeforeRound(session, gs)) {
            return false;
        }

        // ③ CAS 防重入
        synchronized (session.attrs()) {
            if (Boolean.TRUE.equals(session.attrs().get(ATTR_RUNNING))) {
                return false;
            }
            session.attrs().put(ATTR_RUNNING, Boolean.TRUE);
        }

        try {
            return executeRound(session, gs);
        } catch (Exception e) {
            handleExecutionError(session, gs, e);
            return false;
        } finally {
            synchronized (session.attrs()) {
                session.attrs().remove(ATTR_RUNNING);
            }
        }
    }

    /** 轮前预算守卫：命中即收尾并终止。 */
    private boolean guardBeforeRound(AgentSession session, GoalState gs) {
        // 时间预算
        if (gs.getMaxDurationMs() > 0) {
            long elapsed = System.currentTimeMillis() - gs.getStartEpochMs();
            if (elapsed >= gs.getMaxDurationMs()) {
                LOG.info("Goal '{}' duration exceeded ({}ms), wrap-up turn", gs.getId(), elapsed);
                executeBudgetWrapUp(session, gs);
                if (gs.getStatus() != GoalState.Status.ACHIEVED) {
                    gs.markBudgetLimited();
                }
                commit(session, gs);
                return true;
            }
        }

        // Token 预算
        if (gs.isBudgetExceeded()) {
            LOG.info("Goal '{}' token budget exceeded, wrap-up turn", gs.getId());
            executeBudgetWrapUp(session, gs);
            if (gs.getStatus() != GoalState.Status.ACHIEVED) {
                gs.markBudgetLimited();
            }
            commit(session, gs);
            return true;
        }

        // 轮次预算
        if (gs.isIterationExceeded(gs.getCurrentIteration())) {
            LOG.info("Goal '{}' iteration limit reached ({} >= {})",
                    gs.getId(), gs.getCurrentIteration(), gs.getMaxIterations());
            gs.markIterationLimited();
            commit(session, gs);
            return true;
        }

        return false;
    }

    /** 执行一轮主体：构建 prompt → 执行 → 状态评估 → 持久化。 */
    private boolean executeRound(AgentSession session, GoalState gs) {
        String sessionId = session.getSessionId();
        String effectivePrompt = promptBuilder.buildEffectivePrompt(gs);

        RoundResult result = bridge.execute(sessionId, effectivePrompt, null);

        String finalResult = result != null ? result.getFinalResult() : null;
        gs.updateLastExecution(finalResult != null ? finalResult : "ok");
        gs.resetConsecutiveErrors();

        if (result != null && result.isCompleted()) {
            gs.incrementIteration();
        }

        // Token 累计
        if (result != null && result.getTokensUsed() > 0) {
            gs.addTokens(result.getTokensUsed());
        }

        // 无进展检测
        String fingerprint = computeFingerprint(result);
        if (fingerprint != null && fingerprint.equals(gs.getLastFingerprint())) {
            gs.recordStagnation();
            LOG.warn("Goal '{}' stagnation: {} consecutive no-progress rounds",
                    gs.getId(), gs.getStagnationCount());
        } else {
            gs.resetStagnation();
            gs.setLastFingerprint(fingerprint);
        }

        // 完成判定（由 goal_update(complete) 驱动，此处只做证据复核）
        boolean achieved = gs.getStatus() == GoalState.Status.ACHIEVED;
        if (achieved && requiresActionEvidence(gs.getCondition())
                && result != null && !result.isHasToolCalls()) {
            gs.rejectAchievement();
            gs.updateLastExecution("完成声明被拒绝：本轮没有实际工具执行证据，请继续完成并验证目标。");
            achieved = false;
            LOG.warn("Goal '{}' completion rejected: no action evidence", gs.getId());
        }

        if (achieved) {
            LOG.info("Goal '{}' ACHIEVED at iteration {}", gs.getId(), gs.getCurrentIteration());
            commit(session, gs);
            return false;
        }

        // 轮后 Token 预算
        if (gs.isBudgetExceeded()) {
            executeBudgetWrapUp(session, gs);
            if (gs.getStatus() != GoalState.Status.ACHIEVED) {
                gs.markBudgetLimited();
            }
            commit(session, gs);
            return false;
        }

        // 轮后轮次预算
        if (gs.isIterationExceeded(gs.getCurrentIteration())) {
            gs.markIterationLimited();
            commit(session, gs);
            return false;
        }

        commit(session, gs);
        return gs.getStatus().isActive();
    }

    /** 预算耗尽收尾：让模型总结进展与剩余工作，不再续行。 */
    private void executeBudgetWrapUp(AgentSession session, GoalState gs) {
        try {
            String wrapUpPrompt = promptBuilder.buildBudgetLimitPrompt(gs);
            bridge.execute(session.getSessionId(), wrapUpPrompt, null);
        } catch (Exception e) {
            LOG.warn("Goal '{}' wrap-up turn failed: {}", gs.getId(), e.getMessage());
        }
    }

    // ==================== 异常分级熔断 ====================

    private void handleExecutionError(AgentSession session, GoalState gs, Exception e) {
        String errorType = ErrorClassifier.classifyError(e);
        String summary = extractErrorSummary(e);
        int sameTypeCount = gs.recordError(errorType, summary);

        LOG.error("Goal '{}' failed [{}]: {}", gs.getId(), errorType, e.getMessage());
        gs.updateLastExecution("error [" + errorType + "]: " + e.getMessage());

        if (!gs.getStatus().isActive() || gs.isBudgetExceeded()) {
            commit(session, gs);
            return;
        }

        int errors = gs.incrementConsecutiveErrors();

        // 不可恢复错误（SSL / HTTP_4XX）连续 2 次直接熔断
        if (isNonRecoverable(errorType) && sameTypeCount >= 2) {
            LOG.warn("Goal '{}' blocked: non-recoverable error [{}] x{}", gs.getId(), errorType, sameTypeCount);
            gs.markBlocked();
            commit(session, gs);
            return;
        }

        // 同类型错误连续 3 次熔断
        if (sameTypeCount >= 3) {
            LOG.warn("Goal '{}' blocked: same error type [{}] x{}", gs.getId(), errorType, sameTypeCount);
            gs.markBlocked();
            commit(session, gs);
            return;
        }

        if (errors >= maxConsecutiveErrors) {
            LOG.warn("Goal '{}' blocked: {} consecutive errors", gs.getId(), errors);
            gs.markBlocked();
            commit(session, gs);
            return;
        }

        // 未达阈值 → 交由 GoalScheduler 的 5s 轮询 job 重试（单驱动源；
        // 若在此处 RunUtil.delay 补发，会与 job 形成双驱动叠加）
        commit(session, gs);
    }

    /** 当前轮次的工具证据标记（null = 当前入口未提供证据追踪）。 */
    public Boolean currentRoundHasActionEvidence(String sessionId) {
        return bridge.currentRoundHasActionEvidence(sessionId);
    }

    // ==================== 判定辅助 ====================

    /** 计算执行指纹（用于无进展检测）。 */
    static String computeFingerprint(RoundResult result) {
        if (result == null) return "null";
        String text = result.getFinalResult();
        if (text == null || text.isEmpty()) return "empty";
        String toolDim = result.isHasToolCalls() ? "1" : "0";
        int lenBucket = text.length() / 200;
        int lineBucket = countLines(text) / 10;
        return toolDim + ":" + lenBucket + ":" + lineBucket;
    }

    static int countLines(String text) {
        int count = 1;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') count++;
        }
        return count;
    }

    /**
     * 执行型目标（创建/修改/运行等）必须有实际工具证据才能声明完成。
     */
    static boolean requiresActionEvidence(String objective) {
        if (objective == null || objective.trim().isEmpty()) {
            return false;
        }
        String normalized = objective.toLowerCase(Locale.ROOT);
        String[] keywords = {
                "create", "generate", "write", "implement", "modify", "edit", "fix",
                "add", "delete", "remove", "refactor", "build", "test", "run", "install",
                "configure", "deploy", "file",
                "生成", "创建", "新建", "编写", "写入", "修改", "编辑", "修复", "实现",
                "添加", "删除", "重构", "构建", "测试", "运行", "安装", "配置", "部署", "文件"
        };
        for (String keyword : keywords) {
            if (normalized.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    /** 统计会话 TODO 清单中未完成项（供完成判定联动）。 */
    public int countUnfinishedTodos(TodoTalent todoTalent, String workspace, String sessionId) {
        if (todoTalent == null || sessionId == null) {
            return 0;
        }
        try {
            Path todoPath = todoTalent.getTodoPath(workspace, sessionId);
            if (!Files.exists(todoPath)) {
                return 0;
            }
            String content = new String(Files.readAllBytes(todoPath), StandardCharsets.UTF_8);
            return countUnfinishedCheckboxes(content);
        } catch (Throwable e) {
            LOG.debug("countUnfinishedTodos failed for session '{}': {}", sessionId, e.toString());
            return 0;
        }
    }

    static int countUnfinishedCheckboxes(String content) {
        if (content == null) return 0;
        int unfinished = 0;
        for (String line : content.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.length() < 5 || !trimmed.startsWith("- [") || trimmed.charAt(4) != ']') {
                continue;
            }
            char mark = Character.toLowerCase(trimmed.charAt(3));
            if (mark == ' ' || mark == '/') {
                unfinished++;
            }
        }
        return unfinished;
    }

    // ==================== 错误分类 ====================

    public static String extractErrorSummary(Exception e) {
        String msg = e.getMessage();
        if (msg == null || msg.isEmpty()) {
            msg = e.getClass().getSimpleName();
        }
        if (msg.length() > 200) {
            msg = msg.substring(0, 200) + "...";
        }
        return msg;
    }

    public static boolean isNonRecoverable(String errorType) {
        return "SSL".equals(errorType) || "HTTP_4XX".equals(errorType);
    }

    // ==================== 启动恢复辅助 ====================

    /** 投影状态名是否终态（损坏/未知值按非终态处理，交由 hydrate 与守卫兜底）。 */
    public static boolean isTerminalStatus(String statusName) {
        try {
            return GoalState.Status.valueOf(statusName).isTerminal();
        } catch (Throwable e) {
            return false;
        }
    }

    /** 投影状态名是否活跃（PURSUING；损坏/未知值按非活跃处理，恢复时不动）。 */
    public static boolean isActiveStatus(String statusName) {
        try {
            return GoalState.Status.valueOf(statusName).isActive();
        } catch (Throwable e) {
            return false;
        }
    }

    /**
     * 快照上下文中的 goal 投影状态（类型化读取，不涉状态名字符串）。
     *
     * <p>供宿主在会话尚未实例化时做启动恢复判定；判定本身用
     * {@link GoalProjection#isActive()}，不再让宿主自己比较字符串。</p>
     *
     * @return null = 无 goal 投影或投影不可用
     * @since 4.2.1
     */
    public static GoalProjection projectionOf(FlowContext context) {
        return GoalProjection.from(context);
    }
}
