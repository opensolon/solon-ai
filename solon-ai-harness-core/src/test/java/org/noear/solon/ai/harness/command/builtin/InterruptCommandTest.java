package org.noear.solon.ai.harness.command.builtin;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.AgentTrace;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.agent.session.SessionRuns;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.ai.harness.input.InputOrigin;
import org.noear.solon.ai.harness.util.TraceUtil;
import reactor.core.Disposable;

import java.nio.file.Path;
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
 * 覆盖：运行句柄释放恰一次 + 追加取消消息 + runId 元数据、
 * 无任务时的友好回执、runnableWhenBusy 契约、session 为 null 的静默路径。
 *
 * <p>命令只是 {@code engine.interrupt(session, InterruptMode.INTERRUPT_ALL)} 的薄壳，因此一律用真实引擎执行（不存在绕开引擎的第二套中断）。</p>
 *
 * <p>句柄一律经 {@link SessionRuns#attach} 注入：早期版本直接写
 * {@code attrs("disposable")}，那个约定已随运行句柄上收到框架而废弃。</p>
 */
class InterruptCommandTest {
    private final InterruptCommand command = new InterruptCommand();

    @TempDir
    Path workspace;

    private HarnessEngine engine() {
        return HarnessEngine.of(workspace.toString(), null)
                .sessionProvider((id, c) -> InMemoryAgentSession.of(id))
                .build();
    }

    @Test
    void runnableWhenBusyIsTrueByContract() {
        // IM 渠道唯一的中断途径，必须可穿透忙碌检查
        assertTrue(command.runnableWhenBusy());
    }

    @Test
    void sessionNullIsSilentNoOp() throws Exception {
        Fakes.StubContext ctx = new Fakes.StubContext(null, new ArrayList<>());
        command.execute(engine(), ctx.in(InputOrigin.NONE), ctx);
        assertTrue(ctx.out.isEmpty());
    }

    @Test
    void engineNullIsSilentNoOp() throws Exception {
        AgentSession session = Fakes.newSession();
        assertTrue(SessionRuns.attach(session, new Disposable() {
            @Override
            public void dispose() {
            }

            @Override
            public boolean isDisposed() {
                return false;
            }
        }));
        Fakes.StubContext ctx = new Fakes.StubContext(session, new ArrayList<>());
        command.execute(null, ctx.in(InputOrigin.NONE), ctx);
        assertTrue(ctx.out.isEmpty());
        assertTrue(session.getMessages().isEmpty(), "无引擎不得绕过引擎私自中断");
    }

    @Test
    void noRunningTaskPrintsHint() throws Exception {
        AgentSession session = Fakes.newSession();
        Fakes.StubContext ctx = new Fakes.StubContext(session, new ArrayList<>());
        command.execute(engine(), ctx.in(InputOrigin.NONE), ctx);

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
        assertTrue(SessionRuns.attach(session, disposable));

        HarnessEngine engine = engine();
        Fakes.StubContext ctx = new Fakes.StubContext(session, new ArrayList<>());
        command.execute(engine, ctx.in(InputOrigin.NONE), ctx);

        assertEquals(1, disposed.get());
        assertTrue(ctx.output().contains("用户已取消任务（或中断）"));

        // 无 trace：取消消息照常追加，但无 runId 元数据
        List<ChatMessage> messages = session.getMessages();
        assertEquals(1, messages.size());
        assertTrue(messages.get(0) instanceof AssistantMessage);
        assertEquals("用户已取消任务.", messages.get(0).getContent());
        assertNull(((AssistantMessage) messages.get(0)).getMetadataAs(AgentTrace.META_RUN_ID));

        // 句柄已被取消：重复执行进入「无任务」分支（只回执，不追加消息、不重复 dispose）
        Fakes.StubContext ctx2 = new Fakes.StubContext(session, new ArrayList<>());
        command.execute(engine, ctx2.in(InputOrigin.NONE), ctx2);
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
        session.getContext().put(TraceUtil.TRACE_KEY_MAIN, trace);

        assertTrue(SessionRuns.attach(session, new Disposable() {
            @Override
            public void dispose() {
            }

            @Override
            public boolean isDisposed() {
                return false;
            }
        }));

        Fakes.StubContext ctx = new Fakes.StubContext(session, new ArrayList<>());
        command.execute(engine(), ctx.in(InputOrigin.NONE), ctx);

        assertNotNull(trace.getRunId());
        AssistantMessage last = (AssistantMessage) session.getMessages().get(session.getMessages().size() - 1);
        assertEquals(trace.getRunId(), last.getMetadataAs(AgentTrace.META_RUN_ID));
    }
}
