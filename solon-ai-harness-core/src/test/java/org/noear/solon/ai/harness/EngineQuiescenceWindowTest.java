package org.noear.solon.ai.harness;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.chat.ChatConfig;
import org.noear.solon.ai.harness.input.HarnessInput;
import org.noear.solon.ai.harness.input.InputOutcome;
import reactor.core.Disposable;
import reactor.core.scheduler.Schedulers;

import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link HarnessEngine#isQuiescent()} 的两个空档：引擎在「本轮已受理、尚未结束」期间不得被判为可回收。
 *
 * <ul>
 *   <li>受理之后、订阅之前：运行计数要到订阅时才加；</li>
 *   <li>流结束之后、{@code afterRun} 回调执行完之前：外层 {@code doFinally} 先于运行句柄注销执行，
 *       回调里续发的下一轮还没开出。</li>
 * </ul>
 *
 * <p>守护的缺陷：引擎池在空档里回收引擎后，宿主的续发会被「引擎已关闭」拒绝，再被当作同步完成确认掉，
 * 会话剩下的排队任务全部丢失。</p>
 */
class EngineQuiescenceWindowTest {

    @TempDir
    Path workspace;

    /** 假模型（指向不可达地址）：开流很快以 error 终止。 */
    private HarnessEngine engine() {
        ChatConfig model = new ChatConfig();
        model.setName("fake");
        model.setModel("fake-model");
        model.setApiUrl("http://localhost:1/v1");
        model.setApiKey("test");
        return HarnessEngine.of(workspace.toString(), workspace.resolve("home").toString())
                .sessionProvider((id, c) -> InMemoryAgentSession.of(id))
                .modelAdd(model)
                .defaultModel("fake")
                .build();
    }

    private static void await(BooleanSupplier cond, String message) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline) {
            if (cond.getAsBoolean()) {
                return;
            }
            Thread.sleep(10);
        }
        fail(message);
    }

    @Test
    void notQuiescentBetweenAdmissionAndSubscribe() {
        HarnessEngine engine = engine();
        try {
            AgentSession session = InMemoryAgentSession.of();
            assertTrue(engine.isQuiescent());

            InputOutcome outcome = engine.handle(HarnessInput.of(session, "hello"));
            assertEquals(InputOutcome.Kind.TASK, outcome.getKind());
            assertEquals(0, engine.getActiveRunCount(), "前置事实：还没订阅，运行计数为 0");
            assertFalse(engine.isQuiescent(), "已受理、尚未开流：本轮还没结束，不能回收");

            outcome.discard();
            assertTrue(engine.isQuiescent(), "宿主放弃执行（discard）后静默");
        } finally {
            engine.shutdown();
        }
    }

    @Test
    void interruptClearsTheAdmissionForQuiescence() {
        HarnessEngine engine = engine();
        try {
            AgentSession session = InMemoryAgentSession.of();
            InputOutcome outcome = engine.handle(HarnessInput.of(session, "hello"));
            assertFalse(engine.isQuiescent());

            // 用户停止兜底清掉受理占位：宿主看到本轮已取消就不会再订阅，引擎不能因此一直不静默
            engine.interrupt(session, InterruptMode.INTERRUPT_ALL);
            assertTrue(engine.isQuiescent());
            outcome.discard();
        } finally {
            engine.shutdown();
        }
    }

    @Test
    void notQuiescentWhileAfterRunHooksRun() throws Exception {
        HarnessEngine engine = engine();
        try {
            AgentSession session = InMemoryAgentSession.of();
            InputOutcome outcome = engine.handle(HarnessInput.of(session, "hello"));

            AtomicBoolean quiescentInHook = new AtomicBoolean(true);
            AtomicInteger countInHook = new AtomicInteger(-1);
            CountDownLatch hookDone = new CountDownLatch(1);
            assertTrue(outcome.afterRun(() -> {
                quiescentInHook.set(engine.isQuiescent());
                countInHook.set(engine.getActiveRunCount());
                hookDone.countDown();
            }));

            outcome.getRequest().stream()
                    .subscribeOn(Schedulers.boundedElastic())
                    .subscribe(e -> { }, e -> { });

            assertTrue(hookDone.await(30, TimeUnit.SECONDS), "流终止后回调必须执行");
            assertFalse(quiescentInHook.get(), "回调执行期间（续发的下一轮还没开出）引擎不静默");
            assertEquals(1, countInHook.get(), "运行计数在回调执行完之后才减");

            await(() -> engine.getActiveRunCount() == 0, "回调执行完，运行计数回到 0");
            assertTrue(engine.isQuiescent());
        } finally {
            engine.shutdown();
        }
    }

    @Test
    void countReturnsToZeroOnEveryEndPath() throws Exception {
        HarnessEngine engine = engine();
        try {
            // 出错收尾（假模型连不上）
            InputOutcome failed = engine.handle(HarnessInput.of(InMemoryAgentSession.of(), "hello"));
            CountDownLatch failedDone = new CountDownLatch(1);
            failed.afterRun(failedDone::countDown);
            failed.getRequest().stream().subscribe(e -> { }, e -> { });
            assertTrue(failedDone.await(30, TimeUnit.SECONDS));
            await(() -> engine.getActiveRunCount() == 0, "出错收尾后计数归零");

            // 订阅后立即取消。注意：subscribeOn 之后立刻 dispose 可能取消在调度之前，那时上游从未订阅、
            // afterRun 本就不会回调（也不会计数，无泄漏）；所以先等「真的开流了」或「已经结束了」再取消
            InputOutcome cancelled = engine.handle(HarnessInput.of(InMemoryAgentSession.of(), "hello"));
            CountDownLatch cancelledDone = new CountDownLatch(1);
            cancelled.afterRun(cancelledDone::countDown);
            Disposable subscription = cancelled.getRequest().stream()
                    .subscribeOn(Schedulers.boundedElastic())
                    .subscribe(e -> { }, e -> { });
            await(() -> engine.getActiveRunCount() > 0 || cancelledDone.getCount() == 0,
                    "订阅后要么已开流（计数为 1），要么已经结束");
            subscription.dispose();
            assertTrue(cancelledDone.await(30, TimeUnit.SECONDS));
            await(() -> engine.getActiveRunCount() == 0, "取消收尾后计数归零");

            // 同步调用（含抛出）
            InputOutcome called = engine.handle(HarnessInput.of(InMemoryAgentSession.of(), "hello"));
            try {
                called.call();
            } catch (Throwable ignore) {
            }
            assertEquals(0, engine.getActiveRunCount(), "同步调用结束后计数归零");

            assertTrue(engine.isQuiescent(), "所有结束路径之后都静默");
        } finally {
            engine.shutdown();
        }
    }
}
