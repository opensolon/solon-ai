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
 * <p>只用来给调用方（如 HTTP 控制器）映射状态码：要给用户看的内容已经经 {@link HostSink} 发出，
 * 调用方不需要再按结果做任何收尾。</p>
 *
 * @author noear
 * @since 4.2.0
 */
@Preview("4.2.0")
public final class Submitted {
    /** 结果类型 */
    public enum Kind {
        /** 已开流（结果经 {@link HostSink#event} / {@link HostSink#runFinished} 推送） */
        STARTED,
        /** 只有回执（命令已执行），已经 {@link HostSink#reply} */
        REPLY,
        /** 会话忙，已排队，空闲后自动续发 */
        DEFERRED,
        /** 未受理（原因见 {@link #getRejectReason()}，提示已经发出） */
        REJECTED,
        /** 审批已记录，本批仍有未决卡（已经 {@link HostSink#hitl}），或审批卡已失效 */
        HITL,
        /** 空输入，无需处理 */
        IGNORED
    }

    private final Kind kind;
    private final RejectReason rejectReason;
    private final int backlogPosition;

    private Submitted(Kind kind, @Nullable RejectReason rejectReason, int backlogPosition) {
        this.kind = kind;
        this.rejectReason = rejectReason;
        this.backlogPosition = backlogPosition;
    }

    static Submitted of(Kind kind) {
        return new Submitted(kind, null, 0);
    }

    static Submitted deferred(int position) {
        return new Submitted(Kind.DEFERRED, null, position);
    }

    static Submitted rejected(RejectReason reason) {
        return new Submitted(Kind.REJECTED, reason, 0);
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
     * 建议的 HTTP 状态码：STARTED / REPLY / HITL / IGNORED → 200，DEFERRED → 202，
     * REJECTED → 404（会话不存在）/ 307（not_owner）/ 403（作用域）/ 503（引擎已关闭）/ 409（其余）。
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
        return "Submitted{" + kind + (rejectReason != null ? ", " + rejectReason : "")
                + (kind == Kind.DEFERRED ? ", position=" + backlogPosition : "") + "}";
    }
}
