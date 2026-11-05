/*
 * Copyright 2017-2026 noear.org and authors
 * Licensed under the Apache License, Version 2.0.
 */
package org.noear.solon.ai.harness.extension.queue;

/**
 * 一次队列领取的结果。
 *
 * <p>三态而非布尔：宿主需要区分「没有任务」（可以停）与「启动了但立刻失败」
 * （必须停，否则会对同一条失败任务无限重试）。此前这个区分靠宿主自己往会话 attrs
 * 写一个 startFailed 标记实现。</p>
 *
 * @author noear
 * @since 4.2.1
 */
public enum DrainStatus {
    /** 已领取并交给 handler 启动成功 */
    STARTED,
    /** 没有可领取的任务（队空、已有 in-flight、或领取权被他人持有） */
    NO_TASK,
    /** 已领取，但 handler 启动失败；任务已回滚到队首，勿立即重试 */
    START_FAILED
}
