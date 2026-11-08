/*
 * Copyright 2017-2026 noear.org and authors
 * Licensed under the Apache License, Version 2.0.
 */
package org.noear.solon.ai.harness.host;

import org.noear.solon.ai.agent.AgentEvent;
import org.noear.solon.ai.harness.hitl.HitlCard;
import org.noear.solon.ai.harness.input.RejectReason;
import org.noear.solon.lang.Nullable;
import org.noear.solon.lang.Preview;

import java.util.List;

/**
 * 宿主输出通道：{@link HarnessHost} 把「发生了什么」全部经这里交给宿主，由宿主决定「怎么呈现」。
 *
 * <p><b>全部方法都有缺省实现</b>，一个 {@code new HostSink(){ }} 就能跑通完整一轮（缺省把事件记到日志）。
 * 三种常见写法：</p>
 * <ul>
 *   <li>一问一答（HTTP / SSE / 批处理）：不写 sink，用 {@link SubmitResult#getRun()} 或 {@link HarnessHost#stream}；</li>
 *   <li>推送型（WebSocket / IM）：覆盖 {@link #reply} 与 {@link #event} 两个投递出口；</li>
 *   <li>每轮有状态（分段渲染、按轮计费、按轮拦截器）：覆盖 {@link #open}，每轮返回一个自己的 {@link RunSink}。</li>
 * </ul>
 *
 * <p>回调分两级：</p>
 * <ul>
 *   <li><b>会话级</b>（参数 {@link HostInput}，带租户、会话、来源与输入）：受理、回执、排队、插话、拒绝、审批；</li>
 *   <li><b>轮次级</b>：{@link #open} 每轮一次（本轮开始），返回的 {@link RunSink} 收到这一轮的 reply / prepare / event / finished；
 *       缺省实现转发到本接口的 {@link #event} / {@link #runFinished}（回执转 {@link #reply}）。</li>
 * </ul>
 *
 * <p>框架只表达事实，不表达文案：文案、国际化、要不要提示用户，都在宿主自己的实现里决定。
 * 缺省实现一律「记日志、不静默丢弃」。回调可能在任意线程上执行，实现应线程安全；回调抛出的异常只记日志。
 * 队列续发的一轮按当初排队那条消息的来源投递，不是触发续发的那次请求。</p>
 *
 * @author noear
 * @since 4.2.0
 */
@Preview("4.2.0")
public interface HostSink {
    // ==================== 会话级 ====================

    /**
     * 输入已受理（getDisposition() 为 STARTED / REPLY / HITL / IGNORED，在分派之前调用；DEFERRED / REJECTED 不调用）。
     *
     * <p>宿主在这里持久化「用户这次的选择」（模型、子代理、回复路由等），或在命令无回执
     * （{@code !input.hasReply()}）时给一个兜底提示。{@code input.isBesideRunning()} 为 true 时不要改写回复路由。</p>
     */
    default void admitted(HostInput input) {
    }

    /**
     * 文本回执：没有任务的命令回执（{@code REPLY}）。命令触发的任务的回执经 {@link RunSink#reply} 发出，
     * 其缺省实现也转到这里——只覆盖本方法就能收到全部回执。
     *
     * <p>缺省只记日志（不静默丢弃）。</p>
     */
    default void reply(HostInput input, String text) {
        HostSinkSupport.LOG.info("[HarnessHost] reply not delivered (sink not overridden): input={}, text={}", input, text);
    }

    /**
     * 会话忙，输入已进积压（{@code input.getText()} 可用于回放用户消息）。
     *
     * @param position 排队位置（从 1 计）
     */
    default void deferred(HostInput input, int position) {
        HostSinkSupport.LOG.info("[HarnessHost] input deferred: input={}, position={}", input, position);
    }

    /** 忙时插话成功（{@code HarnessInput.whenBusy(BusyPolicy.STEER)}）：输入已交给正在运行的那一轮 */
    default void steered(HostInput input) {
    }

    /**
     * 输入未受理（结构化）：原因为 {@link RejectReason}，{@code detail} 为引擎给出的原始说明（可空）。
     *
     * <p>缺省记 WARN。会话不存在为 {@link RejectReason#SESSION_NOT_FOUND}，主体不符为
     * {@link RejectReason#SCOPE_UNAVAILABLE}；多节点未持有另有 {@link #notOwner}。</p>
     */
    default void rejected(HostInput input, RejectReason reason, @Nullable String detail) {
        HostSinkSupport.LOG.warn("[HarnessHost] input rejected: input={}, reason={}, detail={}", input, reason, detail);
    }

    /**
     * 多节点：会话由其它节点持有。要不要转发（以及怎么转发）是宿主 / 网关的决定。
     * 缺省链到 {@link #rejected}（原因 {@link RejectReason#NOT_OWNER}）。
     *
     * @param owner 持有者节点标识（可空）
     */
    default void notOwner(HostInput input, @Nullable String owner) {
        rejected(input, RejectReason.NOT_OWNER, owner);
    }

    /**
     * 有待审批的工具调用（运行流因审批挂起时、或一次审批后本批仍有未决卡时）。决策经 {@link HarnessHost#decide} 提交。
     */
    default void hitl(HostInput input, List<HitlCard> cards) {
    }

    /** 审批决策已失效（任务已结束、或决策未命中挂起项），本轮未续流 */
    default void hitlExpired(HostInput input) {
        HostSinkSupport.LOG.warn("[HarnessHost] hitl decision expired (no pending card): input={}", input);
    }

    // ==================== 轮次级 ====================

    /**
     * 一轮开始：返回这一轮的输出通道（每轮调用一次，在调用线程上）。
     *
     * <p>这里只建对象、不要向用户输出：本轮可能在调度窗口内就被取消、根本不开流。
     * 返回的 {@link RunSink} 的全部回调串行执行（不并发、前后有先行关系）；「流门复位」放在第一次输出时做。</p>
     *
     * <p>缺省返回 {@link RunSink#forwardTo}（转发到 {@link #event} / {@link #runFinished} / {@link #reply}）。
     * 需要按轮保存状态的宿主覆盖本方法，返回自己的 {@link RunSink}。</p>
     */
    default RunSink open(HostRun run) {
        return RunSink.forwardTo(this, run);
    }

    /**
     * 运行流事件（按到达顺序；投递目标为 {@code run.getInput().getOrigin()}）。缺省只在 debug 级别记录。
     */
    default void event(HostRun run, AgentEvent event) {
        if (HostSinkSupport.LOG.isDebugEnabled()) {
            HostSinkSupport.LOG.debug("[HarnessHost] event not delivered (sink not overridden): run={}, event={}", run,
                    event == null ? null : event.getClass().getSimpleName());
        }
    }

    /** 一轮结束（恰好一次；结束方式、错误、用量见 {@link RunOutcome}） */
    default void runFinished(HostRun run, RunOutcome outcome) {
    }
}
