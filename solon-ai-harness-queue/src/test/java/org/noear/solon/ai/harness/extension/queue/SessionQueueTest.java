package org.noear.solon.ai.harness.extension.queue;

import org.junit.jupiter.api.Test;
import org.noear.solon.ai.harness.input.InputOrigin;
import org.junit.jupiter.api.io.TempDir;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.session.FileAgentSession;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.flow.FlowContext;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SessionQueue 去文件化后的行为测试：快照投影即持久化，attrs 为运行态。
 */
class SessionQueueTest {
    @TempDir Path tempDir;

    @Test
    void allWindowsUseOneFifoQueue() {
        AgentSession session = InMemoryAgentSession.of();
        assertEquals(1, QueueHelper.enqueue(session, "first", InputOrigin.channel("WEB")));
        assertEquals(2, QueueHelper.enqueue(session, "second", InputOrigin.channel("IM")));
        assertEquals(2, QueueHelper.pendingSize(session));

        QueueItem first = QueueHelper.poll(session);
        assertEquals("first", first.getText());
        assertTrue(QueueHelper.acknowledge(session, first.getId()));
        assertEquals("second", QueueHelper.poll(session).getText());
    }

    @Test
    void projectionWrittenToContextData() {
        AgentSession session = InMemoryAgentSession.of();
        QueueHelper.enqueue(session, "first", null);
        QueueHelper.enqueue(session, "second", null);
        Object projection = session.getContext().data().get("session.queue.items");
        assertTrue(projection instanceof List);
        assertEquals(2, ((List<?>) projection).size());
        // 逐个领取并确认后投影最终移除（单飞：须先确认才能领取下一项）
        QueueItem first = QueueHelper.poll(session);
        QueueHelper.acknowledge(session, first.getId());
        QueueItem second = QueueHelper.poll(session);
        QueueHelper.acknowledge(session, second.getId());
        assertFalse(session.getContext().data().containsKey("session.queue.items"));
    }

    @Test
    void hydratedQueueIsVisibleToReadOnlyOpsWithoutCreate() {
        // 只读操作（pendingSize）也能从快照投影 hydrate：重启后无需先入队即可看到队列。
        AgentSession session = InMemoryAgentSession.of();
        QueueHelper.enqueue(session, "first", null);
        AgentSession restarted = InMemoryAgentSession.of(
                org.noear.solon.flow.FlowContext.fromJson(session.getContext().toJson()));
        assertEquals(1, QueueHelper.pendingSize(restarted));
        assertEquals("first", QueueHelper.poll(restarted).getText());
    }

    @Test
    void inFlightKeptFirstInProjection() {
        AgentSession session = InMemoryAgentSession.of();
        QueueHelper.enqueue(session, "first", null);
        QueueHelper.enqueue(session, "second", null);
        QueueHelper.poll(session); // first → in-flight
        List<?> rows = (List<?>) session.getContext().data().get("session.queue.items");
        assertEquals(2, rows.size());
        Map<?, ?> head = (Map<?, ?>) rows.get(0);
        assertEquals("RUNNING", head.get("status"));
        assertEquals("first", head.get("text"));
    }

    @Test
    void fileSessionRestartRecoversQueueFromSnapshot() throws Exception {
        Path dir = tempDir.resolve("session");
        AgentSession session = new FileAgentSession("s-1", dir.toString());
        assertEquals(1, QueueHelper.enqueue(session, "first", InputOrigin.channel("WEB")));
        assertEquals(2, QueueHelper.enqueue(session, "second", InputOrigin.channel("WEB")));

        // 模拟进程重启：同目录重建会话，快照投影应自动 hydrate。
        AgentSession restarted = new FileAgentSession("s-1", dir.toString());
        assertEquals(2, QueueHelper.pendingSize(restarted));
        assertEquals("first", QueueHelper.poll(restarted).getText());
    }

    @Test
    void fileSessionRestartRecoversRunningItemToFront() throws Exception {
        Path dir = tempDir.resolve("running");
        AgentSession session = new FileAgentSession("s-2", dir.toString());
        QueueHelper.enqueue(session, "running", null);
        QueueHelper.enqueue(session, "pending", null);
        QueueHelper.poll(session); // running → in-flight（投影 status=RUNNING 在首位）

        AgentSession restarted = new FileAgentSession("s-2", dir.toString());
        List<QueueItem> view = QueueHelper.view(restarted);
        assertEquals(2, view.size());
        assertEquals("running", view.get(0).getText());
        assertEquals("PENDING", view.get(0).getStatus());
        assertEquals("pending", view.get(1).getText());
    }

