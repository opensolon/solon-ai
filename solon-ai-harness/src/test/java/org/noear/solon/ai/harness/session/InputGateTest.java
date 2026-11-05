package org.noear.solon.ai.harness.session;

import org.junit.jupiter.api.Test;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.harness.session.queue.QueueExtension;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 输入受理窗口（{@link InputGate}）契约验证。
 *
 * <p>锁定的核心不变量：</p>
 * <ol>
 *   <li>占位后 {@link SessionStatus#isOccupied()} 立即为真（否则并发请求会各开一条流）；</li>
 *   <li>占位失败时不写入任何状态（不得误伤真正持有者的窗口）；</li>
 *   <li>close 幂等，空凭据是空操作；</li>
 *   <li>并发 tryAcquire 只有一者成功（这是「刚发出就秒结束」的根因防线）；</li>
 *   <li>HITL 挂起不算占位（审批期间宿主仍可接受旁路输入）。</li>
 * </ol>
 */
class InputGateTest {

    private AgentSession session() {
        return InMemoryAgentSession.of();
    }

    @Test
    void acquiredTicketMakesSessionOccupied() {
        AgentSession session = session();
        assertFalse(SessionStatus.of(session).isOccupied());

        InputGate.Ticket ticket = InputGate.tryAcquire(session);

        assertTrue(ticket.isAcquired());
        assertTrue(InputGate.isAdmitting(session));
        assertTrue(SessionStatus.of(session).isOccupied());
        assertTrue(SessionStatus.of(session).isAdmitting());
        // 只占位、没有活跃流：busy 口径不应被污染
        assertFalse(SessionStatus.of(session).isBusy());

        ticket.close();
        assertFalse(SessionStatus.of(session).isOccupied());
    }

    @Test
    void secondAcquireFailsWhileHeld() {
        AgentSession session = session();
        InputGate.Ticket first = InputGate.tryAcquire(session);
        assertTrue(first.isAcquired());

        InputGate.Ticket second = InputGate.tryAcquire(session);
        assertFalse(second.isAcquired(), "同一会话不得被两条输入同时占位");

        // 失败者释放不应影响持有者
        second.close();
        assertTrue(InputGate.isAdmitting(session));

        first.close();
        assertFalse(InputGate.isAdmitting(session));
    }

    @Test
    void closeIsIdempotent() {
        AgentSession session = session();
        InputGate.Ticket ticket = InputGate.tryAcquire(session);
        ticket.close();
        ticket.close();
        assertFalse(InputGate.isAdmitting(session));
        // 释放后可再次占位
        assertTrue(InputGate.tryAcquire(session).isAcquired());
    }

    @Test
    void emptyTicketIsNoop() {
        assertFalse(InputGate.Ticket.NONE.isAcquired());
        InputGate.Ticket.NONE.close();
        assertFalse(InputGate.isAdmitting(null));
    }

    @Test
    void concurrentAcquireHasExactlyOneWinner() throws Exception {
        AgentSession session = session();
        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger winners = new AtomicInteger();
        AtomicReference<InputGate.Ticket> holder = new AtomicReference<>();

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    InputGate.Ticket ticket = InputGate.tryAcquire(session);
                    if (ticket.isAcquired()) {
                        winners.incrementAndGet();
                        holder.set(ticket);
                    }
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        assertTrue(done.await(10, TimeUnit.SECONDS));
        pool.shutdownNow();

        assertEquals(1, winners.get(), "并发受理必须只有一个赢家，否则会并发开流");
        if (holder.get() != null) {
            holder.get().close();
        }
    }

    @Test
    void pendingHitlDoesNotBlockAcquisition() {
        // HITL 挂起时引擎没有运行流：受理策略由宿主决定，本类不得越权拦截
        AgentSession session = session();
        session.attrs().put("_test_hitl_marker_", Boolean.TRUE);

        InputGate.Ticket ticket = InputGate.tryAcquire(session);
        assertTrue(ticket.isAcquired());
        ticket.close();
    }

    /**
     * drainLoop 的终止条件：START_FAILED 不得立即重试（否则同一失败任务无限循环）。
     */
    @Test
    void drainLoopStopsOnStartFailed() {
        AgentSession session = session();
        QueueExtension queue = new QueueExtension();
        queue.enqueue(session, "always-fail", null);

        AtomicInteger attempts = new AtomicInteger();
        queue.drainLoop(session, null, claim -> {
            attempts.incrementAndGet();
            throw new IllegalStateException("boom");
        });

        assertEquals(1, attempts.get(), "启动失败的任务已回队首，不得在同一轮内重试");
        assertEquals(1, queue.pendingSize(session), "失败任务必须回到队列，不能丢");
    }

    /**
     * drainLoop 的连续续发：队列有多条时一次调用全部发出（每条 handler 自行收口）。
     */
    @Test
    void drainLoopDrainsAllPendingWhenNeverBusy() {
        AgentSession session = session();
        QueueExtension queue = new QueueExtension();
        queue.enqueue(session, "a", null);
        queue.enqueue(session, "b", null);
        queue.enqueue(session, "c", null);

        AtomicInteger started = new AtomicInteger();
        queue.drainLoop(session, s -> true, claim -> {
            started.incrementAndGet();
            return true; // 同步完成：内核当场收口
        });

        assertEquals(3, started.get());
        assertEquals(0, queue.pendingSize(session));
    }

    /**
     * drainLoop 的接单门：谓词返回 false 时必须停下，不领取任何任务。
     */
    @Test
    void drainLoopRespectsCanDrainPredicate() {
        AgentSession session = session();
        QueueExtension queue = new QueueExtension();
        queue.enqueue(session, "a", null);

        AtomicInteger started = new AtomicInteger();
        queue.drainLoop(session, s -> false, claim -> {
            started.incrementAndGet();
            return true;
        });

        assertEquals(0, started.get());
        assertEquals(1, queue.pendingSize(session));
    }
}
