/*
 * Copyright 2017-2026 noear.org and authors
 * Licensed under the Apache License, Version 2.0.
 */
package org.noear.solon.ai.harness.extension.queue;

import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.harness.input.InputOrigin;
import org.noear.solon.ai.harness.spi.TurnDispatch;
import org.noear.solon.lang.Nullable;

/**
 * 一次队列领取（领取事务，同时是核心的程序派发 {@link TurnDispatch}）。
 *
 * <p>把「领取」提升为一等对象，宿主原本必须自己保管的东西都收进 harness：</p>
 * <ol>
 *   <li><b>领取到的任务</b>：{@link #getItem()}，无需再往会话 attrs 里塞一份再强转取回；</li>
 *   <li><b>领取时刻的代际快照</b>：宿主不再需要在终态现取代际——那会让代际防线恒真（详见下文）；</li>
 *   <li><b>派发登记</b>：领取成功即登记到核心槽位（{@code TurnDispatch.bind}），收口即注销，
 *       引擎的受理判定与 {@code SessionTurn} 只认接口，不认识队列；</li>
 *   <li><b>终态收口</b>：唯一入口 {@link #settle(boolean)}（成功确认 / 失败回滚到队首），
 *       身份与代际校验由 harness 内部完成，幂等。</li>
 * </ol>
 *
 * <p><b>收口路径只有两条</b>（宿主不需要、也不应该自己找 claim 收口）：</p>
 * <ul>
 *   <li>同步任务：{@link QueueClaimHandler} 返回 {@code true}，内核当场收口；</li>
 *   <li>异步任务：流终态调核心的 {@code SessionTurn#settle(boolean)}（它在 {@code begin} 时已捕获本派发）。</li>
 * </ul>
 *
 * <p><b>为什么必须有代际快照</b>：{@code cancelPending}（清空待执行）会递增代际并移除
 * in-flight。若宿主在终态「现取」代际，取到的必然是新代际，代际校验恒真，
 * 于是被用户 Stop 掉的任务会重新入队继续执行——丢弃语义被破坏。本类在领取瞬间
 * 绑定代际，回滚时对已清空会话必然被拒。</p>
 *
 * <p><b>两个线程判定必须分开用</b>：{@link #isOnDispatchThread()}——当前线程就在派发调用栈里
 * （入口判定：不要再占受理位、不要自己入队）；{@link #isCurrent()}——会话仍有这条未收口派发
 * （流终态在异步线程上判定）。混用会引入真实缺陷：异步订阅失败发生在 {@code boundedElastic} 上，
 * 用线程判定必然得到 {@code false}，于是「回滚到队首 → 立即续发 → 又领到同一条 → 再失败」形成死循环。</p>
 *
 * @author noear
 * @since 4.2.0
 */
public final class QueueClaim implements TurnDispatch {
    private enum State {
        /** 已领取、尚未收口 */
        OPEN,
        /** 成功终态 */
        ACKNOWLEDGED,
        /** 回滚（启动失败 / 未收口即释放） */
        ROLLED_BACK
    }

    private final AgentSession session;
    private final QueueItem item;
    private final long generation;
    private final Thread dispatchThread;
    private volatile State state = State.OPEN;

    QueueClaim(AgentSession session, QueueItem item, long generation) {
        this.session = session;
        this.item = item;
        this.generation = generation;
        this.dispatchThread = Thread.currentThread();
    }

    /**
     * 会话当前未收口的队列领取（无则 null；槽位里若是别的插件登记的派发，也返回 null）。
     */
    @Nullable
    public static QueueClaim current(@Nullable AgentSession session) {
        TurnDispatch value = TurnDispatch.current(session);
        return value instanceof QueueClaim ? (QueueClaim) value : null;
    }

    /** 本次领取的任务（正常领取必定非 null）。 */
    @Nullable
    public QueueItem getItem() {
        return item;
    }

    /** 回复路由来源（= {@link QueueItem#getOrigin()}），入口据此设置定向回复目标。 */
    @Override
    public InputOrigin getOrigin() {
        return item == null ? InputOrigin.NONE : item.getOrigin();
    }

    /** 当前线程是否就是发起本次派发的线程（即「正处在队列派发调用栈里」）。 */
    @Override
    public boolean isOnDispatchThread() {
        return Thread.currentThread() == dispatchThread;
    }

    /** 本领取是否仍是会话当前的派发事务（跨线程可判，不看线程）。 */
    @Override
    public boolean isCurrent() {
        return TurnDispatch.current(session) == this;
    }

    /** 是否已收口（成功或回滚）。 */
    public boolean isSettled() {
        return state != State.OPEN;
    }

    /**
     * 收口（幂等）：成功确认 / 失败回滚到队首，并注销派发登记。
     *
     * <p>{@code cancelPending}（用户 Stop）会在清队列的同时注销派发登记并递增代际，
     * 此后本领取已失效。因此重复收口、Stop 之后再收口都是安全的空操作，调用方无须先判空。</p>
     *
     * @param started true = 本轮已正常跑起来（确认完成）；false = 启动失败（回滚到队首）
     * @return true = 本次调用完成了收口
     */
    @Override
    public boolean settle(boolean started) {
        return started ? acknowledge() : rollback();
    }

    /** 领取时刻的代际快照（包内：代际校验与诊断）。 */
    long getGeneration() {
        return generation;
    }

    /** 代际是否仍然有效（= 领取之后没有发生过 {@code cancelPending}）。 */
    boolean isValid() {
        return QueueStore.isGenerationActive(session, generation);
    }

    /**
     * 成功终态：从 in-flight 移除并落盘。
     *
     * @return true = 本次调用完成了收口；false = 已收口过 / 身份不匹配（in-flight 已被他人替换）
     */
    boolean acknowledge() {
        if (state != State.OPEN || item == null) {
            return false;
        }
        if (!QueueStore.acknowledge(session, item.getId())) {
            return false;
        }
        state = State.ACKNOWLEDGED;
        TurnDispatch.unbind(session, this);
        return true;
    }

    /**
     * 回滚：把任务放回队首（用领取时刻的代际判定是否还该回写）。
     *
     * @return true = 已放回队首；false = 已收口过 / 代际已变（期间被 cancelPending 清空，应丢弃）
     */
    boolean rollback() {
        if (state != State.OPEN || item == null) {
            return false;
        }
        if (QueueStore.requeueFront(session, item, generation)) {
            state = State.ROLLED_BACK;
            TurnDispatch.unbind(session, this);
            return true;
        }
        // 回写被拒只有两种原因，处理方式相反：
        //   1) 代际已变（期间被 cancelPending 清空）——任务应当被丢弃，清掉 in-flight 残留；
        //   2) 代际未变但队列已满——任务绝不能丢，保持 in-flight 不给其他任务插队。
        if (!isValid()) {
            QueueStore.acknowledge(session, item.getId());
            state = State.ROLLED_BACK;
            TurnDispatch.unbind(session, this);
        }
        // 回写被拒且代际仍有效（队列满）：任务保持 in-flight，派发登记也保持——
        // 它不是「已收口」，后续仍要有人对它收口，这里注销会让任务永久无主。
        return false;
    }

    @Override
    public String toString() {
        return "QueueClaim{item=" + (item == null ? null : item.getId())
                + ", generation=" + generation + ", state=" + state
                + ", onDispatchThread=" + isOnDispatchThread() + "}";
    }
}
