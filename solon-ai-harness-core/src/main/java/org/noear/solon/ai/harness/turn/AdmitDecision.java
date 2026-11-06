/*
 * Copyright 2017-2026 noear.org and authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.noear.solon.ai.harness.turn;

import org.noear.solon.ai.harness.spi.TurnDispatch;
import org.noear.solon.lang.Nullable;

/**
 * 输入受理判定结果（一条输入现在该走哪条路）。
 *
 * <p><b>为什么需要它</b>：{@code handle(...)} 只回答「这条输入装配成了什么」，
 * 不回答「现在能不能接」。后者是入口最易写错的一段：要同时看运行流、受理窗口、
 * 待审批卡、积压、是否程序派发、是否忙态旁路命令——此前由每个宿主在
 * {@code synchronized (session.attrs())} 里自己拼，Web 端这份判定长达 50 余行，
 * 且用到的每个原语其实都已由 harness 提供。</p>
 *
 * <p>本类是这份判定的唯一输出：宿主只按 {@link Kind} 分派，不再自己组合忙态口径。</p>
 *
 * <p><b>边界</b>：本类<b>不</b>执行入积压、不渲染回执、不发终态。{@link Kind#DEFER} 由宿主
 * 交给积压插件（推理强度/思考模式属于宿主域概念，不引入 harness）；
 * {@link Kind#REJECT} 由宿主渲染拒因。内核没有 queue：本类只表达「延后」这一受理结局，
 * 延到哪里、怎么排由积压插件决定。</p>
 *
 * @author noear
 * @since 4.2.0
 */
public final class AdmitDecision {

    /** 受理路径。 */
    public enum Kind {
        /**
         * 正常受理：可以开流，且已占住受理位（{@link #getTicket()} 非空，用完必须 close）。
         */
        ADMIT,
        /**
         * 受理但无需占位：本轮由程序派发而来（如积压续发），或是一条已在真实任务上运行的忙态旁路命令。
         * 可以开流；{@link #getTicket()} 返回 {@link InputGate.Ticket#NONE}。
         */
        ADMIT_BYPASS,
        /**
         * 会话被占或积压里还有待执行输入：应延后执行。宿主把输入交给积压插件（如 queue 插件的
         * {@code enqueue}），再触发续发。仅当引擎上装有 {@code InputBacklog} 能力时才会给出；
         * 没有则忙态为 {@link #REJECT}（{@code "busy"}）。
         */
        DEFER,
        /**
         * 拒绝受理（HITL 挂起且本输入不属于豁免范围；或忙态而引擎没有积压能力）：
         * 宿主渲染 {@link #getRejectDetail()} 并收尾。
         */
        REJECT
    }

    private static final AdmitDecision DEFER =
            new AdmitDecision(Kind.DEFER, InputGate.Ticket.NONE, null, null, null);

    private final Kind kind;
    private final InputGate.Ticket ticket;
    private final TurnDispatch dispatch;
    private final String rejectReason;
    private final String rejectDetail;

    AdmitDecision(Kind kind, InputGate.Ticket ticket, @Nullable TurnDispatch dispatch,
                  @Nullable String rejectReason, @Nullable String rejectDetail) {
        this.kind = kind;
        this.ticket = ticket;
        this.dispatch = dispatch;
        this.rejectReason = rejectReason;
        this.rejectDetail = rejectDetail;
    }

    public static AdmitDecision admit(InputGate.Ticket ticket) {
        return new AdmitDecision(Kind.ADMIT, ticket, null, null, null);
    }

    /** 程序派发而来的一轮（不占位）。 */
    public static AdmitDecision bypassDispatch(@Nullable TurnDispatch dispatch) {
        return new AdmitDecision(Kind.ADMIT_BYPASS, InputGate.Ticket.NONE, dispatch, null, null);
    }

    /** 忙态旁路命令（不占位）。 */
    public static AdmitDecision bypassSideCommand() {
        return new AdmitDecision(Kind.ADMIT_BYPASS, InputGate.Ticket.NONE, null, null, null);
    }

    public static AdmitDecision defer() {
        return DEFER;
    }

    public static AdmitDecision reject(@Nullable String reason, @Nullable String detail) {
        return new AdmitDecision(Kind.REJECT, InputGate.Ticket.NONE, null, reason, detail);
    }

    public Kind getKind() {
        return kind;
    }

    /**
     * 受理凭据（幂等，未占到窗口时为 {@link InputGate.Ticket#NONE}）。
     *
     * <p>仅 {@link Kind#ADMIT} 可能真的占到；其余路径恒为空凭据，因此宿主可以
     * 在 {@code try-with-resources} 中无条件使用。</p>
     */
    public InputGate.Ticket getTicket() {
        return ticket;
    }

    /**
     * 本轮是否由程序派发而来（如积压续发；即会话有未收口的 {@link TurnDispatch} 且当前线程在其调用栈里）。
     *
     * <p>宿主据此决定回复路由来源：派发轮次应沿用派发事务的 origin，
     * 而不是本次请求的 origin。</p>
     */
    public boolean isDispatched() {
        return dispatch != null;
    }

    /** 拒绝原因码（仅 {@link Kind#REJECT}：{@code "hitl_pending"} / {@code "busy"}）。 */
    @Nullable
    public String getRejectReason() {
        return rejectReason;
    }

    /** 拒绝的可展示细节（仅 {@link Kind#REJECT}；通常是待审批的工具名）。 */
    @Nullable
    public String getRejectDetail() {
        return rejectDetail;
    }

    /**
     * 本轮派发事务（仅 {@link Kind#ADMIT_BYPASS} 且 {@link #isDispatched()} 时非空）。
     *
     * <p>供宿主取回复路由来源（{@link TurnDispatch#getOrigin()}）。</p>
     */
    @Nullable
    public TurnDispatch getDispatch() {
        return dispatch;
    }

    @Override
    public String toString() {
        return "AdmitDecision{kind=" + kind
                + ", acquired=" + ticket.isAcquired()
                + ", dispatch=" + isDispatched()
                + (rejectReason == null ? "" : ", reject=" + rejectReason) + "}";
    }
}
