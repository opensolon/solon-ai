package org.noear.solon.ai.harness.extension.queue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.agent.session.SessionRuns;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.ai.harness.InterruptMode;
import org.noear.solon.ai.harness.input.HarnessInput;
import org.noear.solon.ai.harness.input.InputOrigin;
import org.noear.solon.ai.harness.input.InputOutcome;
import org.noear.solon.ai.harness.spi.InputBacklog;
import org.noear.solon.ai.harness.spi.RunDispatch;
import org.noear.solon.ai.harness.InterruptPlan;
import reactor.core.Disposable;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * queue 插件经核心契约接入引擎的集成验证（插件独立后，原核心测试里的队列语义收归这里）：
 *
 * <ol>
 *   <li>{@code onSessionInterrupt}：{@code engine.interrupt(session, INTERRUPT)} / {@code prepareInterrupt} 先清队列再停流，无流也清；</li>
 *   <li>{@code InputBacklog}：引擎按能力接口找到本插件，{@code status} 带出自有键 {@code STATUS_PENDING}、有积压时 {@code isIdle} 为假、忙态 {@code handle} 由引擎自己入队并给 DEFERRED（附件、选择、宿主属性原样保存）；</li>
 *   <li>{@code RunDispatch}：派发登记在核心槽位，{@code handle} 识别为派发轮次（不占位、带回派发事务）；</li>
 *   <li>{@code /queue} 命令经引擎全链路。</li>
 * </ol>
 */
class QueueEngineIntegrationTest {
    @TempDir
    Path workspace;

