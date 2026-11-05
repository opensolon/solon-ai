package org.noear.solon.ai.harness.extension.steer;

import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.ai.harness.input.InputOrigin;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.harness.command.builtin.Fakes;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * /steer 命令编排层测试。
 *
 * <p>命令经 {@link SteerExtension} 门面投递：engine 传 null 时回落无引擎门面（缺省约束、忙判定放行）；
 * 需要先开轮（模拟运行中任务），未开轮时为 NOT_RUNNING。</p>
 */
class SteerCommandTest {
    private final SteerCommand command = new SteerCommand();

    @TempDir
    Path workspace;

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
        new SteerExtension().steer(session, "第一条", InputOrigin.channel("command"));
        command.execute(null, ctx, Fakes.StubContext.imOrigin());
        assertTrue(ctx.output().contains("待生效 2 条"));
    }

    @Test
    void boxFullAdvisesQueueWithoutDegrading() {
        Fakes.StubContext ctx = Fakes.StubContext.im("补充要求");
        AgentSession session = ctx.getSession();
        withActiveRun(session, "run-1");
        SteerExtension ext = new SteerExtension();
        for (int i = 0; i < SteerHelper.MAX_BOX_SIZE; i++) {
            assertEquals(SteerStatus.STEERED, ext.steer(session, "seed-" + i).getStatus());
        }
        command.execute(null, ctx, Fakes.StubContext.imOrigin());
        assertTrue(ctx.output().contains("插话邮箱已满（上限 " + SteerHelper.MAX_BOX_SIZE + " 条），可用 /queue 排队"));
        assertEquals(0, queuePending(session));
    }

    @Test
    void notRunningAdvisesQueueWithoutDegrading() {
        Fakes.StubContext ctx = Fakes.StubContext.im("补充要求");
        command.execute(null, ctx, Fakes.StubContext.imOrigin()); // 未开轮
        assertTrue(ctx.output().contains("当前无正在执行的任务可插话，可直接发送或用 /queue 排队"));
        assertTrue(ctx.tasks.isEmpty());
        assertEquals(0, queuePending(ctx.getSession()));
    }

    @Test
    void textTooLongPrintsLimit() {
        StringBuilder longText = new StringBuilder();
        for (int i = 0; i < SteerHelper.MAX_TEXT_LENGTH + 1; i++) longText.append('x');
        Fakes.StubContext ctx = Fakes.StubContext.im(longText.toString());
        command.execute(null, ctx, Fakes.StubContext.imOrigin());
        assertTrue(ctx.output().contains("上限 " + SteerHelper.MAX_TEXT_LENGTH + " 字符"));
    }

    @Test
    void localSourceDefaultsToCommand() {
        Fakes.StubContext ctx = Fakes.StubContext.local("补充要求");
        AgentSession session = ctx.getSession();
        withActiveRun(session, "run-1");
        command.execute(null, ctx, Fakes.StubContext.localOrigin());
        SteerMessage msg = firstPending(session);
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
        SteerMessage msg = firstPending(session);
        assertNotNull(msg);
        assertEquals("Feishu", msg.getOrigin().getChannel());
        assertEquals("user-1", msg.getOrigin().getSenderId());
        assertEquals("chat-1", msg.getOrigin().getReplyTarget());
        assertEquals("message-1", msg.getOrigin().getMessageId());
    }

    /** 经门面投递：所属扩展停用时返回 DISABLED（旧实现直连内核，开关不生效）。 */
    @Test
    void ownerDisabledReportsDisabled() {
        SteerExtension ext = new SteerExtension();
        SteerCommand owned = new SteerCommand(ext);
        ext.setEnabled(false);

        Fakes.StubContext ctx = Fakes.StubContext.im("补充要求");
        withActiveRun(ctx.getSession(), "run-1");
        owned.execute(null, ctx, Fakes.StubContext.imOrigin());

        assertTrue(ctx.output().contains("插话能力已停用"));
        assertNull(firstPending(ctx.getSession()));
    }

    /** 经门面投递：引擎判定会话不忙（任务已异常退出但轮次未收）时回滚，不投进不会再运行的邮箱。 */
    @Test
    void attachedEngineNotBusyRollsBack() {
        HarnessEngine engine = HarnessEngine.of(workspace.toString(), workspace.resolve("home").toString())
                .sessionProvider(InMemoryAgentSession::of)
                .build();
        SteerExtension ext = SteerExtension.builder().maxBoxSize(7).build();
        engine.addExtension(ext);

        Fakes.StubContext ctx = Fakes.StubContext.im("补充要求");
        withActiveRun(ctx.getSession(), "run-1");
        new SteerCommand().execute(engine, ctx, Fakes.StubContext.imOrigin());

        assertTrue(ctx.output().contains("当前无正在执行的任务可插话"));
        assertNull(firstPending(ctx.getSession()), "回滚后不得残留");
    }

    private static void withActiveRun(AgentSession session, String runId) {
        SteerState.of(session).open(runId);
    }

    private static SteerMessage firstPending(AgentSession session) {
        SteerState state = SteerState.peek(session);
        return state == null || state.size() == 0 ? null : state.snapshot().get(0);
    }

    private static int queuePending(AgentSession session) {
        return new org.noear.solon.ai.harness.extension.queue.QueueExtension().pendingSize(session);
    }
}
