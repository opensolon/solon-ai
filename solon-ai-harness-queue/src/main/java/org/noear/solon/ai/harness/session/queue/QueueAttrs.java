/*
 * Copyright 2017-2026 noear.org and authors
 * Licensed under the Apache License, Version 2.0.
 */
package org.noear.solon.ai.harness.session.queue;

/**
 * queue 插件的会话属性<b>契约键</b>登记（{@code session.queue.*} 命名空间归本插件）。
 *
 * <p>原登记在核心 {@code HarnessAttrs}，随 queue 独立为插件模块迁到这里：核心不再知道队列的存在。
 * 派发事务槽位已上提为核心的 {@code HarnessAttrs.TURN_DISPATCH}（见 {@code TurnDispatch}）。</p>
 *
 * <p><b>键值不可变</b>：{@link #QUEUE_ITEMS} 会写进 FlowContext 持久化，改值会丢失存量队列。</p>
 *
 * @author noear
 * @since 4.2.0
 */
public interface QueueAttrs {


    /**
     * 队列本体（attrs 中存活的运行态 Deque&lt;QueueItem&gt;，未持久化）
     */
    String QUEUE = "session.queue";

    /**
     * 队列项快照投影（FlowContext.data 的持久化 key，值为 List&lt;Map&gt;）
     */
    String QUEUE_ITEMS = "session.queue.items";

    /**
     * 队列在飞项（运行中任务的队列项，drain 暂存）
     */
    String QUEUE_IN_FLIGHT = "session.queue.inFlight";

    /**
     * 队列代际（cancelPending 递增，用于拒绝旧代际的 requeue）
     */
    String QUEUE_GENERATION = "session.queue.generation";

    /**
     * 队列领取标记（claimNext 期间的原子占位，方法返回即释放）
     */
    String QUEUE_CLAIM = "session.queue.claim";
}
