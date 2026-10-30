package org.noear.solon.ai.harness.command.builtin;

import org.junit.jupiter.api.Test;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.AgentTrace;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.harness.input.InputOrigin;
import org.noear.solon.ai.harness.util.TraceKeys;
import reactor.core.Disposable;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * /interrupt 命令 execute 级单元测试。
 * <p>
 * 覆盖：disposable 释放恰一次 + 追加取消消息 + runId 元数据、
 * 无任务时的友好回执、runnableWhenBusy 契约、session 为 null 的静默路径。
 */
class InterruptCommandTest {
    private final InterruptCommand command = new InterruptCommand();

    @Test
    void runnableWhenBusyIsTrueByContract() {
        // IM 渠道唯一的中断途径，必须可穿透忙碌检查
        assertTrue(command.runnableWhenBusy());
    }

    @Test
    void sessionNullIsSilentNoOp() throws Exception {
        Fakes.StubContext ctx = new Fakes.StubContext(null, new ArrayList<>());
        command.execute(null, ctx, InputOrigin.NONE);
        assertTrue(ctx.out.isEmpty());
    }

    @Test
    void noRunningTaskPrintsHint() throws Exception {
        AgentSession session = Fakes.newSession();
        Fakes.StubContext ctx = new Fakes.StubContext(session, new ArrayList<>());
        command.execute(null, ctx, InputOrigin.NONE);

        assertEquals(1, ctx.out.size());
        assertTrue(ctx.out.get(0).contains("当前没有正在执行的任务"));
        assertTrue(session.getMessages().isEmpty());
    }

    @Test
    void disposeExactlyOnceAndAppendCancelMessage() throws Exception {
        AgentSession session = Fakes.newSession();
        AtomicInteger disposed = new AtomicInteger();
        Disposable disposable = new Disposable() {
            @Override
            public void dispose() {
                disposed.incrementAndGet();
            }

            @Override
            public boolean isDisposed() {
                return disposed.get() > 0;
            }
        };
        session.attrs().put("disposable", disposable);

        Fakes.StubContext ctx = new Fakes.StubContext(session, new ArrayList<>());
        command.execute(null, ctx, InputOrigin.NONE);

        assertEquals(1, disposed.get());
        assertTrue(ctx.output().contains("用户已取消任务（或中断）"));

        // 无 trace：取消消息照常追加，但无 runId 元数据
        List<ChatMessage> messages = session.getMessages();
        assertEquals(1, messages.size());
        assertTrue(messages.get(0) instanceof AssistantMessage);
        assertEquals("用户已取消任务.", messages.get(0).getContent());
        assertNull(((AssistantMessage) messages.get(0)).getMetadataAs(AgentTrace.META_RUN_ID));

        // attrs 中的句柄已被 remove：重复执行进入「无任务」分支（只回执，不追加消息、不重复 dispose）
        Fakes.StubContext ctx2 = new Fakes.StubContext(session, new ArrayList<>());
        command.execute(null, ctx2, InputOrigin.NONE);
        assertEquals(1, disposed.get());
        assertEquals(1, session.getMessages().size());
        assertTrue(ctx2.output().contains("当前没有正在执行的任务"));
    }

    @Test
    void cancelMessageCarriesRunIdWhenTraceExists() throws Exception {
        AgentSession session = Fakes.newSession();

        // 构造 trace 并放入 __main（借 ContinueCommandTest 的反射工具）
        org.noear.solon.ai.agent.react.ReActTrace trace =
                ContinueCommandTest.newTrace(session);
        session.getContext().put(TraceKeys.TRACE_KEY_MAIN, trace);

        session.attrs().put("disposable", new Disposable() {
            @Override
            public void dispose() {
            }

            @Override
            public boolean isDisposed() {
                return true;
            }
        });

        Fakes.StubContext ctx = new Fakes.StubContext(session, new ArrayList<>());
        command.execute(null, ctx, InputOrigin.NONE);

        assertNotNull(trace.getRunId());
        AssistantMessage last = (AssistantMessage) session.getMessages().get(session.getMessages().size() - 1);
        assertEquals(trace.getRunId(), last.getMetadataAs(AgentTrace.META_RUN_ID));
    }
}
