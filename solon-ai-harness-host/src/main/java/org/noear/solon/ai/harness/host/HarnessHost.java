/*
 * Copyright 2017-2026 noear.org and authors
 * Licensed under the Apache License, Version 2.0.
 */
package org.noear.solon.ai.harness.host;

import org.noear.solon.ai.harness.HarnessResult;
import org.noear.solon.ai.agent.AgentEvent;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.react.ReActResponse;
import org.noear.solon.ai.agent.react.intercept.HITLAction;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.ai.harness.InterruptMode;
import org.noear.solon.ai.harness.RunTicket;
import org.noear.solon.ai.harness.extension.goal.GoalExecutor;
import org.noear.solon.ai.harness.extension.goal.GoalExtension;
import org.noear.solon.ai.harness.extension.goal.GoalOutcome;
import org.noear.solon.ai.harness.extension.loop.LoopExecutor;
import org.noear.solon.ai.harness.extension.loop.LoopExtension;
import org.noear.solon.ai.harness.extension.loop.LoopOutcome;
import org.noear.solon.ai.harness.extension.queue.QueueExtension;
import org.noear.solon.ai.harness.extension.queue.QueueItem;
import org.noear.solon.ai.harness.hitl.HitlCard;
import org.noear.solon.ai.harness.input.HarnessInput;
import org.noear.solon.ai.harness.input.InputOrigin;
import org.noear.solon.ai.harness.input.InputOutcome;
import org.noear.solon.ai.harness.input.InputOutcome.RejectReason;
import org.noear.solon.ai.harness.session.OwnerCheck;
import org.noear.solon.ai.harness.spi.RunDispatch;

import org.noear.solon.lang.Nullable;
import org.noear.solon.lang.Preview;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.scheduler.Schedulers;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.UnaryOperator;

/**
 * 宿主骨架（L0）：把「受理 → 开流 → 终态 → 续发积压」整套编排收进框架，宿主只实现 {@link HostSink} 投递输出。
 *
 * <pre>{@code
 * HarnessHost host = HarnessHost.builder()
 *         .engines(pool::get)                 // 租户 → 引擎（通常是同包的 {@link HarnessEnginePool}）
 *         .sink(mySink)                       // 只需实现 reply / event
 *         .build();
 *
 * Submitted r = host.submit(tenant, sessionId, text, origin);   // 使用态
 * host.decide(tenant, sessionId, callId, HITLAction.APPROVE, null, origin);   // 审批
 * host.interrupt(tenant, sessionId, InterruptMode.INTERRUPT);             // 停止
 * host.admin(tenant, principal).call(sessionId, "loop.list", LoopExtension.class,
 *         (loop, s) -> HarnessResult.ok(loop.list(sessionId)));  // 管理态（任意扩展同一写法）
 * }</pre>
 *
 * <p><b>框架代办的事</b>（使用 {@code engine.handle} 直连时宿主必须自己写对的约定）：</p>
 * <ul>
 *   <li>按会话 ID 只查不建（不存在时回执并返回 {@code session_not_found}）；多节点先查归属再加载会话；</li>
 *   <li>订阅之前领取 {@link RunTicket}；调度窗口内被取消则放弃开流；开流前失败则 {@code discard} 并收口派发；</li>
 *   <li>积压续发登记在 {@code afterRun}（运行句柄注销之后），判定口径固定为 {@code !isOccupied}；
 *       回执、排队、审批、空输入之后也会续发；</li>
 *   <li>队列续发的一轮按当初排队那条消息的来源投递，领取回调的返回值（同步 / 异步）由框架保证正确；</li>
 *   <li>审批决策与续流一次完成，过期卡提示失效、未齐备时推送剩余未决卡；</li>
 *   <li>{@code engine_closed}（引擎刚被池回收）自动换新引擎重试一次；{@code not_owner} 转交 {@link HostSink#forward}；</li>
 *   <li>goal / loop 的后台轮次也走同一套终态与续发（不推送流事件，最终文本见 {@link HostRun#getResultText()}）；
 *       首次取到引擎时先登记执行器、再恢复 goal 调度（宿主不必自己调 {@code goal.recover()}）。</li>
 * </ul>
 *
 * <p><b>适用边界</b>：本类拥有输出通道与终态去重。自带终态门、需要自定义回执与流的先后时序、
 * 或需要序号回放的宿主（如 soloncode 的 Web / 桌面端）请继续直接使用 {@code engine.handle}（L1）。</p>
 *
 * @author noear
 * @since 4.2.0
 */
