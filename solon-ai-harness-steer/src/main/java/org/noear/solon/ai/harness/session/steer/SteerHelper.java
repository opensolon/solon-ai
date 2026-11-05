/*
 * Copyright 2017-2026 noear.org and authors
 * Licensed under the Apache License, Version 2.0.
 */
package org.noear.solon.ai.harness.session.steer;

import org.noear.solon.Utils;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.harness.input.InputOrigin;
import org.noear.solon.ai.harness.session.InputBacklog;
import org.noear.solon.lang.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Queue;
import java.util.function.BooleanSupplier;

/**
 * 插话（Steering）静态内核：投递校验与回滚、撤销、残留转排队的唯一实现。
 *
 * <p><b>宿主不应依赖本类</b>：一律走 {@link SteerExtension} 门面
 * （经 {@code engine.getExtension(SteerExtension.class)} 获取）；注入消息格式见
 * {@link SteerMessages}。本类将在下一个 minor 版本收窄为包内可见。</p>
 *
 * <p>参考 Codex 的 steering 设计：用户在任务运行中插入的纠偏/补充消息存放在
 * {@link AgentSession#attrs()} 上的会话级状态（{@link SteerState}，transient，不落 snapshot.json，任务级易失），
 * 由拦截器在 {@code onReasonStart}（消息组装前的采样边界）排空并注入工作记忆。
 * 不打断进行中的模型流与工具调用；注入的消息只进当前任务的工作记忆，任务结束即失效。</p>
 *
 * <p><b>零持久化</b>：插话完全不写入 *.messages.ndjson，也不经 {@code session.addMessage}。
 * 因此它不占用 sessionWindowSize 名额、不会在后续任务中被当作初心（META_FIRST）重新入场。</p>
 *
 * <p><b>轮次与邮箱</b>：二者合并在 {@link SteerState} 一个对象、一把锁里，由拦截器在
 * {@code onAgentStart} 开轮、{@code onAgentEnd} 收轮。上一轮未收尾（interrupt / 异常）时，
 * 下一轮开轮自动把残留转排队——宿主不再承担任何「开流前交接」义务。</p>
 *
 * <p><b>邮箱契约</b>：容量上限由 {@link SteerLimits} 给出；判重、容量校验与入队在同一临界区内完成；
 * 投递后按轮次代际复查，异常时回滚本次投递；残留兜底绝不静默丢弃（Codex issue #15842 教训）——
 * 转排队失败的暂存为 orphans（永不注入），下次开轮/收轮重试。</p>
 *
 * @author noear
 * @since 2026
 */
public final class SteerHelper {
    private static final Logger LOG = LoggerFactory.getLogger(SteerHelper.class);

    /**
     * @deprecated 轮次与邮箱已合并为包内状态 {@link SteerState}，本键不再被读写；
     * 宿主一律经 {@link SteerExtension#pending} / {@link SteerExtension#activeRunId} 查询
     */
    @Deprecated
    public static final String ATTR_STEER_BOX = "harness.steerBox";
    /** @deprecated 同 {@link #ATTR_STEER_BOX} */
    @Deprecated
    public static final String ATTR_ACTIVE_RUN_ID = "harness.activeRunId";

    /** 缺省邮箱容量上限（Codex 建议：频繁 steer 加速上下文膨胀，高频场景应改用排队）。可经 {@link SteerLimits} 覆盖 */
    public static final int MAX_BOX_SIZE = 5;
    /** 缺省单条插话长度上限。可经 {@link SteerLimits} 覆盖 */
    public static final int MAX_TEXT_LENGTH = 4096;
    /** 缺省插话 ID 长度上限。可经 {@link SteerLimits} 覆盖 */
    public static final int MAX_ID_LENGTH = 128;

    /** @deprecated 用 {@link SteerMessages#PREFIX} */
    @Deprecated
    public static final String STEER_PREFIX = SteerMessages.PREFIX;

    /** @deprecated 用 {@link SteerMessages#SOURCE} */
    @Deprecated
    public static final String STEER_SOURCE = SteerMessages.SOURCE;

    private SteerHelper() { }

    /**
     * 当前待生效的插话快照（本轮邮箱在前、待重试的 orphans 在后；副本，不创建状态）。
     *
     * @deprecated 用 {@link SteerExtension#pending(AgentSession)}
     */
    @Deprecated
    public static List<SteerMessage> pending(AgentSession session) {
        SteerState state = SteerState.peek(session);
        return state == null ? Collections.<SteerMessage>emptyList() : state.snapshot();
    }

