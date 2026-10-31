package org.noear.solon.ai.harness.session.goal;

import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.react.ReActAgent;
import org.noear.solon.ai.harness.AbsHarnessExtension;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.ai.harness.HarnessExtension;
import org.noear.solon.ai.harness.agent.AgentDefinition;
import org.noear.solon.ai.harness.command.builtin.GoalCommand;
import org.noear.solon.ai.harness.input.HarnessInput;
import org.noear.solon.ai.harness.input.InputOrigin;
import org.noear.solon.ai.harness.runtime.ExecutionBridge;
import org.noear.solon.ai.harness.session.goal.validate.GoalValidator;
import org.noear.solon.core.util.RunUtil;

import java.util.Map;

/**
 * Goal 一键装配扩展 —— goal 能力从头到尾只接触这一个类。
 *
 * <p><b>推荐用法</b>（builder，装配在 {@code engine.addExtension(...)} 触发的
 * {@link #initialize(HarnessEngine)} 里一次完成）：</p>
 * <pre>{@code
 * GoalExtension goal = GoalExtension.builder(workspaceId)
 *         .taskExecutor((sessionId, prompt, agentName) -> runOneTurn(sessionId, prompt))  // 唯一必填
 *         .busyChecker(sessionId -> isBusy(sessionId))
 *         .budget(70, 85)
 *         .build();
 * engine.addExtension(goal);
 * // initialize 内部：ExecutionBridge → GoalEngine → GoalScheduler → /goal 命令注册 → GoalTalent 挂载
 * }</pre>
 *
 * <p>不传 {@code taskExecutor} 时使用内置缺省：经 {@code engine.handle} + 同步取回最终文本
 * （README §5.1 的模板实现）。{@code busyChecker} 缺省恒 false。</p>
 *
 * <p><b>与 loop 共享执行桥</b>：宿主已有 {@link ExecutionBridge}（loop 与 goal 共用的组合根资源）
 * 时用 {@link Builder#bridge(ExecutionBridge)} 注入，扩展不再自建——TaskHandler/BusyChecker
 * 仍会注册到共享桥上。</p>
 *
 * <p><b>高级用法</b>：{@link Builder#scheduler(GoalScheduler)} 允许宿主自备调度器
 * （如测试环境无 Solon 容器、{@code JobManager.getInstance()} 不可用时传 null 关闭调度，
 * 仅保留命令与 Talent）。{@code workspaceId} 用于 goal job 名前缀（跨工作区防撞），
 * {@code workspace} 用于验证器执行目录，两者可不同。</p>
 *
 * <p>运行期操作直接在本实例上中转（{@link #state}/{@link #pause}/{@link #stopAll()} 等），
 * 不必再持有 {@link GoalEngine}/{@link GoalScheduler} 引用。</p>
 *
 * @author noear
 * @since 3.9.2
 */
public class GoalExtension extends AbsHarnessExtension {
    private final String workspaceId;
    private final String workspace;
    private final ExecutionBridge sharedBridge;      // 宿主注入的共享桥（可空）
    private final ExecutionBridge.TaskHandler taskHandler;
    private final ExecutionBridge.BusyChecker busyChecker;
    private final BudgetThresholds budgetThresholds;
    private final int stagnationThreshold;
    private final int maxConsecutiveErrors;
    private final boolean validatorEnabled;
    private final GoalScheduler providedScheduler;   // 宿主自备调度器（可空；高级用法）
    private final boolean schedulerDisabled;         // 显式关闭调度（scheduler(null)）
    private final GoalEngine.GoalListener listener;

    private ExecutionBridge bridge;
    private GoalEngine goalEngine;
    private GoalScheduler goalScheduler;
    private GoalTalent goalTalent;

    private GoalCommand goalCommand;

