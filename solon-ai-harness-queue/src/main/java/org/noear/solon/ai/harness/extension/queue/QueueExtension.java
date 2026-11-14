/*
 * Copyright 2017-2026 noear.org and authors
 * Licensed under the Apache License, Version 2.0.
 */
package org.noear.solon.ai.harness.extension.queue;

import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.harness.AbsHarnessExtension;
import org.noear.solon.ai.harness.extension.queue.i18n.QueueI18n;
import org.noear.solon.ai.harness.extension.queue.i18n.QueueI18nKeys;
import org.noear.solon.flow.FlowContext;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.ai.harness.HarnessResult;
import org.noear.solon.ai.harness.input.HarnessInput;
import org.noear.solon.ai.harness.input.InputOrigin;
import org.noear.solon.ai.harness.spi.InputBacklog;
import org.noear.solon.ai.harness.session.OwnerCheck;
import org.noear.solon.ai.harness.session.SessionStatus;
import org.noear.solon.lang.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.IntSupplier;
import java.util.function.Predicate;

/**
 * Queue 一键装配扩展 —— queue 能力从头到尾只接触这一个类。
 *
 * <p>{@code engine.addExtension(new QueueExtension())} 一步完成：注册 /queue 命令
 * （幂等）+ 提供队列操作门面。写操作统一返回 {@link HarnessResult}（失败用结果码表达，不用哨兵值）。</p>
 *
 * <p>装配后宿主只需掌握一个时机：任务终态续发。首选 {@link #drainLoop}（自动套领取循环 +
 * 三态终止），单条控制用 {@link #claimNext}；二者都把「一次领取」封成 {@link QueueClaim}，
 * 代际快照与终态收口归 harness（handler 返回 true 时内核当场收口，异步启动时由流终态的 {@code RunTicket#settle} 收口）。
 * 重启恢复用 {@link #recover}（按积压索引逐个续发）。</p>
 *
 * <p>历史上曾有一组「异步 drain + 宿主自管代际」的接口
 * （{@code drainOne(runner, false)} / {@code ack} / {@code generation} /
 * {@code isGenerationActive} / {@code requeueFront}）：宿主要在终态<b>现取</b>代际，
 * 而 {@code cancelPending} 会递代，现取必然拿到新代际 → 代际防线恒真 →
 * 被用户 Stop 的任务会重新入队继续执行。这些方法已随 {@link QueueClaim} 落地全部移除，
 * 最后一条布尔型同步变体 {@code drainOne(runner)} 也已删除：同步语义由宿主在
 * {@code claimNext} 的 handler 返回 true 表达，
 * 队列执行从此只剩一条领取路径。</p>
 *
 * <p><b>与核心的协作</b>（核心不认识队列，只认契约）：实现能力接口 {@link InputBacklog}
 * （引擎忙态受理与先进先出、空闲判定、steer 残留转排队都经它）；覆写 {@link #contributeSessionStatus} 贡献
 * 自有事实键 {@link #STATUS_PENDING}；
 * 覆写 {@link #onSessionInterrupt}（用户停止时先清队列再停流）；派发事务登记在核心槽位 {@code RunDispatch}。
 * 队列存在会话属性里，随会话一起消失；会话之外只有积压索引（{@link #INDEX_PATH}），由 {@link #onSessionRemove} 注销。</p>
 *
 * <p><b>实例上有什么</b>：队列的事实都在会话上；本实例只持有引擎与索引目录。{@code /queue} 命令与门面都按挂接的那个引擎
 * 维护索引，所以一个实例同一时刻只服务一个运行中的引擎（见 {@link #onAttach}）。本扩展不覆写 {@code isQuiescent}：
 * 积压在会话快照与索引里，引擎回收不会丢，重建时的恢复会续发；卡住的积压不该挡住回收。</p>
 *
 * @author noear
 * @since 3.9.4
 */
