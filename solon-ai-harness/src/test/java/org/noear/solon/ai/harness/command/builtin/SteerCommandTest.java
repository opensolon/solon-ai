package org.noear.solon.ai.harness.command.builtin;

import org.noear.solon.ai.harness.input.InputOrigin;

import org.junit.jupiter.api.Test;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.harness.session.steer.SteerInterceptor;
import org.noear.solon.ai.harness.session.steer.SteerInterceptor.SteerResult;
import org.noear.solon.ai.harness.session.steer.SteerInterceptor.SteerStatus;
import org.noear.solon.ai.harness.session.steer.SteerMessage;

import static org.junit.jupiter.api.Assertions.*;

/**
 * /steer 命令编排层测试（直连 SteerInterceptor，engine 传 null 同既有口径）。
 * 场景：需要先在 attrs 中建立 activeRunId（模拟运行中任务），未建立时为 NOT_RUNNING。
 */
class SteerCommandTest {
    private final SteerCommand command = new SteerCommand();

    @Test
    void emptyArgsPrintsUsage() {
        Fakes.StubContext ctx = Fakes.StubContext.im("");
        command.execute(null, ctx, Fakes.StubContext.imOrigin());
        assertTrue(ctx.output().contains("用法：/steer"));
    }

    @Test
    void steeredPrintsPendingCount() {
        Fakes.StubContext ctx = Fakes.StubContext.im("补充要求");
        withActiveRun(ctx.getSession(), "run-1");
        command.execute(null, ctx, Fakes.StubContext.imOrigin());
        assertTrue(ctx.output().contains("已插话，将在下一步注入当前任务（待生效 1 条）"));
    }

    @Test
    void secondSteerAccumulatesPendingCount() {
        Fakes.StubContext ctx = Fakes.StubContext.im("补充要求");
        AgentSession session = ctx.getSession();
        withActiveRun(session, "run-1");
        SteerInterceptor.steer(session, null, null, "第一条", InputOrigin.channel("command"), null);
        command.execute(null, ctx, Fakes.StubContext.imOrigin());
        assertTrue(ctx.output().contains("待生效 2 条"));
    }

    @Test
    void boxFullAdvisesQueueWithoutDegrading() {
        Fakes.StubContext ctx = Fakes.StubContext.im("补充要求");
        AgentSession session = ctx.getSession();
        withActiveRun(session, "run-1");
        for (int i = 0; i < SteerInterceptor.MAX_BOX_SIZE; i++) {
            SteerResult r = SteerInterceptor.steer(session, null, null, "seed-" + i, InputOrigin.channel("command"), null);
            assertEquals(SteerStatus.STEERED, r.getStatus());
        }
        command.execute(null, ctx, Fakes.StubContext.imOrigin());
        assertTrue(ctx.output().contains("插话邮箱已满（上限 " + SteerInterceptor.MAX_BOX_SIZE + " 条），可用 /queue 排队"));
        assertEquals(0, queuePending(session));
    }

    @Test
    void notRunningAdvisesQueueWithoutDegrading() {
        Fakes.StubContext ctx = Fakes.StubContext.im("补充要求");
        command.execute(null, ctx, Fakes.StubContext.imOrigin()); // 无 activeRunId
        assertTrue(ctx.output().contains("当前无正在执行的任务可插话，可直接发送或用 /queue 排队"));
        assertTrue(ctx.tasks.isEmpty());
        assertEquals(0, queuePending(ctx.getSession()));
    }

    @Test
    void textTooLongPrintsLimit() {
        StringBuilder longText = new StringBuilder();
        for (int i = 0; i < SteerInterceptor.MAX_TEXT_LENGTH + 1; i++) longText.append('x');
        Fakes.StubContext ctx = Fakes.StubContext.im(longText.toString());
        command.execute(null, ctx, Fakes.StubContext.imOrigin());
        assertTrue(ctx.output().contains("上限 " + SteerInterceptor.MAX_TEXT_LENGTH + " 字符"));
    }

    @Test
    void localSourceDefaultsToCommand() {
        Fakes.StubContext ctx = Fakes.StubContext.local("补充要求");
        AgentSession session = ctx.getSession();
        withActiveRun(session, "run-1");
        command.execute(null, ctx, Fakes.StubContext.localOrigin());
        SteerMessage msg = SteerInterceptor.steerBox(session).poll();
        assertNotNull(msg);
        assertEquals("command", msg.getOrigin().getChannel());
        assertEquals("补充要求", msg.getText());
    }

    @Test
    void imMetadataPassedThrough() {
        Fakes.StubContext ctx = Fakes.StubContext.im("补充要求");
        AgentSession session = ctx.getSession();
        withActiveRun(session, "run-1");
        command.execute(null, ctx, Fakes.StubContext.imOrigin());
        SteerMessage msg = SteerInterceptor.steerBox(session).poll();
        assertNotNull(msg);
        assertEquals("Feishu", msg.getOrigin().getChannel());
        assertEquals("user-1", msg.getOrigin().getSenderId());
        assertEquals("chat-1", msg.getOrigin().getReplyTarget());
        assertEquals("message-1", msg.getOrigin().getMessageId());
    }

    private static void withActiveRun(AgentSession session, String runId) {
        session.attrs().put(SteerInterceptor.ATTR_ACTIVE_RUN_ID, runId);
    }

    private static int queuePending(AgentSession session) {
        return org.noear.solon.ai.harness.session.queue.SessionQueue.pendingSize(session);
    }
}
