/*
 * Copyright 2017-2026 noear.org and authors
 * Licensed under the Apache License, Version 2.0.
 */
package org.noear.solon.ai.harness.extension.loop;

/**
 * loop 一轮的执行器：执行 prompt，返回本轮结论 {@link LoopOutcome}。
 *
 * <pre>{@code
 * loop.addExecutor("cli", "cli"::equals, (sessionId, prompt) -> {
 *     if (!terminalIdle()) {
 *         return LoopOutcome.skip("terminal busy");   // 不计错误
 *     }
 *     return LoopOutcome.done(runRound(prompt));       // 抛异常 = 失败（计错误、退避重试）
 * });
 * }</pre>
 *
 * <p>loop 只需最终文本（轮次计数与 lastResult），不需要 token / 工具证据，因此不与 goal 共用签名。</p>
 *
 * <p><b>没有 agentName 参数</b>：子代理归属是会话属性（宿主在创建任务时写入会话选择），
 * 执行器从 session 读即可。</p>
 */
@FunctionalInterface
public interface LoopExecutor {
    /**
     * @return 本轮结论（不得为 null）
     * @throws Exception 执行失败（等价于 {@link LoopOutcome#failed}）
     */
    LoopOutcome handle(String sessionId, String prompt) throws Exception;
}