public class QueueExtension extends AbsHarnessExtension implements InputBacklog {
    private static final Logger LOG = LoggerFactory.getLogger(QueueExtension.class);

    /**
     * 会话状态事实：待执行的排队任务数（不含正在执行的那条；= {@link #pendingSize}）。
     *
     * <p>经 {@code engine.status(session).get(QueueExtension.STATUS_PENDING)} 读取，与运行流 / HITL / 插话等事实
     * 一次读全。事实口径：停用不影响（停用前已入队的任务仍在队列里）。内核不读它——内核的受理判定走
     * {@link #hasPending}。</p>
     *
     * @since 4.2.0
     */
    public static final SessionStatus.Key<Integer> STATUS_PENDING = SessionStatus.Key.count("queue.pending");

    private final Limits limits;
    private final QueueCommand queueCommand;

    /** 缺省上限（20 条 / 4096 字符）；需要调整用 {@link #builder()} */
    public QueueExtension() {
        this(Limits.DEFAULT);
    }

    private QueueExtension(Limits limits) {
        this.limits = limits;
        // 命令写队列也经本扩展维护积压索引（与门面同一口径）
        this.queueCommand = new QueueCommand(limits, this);
    }

    /** @since 4.2.0 */
    public static Builder builder() {
        return new Builder();
    }

    /** 本实例的队列容量上限 @since 4.2.0 */
    public int getMaxSize() {
        return limits.maxSize;
    }

    /** 本实例的单条文本上限 @since 4.2.0 */
    public int getMaxTextLength() {
        return limits.maxTextLength;
    }

    /** 实例级上限（不可变） */
    static final class Limits {
        static final Limits DEFAULT = new Limits(QueueStore.MAX_QUEUE_SIZE, QueueStore.MAX_TEXT_LENGTH);
        final int maxSize;
        final int maxTextLength;

        Limits(int maxSize, int maxTextLength) {
            this.maxSize = maxSize;
            this.maxTextLength = maxTextLength;
        }
    }

    /** @since 4.2.0 */
    public static final class Builder {
        private int maxSize = QueueStore.MAX_QUEUE_SIZE;
        private int maxTextLength = QueueStore.MAX_TEXT_LENGTH;

        private Builder() {
        }

        /** 每会话待执行条数上限（1 ~ 10000，缺省 20） */
        public Builder maxSize(int maxSize) {
            if (maxSize < 1 || maxSize > QueueStore.HARD_MAX_QUEUE_SIZE) {
                throw new IllegalArgumentException("maxSize must be in [1, " + QueueStore.HARD_MAX_QUEUE_SIZE + "]");
            }
            this.maxSize = maxSize;
            return this;
        }

        /** 单条文本字符上限（1 ~ 1000000，缺省 4096） */
        public Builder maxTextLength(int maxTextLength) {
            if (maxTextLength < 1 || maxTextLength > QueueStore.HARD_MAX_TEXT_LENGTH) {
                throw new IllegalArgumentException("maxTextLength must be in [1, " + QueueStore.HARD_MAX_TEXT_LENGTH + "]");
            }
            this.maxTextLength = maxTextLength;
            return this;
        }

        public QueueExtension build() {
            return new QueueExtension(new Limits(maxSize, maxTextLength));
        }
    }

    /** 积压索引目录（状态来源下；每个可能有积压的会话一个空标记文件） @since 4.2.0 */
    public static final String INDEX_PATH = "queue/pending";

    private volatile HarnessEngine engine;
    private volatile QueueIndex index;

    /**
     * 挂到引擎上：注册 /queue 命令，引擎有状态来源时建立积压索引。
     *
     * @throws IllegalStateException 本实例已绑在另一个未关闭的引擎上（命令与门面会把会话的标记写进最后挂接的那个引擎的状态来源）；
     *                               前一个引擎关闭后可以再挂（如引擎池回收后重建）
     */
    @Override
    public void onAttach(HarnessEngine engine) {
        bindEngine(engine);
        this.engine = engine;
        this.index = engine.getStateSource() == null ? null : new QueueIndex(engine.getStateSource(), INDEX_PATH);
        engine.getCommandRegistry().register(queueCommand);
    }

