/*
 * Copyright 2017-2026 noear.org and authors
 * Licensed under the Apache License, Version 2.0.
 */
package org.noear.solon.ai.harness.host;

import org.noear.solon.lang.Preview;

/**
 * 一次输入的处置（去向）：框架把这条输入怎么处理掉了。只描述「已经发生了什么」。
 *
 * <p>同一次提交里，{@link SubmitResult#getDisposition()} 与回调里的 {@link HostInput#getDisposition()} 是同一个值。</p>
 *
 * <p>与 core 的 {@code InputOutcome.Kind} 不同：那是引擎告诉直连调用方「你该怎么执行」（如 {@code TASK}），
 * 这里是框架已经替宿主执行之后的结论（如 {@link #STARTED}），并多了只有宿主层才有的 {@link #STEERED}。</p>
 *
 * @author noear
 * @since 4.2.0
 */
@Preview("4.2.0")
public enum Disposition {
    /**
     * 本轮已交给执行器执行（{@link SubmitResult#getRun()} 非空）。
     *
     * <p>含义是「已经交出去了」，不保证流已真的跑起来：调度空档里被取消、启动失败时也是本值，
     * 终态见 {@link RunOutcome}。队列续发、后台轮次开出的一轮也是本值。</p>
     */
    STARTED,
    /** 只有回执，没有任务（{@link SubmitResult#getReplyText()}；命令无输出时为 null） */
    REPLY,
    /** 审批已记录但未续流：本批仍有未决卡（{@link SubmitResult#getHitlCards()}），或审批卡已失效（卡为空） */
    HITL,
    /** 空输入（无文本也无附件），未做任何处理 */
    IGNORED,
    /** 会话忙，已入积压，空闲后自动续发（位置见 {@link SubmitResult#getBacklogPosition()}） */
    DEFERRED,
    /** 会话忙，已作为插话交给正在运行的那一轮（{@code HarnessInput.whenBusy(BusyPolicy.STEER)}） */
    STEERED,
    /**
     * 未受理（原因见 {@link SubmitResult#getRejectReason()}，持有者见 {@link SubmitResult#getOwner()}）。
     * 会话不存在、非持有节点、忙时可执行命令触发了任务也是本值。
     */
    REJECTED
}
