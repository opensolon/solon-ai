/*
 * Copyright 2017-2026 noear.org and authors
 * Licensed under the Apache License, Version 2.0.
 */
package org.noear.solon.ai.harness.session.steer;

import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.lang.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;

/**
 * 会话级插话状态（包内可见，宿主不可触达）。
 *
 * <p>取代原先两个独立 attrs（已废弃的 {@code harness.activeRunId} + {@code harness.steerBox}）：
 * 「当前轮次」与「本轮邮箱」合并为一个对象、一把锁，从结构上保证二者不会错配，
 * 投递后的复查只需比对 {@link #epoch}。</p>
 *
 * <p>不变量：</p>
 * <ul>
 *   <li>{@link #inbox} 只属于 {@link #runId} 这一轮，只有它会被注入模型；</li>
 *   <li>{@link #orphans} 是转排队失败、等待重试的插话，<b>永不注入模型</b>
 *       （旧实现回填邮箱，会在下一任务第二轮被注入，造成跨任务串话）；</li>
 *   <li>{@link #epoch} 在每次轮次切换（开 / 收）时递增。</li>
 * </ul>
 *
 * <p>所有字段只在持有 {@code this} 锁时读写。</p>
 *
 * @author noear
 * @since 4.2.1
 */
final class SteerState {
    /** attrs key（transient，不落 snapshot.json）。宿主不得读写。 */
    static final String ATTR = SteerAttrs.STEER;

    /** 当前活跃轮次；null 表示没有活跃任务 */
    String runId;
    /** 轮次代际：每次 open/close/release 递增，供投递后复查 */
    long epoch;
    /** 本轮待注入的插话（按投递顺序） */
    final Deque<SteerMessage> inbox = new ArrayDeque<>();
    /** 转排队失败、待下次开轮/收轮重试的插话（永不注入） */
    final Deque<SteerMessage> orphans = new ArrayDeque<>();

    private SteerState() {
    }

    /** 获取（必要时创建）会话插话状态 */
    static SteerState of(AgentSession session) {
        synchronized (session.attrs()) {
            Object value = session.attrs().get(ATTR);
            if (value instanceof SteerState) {
                return (SteerState) value;
            }
            SteerState state = new SteerState();
            session.attrs().put(ATTR, state);
            return state;
        }
    }

    /** 获取（不创建）会话插话状态 */
    @Nullable
    static SteerState peek(@Nullable AgentSession session) {
        if (session == null) {
            return null;
        }
        synchronized (session.attrs()) {
            Object value = session.attrs().get(ATTR);
            return value instanceof SteerState ? (SteerState) value : null;
        }
    }

    /**
     * 开轮（幂等）。
     *
     * <p>runId 与当前相同：同一逻辑任务（{@code /continue} 续跑、或 onReasonStart 的防御性调用），
     * inbox 保留，只取出 orphans 重试。runId 不同：上一轮未收尾（interrupt / 异常时
     * {@code onAgentEnd} 不触发），其 inbox 一并交出。</p>
     *
     * @return 需要交接（转排队）的残留，永不为 null
     */
    synchronized List<SteerMessage> open(String newRunId) {
        List<SteerMessage> leftovers = takeOrphans();
        if (newRunId.equals(runId)) {
            return leftovers;
        }

        leftovers.addAll(inbox);
        inbox.clear();
        runId = newRunId;
        epoch++;
        return leftovers;
    }

    /**
     * 防御性开轮：只在轮次不一致时才等价于 {@link #open}；轮次已一致时零行为
     * （不重试 orphans——否则队列满时每个推理回合都会重复推出失败事件）。
     */
    synchronized List<SteerMessage> ensureOpen(String currentRunId) {
        if (currentRunId.equals(runId)) {
            return Collections.emptyList();
        }
        return open(currentRunId);
    }

    /**
     * 收轮。迟到的旧回调（runId 不匹配）直接忽略，不得摘走新任务的状态。
     *
     * @return 需要交接的残留（本轮 inbox + orphans），永不为 null
     */
    synchronized List<SteerMessage> close(String endingRunId) {
        if (runId == null || runId.equals(endingRunId) == false) {
            return Collections.emptyList();
        }
        return release();
    }

    /**
     * 交出全部残留并清空轮次标识（{@link #close} 的实现）。
     */
    private List<SteerMessage> release() {
        List<SteerMessage> leftovers = takeOrphans();
        leftovers.addAll(inbox);
        inbox.clear();
        if (runId != null) {
            runId = null;
            epoch++;
        }
        return leftovers;
    }

    /** 取出本轮待注入的插话（只取 inbox，不碰 orphans） */
    synchronized List<SteerMessage> drainFor(String currentRunId) {
        if (runId == null || runId.equals(currentRunId) == false || inbox.isEmpty()) {
            return Collections.emptyList();
        }
        List<SteerMessage> messages = new ArrayList<>(inbox);
        inbox.clear();
        return messages;
    }

    /** 暂存转排队失败的插话（永不注入，下次开轮/收轮重试） */
    synchronized void park(List<SteerMessage> failed) {
        orphans.addAll(failed);
    }

    /** 当前轮次标识 */
    synchronized String runId() {
        return runId;
    }

    /** 待生效快照：inbox 在前、orphans 在后（副本） */
    synchronized List<SteerMessage> snapshot() {
        List<SteerMessage> all = new ArrayList<>(inbox.size() + orphans.size());
        all.addAll(inbox);
        all.addAll(orphans);
        return all;
    }

    /** 待生效总数（inbox + orphans） */
    synchronized int size() {
        return inbox.size() + orphans.size();
    }

    /** 是否已存在同 ID 的插话（调用方须已持有 this 锁） */
    boolean containsId(String steerId) {
        return indexOf(inbox, steerId) != null || indexOf(orphans, steerId) != null;
    }

    /** 按 ID 移除（调用方须已持有 this 锁） */
    static boolean removeById(Deque<SteerMessage> queue, String steerId) {
        SteerMessage hit = indexOf(queue, steerId);
        return hit != null && queue.remove(hit);
    }

    private static SteerMessage indexOf(Deque<SteerMessage> queue, String steerId) {
        for (SteerMessage item : queue) {
            if (steerId.equals(item.getId())) {
                return item;
            }
        }
        return null;
    }

    private List<SteerMessage> takeOrphans() {
        List<SteerMessage> list = new ArrayList<>(orphans);
        orphans.clear();
        return list;
    }
}
