package org.noear.solon.ai.harness.extension.goal;

import org.junit.jupiter.api.Test;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.harness.HarnessEngine;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link GoalRuntime#awaitRoundStopped} 的并发语义。
 *
 * <p>这些用例守护的是一次锁改造：停等从 {@code synchronized (session.attrs())} + {@code wait()}
 * 换成会话级 {@code ReentrantLock} + {@code Condition}。改造后必须同时满足三件事：</p>
 * <ul>
 *   <li>轮次结束时被唤醒（不丢信号，不是靠等满超时）；</li>
 *   <li>多个等待者都被唤醒（signalAll 语义）；</li>
 *   <li>等待期间不再占着 {@code session.attrs()} 的监视器（否则虚拟线程仍会被 pin，改造无意义）。</li>
 * </ul>
 */
class GoalRuntimeAwaitTest {

    private static GoalRuntime engine(CountDownLatch inRound, CountDownLatch release) {
        GoalRuntime e = new GoalRuntime((HarnessEngine) null,
                BudgetThresholds.defaults(), 3, 3, false);
        e.addExecutor("test", null, (sessionId, prompt) -> {
            inRound.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
            return TestOutcomes.ofText("ok");
        });
        return e;
    }

    @Test
    void awaitWakesAllWaitersWhenRoundFinished() throws Exception {
        AgentSession session = InMemoryAgentSession.of("sess-await-wake");
        CountDownLatch inRound = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        GoalRuntime runtime = engine(inRound, release);
        runtime.attach(session, "目标", 0, 0, 0);

        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            Future<Boolean> round = pool.submit(() -> runtime.runRound(session));
            assertTrue(inRound.await(2, TimeUnit.SECONDS), "轮次应已开始");
            assertTrue(runtime.isRunning(session), "轮次进行中 isRunning 应为 true");

            List<Future<Boolean>> waiters = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                waiters.add(pool.submit(() -> runtime.awaitRoundStopped(session, 5000L)));
            }
            // 给等待者一点时间真正进入 await
            Thread.sleep(100);

            long begin = System.currentTimeMillis();
            release.countDown();

            assertTrue(round.get(3, TimeUnit.SECONDS));
            for (Future<Boolean> waiter : waiters) {
                assertTrue(waiter.get(3, TimeUnit.SECONDS), "轮次结束后等待者应返回 true");
            }
            long cost = System.currentTimeMillis() - begin;

            assertFalse(runtime.isRunning(session), "轮次结束后运行态应已清除");
            assertTrue(cost < 3000, "应由信号唤醒，而不是等满 5s 超时，实际 " + cost + "ms");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void awaitReturnsFalseWhenStillRunningAfterTimeout() throws Exception {
        AgentSession session = InMemoryAgentSession.of("sess-await-timeout");
        CountDownLatch inRound = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        GoalRuntime runtime = engine(inRound, release);
        runtime.attach(session, "目标", 0, 0, 0);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> round = pool.submit(() -> runtime.runRound(session));
            assertTrue(inRound.await(2, TimeUnit.SECONDS));

            long begin = System.currentTimeMillis();
            assertFalse(runtime.awaitRoundStopped(session, 200L), "超时未停稳应返回 false");
            long cost = System.currentTimeMillis() - begin;
            assertTrue(cost >= 150, "应至少等满超时预算，实际 " + cost + "ms");
            assertTrue(cost < 2000, "不应远超超时预算，实际 " + cost + "ms");

            release.countDown();
            assertTrue(round.get(3, TimeUnit.SECONDS));
            assertTrue(runtime.awaitRoundStopped(session, 1000L), "停稳后应立即返回 true");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void awaitingDoesNotHoldSessionAttrsMonitor() throws Exception {
        AgentSession session = InMemoryAgentSession.of("sess-await-monitor");
        CountDownLatch inRound = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        GoalRuntime runtime = engine(inRound, release);
        runtime.attach(session, "目标", 0, 0, 0);

        ExecutorService pool = Executors.newFixedThreadPool(3);
        try {
            Future<Boolean> round = pool.submit(() -> runtime.runRound(session));
            assertTrue(inRound.await(2, TimeUnit.SECONDS));

            Future<Boolean> waiter = pool.submit(() -> runtime.awaitRoundStopped(session, 5000L));
            Thread.sleep(100);

            // 若等待者仍占着 attrs 监视器，这里会被阻塞到等待结束
            long begin = System.currentTimeMillis();
            AtomicBoolean entered = new AtomicBoolean();
            Future<Long> probe = pool.submit(() -> {
                synchronized (session.attrs()) {
                    entered.set(true);
                }
                return System.currentTimeMillis() - begin;
            });
            long waitCost = probe.get(1, TimeUnit.SECONDS);

            assertTrue(entered.get(), "等待期间应能进入 attrs 监视器");
            assertTrue(waitCost < 500, "等待者不应占着 attrs 监视器，实际阻塞 " + waitCost + "ms");

            release.countDown();
            assertTrue(round.get(3, TimeUnit.SECONDS));
            assertTrue(waiter.get(3, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
    }
}
