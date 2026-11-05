/*
 * Copyright 2017-2026 noear.org and authors
 * Licensed under the Apache License, Version 2.0.
 */
package org.noear.solon.ai.harness.session.queue;

import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.harness.input.InputOrigin;
import org.noear.solon.ai.harness.session.TurnDispatch;
import org.noear.solon.lang.Nullable;

/**
 * 一次队列派发事务（跨线程存活的领取上下文）。
 *
 * <p>{@link QueueClaim} 的生命周期只覆盖「领取那一刻」；而异步任务会在别的线程结束，
 * 宿主必须能回答两个问题：<b>「本轮输入是不是由队列派发而来？」</b>、
 * <b>「该用哪个 claim 收口？」</b>。此前这两个答案只能由宿主往会话 attrs 里塞一份
 * （自带内部类 + 宿主私有键 + 两处 synchronized），宿主换一端就要重写一遍。</p>
 *
 * <p>本类把这份状态收归 harness：{@link QueueHelper#claimNext} 在领取成功时登记，
 * {@link #settle(boolean)} 收口时注销，宿主全程零 attrs 操作。登记位置是核心的会话事实槽位
 * （{@link TurnDispatch}）：引擎的受理判定只认接口，不认识队列。</p>
 *
 * <p><b>两个线程判定必须分开用</b>：</p>
 * <ul>
 *   <li>{@link #isOnDispatchThread()}——当前线程<b>就在</b>派发调用栈里。
 *       用于入口判定「这是一轮队列派发，不要再占受理位、不要自己入队」；</li>
 *   <li>{@link #isCurrent()} / {@code current(session) != null}——会话<b>有</b>未收口的派发事务。
 *       用于流终态（异步线程）判定「启动失败后不要立即重试」。</li>
 * </ul>
 * <p>混用会引入真实缺陷：异步订阅失败发生在 {@code boundedElastic} 上，用线程判定必然得到
 * {@code false}，于是「回滚到队首 → 立即续发 → 又领到同一条 → 再失败」形成死循环。</p>
 *
 * @author noear
 * @since 4.2.2
 */
public final class QueueDispatch implements TurnDispatch {
    private final AgentSession session;
    private final QueueClaim claim;
    private final Thread dispatchThread;

    QueueDispatch(AgentSession session, QueueClaim claim) {
        this.session = session;
        this.claim = claim;
        this.dispatchThread = Thread.currentThread();
    }

    /**
     * 会话当前未收口的派发事务（无则 null）。
     *
     * <p>静态入口供宿主在任意线程判定，不需要先拿到 {@link QueueExtension} 实例。
     * 槽位里若是别的插件登记的派发，本方法返回 null（与队列无关）。</p>
     */
    @Nullable
    public static QueueDispatch current(AgentSession session) {
        TurnDispatch value = TurnDispatch.current(session);
        return value instanceof QueueDispatch ? (QueueDispatch) value : null;
    }

    /** 本事务持有的领取凭据（包内可见：供 claim 收口时反查自己的派发登记）。 */
    QueueClaim claim() {
        return claim;
    }

    /** 本轮任务本体（取 text / model / effort / 子代理等）。 */
    @Nullable
    public QueueItem getItem() {
        return claim.getItem();
    }

    /** 回复路由来源（= {@link QueueItem#getOrigin()}），入口据此设置定向回复目标。 */
    @Override
    public InputOrigin getOrigin() {
        QueueItem item = claim.getItem();
        return item == null ? InputOrigin.NONE : item.getOrigin();
    }

    /** 当前线程是否就是发起本次派发的线程（即「正处在队列派发调用栈里」）。 */
    @Override
    public boolean isOnDispatchThread() {
        return Thread.currentThread() == dispatchThread;
    }

    /** 本事务是否仍是会话当前的派发事务（跨线程可判，不看线程）。 */
    @Override
    public boolean isCurrent() {
        return TurnDispatch.current(session) == this;
    }

    /**
     * 收口（幂等）：成功确认 / 失败回滚到队首，并注销登记。
     *
     * <p>终态只有在「仍归属于本会话」时才有意义：{@code cancelPending}（用户 Stop）会在清队列的同时
     * 注销派发登记并递增代际，此后本事务的 claim 已失效、登记已不存在。因此重复收口、
     * Stop 之后再收口都是安全的空操作，宿主无须先判空。</p>
     *
     * @param started true = 本轮已正常跑起来（确认完成）；false = 启动失败（回滚到队首）
     * @return true = 本次调用完成了收口
     */
    @Override
    public boolean settle(boolean started) {
        // 先交给内核收口，再注销登记：claim 内部会经由 QueueHelper.unregisterDispatchOf
        // 完成注销（见 QueueClaim.acknowledge/rollback），此处不再重复一遍。
        return started ? claim.acknowledge() : claim.rollback();
    }

    @Override
    public String toString() {
        QueueItem item = claim.getItem();
        return "QueueDispatch{item=" + (item == null ? null : item.getId())
                + ", onDispatchThread=" + isOnDispatchThread() + "}";
    }
}
