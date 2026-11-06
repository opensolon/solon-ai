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
import org.noear.solon.ai.agent.react.ReActOptionsAmend;
import org.noear.solon.ai.agent.react.ReActResponse;
import org.noear.solon.ai.harness.AbsHarnessExtension;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.ai.harness.extension.goal.validate.ConditionMatcher;
import org.noear.solon.ai.harness.extension.goal.validate.GoalValidator;
import org.noear.solon.ai.harness.input.HarnessInput;
import org.noear.solon.ai.harness.session.SessionStatus;
import org.noear.solon.core.util.RunUtil;
import org.noear.solon.scheduling.scheduled.manager.IJobManager;
import org.noear.solon.scheduling.simple.JobManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Goal（会话目标）一键装配扩展 —— goal 能力从头到尾只接触这一个类（与 {@code LoopExtension} 对称）。
 *
 * <pre>{@code
 * GoalExtension goal = engine.addExtensionIfAbsent(GoalExtension.builder(workspaceId) // job 名：goal-<workspaceId>:<sessionId>
 *         .workspace(workspacePath)   // 验证器执行目录；缺省 = workspaceId
 *         .budget(70, 85)             // 预算提示阈值
 *         .build());                  // onAttach 内注册 /goal 命令
 * goal.addExecutor("web", id -> id.startsWith("web-"), (sid, prompt) -> runRound(sid, prompt));
 *
 * // 宿主的增删改查（一律按 sessionId 定位；taskId 可空 = 本会话当前 goal，非空时不匹配即视为不存在）：
 * GoalTask t = goal.create(sid, GoalSpec.of(prompt).maxTokens(2000));
 * goal.update(sid, t.getId(), GoalPatch.create().objective(newPrompt), stopper);
 * goal.pause(sid, t.getId()); goal.resume(sid, t.getId()); goal.trigger(sid, t.getId()); goal.remove(sid, t.getId());
 * Map<String, Object> view = goal.get(sid).toMap();
 * }</pre>
 *
 * <p><b>单槽</b>：每个会话最多一个 goal；已有非终态 goal 时 {@code create} 抛 {@link IllegalStateException}。
 * 状态以投影存放于会话快照，随对话持久化；对外只交只读快照 {@link GoalTask}。</p>
 *
 * <p><b>执行器</b>：按注册顺序取首个认领者（谓词命中即认领，结论不穿透）；全部不认领时走缺省执行器
 * （{@code engine.handle(HarnessInput.ofProgram(..., "goal")).call()}）。结论语义见 {@link GoalOutcome}。
 * 会话忙由插件在调用执行器之前统一判定（{@code engine.isIdle}），宿主不提供忙判定。</p>
 *
 * <p><b>开关语义</b>：{@link #setEnabled} 关闭时同时关闭模型工具与 /goal 命令，并停止全部调度
 * （goal 依赖 {@code goal_update} 声明完成，工具关了还继续跑只会空耗预算直到熔断）。</p>
 *
 * <p><b>钩子</b>：{@code onAttach} 装配运行态、调度与命令；{@code onRequestAssemble} 给顶层请求挂
 * {@link GoalTalent}（委派不挂）；{@code contributeSessionStatus} 贡献 {@link #STATUS_GOAL}；
 * {@code onSessionRemove} 注销该会话的 job；{@code onDetach} 卸载全部 job。
 * 调度轮次只取已存在的会话（{@code getSessionOrNil}），会话已不存在时 job 自行注销，不会把会话重新建出来。</p>
 *
 * @author noear
 * @since 3.9.2
 */
public class GoalExtension extends AbsHarnessExtension {

    /**
     * 会话状态事实：当前 goal 的状态（无 goal 时为 null）。
     *
     * <p>经 {@code engine.status(session).get(GoalExtension.STATUS_GOAL)} 读取。</p>
     */
    public static final SessionStatus.Key<GoalTask.Status> STATUS_GOAL = SessionStatus.Key.of("goal.status", null);

    private static final Logger LOG = LoggerFactory.getLogger(GoalExtension.class);

    /** 编辑在跑 goal 时等待旧轮次停稳的上限。 */
    static final long UPDATE_STOP_TIMEOUT_MS = 2000L;

    private final String workspaceId;
    private final String workspace;
    private final BudgetThresholds budgetThresholds;
    private final int stagnationThreshold;
    private final int maxConsecutiveErrors;
    private final boolean validatorEnabled;
    private final IJobManager jobManager;
    private final GoalListener listener;

    private HarnessEngine engine;
    private GoalRuntime goalRuntime;
    private GoalScheduler goalScheduler;
    private GoalTalent goalTalent;
    private GoalCommand goalCommand;

    private GoalExtension(Builder b) {
        this.workspaceId = b.workspaceId;
        this.workspace = b.workspace != null ? b.workspace : b.workspaceId;
        this.budgetThresholds = b.budgetThresholds;
        this.stagnationThreshold = b.stagnationThreshold;
        this.maxConsecutiveErrors = b.maxConsecutiveErrors;
        this.validatorEnabled = b.validatorEnabled;
        this.listener = b.listener;

        IJobManager jm = b.jobManagerSet ? b.jobManager : resolveJobManager();
        if (jm == null && !b.jobManagerSet) {
            // 未显式关闭调度却取不到 JobManager：goal「看起来在、实际不跑」，必须让宿主看见
            LOG.warn("[Goal] No JobManager available (is solon-scheduling-simple started?); "
                    + "goals of workspace '{}' will be stored but never advance. "
                    + "Use builder.jobManager(null) to disable scheduling explicitly.", b.workspaceId);
        }
        this.jobManager = jm;
    }

    /** 无 Solon 容器（纯 JUnit）时为 null，调度能力随之失效。 */
    private static IJobManager resolveJobManager() {
        try {
            return JobManager.getInstance();
        } catch (Throwable e) {
            return null;
        }
    }

    /** builder 入口。@param workspaceId goal job 名前缀（跨工作区防撞）；可空（仅单工作区进程安全） */
    public static Builder builder(String workspaceId) {
        return new Builder(workspaceId);
    }

    // ==================== 钩子 ====================

    @Override
    public void onAttach(HarnessEngine engine) {
        if (goalRuntime != null) {
            return;
        }
        this.engine = engine;
        goalRuntime = new GoalRuntime(engine, budgetThresholds, stagnationThreshold,
                maxConsecutiveErrors, validatorEnabled, workspace);
        goalRuntime.setDefaultExecutor(defaultExecutor(engine));
        if (listener != null) {
            goalRuntime.addListener(listener);
        }

        if (jobManager != null) {
            goalScheduler = new GoalScheduler(goalRuntime, jobManager, workspaceId);
        }

        goalCommand = new GoalCommand(this);
        goalCommand.setEnabled(isEnabled());
        engine.getCommandRegistry().register(goalCommand);

        goalTalent = new GoalTalent(goalRuntime, engine);
        goalTalent.setEnabled(isEnabled());
    }

    @Override
    public void onRequestAssemble(HarnessEngine engine, String agentName, boolean isDelegated, ReActOptionsAmend optionsAmend) {
        // 子代理委派走临时会话，goal 是会话级能力，不挂
        if (isDelegated == false && goalTalent != null) {
            optionsAmend.talentAdd(goalTalent);
        }
    }

    /** 事实口径，不判开关（停用不代表 goal 消失）；首次读取时从投影 hydrate。 */
    @Override
    public void contributeSessionStatus(HarnessEngine engine, AgentSession session, SessionStatus.Builder builder) {
        if (goalRuntime == null) {
            return;
        }
        GoalState gs = goalRuntime.state(session);
        if (gs != null) {
            builder.put(STATUS_GOAL, gs.getStatus());
        }
    }

    /** 会话被永久删除：注销该会话的 goal job（清理类，不判开关；幂等）。goal 状态存在会话属性里，随会话一起消失。 */
    @Override
    public void onSessionRemove(HarnessEngine engine, String sessionId) {
        if (goalScheduler != null) {
            goalScheduler.unschedule(sessionId);
        }
    }

    /** 卸载全部 job（JobManager 是 JVM 单例，不卸则带着已废引擎空转）；会话里的 goal 记录保留。 */
    @Override
    public void onDetach(HarnessEngine engine) {
        if (goalScheduler != null) {
            goalScheduler.stopAll();
        }
    }

    @Override
    public void setEnabled(Boolean enabled) {
        super.setEnabled(enabled);

        if (goalCommand != null) {
            goalCommand.setEnabled(enabled);
        }
        if (goalTalent != null) {
            goalTalent.setEnabled(enabled);
        }
        // 关闭时同步停止调度（只停未来的调度，不打断正在执行的那一轮）
        if (enabled != null && enabled.booleanValue() == false && goalScheduler != null) {
            goalScheduler.stopAll();
        }
    }

    /**
     * 缺省执行器：经唯一输入入口同步跑一轮（程序生成的 prompt 不解析命令与 @提及）。
     *
     * <p>token 与工具证据直接取自本次响应；失败抛给 goal（参与错误分级熔断），不吞掉。</p>
     */
    private static GoalExecutor defaultExecutor(HarnessEngine engine) {
        return (sessionId, prompt) -> {
            AgentSession session = engine.getSessionOrNil(sessionId);
            if (session == null) {
                return GoalOutcome.skip("session not found");
            }
            ReActResponse response;
            try {
                response = engine.handle(HarnessInput.ofProgram(session, prompt, "goal")).call();
            } catch (Exception | Error e) {
                throw e;
            } catch (Throwable e) {
                throw new IllegalStateException(e.getMessage() != null ? e.getMessage() : e.toString(), e);
            }
            boolean hasToolCalls = response.getTrace() != null && response.getTrace().getToolCallCount() > 0;
            long tokens = response.getMetrics() != null ? response.getMetrics().getTotalTokens() : 0L;
            return GoalOutcome.done(response.getContent(), hasToolCalls, tokens);
        };
    }

    // ==================== 装配期扩展点 ====================

    /**
     * 注册通道化执行器（按注册顺序认领，先于缺省执行器）。
     *
     * <p>谓词必须互斥（宿主应集中在单一所有权类，如 {@code SessionIdOwnership}）；多个谓词同时命中时
     * 按注册顺序取首个并告警（每个命中组合一次）。</p>
     *
     * @param channel  通道名（日志与互斥告警用，如 "cli" / "web" / "desktop"）
     * @param claim    认领谓词（sessionId → 是否本通道的会话）；null = 认领全部
     * @param executor 执行器；通道此刻不能跑返回 {@link GoalOutcome#skip}，失败抛异常即可
     */
    public GoalExtension addExecutor(String channel, Predicate<String> claim, GoalExecutor executor) {
        requireAttached().addExecutor(channel, claim, executor);
        return this;
    }

    /** 注册生命周期观察者（每次状态变更收到只读快照）。 */
    public GoalExtension addListener(GoalListener listener) {
        requireAttached().addListener(listener);
        return this;
    }

    /** 注册带条件匹配的完成验证器（多次注册按 AND 语义）。 */
    public GoalExtension registerValidator(ConditionMatcher matcher, GoalValidator validator) {
        requireAttached().registerValidator(matcher, validator);
        return this;
    }

    // ==================== 运行期门面 ====================

    /**
     * 创建本会话的 goal 并挂上调度（{@link GoalSpec#runNow} 决定是否立即跑首轮）。
     *
     * @return 新 goal 的快照（{@code getId()} 即 taskId）
     * @throws IllegalArgumentException 会话不存在
     * @throws IllegalStateException    本会话已有非终态 goal（单槽）
     */
    public GoalTask create(String sessionId, GoalSpec spec) {
        return create(requireSession(sessionId), spec);
    }

    /** 本会话当前 goal（无则 null）。 */
    public GoalTask get(String sessionId) {
        AgentSession session = sessionOf(sessionId);
        return session == null ? null : goalRuntime.snapshot(session);
    }

    /** 按 taskId 定位（taskId 为空 = 当前 goal；不匹配返回 null，供与 loop 任务混排的列表分流）。 */
    public GoalTask get(String sessionId, String taskId) {
        AgentSession session = sessionOf(sessionId);
        GoalState gs = locate(session, taskId);
        return gs == null ? null : GoalTask.of(gs, goalRuntime.isRunning(session));
    }

    /**
     * 按补丁修改（null 字段保持原值）。
     *
     * <p>goal 活跃时按固定时序完成：暂停并摘调度 → 停流 → 等旧轮次停稳 → 改写 → 恢复并立即跑一轮；
     * 非活跃（PAUSED/BLOCKED）时只改写、不改状态。</p>
     *
     * @param stopper 停掉在跑轮次的动作（渠道侧可能还要推终态帧、清积压）；null = {@code engine.cancelStream}
     * @return 修改后的快照；null 表示不存在
     * @throws IllegalStateException goal 已是终态，或旧轮次未能及时停稳（此时 goal 保持暂停，可重试）
     */
    public GoalTask update(String sessionId, String taskId, GoalPatch patch, Runnable stopper) {
        AgentSession session = sessionOf(sessionId);
        GoalState gs = locate(session, taskId);
        if (gs == null) {
            return null;
        }
        if (gs.getStatus().isTerminal()) {
            throw new IllegalStateException("已完成的 Goal 不能编辑，请先清除");
        }
        if (patch == null || patch.isEmpty()) {
            return goalRuntime.snapshot(session);
        }

        boolean wasActive = gs.getStatus().isActive();
        if (wasActive) {
            // 先摘调度再停流：避免停流后 job 立刻又拉起一轮
            pause(session);
            try {
                if (stopper != null) {
                    stopper.run();
                } else {
                    engine.cancelStream(session);
                }
            } catch (Throwable e) {
                // 停流失败不阻断停等：轮次可能已在收尾，交给超时判定
                LOG.debug("Goal stopper failed for session '{}': {}", sessionId, e.getMessage());
            }
            if (goalRuntime.awaitRoundStopped(session, UPDATE_STOP_TIMEOUT_MS) == false) {
                throw new IllegalStateException("Goal is still stopping; please retry");
            }
            if (gs.getStatus() != GoalTask.Status.PAUSED) {
                throw new IllegalStateException("Goal state changed before settings could be applied");
            }
        }

        goalRuntime.apply(session, gs, patch);

        if (wasActive) {
            resume(session);
        }
        return goalRuntime.snapshot(session);
    }

    /** 暂停（PURSUING → PAUSED）并摘调度。@return false 表示不存在或当前状态不可暂停 */
    public boolean pause(String sessionId, String taskId) {
        AgentSession session = sessionOf(sessionId);
        return locate(session, taskId) != null && pause(session);
    }

    /** 恢复（PAUSED/BLOCKED → PURSUING）并挂调度、立即跑一轮。@return false 表示不存在或当前状态不可恢复 */
    public boolean resume(String sessionId, String taskId) {
        AgentSession session = sessionOf(sessionId);
        return locate(session, taskId) != null && resume(session);
    }

    /**
     * 立即异步跑一轮（只对活跃 goal；同一会话的轮次不会并发）。
     *
     * @return false 表示不存在或不处于活跃态
     */
    public boolean trigger(String sessionId, String taskId) {
        AgentSession session = sessionOf(sessionId);
        GoalState gs = locate(session, taskId);
        if (gs == null || gs.getStatus().isActive() == false || isEnabled() == false) {
            return false;
        }
        schedule(sessionId, false);
        RunUtil.async(() -> goalRuntime.runRound(session));
        return true;
    }

    /** 清除 goal（先摘调度再清状态，避免清除瞬间被 job 捞起）。@return false 表示不存在 */
    public boolean remove(String sessionId, String taskId) {
        AgentSession session = sessionOf(sessionId);
        return locate(session, taskId) != null && remove(session);
    }

    /**
     * 重启后批量恢复活跃 goal 的调度（只挂 job、不立即跑一轮，否则重启会对每个活跃 goal 立即发起执行）。
     *
     * <p>宿主负责枚举候选会话与读取状态（内存会话优先、未实例化则轻读 {@link GoalProjection}）；
     * 仅活跃态才挂、已调度幂等跳过。</p>
     *
     * @param statusReader sessionId → 状态（无 goal 返回 null；抛异常按无 goal 处理）
     * @return 实际挂上调度的会话数
     */
    public int recover(Iterable<String> sessionIds, Function<String, GoalTask.Status> statusReader) {
        if (sessionIds == null || statusReader == null || goalScheduler == null || isEnabled() == false) {
            return 0;
        }
        int recovered = 0;
        for (String sessionId : sessionIds) {
            if (sessionId == null || sessionId.isEmpty()) {
                continue;
            }
            GoalTask.Status status;
            try {
                status = statusReader.apply(sessionId);
            } catch (Throwable e) {
                LOG.debug("Goal recovery status read failed for session '{}': {}", sessionId, e.getMessage());
                continue;
            }
            if (status != null && status.isActive()) {
                schedule(sessionId, false);
                recovered++;
            }
        }
        return recovered;
    }

    /** 是否仍有 goal 在调度（工作区闲置回收判定用）。 */
    public boolean hasActiveGoals() {
        return goalScheduler != null && goalScheduler.hasScheduledJobs();
    }

    /** 是否装配了调度器（{@code jobManager(null)} 或无容器时为 false，goal 不会自动推进）。 */
    public boolean hasScheduler() {
        return goalScheduler != null;
    }

    // ==================== 包内（命令与测试共用的会话级实现） ====================

    GoalTask create(AgentSession session, GoalSpec spec) {
        GoalState gs = requireAttached().attach(session, spec);
        schedule(session.getSessionId(), spec.isRunNow());
        return GoalTask.of(gs, goalRuntime.isRunning(session));
    }

    GoalTask snapshot(AgentSession session) {
        return requireAttached().snapshot(session);
    }

    boolean pause(AgentSession session) {
        GoalState gs = goalRuntime.state(session);
        if (gs == null || goalRuntime.pause(session, gs) == false) {
            return false;
        }
        if (goalScheduler != null) {
            goalScheduler.unschedule(session.getSessionId());
        }
        return true;
    }

    boolean resume(AgentSession session) {
        GoalState gs = goalRuntime.state(session);
        if (gs == null || goalRuntime.resume(session, gs) == false) {
            return false;
        }
        schedule(session.getSessionId(), true);
        return true;
    }

    boolean remove(AgentSession session) {
        if (goalRuntime.state(session) == null) {
            return false;
        }
        if (goalScheduler != null) {
            goalScheduler.unschedule(session.getSessionId());
        }
        goalRuntime.clear(session);
        return true;
    }

    GoalRuntime getGoalRuntime() {
        return goalRuntime;
    }

    GoalScheduler getGoalScheduler() {
        return goalScheduler;
    }

    GoalTalent getGoalTalent() {
        return goalTalent;
    }

    private void schedule(String sessionId, boolean runNow) {
        if (goalScheduler != null && sessionId != null && isEnabled()) {
            goalScheduler.schedule(sessionId, runNow);
        }
    }

    private GoalRuntime requireAttached() {
        if (goalRuntime == null) {
            throw new IllegalStateException("GoalExtension is not attached; call engine.addExtension(...) first");
        }
        return goalRuntime;
    }

    private AgentSession sessionOf(String sessionId) {
        return requireAttached().sessionOf(sessionId);
    }

    private AgentSession requireSession(String sessionId) {
        AgentSession session = sessionOf(sessionId);
        if (session == null) {
            throw new IllegalArgumentException("Session not found: " + sessionId);
        }
        return session;
    }

    /** 定位运行态：taskId 为空 = 当前 goal；不匹配返回 null。 */
    private GoalState locate(AgentSession session, String taskId) {
        if (session == null) {
            return null;
        }
        GoalState gs = goalRuntime.state(session);
        if (gs == null) {
            return null;
        }
        if (taskId != null && taskId.isEmpty() == false && taskId.equals(gs.getId()) == false) {
            return null;
        }
        return gs;
    }

    // ==================== Builder ====================

    /** GoalExtension 构建器：workspaceId 为 job 前缀，其余全有缺省。 */
    public static class Builder {
        private final String workspaceId;
        private String workspace;
        private BudgetThresholds budgetThresholds = BudgetThresholds.defaults();
        private int stagnationThreshold = 3;
        private int maxConsecutiveErrors = 3;
        private boolean validatorEnabled = true;
        private IJobManager jobManager;
        private boolean jobManagerSet;
        private GoalListener listener;

        private Builder(String workspaceId) {
            this.workspaceId = workspaceId;
        }

        /** 预算提示阈值（警告%/紧急%），缺省 70/85。 */
        public Builder budget(int warningPercent, int criticalPercent) {
            this.budgetThresholds = new BudgetThresholds(warningPercent, criticalPercent);
            return this;
        }

        /** 连续无进展多少轮判定停滞，缺省 3。 */
        public Builder stagnation(int threshold) {
            this.stagnationThreshold = threshold;
            return this;
        }

        /** 连续错误多少次熔断，缺省 3。 */
        public Builder maxErrors(int count) {
            this.maxConsecutiveErrors = count;
            return this;
        }

        /** 是否启用完成前验证，缺省 true。 */
        public Builder validatorEnabled(boolean enabled) {
            this.validatorEnabled = enabled;
            return this;
        }

        /** 验证器执行目录；缺省用 workspaceId。 */
        public Builder workspace(String path) {
            this.workspace = path;
            return this;
        }

        /** 调度管理器；缺省取 {@code JobManager.getInstance()}；显式传 null = 关闭调度（纯测试宿主）。 */
        public Builder jobManager(IJobManager jobManager) {
            this.jobManager = jobManager;
            this.jobManagerSet = true;
            return this;
        }

        /** 预注册生命周期观察者。 */
        public Builder listener(GoalListener l) {
            this.listener = l;
            return this;
        }

        public GoalExtension build() {
            return new GoalExtension(this);
        }
    }
}
