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

import java.util.Collections;
import java.util.List;

/**
 * {@link HarnessHost#stream} 的元素：本次请求「发生了什么」的扁平信号，适合直接转成 SSE。
 *
 * <p>顺序：{@code REPLY?} → {@code EVENT*} → {@code HITL?} → {@code FINISHED}（开了流时）；
 * 没开流时只有一个结局信号（{@code REPLY} / {@code DEFERRED} / {@code STEERED} / {@code REJECTED} / {@code HITL} /
 * {@code IGNORED}），随后流结束。</p>
 *
 * @author noear
 * @since 4.2.0
 */
@Preview("4.2.0")
public final class HostSignal {
    public enum Type {
        /** 命令回执（{@link #getText()}） */
        REPLY,
        /** 流事件（{@link #getEvent()}） */
        EVENT,
        /** 已排队（{@link #getPosition()}） */
        DEFERRED,
        /** 已作为插话交给运行中的那一轮 */
        STEERED,
        /** 未受理（{@link #getRejectReason()} / {@link #getText()} 为详情） */
        REJECTED,
        /** 有待审批卡（{@link #getCards()}；卡已失效时为空） */
        HITL,
        /** 空输入 */
        IGNORED,
        /** 本轮终态（{@link #getOutcome()}） */
        FINISHED
    }

    private final Type type;
    private final String text;
    private final AgentEvent event;
    private final int position;
    private final RejectReason rejectReason;
    private final List<HitlCard> cards;
    private final RunOutcome outcome;

    private HostSignal(Type type, String text, AgentEvent event, int position, RejectReason rejectReason,
                       List<HitlCard> cards, RunOutcome outcome) {
        this.type = type;
        this.text = text;
        this.event = event;
        this.position = position;
        this.rejectReason = rejectReason;
        this.cards = cards == null ? Collections.emptyList() : cards;
        this.outcome = outcome;
    }

    static HostSignal of(Type type) {
        return new HostSignal(type, null, null, 0, null, null, null);
    }

    static HostSignal reply(String text) {
        return new HostSignal(Type.REPLY, text, null, 0, null, null, null);
    }

    static HostSignal event(AgentEvent event) {
        return new HostSignal(Type.EVENT, null, event, 0, null, null, null);
    }

    static HostSignal deferred(int position) {
        return new HostSignal(Type.DEFERRED, null, null, position, null, null, null);
    }

    static HostSignal rejected(RejectReason reason, @Nullable String detail) {
        return new HostSignal(Type.REJECTED, detail, null, 0, reason, null, null);
    }

    static HostSignal hitl(List<HitlCard> cards) {
        return new HostSignal(Type.HITL, null, null, 0, null, cards, null);
    }

    static HostSignal finished(RunOutcome outcome) {
        return new HostSignal(Type.FINISHED, null, null, 0, null, null, outcome);
    }

    public Type getType() {
        return type;
    }

    /** REPLY 的回执文本；REJECTED 的详情（可空） */
    @Nullable
    public String getText() {
        return text;
    }

    @Nullable
    public AgentEvent getEvent() {
        return event;
    }

    public int getPosition() {
        return position;
    }

    @Nullable
    public RejectReason getRejectReason() {
        return rejectReason;
    }

    public List<HitlCard> getCards() {
        return cards;
    }

    @Nullable
    public RunOutcome getOutcome() {
        return outcome;
    }

    @Override
    public String toString() {
        return "HostSignal{" + type + "}";
    }
}
