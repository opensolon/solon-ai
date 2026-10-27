/*
 * Copyright 2017-2026 noear.org and authors
 * Licensed under the Apache License, Version 2.0.
 */
package org.noear.solon.ai.harness.session.queue;

import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.flow.FlowContext;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;

/**
 * Session 级唯一排队队列。Web、IM、CLI 只共享此队列，source 仅为元数据。
 *
 * <p>与宿主（CLI/Web/IM 入口）之间的运行时契约是 {@link AgentSession#attrs()} 中的
 * 短横线 key（一字不改，任何一侧不得私自重命名）：</p>
 * <ul>
 *   <li>{@code session.queue}：待执行队列本体（Deque&lt;SessionQueueItem&gt;，运行态）</li>
 *   <li>{@code session.queue.generation}：清空代际（cancelPending 递增，用于拒绝旧代际 requeue）</li>
 *   <li>{@code session.queue.claim}：领取权（同一时刻仅一个运行者可 poll）</li>
 *   <li>{@code session.queue.inFlight}：当前运行中的任务（RUNNING 状态本体）</li>
 * </ul>
 *
 * <p><b>持久化</b>：队列以 Map 投影（List&lt;Map&gt;，运行中任务在首位）存放于
 * {@link AgentSession#getContext()} 的 {@code data()}（key：{@code session.queue.items}），
 * 任何队列变更后调用 {@link AgentSession#updateSnapshot()} 随会话快照一并落盘
 * ——快照即持久化，不再使用独立的 queue-tasks.json 文件。attrs 为瞬态内存，
 * 首次访问队列时从快照投影 hydrate（RUNNING 项重置为 PENDING 并恢复到队首）。</p>
 */
public final class SessionQueue {
    public static final int MAX_QUEUE_SIZE = 20;
    public static final int MAX_TEXT_LENGTH = 4096;
    private static final String ATTR_QUEUE = "session.queue";
    private static final String ATTR_GENERATION = "session.queue.generation";
    private static final String ATTR_CLAIM = "session.queue.claim";
    private static final String ATTR_IN_FLIGHT = "session.queue.inFlight";
    /** 会话上下文 data() 中的持久化投影 key（List&lt;Map&gt;，运行中任务在首位）。 */
    private static final String KEY_ITEMS = "session.queue.items";
    private SessionQueue() { }

    /** 运行态队列：attrs 优先，缺失时从快照投影 hydrate（投影有内容即重建，与 create 无关）。
     *  调用方必须持有 attrs 锁。 */
    @SuppressWarnings("unchecked")
    private static Deque<SessionQueueItem> queue(AgentSession session, boolean create) {
        Object value = session == null ? null : session.attrs().get(ATTR_QUEUE);
        if (value instanceof Deque) return (Deque<SessionQueueItem>) value;
        Deque<SessionQueueItem> hydrated = hydrateLocked(session);
        if (!hydrated.isEmpty()) {
            session.attrs().put(ATTR_QUEUE, hydrated);
            return hydrated;
        }
        if (!create) return null;
        Deque<SessionQueueItem> result = new ArrayDeque<>();
        session.attrs().put(ATTR_QUEUE, result);
        return result;
    }

    /** 从会话上下文快照投影重建队列；上一次进程领取过（RUNNING）的任务恢复到队首。 */
    private static Deque<SessionQueueItem> hydrateLocked(AgentSession session) {
        Deque<SessionQueueItem> result = new ArrayDeque<>();
        Object projection = session == null || session.getContext() == null
                ? null : session.getContext().data().get(KEY_ITEMS);
        if (!(projection instanceof List)) return result;
        List<SessionQueueItem> runnings = new ArrayList<>();
        List<SessionQueueItem> pendings = new ArrayList<>();
        for (Object row : (List<?>) projection) {
            SessionQueueItem item = row instanceof Map ? SessionQueueItem.fromMap((Map<String, Object>) row) : null;
            if (item == null) continue;
            if ("RUNNING".equals(item.getStatus())) {
                // RUNNING 只代表上一次进程曾领取过；新进程没有内存中的 in-flight 时必须恢复到队首。
                item.setStatus("PENDING");
                item.setClaimedAt(null);
                runnings.add(item);
            } else {
                pendings.add(item);
            }
        }
        for (SessionQueueItem item : runnings) result.offerLast(item);
        for (SessionQueueItem item : pendings) result.offerLast(item);
        return result;
    }

    /** 把队列写入会话上下文投影并保存快照（调用方必须持有 attrs 锁；空队列移除投影）。 */
    private static void saveLocked(AgentSession session, Deque<SessionQueueItem> queue) {
        if (session == null || session.getContext() == null) return;
        List<Map<String, Object>> rows = new ArrayList<>();
        Object inFlight = session.attrs().get(ATTR_IN_FLIGHT);
        if (inFlight instanceof SessionQueueItem) rows.add(((SessionQueueItem) inFlight).toMap());
        for (SessionQueueItem item : queue) if (item != null) rows.add(item.toMap());
        if (rows.isEmpty()) session.getContext().data().remove(KEY_ITEMS);
        else session.getContext().data().put(KEY_ITEMS, rows);
        session.updateSnapshot();
    }