    /** 挂接的引擎已关闭：不再领取、续发与恢复（也不查归属——查归属会重新认领 shutdown 刚释放的租约） */
    private boolean isEngineClosed() {
        HarnessEngine e = engine;
        return e != null && e.isClosed();
    }

    /**
     * 重启恢复：按积压索引逐个交给 {@code drain} 续发（进程重启、引擎被池回收后重建时各调一次；
     * L0 的 {@code HarnessHost} 首次取到引擎时自动调用）。
     *
     * <p>逐条处理，死标记在同一遍里清掉（运行期没删成的标记靠它收敛，索引不随历史增长）：</p>
     * <ul>
     *   <li>过滤不命中：跳过，标记原样留给负责它的入口；</li>
     *   <li>会话不归本节点：跳过（先查归属，再加载会话）；</li>
     *   <li>会话已不存在（存储层清理、没经 {@code engine.removeSession} 删除等）：注销标记；</li>
     *   <li>其余交给 {@code drain}：队列其实已空的，由续发领取落空（{@link QueueClaimStatus#NO_TASK}）时注销。</li>
     * </ul>
     *
     * <p>会话查询与 {@code drain} 都在调用线程上执行——会话存储依赖租户上下文的宿主，要在该上下文里调用。
     * 单个会话出错只记告警，不影响其余；整遍处理完记一行汇总（列出、续发、清掉的死标记、跳过、失败与耗时），
     * 索引规模与列举耗时按它观察。未挂接、已停用、引擎已关闭或没有状态来源时什么都不做；中途引擎关闭则停止。</p>
     *
     * @param sessionFilter 会话过滤（null = 全部）
     * @param drain         续发（宿主自己的 {@link #drainLoop} 包装）
     * @return 交给 drain 的会话数
     * @throws IOException 索引列举失败（不能当成「没有积压」，宿主应退避重试）
     * @since 4.2.0
     */
    public int recover(@Nullable Predicate<String> sessionFilter, Consumer<AgentSession> drain) throws IOException {
        QueueIndex i = index;
        HarnessEngine e = engine;
        if (i == null || e == null || drain == null || isEnabled() == false || e.isClosed()) {
            return 0;
        }
        long start = System.currentTimeMillis();
        List<String> sessionIds = i.list();
        int drained = 0;
        int removed = 0;
        int skipped = 0;
        int failed = 0;
        for (String sessionId : sessionIds) {
            if (e.isClosed()) {
                // 引擎中途关闭：剩下的留给新引擎的恢复（再查归属会重新认领刚释放的租约）
                break;
            }
            if (sessionFilter != null && sessionFilter.test(sessionId) == false) {
                skipped++;
                continue;
            }
            try {
                if (e.checkOwner(sessionId).isOwned() == false) {
                    skipped++;
                    continue;
                }
                AgentSession session = e.getSessionOrNull(sessionId);
                if (session == null) {
                    // 会话已不在了：标记是死的
                    if (i.unmark(sessionId)) {
                        removed++;
                    }
                    continue;
                }
                drain.accept(session);
                drained++;
            } catch (Throwable ex) {
                failed++;
                LOG.warn("Queue backlog recover failed for '{}': {}", sessionId, ex.toString());
            }
        }
        if (sessionIds.isEmpty() == false) {
            LOG.info("Queue backlog recover: listed={}, drained={}, removed={}, skipped={}, failed={}, elapsedMs={}",
                    sessionIds.size(), drained, removed, skipped, failed, System.currentTimeMillis() - start);
        }
        return drained;
    }

    /**
     * 为已有积压的会话补登索引（索引上线之前的存量数据用；幂等）。
     *
     * <p>门面、{@code /queue} 命令与忙时积压（{@link #offer}）都会自动登记，正常使用不需要调用。</p>
     *
     * @since 4.2.0
     */
    public void markPending(String sessionId) {
        QueueIndex i = index;
        if (i != null && sessionId != null) {
            i.mark(sessionId);
        }
    }

