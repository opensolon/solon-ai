package org.noear.solon.ai.harness.session.queue;

import org.junit.jupiter.api.Test;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link QueueExtension#drainLoop} 终止条件（随 queue 插件独立，原在核心 InputGateTest）。
 */
class QueueDrainLoopTest {

    private AgentSession session() {
        return InMemoryAgentSession.of();
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
