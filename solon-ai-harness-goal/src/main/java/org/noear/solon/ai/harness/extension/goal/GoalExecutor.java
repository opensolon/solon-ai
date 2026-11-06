/*
 * Copyright 2017-2026 noear.org and authors
 * Licensed under the Apache License, Version 2.0.
 */
package org.noear.solon.ai.harness.extension.goal;

/**
 * goal 执行器：执行一轮 prompt 并返回本轮结论（经 {@link GoalExtension#addExecutor} 注册）。
 *
 * <p>与 loop 的 {@code LoopExecutor} 同形：两个执行域各自定义通道与返回类型，互不共享。</p>
 *
 * <p><b>归属只由认领谓词决定</b>：谓词命中的首个执行器即本轮执行者，其结论就是最终结论，
 * 不存在「返回 null 交给下一个」的穿透。通道此刻不能跑（终端被占、会话忙、运行时未就绪）
 * 返回 {@link GoalOutcome#skip}；失败直接抛异常或返回 {@link GoalOutcome#failed}。</p>
 *
 * <p><b>没有 agentName 参数</b>：子代理归属是会话属性（{@code engine.getSessionAgent}），执行者从会话读取。</p>
 *
 * @author noear
 * @since 4.2.0
 */
@FunctionalInterface
public interface GoalExecutor {
    /**
     * @param sessionId 会话
     * @param prompt    本轮提示词（程序生成，不应再被当作命令或 @提及解析）
     * @return 本轮结论（null 按失败处理）
     * @throws Exception 执行失败（按失败处理，参与错误分级熔断）
     */
    GoalOutcome handle(String sessionId, String prompt) throws Exception;
}
