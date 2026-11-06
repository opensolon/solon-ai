package org.noear.solon.ai.harness.extension.queue;

import org.junit.jupiter.api.Test;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.harness.input.InputOrigin;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 会话队列内核（{@link QueueStore}）的领取 / 收口 / 基础语义。
 *
 * <p>原在 steer 模块的 session.SessionQueueSteerTest 里，QueueStore 收为包内可见后迁回 queue 模块。</p>
 */
class QueueStoreTest {

    private static AgentSession session() {
        return InMemoryAgentSession.of("q1");
    }

    // ========== 同步领取（claimNext 返回 true = 内核当场收口；布尔型 drainOne 已删） ==========

    /** 入队统一走 enqueue（它负责归一化、上限与去重），不直接构造 item。 */
    private static void offer(AgentSession session, String text) {
        QueueStore.enqueue(session, text, InputOrigin.NONE);
    }

    /** 同步语义封装：回调返回 true 即同步完成，收口由内核完成（与被删的布尔型 drainOne 语义一致）。 */
    private static boolean drainSync(AgentSession session, SyncRunner runner) throws Exception {
        return QueueStore.claimNext(session, claim -> {
            runner.run(claim);
            return true;
        }) == QueueClaimStatus.STARTED;
    }

    /** 同步回调签名（与 QueueClaimHandler 的返回值语义解耦，供 drainSync 复用）。 */
    @FunctionalInterface
    interface SyncRunner {
        void run(QueueClaim claim) throws Exception;
    }

    @Test
    void syncDrainReturnsNoTaskWhenEmpty() {
        AgentSession s = session();
        assertEquals(QueueClaimStatus.NO_TASK, QueueStore.claimNext(s, c -> false));
    }

    @Test
    void claimNextReturnsNoTaskOnNullArgs() {
        assertEquals(QueueClaimStatus.NO_TASK, QueueStore.claimNext(null, c -> false));
        assertEquals(QueueClaimStatus.NO_TASK, QueueStore.claimNext(session(), null));
    }

    @Test
    void syncDrainPollsAndRuns() throws Exception {
        AgentSession s = session();
        offer(s, "第一条");

        List<String> seen = new ArrayList<>();
        boolean drained = drainSync(s, c -> seen.add(c.getItem().getText()));

        assertTrue(drained);
        assertEquals(1, seen.size());
        assertEquals("第一条", seen.get(0));
    }

    @Test
    void syncDrainStopsAfterOneItem() throws Exception {
        AgentSession s = session();
        offer(s, "第一条");
        offer(s, "第二条");

        List<String> seen = new ArrayList<>();
        drainSync(s, c -> seen.add(c.getItem().getText()));

        assertEquals(1, seen.size(), "一次只取一项，剩余留给下一轮");
        assertEquals(1, QueueStore.size(s));
        assertEquals("第二条", QueueStore.poll(s).getText());
    }

    @Test
    void syncDrainRollsBackWhenRunnerThrows() throws Exception {
        AgentSession s = session();
        offer(s, "会失败");

        // 启动异常不再向外传播：内核捕获后回滚到队首（START_FAILED），避免宿主忘写 try 而漏掉收口
        QueueClaimStatus status = QueueStore.claimNext(s, c -> {
            throw new IllegalStateException("boom");
        });

        assertEquals(QueueClaimStatus.START_FAILED, status);
        assertEquals("会失败", QueueStore.snapshot(s).get(0).getText(), "失败任务回到队首待重试");
        // 认领必须已释放，否则队列会永久卡住（可再次认领即证明已释放）
        assertTrue(QueueStore.tryClaim(s));
        QueueStore.releaseClaim(s);
    }

    @Test
    void syncDrainSkipsWhenAlreadyClaimed() {
        AgentSession s = session();
        offer(s, "x");
        assertTrue(QueueStore.tryClaim(s));

        List<String> seen = new ArrayList<>();
        assertEquals(QueueClaimStatus.NO_TASK, QueueStore.claimNext(s, c -> {
            seen.add(c.getItem().getText());
            return true;
        }));
        assertTrue(seen.isEmpty());
    }

    // ========== SessionQueue 基础语义 ==========

    @Test
    void queueIsFifo() {
        AgentSession s = session();
        offer(s, "a");
        offer(s, "b");

        QueueItem first = QueueStore.poll(s);
        assertEquals("a", first.getText());
        // poll 会把条目置为 in-flight；未确认前不再取下一项（严格串行执行）
        assertNull(QueueStore.poll(s));

        QueueStore.acknowledge(s, first.getId());
        assertEquals("b", QueueStore.poll(s).getText());
    }

    @Test
    void pollBlocksUntilInFlightAcknowledged() {
        AgentSession s = session();
        offer(s, "a");
        offer(s, "b");

        QueueItem first = QueueStore.poll(s);
        assertEquals("a", first.getText());
        assertEquals("RUNNING", first.getStatus());

        QueueStore.acknowledge(s, first.getId());
        // 确认后队列仍有剩余项
        assertTrue(QueueStore.hasItems(s));
    }

    @Test
    void queueSizeAndClear() {
        AgentSession s = session();
        offer(s, "a");
        offer(s, "b");
        assertEquals(2, QueueStore.size(s));

        QueueStore.cancelPending(s);
        assertFalse(QueueStore.hasItems(s));
    }

    @Test
    void queueIsPerSession() {
        AgentSession a = InMemoryAgentSession.of("qa");
        AgentSession b = InMemoryAgentSession.of("qb");
        offer(a, "只给 a");

        assertFalse(QueueStore.hasItems(b));
        assertEquals(1, QueueStore.size(a));
    }

    @Test
    void enqueueNormalizesAndRejectsEmpty() {
        AgentSession s = session();
        assertEquals(-1, QueueStore.enqueue(s, "   ", InputOrigin.NONE));
        assertEquals(-1, QueueStore.enqueue(s, null, InputOrigin.NONE));

        assertEquals(1, QueueStore.enqueue(s, "  hello  ", InputOrigin.NONE));
        assertEquals("hello", QueueStore.poll(s).getText());
    }

    @Test
    void enqueueRejectsDuplicateId() {
        AgentSession s = session();
        assertEquals(1, QueueStore.enqueue(s, "a", InputOrigin.NONE, "id-1", null, null, null, null, false));
        assertEquals(-1, QueueStore.enqueue(s, "b", InputOrigin.NONE, "id-1", null, null, null, null, false));
    }
}