    private GoalExtension(Builder b) {
        this.workspaceId = b.workspaceId;
        this.workspace = b.workspace;
        this.sharedBridge = b.bridge;
        this.taskHandler = b.taskHandler;
        this.busyChecker = b.busyChecker;
        this.budgetThresholds = b.budgetThresholds;
        this.stagnationThreshold = b.stagnationThreshold;
        this.maxConsecutiveErrors = b.maxConsecutiveErrors;
        this.validatorEnabled = b.validatorEnabled;
        this.providedScheduler = b.scheduler;
        this.schedulerDisabled = b.schedulerExplicitNull;
        this.listener = b.listener;
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
    }

    /** builder 入口。@param workspaceId goal job 名前缀（跨工作区防撞）；可空（仅单工作区进程安全） */
    public static Builder builder(String workspaceId) {
        return new Builder(workspaceId);
    }

    /**
     * 引擎级装配（addExtension 时回调一次；此刻引擎已装配完毕，任意引擎方法可安全调用）。
     *
     * <p>顺序：桥（注入或自建 + 注册回调）→ GoalEngine → GoalScheduler → /goal 命令注册 →
     * 预注册 listener。{@link #configure} 负责把 GoalTalent 挂到主代理。</p>
     */
    @Override
    public void initialize(HarnessEngine engine) {
        if (goalEngine != null && bridge == null) {
            return; // 存量形态（外部 GoalEngine）：不重复装配
        }

        // ① 执行桥：优先复用宿主与 loop 共享的桥，否则自建
        ExecutionBridge effectiveBridge = sharedBridge != null ? sharedBridge : new ExecutionBridge(engine);
        // TaskHandler 缺省走 engine.handle 模板（README §5.1 的内置化）；注册在宿主回调之后，
        // 共享桥场景 loop 的认领式 handler 先执行，本缺省恒认领兜底
        effectiveBridge.addTaskExecutor(taskHandler != null ? taskHandler
                : defaultTaskExecutor(engine));
        if (busyChecker != null) {
            effectiveBridge.addBusyChecker(busyChecker);
        }
        this.bridge = effectiveBridge;

        // ② Goal 引擎
        if (goalEngine == null) {
            goalEngine = new GoalEngine(effectiveBridge,
                    budgetThresholds != null ? budgetThresholds : BudgetThresholds.defaults(),
                    stagnationThreshold, maxConsecutiveErrors, validatorEnabled,
                    workspace != null ? workspace : workspaceId);
        }

        // ③ 调度器（宿主自备优先；显式关闭时不建——纯 JUnit/无容器宿主 JobManager 不可用）
        if (goalScheduler == null) {
            goalScheduler = providedScheduler != null ? providedScheduler
                    : (schedulerDisabled ? null : new GoalScheduler(goalEngine, workspaceId));
        }

        if(goalCommand == null) {
            goalCommand = new GoalCommand(goalEngine, goalScheduler);
            goalCommand.setEnabled(this.isEnabled());
        }

        // ④ /goal 命令（幂等：已注册同名命令时跳过，迁移期双写安全）
        engine.getCommandRegistry().register(goalCommand);

        // ⑤ 预注册生命周期观察者
        if (listener != null) {
            goalEngine.addListener(listener);
        }
    }

    /** 缺省 TaskHandler：目标正文走唯一输入入口同步取回最终文本（commandAware/agentAware 关闭，程序生成的 prompt 不再解析命令与 @提及）。 */
    private static ExecutionBridge.TaskHandler defaultTaskExecutor(HarnessEngine engine) {
        return (sessionId, prompt, agentName) -> {
            AgentSession session = engine.getSession(sessionId);
            if (session == null) {
                return null;
            }
            try {
                return engine.handle(HarnessInput.of(session, prompt)
                                .commandAware(false)
                                .agentAware(false)
                                .origin(InputOrigin.channel("goal")))
                        .getRequest()
                        .call()
                        .getContent();
            } catch (Throwable e) {
                return "error: " + (e.getMessage() != null ? e.getMessage() : e.toString());
            }
        };
    }

    @Override
    public void configure(HarnessEngine engine, String agentName, ReActAgent.Builder agentBuilder) {
        if (goalTalent == null && goalEngine != null) {
            goalTalent = new GoalTalent(goalEngine, engine);
            goalTalent.setEnabled(this.isEnabled());
        }

        if (AgentDefinition.AGENT_MAIN.equals(agentName)) {
            agentBuilder.defaultTalentAdd(goalTalent);
        }
    }