    /**
     * 按队列当前状态同步积压索引（入队、取消、清空、中断、收口、领取落空之后；门面与 /queue 命令共用）。
     *
     * <p>判定与读写都在会话锁内（与队列本身同一把锁）：「判定队列已空」和「删标记」之间插不进一次入队，
     * 否则刚写的标记会被删掉，而之后不会有人再写。入队不走这里，走 {@link #enqueueIndexed}（先写标记再入队）。
     * 状态来源慢时，写 / 删标记与队列写快照受同样的影响（同在这把锁里）。</p>
     * <ul>
     *   <li>有任务（含运行中）：每次都写标记，并清掉会话上的「已确认注销」；</li>
     *   <li>没有任务：会话上没有「已确认注销」时删标记，删成功才记上——此后到下次入队之前不再碰磁盘
     *       （每轮结束都会走到这里，而绝大多数会话从没排过队）；删失败不记，下次再删。</li>
     * </ul>
     */
    void syncIndex(AgentSession session) {
        if (session == null || session.getSessionId() == null) {
            return;
        }
        String sessionId = session.getSessionId();
        QueueIndex i = index;
        synchronized (session.attrs()) {
            if (QueueStore.hasItems(session)) {
                session.attrs().remove(QueueAttrs.QUEUE_UNMARKED);
                if (i != null) {
                    i.mark(sessionId);
                }
            } else if (i != null && Boolean.TRUE.equals(session.attrs().get(QueueAttrs.QUEUE_UNMARKED)) == false
                    && i.unmark(sessionId)) {
                session.attrs().put(QueueAttrs.QUEUE_UNMARKED, Boolean.TRUE);
            }
        }
    }

    /**
     * 入队并登记积压索引（门面、忙时积压与 {@code /queue} 命令共用）：先写标记、再入队，二者在会话锁内一次完成。
     *
     * <p>顺序反过来（入队先存快照、再写标记）时，两步之间进程崩溃会留下「有积压、没标记」：重启恢复碰不到它，
     * 要等该会话下次有输入才续发。没入成且之前队列为空时，尽量把刚写的标记删掉（删不掉也无妨：索引允许多记，
     * 下次领取落空或恢复时清掉）。</p>
     *
     * @param enqueue 入队动作（返回队列位置或导入条数；失败为 0 或负数）
     * @return {@code enqueue} 的返回值
     */
    int enqueueIndexed(AgentSession session, IntSupplier enqueue) {
        QueueIndex i = index;
        if (session == null || session.getSessionId() == null || i == null) {
            return enqueue.getAsInt();
        }
        String sessionId = session.getSessionId();
        synchronized (session.attrs()) {
            boolean wasEmpty = QueueStore.hasItems(session) == false;
            session.attrs().remove(QueueAttrs.QUEUE_UNMARKED);
            i.mark(sessionId);
            int result = enqueue.getAsInt();
            if (result <= 0 && wasEmpty && i.unmark(sessionId)) {
                session.attrs().put(QueueAttrs.QUEUE_UNMARKED, Boolean.TRUE);
            }
            return result;
        }
    }

    /** 会话被永久删除：注销积压索引（只删这一条；不取会话；清理类，不判开关）。 */
    @Override
    public void onSessionRemove(HarnessEngine engine, String sessionId) {
        if (sessionId == null) {
            return;
        }
        QueueIndex i = index;
        if (i != null) {
            i.unmark(sessionId);
        }
    }

    /** 写门面的归属检查（未挂载时视为持有：单测与无引擎场景） */
    private boolean owned(AgentSession session) {
        HarnessEngine e = engine;
        return session == null || e == null || e.checkOwner(session).isOwned();
    }