    public static int enqueue(AgentSession session, String text, String source) {
        return enqueue(session, text, source, null, null, null);
    }

    public static int enqueue(AgentSession session, String text, String source,
                              String sourceUserId, String replyTarget, String messageId) {
        return enqueue(session, text, source, sourceUserId, replyTarget, messageId,
                null, null, null, null, null, false);
    }

    public static int enqueue(AgentSession session, String text, String source,
                              String sourceUserId, String replyTarget, String messageId,
                              String id, String model, String reasoningEffort,
                              String thinkingMode, String selectedAgent, boolean hasFiles) {
        if (session == null || text == null) return -1;
        String normalized = text.trim();
        if (normalized.isEmpty() || normalized.length() > MAX_TEXT_LENGTH) return -1;
        synchronized (session.attrs()) {
            Deque<SessionQueueItem> q = queue(session, true);
            if (q.size() >= MAX_QUEUE_SIZE) return -1;
            if (id != null) {
                for (SessionQueueItem existing : q) if (id.equals(existing.getId())) return -1;
                Object running = session.attrs().get(ATTR_IN_FLIGHT);
                if (running instanceof SessionQueueItem && id.equals(((SessionQueueItem) running).getId())) return -1;
            }
            SessionQueueItem item = new SessionQueueItem(id, normalized,
                    source, System.currentTimeMillis(), "PENDING", model, reasoningEffort,
                    thinkingMode, selectedAgent, hasFiles, sourceUserId, replyTarget, messageId);
            q.offerLast(item);
            saveLocked(session, q);
            return q.size();
        }
    }

    /** 返回队列中待执行任务数量，不把当前运行中的任务计入容量。 */
    public static int pendingSize(AgentSession session) {
        if (session == null) return 0;
        synchronized (session.attrs()) {
            Deque<SessionQueueItem> q = queue(session, false);
            return q == null ? 0 : q.size();
        }
    }

    public static SessionQueueItem poll(AgentSession session) {
        if (session == null) return null;
        synchronized (session.attrs()) {
            Deque<SessionQueueItem> q = queue(session, false);
            if (q == null || session.attrs().get(ATTR_IN_FLIGHT) instanceof SessionQueueItem) return null;
            SessionQueueItem item = q.pollFirst();
            if (item == null) return null;
            item.setStatus("RUNNING");
            item.setClaimedAt(System.currentTimeMillis());
            session.attrs().put(ATTR_IN_FLIGHT, item);
            saveLocked(session, q);
            return item;
        }
    }

    public static boolean tryClaim(AgentSession session) {
        if (session == null) return false;
        synchronized (session.attrs()) {
            if (Boolean.TRUE.equals(session.attrs().get(ATTR_CLAIM))) return false;
            Deque<SessionQueueItem> q = queue(session, false);
            if ((q == null || q.isEmpty()) && !(session.attrs().get(ATTR_IN_FLIGHT) instanceof SessionQueueItem)) return false;
            session.attrs().put(ATTR_CLAIM, Boolean.TRUE); return true;
        }
    }
    public static void releaseClaim(AgentSession session) { if (session != null) synchronized (session.attrs()) { session.attrs().remove(ATTR_CLAIM); } }
    public static long generation(AgentSession session) { synchronized (session.attrs()) { Object v = session.attrs().get(ATTR_GENERATION); return v instanceof Number ? ((Number)v).longValue() : 0L; } }
    public static boolean isGenerationActive(AgentSession session, long generation) { return generation(session) == generation; }
    public static boolean acknowledge(AgentSession session, String id) {
        if (session == null || id == null) return false;
        synchronized (session.attrs()) {
            Object value = session.attrs().get(ATTR_IN_FLIGHT);
            if (!(value instanceof SessionQueueItem) || !id.equals(((SessionQueueItem)value).getId())) return false;
            session.attrs().remove(ATTR_IN_FLIGHT);
            saveLocked(session, queue(session, true));
            return true;
        }
    }
    public static boolean requeueFront(AgentSession session, SessionQueueItem item, long generation) {
        if (session == null || item == null) return false;
        synchronized (session.attrs()) {
            if (!isGenerationActive(session, generation)) return false;
            Deque<SessionQueueItem> q = queue(session, true);
            // in-flight 回收不能因 pending 已满而丢失；允许短暂保留一个恢复槽位。
            if (q.size() > MAX_QUEUE_SIZE) return false;
            item.setStatus("PENDING"); item.setClaimedAt(null); q.offerFirst(item);
            session.attrs().remove(ATTR_IN_FLIGHT);
            saveLocked(session, q);
            return true;
        }
    }
    public static boolean cancelItem(AgentSession session, String itemId) {
        if (session == null || itemId == null) return false;
        synchronized (session.attrs()) {
            Deque<SessionQueueItem> q = queue(session, true);
            SessionQueueItem found = null;
            for (SessionQueueItem item : q) {
                if (itemId.equals(item.getId())) { found = item; break; }
            }
            if (found == null) return false;
            q.remove(found);
            saveLocked(session, q);
            return true;
        }
    }

