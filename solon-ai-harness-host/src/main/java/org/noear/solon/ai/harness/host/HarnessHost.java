/*
 * Copyright 2017-2026 noear.org and authors
 * Licensed under the Apache License, Version 2.0.
 */
package org.noear.solon.ai.harness.host;

import org.noear.solon.ai.agent.AgentEvent;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.react.AbsReActEvent;
import org.noear.solon.ai.agent.react.ReActTrace;
import org.noear.solon.ai.agent.react.intercept.HITLAction;
import org.noear.solon.ai.agent.react.task.ToolCallEndEvent;
import org.noear.solon.ai.agent.trace.Metrics;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.ai.harness.HarnessResult;
import org.noear.solon.ai.harness.InterruptMode;
import org.noear.solon.ai.harness.InterruptPlan;
import org.noear.solon.ai.harness.RunTicket;
import org.noear.solon.ai.harness.extension.goal.GoalExecutor;
import org.noear.solon.ai.harness.extension.goal.GoalExtension;
import org.noear.solon.ai.harness.extension.goal.GoalOutcome;
import org.noear.solon.ai.harness.extension.loop.LoopExecutor;
import org.noear.solon.ai.harness.extension.loop.LoopExtension;
import org.noear.solon.ai.harness.extension.queue.QueueExtension;
import org.noear.solon.ai.harness.extension.queue.QueueItem;
import org.noear.solon.ai.harness.extension.steer.SteerExtension;
import org.noear.solon.ai.harness.hitl.HitlCard;
import org.noear.solon.ai.harness.input.BusyPolicy;
import org.noear.solon.ai.harness.input.HarnessInput;
import org.noear.solon.ai.harness.input.InputOrigin;
import org.noear.solon.ai.harness.input.InputOutcome;
import org.noear.solon.ai.harness.input.RejectReason;
import org.noear.solon.ai.harness.session.OwnerCheck;
import org.noear.solon.ai.harness.session.SessionStatus;
import org.noear.solon.ai.harness.spi.RunDispatch;
import org.noear.solon.lang.Nullable;
import org.noear.solon.lang.Preview;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import reactor.core.publisher.SignalType;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * 宿主骨架（L0）：把「受理 → 开流 → 终态 → 续发积压」整套编排收进框架，宿主只决定输出怎么呈现。
 *
 * <p><b>三档用法</b>（按需加深，后一档不影响前一档）：</p>
 * <pre>{@code
 * HarnessHost host = HarnessHost.builder().engines(pool::get).build();   // pool：core 的 HarnessEnginePool
 * host.openSession(tenant, sid, principal);
 *
 * // ① 一个请求一轮：不写 sink
 * RunOutcome o = host.submit(tenant, sid, HarnessInput.of("hello").principal(principal))
 *                    .getRun().await(Duration.ofMinutes(5));
 * Flux<HostSignal> sse = host.stream(tenant, sid, HarnessInput.of(text).principal(principal));
 *
 * // ② 推送型（WebSocket / IM）：覆盖 HostSink 的 reply / event
 * // ③ 每轮有状态：覆盖 HostSink#open，每轮返回自己的 RunSink
 * }</pre>
 *
 * <p><b>框架代办的事</b>（直连 {@code engine.handle} 时宿主必须自己写对的约定）：</p>
 * <ul>
 *   <li>按会话 ID 只查不建；多节点先查归属再加载会话；传了主体时校验会话主体（防止用别人的 sessionId 提交）；</li>
 *   <li>订阅之前领取 {@link RunTicket}；调度窗口内被取消则放弃开流；开流前失败则 {@code discard} 并收口派发；</li>
 *   <li>每轮 {@link HostSink#open} 恰好配一次 {@link RunSink#finished}，结束方式为 COMPLETED / FAILED / CANCELLED；</li>
 *   <li>积压续发登记在 {@code afterRun}（运行句柄注销之后）；回执、排队、审批、空输入之后也会续发；
 *       队列续发的一轮按当初排队那条消息的来源投递；</li>
 *   <li>同一轮的 {@link RunSink} 回调串行（不并发）；停止就是停止：中断受理后不再投递事件、终态一定是 CANCELLED；
 *       用户停止（{@code INTERRUPT_ALL}）走两段式：先立屏障、落取消消息、再停流；</li>
 *   <li>审批决策与续流一次完成，过期卡回调 {@link HostSink#hitlExpired}、未齐备时推送剩余未决卡；</li>
 *   <li>{@code engine_closed}（引擎刚被池回收）自动换新引擎重试一次；</li>
 *   <li>goal / loop 的后台轮次与用户轮次走同一条链（推流事件、终态、续发、可被停止）；
 *       首次取到引擎时依次登记执行器、恢复 goal 调度、按 queue 积压索引续发；</li>
 *   <li>DEBUG 日志按轮次 id（{@link HostRun#getId()}）记录每轮的 open / reply / prepare / stream / cancel / finished。</li>
 * </ul>
 *
 * <p><b>与宿主的边界</b>：本类只表达「发生了什么」，不决定文案、鉴权、协议状态码与多节点转发方式。</p>
 *
 * @author noear
 * @since 4.2.0
 */
@Preview("4.2.0")
public final class HarnessHost {
    private static final Logger LOG = LoggerFactory.getLogger(HarnessHost.class);

    /** 后台轮次在 goal / loop 执行器中登记的通道名 */
    static final String EXECUTOR_CHANNEL = "host";

    private final Function<String, HarnessEngine> engines;
    private final HostSink sink;
    private final Executor executor;
    private final BiFunction<String, Runnable, Runnable> wrap;
    private final BiConsumer<String, HarnessInput> inputDecorator;
    private final boolean programRuns;
    private final Predicate<String> programFilter;
    private final Set<HarnessEngine> bound = Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));
    /** 活跃轮次（租户 + 会话 → 本轮），中断时据此把本轮标记为 CANCELLED */
    private final Map<String, ActiveRun> active = new ConcurrentHashMap<>();
    /** 受理中的输入（engine.handle 期间还没有轮次对象）：停止请求先记在这里，开出的那一轮以 CANCELLED 收尾 */
    private final Set<Admitting> admitting = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean warnedNoPrincipal = new AtomicBoolean();

    private HarnessHost(Builder b) {
        this.engines = b.engines;
        this.sink = b.sink;
        this.executor = b.executor;
        this.wrap = b.wrap;
        this.inputDecorator = b.input;
        this.programRuns = b.programRuns;
        this.programFilter = b.programFilter;
    }

    public static Builder builder() {
        return new Builder();
    }

    // ==================== 引擎（包内） ====================

    /**
     * 取租户的引擎（首次取到某个引擎实例时，为它的 goal / loop 登记后台轮次执行器，然后恢复 goal 调度）。
     *
     * <p>包内可见：绕过本类直接取引擎会让这些引擎上的后台轮次不经过 {@link HostSink}、结束后也不续发积压。</p>
     */
    HarnessEngine engine(String tenant) {
        Objects.requireNonNull(tenant, "tenant");
        HarnessEngine engine = Objects.requireNonNull(engines.apply(tenant), "engine");
        if (bound.add(engine) && programRuns) {
            // 不接管后台工作的骨架也不恢复：否则与别的入口共用引擎时，会在本入口跑掉不属于它的会话积压
            bindProgramRuns(tenant, engine);
            recoverGoals(tenant, engine);
            recoverBacklog(tenant, engine);
        }
        return engine;
    }

    /**
     * 预热：逐个取引擎（单个失败只记日志）。
     *
     * <p>重启后要恢复后台工作的租户用本方法：首次取到某个引擎时，框架依次登记后台执行器、
     * 按 goal 活跃索引恢复调度、按 queue 积压索引续发（任何入口首次用到该租户时也会做同样的事，
     * 本方法只是把它提前）。宿主的旧数据迁移没有顺序要求。</p>
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

    // ==================== 会话生命周期 ====================

    /**
     * 开通会话并写入会话主体（登录身份），一次调用完成——不给「先建后写主体」留窗口。
     *
     * @param principal 会话主体（设置后不可改）。引擎要求作用域（SERVER 模式）时必填；
     *                  单用户 / 本地部署可为 null（不写主体）
     * @return 会话实例
     * @throws IllegalArgumentException 引擎要求作用域，但没给主体
     * @throws SecurityException        该会话已有不同主体（防止把 A 的会话交给 B 继续用）
     */
    public AgentSession openSession(String tenant, String sessionId, @Nullable String principal) {
        Objects.requireNonNull(sessionId, "sessionId");
        HarnessEngine engine = engine(tenant);
        boolean hasPrincipal = principal != null && principal.isEmpty() == false;
        if (hasPrincipal == false && engine.isScopeRequired()) {
            throw new IllegalArgumentException("principal is required (the engine requires an input scope)");
        }
        AgentSession session = engine.getSession(sessionId);
        if (hasPrincipal) {
            engine.setSessionPrincipal(session, principal);
        }
        return session;
    }

    /**
     * 关闭（永久删除）会话：判忙 → 通知全部扩展（撤掉定时任务等）→ 删除存储。
     *
     * @param storageCleanup 删除存储（可空：宿主自行在返回值之后删除）
     * @return true = 已关闭或本来就不存在；false = 会话忙（有运行流 / 待审批 / 受理窗口），未做任何事
     */
    public boolean closeSession(String tenant, String sessionId, @Nullable Runnable storageCleanup) {
        Objects.requireNonNull(sessionId, "sessionId");
        HarnessEngine engine = engine(tenant);
        AgentSession session = engine.getSessionOrNull(sessionId);
        if (session == null) {
            return true;
        }
        if (engine.status(session).isOccupied()) {
            return false;
        }
        engine.removeSession(sessionId);
        if (storageCleanup != null) {
            HostSinkSupport.safe(storageCleanup);
        }
        return true;
    }

    /** 关闭会话（不删存储；宿主在返回 true 之后自行删除） */
    public boolean closeSession(String tenant, String sessionId) {
        return closeSession(tenant, sessionId, null);
    }

    // ==================== 使用态 ====================

    /**
     * 提交一条用户输入（含斜杠命令）。输入与直连 {@code engine.handle} 用的是同一个类型：
     *
     * <pre>{@code
     * host.submit(tenant, sid, HarnessInput.of(text)
     *         .origin(InputOrigin.of("im", userId, chatId, msgId))
     *         .principal(userId)                 // 校验会话主体，防止用别人的 sessionId 提交
     *         .whenBusy(BusyPolicy.STEER)        // 忙时先插话，插不进再排队（缺省 QUEUE）
     *         .selectedModel("gpt-4o").attr("effort", "high"));
     * }</pre>
     *
     * <p>输入通常不带会话（{@link HarnessInput#of(String)}）：框架先查归属、再加载会话，然后复制一份绑定上去
     * （{@link HarnessInput#copyFor}），调用方的对象不会被引擎改动，可以复用。</p>
     *
     * @param input 输入（若带了会话，其 id 必须与 {@code sessionId} 一致）
     * @throws IllegalArgumentException 输入带的会话与 {@code sessionId} 不一致
     */
    public SubmitResult submit(String tenant, String sessionId, HarnessInput input) {
        return submit(tenant, sessionId, input, null);
    }

    /**
     * 提交一条用户输入，并附加只作用于本次请求开出的那一轮的监听（在全局 sink 之后调用；输入进了积压时不会被调用）。
     *
     * @param observer 本次监听（可空）
     */
    public SubmitResult submit(String tenant, String sessionId, HarnessInput input, @Nullable RunSink observer) {
        Objects.requireNonNull(input, "input");
        if (input.getSession() != null && Objects.equals(input.getSession().getSessionId(), sessionId) == false) {
            throw new IllegalArgumentException("input is bound to another session: " + input.getSession().getSessionId());
        }
        return admit(tenant, sessionId, input, observer);
    }

    /**
     * 提交一条用户输入，并把本次请求「发生了什么」作为信号流返回（适合直接转成 SSE）。
     *
     * <p>开了流时为 {@code REPLY?} → {@code EVENT*} → {@code HITL?} → {@code FINISHED}；没开流时只有一个结局信号。
     * 下游取消（客户端断开）会停掉本轮（{@link RunHandle#cancel()}）。全局 sink 照常收到全部回调。</p>
     */
    public Flux<HostSignal> stream(String tenant, String sessionId, HarnessInput input) {
        return stream(tenant, sessionId, input, null);
    }

    /**
     * 同 {@link #stream(String, String, HarnessInput)}，并附加只作用于本次请求开出的那一轮的监听
     * （如在 {@link RunSink#prepare} 里按请求参数调整本轮、在 {@link RunSink#finished} 里按轮计费；在信号流之前调用）。
     *
     * @param observer 本次监听（可空）
     */
    public Flux<HostSignal> stream(String tenant, String sessionId, HarnessInput input, @Nullable RunSink observer) {
        Objects.requireNonNull(input, "input");
        return Flux.create(fs -> {
            // 下游取消先登记：受理期间（submit 还没返回）客户端就断开时，拿到句柄后补做取消
            final AtomicBoolean gone = new AtomicBoolean();
            final AtomicReference<RunHandle> handleRef = new AtomicReference<>();
            fs.onCancel(() -> {
                gone.set(true);
                RunHandle h = handleRef.get();
                if (h != null) {
                    h.cancel();
                }
            });

            RunSink signals = new RunSink() {
                @Override
                public void reply(String text) {
                    fs.next(HostSignal.reply(text));
                }

                @Override
                public void event(AgentEvent event) {
                    fs.next(HostSignal.event(event));
                }

                @Override
                public void finished(RunOutcome outcome) {
                    if (outcome.getHitlCards().isEmpty() == false) {
                        fs.next(HostSignal.hitl(outcome.getHitlCards()));
                    }
                    fs.next(HostSignal.finished(outcome));
                    fs.complete();
                }
            };
            SubmitResult r;
            try {
                r = submit(tenant, sessionId, input, observer == null ? signals : RunSink.compose(Arrays.asList(observer, signals)));
            } catch (Throwable e) {
                fs.error(e);
                return;
            }

            switch (r.getKind()) {
                case STARTED: {
                    RunHandle handle = r.getRun();
                    handleRef.set(handle);
                    if (gone.get()) {
                        handle.cancel(); // 幂等；两边都「先写自己、再读对方」，至少一边会取消
                    }
                    return;
                }
                case REPLY:
                    if (r.getReplyText() != null) {
                        fs.next(HostSignal.reply(r.getReplyText()));
                    }
                    break;
                case DEFERRED:
                    fs.next(HostSignal.deferred(r.getBacklogPosition()));
                    break;
                case REJECTED:
                    fs.next(HostSignal.rejected(r.getRejectReason(), r.getRejectDetail()));
                    break;
                case HITL:
                    fs.next(HostSignal.hitl(r.getHitlCards()));
                    break;
                case STEERED:
                    fs.next(HostSignal.of(HostSignal.Type.STEERED));
                    break;
                case IGNORED:
                default:
                    fs.next(HostSignal.of(HostSignal.Type.IGNORED));
                    break;
            }
            fs.complete();
        }, FluxSink.OverflowStrategy.BUFFER);
    }

    /**
     * 提交一条审批决策：写入决策，本批齐备时直接续流（返回 STARTED）；
     * 仍有未决卡时推送 {@link HostSink#hitl} 并返回 HITL；卡已失效时回调 {@link HostSink#hitlExpired} 并返回 HITL。
     *
     * @param note 可选：改写工具返回值（可空）
     */
    public SubmitResult decide(String tenant, String sessionId, String callId, HITLAction action,
                               @Nullable String note, @Nullable InputOrigin origin) {
        Objects.requireNonNull(action, "action");
        return admit(tenant, sessionId, HarnessInput.of((String) null).origin(origin).hitl(action, callId)
                .hitlOutput(note).resume(true), null);
    }

    /**
     * 用户点「停止」：清掉积压、写入取消消息、停掉正在运行的那一轮（{@code InterruptMode.INTERRUPT_ALL}）。
     * 只停本次请求开出的那一轮、保留排队用 {@link RunHandle#cancel()}；其它中断方式见 {@link SessionAdmin#interrupt}。
     *
     * <p>停止就是停止：受理之后本轮不再把事件交给宿主，终态一定是 {@link RunOutcome.End#CANCELLED}
     * （原因见 {@link RunOutcome#getCancelCause()}）。宿主不需要在调用前做任何准备。</p>
     *
     * <p>任何时刻都可以调：输入还在受理（{@code submit} 尚未返回）时，它开出的那一轮同样以 CANCELLED 收尾。</p>
     *
     * @return OK 时值为 true = 有一轮被停掉（本骨架的轮次——含还在调度窗口里、尚未开流的——会以
     *         {@code finished(CANCELLED)} 收尾），或有输入正在受理（它若开流就以 CANCELLED 收尾；
     *         若只是回执 / 排队 / 拒收，由对应的会话级回调收尾）；false = 没有在跑的轮次。另有 NOT_FOUND、NOT_OWNER
     */
    public HarnessResult<Boolean> interrupt(String tenant, String sessionId) {
        return interrupt(tenant, sessionId, InterruptMode.INTERRUPT_ALL);
    }

    /** 按指定方式中断（包内：对外经 {@link SessionAdmin#interrupt}） */
    HarnessResult<Boolean> interrupt(String tenant, String sessionId, InterruptMode mode) {
        Objects.requireNonNull(mode, "mode");
        return interrupt(tenant, sessionId, mode, causeOf(mode));
    }

    private static RunOutcome.CancelCause causeOf(InterruptMode mode) {
        switch (mode) {
            case INTERRUPT_ALL:
                return RunOutcome.CancelCause.INTERRUPT_ALL;
            case PREEMPT_STREAM:
                return RunOutcome.CancelCause.PREEMPT_STREAM;
            default:
                return RunOutcome.CancelCause.CANCEL_STREAM;
        }
    }

    private HarnessResult<Boolean> interrupt(String tenant, String sessionId, InterruptMode mode,
                                             RunOutcome.CancelCause cause) {
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
        final String k = key(tenant, sessionId);
        // 会话级停止也要停住「还在受理」的输入（腐位是为新输入让路、句柄取消只管自己那一轮，都不算）。
        // 先记受理中的标记、再读活跃轮次；与 openRun 「先登记活跃、再读标记」相对，至少一边看得到对方
        boolean pending = (mode == InterruptMode.INTERRUPT_ALL || cause == RunOutcome.CancelCause.CANCEL_STREAM)
                && markAdmitting(k, cause);
        ActiveRun run = active.get(k);

        if (mode == InterruptMode.INTERRUPT_ALL) {
            // 两段式：先落取消消息、标记本轮为 CANCELLED（用量在停流前取快照），再停流
            try (InterruptPlan plan = engine.prepareInterrupt(session)) {
                if (plan == null) {
                    return HarnessResult.ok(pending);
                }
                if (plan.isNotOwner()) {
                    return HarnessResult.notOwner(plan.getOwner());
                }
                // 先立屏障（本轮不再向宿主投递事件），再落取消消息、停流
                boolean marked = requestCancel(run, cause) || pending;
                if (plan.hasActiveRun() == false) {
                    // 无活跃流：取消代数已推进，调度窗口里的那一轮会自己放弃开流（以 CANCELLED 收尾）
                    return HarnessResult.ok(marked);
                }
                plan.writeCancelEarly();
                boolean stopped = plan.commit();
                return HarnessResult.ok(marked || stopped);
            }
        }

        boolean marked = requestCancel(run, cause) || pending;
        HarnessResult<Boolean> r = engine.interrupt(session, mode);
        boolean stopped = Boolean.TRUE.equals(r.getValue());
        if (mode == InterruptMode.PREEMPT_STREAM && stopped == false) {
            // 腾位不推进取消代数：没停到流时，窗口里的那一轮会照常跑，不能算作被取消
            if (run != null) {
                run.revokeCancel(cause);
            }
            return r;
        }
        if (r.isOk() == false) {
            return r;
        }
        return HarnessResult.ok(marked || stopped);
    }

    /**
     * 立即尝试续发会话积压（会话忙时什么都不做）。{@link #submit}、运行终态与 {@link SessionAdmin}
     * 已自动调用，宿主不需要手动调（包内可见）。
     */
    void drain(String tenant, String sessionId) {
        HarnessEngine engine = engine(tenant);
        if (engine.checkOwner(sessionId).isOwned() == false) {
            return;
        }
        AgentSession session = engine.getSessionOrNull(sessionId);
        if (session != null) {
            drainWrapped(new Ctx(tenant, engine, session));
        }
    }

    // ==================== 管理态 ====================

    /**
     * 会话管理态（控制台 / 后台 API）。鉴权不在本类：请在控制器 / 拦截器层完成后，再调用它。
     */
    public SessionAdmin admin(String tenant) {
        return new SessionAdmin(this, tenant);
    }

    // ==================== 编排 ====================

    private SubmitResult admit(String tenant, String sessionId, HarnessInput template, @Nullable RunSink observer) {
        Objects.requireNonNull(sessionId, "sessionId");
        // 从调用开始就算「受理中」：宿主在 submit 返回前发起的停止不会丢
        Admitting adm = new Admitting(key(tenant, sessionId));
        admitting.add(adm);
        try {
            return admit(tenant, sessionId, template, observer, adm);
        } finally {
            admitting.remove(adm);
        }
    }

    private SubmitResult admit(String tenant, String sessionId, HarnessInput template, @Nullable RunSink observer,
                               Admitting adm) {
        final InputOrigin origin = template.getOrigin();
        final String text = template.getText();
        for (int attempt = 0; ; attempt++) {
            HarnessEngine engine = engine(tenant);

            // 先查归属再加载会话：非持有节点不把会话装进内存
            OwnerCheck owner = engine.checkOwner(sessionId);
            if (owner.isOwned() == false) {
                final String o = owner.getOwner();
                HostInput input = HostInput.early(SubmitResult.Kind.REJECTED, tenant, sessionId, origin, null, text, null);
                HostSinkSupport.safe(() -> sink.notOwner(input, o));
                return SubmitResult.notOwner(o);
            }

            // 只查不建：getSession 会凭空新建会话
            AgentSession session = engine.getSessionOrNull(sessionId);
            if (session == null) {
                return rejectEarly(tenant, sessionId, origin, null, text, null, RejectReason.SESSION_NOT_FOUND, null);
            }

            warnIfNoPrincipal(engine, template);

            // 复制一份绑定会话：引擎会把作用域写回输入，调用方的对象保持不变（主体校验与 REJECT 由引擎在受理时完成）
            HarnessInput in = template.copyFor(session);
            decorate(tenant, in);

            if (in.getWhenBusy() == BusyPolicy.STEER) {
                SubmitResult steered = trySteer(tenant, engine, session, in);
                if (steered != null) {
                    return steered;
                }
            }

            InputOutcome outcome = engine.handle(in);
            if (attempt == 0 && outcome.getKind() == InputOutcome.Kind.REJECTED
                    && outcome.getRejectReason() == RejectReason.ENGINE_CLOSED) {
                // 引擎刚被池回收：持有的是过期引用，换新引擎重试一次
                continue;
            }
            return route(new Ctx(tenant, engine, session), outcome, in.getOrigin(), in.getText(), null, in, observer,
                    false, adm);
        }
    }

    private SubmitResult rejectEarly(String tenant, String sessionId, InputOrigin origin, @Nullable AgentSession session,
                                     @Nullable String text, @Nullable HarnessInput in, RejectReason reason,
                                     @Nullable String detail) {
        HostInput input = HostInput.early(SubmitResult.Kind.REJECTED, tenant, sessionId, origin, session, text, in);
        HostSinkSupport.safe(() -> sink.rejected(input, reason, detail));
        return SubmitResult.rejected(reason, detail);
    }

    /** 用户提交没带主体、而引擎要求作用域时只告警一次（主体校验本身在引擎受理时完成） */
    private void warnIfNoPrincipal(HarnessEngine engine, HarnessInput in) {
        if (in.isHitlDecision() || (in.getPrincipal() != null && in.getPrincipal().isEmpty() == false)) {
            return;
        }
        if (engine.isScopeRequired() && warnedNoPrincipal.compareAndSet(false, true)) {
            LOG.warn("[HarnessHost] submit without principal under scopeRequired: sessionId comes from the client, "
                    + "pass HarnessInput.principal(...) to stop one user from submitting into another user's session");
        }
    }

    /** 忙时插话：会话有运行流、输入是纯文本且不是命令、装了 steer 插件 */
    @Nullable
    private SubmitResult trySteer(String tenant, HarnessEngine engine, AgentSession session, HarnessInput in) {
        final InputOrigin origin = in.getOrigin();
        String text = in.getText();
        if (text == null || text.trim().isEmpty() || in.hasAttachments() || in.isHitlDecision() || in.isResume()) {
            return null;
        }
        SteerExtension steer = engine.getExtension(SteerExtension.class).orElse(null);
        if (steer == null || engine.resolveCommand(text) != null) {
            return null;
        }
        SessionStatus st = engine.status(session);
        if (st.isBusy() == false || st.hasPendingHitl()) {
            // 待审批时不插话：交给引擎受理，由它给出 HITL_PENDING（提示先处理审批）
            return null;
        }
        HarnessResult<?> r = steer.steer(session, text, origin);
        if (r.isOk() == false) {
            return null; // 插不进（盒满、运行刚结束等）：回落到正常受理
        }
        HostInput input = HostInput.early(SubmitResult.Kind.STEERED, tenant, session.getSessionId(), origin, session,
                text, in);
        HostSinkSupport.safe(() -> sink.steered(input));
        return SubmitResult.of(SubmitResult.Kind.STEERED);
    }

    private void decorate(String tenant, HarnessInput in) {
        if (inputDecorator != null) {
            inputDecorator.accept(tenant, in);
        }
    }

    /**
     * 结局分派（用户输入与队列续发共用）。
     *
     * @param inClaim 是否在队列领取回调里（此时外层 drainLoop 会继续领取，这里不再嵌套续发）
     */
    private SubmitResult route(Ctx c, InputOutcome outcome, InputOrigin origin, @Nullable String text,
                               @Nullable QueueItem item, @Nullable HarnessInput in, @Nullable RunSink observer,
                               boolean inClaim, @Nullable Admitting adm) {
        InputOrigin routeIn = origin;
        RunDispatch dispatch = outcome.getDispatch();
        if (dispatch != null && dispatch.getOrigin() != null && dispatch.getOrigin().isEmpty() == false) {
            routeIn = dispatch.getOrigin();
        }
        final HostInput input = HostInput.of(kindOf(outcome), c.tenant, c.session, routeIn, text, outcome, item, in);

        switch (outcome.getKind()) {
            case TASK:
                if (outcome.isBesideRunning()) {
                    // 忙时可执行命令触发了任务：会话已有流在跑，再开一条会与它共享同一 trace（新流立即空转结束）
                    outcome.discard();
                    HostSinkSupport.safe(() -> sink.rejected(input, RejectReason.BUSY, "another run is in progress"));
                    return SubmitResult.rejected(RejectReason.BUSY, "another run is in progress");
                }
                HostSinkSupport.safe(() -> sink.admitted(input));
                return startRun(c, outcome, input, observer, inClaim, adm);
            case REPLY: {
                HostSinkSupport.safe(() -> sink.admitted(input));
                final String out = outcome.getCommandOutput();
                if (out != null) {
                    HostSinkSupport.safe(() -> sink.reply(input, out));
                }
                drainUnless(c, inClaim);
                return SubmitResult.reply(out);
            }
            case DEFERRED: {
                final int position = outcome.getBacklogPosition();
                HostSinkSupport.safe(() -> sink.deferred(input, position));
                // 会话可能只是「积压未清」而并不忙：立即尝试续发
                drainUnless(c, inClaim);
                return SubmitResult.deferred(position);
            }
            case REJECTED: {
                RejectReason reason = outcome.getRejectReason();
                final String detail = outcome.getRejectDetail();
                if (reason == RejectReason.NOT_OWNER) {
                    HostSinkSupport.safe(() -> sink.notOwner(input, detail));
                    return SubmitResult.notOwner(detail);
                }
                HostSinkSupport.safe(() -> sink.rejected(input, reason, detail));
                return SubmitResult.rejected(reason, detail);
            }
            case HITL: {
                HostSinkSupport.safe(() -> sink.admitted(input));
                List<HitlCard> cards = Collections.emptyList();
                if (Boolean.FALSE.equals(outcome.isHitlApplied())) {
                    HostSinkSupport.safe(() -> sink.hitlExpired(input));
                } else {
                    cards = pushHitl(c.session, input);
                }
                drainUnless(c, inClaim);
                return SubmitResult.hitl(cards);
            }
            case IGNORED:
            default:
                HostSinkSupport.safe(() -> sink.admitted(input));
                drainUnless(c, inClaim);
                return SubmitResult.of(SubmitResult.Kind.IGNORED);
        }
    }

    /**
     * 引擎结局换算为宿主结果类型（与 {@link #route} 的分支一一对应，保证回调里的 kind 与 submit 返回值一致）。
     */
    private static SubmitResult.Kind kindOf(InputOutcome outcome) {
        switch (outcome.getKind()) {
            case TASK:
                // 忙时可执行命令触发了任务：不开流，按 BUSY 拒绝
                return outcome.isBesideRunning() ? SubmitResult.Kind.REJECTED : SubmitResult.Kind.STARTED;
            case REPLY:
                return SubmitResult.Kind.REPLY;
            case HITL:
                return SubmitResult.Kind.HITL;
            case DEFERRED:
                return SubmitResult.Kind.DEFERRED;
            case REJECTED:
                return SubmitResult.Kind.REJECTED;
            case IGNORED:
            default:
                return SubmitResult.Kind.IGNORED;
        }
    }

    /** 打开本轮：领凭据、建通道、登记续发与活跃表；返回的轮次尚未开流 */
    private ActiveRun openRun(Ctx c, InputOutcome outcome, HostInput input, @Nullable String programChannel,
                              @Nullable RunSink observer, @Nullable Admitting adm) {
        // 订阅之前（调用线程内）领取凭据：取消代数基准 + 本轮派发事务
        final RunTicket ticket = RunTicket.begin(c.session);
        final HostRun run = new HostRun(input, c.session, outcome.getAgentName(), outcome.getModelName(), programChannel);

        List<RunSink> sinks = new ArrayList<>(2);
        try {
            RunSink s = sink.open(run);
            sinks.add(s == null ? RunSink.NONE : s);
        } catch (Throwable e) {
            LOG.warn("[HarnessHost] sink.open failed: {}", run, e);
            sinks.add(RunSink.NONE);
        }
        if (observer != null) {
            sinks.add(observer);
        }

        final ActiveRun ar = new ActiveRun(c, run, ticket, outcome, RunSink.compose(sinks));
        ar.handle = new RunHandle(run, () -> cancelRun(ar));
        active.put(ar.key, ar);
        // 受理期间已有停止请求：先登记活跃、再读标记（与 interrupt 的顺序相对），本轮在执行线程上放弃开流
        RunOutcome.CancelCause early = adm == null ? null : adm.cause;
        if (early != null) {
            requestCancel(ar, early);
        }

        // 续发积压登记在运行句柄注销之后（宿主 doFinally 里会话仍算忙）
        outcome.afterRun(() -> drainWrapped(c));
        trace(ar, "open");
        return ar;
    }

    private SubmitResult startRun(Ctx c, InputOutcome outcome, HostInput input, @Nullable RunSink observer,
                                  boolean inClaim, @Nullable Admitting adm) {
        final ActiveRun ar = openRun(c, outcome, input, null, observer, adm);
        try {
            executor.execute(wrap.apply(c.tenant, () -> runStream(ar)));
        } catch (Throwable e) {
            if (inClaim && ar.ticket.dispatch() != null) {
                // 队列续发的一轮启动失败：交回领取侧回滚到队首（START_FAILED，不立即重试）
                outcome.discard();
                finish(ar, RunOutcome.End.FAILED, e, false);
                throw new IllegalStateException("queued run failed to start", e);
            }
            failStart(ar, e);
        }
        return SubmitResult.started(ar.handle);
    }

    /** 执行线程上：回执 → 窗口检查 → prepare → 开流（同一轮的 RunSink 回调由此串行展开） */
    private void runStream(ActiveRun ar) {
        try {
            // 命令已经执行：回执无论本轮是否随后被取消都要发出
            if (ar.outcome.hasReply()) {
                final String out = ar.outcome.getCommandOutput();
                trace(ar, "reply");
                HostSinkSupport.safe(() -> ar.sink.reply(out));
            }

            // 调度窗口内被取消：不开流
            if (cancelBeforeOpen(ar)) {
                return;
            }
            trace(ar, "prepare");

            ar.sink.prepare(new RunPrep(ar.run, ar.ctx.engine, ar.outcome.getRequest(), ar.outcome.getPrompt(),
                    ar.outcome.getModelName()));

            // prepare 期间被取消（宿主的准备动作可能耗时）：同样不开流
            if (cancelBeforeOpen(ar)) {
                return;
            }

            ar.openedAt = System.currentTimeMillis();
            ar.streamOpened = true;
            trace(ar, "stream");
            ar.outcome.stream()
                    .doOnNext(e -> {
                        ar.observe(e);
                        // 取消屏障：中断受理之后不再把事件交给宿主（用量仍照常统计）
                        if (ar.cancelCause == null) {
                            HostSinkSupport.safe(() -> ar.sink.event(e));
                        }
                    })
                    .doOnError(ar.error::set)
                    .doFinally(s -> {
                        ar.ticket.settle(true); // 幂等；非程序派发时是空操作
                        finish(ar, endOf(ar, s), ar.error.get(), true);
                    })
                    .subscribe(e -> {
                    }, e -> {
                    });
        } catch (Throwable e) {
            failStart(ar, e);
        }
    }

    private boolean cancelBeforeOpen(ActiveRun ar) {
        if (ar.ticket.isCancelled() == false && ar.cancelCause == null) {
            return false;
        }
        ar.outcome.discard();
        ar.ticket.settle(false);
        finish(ar, RunOutcome.End.CANCELLED, null, false);
        if (ar.ticket.dispatch() == null) {
            // 只停当前、保留排队（CANCEL_STREAM）时积压还在：未开流就没有 afterRun，这里补一次续发
            drainWrapped(ar.ctx);
        }
        return true;
    }

    private static RunOutcome.End endOf(ActiveRun ar, SignalType signal) {
        if (ar.cancelCause != null || signal == SignalType.CANCEL || ar.ticket.isCancelled()) {
            return RunOutcome.End.CANCELLED;
        }
        return ar.error.get() != null ? RunOutcome.End.FAILED : RunOutcome.End.COMPLETED;
    }

    /** 开流前失败：释放占位、回滚派发、补发终态；普通输入失败时续发积压，队列派发的失败已回队首、不立即重试 */
    private void failStart(ActiveRun ar, Throwable e) {
        LOG.warn("[HarnessHost] run failed to start: {}", ar.run, e);
        ar.outcome.discard();
        boolean rolledBack = ar.ticket.settle(false);
        finish(ar, RunOutcome.End.FAILED, e, false);
        if (rolledBack == false && ar.ticket.dispatch() == null) {
            drainWrapped(ar.ctx);
        }
    }

    /** 终态（恰好一次）：先推送挂起的审批卡，再 finished。取消已受理时结局一律为 CANCELLED */
    private void finish(ActiveRun ar, RunOutcome.End end, @Nullable Throwable error, boolean checkHitl) {
        RunOutcome.CancelCause cause;
        synchronized (ar) {
            if (ar.finished) {
                return;
            }
            ar.finished = true;
            cause = ar.cancelCause;
        }
        if (cause != null) {
            end = RunOutcome.End.CANCELLED;
        }
        active.remove(ar.key, ar);
        List<HitlCard> cards = null;
        if (checkHitl && end == RunOutcome.End.COMPLETED) {
            cards = pushHitl(ar.ctx.session, ar.run.getInput());
        }
        RunUsage usage = ar.cancelUsage != null ? ar.cancelUsage : ar.usage();
        RunOutcome outcome = new RunOutcome(end, cause, end == RunOutcome.End.FAILED ? error : null, ar.resultText(),
                usage, ar.streamOpened, ar.isAbnormal(), cards);
        trace(ar, "finished " + outcome);
        HostSinkSupport.safe(() -> ar.sink.finished(outcome));
        ar.handle.complete(outcome);
    }

    /** 给本会话「受理中」的输入记上停止请求（它若开流，就在执行线程上以 CANCELLED 收尾） */
    private boolean markAdmitting(String key, RunOutcome.CancelCause cause) {
        boolean marked = false;
        for (Admitting a : admitting) {
            if (a.key.equals(key)) {
                if (a.cause == null) {
                    a.cause = cause;
                }
                marked = true;
            }
        }
        return marked;
    }

    /** 登记取消（本轮尚未结束时）：之后事件不再投递、终态一定是 CANCELLED。已结束或没有轮次时返回 false */
    private static boolean requestCancel(@Nullable ActiveRun ar, RunOutcome.CancelCause cause) {
        if (ar == null) {
            return false;
        }
        boolean marked;
        synchronized (ar) {
            marked = ar.finished == false;
            if (marked && ar.cancelCause == null) {
                ar.cancelUsage = ar.usage();
                ar.cancelCause = cause;
            }
        }
        if (marked) {
            trace(ar, "cancel " + cause);
        }
        return marked;
    }

    /** {@link RunHandle#cancel()}：只停仍是本会话当前轮次的那一轮 */
    private void cancelRun(ActiveRun ar) {
        if (active.get(ar.key) == ar) {
            interrupt(ar.ctx.tenant, ar.ctx.session.getSessionId(), InterruptMode.CANCEL_STREAM,
                    RunOutcome.CancelCause.HANDLE);
        }
    }

    /** 生命周期跟踪（DEBUG）：一轮一行一步，按轮次 id 串起来 */
    private static void trace(ActiveRun ar, String step) {
        if (LOG.isDebugEnabled()) {
            LOG.debug("[HarnessHost] {} {}", ar.run, step);
        }
    }

    private List<HitlCard> pushHitl(AgentSession session, HostInput input) {
        List<HitlCard> cards = HitlCard.undecided(session);
        if (cards.isEmpty() == false) {
            HostSinkSupport.safe(() -> sink.hitl(input, cards));
        }
        return cards;
    }

    private void drainUnless(Ctx c, boolean inClaim) {
        if (inClaim == false) {
            drainWrapped(c);
        }
    }

    /** 续发也经过 wrap（日志作用域、租户上下文）；在当前线程执行 */
    private void drainWrapped(Ctx c) {
        try {
            wrap.apply(c.tenant, () -> drain(c)).run();
        } catch (Throwable e) {
            LOG.warn("[HarnessHost] drain failed: {}/{}", c.tenant, c.session.getSessionId(), e);
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
                HarnessInput in = item.toInput(c.session);
                decorate(c.tenant, in);
                // 续发的受理窗口与用户提交同一口径：期间的停止请求不丢
                Admitting adm = new Admitting(key(c.tenant, c.session.getSessionId()));
                admitting.add(adm);
                try {
                    InputOutcome outcome = c.engine.handle(in);
                    SubmitResult r = route(c, outcome, item.getOrigin(), item.getText(), item, in, null, true, adm);
                    // 开了流 = 异步启动（终态由 RunTicket.settle 收口）；其余结局已同步完成
                    return r.getKind() != SubmitResult.Kind.STARTED;
                } finally {
                    admitting.remove(adm);
                }
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
                    goal.addExecutor(EXECUTOR_CHANNEL, programFilter, (GoalExecutor) (sid, prompt) ->
                            runProgram(tenant, engine, sid, prompt, "goal").toGoalOutcome()));
            engine.getExtension(LoopExtension.class).ifPresent(loop ->
                    loop.addExecutor(EXECUTOR_CHANNEL, programFilter, (LoopExecutor) (sid, prompt) ->
                            runProgram(tenant, engine, sid, prompt, "loop").toLoopOutcome()));
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

    /**
     * 按 queue 积压索引续发（进程重启、引擎被池回收后重建都走这里）。只续发后台轮次过滤命中的会话
     * （同一引擎上挂了多个宿主时，各自恢复各自的会话）；非持有节点在 drain 里被归属检查挡下。
     */
    private void recoverBacklog(String tenant, HarnessEngine engine) {
        try {
            QueueExtension queue = engine.getExtension(QueueExtension.class).orElse(null);
            if (queue == null) {
                return;
            }
            for (String sessionId : queue.pendingSessions()) {
                if (programFilter != null && programFilter.test(sessionId) == false) {
                    continue;
                }
                if (engine.checkOwner(sessionId).isOwned() == false) {
                    continue;
                }
                AgentSession session = engine.getSessionOrNull(sessionId);
                if (session != null) {
                    drainWrapped(new Ctx(tenant, engine, session));
                }
            }
        } catch (Throwable e) {
            LOG.warn("[HarnessHost] backlog recover failed: {}", tenant, e);
        }
    }

    /**
     * 跑一轮后台任务：与用户轮次同一条链（open → prepare → event → finished，可被停止），
     * 在调度线程上阻塞到本轮结束。
     */
    private ProgramResult runProgram(String tenant, HarnessEngine engine, String sessionId, String prompt, String channel)
            throws InterruptedException {
        AgentSession session = engine.getSessionOrNull(sessionId);
        if (session == null) {
            return ProgramResult.skip("session not found");
        }
        HarnessInput in = HarnessInput.ofProgram(session, prompt, channel);
        decorate(tenant, in);
        InputOutcome outcome = engine.handle(in);
        if (outcome.getKind() != InputOutcome.Kind.TASK) {
            // 忙 / 待审批等未受理结局：本轮跳过，不计失败
            return ProgramResult.skip(outcome.getRejectReason() != null
                    ? outcome.getRejectReason().code() : "input not admitted: " + outcome.getKind());
        }

        Ctx c = new Ctx(tenant, engine, session);
        HostInput input = HostInput.of(SubmitResult.Kind.STARTED, tenant, session, InputOrigin.channel(channel), prompt,
                outcome, null, in);
        ActiveRun ar = openRun(c, outcome, input, channel, null, null);
        try {
            wrap.apply(tenant, () -> runStream(ar)).run();
        } catch (Throwable e) {
            failStart(ar, e);
        }

        try {
            return ProgramResult.of(ar.handle.future().get(), ar.evidenceCalls.get() > 0);
        } catch (InterruptedException e) {
            ar.handle.cancel();
            throw e;
        } catch (ExecutionException e) {
            return ProgramResult.failed(e.getCause());
        }
    }

    // ==================== 内部 ====================

    private static String key(String tenant, String sessionId) {
        return tenant + '\u0000' + sessionId;
    }

    /** 一条受理中的输入（engine.handle 期间）：停止请求先记在这里，开出的轮次在 openRun 里读取 */
    private static final class Admitting {
        final String key;
        volatile RunOutcome.CancelCause cause;

        Admitting(String key) {
            this.key = key;
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

    /** 一轮运行的内部状态 */
    private static final class ActiveRun {
        final Ctx ctx;
        final HostRun run;
        final RunTicket ticket;
        final InputOutcome outcome;
        final RunSink sink;
        final String key;
        final AtomicReference<Throwable> error = new AtomicReference<>();
        final AtomicInteger toolCalls = new AtomicInteger();
        final AtomicInteger evidenceCalls = new AtomicInteger();
        RunHandle handle;
        /** 终态已认领（与 cancelCause 一起在 synchronized(this) 下读写） */
        boolean finished;
        /** 取消原因（非空 = 取消已受理；事件屏障无锁读） */
        volatile RunOutcome.CancelCause cancelCause;
        volatile RunUsage cancelUsage;
        volatile boolean streamOpened;
        volatile long openedAt;
        volatile ReActTrace lastTrace;

        ActiveRun(Ctx ctx, HostRun run, RunTicket ticket, InputOutcome outcome, RunSink sink) {
            this.ctx = ctx;
            this.run = run;
            this.ticket = ticket;
            this.outcome = outcome;
            this.sink = sink;
            this.key = key(ctx.tenant, ctx.session.getSessionId());
        }

        /** 撤回取消（腾位没停到流：窗口里的这一轮照常跑） */
        synchronized void revokeCancel(RunOutcome.CancelCause cause) {
            if (finished == false && cancelCause == cause) {
                cancelCause = null;
                cancelUsage = null;
            }
        }

        void observe(AgentEvent e) {
            if (e instanceof AbsReActEvent) {
                ReActTrace trace = ((AbsReActEvent) e).getTrace();
                if (trace != null) {
                    lastTrace = trace;
                }
            }
            if (e instanceof ToolCallEndEvent) {
                ToolCallEndEvent end = (ToolCallEndEvent) e;
                if (end.isError() == false) {
                    toolCalls.incrementAndGet();
                    if (GoalOutcome.isEvidenceTool(end.getToolName())) {
                        evidenceCalls.incrementAndGet();
                    }
                }
            }
        }

        RunUsage usage() {
            ReActTrace trace = lastTrace;
            Metrics m = trace == null ? null : trace.getMetrics();
            long elapsed = streamOpened ? Math.max(0L, System.currentTimeMillis() - openedAt) : 0L;
            if (m == null) {
                return new RunUsage(0L, 0L, 0L, toolCalls.get(), elapsed);
            }
            return new RunUsage(m.getTotalTokens(), m.getPromptTokens(), m.getCompletionTokens(), toolCalls.get(), elapsed);
        }

        @Nullable
        String resultText() {
            ReActTrace trace = lastTrace;
            return trace == null ? null : trace.getFinalAnswer();
        }

        boolean isAbnormal() {
            ReActTrace trace = lastTrace;
            return trace != null && trace.isAbnormal();
        }
    }

    // ==================== Builder ====================

    /** @since 4.2.0 */
    public static final class Builder {
        private Function<String, HarnessEngine> engines;
        private HostSink sink = new HostSink() {
        };
        private Executor executor = r -> Schedulers.boundedElastic().schedule(r);
        private BiFunction<String, Runnable, Runnable> wrap = (tenant, r) -> r;
        private BiConsumer<String, HarnessInput> input;
        private boolean programRuns = true;
        private Predicate<String> programFilter;

        private Builder() {
        }

        /**
         * 租户 → 引擎（通常传 core 的 {@code HarnessEnginePool::get}；同一租户应返回同一实例，直到它被回收）。
         *
         * <p>这是唯一的引擎入口：多租户共用一个引擎会让不同租户的会话互相可见，而那种写法不会报错。
         * 单租户部署同样应当给一个返回常量引擎的函数。</p>
         */
        public Builder engines(Function<String, HarnessEngine> engines) {
            this.engines = engines;
            return this;
        }

        /** 输出通道（可选：不传则用缺省 sink，事件只记日志） */
        public Builder sink(HostSink sink) {
            this.sink = sink == null ? new HostSink() {
            } : sink;
            return this;
        }

        /** 开流动作的执行器（缺省 Reactor boundedElastic；Agent 管道的同步部分在这里执行） */
        public Builder executor(Executor executor) {
            this.executor = Objects.requireNonNull(executor, "executor");
            return this;
        }

        /**
         * 包装框架代跑的动作（开流、续发、后台轮次）：注入 MDC、日志作用域、租户上下文等。
         *
         * @param wrap (租户, 动作) → 包装后的动作
         */
        public Builder wrap(BiFunction<String, Runnable, Runnable> wrap) {
            this.wrap = wrap == null ? (tenant, r) -> r : wrap;
            return this;
        }

        /**
         * 统一补充每条输入（用户提交、审批决策、队列续发、后台轮次四处都经过它），
         * 如统一的 {@code commandContextFactory}、租户级属性。单次提交的参数直接写在交给 {@code submit} 的 {@link HarnessInput} 上。
         *
         * @param input (租户, 输入) → 就地补充
         */
        public Builder input(BiConsumer<String, HarnessInput> input) {
            this.input = input;
            return this;
        }

        /**
         * goal / loop 后台轮次是否接入本骨架（缺省 true：与用户轮次同一条链）。关闭时沿用插件自带的缺省执行器，
         * 首次绑定引擎时也不恢复 goal、不续发积压（那些归接管后台工作的入口）。
         */
        public Builder programRuns(boolean programRuns) {
            this.programRuns = programRuns;
            return this;
        }

        /**
         * 只接管部分会话的后台工作（同一引擎上挂了多个宿主时用）：goal / loop 轮次不命中的交给后注册的执行器
         * 或插件缺省执行器；首次绑定引擎时也只续发命中会话的积压。
         *
         * @param sessionFilter sessionId → 是否由本骨架执行；null = 全部
         */
        public Builder programRuns(@Nullable Predicate<String> sessionFilter) {
            this.programRuns = true;
            this.programFilter = sessionFilter;
            return this;
        }

        public HarnessHost build() {
            if (engines == null) {
                throw new IllegalStateException("engines is required (a tenant -> engine function; "
                        + "use 'k -> engine' for a single-tenant deployment)");
            }
            return new HarnessHost(this);
        }
    }
}
