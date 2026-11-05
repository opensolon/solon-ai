package org.noear.solon.ai.harness.extension.goal;

import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.react.ReActOptionsAmend;
import org.noear.solon.ai.harness.AbsHarnessExtension;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.ai.harness.input.HarnessInput;
import org.noear.solon.ai.harness.input.InputOrigin;
import org.noear.solon.ai.harness.runtime.RoundResult;
import org.noear.solon.ai.harness.session.SessionStatus;
import org.noear.solon.ai.harness.extension.goal.validate.GoalValidator;
import org.noear.solon.core.util.RunUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Goal 一键装配扩展 —— goal 能力从头到尾只接触这一个类。
 *
 * <p><b>推荐用法</b>（builder，装配在 {@code engine.addExtension(...)} 触发的
 * {@link #onAttach(HarnessEngine)} 里一次完成）：</p>
 * <pre>{@code
 * GoalExtension goal = GoalExtension.builder(workspaceId)
 *         .taskExecutor((sessionId, prompt) -> runOneTurn(...))  // 可选覆盖
 *         .budget(70, 85)
 *         .build();
 * engine.addExtension(goal);
 * // onAttach 内部：GoalEngine → GoalScheduler → /goal 命令注册 → GoalTalent 挂载
 * }</pre>
 *
 * <p>不传 {@code taskExecutor} 时使用内置缺省：经 {@code engine.handle} + 同步取回最终文本
 * （README §5.1 的模板实现）。<b>忙判定不由宿主提供</b>：一律由会话快照派生
 * （{@code engine.isIdle(session)}），HITL 挂起与积压待执行均算不空闲。</p>
 *
 * <p><b>与 loop 无共享</b>：loop 是宿主调度能力（cli 域），goal 是引擎能力（harness 域），
 * 二者各持自己的执行通道与自己的宿。曾共用的 {@code ExecutionBridge} 已删除——
 * 它把「执行投递」「忙判定」「轮次度量」三件不相干的事装在一个类里，
 * 导致 loop 的语义渗进了 harness（上下划痕迹见 {@code _loop_*} 键的历史）。</p>
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

    /**
     * 会话状态事实：当前 goal 的状态（无 goal 时为 null）。
     *
     * <p>经 {@code engine.status(session).get(GoalExtension.STATUS_GOAL)} 读取，与运行流 / 积压等事实一次读全，
     * 宿主不必再单独查 goal 门面拼装徽章。</p>
     *
     * @since 4.2.0
     */
    public static final SessionStatus.Key<GoalState.Status> STATUS_GOAL = SessionStatus.Key.of("goal.status", null);
    private static final Logger LOG = LoggerFactory.getLogger(GoalExtension.class);

    private final String workspaceId;
    private final String workspace;
    private final GoalEngine.TaskHandler taskHandler;
    private final BudgetThresholds budgetThresholds;
    private final int stagnationThreshold;
    private final int maxConsecutiveErrors;
    private final boolean validatorEnabled;
    private final GoalScheduler providedScheduler;   // 宿主自备调度器（可空；高级用法）
    private final boolean schedulerDisabled;         // 显式关闭调度（scheduler(null)）
    private final GoalEngine.GoalListener listener;

    /** builder 阶段声明的通道化执行者（attach 后按序注册，先于运行期追加）。 */
    private final java.util.List<ChannelClaim> pendingChannelClaims;

    private GoalEngine goalEngine;
    private GoalScheduler goalScheduler;
    private GoalTalent goalTalent;

    /** 「无调度器时调用调度方法」的一次性告警门闩（防批量恢复场景刷屏）。 */
    private final AtomicBoolean noSchedulerWarned = new AtomicBoolean();

    private GoalCommand goalCommand;

    /** 通道化注册项（仅 builder → attach 间传递，不入運行期）。 */
    private static final class ChannelClaim {
        final String channel;
        final Predicate<String> claim;
        final GoalEngine.TaskHandler handler;

        ChannelClaim(String channel, Predicate<String> claim, GoalEngine.TaskHandler handler) {
            this.channel = channel;
            this.claim = claim;
            this.handler = handler;
        }
    }

    private GoalExtension(Builder b) {
        this.workspaceId = b.workspaceId;
        this.workspace = b.workspace;
        this.taskHandler = b.taskHandler;
        this.budgetThresholds = b.budgetThresholds;
        this.stagnationThreshold = b.stagnationThreshold;
        this.maxConsecutiveErrors = b.maxConsecutiveErrors;
        this.validatorEnabled = b.validatorEnabled;
        this.providedScheduler = b.scheduler;
        this.schedulerDisabled = b.schedulerExplicitNull;
        this.listener = b.listener;
        this.pendingChannelClaims = new java.util.ArrayList<>(b.channelClaims);
    }

    /**
     * 会话状态贡献：goal 状态（{@link #STATUS_GOAL}）。
     *
     * <p>事实口径，不判开关（停用不代表 goal 消失）；与 goal 运行态同用会话锁（可重入），
     * 首次读取时从投影 hydrate 并缓存运行态，此外无副作用。</p>
     */
    @Override
    public void onStatus(HarnessEngine engine, AgentSession session, SessionStatus.Builder status) {
        GoalEngine ge = goalEngine;
        if (ge == null) {
            return;
        }
        GoalState gs = ge.state(session);
        if (gs != null) {
            status.put(STATUS_GOAL, gs.getStatus());
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

        // 关闭时同步停止调度：否则扩展已「关闭」而 job 仍每 5s 触发 runRound，后台会持续发 LLM 请求。
        // 只停止未来的调度，不打断正在执行的那一轮（与 {@link GoalEngine#runRound} 的边界一致）。
        if (enabled != null && enabled.booleanValue() == false && goalScheduler != null) {
            goalScheduler.stopAll();
        }
    }

    /** builder 入口。@param workspaceId goal job 名前缀（跨工作区防撞）；可空（仅单工作区进程安全） */
    public static Builder builder(String workspaceId) {
        return new Builder(workspaceId);
    }

    /**
     * 存量形态：只接管外部已建好的 {@link GoalEngine}，不装配命令与调度。
     *
     * <p>适用于宿主自己管理 GoalEngine 生命周期、只借用本类的「Talent 挂载 + 运行期门面」的场景；
     * 新代码请用 {@link #builder(String)} 一键装配。</p>
     *
     * <p>该形态下不注册 /goal 命令（命令依赖内部装配的调度器），{@link #getGoalScheduler()} 为 null。</p>
     */
    public static GoalExtension of(GoalEngine goalEngine, HarnessEngine engine) {
        GoalExtension ext = builder(null).scheduler(null).build();
        ext.goalEngine = goalEngine;
        ext.goalTalent = goalEngine == null ? null : new GoalTalent(goalEngine, engine);
        return ext;
    }

    /**
     * 引擎级装配（addExtension 时回调一次；此刻引擎已装配完毕，任意引擎方法可安全调用）。
     *
     * <p>顺序：桥（注入或自建 + 注册回调）→ GoalEngine → GoalScheduler → /goal 命令注册 →
     * 预注册 listener。GoalTalent 的挂载不在这里，而在 {@link #onRequestAssemble}
     * （每次顶层请求挂一次，子代理委派不挂）。</p>
     */
    @Override
    public void onAttach(HarnessEngine engine) {
        if (goalEngine != null) {
            return; // 存量形态（外部 GoalEngine）：不重复装配
        }

        // ① Goal 引擎（执行通道自持：缺省 handler 注册在其内部，宿主可再覆盖）
        if (goalEngine == null) {
            goalEngine = new GoalEngine(engine,
                    budgetThresholds != null ? budgetThresholds : BudgetThresholds.defaults(),
                    stagnationThreshold, maxConsecutiveErrors, validatorEnabled,
                    workspace != null ? workspace : workspaceId);
        }

        // 宿主未提供执行者时，缺省走引擎同步回合（程序生成的 prompt 不解析命令与 @提及）。
        // 作为编底单独设置：若宿主提供了执行者，它必须优先于同步回合被考虑。
        goalEngine.setDefaultTaskExecutor(defaultTaskExecutor(engine));
        if (taskHandler != null) {
            goalEngine.addTaskExecutor(taskHandler);
        }
        // builder 阶段声明的通道化执行者：按声明顺序登记（先于 attach 后追加的运行期注册）
        for (ChannelClaim cc : pendingChannelClaims) {
            goalEngine.addTaskExecutor(cc.channel, cc.claim, cc.handler);
        }

        // ③ 调度器（宿主自备优先；显式关闭时不建——纯 JUnit/无容器宿主 JobManager 不可用）
        if (goalScheduler == null) {
            goalScheduler = providedScheduler != null ? providedScheduler
                    : (schedulerDisabled ? null : new GoalScheduler(goalEngine, workspaceId));
        }

        if (goalCommand == null) {
            // 命令只持扩展实例：pause/resume/clear 与创建全部走门面同一条路径
            goalCommand = new GoalCommand(this);
            goalCommand.setEnabled(this.isEnabled());
        }

        // ④ /goal 命令（幂等：已注册同名命令时跳过，迁移期双写安全）
        engine.getCommandRegistry().register(goalCommand);

        // ⑤ 预注册生命周期观察者
        if (listener != null) {
            goalEngine.addListener(listener);
        }

        if (goalTalent == null) {
            goalTalent = new GoalTalent(goalEngine, engine);
            goalTalent.setEnabled(this.isEnabled());
        }
    }

    @Override
    public void onRequestAssemble(HarnessEngine engine, boolean isDelegated, String agentName, ReActOptionsAmend optionsAmend) {
        // 子代理委派走临时会话，goal 是会话级能力，不挂
        if (isDelegated == false) {
            optionsAmend.talentAdd(goalTalent);
        }
    }

    /**
     * 摘除时停掉本扩展建在 {@link GoalScheduler} 上的全部 job。
     *
     * <p>JobManager 是 JVM 单例，不停则 job 会带着已废引擎的引用在全局调度器里空转；
     * 与 {@link #setEnabled} 的停用分支、{@link #stopAll()} 门面共用同一个实现。</p>
     */
    @Override
    public void onDetach(HarnessEngine engine) {
        // 直连内部字段而非 stopAll()：摘除时 scheduler 为 null（纯测试宿主）属于正常形态，
        // 走门面会打一条「没配调度器」的 warn，误导排障。
        if (goalScheduler != null) {
            goalScheduler.stopAll();
        }
    }

    /**
     * 缺省 TaskHandler：目标正文走唯一输入入口同步取回最终文本。
     *
     * <p>token 与工具证据直接取自本次响应：{@code metrics.getTotalTokens()} 与 trace 的工具调用数，
     * 不再要求宿主往会话 attrs 里写「上一轮指标」再读回来——旁路方案下 CLI/Web 因未写键而
     * 恒无工具证据，等于完成校验静默失效。</p>
     */
    private static GoalEngine.TaskHandler defaultTaskExecutor(HarnessEngine engine) {
        return (sessionId, prompt) -> {
            AgentSession session = engine.getSession(sessionId);
            if (session == null) {
                return null;
            }
            try {
                org.noear.solon.ai.agent.react.ReActResponse response =
                        engine.handle(HarnessInput.of(session, prompt)
                                        .commandAware(false)
                                        .agentAware(false)
                                        .origin(InputOrigin.channel("goal")))
                                .call();
                String text = response.getContent();
                boolean hasToolCalls = response.getTrace() != null
                        && response.getTrace().getToolCallCount() > 0;
                long tokens = response.getMetrics() != null ? response.getMetrics().getTotalTokens() : 0L;
                return RoundResult.of(hasToolCalls, tokens, text);
            } catch (Throwable e) {
                return RoundResult.ofText("error: " + (e.getMessage() != null ? e.getMessage() : e.toString()));
            }
        };
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

    /**
     * 注册认领式执行器（开箱即用宿主的主要扩展点）。
     *
     * <p>与 {@link Builder#taskExecutor} 的区别：builder 是「装配时一次性」的，
     * 本方法允许在 {@code addExtension} 之后再注册多个入口（如 CLI/Web/桌面各一个），
     * 宿主不必为一次注册去取 {@link #getGoalEngine()}。</p>
     *
     * <p><b>认领条件由宿主保证互斥</b>：按注册顺序取首个非 null 返回值，先注册者优先。
     * 当注册超过一个执行器时打一次 debug 提示，便于排查「会话被错误入口抢跑」。</p>
     */
    public GoalExtension addTaskExecutor(GoalEngine.TaskHandler handler) {
        if (goalEngine != null && handler != null) {
            goalEngine.addTaskExecutor(handler);
            if (goalEngine.hasTaskHandler()) {
                LOG.debug("Goal task executor registered; claiming order = registration order, "
                        + "ensure session-id predicates are mutually exclusive");
            }
        }
        return this;
    }

    /**
     * 注册带通道名与认领谓词的执行器（多入口宿主推荐形态）。
     *
     * <p>认领条件由内联 lambda 升级为显式谓词后，引擎可在不执行 handler 的前提下评估
     * 各入口认领边界是否互斥，重叠时告警（每组合一次）；认领日志也会带上通道名。
     * 宿主仍应把谓词集中到单一所有权类（如 {@code SessionIdOwnership}）——
     * 引擎能发现重叠，但互斥本身还得靠宿主的 ID 形态设计保证。</p>
     *
     * <pre>{@code
     * goal.addTaskExecutor("cli", SessionIdOwnership::isCli,
     *         (sessionId, prompt) -> runInjectedRound(session, prompt));
     * }</pre>
     *
     * @param channel 通道名（日志与互斥告警用，如 "cli" / "web" / "desktop"）
     * @param claim   认领谓词（sessionId → 是否本通道的会话）
     */
    public GoalExtension addTaskExecutor(String channel, Predicate<String> claim, GoalEngine.TaskHandler handler) {
        if (goalEngine != null) {
            goalEngine.addTaskExecutor(channel, claim, handler);
        }
        return this;
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

    /**
     * 等待正在执行的那一轮安全结束（停等，不忙等，也不负责中断）。
     *
     * <p>编辑 goal 的时序通常是<b>暂停 → 中断在跑轮次 → 等它真的停下 → 改写</b>：
     * 最后一步此前由宿主各写一个 {@code while (running) sleep(...)} 循环（Web 20ms×200、
     * 桌面 10ms×200，两份副本），且轮次一跑满就要白等到超时。本方法用会话
     * {@code attrs()} 上的 {@code wait/notify} 取代轮询，返回即代表可以安全改写。</p>
     *
     * <p>中断动作仍由宿主提供（{@link #pauseAndAwait} 的 {@code interrupter} 参数），
     * 因为「怎么停流」是宿主运行时通道的职责，harness 无权假设。</p>
     *
     * @param timeoutMs 最长等待毫秒；≤0 只做即时判定
     * @return true=已停稳（或无轮次在跑）；false=超时仍未停
     */
    public boolean awaitRoundStopped(AgentSession session, long timeoutMs) {
        return goalEngine == null || goalEngine.awaitRoundStopped(session, timeoutMs);
    }

    /** 按 sessionId 等待轮次安全结束（会话不存在时视为已停稳）。 */
    public boolean awaitRoundStopped(String sessionId, long timeoutMs) {
        AgentSession session = sessionOf(sessionId);
        return session == null || awaitRoundStopped(session, timeoutMs);
    }

    /**
     * 暂停 + 中断 + 停等三连（编辑前的标准时序）。
     *
     * <p>顺序固定为 {@code pause → interrupter → awaitRoundStopped}：先摘调度再中断，
     * 避免中断后 job 立刻又拉起一轮。{@code interrupter} 为 null 时只暂停与停等。</p>
     *
     * @param interrupter 宿主的「中断在跑轮次」动作（如 runtimePort.interruptSession）
     * @param timeoutMs   停等超时毫秒
     * @return true=已暂停且已停稳；false=未能暂停或未能停稳（调用方回 409/重试）
     */
    public boolean pauseAndAwait(AgentSession session, GoalState gs,
                                 Runnable interrupter, long timeoutMs) {
        if (!pause(session, gs)) {
            return false;
        }
        if (interrupter != null) {
            try {
                interrupter.run();
            } catch (Throwable e) {
                // 中断失败不阻断停等：轮次可能已在收尾，交给超时判定
                LOG.debug("Goal interrupter failed for session '{}': {}", session.getSessionId(), e.getMessage());
            }
        }
        return awaitRoundStopped(session, timeoutMs);
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
     * 创建 goal（<b>不挂调度</b>），参数对象版。
     *
     * <p>单槽校验异常（{@link IllegalStateException} 已有进行中 goal）原样透出。供首轮由宿主自己的流驱动、
     * 只需后续挂 job 的场景（桌面端）。</p>
     */
    public GoalState attach(AgentSession session, GoalSpec spec) {
        return goalEngine.attach(session, spec.getObjective(),
                spec.getMaxTokens(), spec.getMaxDurationMs(), spec.getMaxIterations());
    }

    /**
     * 创建 goal（<b>不挂调度</b>），并区分「执行正文」与「展示正文」。
     *
     * <p>桌面端首轮流由宿主自己的流驱动，且会把附件正文拼进执行正文；
     * 此前宿主先 {@code attach(执行正文)} 再 {@code setCondition(展示正文) + commit}，
     * 两次落盘之间会广播一次携带附件全文的中间态。用本方法可一次成型、只广播一次。</p>
     *
     * @param displayObjective 展示正文（UI 列表/徽章）；null 或与执行正文相同时不覆写
     */
    public GoalState attach(AgentSession session, GoalSpec spec, String displayObjective) {
        return goalEngine.attach(session, spec.getObjective(), displayObjective,
                spec.getMaxTokens(), spec.getMaxDurationMs(), spec.getMaxIterations());
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
     * 创建 goal 并立即挂上调度（{@code runNow=true}），参数对象版。
     *
     * <p>命令行与编程调用的唯一汇合点：{@code GoalCommand} 解析出 {@link GoalSpec} 后走本方法，
     * 与宿主程序调用走的是同一条路径。</p>
     */
    public GoalState attachAndSchedule(AgentSession session, GoalSpec spec) {
        GoalState gs = attach(session, spec);
        scheduleNow(session.getSessionId());
        return gs;
    }

    /**
     * 创建 goal 并立即挂上调度，参数对象版，且区分「执行正文」与「展示正文」。
     *
     * @param displayObjective 展示正文（UI 列表/徽章）；null 或与执行正文相同时不覆写
     */
    public GoalState attachAndSchedule(AgentSession session, GoalSpec spec, String displayObjective) {
        GoalState gs = attach(session, spec, displayObjective);
        scheduleNow(session.getSessionId());
        return gs;
    }

    /**
     * 最简形态：创建 goal 并挂调度，不设任何预算（= 不限制）。
     *
     * <p>等价于 {@code attachAndSchedule(session, GoalSpec.of(objective))}。</p>
     */
    public GoalState attachAndSchedule(AgentSession session, String objective) {
        return attachAndSchedule(session, GoalSpec.of(objective));
    }

    /** 最简形态：创建 goal（不挂调度），不设任何预算。 */
    public GoalState attach(AgentSession session, String objective) {
        return attach(session, GoalSpec.of(objective));
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

    /**
     * 按 sessionId 暂停（免去宿主「sessionOf → state」两步判空）。
     *
     * @return false=无会话/无 goal 或状态不允许暂停
     */
    public boolean pause(String sessionId) {
        GoalState gs = stateOf(sessionId);
        return gs != null && pause(sessionOf(sessionId), gs);
    }

    /**
     * 按 (sessionId, taskId) 暂停；taskId 不匹配时返回 false（混合任务列表的安全版）。
     */
    public boolean pause(String sessionId, String taskId) {
        GoalState gs = find(sessionId, taskId);
        return gs != null && pause(sessionOf(sessionId), gs);
    }

    /** 修改目标正文与预算（不改状态、不动调度）；无 goal 时返回 false。 */
    public boolean update(String sessionId, String objective, Long maxTokens, Long maxDurationMs) {
        GoalState gs = stateOf(sessionId);
        if (gs == null) {
            return false;
        }
        AgentSession session = sessionOf(sessionId);
        goalEngine.update(session, objective != null ? objective : gs.getCondition(),
                maxTokens != null ? maxTokens : gs.getMaxTokens(), gs.getMaxIterations());
        if (maxDurationMs != null) {
            gs.setMaxDurationMs(maxDurationMs);
            goalEngine.commit(session, gs);
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

    /** 按 sessionId 恢复（免去宿主两步判空）；无会话/无 goal 时返回 false。 */
    public boolean resume(String sessionId) {
        GoalState gs = stateOf(sessionId);
        return gs != null && resume(sessionOf(sessionId), gs);
    }

    /** 按 (sessionId, taskId) 恢复；taskId 不匹配时返回 false。 */
    public boolean resume(String sessionId, String taskId) {
        GoalState gs = find(sessionId, taskId);
        return gs != null && resume(sessionOf(sessionId), gs);
    }

    /**
     * 仅把会话挂回调度（{@code runNow=false}，等下一轮），不做状态转移。
     *
     * <p><b>恢复期专用</b>：进程重启后为快照中仍处活跃态的 goal 重新挂 job 时使用——
     * 此时不能走 {@link #resume}（那是立刻跑一轮），否则重启会对每个活跃 goal 立即发起执行。</p>
     */
    public void resumeScheduleOnly(String sessionId) {
        if (goalScheduler == null) {
            warnOnceNoScheduler("resumeScheduleOnly");
            return;
        }
        if (sessionId != null && isEnabled()) {
            goalScheduler.schedule(sessionId, false);
        }
    }

    /** 立即挂调度并马上跑一轮（桌面端 trigger 语义）；不做状态校验，调用方前置判活跃态。 */
    public void scheduleNow(String sessionId) {
        if (goalScheduler == null) {
            warnOnceNoScheduler("scheduleNow");
            return;
        }
        if (sessionId != null && isEnabled()) {
            goalScheduler.schedule(sessionId, true);
        }
    }

    /** 注销调度（不清状态）。clear/pause 已内含此步，仅在纯停调度场景直接用。 */
    public void unschedule(String sessionId) {
        if (goalScheduler == null) {
            warnOnceNoScheduler("unschedule");
            return;
        }
        if (sessionId != null) {
            goalScheduler.unschedule(sessionId);
        }
    }

    /**
     * 无调度器（scheduler(null) 显式关闭或尚未 attach）时调度方法静默 no-op 的排障门闩：
     * 「调了 scheduleNow 却没跑」的第一嫌疑就是这里，一条 warn 足够定位，不刷屏。
     * <p>只由调用方在「{@code goalScheduler == null}」分支进入——{@code sessionId == null}
     * 或扩展停用是另一类原因，不应报「没配调度器」误导排障。</p>
     */
    private void warnOnceNoScheduler(String method) {
        if (noSchedulerWarned.compareAndSet(false, true)) {
            LOG.warn("GoalExtension.{} called but no scheduler is configured "
                    + "(builder.scheduler(null) disables scheduling entirely); goal rounds will never run",
                    method);
        }
    }

    /** 无调度器告警门闩是否已触发（测试观测口；测试 classpath 无 SLF4J 后端，无法捕日志）。 */
    boolean noSchedulerWarned() {
        return noSchedulerWarned.get();
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

    /** 按 sessionId 清除（免去宿主两步判空）；无会话时静默返回。 */
    public void clear(String sessionId) {
        AgentSession session = sessionOf(sessionId);
        if (session != null) {
            clear(session);
        }
    }

    /**
     * 重启后批量恢复活跃 goal 的调度（只挂 job、不立即跑一轮）。
     *
     * <p>宿主仍负责「枚举哪些 sessionId」（目录布局是宿主存储细节）与「怎么读状态」
     * （内存会话优先、未实例化则轻读快照投影）；本方法只管 goal 侧的挂接语义：
     * 仅活跃态才挂、终态/PAUSED/BLOCKED 跳过、已调度幂等跳过，并返回实际挂接数供宿主打日志。</p>
     *
     * @param sessionIds   候选会话 ID（可含无 goal 的会话，内部自行过滤）
     * @param statusReader 状态读取器（id → {@link GoalState.Status}，无 goal 返回 null）
     * @return 实际挂上调度的会话数
     */
    public int recoverActiveSchedules(Iterable<String> sessionIds,
                                      Function<String, GoalState.Status> statusReader) {
        if (sessionIds == null || statusReader == null || goalScheduler == null || !isEnabled()) {
            return 0;
        }
        int recovered = 0;
        for (String sessionId : sessionIds) {
            if (sessionId == null || sessionId.isEmpty()) {
                continue;
            }
            GoalState.Status status;
            try {
                status = statusReader.apply(sessionId);
            } catch (Throwable e) {
                LOG.debug("Goal recovery status read failed for session '{}': {}", sessionId, e.getMessage());
                continue;
            }
            if (status == null || !status.isActive()) {
                continue;
            }
            resumeScheduleOnly(sessionId);
            recovered++;
        }
        return recovered;
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
        if (goalScheduler == null) {
            warnOnceNoScheduler("stopAll");
            return;
        }
        goalScheduler.stopAll();
    }

    // ==================== Builder ====================

    /** GoalExtension 构建器：workspaceId 必填（job 前缀），其余全有缺省。 */
    public static class Builder {
        private final String workspaceId;
        private String workspace;                                   // 缺省 = workspaceId
        private GoalEngine.TaskHandler taskHandler;                 // 缺省 = engine.handle 模板
        private final java.util.List<ChannelClaim> channelClaims = new java.util.ArrayList<>(2); // 通道化注册（装配时）
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

        /** 任务执行者：执行一轮 prompt 并返回本轮客观事实（null = 本入口不认领）。不设则用内置缺省。 */
        public Builder taskExecutor(GoalEngine.TaskHandler handler) {
            this.taskHandler = handler;
            return this;
        }

        /**
         * 通道化任务执行者（装配时形态，与 {@link #addTaskExecutor(String, Predicate, GoalEngine.TaskHandler)}
         * 运行期形态对称）。
         *
         * <p>多入口宿主既可选择在 builder 上一次性声明全部通道（单工作区进程，最简单），
         * 也可在 attach 后按需追加（入口生命周期晚于扩展装配时）。两种形态可混用，
         * 注册顺序恒为「builder 声明先于运行期追加」。</p>
         *
         * @param channel 通道名（日志与互斥告警用）
         * @param claim   认领谓词（sessionId → 是否本通道的会话）
         */
        public Builder taskExecutor(String channel, Predicate<String> claim, GoalEngine.TaskHandler handler) {
            if (handler != null && claim != null && channel != null && channel.trim().isEmpty() == false) {
                channelClaims.add(new ChannelClaim(channel.trim(), claim, handler));
            }
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