    public static boolean promote(AgentSession session, String itemId) {
        if (session == null || itemId == null) return false;
        synchronized (session.attrs()) {
            Deque<SessionQueueItem> q = queue(session, true);
            SessionQueueItem found = null;
            for (SessionQueueItem item : q) if (itemId.equals(item.getId())) { found = item; break; }
            if (found == null) return false;
            q.remove(found);
            q.offerFirst(found);
            saveLocked(session, q);
            return true;
        }
    }

    public static boolean cancelPending(AgentSession session) {
        if (session == null) return false;
        synchronized (session.attrs()) {
            Deque<SessionQueueItem> q = queue(session, true);
            Object inFlight = session.attrs().get(ATTR_IN_FLIGHT);
            boolean hadItems = !q.isEmpty() || inFlight instanceof SessionQueueItem;
            q.clear();
            session.attrs().remove(ATTR_IN_FLIGHT);
            session.attrs().put(ATTR_GENERATION, generation(session) + 1);
            session.attrs().remove(ATTR_CLAIM);
            // 无队列变更（本就空）时不写快照：interrupt 空队列会话不应触发落盘。
            if (hadItems) saveLocked(session, q);
            return true;
        }
    }

    /** 统一视图：运行中任务（若有）在首位，其余为待执行（供队列展示接口使用）。 */
    public static List<SessionQueueItem> view(AgentSession session) {
        List<SessionQueueItem> result = new ArrayList<>();
        if (session == null) return result;
        synchronized (session.attrs()) {
            Object inFlight = session.attrs().get(ATTR_IN_FLIGHT);
            if (inFlight instanceof SessionQueueItem) result.add((SessionQueueItem) inFlight);
            Deque<SessionQueueItem> q = queue(session, false);
            if (q != null) result.addAll(q);
        }
        return result;
    }

    public static List<SessionQueueItem> snapshot(AgentSession session) {
        if (session == null) return new ArrayList<>();
        synchronized (session.attrs()) {
            return new ArrayList<>(queue(session, false) == null ? new ArrayDeque<SessionQueueItem>() : queue(session, false));
        }
    }
    public static int size(AgentSession session) { if (session == null) return 0; synchronized(session.attrs()) { Deque<SessionQueueItem> q=queue(session,false); return (q == null ? 0 : q.size()) + (session.attrs().get(ATTR_IN_FLIGHT) instanceof SessionQueueItem ? 1 : 0); } }

    /** 该会话是否有任务（含待执行与运行中）。 */
    public static boolean hasItems(AgentSession session) {
        if (session == null) return false;
        synchronized (session.attrs()) {
            if (session.attrs().get(ATTR_IN_FLIGHT) instanceof SessionQueueItem) return true;
            Deque<SessionQueueItem> q = queue(session, false);
            return q != null && !q.isEmpty();
        }
    }

    /** 会话上下文中是否存在队列持久化投影（List 非空即有）。 */
    public static boolean hasPersistedItems(FlowContext context) {
        if (context == null) return false;
        Object projection = context.get(KEY_ITEMS);
        return projection instanceof List && !((List<?>) projection).isEmpty();
    }

    /**
     * 旧版 queue-tasks.json / queue.json 的一次性迁移入口：把外部读出的 Map 行导入队列并保存快照。
     * RUNNING 项重置为 PENDING 并恢复到队首；按 id 去重；队列满即止。
     *
     * @return 实际导入条数
     */
    public static int importItems(AgentSession session, List<Map<String, Object>> rows) {
        if (session == null || rows == null || rows.isEmpty()) return 0;
        synchronized (session.attrs()) {
            Deque<SessionQueueItem> q = queue(session, true);
            List<SessionQueueItem> runnings = new ArrayList<>();
            List<SessionQueueItem> pendings = new ArrayList<>();
            int total = q.size();
            for (Map<String, Object> row : rows) {
                if (total >= MAX_QUEUE_SIZE) break;
                SessionQueueItem item = SessionQueueItem.fromMap(row);
                if (item == null) continue;
                boolean duplicated = false;
                for (SessionQueueItem existing : q) if (item.getId().equals(existing.getId())) { duplicated = true; break; }
                for (SessionQueueItem existing : runnings) if (item.getId().equals(existing.getId())) { duplicated = true; break; }
                for (SessionQueueItem existing : pendings) if (item.getId().equals(existing.getId())) { duplicated = true; break; }
                if (duplicated) continue;
                total++;
                if ("RUNNING".equals(item.getStatus())) {
                    item.setStatus("PENDING");
                    item.setClaimedAt(null);
                    runnings.add(item);
                } else {
                    pendings.add(item);
                }
            }
            // RUNNING 项恢复到队首（保持相对顺序），再接原队头与待执行项。
            for (int i = runnings.size() - 1; i >= 0; i--) q.offerFirst(runnings.get(i));
            for (SessionQueueItem item : pendings) q.offerLast(item);
            int imported = runnings.size() + pendings.size();
            if (imported > 0) saveLocked(session, q);
            return imported;
        }
    }
}