    @Override
    public void setEnabled(Boolean enabled) {
        super.setEnabled(enabled);
        queueCommand.setEnabled(enabled);
    }

    /**
     * 用户级中断：清空待执行并递增代际（引擎保证先于停流回调；清理类，不判开关）。
     */
    @Override
    public void onSessionInterrupt(HarnessEngine engine, AgentSession session) {
        QueueStore.cancelPending(session);
        syncIndex(session);
    }

    // ==================== InputBacklog（核心能力契约） ====================

    /**
     * 积压入口（引擎 {@code handle} 受理判定为忙时调用；受开关控制）。
     *
     * <p>保存续发还原所需的事实：正文、来源、显式模型与子代理选择、宿主域属性
     * （{@link HarnessInput#getAttrs()}，原样保存，续发经 {@link QueueItem#toInput} 原样带回）。</p>
     */
    @Override
    public int offer(AgentSession session, HarnessInput input) {
        if (input == null || isEnabled() == false) {
            return -1;
        }
        String agent = input.getExplicitAgent() != null ? input.getExplicitAgent() : input.getSelectedAgent();
        // 输入快照：技能 / MCP 视野、终端标记与显式作用域随积压保存，续发时还原（解析所得的作用域不存，续发时重新解析）
        int position = enqueueIndexed(session, () -> QueueStore.enqueue(session, input.getText(), input.getOrigin(), null,
                input.getSelectedModel(), agent, input.getAttrs(), QueueItem.snapshotOf(input),
                limits.maxSize, limits.maxTextLength));
        return position > 0 ? position : -1;
    }

    /**
     * 是否有待执行的排队任务（核心受理的先进先出与 {@code engine.isIdle} 依赖它）。
     *
     * <p>事实口径，不受开关影响；只读内存态、不加载快照，可在会话锁内安全调用。</p>
     */
    @Override
    public boolean hasPending(AgentSession session) {
        return QueueStore.pendingSize(session) > 0;
    }

    /**
     * 会话状态贡献：{@link #STATUS_PENDING}（事实口径，不受开关影响；只读内存态，可在会话锁内调用）。
     */
    @Override
    public void contributeSessionStatus(HarnessEngine engine, AgentSession session, SessionStatus.Builder builder) {
        builder.put(STATUS_PENDING, QueueStore.pendingSize(session));
    }

    /**
     * 开关语义（与命令层一致）：
     * <ul>
     *   <li>受控：{@link #enqueue} 与 {@link #claimNext}/{@link #drainLoop}
     *       —— 停用后不再接单、不再续发；</li>
     *   <li>不受控：cancelItem / promote / clearPending 等清理类接口 —— 开关不得阻止清理，
     *       否则停用前已入队的项将永远无法取消。</li>
     * </ul>
     */

    // ==================== 操作门面（中转静态实现） ====================

    /**
     * 入队（携带来源身份）。
     *
     * <p>注意：门面入队不经 {@code handle} 受理，因此不做作用域预检；作用域在续发时由解析器解析
     * （{@code scopeRequired} 下解析不到即被拒绝）。</p>
     *
     * @return OK 时值为队列位置（从 1 计）；DISABLED（已停用）/ NOT_OWNER（本节点不持有该会话）/
     *         INVALID（文本为空或超长）/ FULL（队列已满）
     */
    public HarnessResult<Integer> enqueue(AgentSession session, String text, InputOrigin origin) {
        return enqueue(HarnessInput.of(session, text).origin(origin), null);
    }

    /**
     * 入队（来源缺省为 {@code channel("queue")}）。
     *
     * <p>程序宿主调用时通常不关心来源元数据，不必为一个必填参数编造渠道标识。</p>
     */
    public HarnessResult<Integer> enqueue(AgentSession session, String text) {
        return enqueue(session, text, InputOrigin.channel("queue"));
    }

