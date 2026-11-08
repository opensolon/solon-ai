/*
 * Copyright 2017-2026 noear.org and authors
 * Licensed under the Apache License, Version 2.0.
 */
package org.noear.solon.ai.harness.host;

import org.noear.solon.ai.agent.AgentEvent;
import org.noear.solon.ai.harness.hitl.HitlCard;
import org.noear.solon.ai.harness.input.InputOrigin;
import org.noear.solon.ai.harness.input.InputOutcome.RejectReason;
import org.noear.solon.lang.Nullable;
import org.noear.solon.lang.Preview;

import java.util.List;

/**
 * 宿主输出通道：{@link HarnessHost} 把「发生了什么」全部经这里交给宿主，由宿主决定「怎么呈现」。
 *
 * <p><b>全部方法都有缺省实现</b>，一个 {@code new HostSink(){ }} 就能跑通完整一轮（缺省把事件记到日志）。
 * 宿主按需要覆盖：最少只需覆盖 {@link #reply}（发文本）与 {@link #event}（推流事件）两个投递出口。</p>
 *
 * <p>框架只表达事实，不表达文案：拒绝、排队、审批失效、非持有节点都各有结构化回调，
 * 文案、国际化、要不要提示用户，都在宿主自己的实现里决定。缺省实现一律「记日志、不静默丢弃」，
 * 所以只覆盖一部分方法也不会让用户什么也收不到。</p>
 *
 * <p>何时发、发给谁、终态发几次由 {@link HarnessHost} 保证：</p>
 * <ul>
 *   <li>每个 {@link #runStarted} 恰好配一个 {@link #runFinished}（含调度窗口内被取消、开流失败、流出错）；</li>
 *   <li>队列续发的一轮按当初排队那条消息的来源投递，不是触发续发的那次请求；</li>
 *   <li>回调可能在任意线程上执行，实现应线程安全；回调抛出的异常只记日志，不影响运行。</li>
 * </ul>
 *
 * @author noear
 * @since 4.2.0
 */
@Preview("4.2.0")
public interface HostSink {
    /**
     * 文本投递出口：命令回执、拒绝说明、排队提示、审批卡失效提示等「要直接给用户看的一句话」。
     *
     * <p>缺省只记日志（不静默丢弃）；真实宿主应覆盖为 WebSocket / SSE / IM 等投递。</p>
     */
    default void reply(InputOrigin origin, String text) {
        HostSinkSupport.LOG.info("[HarnessHost] reply not delivered (sink not overridden): origin={}, text={}", origin, text);
    }

    /**
     * 运行流事件出口（按到达顺序；投递目标为 {@code run.getOrigin()}）。
     *
     * <p>缺省只在 debug 级别记录（事件量大，不宜默认打 INFO）；需要推流的宿主覆盖本方法。</p>
     */
    default void event(HostRun run, AgentEvent event) {
        if (HostSinkSupport.LOG.isDebugEnabled()) {
            HostSinkSupport.LOG.debug("[HarnessHost] event not delivered (sink not overridden): run={}, event={}", run,
                    event == null ? null : event.getClass().getSimpleName());
        }
    }

    /**
     * 一条运行流即将开始（在命令回执之前、订阅之前调用；需要「流门复位」的宿主放在这里）。
     */
    default void runStarted(HostRun run) {
    }

    /**
     * 一条运行流结束（恰好一次）。
     *
     * @param error 出错时为异常；正常结束、被停止时为 null
     */
    default void runFinished(HostRun run, @Nullable Throwable error) {
    }

    /**
     * 有待审批的工具调用（运行流因审批挂起时、或一次审批后本批仍有未决卡时）。
     *
     * <p>用户的决策经 {@link HarnessHost#decide} 提交。</p>
     *
     * @param cards 尚未决策的审批卡
     */
    default void hitl(InputOrigin origin, List<HitlCard> cards) {
    }

    /**
     * 输入未受理（结构化）：原因为 {@link RejectReason}，{@code detail} 为引擎给出的原始说明（可空）。
     *
     * <p>宿主在这里决定文案与是否提示用户；缺省记 WARN。租户会话不存在为
     * {@link RejectReason#SESSION_NOT_FOUND}；多节点未持有为 {@link RejectReason#NOT_OWNER}（另有
     * {@link #notOwner} 更精确的回调）。</p>
     */
    default void rejected(InputOrigin origin, RejectReason reason, @Nullable String detail) {
        HostSinkSupport.LOG.warn("[HarnessHost] input rejected: origin={}, reason={}, detail={}", origin, reason, detail);
    }

    /**
     * 会话忙，输入已进积压（结构化）。
     *
     * @param position 排队位置（从 1 计）
     */
    default void deferred(InputOrigin origin, int position) {
        HostSinkSupport.LOG.info("[HarnessHost] input deferred: origin={}, position={}", origin, position);
    }

    /**
     * 审批决策已失效（任务已结束、或决策未命中挂起项），本轮未续流。
     */
    default void hitlExpired(InputOrigin origin) {
        HostSinkSupport.LOG.warn("[HarnessHost] hitl decision expired (no pending card): origin={}", origin);
    }

    /**
     * 多节点：会话由其它节点持有。
     *
     * <p>要不要把请求转发给 {@code owner}（以及怎么转发）是宿主 / 网关的决定；框架只告知事实。
     * 缺省链到 {@link #rejected}（原因 {@link RejectReason#NOT_OWNER}），保证不静默丢弃。</p>
     *
     * @param owner 持有者节点标识（可空）
     */
    default void notOwner(InputOrigin origin, @Nullable String owner) {
        rejected(origin, RejectReason.NOT_OWNER, owner);
    }
}
