/*
 * Copyright 2017-2026 noear.org and authors
 * Licensed under the Apache License, Version 2.0.
 */
package org.noear.solon.ai.harness.extension.steer;

import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.react.ReActOptionsAmend;
import org.noear.solon.ai.harness.AbsHarnessExtension;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.ai.harness.input.InputOrigin;
import org.noear.solon.ai.harness.spi.InputBacklog;
import org.noear.solon.ai.harness.session.SessionStatus;
import org.noear.solon.lang.Nullable;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * Steer 一键装配扩展 —— steer 能力从头到尾只接触这一个类。
 *
 * <p><b>宿主接入只需两步</b>：</p>
 * <ol>
 *   <li>装配：{@code engine.addExtensionIfAbsent(new SteerExtension())}；</li>
 *   <li>投递：{@link #steer(AgentSession, String, InputOrigin)}（IM / 命令 / CLI）或
 *       {@link #steerOnRun(AgentSession, String, String, String)}（Web 严格轮次绑定），按 {@link SteerResult.Code} 映射协议。</li>
 * </ol>
 * <p>没有开流前义务：上一任务被 interrupt / 异常终止后的残留，由拦截器在新任务 {@code onAgentStart}
 * 自动转排队并推出 {@link SteerDroppedEvent}。需要「可撤销」UI 时再用
 * {@link #cancelOnRun} / {@link #cancelPending} / {@link #pending}；回放历史用 {@link SteerProtocol#textOf}。</p>
 *
 * <p><b>为何走请求级挂载（而非默认拦截器）</b>：面板/提及选中的子代理也是一个顶层请求，
 * 此时目标代理不是主代理，挂在主代理默认拦截器上的组件根本不会生效，就会出现
 * 「选了子代理就不能插话」。挂到请求级 options 后，无论目标代理是主代理还是面板子代理都能命中；
 * 同时按 {@code isDelegated} 排除 {@code task}/{@code multitask} 委派任务——
 * 那些任务跑在临时会话上，没有前端邮箱语义，不应挂。</p>
 *
 * <p><b>输出走 AgentEvent</b>：{@link SteerAppliedEvent} / {@link SteerDroppedEvent} 推入流，
 * 宿主按事件类型映射为自身协议，不认识的宿主零成本忽略；不需要任何端口注入。</p>
 *
 * <p><b>未消费插话的持久化在 harness 内完成</b>（转排队），与有没有订阅者无关：
 * CLI / Desktop 也能把残留插话转为排队任务，不会丢。转排队目标是核心能力接口
 * {@link InputBacklog}（按接口从引擎现取，当前由 queue 插件实现）：steer 插件不依赖 queue 插件，
 * 未装 queue 时残留按转排队失败处理（暂存并经 {@link SteerDroppedEvent} 告知）。</p>
 *
 * <p>开关语义：停用时投递返回 {@link SteerResult.Code#DISABLED}；撤销 / 查询类接口与状态贡献不受控（不得阻止撤销）。</p>
 *
 * <p>会话属性键见 {@link SteerAttrs}；计数事实见 {@link #STATUS_PENDING}。</p>
 *
 * @author noear
 * @since 3.9.4
 */
public class SteerExtension extends AbsHarnessExtension {

    /**
     * 会话状态事实：待生效插话数（本轮邮箱 + 转排队失败暂存；无插话时为 0）。
     *
     * <p>经 {@code engine.status(session).get(SteerExtension.STATUS_PENDING)} 读取，
     * 适合徽章 / 状态栏这类只要计数的场景；要逐条撤销时用 {@link #pending}。</p>
     *
     * @since 4.2.0
     */
    public static final SessionStatus.Key<Integer> STATUS_PENDING = SessionStatus.Key.count("steer.pending");

    private final SteerInterceptor interceptor = new SteerInterceptor(this::backlog);

    private final SteerCommand steerCommand = new SteerCommand(this);

    /** 容量 / 长度约束（缺省 {@link SteerLimits#DEFAULT}，可经 {@link #builder()} 覆盖） */
    private final SteerLimits limits;

    /**
     * 宿主引擎（{@link #onAttach} 时注入），用于缺省忙判定（{@code engine.status(session)}）。
     * 未 attach 时为 null（纯单测直接 new 出来的门面）。
     */
    private volatile HarnessEngine engine;

    /**
     * 缺省构造器：输出走 AgentEvent，无需注入任何宿主端口。
     */
    public SteerExtension() {
        this(SteerLimits.DEFAULT);
    }

    private SteerExtension(SteerLimits limits) {
        this.limits = limits == null ? SteerLimits.DEFAULT : limits;
    }

    /** 构建器：仅用于覆盖容量 / 长度约束，其余全有缺省。 */
    public static Builder builder() {
        return new Builder();
    }

    @Override
    public void onAttach(HarnessEngine engine) {
        this.engine = engine;
        engine.getCommandRegistry().register(steerCommand);
    }

    /** 本扩展生效的容量 / 长度约束 */
    public SteerLimits getLimits() {
        return limits;
    }

    @Override
    public void onRequestAssemble(HarnessEngine engine, String agentName, boolean isDelegated, ReActOptionsAmend optionsAmend) {
        if (isDelegated == false) {
            optionsAmend.interceptorAdd(interceptor);
        }
    }

    /**
     * 会话状态贡献：待生效插话数（{@link #STATUS_PENDING}，本轮邮箱 + 转排队失败暂存）。
     *
     * <p>事实口径，不判开关；只读不创建状态（无状态即不贡献，读取方取缺省 0）。
     * 锁序为「会话锁 → 插话状态锁」，插话状态锁内从不回取会话锁，故可在会话锁内安全调用。</p>
     */
    @Override
    public void contributeSessionStatus(HarnessEngine engine, AgentSession session, SessionStatus.Builder builder) {
        SteerState state = SteerState.peek(session);
        if (state != null) {
            builder.put(STATUS_PENDING, state.size());
        }
    }

    @Override
    public void setEnabled(Boolean enabled) {
        super.setEnabled(enabled);
        steerCommand.setEnabled(enabled);
        interceptor.setEnabled(enabled);
    }

    // ==================== 投递 ====================

    /**
     * 向当前活跃任务插话（IM / 命令 / CLI 用）。
     *
     * <p>不比对轮次、steerId 由框架生成；忙判定按引擎缺省口径
     * （{@code engine.status(session)} 的 busy 或 admitting）。来源为 null 时回落 {@link InputOrigin#NONE}。</p>
     */
    public SteerResult steer(AgentSession session, String text, @Nullable InputOrigin origin) {
        return doSteer(session, null, null, text, origin, null, false);
    }

    /** 向当前活跃任务插话（来源缺省 {@link InputOrigin#NONE}）。 */
    public SteerResult steer(AgentSession session, String text) {
        return steer(session, text, InputOrigin.NONE);
    }

    /**
     * 严格轮次绑定投递（Web 首选）：{@code runId} 必填，空或与当前轮次不符即 {@link SteerResult.Code#TURN_CHANGED}。
     *
     * <p>任务的首个事件到达前无法确认归属，此时放行会让插话落入「任务结束 / 轮次切换」的窄窗。</p>
     *
     * @param steerId 前端生成的稳定 ID（可空，空则由后端生成）
     */
    public SteerResult steerOnRun(AgentSession session, String runId, @Nullable String steerId, String text) {
        return steerOnRun(session, runId, steerId, text, InputOrigin.NONE);
    }

    /** 严格轮次绑定投递，携带来源身份（残留转排队时保留渠道与回复地址）。 */
    public SteerResult steerOnRun(AgentSession session, String runId, @Nullable String steerId,
                                  String text, @Nullable InputOrigin origin) {
        return doSteer(session, runId, steerId, text, origin, null, true);
    }

    // ==================== 撤销 ====================

    /**
     * 撤销一条待生效插话（与 {@link #steerOnRun} 对称）。
     *
     * <p>steerId 校验（空 / 超长，按实例 {@link #getLimits()}）、轮次比对都在内核完成，
     * 宿主只需把 {@link SteerCancelResult.Code} 映射成自身协议。转排队失败而暂存的插话与轮次无关，
     * 任务结束后仍可撤销。</p>
     *
     * @param runId 调用方所见的当前运行 ID（本轮邮箱中的项必须匹配当前轮次）
     */
    public SteerCancelResult cancelOnRun(@Nullable AgentSession session, @Nullable String runId,
                                         @Nullable String steerId) {
        String id = steerId == null ? null : steerId.trim();
        if (id == null || id.isEmpty() || id.length() > limits.getMaxIdLength()) {
            return SteerCancelResult.of(SteerCancelResult.Code.INVALID_STEER_ID, steerId, 0);
        }

        SteerState state = SteerState.peek(session);
        if (state == null) {
            return SteerCancelResult.of(SteerCancelResult.Code.NOT_PENDING, id, 0);
        }
        return SteerMailbox.cancel(state, runId, id);
    }

    /**
     * 批量撤销待生效插话（「清空待生效插话」）。
     *
     * <p>{@code steerIds} 为 null 或空表示清空全部；只有真正移除的计入
     * {@link SteerCancelResult#getCanceled()}，已被消费的不算。状态恒为 {@link SteerCancelResult.Code#CANCELED}。</p>
     */
    public SteerCancelResult cancelPending(@Nullable AgentSession session, @Nullable Collection<String> steerIds) {
        SteerState state = SteerState.peek(session);
        if (state == null) {
            return SteerCancelResult.canceled(null, 0, 0);
        }
        return SteerMailbox.cancelAll(state, steerIds);
    }

    // ==================== 查询 ====================

    /**
     * 待生效的插话快照（按投递顺序；本轮邮箱在前、转排队失败而暂存的在后；无状态时返回空集合）。
     *
     * <p>宿主刷新/重连后靠它重建「可撤销」列表，否则 steerId 只存在于投递返回值中。</p>
     */
    public List<SteerMessage> pending(@Nullable AgentSession session) {
        SteerState state = SteerState.peek(session);
        return state == null ? Collections.<SteerMessage>emptyList() : state.snapshot();
    }

    /**
     * 当前活跃轮次标识（拦截器在 {@code onAgentStart} 写入、{@code onAgentEnd} 清除）。
     *
     * @return 当前轮次标识；无活跃轮次时为 null
     */
    @Nullable
    public String activeRunId(@Nullable AgentSession session) {
        SteerState state = SteerState.peek(session);
        return state == null ? null : state.runId();
    }

    // ==================== 内部 ====================

    private SteerResult doSteer(AgentSession session, String runId, String steerId, String text,
                                InputOrigin origin, BooleanSupplier busyRecheck, boolean requireRunId) {
        if (isEnabled() == false) {
            // 停用后仍投递会一直积压在邮箱（消费方拦截器已停用），必须当场拒绝。
            return new SteerResult(SteerResult.Code.DISABLED, steerId, 0);
        }

        BooleanSupplier busy = busyRecheck == null ? () -> isBusy(session) : busyRecheck;
        return SteerMailbox.steer(session, runId, steerId, text,
                origin == null ? InputOrigin.NONE : origin, busy, limits, requireRunId);
    }

    /**
     * 缺省忙判定：{@code SessionStatus.isBusy() || isAdmitting()}（活跃流 或 输入受理窗口）。
     *
     * <p>未 attach（无 engine）时视为「忙」，即放行、只依据轮次判定——纯单测 / 手工 new 的门面
     * 没有引擎可查，若视为「不忙」会把所有投递回滚成 NOT_RUNNING。</p>
     */
    private boolean isBusy(AgentSession session) {
        if (engine == null || session == null) {
            return true;
        }
        SessionStatus status = engine.status(session);
        return status.isBusy() || status.isAdmitting();
    }

    /**
     * 积压能力（每次现取：与插件装配顺序无关，运行期增删 queue 插件也即时生效）。
     * 未 attach 或未装积压插件时为 null。
     */
    @Nullable
    private InputBacklog backlog() {
        HarnessEngine e = engine;
        return e == null ? null : e.getExtension(InputBacklog.class).orElse(null);
    }

    // ==================== Builder ====================

    /** 构建器：只覆盖容量 / 长度约束，其余全有缺省（等价于 {@code new SteerExtension()}）。 */
    public static final class Builder {
        private int maxBoxSize = SteerMailbox.MAX_BOX_SIZE;
        private int maxTextLength = SteerMailbox.MAX_TEXT_LENGTH;
        private int maxIdLength = SteerMailbox.MAX_ID_LENGTH;

        private Builder() {
        }

        /** 邮箱容量上限（缺省 5）。频繁 steer 会加速上下文膨胀，高频场景应改用排队。 */
        public Builder maxBoxSize(int size) {
            this.maxBoxSize = size;
            return this;
        }

        /** 单条插话文本长度上限（缺省 4096）。 */
        public Builder maxTextLength(int length) {
            this.maxTextLength = length;
            return this;
        }

        /** steerId 长度上限（缺省 128）。 */
        public Builder maxIdLength(int length) {
            this.maxIdLength = length;
            return this;
        }

        public SteerExtension build() {
            return new SteerExtension(new SteerLimits(maxBoxSize, maxTextLength, maxIdLength));
        }
    }
}
