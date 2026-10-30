package org.noear.solon.ai.harness.command.builtin;

import org.junit.jupiter.api.Test;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.react.ReActAgent;
import org.noear.solon.ai.agent.react.ReActTrace;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.message.UserMessage;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.harness.input.InputOrigin;
import org.noear.solon.ai.harness.util.TraceKeys;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * /continue 命令 execute 级单元测试。
 * <p>
 * 覆盖：无可继续任务（无 trace / 空壳 trace）、END 轨迹重置回思考点、
 * 连续 Assistant 尾部消息弹出、续流意图声明（prompt 为空 = isResume）。
 */
class ContinueCommandTest {
    private final ContinueCommand command = new ContinueCommand();

    @Test
    void sessionNullIsSilentNoOp() throws Exception {
        // session 为 null：静默返回，不抛异常、无回执
        Fakes.StubContext ctx = new Fakes.StubContext(null, new ArrayList<>());
        command.execute(null, ctx, InputOrigin.NONE);
        assertTrue(ctx.out.isEmpty());
        assertNull(ctx.intent);
    }

    @Test
    void noTracePrintsHintAndDeclaresNothing() throws Exception {
        AgentSession session = Fakes.newSession();
        Fakes.StubContext ctx = new Fakes.StubContext(session, new ArrayList<>());
        command.execute(null, ctx, InputOrigin.NONE);

        assertEquals(1, ctx.out.size());
        assertTrue(ctx.out.get(0).contains("没有可继续的任务"));
        assertNull(ctx.intent);
    }

    @Test
    void emptyShellTraceOriginalPromptEmptyIsRejected() throws Exception {
        // __main 存在但从未跑过任务（originalPrompt 为空）：空壳轨迹，同样拒绝
        AgentSession session = Fakes.newSession();
        session.getContext().put(TraceKeys.TRACE_KEY_MAIN, new ReActTrace());
        Fakes.StubContext ctx = new Fakes.StubContext(session, new ArrayList<>());
        command.execute(null, ctx, InputOrigin.NONE);

        assertTrue(ctx.output().contains("没有可继续的任务"));
        assertNull(ctx.intent);
    }

    @Test
    void endRouteTraceResetsToReasonAndPopsTrailingAssistantMessages() throws Exception {
        AgentSession session = Fakes.newSession();

        // 构造 END 轨迹：originalPrompt 非空、route=END、finalAnswer 非空
        ReActTrace trace = newTrace(session);
        setOriginalPrompt(trace, Prompt.of("帮我修个 bug"));
        trace.setRoute(ReActAgent.ID_END);
        trace.setFinalAnswer("done", false);
        trace.getWorkingMemory().addMessage(ChatMessage.ofAssistant("工作记忆里的尾部 ai"));
        session.getContext().put(TraceKeys.TRACE_KEY_MAIN, trace);

        // 会话尾部两条 Assistant + 一条 User（不应被弹出）
        session.addMessage(ChatMessage.ofUser("帮我修个 bug"));
        session.addMessage(ChatMessage.ofAssistant("第一轮回答"));
        session.addMessage(ChatMessage.ofAssistant("最终回答"));

        Fakes.StubContext ctx = new Fakes.StubContext(session, new ArrayList<>());
        command.execute(null, ctx, InputOrigin.NONE);

        // 声明了续流意图（prompt 为空 = 续流）
        assertNotNull(ctx.intent);
        assertNull(ctx.intent.getPrompt());
        assertTrue(ctx.intent.isResume());

        // 轨迹重置回思考点
        assertEquals(ReActAgent.ID_REASON, trace.getRoute());
        assertNull(trace.getFinalAnswer());

        // working memory 尾部 Assistant 被移除
        ChatMessage lastWork = trace.getWorkingMemory().getLastMessage();
        assertFalse(lastWork instanceof AssistantMessage);

        // 会话尾部连续 Assistant 全部弹出，User 保留
        List<ChatMessage> messages = session.getMessages();
        assertEquals(1, messages.size());
        assertTrue(messages.get(0) instanceof UserMessage);
    }

    @Test
    void runningTraceKeepsMessagesAndStillDeclaresResume() throws Exception {
        // route != END（任务中断在中间态）：不动消息，直接声明续流
        AgentSession session = Fakes.newSession();
        ReActTrace trace = newTrace(session);
        setOriginalPrompt(trace, Prompt.of("跑到一半的任务"));
        trace.setRoute(ReActAgent.ID_REASON);
        session.getContext().put(TraceKeys.TRACE_KEY_MAIN, trace);

        session.addMessage(ChatMessage.ofUser("跑到一半的任务"));
        session.addMessage(ChatMessage.ofAssistant("中途回答"));

        Fakes.StubContext ctx = new Fakes.StubContext(session, new ArrayList<>());
        command.execute(null, ctx, InputOrigin.NONE);

        assertNotNull(ctx.intent);
        assertTrue(ctx.intent.isResume());
        assertEquals(2, session.getMessages().size());
    }

    // ---------- 反射构造工具（仓库先例：HITLBatchUnitTest.newTrace） ----------

    static ReActTrace newTrace(AgentSession session) {
        ReActTrace trace = new ReActTrace();
        try {
            Field f = ReActTrace.class.getDeclaredField("session");
            f.setAccessible(true);
            f.set(trace, session);
            return trace;
        } catch (Exception e) {
            throw new IllegalStateException("inject session into ReActTrace failed", e);
        }
    }

    static void setOriginalPrompt(ReActTrace trace, Prompt prompt) {
        try {
            Field f = ReActTrace.class.getDeclaredField("originalPrompt");
            f.setAccessible(true);
            f.set(trace, prompt);
        } catch (Exception e) {
            throw new IllegalStateException("inject originalPrompt failed", e);
        }
    }
}