    /**
     * 完整入队：正文、来源、显式模型 / 子代理选择、宿主域属性（{@code input.attr(...)}，如推理强度、思考模式）
     * 与技能 / MCP 视野原样保存，续发经 {@link QueueItem#toInput} 原样带回。
     *
     * @param input 输入（会话取自 {@code input.getSession()}；附件不进队列）
     * @param id    条目 id（HTTP 重试幂等用；null = 自动生成）。重复时为 CONFLICT
     * @since 4.2.0
     */
    public HarnessResult<Integer> enqueue(HarnessInput input, String id) {
        if (input == null || input.getSession() == null) {
            return HarnessResult.invalid("session is required");
        }
        if (isEnabled() == false) {
            return HarnessResult.disabled("queue is disabled");
        }
        AgentSession session = input.getSession();
        HarnessResult<Integer> owner = ownerCheck(session);
        if (owner != null) {
            return owner;
        }
        String agent = input.getExplicitAgent() != null ? input.getExplicitAgent() : input.getSelectedAgent();
        int position = enqueueIndexed(session, () -> QueueStore.enqueue(session, input.getText(), input.getOrigin(), id,
                input.getSelectedModel(), agent, input.getAttrs(), QueueItem.snapshotOf(input),
                limits.maxSize, limits.maxTextLength));
        if (position > 0) {
            return HarnessResult.ok(position);
        }
        switch (position) {
            case QueueStore.ENQUEUE_FULL:
                HarnessEngine e = engine;
                return HarnessResult.full(QueueI18n.fmt(e == null ? input.getLocale() : e.localeOf(input),
                        QueueI18nKeys.QUEUE_FULL_DETAIL, "max", limits.maxSize));
            case QueueStore.ENQUEUE_DUPLICATE:
                return HarnessResult.conflict("duplicate queue item id: " + id);
            default:
                return HarnessResult.invalid("text is empty or longer than " + limits.maxTextLength);
        }
    }

    /** 待执行数量（不含 in-flight，运行中的那条不占容量）。 */
    public int pendingSize(AgentSession session) {
        return QueueStore.pendingSize(session);
    }

    /** 是否有任务（含待执行与运行中）。 */
    public boolean hasItems(AgentSession session) {
        return QueueStore.hasItems(session);
    }

    /**
     * 快照投影中是否存在队列（静态探测，供宿主在会话尚未实例化时使用）。
     *
     * <p>替代宿主直接依赖内核 {@code QueueStore} 静态方法；不需要扩展实例。</p>
     *
     * @since 4.2.0
     */
    public static boolean isPersisted(FlowContext context) {
        return QueueStore.hasPersistedItems(context);
    }

    /**
     * 队列列表：运行中任务（若有）在首位，其余为待执行（只读实体；对外展示用 {@link QueueItem#toMap()}）。
     *
     * @since 4.2.0
     */
    public List<QueueItem> list(AgentSession session) {
        return QueueStore.view(session);
    }

    /**
     * 领取并执行队首一条（一次领取 = 一个 {@link QueueClaim}）。
     *
     * <p>handler 拿到 claim 后直接取 {@code claim.getItem()} 拿任务本体；收口由 handler 返回值
     * （同步完成）或流终态的 {@code RunTicket#settle}（异步启动）完成——不需要往会话 attrs 里塞
     * QueueItem，也不需要在终态现取代际。</p>
     *
     * <p>{@code canAccept} 在领取权的<b>同一把会话锁内</b>求值，途中不存在「刚判完空闲、领取前已被占」的窗口，
     * 宿主不需要再写 {@code synchronized (session.attrs())} 与代际复核。只想「领到队空为止」的宿主用 {@link #drainLoop}。</p>
     *
     * <p>挂接的引擎已关闭时返回 {@link QueueClaimStatus#NO_TASK}，不领取、不查归属：队列原样留在会话快照与索引里，
     * 由新引擎的恢复续发。</p>
     *
     * @param canAccept 现在能不能接（null = 不预判）；false 时不消耗队列项、不占领取权
     * @since 4.2.0
     */
    public QueueClaimStatus claimNext(AgentSession session, Predicate<AgentSession> canAccept,
                                QueueClaimHandler handler) {
        if (isEnabled() == false || isEngineClosed() || owned(session) == false) {
            return QueueClaimStatus.NO_TASK;
        }
        QueueClaimStatus status = QueueStore.claimNext(session, canAccept, handler);
        if (status == QueueClaimStatus.NO_TASK || QueueStore.hasItems(session) == false) {
            // 领取落空（队列已空则注销，有项但这会儿领不了则补写标记），或这一条已同步完成、队列全空：按现状同步。
            // 异步启动的那条还在运行时不碰索引（收口也不碰）：下次领取落空或恢复时再注销
            syncIndex(session);
        }
        return status;
    }

