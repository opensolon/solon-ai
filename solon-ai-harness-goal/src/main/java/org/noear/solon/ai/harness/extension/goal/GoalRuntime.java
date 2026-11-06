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

import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.talents.todo.TodoTalent;
import org.noear.solon.ai.harness.extension.goal.validate.BuildPassValidator;
import org.noear.solon.ai.harness.extension.goal.validate.ConditionMatcher;
import org.noear.solon.ai.harness.extension.goal.validate.GoalValidator;
import org.noear.solon.ai.harness.extension.goal.validate.NoopValidator;
import org.noear.solon.ai.harness.extension.goal.validate.TestsPassValidator;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.ai.harness.util.ErrorClassifier;
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
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

/**
 * Goal 引擎 —— 会话级「有终止判据的持续执行」能力。
 *
 * <p><b>归属</b>：Goal 属于会话，且每个会话最多一个。状态以 Map 投影存放于
 * {@link AgentSession#getContext()} 的 {@code data()}（key：{@value GoalAttrs#GOAL}），
 * 任何变更后调用 {@link AgentSession#updateSnapshot()} 随会话快照一并落盘。
 * 运行态实例存放于 {@code attrs()}（瞬态，不落盘），首次访问时从投影 hydrate。
 * 这与排队（QueueStore）的既有分层完全一致：<b>存储介质是会话实现的细节</b>
 * （文件 / 内存 / Redis / JDBC），本类只依赖 {@link AgentSession} 接口。</p>
 *
 * <p><b>执行通道自持</b>：本类不依赖任何调度体系的具体类型，也不与 loop 共享任何执行通道。
 * 「一轮怎么执行」由宿主通过 {@link #addExecutor(String, Predicate, GoalExecutor)} 按通道注册，
 * 缺省走引擎自身的同步回合。「能不能执行」由引擎空闲判定单向派生（{@code HarnessEngine#isIdle}），
 * 宿主不得提供忙判定谓词——那会让引擎职责外推且必然与真实状态漂移。</p>
 *
 * @author noear
 * @since 3.9.4
 */
class GoalRuntime {
    private static final Logger LOG = LoggerFactory.getLogger(GoalRuntime.class);

    /** 持久化投影 key（见 {@link GoalAttrs#GOAL}）。 */
    private static final String KEY_GOAL = GoalAttrs.GOAL;
    /** 运行态实例的 attrs key（见 {@link GoalAttrs#GOAL_STATE}）。 */
    private static final String ATTR_GOAL = GoalAttrs.GOAL_STATE;
    /** 运行态防重入锁的 attrs key（见 {@link GoalAttrs#GOAL_RUNNING}）。 */
    private static final String ATTR_RUNNING = GoalAttrs.GOAL_RUNNING;

    private final HarnessEngine engine;
    /** 宿主注册的执行者（按注册顺序取首个认领者；通道化注册携带通道名与认领谓词）。 */
    private final List<TaskClaim> executors = new CopyOnWriteArrayList<>();
    /**
     * 内置兜底执行者（程序生成的 prompt 同步回合）。
     *
     * <p>单独存放而不入 {@link #executors} 列表，是因为列表是「按顺序取首个认领者」——
     * 兜底恒认领，若排在首位则宿主注册的执行者永远轮不到（装配顺序上扩展先于入口构造，
     * 靠「宿主后注册」是不成立的外部不变量）。现在兜底只能是最后一个被考虑的。</p>
     */
    private volatile GoalExecutor defaultExecutor;
    private final List<GoalListener> listeners = new CopyOnWriteArrayList<>();
    private final BudgetThresholds budgetThresholds;
    private final GoalPromptBuilder promptBuilder;
    private final int stagnationThreshold;
    private final int maxConsecutiveErrors;
    private final boolean validatorEnabled;
    /**
     * 验证器注册表（实例级；执行目录不在注册时绑定，由 goal_update 按本轮会话工作目录传入）。
     *
     * <p>曾为全局静态（ValidatorFactory），而每个工作区都会注册一次默认验证器，
     * 导致注册项跨工作区累加：在 A 工作区跑 goal 会连 B 工作区的 mvn test 一起跑
     * 并按 AND 语义阻断完成。改为实例持有后，注册项不再跨引擎累加。</p>
     */
    private final List<ValidatorRegistration> validators = new CopyOnWriteArrayList<>();

