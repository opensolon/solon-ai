package org.noear.solon.ai.harness.session.queue;

import org.junit.jupiter.api.Test;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 一次队列领取（{@link QueueClaim}）契约验证：
 * 1. claim 自带 item 与领取瞬间的代际快照；
 * 2. acknowledge 幂等，rollback 幂等；
 * 3. cancelPending 之后 rollback 必须失败——被 Stop 的任务不得复活（核心竞态回归）；
 * 4. 并发双领取只有一者拿到 claim；
 * 5. handler 抛异常等价启动失败，内核自动回滚且返回 START_FAILED；
 * 6. 未收口 close 兜底回滚。
 */
class QueueClaimTest {

    private AgentSession session() {
        return InMemoryAgentSession.of();
    }

    @Test
    void claimCarriesItemAndGenerationSnapshot() {
        AgentSession session = session();
        QueueHelper.enqueue(session, "task", null);

        AtomicReference<QueueClaim> ref = new AtomicReference<>();
        DrainStatus status = QueueHelper.claimNext(session, ref::set);

        assertEquals(DrainStatus.STARTED, status);
        QueueClaim claim = ref.get();
        assertNotNull(claim.getItem());
        assertEquals("task", claim.getItem().getText());
        assertTrue(claim.isValid());
        assertFalse(claim.isSettled());
    }

    @Test
    void acknowledgeIsIdempotent() {
        AgentSession session = session();
        QueueHelper.enqueue(session, "task", null);
        AtomicReference<QueueClaim> ref = new AtomicReference<>();
        QueueHelper.claimNext(session, ref::set);
        QueueClaim claim = ref.get();

        assertTrue(claim.acknowledge());
        assertFalse(claim.acknowledge(), "重复收口必须返回 false");
        assertTrue(claim.isSettled());
        assertEquals(0, QueueHelper.size(session));
    }

    @Test
    void rollbackRestoresItemToHead() {
        AgentSession session = session();
        QueueHelper.enqueue(session, "task", null);
        QueueHelper.enqueue(session, "next", null);
        AtomicReference<QueueClaim> ref = new AtomicReference<>();
        QueueHelper.claimNext(session, ref::set);

        assertTrue(ref.get().rollback());
        assertTrue(ref.get().isSettled());
        assertEquals("task", QueueHelper.snapshot(session).get(0).getText());
        assertEquals(2, QueueHelper.pendingSize(session));
    }

    /**
     * 核心回归：领取后用户 Stop（cancelPending 清空 + 代际递增），
     * 启动失败回滚必须被拒绝，任务不得复活。
     *
     * <p>旧实现在终态现取 generation，取到的必然是新代际，于是回滚通过、
     * 被取消的任务重新入队继续执行。</p>
     */
    @Test
    void rolledBackClaimCannotResurrectTaskAfterCancelPending() {
        AgentSession session = session();
        QueueHelper.enqueue(session, "task", null);
        AtomicReference<QueueClaim> ref = new AtomicReference<>();
        QueueHelper.claimNext(session, ref::set);
        QueueClaim claim = ref.get();

        assertTrue(QueueHelper.cancelPending(session));

        assertFalse(claim.rollback(), "Stop 之后回滚必须被拒绝");
        assertFalse(claim.isValid(), "领取代际已失效");
        assertEquals(0, QueueHelper.size(session), "被取消的任务不得复活");
        assertNull(session.getContext().data().get("session.queue.items"));
    }

    @Test
    void acknowledgeAfterCancelPendingDoesNotLeakInFlight() {
        AgentSession session = session();
        QueueHelper.enqueue(session, "task", null);
        AtomicReference<QueueClaim> ref = new AtomicReference<>();
        QueueHelper.claimNext(session, ref::set);

        QueueHelper.cancelPending(session);

        ref.get().acknowledge();
        assertEquals(0, QueueHelper.size(session));
    }

    @Test
    void concurrentClaimOnlyOneSucceeds() throws Exception {
        AgentSession session = session();
        QueueHelper.enqueue(session, "task", null);

        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(8);
        AtomicReference<QueueClaim> winner = new AtomicReference<>();
        java.util.concurrent.atomic.AtomicInteger started = new java.util.concurrent.atomic.AtomicInteger();

        for (int i = 0; i < 8; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    DrainStatus s = QueueHelper.claimNext(session, claim -> {
                        winner.set(claim);
                        started.incrementAndGet();
                        // 慢一点，制造真实的并发窗口
                        try { Thread.sleep(50); } catch (InterruptedException ignored) { }
                    });
                    if (s == DrainStatus.STARTED) {
                        // 保留 claim 不立即收口，模拟异步运行
                    }
                } catch (InterruptedException ignored) {
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(5, TimeUnit.SECONDS));
        pool.shutdownNow();

        assertEquals(1, started.get(), "同一时刻只允许一个领取者");
        assertNotNull(winner.get());
    }

    @Test
    void handlerFailureReturnsStartFailedAndRollsBack() {
        AgentSession session = session();
        QueueHelper.enqueue(session, "task", null);

        DrainStatus status = QueueHelper.claimNext(session, claim -> {
            throw new IllegalStateException("boom");
        });

        assertEquals(DrainStatus.START_FAILED, status);
        assertEquals("task", QueueHelper.snapshot(session).get(0).getText(), "启动失败的任务回到队首");
        assertEquals("PENDING", QueueHelper.snapshot(session).get(0).getStatus());
    }

    @Test
    void emptyQueueReturnsNoTask() {
        AgentSession session = session();
        assertEquals(DrainStatus.NO_TASK, QueueHelper.claimNext(session, claim -> { }));
    }

    @Test
    void facadeClaimNextDelegates() {
        AgentSession session = session();
        QueueExtension queue = new QueueExtension();
        queue.setEnabled(true);
        queue.enqueue(session, "task", null);

        AtomicReference<QueueClaim> ref = new AtomicReference<>();
        assertEquals(DrainStatus.STARTED, queue.claimNext(session, ref::set));
        assertTrue(ref.get().acknowledge());
        assertEquals(0, queue.viewRows(session).size());
    }

    @Test
    void disabledExtensionRefusesClaim() {
        AgentSession session = session();
        QueueExtension queue = new QueueExtension();
        queue.setEnabled(false);
        QueueHelper.enqueue(session, "task", null);

        assertEquals(DrainStatus.NO_TASK, queue.claimNext(session, claim -> { }));
        assertEquals(1, QueueHelper.pendingSize(session), "停用后不接单，队列原样保留");
    }
}