    private HarnessEngine engine() {
        // 假模型（不可达地址）：受理判定类用例需要装配出请求，但不开流
        org.noear.solon.ai.chat.ChatConfig model = new org.noear.solon.ai.chat.ChatConfig();
        model.setName("fake");
        model.setModel("fake-model");
        model.setApiUrl("http://localhost:1/v1");
        model.setApiKey("test");
        HarnessEngine engine = HarnessEngine.of(workspace.toString(), workspace.resolve("home").toString())
                .sessionProvider((id, c) -> InMemoryAgentSession.of(id))
                .modelAdd(model)
                .defaultModel("fake")
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

    // ---------- onSessionInterrupt ----------

    @Test
    void interruptCancelsPendingQueueItemsSoTheyAreNotRedispatched() {
        HarnessEngine engine = engine();
        AgentSession session = InMemoryAgentSession.of();
        QueueStore.enqueue(session, "排队一", InputOrigin.channel("test"));
        QueueStore.enqueue(session, "排队二", InputOrigin.channel("test"));
        AtomicInteger disposed = busy(session);

        assertTrue(engine.interrupt(session, InterruptMode.INTERRUPT).getValue());
        assertFalse(QueueStore.hasItems(session), "中断必须连带取消排队项，否则停流后又被续发");
        assertEquals(1, disposed.get());
    }

    @Test
    void interruptCancelsQueueEvenWhenNoStreamIsRunning() {
        HarnessEngine engine = engine();
        AgentSession session = InMemoryAgentSession.of();
        QueueStore.enqueue(session, "排队一", InputOrigin.channel("test"));

        assertFalse(engine.interrupt(session, InterruptMode.INTERRUPT).getValue(), "无运行流时返回 false");
        assertEquals(0, QueueStore.pendingSize(session), "排队项仍必须被取消");
    }

    @Test
    void prepareInterruptClearsQueueInFirstPhase() {
        HarnessEngine engine = engine();
        AgentSession session = InMemoryAgentSession.of();
        QueueStore.enqueue(session, "排队一", InputOrigin.channel("test"));
        AtomicInteger disposed = busy(session);

        try (InterruptPlan plan = engine.prepareInterrupt(session)) {
            assertTrue(plan.hasActiveRun());
            assertEquals(0, QueueStore.pendingSize(session), "第一段就必须清掉排队项");
            assertEquals(0, disposed.get(), "第一段不得停流");
        }
        assertEquals(1, disposed.get());
    }

    @Test
    void interruptCommandGoesThroughEngineAndClearsQueue() {
        HarnessEngine engine = engine();
        engine.getCommandRegistry().register(new org.noear.solon.ai.harness.command.builtin.InterruptCommand());
        AgentSession session = InMemoryAgentSession.of();
        QueueStore.enqueue(session, "排队一", InputOrigin.channel("test"));

        engine.handle(HarnessInput.of(session, "/interrupt"));
        assertEquals(0, QueueStore.pendingSize(session), "/interrupt 必须经引擎回调插件清队列");
    }

    // ---------- InputBacklog ----------

    @Test
    void engineSeesQueueAsBacklogCapability() {
        HarnessEngine engine = engine();
        AgentSession session = InMemoryAgentSession.of();
        InputBacklog backlog = engine.getExtension(InputBacklog.class).orElse(null);
        assertTrue(backlog instanceof QueueExtension);

        assertEquals(1, backlog.offer(session, HarnessInput.of(session, "a")));
        assertEquals(1, (int) engine.status(session).get(QueueExtension.STATUS_PENDING));
        assertTrue(backlog.hasPending(session));
        assertFalse(engine.isIdle(session), "有积压即不空闲（goal/定时任务据此不插队）");
        InputOutcome deferred = engine.handle(HarnessInput.of(session, "b"));
        assertEquals(InputOutcome.Kind.DEFERRED, deferred.getKind(), "空闲但有积压：延后（先进先出）");
        assertEquals(2, deferred.getBacklogPosition());

        busy(session);
        assertEquals(InputOutcome.Kind.DEFERRED, engine.handle(HarnessInput.of(session, "c")).getKind());
        assertEquals(3, QueueStore.pendingSize(session), "引擎自己入队，宿主不再 enqueue");
    }

    @Test
    void deferredInputKeepsSelectionsAndHostAttrsForRedispatch() {
        HarnessEngine engine = engine();
        AgentSession session = InMemoryAgentSession.of();
        busy(session);

        InputOutcome outcome = engine.handle(HarnessInput.of(session, "排队的消息")
                .origin(InputOrigin.of("feishu", "u1", "chat1", "m1"))
                .selectedModel("gpt-x")
                .selectedAgent("coder")
                .attr("reasoningEffort", "high")
                .attr("thinkingMode", "deep"));
        assertEquals(InputOutcome.Kind.DEFERRED, outcome.getKind());

        QueueItem item = QueueStore.snapshot(session).get(0);
        assertEquals("gpt-x", item.getModel());
        assertEquals("coder", item.getSelectedAgent());
        assertEquals("high", item.getAttrs().get("reasoningEffort"), "宿主属性原样保存");
        assertEquals("deep", item.getAttrs().get("thinkingMode"));
        assertEquals("feishu", item.getOrigin().getChannel());

        // 落盘投影可还原（重启续发不丢宿主属性）
        QueueItem restored = QueueItem.fromMap(item.toPersistMap());
        assertEquals("high", restored.getAttrs().get("reasoningEffort"));

        HarnessInput back = restored.toInput(session);
        assertEquals("排队的消息", back.getText());
        assertEquals("gpt-x", back.getSelectedModel());
        assertEquals("coder", back.getSelectedAgent());
        assertEquals("deep", back.getAttr("thinkingMode"));
        assertEquals("m1", back.getOrigin().getMessageId());
    }

    @Test
    void busyInputWithAttachmentsIsRejectedNotQueued() {
        HarnessEngine engine = engine();
        AgentSession session = InMemoryAgentSession.of();
        busy(session);

        InputOutcome outcome = engine.handle(HarnessInput.of(session, "看看附件").reference("a.txt"));
        assertEquals(InputOutcome.Kind.REJECTED, outcome.getKind());
        assertEquals(InputOutcome.RejectReason.ATTACHMENTS_NOT_DEFERRABLE, outcome.getRejectReason());
        assertEquals(0, QueueStore.pendingSize(session));
    }

    @Test
    void busyProgramInputIsRejectedNotQueued() {
        HarnessEngine engine = engine();
        AgentSession session = InMemoryAgentSession.of();
        busy(session);

        InputOutcome outcome = engine.handle(HarnessInput.ofProgram(session, "goal 的一轮", "goal"));
        assertEquals(InputOutcome.Kind.REJECTED, outcome.getKind());
        assertEquals(InputOutcome.RejectReason.BUSY, outcome.getRejectReason());
        assertEquals(0, QueueStore.pendingSize(session), "程序轮次不进积压");
    }

    @Test
    void disabledQueueRejectsOfferButStillReportsBacklog() {
        HarnessEngine engine = engine();
        QueueExtension queue = engine.getExtension(QueueExtension.class).get();
        AgentSession session = InMemoryAgentSession.of();
        queue.offer(session, HarnessInput.of(session, "a"));

        queue.setEnabled(false);
        assertTrue(queue.offer(session, HarnessInput.of(session, "b")) < 0, "停用即拒收");

        busy(session);
        InputOutcome outcome = engine.handle(HarnessInput.of(session, "c"));
        assertEquals(InputOutcome.Kind.REJECTED, outcome.getKind(), "积压拒收时明确拒绝，而不是假装排上了");
        assertEquals(InputOutcome.RejectReason.BACKLOG_UNAVAILABLE, outcome.getRejectReason());
        assertEquals(1, (int) engine.status(session).get(QueueExtension.STATUS_PENDING), "积压是事实口径，不受开关影响");
        assertTrue(queue.hasPending(session), "hasPending 同为事实口径");
    }

    // ---------- RunDispatch ----------

    @Test
    void dispatchIsRegisteredInCoreSlotAndRecognizedByAdmit() {
        HarnessEngine engine = engine();
        QueueExtension queue = engine.getExtension(QueueExtension.class).get();
        AgentSession session = InMemoryAgentSession.of();
        queue.enqueue(session, "a", InputOrigin.channel("feishu"));

        AtomicInteger checked = new AtomicInteger();
        queue.claimNext(session, claim -> {
            RunDispatch current = RunDispatch.current(session);
            assertTrue(current instanceof QueueClaim);
            InputOutcome admit = engine.handle(claim.getItem().toInput(session));
            assertEquals(InputOutcome.Kind.TASK, admit.getKind());
            assertSame(current, admit.getDispatch());
            assertTrue(engine.status(session).isAdmitting(), "派发轮次的 TASK 占位到开流：返回时判忙=已异步开流");
            admit.discard();
            assertFalse(engine.status(session).isAdmitting(), "放弃后释放：同步完成判定成立");
            checked.incrementAndGet();
            return true;
        });

        assertEquals(1, checked.get());
        assertNull(RunDispatch.current(session), "同步完成后注销");
    }

    // ---------- /queue 命令链路 ----------

    @Test
    void queueEnqueuesThroughEngineAndReplyTexts() {
        HarnessEngine engine = engine();
        AgentSession session = InMemoryAgentSession.of();

        InputOutcome outcome = engine.handle(HarnessInput.of(session, "/queue 排队的消息"));

        assertEquals(InputOutcome.Kind.REPLY, outcome.getKind());
        assertTrue(outcome.getCommandOutput().contains("已加入队列（第 1 位）"));
        assertEquals(1, QueueStore.pendingSize(session));
    }

    @Test
    void queueWithoutArgsShowsUsage() {
        InputOutcome outcome = engine().handle(HarnessInput.of(InMemoryAgentSession.of(), "/queue"));

        assertEquals(InputOutcome.Kind.REPLY, outcome.getKind());
        assertTrue(outcome.getCommandOutput().contains("用法：/queue"));
    }
}
