package org.noear.solon.ai.harness.session.queue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.agent.session.SessionRuns;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.ai.harness.input.AdmitDecision;
import org.noear.solon.ai.harness.input.HarnessInput;
import org.noear.solon.ai.harness.input.InputOrigin;
import org.noear.solon.ai.harness.input.InputOutcome;
import org.noear.solon.ai.harness.session.InputBacklog;
import org.noear.solon.ai.harness.session.TurnDispatch;
import org.noear.solon.ai.harness.session.interrupt.InterruptPlan;
import reactor.core.Disposable;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * queue 插件经核心契约接入引擎的集成验证（插件独立后，原核心测试里的队列语义收归这里）：
 *
 * <ol>
 *   <li>{@code onInterrupt}：{@code engine.interrupt} / {@code prepareInterrupt} 先清队列再停流，无流也清；</li>
 *   <li>{@code InputBacklog}：引擎按能力接口找到本插件，{@code status} 计入积压、忙态 {@code admit} 给 QUEUE；</li>
 *   <li>{@code TurnDispatch}：派发登记在核心槽位，{@code admit} 识别为派发轮次；</li>
 *   <li>{@code /queue} 命令经引擎全链路。</li>
 * </ol>
 */
class QueueEngineIntegrationTest {
    @TempDir
    Path workspace;

    private HarnessEngine engine() {
        HarnessEngine engine = HarnessEngine.of(workspace.toString(), workspace.resolve("home").toString())
                .sessionProvider(InMemoryAgentSession::of)
                .build();
        engine.addExtensionIfAbsent(new QueueExtension());
        return engine;
    }

    private static AtomicInteger busy(AgentSession session) {
        AtomicInteger disposed = new AtomicInteger();
        SessionRuns.attach(session, new Disposable() {
            @Override
            public void dispose() {
                disposed.incrementAndGet();
            }

            @Override
            public boolean isDisposed() {
                return disposed.get() > 0;
            }
        });
        return disposed;
    }

    // ---------- onInterrupt ----------

    @Test
    void interruptCancelsPendingQueueItemsSoTheyAreNotRedispatched() {
        HarnessEngine engine = engine();
        AgentSession session = InMemoryAgentSession.of();
        QueueHelper.enqueue(session, "排队一", InputOrigin.channel("test"));
        QueueHelper.enqueue(session, "排队二", InputOrigin.channel("test"));
        AtomicInteger disposed = busy(session);

        assertTrue(engine.interrupt(session));
        assertFalse(QueueHelper.hasItems(session), "中断必须连带取消排队项，否则停流后又被续发");
        assertEquals(1, disposed.get());
    }

    @Test
    void interruptCancelsQueueEvenWhenNoStreamIsRunning() {
        HarnessEngine engine = engine();
        AgentSession session = InMemoryAgentSession.of();
        QueueHelper.enqueue(session, "排队一", InputOrigin.channel("test"));

        assertFalse(engine.interrupt(session), "无运行流时返回 false");
        assertEquals(0, QueueHelper.pendingSize(session), "排队项仍必须被取消");
    }

    @Test
    void prepareInterruptClearsQueueInFirstPhase() {
        HarnessEngine engine = engine();
        AgentSession session = InMemoryAgentSession.of();
        QueueHelper.enqueue(session, "排队一", InputOrigin.channel("test"));
        AtomicInteger disposed = busy(session);

        try (InterruptPlan plan = engine.prepareInterrupt(session)) {
            assertTrue(plan.hasActiveRun());
            assertEquals(0, QueueHelper.pendingSize(session), "第一段就必须清掉排队项");
            assertEquals(0, disposed.get(), "第一段不得停流");
        }
        assertEquals(1, disposed.get());
    }

    @Test
    void interruptCommandGoesThroughEngineAndClearsQueue() {
        HarnessEngine engine = engine();
        engine.getCommandRegistry().register(new org.noear.solon.ai.harness.session.interrupt.InterruptCommand());
        AgentSession session = InMemoryAgentSession.of();
        QueueHelper.enqueue(session, "排队一", InputOrigin.channel("test"));

        engine.handle(HarnessInput.of(session, "/interrupt"));
        assertEquals(0, QueueHelper.pendingSize(session), "/interrupt 必须经引擎回调插件清队列");
    }

    // ---------- InputBacklog ----------

    @Test
    void engineSeesQueueAsBacklogCapability() {
        HarnessEngine engine = engine();
        AgentSession session = InMemoryAgentSession.of();
        InputBacklog backlog = engine.getExtension(InputBacklog.class).orElse(null);
        assertTrue(backlog instanceof QueueExtension);

        assertEquals(1, backlog.offer(session, "a", InputOrigin.NONE));
        assertEquals(1, engine.status(session).getQueuedCount());

        busy(session);
        assertEquals(AdmitDecision.Kind.QUEUE, engine.admit(session, "b", false, null).getKind());
    }

    @Test
    void disabledQueueRejectsOfferButStillReportsBacklog() {
        HarnessEngine engine = engine();
        QueueExtension queue = engine.getExtension(QueueExtension.class).get();
        AgentSession session = InMemoryAgentSession.of();
        queue.offer(session, "a", InputOrigin.NONE);

        queue.setEnabled(false);
        assertTrue(queue.offer(session, "b", InputOrigin.NONE) < 0, "停用即拒收");
        assertEquals(1, engine.status(session).getQueuedCount(), "积压是事实口径，不受开关影响");
    }

    // ---------- TurnDispatch ----------

    @Test
    void dispatchIsRegisteredInCoreSlotAndRecognizedByAdmit() {
        HarnessEngine engine = engine();
        QueueExtension queue = engine.getExtension(QueueExtension.class).get();
        AgentSession session = InMemoryAgentSession.of();
        queue.enqueue(session, "a", InputOrigin.channel("feishu"));

        AtomicInteger checked = new AtomicInteger();
        queue.claimNext(session, claim -> {
            TurnDispatch current = TurnDispatch.current(session);
            assertTrue(current instanceof QueueDispatch);
            AdmitDecision admit = engine.admit(session, "a", false, null);
            assertEquals(AdmitDecision.Kind.ADMIT_BYPASS, admit.getKind());
            assertSame(current, admit.getDispatch());
            checked.incrementAndGet();
            return true;
        });

        assertEquals(1, checked.get());
        assertNull(TurnDispatch.current(session), "同步完成后注销");
    }

    // ---------- /queue 命令链路 ----------

    @Test
    void queueEnqueuesThroughEngineAndReplyTexts() {
        HarnessEngine engine = engine();
        AgentSession session = InMemoryAgentSession.of();

        InputOutcome outcome = engine.handle(HarnessInput.of(session, "/queue 排队的消息"));

        assertEquals(InputOutcome.Kind.REPLY, outcome.getKind());
        assertTrue(outcome.getCommandOutput().contains("已加入队列（第 1 位）"));
        assertEquals(1, QueueHelper.pendingSize(session));
    }

    @Test
    void queueWithoutArgsShowsUsage() {
        InputOutcome outcome = engine().handle(HarnessInput.of(InMemoryAgentSession.of(), "/queue"));

        assertEquals(InputOutcome.Kind.REPLY, outcome.getKind());
        assertTrue(outcome.getCommandOutput().contains("用法：/queue"));
    }
}