    // ==================== 运行期门面（中转 GoalEngine / GoalScheduler） ====================

    /** 模型侧工具（{@code goal_get}/{@code goal_update}）；{@code setEnabled(false)} 只关工具不关命令。 */
    public GoalTalent getGoalTalent() {
        return goalTalent;
    }

    /** 组装好的 GoalEngine（initialize 之后非空）；供确需细粒度访问的宿主。 */
    public GoalEngine getGoalEngine() {
        return goalEngine;
    }

    /** 组装好的 GoalScheduler（initialize 之后非空，除非显式关闭）；供恢复/停机等高级场景。 */
    public GoalScheduler getGoalScheduler() {
        return goalScheduler;
    }

    /** 读取会话 goal（无则 null）。 */
    public GoalState state(AgentSession session) {
        return goalEngine == null ? null : goalEngine.state(session);
    }

    /**
     * 按 sessionId 解析会话（无则 null）。
     *
     * <p>宿主做「这个 taskId 是不是 goal」的分流时，通常第一步就要拿到会话；
     * 提供此方法以免为一次查询而持 {@link GoalEngine} 引用。</p>
     */
    public AgentSession sessionOf(String sessionId) {
        return goalEngine == null ? null : goalEngine.sessionOf(sessionId);
    }

    /** 按 sessionId 读取 goal（无则 null）；等价于 {@code state(sessionOf(id))} 但免去两步判空。 */
    public GoalState stateOf(String sessionId) {
        AgentSession session = sessionOf(sessionId);
        return session == null ? null : state(session);
    }

    /**
     * 按 (sessionId, taskId) 定位 goal 行；不匹配或无 goal 时返回 null。
     *
     * <p>收敛宿主「sessionOf → state → taskId.equals(gs.getId())」三连样板：
     * loop 任务表与 goal 行共用同一列表时，宿主要靠这段判断该 id 归谁。</p>
     */
    public GoalState find(String sessionId, String taskId) {
        if (taskId == null || taskId.isEmpty()) {
            return null;
        }
        GoalState gs = stateOf(sessionId);
        return (gs != null && taskId.equals(gs.getId())) ? gs : null;
    }

    /** 本会话是否有一轮 goal 正在执行（UI 展示与编辑前停等判定用）。 */
    public boolean running(AgentSession session) {
        return goalEngine != null && goalEngine.isRunning(session);
    }

    /** 按 sessionId 判断是否有轮次正在执行。 */
    public boolean running(String sessionId) {
        AgentSession session = sessionOf(sessionId);
        return session != null && running(session);
    }

    /** 是否存在活跃或可恢复的 goal。 */
    public boolean hasGoal(AgentSession session) {
        return goalEngine != null && goalEngine.hasGoal(session);
    }

    /** 供列表/徽章渲染的任务视图（与 loop 任务同构，type = "GOAL"）。 */
    public Map<String, Object> toTaskMap(AgentSession session, GoalState gs) {
        return goalEngine == null ? null : goalEngine.toTaskMap(session, gs);
    }

    /**
     * 创建 goal（<b>不挂调度</b>）。供首轮由宿主自己的流驱动、只需后续挂 job 的场景（桌面端）。
     *
     * <p>单槽校验异常原样透出（{@link IllegalStateException} 已有进行中 goal）。</p>
     */
    public GoalState attach(AgentSession session, String objective,
                            long maxTokens, long maxDurationMs, int maxIterations) {
        return goalEngine.attach(session, objective, maxTokens, maxDurationMs, maxIterations);
    }

    /** 持久化状态变更并广播（修改 {@link GoalState} 字段后必须调用）。 */
    public void commit(AgentSession session, GoalState gs) {
        if (goalEngine != null) {
            goalEngine.commit(session, gs);
        }
    }

    /** 是否装配了调度器（无容器测试宿主可能显式关闭）。 */
    public boolean hasScheduler() {
        return goalScheduler != null;
    }

