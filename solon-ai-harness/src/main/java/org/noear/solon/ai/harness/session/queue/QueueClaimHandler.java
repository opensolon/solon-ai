/*
 * Copyright 2017-2026 noear.org and authors
 * Licensed under the Apache License, Version 2.0.
 */
package org.noear.solon.ai.harness.session.queue;

/**
 * 领取一条队列任务的运行者。
 *
 * <p>与 {@link QueueTaskRunner} 的区别是「上下文完整性」：这里拿到的是
 * {@link QueueClaim}，它自带领取时的代际快照与收口方法（{@code acknowledge} /
 * {@code rollback}），宿主不再需要自己记住 item id、也不需要在终态现取代际。</p>
 *
 * <p><b>语义</b>：handler 只负责「启动」。同步运行者可在返回前自行
 * {@code claim.acknowledge()}（一次性任务）；异步运行者直接返回，等任务真正
 * 结束（含失败）再收口。handler 抛异常等价于「启动失败」，内核会自动回滚。</p>
 *
 * @author noear
 * @since 4.2.1
 */
@FunctionalInterface
public interface QueueClaimHandler {
    void run(QueueClaim claim) throws Exception;
}
