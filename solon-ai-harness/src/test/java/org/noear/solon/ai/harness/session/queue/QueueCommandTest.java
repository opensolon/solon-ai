package org.noear.solon.ai.harness.session.queue;

import org.junit.jupiter.api.Test;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.harness.command.builtin.Fakes;

import static org.junit.jupiter.api.Assertions.*;

/**
 * /queue 命令编排层测试（直连 SessionQueue，engine 传 null 同既有口径）。
 * 入队结果与容量/长度上限文案由领域类事实锁定。
 */
class QueueCommandTest {
    private final QueueCommand command = new QueueCommand();

    @Test
    void emptyArgsPrintsUsage() {
        Fakes.StubContext ctx = Fakes.StubContext.im("");
        command.execute(null, ctx, Fakes.StubContext.imOrigin());
        assertTrue(ctx.output().contains("用法：/queue"));
    }

    @Test
    void enqueuesWithImMetadata() {
        Fakes.StubContext ctx = Fakes.StubContext.im("接着跑测试");
        command.execute(null, ctx, Fakes.StubContext.imOrigin());
        assertEquals(1, QueueHelper.pendingSize(ctx.getSession()));
        QueueItem item = QueueHelper.snapshot(ctx.getSession()).get(0);
        assertEquals("接着跑测试", item.getText());
        assertEquals("Feishu", item.getOrigin().getChannel());
        assertEquals("user-1", item.getOrigin().getSenderId());
        assertEquals("chat-1", item.getOrigin().getReplyTarget());
        assertEquals("message-1", item.getOrigin().getMessageId());
        assertTrue(ctx.output().contains("已加入队列（第 1 位）"));
    }

    @Test
    void localSourceDefaultsToCli() {
        Fakes.StubContext ctx = Fakes.StubContext.local("接着跑测试");
        command.execute(null, ctx, Fakes.StubContext.localOrigin());
        QueueItem item = QueueHelper.snapshot(ctx.getSession()).get(0);
        assertEquals("CLI", item.getOrigin().getChannel());
    }

    @Test
    void tooLongTextRejected() {
        StringBuilder longText = new StringBuilder();
        for (int i = 0; i < 4097; i++) {
            longText.append('x');
        }
        Fakes.StubContext ctx = Fakes.StubContext.im(longText.toString());
        command.execute(null, ctx, Fakes.StubContext.imOrigin());
        assertEquals(0, QueueHelper.pendingSize(ctx.getSession()));
        assertTrue(ctx.output().contains("排队内容过长（上限 4096 字符）"));
    }

    @Test
    void enqueueFailurePrintsLimit() {
        Fakes.StubContext ctx = Fakes.StubContext.im("接着跑测试");
        AgentSession session = ctx.getSession();
        for (int i = 0; i < QueueHelper.MAX_QUEUE_SIZE; i++) {
            QueueHelper.enqueue(session, "fill-" + i, null);
        }
        command.execute(null, ctx, Fakes.StubContext.imOrigin());
        assertEquals(0, ctx.tasks.size());
        assertEquals(QueueHelper.MAX_QUEUE_SIZE, QueueHelper.pendingSize(session));
        assertTrue(ctx.output().contains("排队失败（队列已满，上限 20 条）"));
    }
}
