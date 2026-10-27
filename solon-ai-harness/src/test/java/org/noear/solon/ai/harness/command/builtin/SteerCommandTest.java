package org.noear.solon.ai.harness.command.builtin;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * /steer 命令编排层测试（端口桩，engine 传 null 同既有测试口径）。
 * 覆盖：文案分支（纯投递、不降级）、渠道元数据透传。
 * 降级链（邮箱满/未运行转排队、空闲直发）已按设计移除，不再测试。
 */
class SteerCommandTest {
    private final Fakes.StubSteerPort steerPort = new Fakes.StubSteerPort();
    private final SteerCommand command = new SteerCommand(steerPort);

    @Test
    void emptyArgsPrintsUsage() {
        Fakes.StubContext ctx = Fakes.StubContext.im("");
        command.execute(null, ctx);
        assertTrue(ctx.output().contains("用法：/steer"));
    }

    @Test
    void steeredPrintsPendingCount() {
        steerPort.result = new org.noear.solon.ai.harness.session.steer.SteerInterceptor.SteerResult(
                org.noear.solon.ai.harness.session.steer.SteerInterceptor.SteerStatus.STEERED, "s_1", 3);
        Fakes.StubContext ctx = Fakes.StubContext.im("补充要求");
        command.execute(null, ctx);
        assertTrue(ctx.output().contains("已插话，将在下一步注入当前任务（待生效 3 条）"));
    }

    @Test
    void boxFullAdvisesQueueWithoutDegrading() {
        steerPort.result = steerResult("BOX_FULL");
        Fakes.StubContext ctx = Fakes.StubContext.im("补充要求");
        command.execute(null, ctx);
        assertTrue(ctx.output().contains("插话邮箱已满（上限 5 条），可用 /queue 排队"));
    }

    @Test
    void notRunningAdvisesQueueWithoutDegrading() {
        steerPort.result = steerResult("NOT_RUNNING");
        Fakes.StubContext ctx = Fakes.StubContext.im("补充要求");
        command.execute(null, ctx);
        assertTrue(ctx.output().contains("当前无正在执行的任务可插话，可直接发送或用 /queue 排队"));
        assertTrue(ctx.tasks.isEmpty());
    }

    @Test
    void turnChangedAdvisesSameAsNotRunning() {
        steerPort.result = steerResult("TURN_CHANGED");
        Fakes.StubContext ctx = Fakes.StubContext.im("补充要求");
        command.execute(null, ctx);
        assertTrue(ctx.output().contains("当前无正在执行的任务可插话"));
        assertTrue(ctx.tasks.isEmpty());
    }

    @Test
    void textTooLongPrintsLimit() {
        steerPort.result = steerResult("TEXT_TOO_LONG");
        Fakes.StubContext ctx = Fakes.StubContext.im("补充要求");
        command.execute(null, ctx);
        assertTrue(ctx.output().contains("上限 4096 字符"));
    }

    @Test
    void failedPrintsTransferredCode() {
        steerPort.result = steerResult("DUPLICATE_STEER_ID");
        Fakes.StubContext ctx = Fakes.StubContext.im("补充要求");
        command.execute(null, ctx);
        assertTrue(ctx.output().contains("插话未生效：DUPLICATE_STEER_ID"));
    }

    @Test
    void localSourceDefaultsToCommand() {
        steerPort.result = steerResult("STEERED");
        Fakes.StubContext ctx = Fakes.StubContext.local("补充要求");
        command.execute(null, ctx);
        assertEquals("command", steerPort.lastSource);
        assertEquals("补充要求", steerPort.lastText);
    }

    @Test
    void imMetadataPassedThrough() {
        steerPort.result = steerResult("STEERED");
        Fakes.StubContext ctx = Fakes.StubContext.im("补充要求");
        command.execute(null, ctx);
        assertEquals("Feishu", steerPort.lastSource);
        assertEquals("user-1", steerPort.lastSourceUserId);
        assertEquals("chat-1", steerPort.lastReplyTarget);
        assertEquals("message-1", steerPort.lastMessageId);
    }

    private static org.noear.solon.ai.harness.session.steer.SteerInterceptor.SteerResult steerResult(String code) {
        org.noear.solon.ai.harness.session.steer.SteerInterceptor.SteerStatus status =
                org.noear.solon.ai.harness.session.steer.SteerInterceptor.SteerStatus.valueOf(code);
        return new org.noear.solon.ai.harness.session.steer.SteerInterceptor.SteerResult(status, "s_1", 0);
    }
}