    @Test
    void snapshotRoundTripPreservesExecutionOptions() {
        AgentSession session = InMemoryAgentSession.of();
        assertEquals(1, QueueHelper.enqueue(session, "run", null,
                "q-1", "model-a", "high", "on", "agent-a", true));
        // 手工模拟一次快照序列化/反序列化往返（FlowContext.toJson/fromJson 口径）
        AgentSession restarted = InMemoryAgentSession.of(
                org.noear.solon.flow.FlowContext.fromJson(session.getContext().toJson()));
        assertEquals(1, QueueHelper.pendingSize(restarted));
        QueueItem item = QueueHelper.snapshot(restarted).get(0);
        assertEquals("model-a", item.getModel());
        assertEquals("high", item.getReasoningEffort());
    }

    @Test
    void asyncDrainKeepsItemUntilTerminalAcknowledgement() {
        AgentSession session = InMemoryAgentSession.of();
        assertEquals(1, QueueHelper.enqueue(session, "task", null));
        // 异步语义只走 claimNext：handler 返回 false 后任务仍是 RUNNING，直到终态 acknowledge
        QueueClaim[] held = new QueueClaim[1];
        assertEquals(QueueClaimStatus.STARTED, QueueHelper.claimNext(session, claim -> {
            held[0] = claim;
            return false;
        }));
        assertEquals("RUNNING", QueueHelper.view(session).get(0).getStatus());
        assertNull(QueueHelper.poll(session));
        assertNotNull(QueueDispatch.current(session), "异步任务未终态前派发事务必须仍在登记");
        assertTrue(held[0].acknowledge(), "终态确认应成功");
        assertNull(QueueDispatch.current(session), "收口后派发事务必须注销");
        assertEquals(0, QueueHelper.pendingSize(session));
    }

    @Test
    void anotherTaskCannotReplaceInFlight() {
        AgentSession session = InMemoryAgentSession.of();
        assertEquals(1, QueueHelper.enqueue(session, "first", null));
        assertEquals(2, QueueHelper.enqueue(session, "second", null));
        QueueItem first = QueueHelper.poll(session);
        assertNull(QueueHelper.poll(session));
        assertEquals(2, QueueHelper.size(session));
        assertTrue(QueueHelper.acknowledge(session, first.getId()));
        assertEquals("second", QueueHelper.poll(session).getText());
    }

    @Test
    void duplicateIdIsRejected() {
        AgentSession session = InMemoryAgentSession.of();
        assertEquals(1, QueueHelper.enqueue(session, "first", null,
                "same", null, null, null, null, false));
        assertEquals(-1, QueueHelper.enqueue(session, "second", null,
                "same", null, null, null, null, false));
    }

    @Test
    void clearedGenerationCannotRequeueInFlightItem() {
        AgentSession session = InMemoryAgentSession.of();
        assertEquals(1, QueueHelper.enqueue(session, "task", null));
        long generation = QueueHelper.generation(session);
        QueueItem running = QueueHelper.poll(session);
        assertTrue(QueueHelper.cancelPending(session));

        assertFalse(QueueHelper.requeueFront(session, running, generation));
        assertEquals("RUNNING", running.getStatus());
        assertEquals(0, QueueHelper.size(session));
        assertFalse(session.getContext().data().containsKey("session.queue.items"));
    }

    @Test
    void promoteReordersQueueHead() {
        AgentSession session = InMemoryAgentSession.of();
        QueueHelper.enqueue(session, "first", null);
        QueueHelper.enqueue(session, "middle", null);
        QueueHelper.enqueue(session, "last", null);
        String middleId = QueueHelper.snapshot(session).get(1).getId();

        assertTrue(QueueHelper.promote(session, middleId));
        assertEquals("middle", QueueHelper.snapshot(session).get(0).getText());
        assertEquals("first", QueueHelper.snapshot(session).get(1).getText());
        assertEquals("last", QueueHelper.snapshot(session).get(2).getText());
    }

