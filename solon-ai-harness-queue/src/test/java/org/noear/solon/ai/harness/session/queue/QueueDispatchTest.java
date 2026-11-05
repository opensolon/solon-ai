package org.noear.solon.ai.harness.session.queue;

import org.junit.jupiter.api.Test;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.harness.session.HarnessAttrs;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 队列派发事务（{@link QueueDispatch}）契约验证：
 * 1. 领取时登记、终态 settle 后注销（宿主零 attrs）；
 * 2. 派发线程判定与跨线程判定分离（异步失败的根因防线）；
 * 3. settle 幂等 / 与取消竞态时不复活任务；
 * 4. 「能不能接」谓词与领取在同一锁内（不消耗、不占位）。
 */
class QueueDispatchTest {

    private AgentSession session() {
        return InMemoryAgentSession.of();
    }

    @Test
    void claimRegistersDispatchVisibleOutsideCallback() {
        AgentSession session = session();
        QueueHelper.enqueue(session, "task", null);

        AtomicReference<QueueDispatch> ref = new AtomicReference<>();
        assertEquals(DrainStatus.STARTED, QueueHelper.claimNext(session, claim -> {
            ref.set(QueueDispatch.current(session));
            return false; // 异步启动：终态由 settle 收口
        }));

        QueueDispatch dispatch = ref.get();
        assertNotNull(dispatch, "领取时必须登记派发事务");
        assertEquals("task", dispatch.getItem().getText());
        assertTrue(dispatch.isOnDispatchThread());
        assertTrue(dispatch.isCurrent());
        // handler 返回后仍未注销：异步任务还要靠它收口
        assertSame(dispatch, QueueHelper.currentDispatch(session));
        assertNotNull(session.attrs().get(HarnessAttrs.TURN_DISPATCH));
    }

    /**
     * 回归：宿主可直接调 {@code claim.acknowledge()}（README 终态示例即如此），
     * 此时派发登记必须一并注销。若注销只挂在 {@code QueueDispatch.settle} 上，
     * 这条路径会留下永久登记 —— 会话此后恒被视为「有未收口派发」，
     * 队列续发被自己的残留挡死，且 {@code currentDispatch} 永不为 null。
     */
    @Test
    void directClaimAcknowledgeAlsoUnregistersDispatch() {
        AgentSession session = session();
        QueueHelper.enqueue(session, "task", null);

        AtomicReference<QueueClaim> ref = new AtomicReference<>();
        assertEquals(DrainStatus.STARTED, QueueHelper.claimNext(session, claim -> {
            ref.set(claim);
            return false;
        }));

        assertNotNull(QueueHelper.currentDispatch(session));
        assertTrue(ref.get().acknowledge(), "直接确认应成功");
        assertNull(QueueHelper.currentDispatch(session),
                "claim 收口必须连带注销派发登记，否则登记永久泄漏");
        assertNull(session.attrs().get(HarnessAttrs.TURN_DISPATCH));
        assertEquals(0, QueueHelper.pendingSize(session));
    }

    /** 回归：直接回滚同样必须注销登记。 */
    @Test
    void directClaimRollbackAlsoUnregistersDispatch() {
        AgentSession session = session();
        QueueHelper.enqueue(session, "task", null);

        AtomicReference<QueueClaim> ref = new AtomicReference<>();
        assertEquals(DrainStatus.STARTED, QueueHelper.claimNext(session, claim -> {
            ref.set(claim);
            return false;
        }));

        assertTrue(ref.get().rollback(), "回滚应把任务放回队首");
        assertNull(QueueHelper.currentDispatch(session), "回滚也必须注销派发登记");
        assertEquals(1, QueueHelper.pendingSize(session), "回滚后任务必须回到队列");
    }

