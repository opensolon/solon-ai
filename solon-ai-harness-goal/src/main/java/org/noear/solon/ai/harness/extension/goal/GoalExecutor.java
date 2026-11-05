/*
 * Copyright 2017-2026 noear.org and authors
 * Licensed under the Apache License, Version 2.0.
 */
package org.noear.solon.ai.harness.extension.goal;

/**
 * goal 执行器：执行一轮 prompt 并返回本轮客观事实（经 {@link GoalExtension#addExecutor} 注册）。
 *
 * <p>与 loop 的 {@code LoopExecutor} 对称：两个执行域各自定义通道与返回类型，互不共享。</p>
 *
 * <p><b>返回 null = 本通道不认领</b>，交给下一个注册者（通道化注册时谓词是预过滤，命中后返回 null 仍会穿透）。</p>
 *
 * <p>返回值同时携带 token 消耗与工具证据，因此不再需要在会话 attrs 里落
 * 「上一轮指标」这类跨模块旁路键，也就没有「轮前重置 / 读完即删」那套无人能强制的时序约定。</p>
 *
 * <p><b>没有 agentName 参数</b>：子代理归属是<b>会话属性</b>（{@code engine.getSessionAgent}，
 * 由宿主／{@code /goal} 命令／自动化任务写入会话上下文），执行者从 session 读即可。</p>
 *
 * @author noear
 * @since 4.2.0
 */
@FunctionalInterface
public interface GoalExecutor {
    /**
     * @param sessionId 会话
     * @param prompt    本轮提示词
     * @return 本轮客观事实；null = 不认领
     */
    GoalRoundResult handle(String sessionId, String prompt);
}
