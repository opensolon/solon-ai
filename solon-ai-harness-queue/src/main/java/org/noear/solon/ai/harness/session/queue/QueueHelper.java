/*
 * Copyright 2017-2026 noear.org and authors
 * Licensed under the Apache License, Version 2.0.
 */
package org.noear.solon.ai.harness.session.queue;

import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.harness.input.InputOrigin;
import org.noear.solon.ai.harness.session.TurnDispatch;
import org.noear.solon.flow.FlowContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
 *   <li>{@code session.queue}：待执行队列本体（Deque&lt;QueueItem&gt;，运行态）</li>
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
 *
 * <p><b>宿主接入方式</b>：宿主（CLI/Web/IM 入口）应优先使用 {@link QueueExtension}
 * 门面（经 {@code engine.getExtension(QueueExtension.class)} 获取），本类的静态方法
 * 属于内核实现，仅供门面与测试使用，新宿主代码不应直接依赖。</p>
 */
public final class QueueHelper {
    private static final Logger LOG = LoggerFactory.getLogger(QueueHelper.class);
    public static final int MAX_QUEUE_SIZE = 20;
    public static final int MAX_TEXT_LENGTH = 4096;
    private static final String ATTR_QUEUE = QueueAttrs.QUEUE;
    private static final String ATTR_GENERATION = QueueAttrs.QUEUE_GENERATION;
    private static final String ATTR_CLAIM = QueueAttrs.QUEUE_CLAIM;
    private static final String ATTR_IN_FLIGHT = QueueAttrs.QUEUE_IN_FLIGHT;
    /** 会话上下文 data() 中的持久化投影 key（List&lt;Map&gt;，运行中任务在首位）。 */
    private static final String KEY_ITEMS = QueueAttrs.QUEUE_ITEMS;
    private QueueHelper() { }

    /** 运行态队列：attrs 优先，缺失时从快照投影 hydrate（投影有内容即重建，与 create 无关）。
     *  调用方必须持有 attrs 锁。 */
    @SuppressWarnings("unchecked")
    private static Deque<QueueItem> queue(AgentSession session, boolean create) {
        Object value = session == null ? null : session.attrs().get(ATTR_QUEUE);
        if (value instanceof Deque) return (Deque<QueueItem>) value;
        Deque<QueueItem> hydrated = hydrateLocked(session);
        if (!hydrated.isEmpty()) {
            session.attrs().put(ATTR_QUEUE, hydrated);
            return hydrated;
        }
        if (!create) return null;
        Deque<QueueItem> result = new ArrayDeque<>();
        session.attrs().put(ATTR_QUEUE, result);
        return result;
    }