    /**
     * 连续领取直到「本轮真的一启动了任务」或「队列空」或「不能再接单」。
     *
     * <p><b>为何要有这个方法</b>：队列续发不是一个简单循环——它需要回答三个问题：
     * 现在能不能接（宿主自己的占位闩语义，harness 无从得知）、上一条是否真的启动成功
     * （{@link QueueClaimStatus#START_FAILED} 时立即重试会形成死循环）、启动后还要不要继续。
     * 此前每个宿主都得自己写一遍递归 + 三态判定，写错任何一处就是要么漏发、要么狂循环。
     * 本方法把循环与终止条件收归 harness，只把「能不能接」作为谓词留给宿主。</p>
     *
     * <p>终止条件（任一成立即返回）：
     * <ul>
     *   <li>{@code canDrain} 返回 false（会话忙/被占）——返回上一次状态；</li>
     *   <li>领取结果为 {@link QueueClaimStatus#START_FAILED}——任务已回队首，<b>不立即重试</b>；</li>
     *   <li>领取结果为 {@link QueueClaimStatus#NO_TASK}（队空）；</li>
     *   <li>启动成功后队列已无待执行项（运行中的那条不占容量，不必再发）；</li>
     *   <li>挂接的引擎已关闭（进入时已关闭则不领取、不查归属，返回 NO_TASK）。宿主的 handler 遇到引擎已关闭
     *       （{@code RejectReason.ENGINE_CLOSED}）或会话已不归本节点（{@code NOT_OWNER}）的拒绝应当抛出：输入本身没问题，
     *       这条回到队首（START_FAILED）由新引擎 / 持有节点续发，而不是被当作同步完成确认掉。其余拒绝（如作用域解析不到）
     *       跟着这条输入走，按已处理返回 true：放回队首会挡住后面所有输入（队列先进先出）。</li>
     * </ul>
     *
     * <p>与 {@link #claimNext} 的关系：本方法是它的循环包装，语义完全一致。
     * 需要自己对每次领取做额外决策的宿主仍可直接用 {@code claimNext}。</p>
     *
     * @param canDrain 现在能不能接单（传 null 表示不预判，完全由队列状态决定）；
     *                 同步命令会在回调返回前就结束，宿主通常在这里传「当前是否忙」
     * @param handler  单次领取的启动者；返回 true = 同步完成（内核当场收口），
     *                 false = 异步启动（终态由流终态的 {@code dispatch.settle} 收口）
     * @return 最后一次领取的结果（未发生领取时为 {@link QueueClaimStatus#NO_TASK}）
     * @since 4.2.0
     */
    public QueueClaimStatus drainLoop(AgentSession session, Predicate<AgentSession> canDrain,
                                QueueClaimHandler handler) {
        if (isEnabled() == false || session == null || handler == null || isEngineClosed() || owned(session) == false) {
            return QueueClaimStatus.NO_TASK;
        }

        QueueClaimStatus last = QueueClaimStatus.NO_TASK;
        // 单次调用最多续发一轮队列容量：正常情况下每条领取都会消耗一个待执行项，
        // 这里的上限只是防御「宿主 handler 把同一项反复塞回」的失控情形，不应被触达。
        for (int guard = 0; guard <= limits.maxSize; guard++) {
            // 本处只是快速出口（省一次入锁构造），权威判定在 claimNext 内部与领取权同一把锁完成。
            // 两处都判不是冗余防御，而是「能看见就早退」，不要把这里当成唯一关卡。
            if (canDrain != null && !canDrain.test(session)) {
                return last;
            }
            if (isEngineClosed()) {
                // 上一条启动之后引擎关闭了：剩下的留在队列里，由新引擎的恢复续发
                return last;
            }

            QueueClaimStatus status = QueueStore.claimNext(session, canDrain, handler);
            if (status != QueueClaimStatus.STARTED) {
                if (status == QueueClaimStatus.NO_TASK) {
                    // 领取落空：队列已空则注销，有项但这会儿领不了则补写标记
                    syncIndex(session);
                }
                return status;
            }

            last = status;
            if (QueueStore.pendingSize(session) <= 0) {
                if (QueueStore.hasItems(session) == false) {
                    // 最后一条已同步完成（handler 返回 true）、队列全空：顺手注销。异步启动的那条还在运行时不碰索引，
                    // 它的这一轮结束后宿主再续发时领取落空、在上面注销
                    syncIndex(session);
                }
                return last;
            }
        }
        return last;
    }