    /**
     * 创建 goal 并立即挂上调度（{@code runNow=true}）。
     *
     * <p>收敛宿主「attach + schedule」配对；{@code attach} 的单槽校验异常
     * （{@link IllegalStateException} 已有进行中 goal）原样透出，宿主据此回 409。</p>
     */
    public GoalState attachAndSchedule(AgentSession session, String objective,
                                       long maxTokens, long maxDurationMs, int maxIterations) {
        GoalState gs = goalEngine.attach(session, objective, maxTokens, maxDurationMs, maxIterations);
        scheduleNow(session.getSessionId());
        return gs;
    }

    /**
     * 编辑 goal 后重新挂调度：update →（可选）改时长 commit → resume → schedule(runNow=true)。
     *
     * <p><b>只覆盖后半段</b>：调用方须自行完成「暂停 + 中断在跑轮次 + 等待停止」——
     * 中断依赖宿主的运行时通道，不属于 goal 域职责。</p>
     *
     * @param resumeAfterUpdate 编辑前是否为活跃态（true 才恢复调度）
     * @param maxDurationMs     非空时才写时长（null = 保持原值，不能清空）
     */
    public void updateAndReschedule(AgentSession session, GoalState gs, String objective,
                                    Long maxTokens, Long maxDurationMs, boolean resumeAfterUpdate) {
        goalEngine.update(session, objective,
                maxTokens != null ? maxTokens : gs.getMaxTokens(),
                gs.getMaxIterations());
        if (maxDurationMs != null) {
            gs.setMaxDurationMs(maxDurationMs);
            goalEngine.commit(session, gs);
        }
        if (resumeAfterUpdate) {
            goalEngine.resume(session, gs);
            scheduleNow(session.getSessionId());
        }
    }

    /** 暂停（PURSUING → PAUSED）并注销调度。 */
    public boolean pause(AgentSession session, GoalState gs) {
        if (goalEngine == null || !goalEngine.pause(session, gs)) {
            return false;
        }
        if (goalScheduler != null) {
            goalScheduler.unschedule(session.getSessionId());
        }
        return true;
    }

    /** 恢复（PAUSED/BLOCKED → PURSUING）并重新挂调度（立即跑一轮）。 */
    public boolean resume(AgentSession session, GoalState gs) {
        if (goalEngine == null || !goalEngine.resume(session, gs)) {
            return false;
        }
        if (goalScheduler != null) {
            goalScheduler.schedule(session.getSessionId(), true);
        }
        return true;
    }

    /**
     * 恢复（PAUSED/BLOCKED → PURSUING）并重新挂调度（立即跑一轮）。
     *
     * <p>与 {@link #resume} 同义，命名上强调「连带挂调度」，便于宿主在编辑/接管路径选用。</p>
     */
    public boolean resumeAndSchedule(AgentSession session, GoalState gs) {
        return resume(session, gs);
    }

    /**
     * 仅把会话挂回调度（{@code runNow=false}，等下一轮），不做状态转移。
     *
     * <p><b>恢复期专用</b>：进程重启后为快照中仍处活跃态的 goal 重新挂 job 时使用——
     * 此时不能走 {@link #resume}（那是立刻跑一轮），否则重启会对每个活跃 goal 立即发起执行。</p>
     */
    public void resumeScheduleOnly(String sessionId) {
        if (goalScheduler != null && sessionId != null) {
            goalScheduler.schedule(sessionId, false);
        }
    }

    /** 立即挂调度并马上跑一轮（桌面端 trigger 语义）；不做状态校验，调用方前置判活跃态。 */
    public void scheduleNow(String sessionId) {
        if (goalScheduler != null && sessionId != null) {
            goalScheduler.schedule(sessionId, true);
        }
    }

    /** 注销调度（不清状态）。clear/pause 已内含此步，仅在纯停调度场景直接用。 */
    public void unschedule(String sessionId) {
        if (goalScheduler != null && sessionId != null) {
            goalScheduler.unschedule(sessionId);
        }
    }

