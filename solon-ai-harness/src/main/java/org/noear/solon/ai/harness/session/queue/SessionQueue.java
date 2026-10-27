/*
 * Copyright 2017-2026 noear.org and authors
 * Licensed under the Apache License, Version 2.0.
 */
package org.noear.solon.ai.harness.session.queue;

import org.noear.solon.ai.agent.AgentSession;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Session 级唯一排队队列。Web、IM、CLI 只共享此队列，source 仅为元数据。
 *
 * <p>与宿主（CLI/Web/IM 入口）之间的运行时契约是 {@link AgentSession#attrs()} 中的
 * 短横线 key（一字不改，任何一侧不得私自重命名）：</p>
 * <ul>
 *   <li>{@code session.queue}：待执行队列本体（Deque&lt;SessionQueueItem&gt;）</li>
 *   <li>{@code session.queue.path}：持久化文件绝对路径</li>
 *   <li>{@code session.queue.generation}：清空代际（cancelPending 递增，用于拒绝旧代际 requeue）</li>
 *   <li>{@code session.queue.claim}：领取权（同一时刻仅一个运行者可 poll）</li>
 *   <li>{@code session.queue.inFlight}：当前运行中的任务（RUNNING 状态本体）</li>
 *   <li>{@code session.queue.storageError}：存储读写异常标记</li>
 * </ul>
 */
public final class SessionQueue {
    public static final int MAX_QUEUE_SIZE = 20;
    public static final int MAX_TEXT_LENGTH = 4096;
    private static final String ATTR_QUEUE = "session.queue";
    private static final String ATTR_PATH = "session.queue.path";
    private static final String ATTR_GENERATION = "session.queue.generation";
    private static final String ATTR_CLAIM = "session.queue.claim";
    private static final String ATTR_IN_FLIGHT = "session.queue.inFlight";
    private SessionQueue() { }

    @SuppressWarnings("unchecked")
    private static Deque<SessionQueueItem> queue(AgentSession session, boolean create) {
        Object value = session == null ? null : session.attrs().get(ATTR_QUEUE);
        if (value instanceof Deque) return (Deque<SessionQueueItem>) value;
        if (!create) return null;
        Deque<SessionQueueItem> result = new ArrayDeque<>();
        session.attrs().put(ATTR_QUEUE, result);
        return result;
    }

    public static void bindStorage(AgentSession session, Path dir) {
        if (session == null || dir == null) return;
        synchronized (session.attrs()) {
            Path path = dir.resolve(SessionQueueStore.FILE_NAME).toAbsolutePath().normalize();
            if (path.equals(session.attrs().get(ATTR_PATH)) && session.attrs().get(ATTR_QUEUE) != null) {
                // 各入口都会 bindStorage；刷新外部入队，但不覆盖当前运行中的任务。
                reloadPendingLocked(session, dir);
                return;
            }
            session.attrs().put(ATTR_PATH, path);
            try {
                session.attrs().put(ATTR_QUEUE, pendingFromStorage(dir, null));
            } catch (IOException e) {
                session.attrs().remove(ATTR_PATH);
                session.attrs().put(ATTR_QUEUE, new ArrayDeque<SessionQueueItem>());
                session.attrs().put("session.queue.storageError", Boolean.TRUE);
            }
        }
    }

    /** 在外部窗口完成原子写入后刷新待执行视图；当前 claim/in-flight 始终由运行时保留。 */
    public static void reloadStorage(AgentSession session, Path dir) {
        if (session == null || dir == null) return;
        synchronized (session.attrs()) {
            session.attrs().put(ATTR_PATH, dir.resolve(SessionQueueStore.FILE_NAME).toAbsolutePath().normalize());
            reloadPendingLocked(session, dir);
        }
    }

    private static void reloadPendingLocked(AgentSession session, Path dir) {
        try {
            Object flight = session.attrs().get(ATTR_IN_FLIGHT);
            String flightId = flight instanceof SessionQueueItem ? ((SessionQueueItem) flight).getId() : null;
            session.attrs().put(ATTR_QUEUE, pendingFromStorage(dir, flightId));
            session.attrs().remove("session.queue.storageError");
        } catch (IOException e) {
            session.attrs().put("session.queue.storageError", Boolean.TRUE);
        }
    }

    private static Deque<SessionQueueItem> pendingFromStorage(Path dir, String activeId) throws IOException {
        Deque<SessionQueueItem> result = new ArrayDeque<>();
        for (SessionQueueItem item : SessionQueueStore.load(dir)) {
            if (item == null) continue;
            // RUNNING 只代表上一次进程曾领取过；新进程没有内存中的 in-flight 时必须恢复到队首。
            if ("RUNNING".equals(item.getStatus())) {
                if (activeId != null && activeId.equals(item.getId())) continue;
                item.setStatus("PENDING");
                item.setClaimedAt(null);
                result.offerFirst(item);
            } else {
                result.offerLast(item);
            }
        }
        return result;
    }

    private static boolean persist(AgentSession session, Deque<SessionQueueItem> queue) {
        Object value = session.attrs().get(ATTR_PATH);
        if (!(value instanceof Path)) return !Boolean.TRUE.equals(session.attrs().get("session.queue.storageError"));
        try {
            SessionQueueStore.save(((Path) value).getParent(), new ArrayList<>(queueWithInFlight(session, queue)));
            return true;
        } catch (IOException e) { return false; }
    }

    private static List<SessionQueueItem> queueWithInFlight(AgentSession session, Deque<SessionQueueItem> queue) {
        List<SessionQueueItem> result = new ArrayList<>(queue);
        Object inFlight = session.attrs().get(ATTR_IN_FLIGHT);
        if (inFlight instanceof SessionQueueItem) result.add(0, (SessionQueueItem) inFlight);
        return result;
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
            if (!persist(session, q)) { q.removeLast(); return -1; }
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
            if (!persist(session, q)) {
                item.setStatus("PENDING");
                item.setClaimedAt(null);
                q.offerFirst(item);
                session.attrs().remove(ATTR_IN_FLIGHT);
                return null;
            }
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
            if (persist(session, queue(session, true))) return true;
            session.attrs().put(ATTR_IN_FLIGHT, value);
            return false;
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
            if (persist(session, q)) return true;
            q.pollFirst();
            item.setStatus("RUNNING");
            item.setClaimedAt(System.currentTimeMillis());
            session.attrs().put(ATTR_IN_FLIGHT, item);
            return false;
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
            List<SessionQueueItem> original = new ArrayList<>(q);
            q.remove(found);
            if (persist(session, q)) return true;
            q.clear();
            q.addAll(original);
            return false;
        }
    }

    public static boolean promote(AgentSession session, String itemId) {
        if (session == null || itemId == null) return false;
        synchronized (session.attrs()) {
            Deque<SessionQueueItem> q = queue(session, true);
            SessionQueueItem found = null;
            for (SessionQueueItem item : q) if (itemId.equals(item.getId())) { found = item; break; }
            if (found == null) return false;
            List<SessionQueueItem> original = new ArrayList<>(q);
            q.remove(found);
            q.offerFirst(found);
            if (persist(session, q)) return true;
            q.clear();
            q.addAll(original);
            return false;
        }
    }

    public static boolean cancelPending(AgentSession session) {
        if (session == null) return false;
        synchronized (session.attrs()) {
            Deque<SessionQueueItem> q = queue(session, true);
            List<SessionQueueItem> old = new ArrayList<>(q);
            Object oldFlight = session.attrs().get(ATTR_IN_FLIGHT);
            Object oldGeneration = session.attrs().get(ATTR_GENERATION);
            Object oldClaim = session.attrs().get(ATTR_CLAIM);
            q.clear();
            session.attrs().remove(ATTR_IN_FLIGHT);
            session.attrs().put(ATTR_GENERATION, generation(session) + 1);
            session.attrs().remove(ATTR_CLAIM);
            if (persist(session, q)) return true;
            q.addAll(old);
            if (oldFlight != null) session.attrs().put(ATTR_IN_FLIGHT, oldFlight);
            if (oldGeneration == null) session.attrs().remove(ATTR_GENERATION);
            else session.attrs().put(ATTR_GENERATION, oldGeneration);
            if (oldClaim != null) session.attrs().put(ATTR_CLAIM, oldClaim);
            return false;
        }
    }
    public static List<SessionQueueItem> snapshot(AgentSession session) {
        if (session == null) return new ArrayList<>();
        synchronized (session.attrs()) {
            return new ArrayList<>(queue(session, false) == null ? new ArrayDeque<SessionQueueItem>() : queue(session, false));
        }
    }
    public static int size(AgentSession session) { if (session == null) return 0; synchronized(session.attrs()) { Deque<SessionQueueItem> q=queue(session,false); return (q == null ? 0 : q.size()) + (session.attrs().get(ATTR_IN_FLIGHT) instanceof SessionQueueItem ? 1 : 0); } }
}
