/*
 * Copyright 2017-2026 noear.org and authors
 * Licensed under the Apache License, Version 2.0.
 */
package org.noear.solon.ai.harness.extension.queue;

import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.harness.AbsHarnessExtension;
import org.noear.solon.flow.FlowContext;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.ai.harness.HarnessResult;
import org.noear.solon.ai.harness.input.HarnessInput;
import org.noear.solon.ai.harness.input.InputOrigin;
import org.noear.solon.ai.harness.spi.InputBacklog;
import org.noear.solon.ai.harness.session.OwnerCheck;
import org.noear.solon.ai.harness.session.SessionStatus;

import java.util.Collections;
import java.util.List;
import java.util.Map;
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
 * 启动恢复用 {@link #isPersisted} 探测后直接 drain。</p>
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
 * 队列存在会话属性里，随会话一起消失，无需覆写 {@code onSessionRemove}。</p>
 *
 * @author noear
 * @since 3.9.4
 */
public class QueueExtension extends AbsHarnessExtension implements InputBacklog {
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
        // 命令写队列后回到本扩展同步积压索引（与门面同一口径）
        this.queueCommand = new QueueCommand(limits, this::syncIndex);
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

    @Override
    public void onAttach(HarnessEngine engine) {
        this.engine = engine;
        this.index = engine.getStateSource() == null ? null : new QueueIndex(engine.getStateSource(), INDEX_PATH);
        engine.getCommandRegistry().register(queueCommand);
    }

    /**
     * 重启恢复用：可能有积压的会话（索引是超集——入队时登记，观察到队列已空时注销）。
     *
     * <p>宿主对这些会话各调一次续发即可，不需要枚举会话目录；HarnessHost 首次绑定引擎时会自动做。
     * 未挂接或引擎没有状态来源时返回空。</p>
     *
     * @since 4.2.0
     */
    public List<String> pendingSessions() {
        QueueIndex i = index;
        return i == null ? Collections.<String>emptyList() : i.read();
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
        if (i != null) {
            i.mark(sessionId);
        }
    }

    /** 入队成功后登记、队列已空时注销（门面与 /queue 命令共用） */
    private void syncIndex(AgentSession session) {
        QueueIndex i = index;
        if (i == null || session == null) {
            return;
        }
        if (QueueStore.hasItems(session)) {
            i.mark(session.getSessionId());
        } else {
            i.unmark(session.getSessionId());
        }
    }

    @Override
    public void onSessionRemove(HarnessEngine engine, String sessionId) {
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
        int position = QueueStore.enqueue(session, input.getText(), input.getOrigin(), null, input.getSelectedModel(),
                agent, input.getAttrs(), QueueItem.snapshotOf(input), limits.maxSize, limits.maxTextLength);
        if (position > 0) {
            syncIndex(session);
        }
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
        int position = QueueStore.enqueue(session, input.getText(), input.getOrigin(), id, input.getSelectedModel(),
                agent, input.getAttrs(), QueueItem.snapshotOf(input), limits.maxSize, limits.maxTextLength);
        if (position > 0) {
            syncIndex(session);
            return HarnessResult.ok(position);
        }
        switch (position) {
            case QueueStore.ENQUEUE_FULL:
                return HarnessResult.full("队列已满，上限 " + limits.maxSize + " 条");
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
     * @param canAccept 现在能不能接（null = 不预判）；false 时不消耗队列项、不占领取权
     * @since 4.2.0
     */
    public QueueClaimStatus claimNext(AgentSession session, Predicate<AgentSession> canAccept,
                                QueueClaimHandler handler) {
        if (isEnabled() == false || owned(session) == false) {
            return QueueClaimStatus.NO_TASK;
        }
        return QueueStore.claimNext(session, canAccept, handler);
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
     *   <li>启动成功后队列已无待执行项（运行中的那条不占容量，不必再发）。</li>
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
        if (isEnabled() == false || session == null || handler == null || owned(session) == false) {
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

            QueueClaimStatus status = QueueStore.claimNext(session, canDrain, handler);
            if (status != QueueClaimStatus.STARTED) {
                if (status == QueueClaimStatus.NO_TASK) {
                    // 队列已空（运行中的那条也已收口）：注销索引
                    syncIndex(session);
                }
                return status;
            }

            last = status;
            if (QueueStore.pendingSize(session) <= 0) {
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
        int imported = QueueStore.importItems(session, rows, limits.maxSize);
        syncIndex(session);
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
