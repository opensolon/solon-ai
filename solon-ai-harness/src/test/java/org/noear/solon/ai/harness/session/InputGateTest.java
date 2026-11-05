package org.noear.solon.ai.harness.session;

import org.junit.jupiter.api.Test;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;

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
}
