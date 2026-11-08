/*
 * Copyright 2017-2026 noear.org and authors
 * Licensed under the Apache License, Version 2.0.
 */
package org.noear.solon.ai.harness.host;

import org.noear.solon.ai.harness.hitl.HitlCard;
import org.noear.solon.lang.Nullable;
import org.noear.solon.lang.Preview;

import java.util.Collections;
import java.util.List;

/**
 * 一轮运行的终态（每轮恰好一个）。
 *
 * @author noear
 * @since 4.2.0
 */
@Preview("4.2.0")
public final class RunOutcome {
    /** 结束方式 */
    public enum End {
        /** 正常结束 */
        COMPLETED,
        /** 出错（开流失败、流出错；原因见 {@link #getError()}） */
        FAILED,
        /** 被中断（含调度窗口内被取消、从未开流的情况，见 {@link #isStreamOpened()}） */
        CANCELLED
    }

    /** 被中断的原因（只在 {@link End#CANCELLED} 时有值） */
    public enum CancelCause {
        /** {@code host.interrupt(tenant, sid)}：用户点「停止」（清积压、写取消消息） */
        INTERRUPT_ALL,
        /** {@code admin.interrupt(sid, CANCEL_STREAM)}：程序取消，只停当前一轮 */
        CANCEL_STREAM,
        /** {@code admin.interrupt(sid, PREEMPT_STREAM)}：为新一轮腾位 */
        PREEMPT_STREAM,
        /** {@link RunHandle#cancel()}，含 {@link HarnessHost#stream} 的下游取消（客户端断开） */
        HANDLE,
        /** 不经本骨架的取消（如直接调用 {@code engine.interrupt}） */
        EXTERNAL
    }

    private final End end;
    private final CancelCause cancelCause;
    private final Throwable error;
    private final String resultText;
    private final RunUsage usage;
    private final boolean streamOpened;
    private final boolean abnormal;
    private final List<HitlCard> hitlCards;

    RunOutcome(End end, @Nullable CancelCause cancelCause, @Nullable Throwable error, @Nullable String resultText,
               RunUsage usage, boolean streamOpened, boolean abnormal, @Nullable List<HitlCard> hitlCards) {
        this.end = end;
        this.cancelCause = end == End.CANCELLED ? (cancelCause == null ? CancelCause.EXTERNAL : cancelCause) : null;
        this.error = error;
        this.resultText = resultText;
        this.usage = usage == null ? RunUsage.EMPTY : usage;
        this.streamOpened = streamOpened;
        this.abnormal = abnormal;
        this.hitlCards = hitlCards == null ? Collections.emptyList() : hitlCards;
    }

    public End getEnd() {
        return end;
    }

    public boolean isCompleted() {
        return end == End.COMPLETED;
    }

    public boolean isFailed() {
        return end == End.FAILED;
    }

    public boolean isCancelled() {
        return end == End.CANCELLED;
    }

    /**
     * 被中断的原因（仅 CANCELLED 时非空）。宿主据此区分「用户停止」（要给用户回执）与
     * 「客户端断开 / 程序取消」（通常不需要回执）。
     */
    @Nullable
    public CancelCause getCancelCause() {
        return cancelCause;
    }

    /** 仅 FAILED 时非空 */
    @Nullable
    public Throwable getError() {
        return error;
    }

    /** 本轮最终回答文本（没有最终回答时为 null） */
    @Nullable
    public String getResultText() {
        return resultText;
    }

    /** 用量（永不为 null） */
    public RunUsage getUsage() {
        return usage;
    }

    /** 是否真的开了流（调度窗口内被取消、开流前失败为 false） */
    public boolean isStreamOpened() {
        return streamOpened;
    }

    /**
     * ReAct 是否异常结束（如超出 maxTurns 被强制收尾）。流本身正常结束，所以结束方式仍是 COMPLETED；
     * 此时 {@link #getResultText()} 可能是半成品，宿主通常应改用安全文案、按失败计费或提示用户。
     */
    public boolean isAbnormal() {
        return abnormal;
    }

    /** 本轮因审批挂起时尚未决策的卡（同时已经 {@link HostSink#hitl} 推送；决策经 {@link HarnessHost#decide} 提交） */
    public List<HitlCard> getHitlCards() {
        return hitlCards;
    }

    @Override
    public String toString() {
        return "RunOutcome{" + end + (cancelCause != null ? "(" + cancelCause + ")" : "")
                + (abnormal ? ", abnormal" : "")
                + (error != null ? ", error=" + error : "") + ", opened=" + streamOpened + ", " + usage + "}";
    }
}
