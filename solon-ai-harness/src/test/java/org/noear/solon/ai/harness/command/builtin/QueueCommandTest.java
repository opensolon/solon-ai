package org.noear.solon.ai.harness.command.builtin;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * /queue 命令编排层测试（fake 端口）：锁定入队参数、容量/长度提示与来源默认值。
 */
class QueueCommandTest {
    private final Fakes.StubQueuePort queuePort = new Fakes.StubQueuePort();
    private final QueueCommand command = new QueueCommand(queuePort);

    @Test
    void emptyArgsPrintsUsage() {
        Fakes.StubContext ctx = Fakes.StubContext.im("");
        command.execute(null, ctx);
        assertTrue(ctx.output().contains("用法：/queue"));
    }

    @Test
    void enqueuesWithImMetadata() {
        Fakes.StubContext ctx = Fakes.StubContext.im("接着跑测试");
        command.execute(null, ctx);
        assertEquals(1, queuePort.enqueues.size());
        assertTrue(queuePort.enqueues.get(0).contains("接着跑测试|Feishu|user-1|chat-1|message-1"));
        assertTrue(ctx.output().contains("已加入队列（第 1 位）"));
    }

    @Test
    void localSourceDefaultsToCli() {
        Fakes.StubContext ctx = Fakes.StubContext.local("接着跑测试");
        command.execute(null, ctx);
        assertTrue(queuePort.enqueues.get(0).contains("|CLI|"));
    }

    @Test
    void tooLongTextRejected() {
        StringBuilder longText = new StringBuilder();
        for (int i = 0; i < 4097; i++) {
            longText.append('x');
        }
        Fakes.StubContext ctx = Fakes.StubContext.im(longText.toString());
        command.execute(null, ctx);
        assertEquals(0, queuePort.enqueues.size());
        assertTrue(ctx.output().contains("排队内容过长（上限 4096 字符）"));
    }

    @Test
    void enqueueFailurePrintsLimit() {
        queuePort.enqueueResult = -1;
        Fakes.StubContext ctx = Fakes.StubContext.im("接着跑测试");
        command.execute(null, ctx);
        assertEquals(0, ctx.tasks.size());
        assertTrue(ctx.output().contains("排队失败（队列已满或持久化失败，上限 20 条）"));
    }
}