    /** 从会话上下文快照投影重建队列；上一次进程领取过（RUNNING）的任务恢复到队首。 */
    private static Deque<QueueItem> hydrateLocked(AgentSession session) {
        Deque<QueueItem> result = new ArrayDeque<>();
        Object projection = session == null || session.getContext() == null
                ? null : session.getContext().data().get(KEY_ITEMS);
        if (!(projection instanceof List)) return result;
        List<QueueItem> runnings = new ArrayList<>();
        List<QueueItem> pendings = new ArrayList<>();
        for (Object row : (List<?>) projection) {
            QueueItem item = row instanceof Map ? QueueItem.fromMap((Map<String, Object>) row) : null;
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
        for (QueueItem item : runnings) result.offerLast(item);
        for (QueueItem item : pendings) result.offerLast(item);
        return result;
    }

    /** 把队列写入会话上下文投影并保存快照（调用方必须持有 attrs 锁；空队列移除投影）。 */
    private static void saveLocked(AgentSession session, Deque<QueueItem> queue) {
        if (session == null || session.getContext() == null) return;
        List<Map<String, Object>> rows = new ArrayList<>();
        Object inFlight = session.attrs().get(ATTR_IN_FLIGHT);
        if (inFlight instanceof QueueItem) rows.add(((QueueItem) inFlight).toMap());
        for (QueueItem item : queue) if (item != null) rows.add(item.toMap());
        if (rows.isEmpty()) session.getContext().data().remove(KEY_ITEMS);
        else session.getContext().data().put(KEY_ITEMS, rows);
        session.updateSnapshot();
    }

    /**
     * 入队（携带来源身份）
     *
     * <p>不提供 {@code (String source)} 三参重载：它与本方法在传 null 时无法区分。
     * 三参调用统一走本方法（null 即 {@link InputOrigin#NONE}）。</p>
     */
    public static int enqueue(AgentSession session, String text, InputOrigin origin) {
        return enqueue(session, text, origin, null, null, null, null, null, false);
    }

    public static int enqueue(AgentSession session, String text, InputOrigin origin,
                              String id, String model, String reasoningEffort,
                              String thinkingMode, String selectedAgent, boolean hasFiles) {
        if (session == null || text == null) return -1;
        String normalized = text.trim();
        if (normalized.isEmpty() || normalized.length() > MAX_TEXT_LENGTH) return -1;
        synchronized (session.attrs()) {
            Deque<QueueItem> q = queue(session, true);
            if (q.size() >= MAX_QUEUE_SIZE) return -1;
            if (id != null) {
                for (QueueItem existing : q) if (id.equals(existing.getId())) return -1;
                Object running = session.attrs().get(ATTR_IN_FLIGHT);
                if (running instanceof QueueItem && id.equals(((QueueItem) running).getId())) return -1;
            }
            QueueItem item = new QueueItem(id, normalized,
                    origin, System.currentTimeMillis(), "PENDING", model, reasoningEffort,
                    thinkingMode, selectedAgent, hasFiles);
            q.offerLast(item);
            saveLocked(session, q);
            return q.size();
        }
    }

    /** 返回队列中待执行任务数量，不把当前运行中的任务计入容量。 */
    public static int pendingSize(AgentSession session) {
        if (session == null) return 0;
        synchronized (session.attrs()) {
            Deque<QueueItem> q = queue(session, false);
            return q == null ? 0 : q.size();
        }
    }

    public static QueueItem poll(AgentSession session) {
        if (session == null) return null;
        synchronized (session.attrs()) {
            Deque<QueueItem> q = queue(session, false);
            if (q == null || session.attrs().get(ATTR_IN_FLIGHT) instanceof QueueItem) return null;
            QueueItem item = q.pollFirst();
            if (item == null) return null;
            item.setStatus("RUNNING");
            item.setClaimedAt(System.currentTimeMillis());
            session.attrs().put(ATTR_IN_FLIGHT, item);
            saveLocked(session, q);
            return item;
        }
    }

    public static boolean tryClaim(AgentSession session) {
        return tryClaim(session, null);
    }

    /**
     * 领取权占位（含「现在能不能接」的原子判定）。
     *
     * <p>{@code canAccept} 在<b>同一把会话锁</b>内求值，与占位不可分割：宿主不再需要在
     * 领取前后各判一次忙态（那样两次判定之间必然存在一个窗口，只能靠宿主再补一层锁 + 
     * 代际复核去堵）。谓词为 false 时<b>不消耗队列项、不占领取权</b>。</p>
     */
    public static boolean tryClaim(AgentSession session, java.util.function.Predicate<AgentSession> canAccept) {
        if (session == null) return false;
        synchronized (session.attrs()) {
            if (Boolean.TRUE.equals(session.attrs().get(ATTR_CLAIM))) return false;
            Deque<QueueItem> q = queue(session, false);
            if ((q == null || q.isEmpty()) && !(session.attrs().get(ATTR_IN_FLIGHT) instanceof QueueItem)) return false;
            if (canAccept != null && !canAccept.test(session)) return false;
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
            if (!(value instanceof QueueItem) || !id.equals(((QueueItem)value).getId())) return false;
            session.attrs().remove(ATTR_IN_FLIGHT);
            saveLocked(session, queue(session, true));
            return true;
        }
    }
    public static boolean requeueFront(AgentSession session, QueueItem item, long generation) {
        if (session == null || item == null) return false;
        synchronized (session.attrs()) {
            if (!isGenerationActive(session, generation)) return false;
            Deque<QueueItem> q = queue(session, true);
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
            Deque<QueueItem> q = queue(session, true);
            QueueItem found = null;
            for (QueueItem item : q) {
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
            Deque<QueueItem> q = queue(session, true);
            QueueItem found = null;
            for (QueueItem item : q) if (itemId.equals(item.getId())) { found = item; break; }
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
            Deque<QueueItem> q = queue(session, true);
            Object inFlight = session.attrs().get(ATTR_IN_FLIGHT);
            boolean hadItems = !q.isEmpty() || inFlight instanceof QueueItem;
            q.clear();
            session.attrs().remove(ATTR_IN_FLIGHT);
            session.attrs().put(ATTR_GENERATION, generation(session) + 1);
            session.attrs().remove(ATTR_CLAIM);
            // Stop 意味着「正在跑的这一轮作废」：必须连派发登记一起清。
            // 否则运行流终态的 acknowledge() 会因 in-flight 已消失而失败，登记就永远留着——
            // 会话此后恒被视为「有未收口派发」，failStreamTurn 的跨线程判定据此误以为
            // 「这是队列派发」而静默跳过续发，队列从此瞎一轮（不报错、不打日志）。
            // 注销放在这里而不是 acknowledge 里：代际已递增，本会话所有在途 claim 均已失效，
            // 无需再看某个具体 claim。
            // 只清自己登记的派发：槽位已上提为核心事实，别的插件的派发不归队列作废。
            TurnDispatch current = TurnDispatch.current(session);
            if (current instanceof QueueDispatch) {
                TurnDispatch.unbind(session, current);
            }
            // 无队列变更（本就空）时不写快照：interrupt 空队列会话不应触发落盘。
            if (hadItems) saveLocked(session, q);
            return true;
        }
    }

    /** 统一视图：运行中任务（若有）在首位，其余为待执行（供队列展示接口使用）。 */
    public static List<QueueItem> view(AgentSession session) {
        List<QueueItem> result = new ArrayList<>();
        if (session == null) return result;
        synchronized (session.attrs()) {
            Object inFlight = session.attrs().get(ATTR_IN_FLIGHT);
            if (inFlight instanceof QueueItem) result.add((QueueItem) inFlight);
            Deque<QueueItem> q = queue(session, false);
            if (q != null) result.addAll(q);
        }
        return result;
    }

    /**
     * 测试专用：待执行队列快照（不含 in-flight）。
     *
     * <p>主代码读侧一律用 {@link #view()}（含 in-flight）或 {@link #pendingSize()}；
     * 本方法仅供测试直接断言队列内容，展示层禁止使用（会用掉 in-flight 语义）。</p>
     */
    public static List<QueueItem> snapshot(AgentSession session) {
        if (session == null) return new ArrayList<>();
        synchronized (session.attrs()) {
            return new ArrayList<>(queue(session, false) == null ? new ArrayDeque<QueueItem>() : queue(session, false));
        }
    }

    /**
     * 测试专用：任务总数（待执行 + in-flight）。
     *
     * <p>主代码用 {@link #hasItems(AgentSession)}（布尔判定）或 {@link #pendingSize(AgentSession)}
     * （不含 in-flight 的容量口径）；本计数仅供测试断言总量。</p>
     */
    public static int size(AgentSession session) { if (session == null) return 0; synchronized(session.attrs()) { Deque<QueueItem> q=queue(session,false); return (q == null ? 0 : q.size()) + (session.attrs().get(ATTR_IN_FLIGHT) instanceof QueueItem ? 1 : 0); } }

    /** 该会话是否有任务（含待执行与运行中）。 */
    public static boolean hasItems(AgentSession session) {
        if (session == null) return false;
        synchronized (session.attrs()) {
            if (session.attrs().get(ATTR_IN_FLIGHT) instanceof QueueItem) return true;
            Deque<QueueItem> q = queue(session, false);
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
            Deque<QueueItem> q = queue(session, true);
            List<QueueItem> runnings = new ArrayList<>();
            List<QueueItem> pendings = new ArrayList<>();
            int total = q.size();
            for (Map<String, Object> row : rows) {
                if (total >= MAX_QUEUE_SIZE) break;
                QueueItem item = QueueItem.fromMap(row);
                if (item == null) continue;
                boolean duplicated = false;
                for (QueueItem existing : q) if (item.getId().equals(existing.getId())) { duplicated = true; break; }
                for (QueueItem existing : runnings) if (item.getId().equals(existing.getId())) { duplicated = true; break; }
                for (QueueItem existing : pendings) if (item.getId().equals(existing.getId())) { duplicated = true; break; }
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
            for (QueueItem item : pendings) q.offerLast(item);
            int imported = runnings.size() + pendings.size();
            if (imported > 0) saveLocked(session, q);
            return imported;
        }
    }

    //==============


    /**
     * 领取队首一条并交给 handler（一次领取 = 一个 {@link QueueClaim}）。
     *
     * <p>收口契约由 handler 返回值表达（见 {@link QueueClaimHandler}）：返回 true = 同步完成，
     * 内核当场 acknowledge；ack 被拒（期间被 cancelPending）则回滚丢弃并返回 START_FAILED。
     * 返回 false = 异步启动，claim 由宿主经捕获的 {@code QueueDispatch} 在流终态收口。
     * handler 抛异常等价启动失败，由内核用领取时快照的代际回滚。</p>
     *
     * @return STARTED / NO_TASK / START_FAILED
     */
    public static DrainStatus claimNext(AgentSession session, QueueClaimHandler handler) {
        return claimNext(session, null, handler);
    }

    /**
     * 领取队首一条并交给 handler，附带「现在能不能接」的原子谓词。
     *
     * <p>{@code canAccept} 与领取权占位在同一把锁内完成（见
     * {@link #tryClaim(AgentSession, java.util.function.Predicate)}），因此宿主无须再在
     * 领取前后自建 synchronized 块与忙态复核。</p>
     *
     * <p>领取成功后登记一个跨线程存活的 {@link QueueDispatch}：handler 返回 false（异步启动）
     * 时<b>不</b>注销——任务还在跑，终态由宿主经 {@code dispatch.settle(..)} 收口；
     * handler 返回 true（同步完成）时内核当场 acknowledge 收口；handler 抛异常视为启动失败，
     * 注销登记并用领取瞬间的代际快照回滚到队首。</p>
     *
     * @return STARTED / NO_TASK / START_FAILED
     */
    public static DrainStatus claimNext(AgentSession session, java.util.function.Predicate<AgentSession> canAccept,
                                       QueueClaimHandler handler) {
        if (session == null || handler == null || !tryClaim(session, canAccept)) return DrainStatus.NO_TASK;
        QueueItem item = poll(session);
        if (item == null) {
            releaseClaim(session);
            return DrainStatus.NO_TASK;
        }
        QueueClaim claim = new QueueClaim(session, item, generation(session));
        QueueDispatch dispatch = registerDispatch(session, claim);
        try {
            boolean done = handler.run(claim);
            if (!done) {
                // 异步启动：claim 留给流终态（宿主捕获的 dispatch.settle）收口
                return DrainStatus.STARTED;
            }
            // 同步完成：内核当场收口。ack 被拒（期间被 Stop）必须转为回滚丢弃 + START_FAILED——
            // 静默吞掉会让调用方以为启动成功而继续领取下一条，把用户刚停掉的任务后续也跑完。
            if (!claim.acknowledge()) {
                claim.rollback();
                return DrainStatus.START_FAILED;
            }
            return DrainStatus.STARTED;
        } catch (Throwable e) {
            unregisterDispatch(session, dispatch);
            claim.rollback();
            return DrainStatus.START_FAILED;
        } finally {
            releaseClaim(session);
        }
    }

    /** 登记派发事务（覆盖式：同一会话同一时刻只允许一个未收口派发）。 */
    private static QueueDispatch registerDispatch(AgentSession session, QueueClaim claim) {
        QueueDispatch dispatch = new QueueDispatch(session, claim);
        TurnDispatch previous = TurnDispatch.bind(session, dispatch);
        if (previous != null) {
            // 「上一轮未收口就派发下一轮」按设计不可能发生（任一派发期间会话必为占用），
            // 但真的发生时旧事务会永久 OPEN 且无任何痕迹——留一条告警以便发现。
            LOG.warn("[Queue] dispatch overwritten without settle: {} -> {}", previous, dispatch);
        }
        return dispatch;
    }

    /** 注销派发事务（仅当仍是它自己，避免把新派发抹掉）。 */
    private static void unregisterDispatch(AgentSession session, QueueDispatch dispatch) {
        TurnDispatch.unbind(session, dispatch);
    }

    /**
     * 按 claim 注销它自己的派发登记（claim 收口时调用）。
     *
     * <p><b>为什么不只在 settle 里注销</b>：宿主可以绕过 {@code Dispatch.settle} 直接
     * {@code claim.acknowledge()}（README 的终态示例即如此）。若注销只挂在 settle 上，那条路径
     * 就会留下永久登记——会话此后恒被视为「有未收口派发」，队列续发被自己的残留挡死。
     * 收口是 claim 的状态迁移，注销必须跟着状态迁移走，而不是跟着某个调用入口走。</p>
     */
    static void unregisterDispatchOf(AgentSession session, QueueClaim claim) {
        if (session == null || claim == null) return;
        QueueDispatch dispatch = QueueDispatch.current(session);
        if (dispatch != null && dispatch.claim() == claim) {
            unregisterDispatch(session, dispatch);
        }
    }

    /** 当前未收口的派发事务（无则 null）。 */
    public static QueueDispatch currentDispatch(AgentSession session) {
        return QueueDispatch.current(session);
    }
}
