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
    private final Disposition disposition;
    private final RejectReason rejectReason;
    private final String rejectDetail;
    private final int backlogPosition;
    private final String owner;
    private final RunHandle run;
    private final String replyText;
    private final List<HitlCard> hitlCards;

    private SubmitResult(Disposition disposition, RejectReason rejectReason, String rejectDetail, int backlogPosition, String owner,
                         RunHandle run, String replyText, List<HitlCard> hitlCards) {
        this.disposition = disposition;
        this.rejectReason = rejectReason;
        this.rejectDetail = rejectDetail;
        this.backlogPosition = backlogPosition;
        this.owner = owner;
        this.run = run;
        this.replyText = replyText;
        this.hitlCards = hitlCards == null ? Collections.emptyList() : hitlCards;
    }

    static SubmitResult of(Disposition disposition) {
        return new SubmitResult(disposition, null, null, 0, null, null, null, null);
    }

    static SubmitResult started(RunHandle run) {
        return new SubmitResult(Disposition.STARTED, null, null, 0, null, run, null, null);
    }

    static SubmitResult reply(@Nullable String text) {
        return new SubmitResult(Disposition.REPLY, null, null, 0, null, null, text, null);
    }

    static SubmitResult hitl(List<HitlCard> cards) {
        return new SubmitResult(Disposition.HITL, null, null, 0, null, null, null, cards);
    }

    static SubmitResult deferred(int position) {
        return new SubmitResult(Disposition.DEFERRED, null, null, position, null, null, null, null);
    }

    static SubmitResult rejected(RejectReason reason, @Nullable String detail) {
        return new SubmitResult(Disposition.REJECTED, reason, detail, 0, null, null, null, null);
    }

    static SubmitResult notOwner(@Nullable String owner) {
        return new SubmitResult(Disposition.REJECTED, RejectReason.NOT_OWNER, owner, 0, owner, null, null, null);
    }

    /** 这次输入的处置（与同一次提交里回调看到的 {@link HostInput#getDisposition()} 一致） */
    public Disposition getDisposition() {
        return disposition;
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
        switch (disposition) {
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
        return "SubmitResult{" + disposition + (rejectReason != null ? ", " + rejectReason : "")
                + (disposition == Disposition.DEFERRED ? ", position=" + backlogPosition : "")
                + (owner != null ? ", owner=" + owner : "") + "}";
    }
}
