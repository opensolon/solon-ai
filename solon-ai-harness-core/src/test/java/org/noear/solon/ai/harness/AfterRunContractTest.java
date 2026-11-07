package org.noear.solon.ai.harness;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.chat.ChatConfig;
import org.noear.solon.ai.harness.input.HarnessInput;
import org.noear.solon.ai.harness.input.InputOutcome;
import reactor.core.scheduler.Schedulers;

import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link InputOutcome#afterRun(Runnable)} 契约：回调在本轮运行句柄注销之后执行。
 *
 * <p>守护的缺陷：宿主在自己流的 {@code doFinally} 里续发积压，而 Reactor 正常终止时下游 doFinally
 * 先于上游执行——那时运行句柄仍在登记表里，会话判忙，续发被挡掉，排队消息要等下一次外部触发。</p>
 */
class AfterRunContractTest {

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

    @Test
    void streamHookRunsAfterRunIsDeregistered() throws Exception {
        HarnessEngine engine = engine();
        AgentSession session = InMemoryAgentSession.of();

        InputOutcome outcome = engine.handle(HarnessInput.of(session, "hello"));
        assertEquals(InputOutcome.Kind.TASK, outcome.getKind());

        AtomicBoolean busyInHostFinally = new AtomicBoolean();
        AtomicBoolean busyInHook = new AtomicBoolean(true);
        AtomicBoolean occupiedInHook = new AtomicBoolean(true);
        AtomicReference<InputOutcome.Kind> nextKind = new AtomicReference<>();
        AtomicBoolean hostFinallyFirst = new AtomicBoolean();
        CountDownLatch hookDone = new CountDownLatch(1);

        assertTrue(outcome.afterRun(() -> {
            busyInHook.set(engine.status(session).isBusy());
            occupiedInHook.set(engine.status(session).isOccupied());
            hostFinallyFirst.set(busyInHostFinally.get());
            // 典型用法：在回调里续发下一条——此时必须能被受理
            InputOutcome next = engine.handle(HarnessInput.of(session, "next"));
            nextKind.set(next.getKind());
            next.discard();
            hookDone.countDown();
        }));

        outcome.getRequest().stream()
                .doFinally(s -> busyInHostFinally.set(engine.status(session).isBusy()))
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe(e -> { }, e -> { });

        assertTrue(hookDone.await(30, TimeUnit.SECONDS), "流终止后回调必须执行");
        assertTrue(busyInHostFinally.get(), "前置事实：宿主 doFinally 里会话仍算忙（这正是不能在那里续发的原因）");
        assertTrue(hostFinallyFirst.get(), "回调晚于宿主 doFinally");
        assertFalse(busyInHook.get(), "回调时运行句柄已注销");
        assertFalse(occupiedInHook.get(), "回调时会话不再被占用");
        assertEquals(InputOutcome.Kind.TASK, nextKind.get(), "回调里的续发能被受理，而不是 DEFERRED / REJECTED");
    }

    @Test
    void callHookRunsAfterRunIsDeregistered() throws Exception {
        HarnessEngine engine = engine();
        AgentSession session = InMemoryAgentSession.of();

        InputOutcome outcome = engine.handle(HarnessInput.of(session, "hello"));
        AtomicBoolean busyInHook = new AtomicBoolean(true);
        AtomicInteger runs = new AtomicInteger();
        assertTrue(outcome.afterRun(() -> {
            busyInHook.set(engine.status(session).isOccupied());
            runs.incrementAndGet();
        }));

        try {
            outcome.call(); // 假模型：返回错误文本或抛出，两者都应回调
        } catch (Throwable ignore) {
        }
        assertEquals(1, runs.get(), "call 结束（含抛出）回调一次");
        assertFalse(busyInHook.get());
    }

    @Test
    void failingHookDoesNotBlockOthersOrLeaveSessionBusy() throws Exception {
        HarnessEngine engine = engine();
        AgentSession session = InMemoryAgentSession.of();

        InputOutcome outcome = engine.handle(HarnessInput.of(session, "hello"));
        CountDownLatch second = new CountDownLatch(1);
        outcome.afterRun(() -> {
            throw new IllegalStateException("boom");
        });
        outcome.afterRun(second::countDown);

        CountDownLatch terminated = new CountDownLatch(1);
        outcome.getRequest().stream()
                .doFinally(s -> terminated.countDown())
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe(e -> { }, e -> { });

        assertTrue(terminated.await(30, TimeUnit.SECONDS));
        assertTrue(second.await(10, TimeUnit.SECONDS), "前一个回调抛异常不影响后一个");
        assertFalse(engine.status(session).isOccupied(), "句柄照常注销");
    }

    @Test
    void nonTaskOutcomeRejectsRegistration() {
        HarnessEngine engine = engine();
        AgentSession session = InMemoryAgentSession.of();

        InputOutcome ignored = engine.handle(HarnessInput.of(session, ""));
        assertEquals(InputOutcome.Kind.IGNORED, ignored.getKind());
        assertFalse(ignored.afterRun(() -> { }));
        assertFalse(InputOutcome.deferred(1).afterRun(() -> { }));
    }

    @Test
    void discardedTaskNeverRunsHook() {
        HarnessEngine engine = engine();
        AgentSession session = InMemoryAgentSession.of();

        InputOutcome outcome = engine.handle(HarnessInput.of(session, "hello"));
        AtomicInteger runs = new AtomicInteger();
        assertTrue(outcome.afterRun(runs::incrementAndGet));
        outcome.discard();
        assertEquals(0, runs.get(), "未开流就放弃：回调不执行（由宿主自己的失败收尾负责）");
        assertFalse(engine.status(session).isOccupied());
    }

    @Test
    void sessionlessTaskStillSupportsHook() {
        HarnessEngine engine = engine();
        InputOutcome outcome = engine.handle(HarnessInput.of(null, "a"));
        assertEquals(InputOutcome.Kind.TASK, outcome.getKind());
        assertTrue(outcome.afterRun(() -> { }), "无会话时同样可登记（行为与有会话一致）");
    }
}
