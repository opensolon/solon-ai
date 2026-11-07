package org.noear.solon.ai.harness.extension.queue;

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
        QueueStore.enqueue(session, "task", null);

        AtomicReference<QueueClaim> ref = new AtomicReference<>();
        QueueClaimStatus status = QueueStore.claimNext(session, claim -> {
            ref.set(claim);
            return false; // 异步启动：不收口，终态由宿主收
        });

        assertEquals(QueueClaimStatus.STARTED, status);
        QueueClaim claim = ref.get();
        assertNotNull(claim.getItem());
        assertEquals("task", claim.getItem().getText());
        assertTrue(claim.isValid());
        assertFalse(claim.isSettled());
    }

    @Test
    void acknowledgeIsIdempotent() {
        AgentSession session = session();
        QueueStore.enqueue(session, "task", null);
        AtomicReference<QueueClaim> ref = new AtomicReference<>();
        QueueStore.claimNext(session, claim -> {
            ref.set(claim);
            return false;
        });
        QueueClaim claim = ref.get();

        assertTrue(claim.acknowledge());
        assertFalse(claim.acknowledge(), "重复收口必须返回 false");
        assertTrue(claim.isSettled());
        assertEquals(0, QueueStore.size(session));
    }

    @Test
    void rollbackRestoresItemToHead() {
        AgentSession session = session();
        QueueStore.enqueue(session, "task", null);
        QueueStore.enqueue(session, "next", null);
        AtomicReference<QueueClaim> ref = new AtomicReference<>();
        QueueStore.claimNext(session, claim -> {
            ref.set(claim);
            return false;
        });

        assertTrue(ref.get().rollback());
        assertTrue(ref.get().isSettled());
        assertEquals("task", QueueStore.snapshot(session).get(0).getText());
        assertEquals(2, QueueStore.pendingSize(session));
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
        QueueStore.enqueue(session, "task", null);
        AtomicReference<QueueClaim> ref = new AtomicReference<>();
        QueueStore.claimNext(session, claim -> {
            ref.set(claim);
            return false;
        });
        QueueClaim claim = ref.get();

        assertTrue(QueueStore.cancelPending(session));

        assertFalse(claim.rollback(), "Stop 之后回滚必须被拒绝");
        assertFalse(claim.isValid(), "领取代际已失效");
        assertEquals(0, QueueStore.size(session), "被取消的任务不得复活");
        assertNull(session.getContext().data().get("session.queue.items"));
    }

    @Test
    void acknowledgeAfterCancelPendingDoesNotLeakInFlight() {
        AgentSession session = session();
        QueueStore.enqueue(session, "task", null);
        AtomicReference<QueueClaim> ref = new AtomicReference<>();
        QueueStore.claimNext(session, claim -> {
            ref.set(claim);
            return false;
        });

        QueueStore.cancelPending(session);

        ref.get().acknowledge();
        assertEquals(0, QueueStore.size(session));
    }

    /**
     * 同步完成 + 期间被 Stop（cancelPending）：内核收口必须拒 ack 并回滚丢弃，
     * 返回 START_FAILED 让调用循环终止——被停掉的这条不得复活，后续任务也不得继续领取。
     */
    @Test
    void syncDoneDuringStopReturnsStartFailedWithoutResurrect() {
        AgentSession session = session();
        QueueStore.enqueue(session, "task", null);
        QueueStore.enqueue(session, "next", null);

        QueueClaimStatus status = QueueStore.claimNext(session, claim -> {
            // 同步命令已跑完；恰好此时用户 Stop（模拟同步完成前被 cancelPending）
            QueueStore.cancelPending(session);
            return true;
        });

        assertEquals(QueueClaimStatus.START_FAILED, status, "Stop 后 ack 必被拒，不得报 STARTED 让循环继续");
        assertEquals(0, QueueStore.size(session), "被取消的任务不得复活（含已入队的 next，Stop 清空一切）");
        assertNull(session.getContext().data().get("session.queue.items"), "落盘投影也应为空");
        // 派发登记不得残留（否则续发被自己的残留挡死）
        assertNull(QueueClaim.current(session));
        // 领取权必须已释放：Stop 后新入队的任务能立即被领取，队列不因这次失败而卡死
        // （tryClaim 对空队列本就返回 false，故必须先补一条再验）
        QueueStore.enqueue(session, "post-stop", null);
        assertTrue(QueueStore.tryClaim(session));
        QueueStore.releaseClaim(session);
    }

    /**
     * 同步完成的正常路径：返回 true 后内核当场收口，队列不残留 in-flight。
     */
    @Test
    void syncDoneSettlesInKernelAndClearsQueue() {
        AgentSession session = session();
        QueueStore.enqueue(session, "task", null);

        java.util.concurrent.atomic.AtomicBoolean seen = new java.util.concurrent.atomic.AtomicBoolean(false);
        QueueClaimStatus status = QueueStore.claimNext(session, claim -> {
            seen.set(claim.getItem() != null);
            return true;
        });

        assertEquals(QueueClaimStatus.STARTED, status);
        assertTrue(seen.get());
        assertEquals(0, QueueStore.size(session), "同步完成后任务即从队列消失（内核已收口）");
        assertNull(QueueClaim.current(session), "派发登记不得残留");
    }

    @Test
    void concurrentClaimOnlyOneSucceeds() throws Exception {
        AgentSession session = session();
        QueueStore.enqueue(session, "task", null);

        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(8);
        AtomicReference<QueueClaim> winner = new AtomicReference<>();
        java.util.concurrent.atomic.AtomicInteger started = new java.util.concurrent.atomic.AtomicInteger();

        for (int i = 0; i < 8; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    QueueClaimStatus s = QueueStore.claimNext(session, claim -> {
                        winner.set(claim);
                        started.incrementAndGet();
                        // 慢一点，制造真实的并发窗口
                        try { Thread.sleep(50); } catch (InterruptedException ignored) { }
                        return false; // 异步启动：保留 claim 不立即收口，模拟异步运行
                    });
                    if (s == QueueClaimStatus.STARTED) {
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
        QueueStore.enqueue(session, "task", null);

        QueueClaimStatus status = QueueStore.claimNext(session, claim -> {
            throw new IllegalStateException("boom");
        });

        assertEquals(QueueClaimStatus.START_FAILED, status);
        assertEquals("task", QueueStore.snapshot(session).get(0).getText(), "启动失败的任务回到队首");
        assertEquals(QueueItem.Status.PENDING, QueueStore.snapshot(session).get(0).getStatus());
    }

    @Test
    void emptyQueueReturnsNoTask() {
        AgentSession session = session();
        assertEquals(QueueClaimStatus.NO_TASK, QueueStore.claimNext(session, claim -> false));
    }

    @Test
    void facadeClaimNextDelegates() {
        AgentSession session = session();
        QueueExtension queue = new QueueExtension();
        queue.setEnabled(true);
        queue.enqueue(session, "task", null);

        AtomicReference<QueueClaim> ref = new AtomicReference<>();
        assertEquals(QueueClaimStatus.STARTED, queue.claimNext(session, claim -> {
            ref.set(claim);
            return false;
        }));
        assertTrue(ref.get().acknowledge());
        assertEquals(0, queue.list(session).size());
    }

    @Test
    void disabledExtensionRefusesClaim() {
        AgentSession session = session();
        QueueExtension queue = new QueueExtension();
        queue.setEnabled(false);
        QueueStore.enqueue(session, "task", null);

        assertEquals(QueueClaimStatus.NO_TASK, queue.claimNext(session, claim -> false));
        assertEquals(1, QueueStore.pendingSize(session), "停用后不接单，队列原样保留");
    }
}