    @Test
    void cancelItemRemovesFromQueueAndProjection() {
        AgentSession session = InMemoryAgentSession.of();
        QueueHelper.enqueue(session, "first", null);
        QueueHelper.enqueue(session, "middle", null);
        QueueHelper.enqueue(session, "last", null);
        String middleId = QueueHelper.snapshot(session).get(1).getId();

        assertTrue(QueueHelper.cancelItem(session, middleId));
        assertEquals(Arrays.asList("first", "last"),
                Arrays.asList(QueueHelper.snapshot(session).get(0).getText(),
                        QueueHelper.snapshot(session).get(1).getText()));
        assertEquals(2, ((List<?>) session.getContext().data().get("session.queue.items")).size());
    }

    @Test
    void importItemsRecoversRunningFirstAndDeduplicates() {
        AgentSession session = InMemoryAgentSession.of();
        List<Map<String, Object>> rows = new ArrayList<>();
        rows.add(row("running", "recover me", "RUNNING"));
        rows.add(row("pending", "next", "PENDING"));
        rows.add(row("running", "duplicate with same id", "RUNNING"));
        assertEquals(2, QueueHelper.importItems(session, rows));
        assertEquals(2, QueueHelper.pendingSize(session));
        assertEquals("recover me", QueueHelper.poll(session).getText());
    }

    @Test
    void importItemsStopsAtCapacity() {
        AgentSession session = InMemoryAgentSession.of();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i < 30; i++) rows.add(row("q-" + i, "task-" + i, "PENDING"));
        int imported = QueueHelper.importItems(session, rows);
        assertEquals(QueueHelper.MAX_QUEUE_SIZE, imported);
        assertEquals(QueueHelper.MAX_QUEUE_SIZE, QueueHelper.pendingSize(session));
    }

    @Test
    void concurrentEnqueueIsBounded() throws Exception {
        AgentSession session = InMemoryAgentSession.of();
        ExecutorService pool = Executors.newFixedThreadPool(6);
        CountDownLatch start = new CountDownLatch(1);
        try {
            for (int i = 0; i < 60; i++) {
                final int n = i;
                pool.submit(() -> {
                    start.await();
                    QueueHelper.enqueue(session, "task-" + n, null);
                    return null;
                });
            }
            start.countDown();
        } finally {
            pool.shutdown();
            while (!pool.isTerminated()) Thread.sleep(10L);
        }
        assertTrue(QueueHelper.pendingSize(session) <= QueueHelper.MAX_QUEUE_SIZE);
    }

    private static Map<String, Object> row(String id, String text, String status) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", id);
        map.put("text", text);
        map.put("status", status);
        map.put("createdAt", System.currentTimeMillis());
        return map;
    }
    @Test
    void hasItemsReflectsQueueAndInFlight() {
        AgentSession session = InMemoryAgentSession.of();
        assertFalse(QueueHelper.hasItems(session));
        QueueHelper.enqueue(session, "first", null);
        assertTrue(QueueHelper.hasItems(session));
        // 领取后进入 in-flight：仍算有任务
        QueueItem first = QueueHelper.poll(session);
        assertTrue(QueueHelper.hasItems(session));
        QueueHelper.acknowledge(session, first.getId());
        assertFalse(QueueHelper.hasItems(session));
        // 空队列探测不应触发投影写入（空队列在投影中被移除）
        assertFalse(session.getContext().data().containsKey("session.queue.items"));
    }

    @Test
    void hasPersistedItemsDetectsProjectionFromDeserializedContext() throws Exception {
        Path dir = tempDir.resolve("probe");
        AgentSession session = new FileAgentSession("s-3", dir.toString());
        assertFalse(QueueHelper.hasPersistedItems(session.getContext()));
        assertFalse(QueueHelper.hasItems(session));

        QueueHelper.enqueue(session, "first", null);
        // 模拟宿主只读探测：仅反序列化快照，不实例化会话
        FlowContext snapshotOnly = FlowContext.fromJson(session.getContext().toJson());
        assertTrue(QueueHelper.hasPersistedItems(snapshotOnly));
        assertFalse(QueueHelper.hasItems(null));
        assertFalse(QueueHelper.hasPersistedItems(null));

        // 全部完成并确认后，投影移除 → 探测为 false
        QueueItem first = QueueHelper.poll(session);
        QueueHelper.acknowledge(session, first.getId());
        assertFalse(QueueHelper.hasPersistedItems(FlowContext.fromJson(session.getContext().toJson())));
    }
}