    public GoalRuntime(HarnessEngine engine, BudgetThresholds budgetThresholds,
                      int stagnationThreshold, int maxConsecutiveErrors, boolean validatorEnabled) {
        this(engine, budgetThresholds, stagnationThreshold, maxConsecutiveErrors, validatorEnabled, false);
    }

    /**
     * @param engine            引擎（解析会话、读取会话占用状态；测试可为 null）
     * @param defaultValidators 是否注册内置验证器（测试/构建）；false 时无自定义注册则验证恒通过
     */
    public GoalRuntime(HarnessEngine engine, BudgetThresholds budgetThresholds,
                      int stagnationThreshold, int maxConsecutiveErrors, boolean validatorEnabled,
                      boolean defaultValidators) {
        this.engine = engine;
        this.budgetThresholds = budgetThresholds != null ? budgetThresholds : BudgetThresholds.defaults();
        this.stagnationThreshold = stagnationThreshold;
        this.maxConsecutiveErrors = maxConsecutiveErrors;
        this.validatorEnabled = validatorEnabled;
        this.promptBuilder = new GoalPromptBuilder(stagnationThreshold);

        if (defaultValidators) {
            registerDefaultValidators();
        }
    }

    /** 注册生命周期观察者（桌面端流式回推用）。 */
    public void addListener(GoalListener listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    private void notifyChanged(AgentSession session, GoalState gs, boolean removed) {
        if (gs == null || listeners.isEmpty()) return;
        String sessionId = session.getSessionId();
        GoalTask snapshot = GoalTask.of(gs, removed == false && isRunning(session));
        for (GoalListener listener : listeners) {
            try {
                listener.onChanged(sessionId, snapshot, removed);
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

    /**
     * 等待正在执行的那一轮安全结束（不轮询，靠 {@code attrs()} 上的 notify）。
     *
     * <p>锁对象就是 {@code attrs()} 本身，与 {@link #runRound} 的 CAS 段、finally 清除段
     * 完全同一把锁，因此不存在「漏唤醒」：唯一清除 {@code ATTR_RUNNING} 的地方
     * 在 {@code runRound} 的 finally 内，且会 {@code notifyAll}。</p>
     *
     * <p>语义是「给宿主一个不忙等的停等」，<b>不负责中断</b>——中断依赖宿主的运行时通道，
     * 不属于 goal 域职责。</p>
     *
     * @param timeoutMs 最长等待毫秒（≤0 表示只做一次即时判定、不等待）
     * @return true=当前无轮次在跑；false=超时仍未停稳或被中断
     */
    public boolean awaitRoundStopped(AgentSession session, long timeoutMs) {
        if (session == null) {
            return true;
        }
        long deadline = System.currentTimeMillis() + Math.max(0L, timeoutMs);
        synchronized (session.attrs()) {
            while (Boolean.TRUE.equals(session.attrs().get(ATTR_RUNNING))) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) {
                    return false;
                }
                try {
                    session.attrs().wait(remaining);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * 注册通道化执行器（按注册顺序取首个认领者，先于内置兜底）。
     *
     * <p>归属只由认领谓词决定：命中的首个执行器即本轮执行者，其结论就是最终结论（不穿透）。
     * 引擎可在不执行 handler 的前提下评估各通道认领边界是否互斥，重叠时告警（每个组合一次）。</p>
     *
     * @param channel 通道名（CLI/Web/桌面等；用于日志与互斥告警，不参与路由）
     * @param claim   认领谓词（sessionId → 是否本通道的会话）；null = 认领全部（不参与互斥评估）。谓词抛异常按不命中计
     * @param handler 执行者
     */
    public void addExecutor(String channel, Predicate<String> claim, GoalExecutor handler) {
        if (handler == null) {
            return;
        }
        if (channel == null || channel.trim().isEmpty()) {
            throw new IllegalArgumentException("channel must not be empty");
        }
        executors.add(new TaskClaim(channel.trim(), claim, handler));
    }

    /** 执行者注册项（谓词可空 = 认领全部）。 */
    private static final class TaskClaim {
        final String channel;
        final Predicate<String> claim;
        final GoalExecutor handler;

        TaskClaim(String channel, Predicate<String> claim, GoalExecutor handler) {
            this.channel = channel;
            this.claim = claim;
            this.handler = handler;
        }
    }

    /** 互斥告警的防重放键（按「命中通道组合」去重：配置错误是静态的，同组合只警一次）。 */
    private final Set<String> overlapWarnKeys = ConcurrentHashMap.newKeySet();
    private final AtomicInteger overlapWarnCount = new AtomicInteger();

    /**
     * 设置内置兜底执行者（只在前面的执行者全不认领时考虑）。
     *
     * <p>与 {@link #addExecutor} 分开，才能保证「宿主注册的总是优先」不依赖装配顺序。</p>
     */
    public void setDefaultExecutor(GoalExecutor handler) {
        this.defaultExecutor = handler;
    }

    /** 是否已有可用执行者（宿主注册或内置兜底任一）。 */
    public boolean hasExecutor() {
        return !executors.isEmpty() || defaultExecutor != null;
    }

    /** 按 sessionId 解析会话（引擎未就绪或解析失败时返回 null）。 */
    public AgentSession sessionOf(String sessionId) {
        if (engine == null || sessionId == null) return null;
        try {
            return engine.getSession(sessionId);
        } catch (Throwable e) {
            LOG.debug("resolve session '{}' failed: {}", sessionId, e.getMessage());
            return null;
        }
    }

    /**
     * 取已存在的会话（不存在返回 null，不新建）。调度轮次用：会话被删后不得把它重建出来。
     *
     * <p>与 {@link #sessionOf} 不同，解析异常会向外抛出——调用方要能区分「确实不存在」与「暂时取不到」。</p>
     */
    AgentSession sessionOrNil(String sessionId) {
        if (engine == null || sessionId == null) return null;
        return engine.getSessionOrNil(sessionId);
    }

    /**
     * 执行一轮：按注册顺序取首个认领者（无人认领时用内置兜底）；仍无执行者时返回 null。
     *
     * <p>不含任何业务判定（预算、熔断、完成条件都在调用方），只做「投递 + 取回结论」。
     * 执行者返回 null 视为编程错误，按失败处理；异常原样抛给调用方（参与错误分级熔断）。</p>
     */
    GoalOutcome execute(String sessionId, String prompt) throws Exception {
        // 谓词只评估一次，结果供「互斥检测」与「认领路由」复用：宿主谓词常是正则匹配
        // （如 isDesktop = matches("\\d{1,18}")），两次遍历等于双倍编译开销。
        TaskClaim[] claims = executors.toArray(new TaskClaim[0]);
        boolean[] claimed = new boolean[claims.length];
        java.util.List<String> matchedChannels = new java.util.ArrayList<>(2);

        for (int i = 0; i < claims.length; i++) {
            TaskClaim taskClaim = claims[i];
            if (taskClaim.claim == null) {
                claimed[i] = true;          // 无谓词：认领全部
                continue;
            }
            try {
                if (taskClaim.claim.test(sessionId)) {
                    claimed[i] = true;
                    if (taskClaim.channel != null) {
                        matchedChannels.add(taskClaim.channel);
                    }
                }
            } catch (Throwable e) {
                LOG.debug("Goal claim predicate failed for channel '{}': {}", taskClaim.channel, e.getMessage());
            }
        }

        // 静态评估互斥性（仅通道化注册可评估）：多个谓词命中同一会话意味着宿主的
        // 认领边界重叠，先注册者会静默抢跑——此前只有一条无上下文的 debug 行。
        warnIfClaimsOverlap(sessionId, matchedChannels);

        GoalExecutor chosen = null;
        for (int i = 0; i < claims.length; i++) {
            if (claimed[i]) {
                LOG.debug("Goal round for session '{}' claimed by channel '{}'", sessionId, claims[i].channel);
                chosen = claims[i].handler;
                break;
            }
        }
        if (chosen == null) {
            chosen = defaultExecutor;
        }
        if (chosen != null) {
            GoalOutcome outcome = chosen.handle(sessionId, prompt);
            return outcome != null ? outcome : GoalOutcome.failed("goal executor returned null");
        }

        // 落到这里意味着配置错误：既无任何宿主执行器认领，也无内置兜底。
        // 不得静默——否则表现为「目标一直在跑却什么都不做」。
        LOG.debug("Goal round not claimed by any executor (session={}); no default executor configured", sessionId);
        return null;
    }

    /**
     * 谓词重叠告警：同一「命中通道组合」只警一次。
     *
     * <p>键集上限 64：超过后**新组合不再告警**（也不计数），只保留已记录组合的防重放效力——
     * 这是为了在病态注册（谓词组合爆炸）时不撑爆内存，代价是极端场景下会漏报。</p>
     */
    private void warnIfClaimsOverlap(String sessionId, java.util.List<String> matched) {
        if (matched.size() < 2) {
            return;
        }
        String key = String.join("+", matched);
        if (overlapWarnKeys.add(key) == false) {
            return;
        }
        if (overlapWarnKeys.size() > 64) {
            overlapWarnKeys.remove(key);
            return;
        }
        LOG.warn("Goal round for session '{}' matched {} channel executors [{}]; "
                + "taking first by registration order - host claim predicates are NOT mutually exclusive",
                sessionId, matched.size(), String.join(",", matched));
        overlapWarnCount.incrementAndGet();
    }

    /**
     * 命中会话的通道列表（旧形态注册不含在内；谓词异常按不命中计）。
     *
     * <p>测试观测口：{@link #execute} 走自己的单次评估路径，不复用本方法
     * （避免为跑一轮多遍历一次谓词）。两者判定口径必须保持一致。</p>
     */
    java.util.List<String> channelsClaiming(String sessionId) {
        java.util.List<String> matched = new java.util.ArrayList<>(2);
        for (TaskClaim taskClaim : executors) {
            if (taskClaim.claim == null || taskClaim.channel == null) {
                continue;
            }
            try {
                if (taskClaim.claim.test(sessionId)) {
                    matched.add(taskClaim.channel);
                }
            } catch (Throwable ignored) {
                // 谓词异常已由 execute 路径单独记录，这里只做评估
            }
        }
        return matched;
    }

    /** 已发出的互斥告警条数（测试观测口）。 */
    int overlapWarnCount() {
        return overlapWarnCount.get();
    }

    /**
     * 会话是否应跳过本轮注入。
     *
     * <p>口径为引擎的 {@code isIdle}（占用或有积压即不空闲）：HITL 挂起时没有活跃流，
     * 但此时注入会顶掉未决审批；积压压着任务时注入等于插队。goal 不认识任何积压插件。</p>
     */
    boolean shouldSkipSession(String sessionId) {
        if (engine == null) return false;
        try {
            AgentSession session = engine.getSessionOrNil(sessionId);
            if (session == null) return true;
            return engine.isIdle(session) == false;
        } catch (Throwable e) {
            LOG.debug("status check failed for session '{}': {}", sessionId, e.getMessage());
            return true;
        }
    }

    // ==================== 验证器（实例级注册表） ====================

    private static final class ValidatorRegistration {
        final ConditionMatcher matcher;
        final GoalValidator validator;

        ValidatorRegistration(ConditionMatcher matcher, GoalValidator validator) {
            this.matcher = matcher;
            this.validator = validator;
        }
    }

    /** 注册默认验证器（执行目录在验证时传入）。 */
    private void registerDefaultValidators() {
        registerValidator(
                condition -> condition.contains("test")
                        || condition.contains("pass")
                        || condition.contains("check")
                        || condition.contains("ensure")
                        || condition.contains("verify"),
                new TestsPassValidator());

        registerValidator(
                condition -> condition.contains("build")
                        || condition.contains("compile")
                        || condition.contains("implement")
                        || condition.contains("refactor")
                        || condition.contains("fix")
                        || condition.contains("create")
                        || condition.contains("add")
                        || condition.contains("write"),
                new BuildPassValidator());
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
        public ValidationResult validate(String condition, String workDir) {
            for (GoalValidator v : validators) {
                ValidationResult vr = v.validate(condition, workDir);
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
        return attach(session, objective, null, maxTokens, maxDurationMs, maxIterations);
    }

    /** 按规格创建（含展示正文）。 */
    GoalState attach(AgentSession session, GoalSpec spec) {
        return attach(session, spec.getObjective(), spec.getDisplayObjective(),
                spec.getMaxTokens(), spec.getMaxDurationMs(), spec.getMaxIterations());
    }

    /**
     * 创建 goal，并允许「执行正文」与「展示正文」不同。
     *
     * <p>典型场景：宿主把附件正文拼进执行正文（沙箱内可读的完整上下文），
     * 但 UI 列表与徽章应只展示用户输入的原句。此前宿主先 {@code attach(执行正文)}
     * 再 {@code setObjective(展示正文) + commit}，两次落盘之间会广播一次中间态
     * （列表短暂出现附件全文）。本方法在同一个临界区内完成两步并只广播一次。</p>
     *
     * @param displayObjective 展示正文；null 或与 objective 相同时不覆写
     * @return 新建的 GoalState
     * @throws IllegalStateException 已有活跃 goal
     */
    public GoalState attach(AgentSession session, String objective, String displayObjective,
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

            String displayed = resolveDisplayObjective(objective, displayObjective);
            if (displayed != null) {
                gs.setObjective(displayed);
            }

            session.attrs().put(ATTR_GOAL, gs);
            saveLocked(session, gs);
            notifyChanged(session, gs, false);
            return gs;
        }
    }

    /** 展示正文归一：null/空/与执行正文相同时返回 null（表示无需覆写）。 */
    private static String resolveDisplayObjective(String objective, String displayObjective) {
        if (displayObjective == null || displayObjective.trim().isEmpty()) {
            return null;
        }
        String displayed = displayObjective.trim();
        return displayed.equals(objective) ? null : displayed;
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
        notifyChanged(session, gs, false);
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
            gs.setObjective(objective);
            gs.setMaxTokens(maxTokens);
            gs.setMaxIterations(maxIterations);
            session.attrs().put(ATTR_GOAL, gs);
            saveLocked(session, gs);
        }
        notifyChanged(session, gs, false);
    }

    /** 按补丁改写（null 字段保持原值）；一次落盘、一次广播。调用方应先停稳在跑轮次。 */
    void apply(AgentSession session, GoalState gs, GoalPatch patch) {
        synchronized (session.attrs()) {
            if (patch.getObjective() != null) gs.setObjective(patch.getObjective());
            if (patch.getMaxTokens() != null) gs.setMaxTokens(patch.getMaxTokens());
            if (patch.getMaxDurationMs() != null) gs.setMaxDurationMs(patch.getMaxDurationMs());
            if (patch.getMaxIterations() != null) gs.setMaxIterations(patch.getMaxIterations());
            session.attrs().put(ATTR_GOAL, gs);
            saveLocked(session, gs);
        }
        notifyChanged(session, gs, false);
    }

    /** 只读快照（无 goal 时为 null）。 */
    GoalTask snapshot(AgentSession session) {
        return GoalTask.of(state(session), isRunning(session));
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
            notifyChanged(session, removed, true);
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
        if (!hasExecutor()) {
            // 无执行者 = 装配错误（GoalExtension 恒会装入缺省执行者），不得计入迭代
            LOG.debug("Goal round skipped: no task executor registered");
            return false;
        }
        if (shouldSkipSession(sessionId)) {
            LOG.debug("Goal round skipped: session '{}' occupied or backlogged", sessionId);
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
                // 唤醒 awaitRoundStopped 的等待者（锁对象与等待者一致，且此处是唯一移除点）
                session.attrs().notifyAll();
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
                if (gs.getStatus() != GoalTask.Status.ACHIEVED) {
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
            if (gs.getStatus() != GoalTask.Status.ACHIEVED) {
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
    private boolean executeRound(AgentSession session, GoalState gs) throws Exception {
        String sessionId = session.getSessionId();
        String effectivePrompt = promptBuilder.buildEffectivePrompt(gs);

        // 必须先置空：本轮证据要到 execute 返回才知道，而 goal_update(complete) 是在
        // execute 内部（工具调用阶段）读取的。若不置空，它会读到上一轮的值——上轮有
        // 工具调用、本轮没有时会被误放行，本轮白跑一轮后又被复核拒绝。
        // 置空后本轮读取恒为 null（提示性校验放行），权威判定交给下方结果复核。
        gs.setLastHasToolCalls(null);

        GoalOutcome result = execute(sessionId, effectivePrompt);

        if (result != null && result.isSkipped()) {
            // 通道认领但此刻不宜执行：不计错误、不推进、不改状态，等下一次调度
            LOG.debug("Goal '{}' round skipped by executor (session '{}'): {}", gs.getId(), sessionId, result.getText());
            return gs.getStatus().isActive();
        }
        if (result != null && result.isFailed()) {
            throw new IllegalStateException(result.getText() != null ? result.getText() : "goal round failed");
        }

        if (result == null) {
            // 无执行器认领 = 装配/环境问题，不是「本轮成功但无产出」。
            // 必须不记 ok、不清零连续错误计数：否则目标看起来一直在跑、实际什么都不做，
            // 且异常分级熔断被这轮流重置，永远不触发。
            // 与 loop 侧「无认领不记 ok」同口径。
            String unclaimed = "error [no-executor]: 本轮未被执行器认领，目标无法推进。";
            if (unclaimed.equals(gs.getLastResult()) == false) {
                LOG.warn("Goal '{}' round not claimed by any executor (session '{}')", gs.getId(), sessionId);
            }
            gs.updateLastExecution(unclaimed);
            commit(session, gs);
            return gs.getStatus().isActive();
        }

        String finalResult = result.getText();
        gs.updateLastExecution(finalResult != null && finalResult.isEmpty() == false ? finalResult : "ok");
        gs.setLastHasToolCalls(result.hasToolCalls());
        gs.resetConsecutiveErrors();
        gs.incrementIteration();

        // Token 累计
        if (result.getTokensUsed() > 0) {
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
        boolean achieved = gs.getStatus() == GoalTask.Status.ACHIEVED;
        if (achieved && requiresActionEvidence(gs.getObjective())
                && !result.hasToolCalls()) {
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
            if (gs.getStatus() != GoalTask.Status.ACHIEVED) {
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
            execute(session.getSessionId(), wrapUpPrompt);
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

    /**
     * 最近一轮已完成的工具证据标记。
     *
     * <p>取值来自上一轮 {@link #execute} 的返回值，不经会话 attrs 旁路——旁路方案下
     * CLI/Web 因未写键而恒为 null，等于完成校验静默失效。</p>
     *
     * <p><b>时序语义</b>：本值只在轮次<b>结束后</b>写入。因此在工具执行阶段（如
     * {@code goal_update(complete)} 内部）读到的是 null——{@code executeRound} 已在
     * 调用 {@code execute} 前显式置空，确保不会拿到上一轮的陈旧值。也就是说该方法
     * 在轮内仅作「提示性」判断，权威判定是 {@code executeRound} 对返回结果的复核。</p>
     */
    public Boolean currentRoundHasActionEvidence(AgentSession session) {
        GoalState gs = state(session);
        return gs == null ? null : gs.getLastHasToolCalls();
    }

    // ==================== 判定辅助 ====================

    /** 计算执行指纹（用于无进展检测）。 */
    static String computeFingerprint(GoalOutcome result) {
        if (result == null) return "null";
        String text = result.getText();
        if (text == null || text.isEmpty()) return "empty";
        String toolDim = result.hasToolCalls() ? "1" : "0";
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

    /**
     * 统计会话 TODO 清单中未完成项（供完成判定联动）。
     *
     * @param cwd 本轮会话工作目录（与 todowrite 收到的 {@code __cwd} 同口径）
     */
    public int countUnfinishedTodos(TodoTalent todoTalent, String cwd, String sessionId) {
        if (todoTalent == null || sessionId == null) {
            return 0;
        }
        try {
            // 经 TodoTalent 的存储口径读取（数据来源可能是 db / http，不能假设本地路径）
            String content = todoTalent.readTodo(cwd, sessionId);
            return content == null ? 0 : countUnfinishedCheckboxes(content);
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
            return GoalTask.Status.valueOf(statusName).isTerminal();
        } catch (Throwable e) {
            return false;
        }
    }

    /** 投影状态名是否活跃（PURSUING；损坏/未知值按非活跃处理，恢复时不动）。 */
    public static boolean isActiveStatus(String statusName) {
        try {
            return GoalTask.Status.valueOf(statusName).isActive();
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
     * @since 4.2.0
     */
    public static GoalProjection projectionOf(FlowContext context) {
        return GoalProjection.from(context);
    }
}
