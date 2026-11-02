package org.noear.solon.ai.harness.session.queue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.harness.HarnessEngine;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * QueueExtension 一键装配验证：
 * 1. addExtension 后 /queue 命令自动注册；
 * 2. 门面操作与静态实现行为一致（enqueue/view/pendingSize/drainOne/ack 语义透传）；
 * 3. 命令注册幂等；扩展停用后命令不可见（查找时过滤）且不再接单。
 */
class QueueExtensionTest {
    @TempDir
    Path workspace;

    private HarnessEngine newEngine() {
        return HarnessEngine.of(workspace.toString(), workspace.resolve("home").toString())
                .sessionProvider(InMemoryAgentSession::of)
                .build();
    }

    @Test
    void extensionRegistersQueueCommandOnAdd() {
        HarnessEngine engine = newEngine();
        QueueExtension queue = new QueueExtension();
        engine.addExtension(queue);

        assertNotNull(engine.getCommandRegistry().find("queue"), "/queue 命令应自动注册");
    }

    @Test
    void facadeOperationsDelegateToStaticImplementation() {
        QueueExtension queue = new QueueExtension();
        AgentSession session = InMemoryAgentSession.of();

        assertEquals(1, queue.enqueue(session, "第一条", null));
        assertEquals(2, queue.enqueue(session, "第二条", null));
        assertEquals(2, queue.pendingSize(session));
        assertEquals(2, queue.size(session));
        assertTrue(queue.hasItems(session));

        List<SessionQueueItem> view = queue.view(session);
        assertEquals(2, view.size());
        assertEquals("第一条", view.get(0).getText());

        // 同步 drain：回调返回即确认
        AtomicReference<String> executed = new AtomicReference<>();
        assertTrue(queue.drainOne(session, item -> executed.set(item.getText())));
        assertEquals("第一条", executed.get());
        assertEquals(1, queue.pendingSize(session), "drainOne 后 in-flight 已确认，不占待执行数");
    }

    @Test
    void twoArgEnqueueDefaultsOriginWithoutExtraMetadata() {
        QueueExtension queue = new QueueExtension();
        AgentSession session = InMemoryAgentSession.of();

        // 程序宿主不必为一个必填参数编造渠道标识
        assertEquals(1, queue.enqueue(session, "最简入队"));
        assertEquals("最简入队", queue.view(session).get(0).getText());
        assertNotNull(queue.view(session).get(0).getOrigin(), "缺省来源不得为 null（与静态实现契约一致）");
        assertEquals("queue", queue.view(session).get(0).getOrigin().getChannel());
    }

    @Test
    void twoArgEnqueueRespectsDisabledState() {
        QueueExtension queue = new QueueExtension();
        queue.setEnabled(false);

        assertEquals(-1, queue.enqueue(InMemoryAgentSession.of(), "x"),
                "便捷重载不得绕过开关语义");
    }

    @Test
    void asyncDrainDefersAckUntilExplicitAcknowledge() {
        QueueExtension queue = new QueueExtension();
        AgentSession session = InMemoryAgentSession.of();
        queue.enqueue(session, "异步任务", null);

        // 异步语义：回调只启动，不确认
        assertTrue(queue.drainOne(session, item -> { }, false));
        assertEquals(0, queue.pendingSize(session), "in-flight 不计待执行");
        assertEquals(1, queue.size(session), "size 含 in-flight");

        // ack 带 id 才能确认
        List<SessionQueueItem> view = queue.view(session);
        assertTrue(queue.ack(session, view.get(0).getId()));
        assertEquals(0, queue.size(session));
    }

    @Test
    void cancelPendingClearsAndBumpsGeneration() {
        QueueExtension queue = new QueueExtension();
        AgentSession session = InMemoryAgentSession.of();
        queue.enqueue(session, "a", null);
        queue.enqueue(session, "b", null);

        assertTrue(queue.cancelPending(session));
        assertEquals(0, queue.pendingSize(session));
        assertFalse(queue.hasItems(session));
    }

    @Test
    void commandRegistrationIsIdempotentAndCanBeDisabled() {
        // 幂等：宿主先注册，扩展不覆盖
        HarnessEngine engine = newEngine();
        engine.getCommandRegistry().register(new QueueCommand());
        QueueExtension queue = new QueueExtension();
        engine.addExtension(queue);
        assertNotNull(engine.getCommandRegistry().find("queue"));

        // 关闭：命令照常注册但不可见（开关语义在查找时过滤）
        HarnessEngine engine2 = newEngine();
        QueueExtension disabled = new QueueExtension();
        disabled.setEnabled(false);
        engine2.addExtension(disabled);
        assertEquals(null, engine2.getCommandRegistry().find("queue"));
        assertNotNull(engine2.getCommandRegistry().get("queue"), "已注册才能区分停用与未注册");
    }

    @Test
    void disabledExtensionRejectsEnqueue() {
        HarnessEngine engine = newEngine();
        QueueExtension queue = new QueueExtension();
        engine.addExtension(queue);
        queue.setEnabled(false);

        AgentSession session = InMemoryAgentSession.of();
        assertEquals(-1, queue.enqueue(session, "x", null), "停用后不再接单");
        assertFalse(queue.hasItems(session));
    }
}
