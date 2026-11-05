/*
 * Copyright 2017-2026 noear.org and authors
 * Licensed under the Apache License, Version 2.0.
 */
package org.noear.solon.ai.harness.extension.queue;

/**
 * 领取一条队列任务的运行者。
 *
 * <p>「上下文完整性」是它存在的理由：拿到的是 {@link QueueClaim}，自带领取时的代际快照
 * 与收口方法（{@code acknowledge} / {@code rollback}），宿主不再需要自己记住 item id、
 * 也不需要在终态现取代际。历史上还有一个只拿 {@code QueueItem} 的布尔型同步变体
 * （{@code QueueTaskRunner}），已随双路径收敛删除。</p>
 *
 * <p><b>返回值即收口契约</b>（4.2.2 收敛）：收口时机只有两种，正好由布尔表达——</p>
 * <ul>
 *   <li>{@code true}：任务在回调返回前<b>已同步完成</b>（如同步命令），内核当场
 *       {@code acknowledge()} 收口；若确认被拒（期间被 Stop），内核改走回滚丢弃
 *       并返回 {@link DrainStatus#START_FAILED}，循环自然终止；</li>
 *   <li>{@code false}：任务只是<b>异步启动</b>（如 Agent 流），终态由宿主在流终态
 *       调 {@code turn.settle(..)} 收口（turn = 回调里捕获的 {@link QueueDispatch}）。</li>
 * </ul>
 *
 * <p>异步返回 {@code true}（谎报完成）会让任务在流失败后无法回滚重试；同步返回
 * {@code false}（不收口）会留下悬挂 claim 挡住后续领取。因此返回值必须诚实。</p>
 *
 * <p>handler 抛异常等价于「启动失败」，内核会自动回滚（用领取瞬间绑定的代际快照）。</p>
 *
 * @author noear
 * @since 4.2.1
 */
@FunctionalInterface
public interface QueueClaimHandler {
    /**
     * @return true = 回调返回前任务已同步完成（内核当场收口）；
     *         false = 异步启动，终态由宿主持 {@code QueueDispatch} 收口
     */
    boolean run(QueueClaim claim) throws Exception;
}
