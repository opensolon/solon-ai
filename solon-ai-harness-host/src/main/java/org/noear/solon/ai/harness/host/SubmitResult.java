/*
 * Copyright 2017-2026 noear.org and authors
 * Licensed under the Apache License, Version 2.0.
 */
package org.noear.solon.ai.harness.host;

import org.noear.solon.ai.harness.input.InputOutcome.RejectReason;
import org.noear.solon.lang.Nullable;
import org.noear.solon.lang.Preview;

/**
 * {@link HarnessHost#submit} / {@link HarnessHost#decide} 的结果。
 *
 * <p>只用来给调用方（如 HTTP 控制器、网关）判断「已经发生了什么」：要给用户看的内容已经经 {@link HostSink}
 * 发出，调用方不需要再按结果做任何收尾。</p>
 *
 * @author noear
 * @since 4.2.0
 */
@Preview("4.2.0")
public final class SubmitResult {
    /**
     * 结果类型
     *
     * <p>只描述「已经发生了什么」，不描述「接下来该做什么」（后者是引擎层的
     * {@code InputOutcome.Kind}，两者除了 TASK / STARTED 一一对应）。
     * 常量顺序与 {@code InputOutcome.Kind} 对齐，便于对照阅读。</p>
     */
    public enum Kind {
        /**
         * 本轮已交给执行器执行（流经 {@link HostSink#event} / {@link HostSink#runFinished} 推送）。
         *
         * <p>含义是「已经交出去了」，不保证流已真的跑起来：调度空档里被取消、启动失败时也是本值，
         * 失败会经 {@link HostSink#runFinished} 以 error 收尾。调用方不要把它当成「必有输出」。
         */
        STARTED,
        /**
         * 只有回执，没有任务（回执已经 {@link HostSink#reply}）。
         *
         * <p>包括「命令已执行」与「命令在本渠道不可用、未执行」两种事实，调用方动作相同。
         */
        REPLY,
        /**
         * 审批已记录但未续流：本批仍有未决卡（卡已 {@link HostSink#hitl}），或审批卡已失效。
         */
        HITL,
        /** 空输入（无文本也无附件），未做任何处理 */
        IGNORED,
        /** 会话忙，已入积压，空闲后由宿主续发（位置见 {@link #getBacklogPosition()}） */
        DEFERRED,
        /**
         * 未受理（原因见 {@link #getRejectReason()}）。
         *
         * <p>提示与转发都已由对应的结构化回调交给宿主：{@link HostSink#rejected}（一般拒绝）、
         * {@link HostSink#notOwner}（多节点未持有，持有者见 {@link #getOwner()}）。</p>
         */
        REJECTED
    }

    private final Kind kind;
    private final RejectReason rejectReason;
    private final int backlogPosition;
    private final String owner;

    private SubmitResult(Kind kind, @Nullable RejectReason rejectReason, int backlogPosition, @Nullable String owner) {
        this.kind = kind;
        this.rejectReason = rejectReason;
        this.backlogPosition = backlogPosition;
        this.owner = owner;
    }

    static SubmitResult of(Kind kind) {
        return new SubmitResult(kind, null, 0, null);
    }

    static SubmitResult deferred(int position) {
        return new SubmitResult(Kind.DEFERRED, null, position, null);
    }

    static SubmitResult rejected(RejectReason reason) {
        return new SubmitResult(Kind.REJECTED, reason, 0, null);
    }

    static SubmitResult notOwner(@Nullable String owner) {
        return new SubmitResult(Kind.REJECTED, RejectReason.NOT_OWNER, 0, owner);
    }

    public Kind getKind() {
        return kind;
    }

    /** 拒绝原因（仅 REJECTED；与引擎同一张码表，会话不存在为 {@code SESSION_NOT_FOUND}） */
    @Nullable
    public RejectReason getRejectReason() {
        return rejectReason;
    }

    /** 排队位置（仅 DEFERRED，从 1 计） */
    public int getBacklogPosition() {
        return backlogPosition;
    }

    /**
     * 持有者节点标识（仅 {@code NOT_OWNER}，可空）：网关据此把请求转发过去，不必自己去解析拒绝详情。
     *
     * @since 4.2.0
     */
    @Nullable
    public String getOwner() {
        return owner;
    }

    /**
     * 建议的 HTTP 状态码：STARTED / REPLY / HITL / IGNORED → 200，DEFERRED → 202，
     * REJECTED 按 {@link RejectReason}：SESSION_NOT_FOUND → 404，NOT_OWNER → 307，
     * SCOPE_UNAVAILABLE → 403，ENGINE_CLOSED → 503，其余（BUSY / HITL_PENDING /
     * ATTACHMENTS_NOT_DEFERRABLE / BACKLOG_UNAVAILABLE / SCOPE_NOT_DEFERRABLE）→ 409。
     *
     * <p>与 {@code HarnessResult.Code#httpStatus()} 同一套口径：不采用 HTTP 的宿主（IM 网关、RPC）忽略即可。</p>
     */
    public int httpStatus() {
        switch (kind) {
            case DEFERRED:
                return 202;
            case REJECTED:
                if (rejectReason == null) return 409;
                switch (rejectReason) {
                    case SESSION_NOT_FOUND:
                        return 404;
                    case NOT_OWNER:
                        return 307;
                    case SCOPE_UNAVAILABLE:
                        return 403;
                    case ENGINE_CLOSED:
                        return 503;
                    default:
                        return 409;
                }
            default:
                return 200;
        }
    }

    @Override
    public String toString() {
        return "SubmitResult{" + kind + (rejectReason != null ? ", " + rejectReason : "")
                + (kind == Kind.DEFERRED ? ", position=" + backlogPosition : "")
                + (owner != null ? ", owner=" + owner : "") + "}";
    }
}
