/*
 * Copyright 2017-2026 noear.org and authors
 * Licensed under the Apache License, Version 2.0.
 */
package org.noear.solon.ai.harness.session.loop;

/**
 * 注入型回合的额外屏障（会话状态表达不了的事实，如终端所有权）。
 *
 * <p>CLI 注入回合在调度线程执行、直写终端，与主线程 readLine 并发。这与「会话忙不忙」是两回事：
 * 主线程跑 /help、/model 选择器时会话不忙，但终端正被占用。缺省恒 true（非 CLI 宿主不用关心）。</p>
 */
@FunctionalInterface
public interface InjectionGate {
    /** @return true 表示可以注入本轮 */
    boolean canInject();
}