    /** 取消单条待执行任务（运行中的那条不能取消，为 NOT_FOUND）。 */
    public HarnessResult<Void> cancelItem(AgentSession session, String itemId) {
        HarnessResult<Void> owner = ownerCheck(session);
        if (owner != null) {
            return owner;
        }
        boolean removed = QueueStore.cancelItem(session, itemId);
        syncIndex(session);
        return removed ? HarnessResult.<Void>ok() : HarnessResult.<Void>notFound("queue item not found");
    }

    /** 置顶单条待执行任务。 */
    public HarnessResult<Void> promote(AgentSession session, String itemId) {
        HarnessResult<Void> owner = ownerCheck(session);
        if (owner != null) {
            return owner;
        }
        return QueueStore.promote(session, itemId) ? HarnessResult.<Void>ok() : HarnessResult.<Void>notFound("queue item not found");
    }

    /**
     * 清空全部待执行（不动正在执行的那条、不递增代际）。
     *
     * <p>「连同运行中那条一起作废」是停止语义，不在本门面：宿主调 {@code engine.interrupt(session, InterruptMode.INTERRUPT_ALL)}
     * 或 {@code engine.prepareInterrupt(session)}，引擎先回调本扩展的 {@link #onSessionInterrupt}（清空并递增代际）再停流。</p>
     *
     * @return OK 时值为清掉的条数
     * @since 4.2.0
     */
    public HarnessResult<Integer> clearPending(AgentSession session) {
        HarnessResult<Integer> owner = ownerCheck(session);
        if (owner != null) {
            return owner;
        }
        int cleared = QueueStore.clearPending(session);
        syncIndex(session);
        return HarnessResult.ok(cleared);
    }

    /** 旧版 queue-tasks.json 的一次性迁移入口。OK 时值为实际导入条数 */
    public HarnessResult<Integer> importItems(AgentSession session, List<Map<String, Object>> rows) {
        HarnessResult<Integer> owner = ownerCheck(session);
        if (owner != null) {
            return owner;
        }
        int imported = enqueueIndexed(session, () -> QueueStore.importItems(session, rows, limits.maxSize));
        return HarnessResult.ok(imported);
    }

    /** 写门面的归属检查：本节点持有时返回 null，否则返回 NOT_OWNER（未挂载时视为持有：单测与无引擎场景） */
    private <T> HarnessResult<T> ownerCheck(AgentSession session) {
        HarnessEngine e = engine;
        if (session == null || e == null) {
            return null;
        }
        OwnerCheck check = e.checkOwner(session);
        return check.isOwned() ? null : HarnessResult.<T>notOwner(check.getOwner());
    }
}