    @Test
    void dispatchThreadFlagIsFalseOnOtherThreads() throws Exception {
        AgentSession session = session();
        QueueHelper.enqueue(session, "task", null);

        AtomicReference<QueueDispatch> ref = new AtomicReference<>();
        QueueHelper.claimNext(session, claim -> {
            ref.set(QueueDispatch.current(session));
            return false;
        });

        QueueDispatch dispatch = ref.get();
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Boolean onThread = pool.submit(dispatch::isOnDispatchThread).get(5, TimeUnit.SECONDS);
            assertFalse(onThread, "异步线程上不是派发线程");
            assertTrue(dispatch.isCurrent(), "但仍是当前未收口的派发事务（跨线程可判）");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void settleSuccessAcknowledgesAndUnregisters() {
        AgentSession session = session();
        QueueHelper.enqueue(session, "task", null);
        AtomicReference<QueueDispatch> ref = new AtomicReference<>();
        QueueHelper.claimNext(session, claim -> {
            ref.set(QueueDispatch.current(session));
            return false;
        });

        assertTrue(ref.get().settle(true));
        assertEquals(0, QueueHelper.size(session));
        assertNull(QueueHelper.currentDispatch(session));
        assertNull(session.attrs().get(HarnessAttrs.TURN_DISPATCH));
        assertFalse(ref.get().settle(true), "重复收口返回 false");
    }

    @Test
    void settleFailureRestoresItemToHeadAndUnregisters() {
        AgentSession session = session();
        QueueHelper.enqueue(session, "task", null);
        AtomicReference<QueueDispatch> ref = new AtomicReference<>();
        QueueHelper.claimNext(session, claim -> {
            ref.set(QueueDispatch.current(session));
            return false;
        });

        assertTrue(ref.get().settle(false));
        assertEquals("task", QueueHelper.snapshot(session).get(0).getText());
        assertEquals("PENDING", QueueHelper.snapshot(session).get(0).getStatus());
        assertNull(QueueHelper.currentDispatch(session));
    }

    /** 核心回归：Start 失败的回滚不得让用户已 Stop 的任务复活。 */
    /**
     * 回归：Stop（cancelPending）之后流终态走【成功】收口，也不得留下派发登记。
     *
     * <p>这条路径比失败路径更隐蔽：{@code acknowledge()} 会因 in-flight 已被 cancelPending
     * 清掉而返回 false，若注销只在「注销成功」分支上做，登记就永远留着——会话此后恒被视为
     * 「有未收口派发」，{@code failStreamTurn} 的跨线程判定据此误认为「这是队列派发」而静默
     * 跳过续发，队列从此瞎一轮（不报错、不打日志）。</p>
     */
    @Test
    void settleSuccessAfterCancelPendingUnregistersDispatch() {
        AgentSession session = session();
        QueueHelper.enqueue(session, "task", null);
        AtomicReference<QueueDispatch> ref = new AtomicReference<>();
        QueueHelper.claimNext(session, claim -> {
            ref.set(QueueDispatch.current(session));
            return false;
        });

        QueueHelper.cancelPending(session);

        ref.get().settle(true);
        assertNull(session.attrs().get(HarnessAttrs.TURN_DISPATCH),
                "Stop 之后成功收口也不得留下派发登记，否则队列会被自己的残留挡死续发");
    }

    @Test
    void settleFailureAfterCancelPendingDoesNotResurrectTask() {
        AgentSession session = session();
        QueueHelper.enqueue(session, "task", null);
        AtomicReference<QueueDispatch> ref = new AtomicReference<>();
        QueueHelper.claimNext(session, claim -> {
            ref.set(QueueDispatch.current(session));
            return false;
        });

        QueueHelper.cancelPending(session);

        assertFalse(ref.get().settle(false), "Stop 之后回滚必须被拒");
        assertEquals(0, QueueHelper.size(session), "被取消的任务不得复活");
        assertNull(session.attrs().get(HarnessAttrs.TURN_DISPATCH));
    }

    @Test
    void handlerFailureUnregistersDispatchAndRollsBack() {
        AgentSession session = session();
        QueueHelper.enqueue(session, "task", null);

        DrainStatus status = QueueHelper.claimNext(session, claim -> {
            throw new IllegalStateException("boom");
        });

        assertEquals(DrainStatus.START_FAILED, status);
        assertNull(QueueHelper.currentDispatch(session), "启动失败不得残留派发事务");
        assertEquals("task", QueueHelper.snapshot(session).get(0).getText());
    }

    /** 谓词为 false 时：不消耗队列项、不占领取权、不登记派发。 */
    @Test
    void canAcceptFalseConsumesNothing() throws Exception {
        AgentSession session = session();
        QueueHelper.enqueue(session, "task", null);
        AtomicInteger calls = new AtomicInteger();

        DrainStatus status = QueueHelper.claimNext(session, s -> false, claim -> {
            calls.incrementAndGet();
            return true;
        });

        assertEquals(DrainStatus.NO_TASK, status);
        assertEquals(0, calls.get());
        assertEquals(1, QueueHelper.pendingSize(session), "队列项必须原样保留");
        assertNull(QueueHelper.currentDispatch(session));
        assertNull(session.attrs().get(HarnessAttrs.TURN_DISPATCH));

        // 领取权未被占：放行后能立即领到
        AtomicReference<QueueClaim> ref = new AtomicReference<>();
        assertEquals(DrainStatus.STARTED, QueueHelper.claimNext(session, claim -> {
            ref.set(claim);
            return false;
        }));
        assertNotNull(ref.get());
    }

    /** 并发 drainLoop：同一会话只允许一个领取者进入 handler。 */
    @Test
    void concurrentDrainLoopHasSingleWinner() throws Exception {
        AgentSession session = session();
        QueueHelper.enqueue(session, "a", null);
        QueueHelper.enqueue(session, "b", null);

        QueueExtension queue = new QueueExtension();
        queue.setEnabled(true);

        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger entered = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    queue.drainLoop(session, s -> true, claim -> {
                        entered.incrementAndGet();
                        try { Thread.sleep(30); } catch (InterruptedException ignored) { }
                        return true; // 同步完成：内核当场收口（本用例验证并发领取互斥）
                    });
                } catch (InterruptedException ignored) {
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(10, TimeUnit.SECONDS));
        pool.shutdownNow();

        // 两条任务被消化且每条只被领取一次（不会因为「判忙窗口」重复领取）
        assertEquals(2, entered.get(), "每条任务必须且只被领取一次");
        assertEquals(0, QueueHelper.size(session));
    }
}