    /**
     * 插话入队（不比对轮次之外的约束：缺省容量、非严格轮次绑定）。
     *
     * @deprecated 用 {@link SteerExtension#steer(AgentSession, String, InputOrigin)} /
     * {@link SteerExtension#steerOnRun(AgentSession, String, String, String, InputOrigin)}
     */
    @Deprecated
    public static SteerResult steer(AgentSession session, String runId, String steerId, String text,
                                    InputOrigin origin, BooleanSupplier busyRecheck) {
        return steer(session, runId, steerId, text, origin, busyRecheck, SteerLimits.DEFAULT, false);
    }

    /**
     * 插话入队的唯一实现（Web / IM / 命令共用）。
     *
     * @param session      目标会话
     * @param runId        调用方所见的当前运行 ID。非空时必须与当前轮次相等（Web 场景防跨任务错投）；
     *                     为 null 时表示「绑定当前活跃任务」
     * @param steerId      插话 ID，可为空（由后端生成）
     * @param text         插话文本
     * @param origin       来源身份（可空，空同 {@link InputOrigin#NONE}）
     * @param busyRecheck  入邮箱后复查会话是否仍繁忙的回调，可为 null（为 null 时仅依据轮次判定）
     * @param limits       容量 / 长度约束
     * @param requireRunId 为 true 时 runId 必填：空即 {@link SteerStatus#TURN_CHANGED}，且不生成 steerId
     * @return 入队结果（status 与 /web/chat/steer 的应答码同名）
     */
    static SteerResult steer(AgentSession session, String runId, String steerId, String text,
                             InputOrigin origin, BooleanSupplier busyRecheck,
                             SteerLimits limits, boolean requireRunId) {
        if (session == null) {
            return new SteerResult(SteerStatus.NOT_RUNNING, null, 0);
        }
        if (text == null || text.trim().isEmpty()) {
            return new SteerResult(SteerStatus.EMPTY_TEXT, null, 0);
        }

        text = text.trim();
        if (text.length() > limits.getMaxTextLength()) {
            return new SteerResult(SteerStatus.TEXT_TOO_LONG, null, 0);
        }

        if (requireRunId && (runId == null || runId.trim().isEmpty())) {
            // 严格轮次绑定：先于 id 生成拒绝，避免调用方拿到一个无意义的 steerId
            return new SteerResult(SteerStatus.TURN_CHANGED, null, 0);
        }

        if (steerId == null || steerId.trim().isEmpty()) {
            steerId = "s_" + Utils.uuid();
        } else {
            steerId = steerId.trim();
            if (steerId.length() > limits.getMaxIdLength()) {
                return new SteerResult(SteerStatus.INVALID_STEER_ID, null, 0);
            }
        }

        SteerState state = SteerState.of(session);
        SteerMessage steer = new SteerMessage(steerId, text, origin);
        long epoch;
        synchronized (state) {
            if (state.runId == null) {
                // 任务尚未开轮或已结束：Web 端有明确 runId 时按 TURN_CHANGED 由前端决策；
                // IM 端（runId==null）按 NOT_RUNNING 由调用方如实告知用户，不代为降级。
                return new SteerResult(runId != null ? SteerStatus.TURN_CHANGED : SteerStatus.NOT_RUNNING, steerId, 0);
            }
            if (runId != null && runId.equals(state.runId) == false) {
                return new SteerResult(SteerStatus.TURN_CHANGED, steerId, 0);
            }
            // 先判重再判容量：满邮箱中的同 ID 重试仍应返回准确的重复结果，
            // 不能误报 BOX_FULL 后被前端降级为普通任务而造成重复执行。
            if (state.containsId(steerId)) {
                return new SteerResult(SteerStatus.DUPLICATE_STEER_ID, steerId, state.inbox.size());
            }
            if (state.inbox.size() >= limits.getMaxBoxSize()) {
                return new SteerResult(SteerStatus.BOX_FULL, steerId, state.inbox.size());
            }
            state.inbox.addLast(steer);
            epoch = state.epoch;
        }

        // 忙判定回调在锁外执行（可能查引擎状态，不得持锁）
        boolean busyAfterOffer = (busyRecheck == null) || busyRecheck.getAsBoolean();
        synchronized (state) {
            boolean sameRun = state.epoch == epoch;
            if (busyAfterOffer && sameRun) {
                return new SteerResult(SteerStatus.STEERED, steerId, state.inbox.size());
            }
            if (state.inbox.remove(steer)) {
                return new SteerResult(sameRun ? SteerStatus.NOT_RUNNING : SteerStatus.TURN_CHANGED, steerId, 0);
            }
        }

        // 已被注入或已随收轮转排队，无法回滚：必须报成功，否则调用方降级排队会把同一文本执行第二次
        return new SteerResult(SteerStatus.STEERED, steerId, 0);
    }

