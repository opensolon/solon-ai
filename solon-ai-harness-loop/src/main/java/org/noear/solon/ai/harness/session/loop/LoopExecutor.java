/*
 * Copyright 2017-2026 noear.org and authors
 * Licensed under the Apache License, Version 2.0.
 */
package org.noear.solon.ai.harness.session.loop;

/**
 * loop 一轮的执行器：执行 prompt，返回最终文本（null = 本入口不认领，穿透到下一注册者）。
 *
 * <p>loop 只需最终文本（轮次计数与 lastResult），不需要 token / 工具证据，因此不与 goal 共用签名。</p>
 *
 * <p><b>没有 agentName 参数</b>：子代理归属是会话属性（宿主在创建任务时写入会话选择），
 * 执行器从 session 读即可。</p>
 */
@FunctionalInterface
public interface LoopExecutor {
    String handle(String sessionId, String prompt);
}
