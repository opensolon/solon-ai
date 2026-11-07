/*
 * Copyright 2017-2026 noear.org and authors
 * Licensed under the Apache License, Version 2.0.
 */
package org.noear.solon.ai.harness.extension.queue;

import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.harness.AbsHarnessExtension;
import org.noear.solon.flow.FlowContext;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.ai.harness.input.HarnessInput;
import org.noear.solon.ai.harness.input.InputOrigin;
import org.noear.solon.ai.harness.spi.InputBacklog;
import org.noear.solon.ai.harness.session.SessionStatus;

import java.util.List;
import java.util.Map;

/**
 * Queue 一键装配扩展 —— queue 能力从头到尾只接触这一个类。
 *
 * <p>{@code engine.addExtension(new QueueExtension())} 一步完成：注册 /queue 命令
 * （幂等）+ 提供队列操作门面。宿主不再分别接触 {@link QueueStore} 静态工具类——本类内部中转，静态实现与
 * {@code session.queue.*} attrs 契约零改动。</p>
 *
 * <p>装配后宿主只需掌握一个时机：任务终态续发。首选 {@link #drainLoop}（自动套领取循环 +
 * 三态终止），单条控制用 {@link #claimNext}；二者都把「一次领取」封成 {@link QueueClaim}，
 * 代际快照与终态收口（{@code acknowledge}/{@code rollback}）归 harness。
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
 * 覆写 {@link #onSessionInterrupt}（用户停止时先清队列再停流）；派发事务登记在核心槽位 {@code TurnDispatch}。
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
        this.queueCommand = new QueueCommand(limits);
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

        /** 每会话待执行条数上限（1 /Users/noear 10000，缺省 20） */
        public Builder maxSize(int maxSize) {
            if (maxSize < 1 || maxSize > QueueStore.HARD_MAX_QUEUE_SIZE) {
                throw new IllegalArgumentException("maxSize must be in [1, " + QueueStore.HARD_MAX_QUEUE_SIZE + "]");
            }
            this.maxSize = maxSize;
            return this;
        }

        /** 单条文本字符上限（1 /Users/noear 1000000，缺省 4096） */
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

    @Override
    public void onAttach(HarnessEngine engine) {
        engine.getCommandRegistry().register(queueCommand);
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
        return QueueStore.enqueue(session, input.getText(), input.getOrigin(), null, input.getSelectedModel(),
                null, null, agent, false, input.getAttrs(), limits.maxSize, limits.maxTextLength);
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
     *   <li>不受控：cancelItem / promote / cancelPending 等清理类接口 —— 开关不得阻止清理，
     *       否则停用前已入队的项将永远无法取消。</li>
     * </ul>
     */

    // ==================== 操作门面（中转静态实现） ====================

    /** 入队（携带来源身份）。@return 队列位置（从 1 计）；-1 表示失败（满/超长/参数空/扩展已停用） */
    public int enqueue(AgentSession session, String text, InputOrigin origin) {
        if (isEnabled() == false) {
            return -1;
        }
        return QueueStore.enqueue(session, text, origin, null, null, null, null, null, false, null,
                limits.maxSize, limits.maxTextLength);
    }

    /**
     * 入队（来源缺省为 {@code channel("queue")}）。
     *
     * <p>程序宿主调用时通常不关心来源元数据，不必为一个必填参数编造渠道标识
     * （与 {@code /queue} 命令的内部兜底同口径）。
     *
     * @return 队列位置（从 1 计）；-1 表示失败
     */
    public int enqueue(AgentSession session, String text) {
        return enqueue(session, text, InputOrigin.channel("queue"));
    }

    /** 完整参数入队（HTTP 重试幂等用 id 去重；model/effort 等随任务执行）。已停用时返回 -1。 */
    public int enqueue(AgentSession session, String text, InputOrigin origin,
                       String id, String model, String reasoningEffort,
                       String thinkingMode, String selectedAgent, boolean hasFiles) {
        if (isEnabled() == false) {
            return -1;
        }
        return QueueStore.enqueue(session, text, origin, id, model, reasoningEffort,
                thinkingMode, selectedAgent, hasFiles, null, limits.maxSize, limits.maxTextLength);
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
     * （同步完成）或流终态的 {@code SessionTurn#settle}（异步启动）完成——不需要往会话 attrs 里塞
     * QueueItem，也不需要在终态现取代际。</p>
     *
     * @see QueueStore#claimNext(AgentSession, QueueClaimHandler)
     * @since 4.2.0
     */
    public QueueClaimStatus claimNext(AgentSession session, QueueClaimHandler handler) {
        if (isEnabled() == false) {
            return QueueClaimStatus.NO_TASK;
        }
        return QueueStore.claimNext(session, handler);
    }

    /**
     * 领取并执行队首一条（带「现在能不能接」的原子谓词）。
     *
     * <p>与 {@link #claimNext(AgentSession, QueueClaimHandler)} 的差别：谓词在领取权的
     * <b>同一把会话锁内</b>求值，途中不存在「刚判完空闲、领取前已被占」的窗口，
     * 宿主不需要再写 {@code synchronized (session.attrs())} 与代际复核。</p>
     *
     * @param canAccept 现在能不能接（null = 不预判）；false 时不消耗队列项、不占领取权
     * @since 4.2.0
     */
    public QueueClaimStatus claimNext(AgentSession session, java.util.function.Predicate<AgentSession> canAccept,
                                QueueClaimHandler handler) {
        if (isEnabled() == false) {
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
    public QueueClaimStatus drainLoop(AgentSession session, java.util.function.Predicate<AgentSession> canDrain,
                                QueueClaimHandler handler) {
        if (isEnabled() == false || session == null || handler == null) {
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
                return status;
            }

            last = status;
            if (QueueStore.pendingSize(session) <= 0) {
                return last;
            }
        }
        return last;
    }

    /** 取消单条待执行任务。 */
    public boolean cancelItem(AgentSession session, String itemId) {
        return QueueStore.cancelItem(session, itemId);
    }

    /** 置顶单条待执行任务。 */
    public boolean promote(AgentSession session, String itemId) {
        return QueueStore.promote(session, itemId);
    }

    /** 清空待执行并递增代际（in-flight 不受影响；旧代际 requeue 被拒）。 */
    public boolean cancelPending(AgentSession session) {
        return QueueStore.cancelPending(session);
    }

    /** 旧版 queue-tasks.json 的一次性迁移入口。@return 实际导入条数 */
    public int importItems(AgentSession session, List<Map<String, Object>> rows) {
        return QueueStore.importItems(session, rows, limits.maxSize);
    }
}