    /** 异步触发一轮执行（不阻塞请求线程）；调用方须前置校验活跃态。 */
    public void runRoundAsync(AgentSession session) {
        if (goalEngine == null || session == null) {
            return;
        }
        RunUtil.async(() -> goalEngine.runRound(session));
    }

    /** 清除会话 goal 并注销调度（先注销调度再清状态，避免清除瞬间被 job 捞起）。 */
    public void clear(AgentSession session) {
        if (goalScheduler != null) {
            goalScheduler.unschedule(session.getSessionId());
        }
        if (goalEngine != null) {
            goalEngine.clear(session);
        }
    }

    /** 注册生命周期观察者（initialize 前调用即预注册，之后正常注册）。 */
    public void addListener(GoalEngine.GoalListener l) {
        if (goalEngine != null) {
            goalEngine.addListener(l);
        }
    }

    /** 注册带条件匹配的验证器（多次注册按 AND 语义）。 */
    public void registerValidator(GoalEngine.ConditionMatcher matcher, GoalValidator validator) {
        if (goalEngine != null) {
            goalEngine.registerValidator(matcher, validator);
        }
    }

    /** 是否仍有会话 goal 在调度（工作区闲置判定用）。 */
    public boolean hasScheduledGoals() {
        return goalScheduler != null && goalScheduler.hasScheduledJobs();
    }

    /** 停止全部 goal 调度（工作区关闭时必须调用，防 job 泄漏）。 */
    public void stopAll() {
        if (goalScheduler != null) {
            goalScheduler.stopAll();
        }
    }

    // ==================== Builder ====================

    /** GoalExtension 构建器：workspaceId 必填（job 前缀），其余全有缺省。 */
    public static class Builder {
        private final String workspaceId;
        private String workspace;                                   // 缺省 = workspaceId
        private ExecutionBridge bridge;                             // 缺省 = 自建（goal 专用）
        private ExecutionBridge.TaskHandler taskHandler;            // 缺省 = engine.handle 模板
        private ExecutionBridge.BusyChecker busyChecker;            // 缺省 = 恒 false
        private BudgetThresholds budgetThresholds = BudgetThresholds.defaults();
        private int stagnationThreshold = 3;
        private int maxConsecutiveErrors = 3;
        private boolean validatorEnabled = true;
        private GoalScheduler scheduler;                            // 缺省 = initialize 时新建
        private boolean schedulerExplicitNull = false;              // 显式关闭调度三态标记
        private GoalEngine.GoalListener listener;

        private Builder(String workspaceId) {
            this.workspaceId = workspaceId;
        }

        /** 任务执行器：执行一轮 prompt 并返回最终文本（null = 本入口不认领）。不设则用内置缺省。 */
        public Builder taskExecutor(ExecutionBridge.TaskHandler handler) {
            this.taskHandler = handler;
            return this;
        }

        /** 忙判定：会话正在跑其它任务时跳过本轮。缺省恒 false。 */
        public Builder busyChecker(ExecutionBridge.BusyChecker checker) {
            this.busyChecker = checker;
            return this;
        }

        /** 复用宿主（与 loop 共享）的执行桥；不设则自建。 */
        public Builder bridge(ExecutionBridge shared) {
            this.bridge = shared;
            return this;
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

        /** 验证器执行目录；缺省用 workspaceId。传 null 与 workspaceId 同效（不注册默认验证器）。 */
        public Builder workspace(String path) {
            this.workspace = path;
            return this;
        }

        /** 自备调度器（高级用法）；显式传 null 表示不建调度（纯测试宿主，JobManager 不可用场景），不传则缺省新建。 */
        public Builder scheduler(GoalScheduler own) {
            this.scheduler = own;
            this.schedulerExplicitNull = (own == null);
            return this;
        }

        /** 预注册生命周期观察者。 */
        public Builder listener(GoalEngine.GoalListener l) {
            this.listener = l;
            return this;
        }

        public GoalExtension build() {
            if (workspace == null) {
                workspace = workspaceId;
            }
            return new GoalExtension(this);
        }
    }
}
