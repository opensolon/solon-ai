/*
 * Copyright 2017-2026 noear.org and authors
 * Licensed under the Apache License, Version 2.0.
 */
package org.noear.solon.ai.harness.host;

import org.noear.solon.ai.harness.hitl.HitlCard;
import org.noear.solon.ai.harness.input.RejectReason;
import org.noear.solon.lang.Nullable;
import org.noear.solon.lang.Preview;

import java.util.Collections;
import java.util.List;

/**
 * {@link HarnessHost#submit} / {@link HarnessHost#decide} 的结果。
 *
 * <p>推送型宿主只用它映射状态码（内容已经经 {@link HostSink} 发出）；一问一答型宿主可以直接读结果：
 * 开了流时 {@link #getRun()} 等终态，只有回执时 {@link #getReplyText()}。</p>
 *
 * @author noear
 * @since 4.2.0
 */
@Preview("4.2.0")
public final class SubmitResult {
    /**
     * 结果类型（只描述「已经发生了什么」）。
     */
    public enum Kind {
        /**
         * 本轮已交给执行器执行（{@link #getRun()} 非空）。
         *
         * <p>含义是「已经交出去了」，不保证流已真的跑起来：调度空档里被取消、启动失败时也是本值，
         * 终态见 {@link RunOutcome}。</p>
         */
        STARTED,
        /** 只有回执，没有任务（{@link #getReplyText()}；命令无输出时为 null） */
        REPLY,
        /** 审批已记录但未续流：本批仍有未决卡（{@link #getHitlCards()}），或审批卡已失效（卡为空） */
        HITL,
        /** 空输入（无文本也无附件），未做任何处理 */
        IGNORED,
        /** 会话忙，已入积压，空闲后自动续发（位置见 {@link #getBacklogPosition()}） */
        DEFERRED,
        /** 会话忙，已作为插话交给正在运行的那一轮（{@code HarnessInput.whenBusy(BusyPolicy.STEER)}） */
        STEERED,
        /** 未受理（原因见 {@link #getRejectReason()}，持有者见 {@link #getOwner()}） */
        REJECTED
    }

    private final Kind kind;
    private final RejectReason rejectReason;
    private final String rejectDetail;
    private final int backlogPosition;
    private final String owner;
    private final RunHandle run;
    private final String replyText;
    private final List<HitlCard> hitlCards;

    private SubmitResult(Kind kind, RejectReason rejectReason, String rejectDetail, int backlogPosition, String owner,
                         RunHandle run, String replyText, List<HitlCard> hitlCards) {
        this.kind = kind;
        this.rejectReason = rejectReason;
        this.rejectDetail = rejectDetail;
        this.backlogPosition = backlogPosition;
        this.owner = owner;
        this.run = run;
        this.replyText = replyText;
        this.hitlCards = hitlCards == null ? Collections.emptyList() : hitlCards;
    }

    static SubmitResult of(Kind kind) {
        return new SubmitResult(kind, null, null, 0, null, null, null, null);
    }

    static SubmitResult started(RunHandle run) {
        return new SubmitResult(Kind.STARTED, null, null, 0, null, run, null, null);
    }

    static SubmitResult reply(@Nullable String text) {
        return new SubmitResult(Kind.REPLY, null, null, 0, null, null, text, null);
    }

    static SubmitResult hitl(List<HitlCard> cards) {
        return new SubmitResult(Kind.HITL, null, null, 0, null, null, null, cards);
    }

    static SubmitResult deferred(int position) {
        return new SubmitResult(Kind.DEFERRED, null, null, position, null, null, null, null);
    }

    static SubmitResult rejected(RejectReason reason, @Nullable String detail) {
        return new SubmitResult(Kind.REJECTED, reason, detail, 0, null, null, null, null);
    }

    static SubmitResult notOwner(@Nullable String owner) {
        return new SubmitResult(Kind.REJECTED, RejectReason.NOT_OWNER, owner, 0, owner, null, null, null);
    }

    public Kind getKind() {
        return kind;
    }

    /** 本次请求开出的那一轮（仅 STARTED） */
    @Nullable
    public RunHandle getRun() {
        return run;
    }

    /** 回执文本（仅 REPLY，命令无输出时为 null） */
    @Nullable
    public String getReplyText() {
        return replyText;
    }

    /** 尚未决策的审批卡（仅 HITL；卡已失效时为空） */
    public List<HitlCard> getHitlCards() {
        return hitlCards;
    }

    /** 拒绝原因（仅 REJECTED；与引擎同一张码表） */
    @Nullable
    public RejectReason getRejectReason() {
        return rejectReason;
    }

    /** 拒绝详情（仅 REJECTED，可空） */
    @Nullable
    public String getRejectDetail() {
        return rejectDetail;
    }

    /** 排队位置（仅 DEFERRED，从 1 计） */
    public int getBacklogPosition() {
        return backlogPosition;
    }

    /** 持有者节点标识（仅 {@code NOT_OWNER}，可空）：网关据此转发 */
    @Nullable
    public String getOwner() {
        return owner;
    }

    /**
     * 建议的 HTTP 状态码：STARTED / REPLY / HITL / IGNORED / STEERED → 200，DEFERRED → 202，
     * REJECTED 按 {@link RejectReason}：SESSION_NOT_FOUND → 404，NOT_OWNER → 307，
     * SCOPE_UNAVAILABLE → 403，ENGINE_CLOSED → 503，其余 → 409。
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