    /**
     * 撤销一条待生效插话。
     *
     * <p>orphans（转排队失败的暂存项）与轮次无关，任务结束后用户仍可撤掉；
     * 本轮邮箱中的项必须带当前 runId 才能撤销。</p>
     */
    static SteerCancelResult cancel(SteerState state, String runId, String steerId) {
        synchronized (state) {
            if (SteerState.removeById(state.orphans, steerId)) {
                return SteerCancelResult.canceled(steerId, 1, state.inbox.size() + state.orphans.size());
            }
            if (runId == null || runId.equals(state.runId) == false) {
                return SteerCancelResult.of(SteerCancelStatus.TURN_CHANGED, steerId,
                        state.inbox.size() + state.orphans.size());
            }
            if (SteerState.removeById(state.inbox, steerId)) {
                return SteerCancelResult.canceled(steerId, 1, state.inbox.size() + state.orphans.size());
            }
            return SteerCancelResult.of(SteerCancelStatus.NOT_PENDING, steerId,
                    state.inbox.size() + state.orphans.size());
        }
    }

    /** 不比对轮次的单条撤销（已弃用门面 {@code cancel(session, id)} 的兼容实现） */
    static boolean cancelAnyRun(SteerState state, String steerId) {
        synchronized (state) {
            return SteerState.removeById(state.inbox, steerId)
                    || SteerState.removeById(state.orphans, steerId);
        }
    }

    /**
     * 批量撤销（steerIds 为 null 或空表示清空全部；不比对轮次——「清空」本就跨轮生效）。
     */
    static SteerCancelResult cancelAll(SteerState state, Collection<String> steerIds) {
        synchronized (state) {
            int before = state.inbox.size() + state.orphans.size();
            if (steerIds == null || steerIds.isEmpty()) {
                state.inbox.clear();
                state.orphans.clear();
            } else {
                for (String id : steerIds) {
                    if (id == null || id.trim().isEmpty()) {
                        continue;
                    }
                    String trimmed = id.trim();
                    if (SteerState.removeById(state.inbox, trimmed) == false) {
                        SteerState.removeById(state.orphans, trimmed);
                    }
                }
            }
            int after = state.inbox.size() + state.orphans.size();
            return SteerCancelResult.canceled(null, before - after, after);
        }
    }

    /**
     * 在给定队列中按 ID 撤销（纯函数，与会话状态无关）。
     *
     * @deprecated 邮箱已不对外暴露；用 {@link SteerExtension#cancelOnRun} / {@link SteerExtension#cancelPending}
     */
    @Deprecated
    public static boolean cancel(Queue<SteerMessage> box, String steerId) {
        if (box == null || steerId == null) {
            return false;
        }
        for (SteerMessage item : box) {
            if (steerId.equals(item.getId())) {
                return box.remove(item);
            }
        }
        return false;
    }

    /**
     * 把未消费插话转入积压（唯一实现）。
     *
     * <p>只依赖 session 与核心能力接口 {@link InputBacklog}（当前由 queue 插件实现），
     * 不认识队列实现、不看流式 sink、不看宿主类型——因此「绝不静默丢弃」在任何宿主下都成立
     * （CLI / Desktop 没有订阅者也能排队）。不再回填邮箱：失败项由调用方暂存为 orphans（永不注入），
     * 避免下一任务把它当插话注入。</p>
     *
     * <p>没有积压能力（{@code backlog == null}，即未装 queue 插件）、积压停用或拒收、入队异常，
     * 均按失败计（拦截器链遍历处没有 try，异常不得冒泡）。</p>
     *
     * @param backlog 积压能力（可为 null）
     * @return 转入失败的插话（永不为 null）
     */
    static List<SteerMessage> transferToBacklog(@Nullable InputBacklog backlog, AgentSession session,
                                                List<SteerMessage> items) {
        List<SteerMessage> failed = new ArrayList<>();
        if (session == null || items == null || items.isEmpty()) {
            return failed;
        }

        for (SteerMessage message : items) {
            boolean ok;
            try {
                ok = backlog != null && backlog.offer(session, message.getText(), message.getOrigin()) >= 0;
            } catch (RuntimeException e) {
                LOG.warn("[SteerHelper] enqueue steer failed for session {}: {}",
                        session.getSessionId(), e.getMessage());
                ok = false;
            }
            if (ok == false) {
                failed.add(message);
            }
        }

        if (failed.isEmpty() == false) {
            LOG.warn("[SteerHelper] {} unconsumed steers could not be queued for session {}",
                    failed.size(), session.getSessionId());
        }

        return failed;
    }
}
