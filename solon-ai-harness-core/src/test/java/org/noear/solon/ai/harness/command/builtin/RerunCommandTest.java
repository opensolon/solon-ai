package org.noear.solon.ai.harness.command.builtin;

import org.junit.jupiter.api.Test;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.harness.input.InputOrigin;

import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * /rerun 命令 execute 级单元测试。
 * <p>
 * 覆盖：弹出尾部消息直到 UserMessage 并以其内容声明意图、
 * 会话为空时静默、全部消息均无 UserMessage 的破坏性边界（清空且不声明意图）、
 * UserMessage 恰为最后一条的精确重发。
 */
class RerunCommandTest {
    private final RerunCommand command = new RerunCommand();

    @Test
    void sessionNullIsSilentNoOp() throws Exception {
        Fakes.StubContext ctx = new Fakes.StubContext(null, new ArrayList<>());
        command.execute(null, ctx.in(InputOrigin.NONE), ctx);
        assertTrue(ctx.out.isEmpty());
        assertNull(ctx.intent);
    }

    @Test
    void emptySessionDeclaresNothing() throws Exception {
        AgentSession session = Fakes.newSession();
        Fakes.StubContext ctx = new Fakes.StubContext(session, new ArrayList<>());
        command.execute(null, ctx.in(InputOrigin.NONE), ctx);
        assertNull(ctx.intent);
        assertTrue(session.getMessages().isEmpty());
    }

    @Test
    void popsTrailingAssistantUntilUserAndDeclaresUserContent() throws Exception {
        AgentSession session = Fakes.newSession();
        session.addMessage(ChatMessage.ofUser("帮我写个函数"));
        session.addMessage(ChatMessage.ofAssistant("草稿一"));
        session.addMessage(ChatMessage.ofAssistant("草稿二"));

        Fakes.StubContext ctx = new Fakes.StubContext(session, new ArrayList<>());
        command.execute(null, ctx.in(InputOrigin.NONE), ctx);

        // 意图正文 = 被移除的最后一条 User 消息内容
        assertEquals("帮我写个函数", ctx.intent.getPrompt());
        assertFalseResume(ctx);
        // 弹到 User 为止（含 User 本身），会话清空
        assertTrue(session.getMessages().isEmpty());
    }

    @Test
    void userMessageIsLastOneRemovedExactly() throws Exception {
        AgentSession session = Fakes.newSession();
        session.addMessage(ChatMessage.ofUser("第一问"));
        session.addMessage(ChatMessage.ofAssistant("第一答"));
        session.addMessage(ChatMessage.ofUser("第二问"));

        Fakes.StubContext ctx = new Fakes.StubContext(session, new ArrayList<>());
        command.execute(null, ctx.in(InputOrigin.NONE), ctx);

        assertEquals("第二问", ctx.intent.getPrompt());
        // 只移除最后一条 User，前两轮保留
        assertEquals(2, session.getMessages().size());
    }

    @Test
    void noUserMessageAtAllClearsEverythingAndDeclaresNothing() throws Exception {
        // 破坏性边界锁定：全 Assistant 的会话被整体清空，却不声明任何意图
        AgentSession session = Fakes.newSession();
        session.addMessage(ChatMessage.ofAssistant("孤儿回答一"));
        session.addMessage(ChatMessage.ofAssistant("孤儿回答二"));

        Fakes.StubContext ctx = new Fakes.StubContext(session, new ArrayList<>());
        command.execute(null, ctx.in(InputOrigin.NONE), ctx);

        assertNull(ctx.intent);
        assertTrue(session.getMessages().isEmpty());
    }

    private static void assertFalseResume(Fakes.StubContext ctx) {
        // 普通任务意图（非续流）
        assertEquals(false, ctx.intent.isResume());
    }
}
