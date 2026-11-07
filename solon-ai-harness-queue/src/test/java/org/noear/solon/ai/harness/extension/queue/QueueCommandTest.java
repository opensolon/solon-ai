package org.noear.solon.ai.harness.extension.queue;

import org.junit.jupiter.api.Test;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.harness.command.builtin.Fakes;
import org.noear.solon.ai.harness.input.InputOrigin;

import java.util.Arrays;

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
        assertEquals(1, QueueStore.pendingSize(ctx.getSession()));
        QueueItem item = QueueStore.snapshot(ctx.getSession()).get(0);
        assertEquals("接着跑测试", item.getText());
        assertEquals("Feishu", item.getOrigin().getChannel());
        assertEquals("user-1", item.getOrigin().getSenderId());
        assertEquals("chat-1", item.getOrigin().getReplyTarget());
        assertEquals("message-1", item.getOrigin().getMessageId());
        assertTrue(ctx.output().contains("已加入队列（第 1 位）"));
    }

    @Test
    void localSourceStaysNoneInsteadOfHardcodedChannel() {
        // 渠道兜底已删：命令层不再硬编码 "CLI"，本地无渠道来源（NONE）是合法值，
        // 展示层用 channelOr 自行降级。origin 携带什么就存什么。
        Fakes.StubContext ctx = Fakes.StubContext.local("接着跑测试");
        command.execute(null, ctx, Fakes.StubContext.localOrigin());
        QueueItem item = QueueStore.snapshot(ctx.getSession()).get(0);
        assertNull(item.getOrigin().getChannel(), "本地入队不得被伪造渠道标识");
        assertEquals("CLI", item.getOrigin().channelOr("CLI"), "展示层降级由消费方决定");
    }

    @Test
    void tooLongTextRejected() {
        StringBuilder longText = new StringBuilder();
        for (int i = 0; i < 4097; i++) {
            longText.append('x');
        }
        Fakes.StubContext ctx = Fakes.StubContext.im(longText.toString());
        command.execute(null, ctx, Fakes.StubContext.imOrigin());
        assertEquals(0, QueueStore.pendingSize(ctx.getSession()));
        assertTrue(ctx.output().contains("排队内容过长（上限 4096 字符）"));
    }

    @Test
    void enqueueFailurePrintsLimit() {
        Fakes.StubContext ctx = Fakes.StubContext.im("接着跑测试");
        AgentSession session = ctx.getSession();
        for (int i = 0; i < QueueStore.MAX_QUEUE_SIZE; i++) {
            QueueStore.enqueue(session, "fill-" + i, null);
        }
        command.execute(null, ctx, Fakes.StubContext.imOrigin());
        assertEquals(0, ctx.tasks.size());
        assertEquals(QueueStore.MAX_QUEUE_SIZE, QueueStore.pendingSize(session));
        assertTrue(ctx.output().contains("排队失败（队列已满，上限 20 条）"));
    }

    private static Fakes.StubContext args(AgentSession session, String... args) {
        return new Fakes.StubContext(session, Arrays.asList(args));
    }

    @Test
    void listCancelAndClearSubcommands() {
        AgentSession session = Fakes.StubContext.local("x").getSession();
        QueueStore.enqueue(session, "a", null);
        QueueStore.enqueue(session, "b", null);
        QueueStore.enqueue(session, "c", null);

        Fakes.StubContext list = args(session, "list");
        command.execute(null, list, InputOrigin.NONE);
        assertTrue(list.output().contains("队列（共 3 条）"), list.output());

        command.execute(null, args(session, "cancel", "1"), InputOrigin.NONE);
        assertEquals(2, QueueStore.pendingSize(session), "按序号取消");
        String idOfC = QueueStore.view(session).get(1).getId();
        command.execute(null, args(session, "cancel", idOfC), InputOrigin.NONE);
        assertEquals(1, QueueStore.pendingSize(session), "按 id 取消");

        Fakes.StubContext clear = args(session, "clear");
        command.execute(null, clear, InputOrigin.NONE);
        assertEquals(0, QueueStore.pendingSize(session));
        assertTrue(clear.output().contains("已清空 1 条"), clear.output());
    }

    @Test
    void clearKeepsInFlightItem() {
        AgentSession session = Fakes.StubContext.local("x").getSession();
        QueueStore.enqueue(session, "running", null);
        QueueStore.enqueue(session, "pending", null);
        QueueStore.poll(session);

        command.execute(null, args(session, "clear"), InputOrigin.NONE);
        assertEquals(0, QueueStore.pendingSize(session));
        assertTrue(QueueStore.hasItems(session), "正在执行的那条不受 clear 影响");
    }

    @Test
    void naturalLanguageAndEscapedWordsAreEnqueued() {
        AgentSession session = Fakes.StubContext.local("x").getSession();
        command.execute(null, args(session, "list", "all", "files"), InputOrigin.NONE);
        command.execute(null, args(session, "--", "clear"), InputOrigin.NONE);
        assertEquals(2, QueueStore.pendingSize(session));
        assertEquals("list all files", QueueStore.snapshot(session).get(0).getText());
        assertEquals("clear", QueueStore.snapshot(session).get(1).getText());
    }
}