@Preview("4.2.0")
public final class HarnessHost {
    private static final Logger LOG = LoggerFactory.getLogger(HarnessHost.class);

    /** 后台轮次在 goal / loop 执行器中登记的通道名 */
    public static final String EXECUTOR_CHANNEL = "host";

    private final Function<String, HarnessEngine> engines;
    private final HostSink sink;
    private final Executor executor;
    private final UnaryOperator<Runnable> wrap;
    private final AdminAuthorizer adminAuthorizer;
    private final boolean programRuns;
    private final Set<HarnessEngine> bound = Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));

    private HarnessHost(Builder b) {
        this.engines = b.engines;
        this.sink = b.sink;
        this.executor = b.executor;
        this.wrap = b.wrap;
        this.adminAuthorizer = b.adminAuthorizer;
        this.programRuns = b.programRuns;
    }

    public static Builder builder() {
        return new Builder();
    }

    // ==================== 引擎 ====================

    /**
     * 取租户的引擎（首次取到某个引擎实例时，为它的 goal / loop 登记后台轮次执行器，然后恢复 goal 调度）。
     *
     * <p>需要预热有后台工作的租户时用 {@link #warmup}，而不是绕过本类直接取引擎——否则这些引擎上的后台轮次
     * 不经过 {@link HostSink}，结束后也不会续发积压。</p>
     */
    public HarnessEngine engine(String tenant) {
        Objects.requireNonNull(tenant, "tenant");
        HarnessEngine engine = Objects.requireNonNull(engines.apply(tenant), "engine");
        if (bound.add(engine)) {
            bindProgramRuns(tenant, engine);
            recoverGoals(tenant, engine);
        }
        return engine;
    }

    /**
     * 预热：逐个取引擎（单个失败只记日志）。
     *
     * @return 成功的数量
     */
    public int warmup(Collection<String> tenants) {
        int ok = 0;
        if (tenants != null) {
            for (String tenant : tenants) {
                try {
                    engine(tenant);
                    ok++;
                } catch (Throwable e) {
                    LOG.warn("[HarnessHost] warmup failed: {}", tenant, e);
                }
            }
        }
        return ok;
    }

    // ==================== 使用态 ====================

    /**
     * 提交一条用户输入（含斜杠命令）。
     *
     * @param origin 来源（回复路由；可空）
     */
    public Submitted submit(String tenant, String sessionId, @Nullable String text, @Nullable InputOrigin origin) {
        return submit(tenant, sessionId, text, origin, null);
    }

    /**
     * 提交一条用户输入，并补充输入参数（附件、显式模型 / 子代理、宿主域属性等）。
     *
     * @param customizer 对 {@code HarnessInput.of(session, text).origin(origin)} 的补充（可空）
     */
    public Submitted submit(String tenant, String sessionId, @Nullable String text, @Nullable InputOrigin origin,
                            @Nullable Consumer<HarnessInput> customizer) {
        final InputOrigin o = origin == null ? InputOrigin.NONE : origin;
        return admit(tenant, sessionId, o, session -> {
            HarnessInput in = HarnessInput.of(session, text).origin(o);
            if (customizer != null) {
                customizer.accept(in);
            }
            return in;
        });
    }

    /**
     * 提交一条审批决策：写入决策，本批齐备时直接续流（返回 STARTED）；
     * 仍有未决卡时推送 {@link HostSink#hitl} 并返回 HITL；卡已失效时回执提示并返回 HITL。
     *
     * @param note 可选：改写工具返回值（可空）
     */
    public Submitted decide(String tenant, String sessionId, String callId, HITLAction action,
                            @Nullable String note, @Nullable InputOrigin origin) {
        Objects.requireNonNull(action, "action");
        final InputOrigin o = origin == null ? InputOrigin.NONE : origin;
        return admit(tenant, sessionId, o, session -> {
            HarnessInput in = HarnessInput.of(session, null).origin(o).hitl(action, callId).resume(true);
            if (note != null) {
                in.hitlOutput(note);
            }
            return in;
        });
    }

    /**
     * 中断会话的运行（方式见 {@link InterruptMode}：用户点「停止」用 {@code INTERRUPT}，
     * 只停当前运行、保留排队用 {@code CANCEL_STREAM}）。
     *
     * @return OK（值为「确有运行中的流被停掉」）、NOT_FOUND、NOT_OWNER
     */
    public HarnessResult<Boolean> interrupt(String tenant, String sessionId, InterruptMode mode) {
        Objects.requireNonNull(mode, "mode");
        HarnessEngine engine = engine(tenant);
        // 先查归属再加载会话：非持有节点不把会话装进内存
        OwnerCheck owner = engine.checkOwner(sessionId);
        if (owner.isOwned() == false) {
            return HarnessResult.notOwner(owner.getOwner());
        }
        AgentSession session = engine.getSessionOrNull(sessionId);
        if (session == null) {
            return HarnessResult.fail(HarnessResult.Code.NOT_FOUND, "session not found");
        }
        return engine.interrupt(session, mode);
    }

    /**
     * 立即尝试续发会话积压（会话忙时什么都不做）。{@link #submit}、运行终态与 {@link AdminGateway}
     * 已自动调用，宿主一般不需要手动调。
     */
    public void drain(String tenant, String sessionId) {
        HarnessEngine engine = engine(tenant);
        if (engine.checkOwner(sessionId).isOwned() == false) {
            return;
        }
        AgentSession session = engine.getSessionOrNull(sessionId);
        if (session != null) {
            drain(new Ctx(tenant, engine, session));
        }
    }

    // ==================== 管理态 ====================

    /** 管理视图（不带调用者；配置了 {@link AdminAuthorizer} 时以 null 主体鉴权） */
    public AdminGateway admin(String tenant) {
        return admin(tenant, null);
    }

    /** 管理视图（调用者交给 {@link AdminAuthorizer} 鉴权） */
    public AdminGateway admin(String tenant, @Nullable String principal) {
        return new AdminGateway(this, tenant, principal);
    }

    AdminAuthorizer adminAuthorizer() {
        return adminAuthorizer;
    }

    // ==================== 编排 ====================

    private Submitted admit(String tenant, String sessionId, InputOrigin origin,
                            Function<AgentSession, HarnessInput> inputOf) {
        Objects.requireNonNull(sessionId, "sessionId");
        for (int attempt = 0; ; attempt++) {
            HarnessEngine engine = engine(tenant);

            // 先查归属再加载会话：非持有节点不把会话装进内存
            OwnerCheck owner = engine.checkOwner(sessionId);
            if (owner.isOwned() == false) {
                safe(() -> sink.forward(origin, owner.getOwner()));
                return Submitted.rejected(RejectReason.NOT_OWNER);
            }

            // 只查不建：getSession 会凭空新建会话
            AgentSession session = engine.getSessionOrNull(sessionId);
            if (session == null) {
                reply(origin, sink.rejectText(RejectReason.SESSION_NOT_FOUND, null));
                return Submitted.rejected(RejectReason.SESSION_NOT_FOUND);
            }

            InputOutcome outcome = engine.handle(inputOf.apply(session));
            if (attempt == 0 && outcome.getKind() == InputOutcome.Kind.REJECTED
                    && outcome.getRejectReason() == RejectReason.ENGINE_CLOSED) {
                // 引擎刚被池回收：持有的是过期引用，换新引擎重试一次
                continue;
            }
            return route(new Ctx(tenant, engine, session), outcome, origin, false);
        }
    }

    /**
     * 结局分派（用户输入与队列续发共用）。
     *
     * @param inClaim 是否在队列领取回调里（此时外层 drainLoop 会继续领取，这里不再嵌套续发）
     */
    private Submitted route(Ctx c, InputOutcome outcome, InputOrigin origin, boolean inClaim) {
        InputOrigin route = origin;
        RunDispatch dispatch = outcome.getDispatch();
        if (dispatch != null && dispatch.getOrigin() != null && dispatch.getOrigin().isEmpty() == false) {
            route = dispatch.getOrigin();
        }

        switch (outcome.getKind()) {
            case TASK:
                return startRun(c, outcome, route, inClaim);
            case REPLY:
                if (outcome.hasReply()) {
                    reply(route, outcome.getCommandOutput());
                }
                drainUnless(c, inClaim);
                return Submitted.of(Submitted.Kind.REPLY);
            case DEFERRED:
                reply(route, sink.deferredText(outcome.getBacklogPosition()));
                // 会话可能只是「积压未清」而并不忙：立即尝试续发
                drainUnless(c, inClaim);
                return Submitted.deferred(outcome.getBacklogPosition());
            case REJECTED: {
                RejectReason reason = outcome.getRejectReason();
                if (reason == RejectReason.NOT_OWNER) {
                    final InputOrigin r = route;
                    safe(() -> sink.forward(r, outcome.getRejectDetail()));
                } else {
                    reply(route, sink.rejectText(reason, outcome.getRejectDetail()));
                }
                return Submitted.rejected(reason);
            }
            case HITL:
                if (Boolean.FALSE.equals(outcome.isHitlApplied())) {
                    reply(route, sink.hitlExpiredText());
                } else {
                    pushHitl(c.session, route);
                }
                drainUnless(c, inClaim);
                return Submitted.of(Submitted.Kind.HITL);
            case IGNORED:
            default:
                drainUnless(c, inClaim);
                return Submitted.of(Submitted.Kind.IGNORED);
        }
    }

    private Submitted startRun(Ctx c, InputOutcome outcome, InputOrigin route, boolean inClaim) {
        // ① 订阅之前（调用线程内）领取凭据：取消代数基准 + 本轮派发事务
        final RunTicket ticket = RunTicket.begin(c.session);
        final HostRun run = new HostRun(c.tenant, c.session.getSessionId(), route, outcome.getAgentName(),
                outcome.isDispatched() || ticket.dispatch() != null, null);
        final AtomicBoolean finished = new AtomicBoolean();

        // ② 续发积压登记在运行句柄注销之后（宿主 doFinally 里会话仍算忙）
        outcome.afterRun(() -> drain(c));

        safe(() -> sink.runStarted(run));
        if (outcome.hasReply()) {
            reply(route, outcome.getCommandOutput());
        }

        Runnable action = () -> {
            try {
                // ③ 调度窗口内被取消：不开流
                if (ticket.isCancelled()) {
                    outcome.discard();
                    ticket.settle(false);
                    finish(c, run, finished, null, false);
                    return;
                }

                final AtomicReference<Throwable> error = new AtomicReference<>();
                outcome.stream()
                        .doOnNext(e -> event(run, e))
                        .doOnError(error::set)
                        .doFinally(s -> {
                            ticket.settle(true); // 幂等；非程序派发时是空操作
                            finish(c, run, finished, error.get(), true);
                        })
                        .subscribe(e -> {
                        }, e -> {
                        });
            } catch (Throwable e) {
                failStart(c, outcome, ticket, run, finished, e);
            }
        };

        try {
            executor.execute(wrap.apply(action));
        } catch (Throwable e) {
            if (inClaim && ticket.dispatch() != null) {
                // 队列续发的一轮启动失败：交回领取侧回滚到队首（START_FAILED，不立即重试）
                outcome.discard();
                finish(c, run, finished, e, false);
                throw new IllegalStateException("queued run failed to start", e);
            }
            failStart(c, outcome, ticket, run, finished, e);
        }
        return Submitted.of(Submitted.Kind.STARTED);
    }

    /** 开流前失败：释放占位、回滚派发、补发终态；普通输入失败时续发积压，队列派发的失败已回队首、不立即重试 */
    private void failStart(Ctx c, InputOutcome outcome, RunTicket ticket, HostRun run, AtomicBoolean finished, Throwable e) {
        LOG.warn("[HarnessHost] run failed to start: {}", run, e);
        outcome.discard();
        boolean rolledBack = ticket.settle(false);
        finish(c, run, finished, e, false);
        if (rolledBack == false && ticket.dispatch() == null) {
            drain(c);
        }
    }

    /** 终态（恰好一次）：先推送挂起的审批卡，再 runFinished */
    private void finish(Ctx c, HostRun run, AtomicBoolean finished, @Nullable Throwable error, boolean checkHitl) {
        if (finished.compareAndSet(false, true) == false) {
            return;
        }
        if (checkHitl && error == null) {
            pushHitl(c.session, run.getOrigin());
        }
        safe(() -> sink.runFinished(run, error));
    }

    private void pushHitl(AgentSession session, InputOrigin route) {
        List<HitlCard> cards = HitlCard.undecided(session);
        if (cards.isEmpty() == false) {
            safe(() -> sink.hitl(route, cards));
        }
    }

    private void drainUnless(Ctx c, boolean inClaim) {
        if (inClaim == false) {
            drain(c);
        }
    }

    /** 续发：领取队首，在同一线程回到 handle（引擎据此识别派发轮次） */
    private void drain(Ctx c) {
        QueueExtension queue = c.engine.getExtension(QueueExtension.class).orElse(null);
        if (queue == null) {
            return;
        }
        try {
            queue.drainLoop(c.session, s -> c.engine.status(s).isOccupied() == false, claim -> {
                QueueItem item = claim.getItem();
                InputOutcome outcome = c.engine.handle(item.toInput(c.session));
                Submitted r = route(c, outcome, item.getOrigin(), true);
                // 开了流 = 异步启动（终态由 RunTicket.settle 收口）；其余结局已同步完成
                return r.getKind() != Submitted.Kind.STARTED;
            });
        } catch (Throwable e) {
            LOG.warn("[HarnessHost] drain failed: {}/{}", c.tenant, c.session.getSessionId(), e);
        }
    }

    // ==================== 后台轮次（goal / loop） ====================

    private void bindProgramRuns(String tenant, HarnessEngine engine) {
        if (programRuns == false) {
            return;
        }
        try {
            engine.getExtension(GoalExtension.class).ifPresent(goal ->
                    goal.addExecutor(EXECUTOR_CHANNEL, null, (GoalExecutor) (sid, prompt) -> {
                        ProgramResult r = runProgram(tenant, engine, sid, prompt, "goal");
                        return r.skipReason != null ? GoalOutcome.skip(r.skipReason) : GoalOutcome.done(r.response);
                    }));
            engine.getExtension(LoopExtension.class).ifPresent(loop ->
                    loop.addExecutor(EXECUTOR_CHANNEL, null, (LoopExecutor) (sid, prompt) -> {
                        ProgramResult r = runProgram(tenant, engine, sid, prompt, "loop");
                        return r.skipReason != null ? LoopOutcome.skip(r.skipReason) : LoopOutcome.done(r.response.getContent());
                    }));
        } catch (Throwable e) {
            // 插件未挂接等：保留插件自带的缺省执行器
            LOG.warn("[HarnessHost] bind program runs failed: {}", tenant, e);
        }
    }

    /** 先登记执行器再恢复：反过来，刚恢复的轮次可能走插件缺省执行器（不经 sink、不续发）。幂等 */
    private void recoverGoals(String tenant, HarnessEngine engine) {
        try {
            engine.getExtension(GoalExtension.class).ifPresent(GoalExtension::recover);
        } catch (Throwable e) {
            LOG.warn("[HarnessHost] goal recover failed: {}", tenant, e);
        }
    }

    /** 同步跑一轮后台任务（与插件缺省执行器同口径），并接入 runStarted / runFinished / 续发 */
    private ProgramResult runProgram(String tenant, HarnessEngine engine, String sessionId, String prompt, String channel)
            throws Exception {
        AgentSession session = engine.getSessionOrNull(sessionId);
        if (session == null) {
            return ProgramResult.skip("session not found");
        }
        InputOutcome outcome = engine.handle(HarnessInput.ofProgram(session, prompt, channel));
        if (outcome.getKind() != InputOutcome.Kind.TASK) {
            // 忙 / 待审批等未受理结局：本轮跳过，不计失败
            return ProgramResult.skip(outcome.getRejectReason() != null
                    ? outcome.getRejectReason().code() : "input not admitted: " + outcome.getKind());
        }

        // 同线程同步执行，没有调度窗口，不需要 RunTicket
        Ctx c = new Ctx(tenant, engine, session);
        HostRun run = new HostRun(tenant, sessionId, InputOrigin.channel(channel), outcome.getAgentName(), false, channel);
        AtomicBoolean finished = new AtomicBoolean();
        outcome.afterRun(() -> drain(c)); // call() 返回前执行（句柄已注销）

        safe(() -> sink.runStarted(run));

        Throwable error = null;
        try {
            ReActResponse response = outcome.call();
            run.setResultText(response.getContent());
            return ProgramResult.done(response);
        } catch (Exception | Error e) {
            error = e;
            throw e;
        } catch (Throwable e) {
            error = e;
            throw new IllegalStateException(e.getMessage() != null ? e.getMessage() : e.toString(), e);
        } finally {
            outcome.discard(); // 幂等：call 已释放时为空操作
            finish(c, run, finished, error, true);
        }
    }

    private static final class ProgramResult {
        final ReActResponse response;
        final String skipReason;

        private ProgramResult(ReActResponse response, String skipReason) {
            this.response = response;
            this.skipReason = skipReason;
        }

        static ProgramResult done(ReActResponse response) {
            return new ProgramResult(response, null);
        }

        static ProgramResult skip(String reason) {
            return new ProgramResult(null, reason);
        }
    }

    // ==================== 安全投递 ====================

    private void reply(InputOrigin origin, @Nullable String text) {
        if (text != null) {
            safe(() -> sink.reply(origin, text));
        }
    }

    private void event(HostRun run, AgentEvent e) {
        safe(() -> sink.event(run, e));
    }

    private static void safe(Runnable r) {
        try {
            r.run();
        } catch (Throwable e) {
            LOG.warn("[HarnessHost] sink callback failed", e);
        }
    }

    /** 一次编排的上下文 */
    static final class Ctx {
        final String tenant;
        final HarnessEngine engine;
        final AgentSession session;

        Ctx(String tenant, HarnessEngine engine, AgentSession session) {
            this.tenant = tenant;
            this.engine = engine;
            this.session = session;
        }
    }

    // ==================== Builder ====================

    /** @since 4.2.0 */
    public static final class Builder {
        private Function<String, HarnessEngine> engines;
        private HostSink sink;
        private Executor executor = r -> Schedulers.boundedElastic().schedule(r);
        private UnaryOperator<Runnable> wrap = UnaryOperator.identity();
        private AdminAuthorizer adminAuthorizer;
        private boolean programRuns = true;

        private Builder() {
        }

        /** 租户 → 引擎（通常传 {@code pool::get}；同一租户应返回同一实例，直到它被回收） */
        public Builder engines(Function<String, HarnessEngine> engines) {
            this.engines = engines;
            return this;
        }

        /** 单引擎宿主（忽略租户键） */
        public Builder engine(HarnessEngine engine) {
            Objects.requireNonNull(engine, "engine");
            this.engines = k -> engine;
            return this;
        }

        /** 输出通道（必填） */
        public Builder sink(HostSink sink) {
            this.sink = sink;
            return this;
        }

        /** 开流动作的执行器（缺省 Reactor boundedElastic；Agent 管道的同步部分在这里执行） */
        public Builder executor(Executor executor) {
            this.executor = Objects.requireNonNull(executor, "executor");
            return this;
        }

        /** 包装开流动作（注入 MDC、日志作用域、租户上下文等） */
        public Builder wrap(UnaryOperator<Runnable> wrap) {
            this.wrap = wrap == null ? UnaryOperator.identity() : wrap;
            return this;
        }

        /** 管理门面的统一鉴权（未配置时不鉴权，由控制器负责） */
        public Builder adminAuthorizer(AdminAuthorizer adminAuthorizer) {
            this.adminAuthorizer = adminAuthorizer;
            return this;
        }

        /**
         * goal / loop 后台轮次是否接入本骨架（缺省 true：经 {@link HostSink} 通知、结束后续发积压）。
         * 关闭时沿用插件自带的缺省执行器。
         */
        public Builder programRuns(boolean programRuns) {
            this.programRuns = programRuns;
            return this;
        }

        public HarnessHost build() {
            if (engines == null) {
                throw new IllegalStateException("engines (or engine) is required");
            }
            if (sink == null) {
                throw new IllegalStateException("sink is required");
            }
            return new HarnessHost(this);
        }
    }
}
