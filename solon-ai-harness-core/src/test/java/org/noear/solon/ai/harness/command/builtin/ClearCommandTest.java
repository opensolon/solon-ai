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
 * /clear 命令 execute 级单元测试。
 * <p>
 * 覆盖：清空会话消息、空会话幂等、session 为 null 的静默路径、不声明任务意图。
 */
class ClearCommandTest {
    private final ClearCommand command = new ClearCommand();

    @Test
    void sessionNullIsSilentNoOp() throws Exception {
        Fakes.StubContext ctx = new Fakes.StubContext(null, new ArrayList<>());
        command.execute(null, ctx.in(InputOrigin.NONE), ctx);
        assertTrue(ctx.out.isEmpty());
    }

    @Test
    void clearsMessages() throws Exception {
        AgentSession session = Fakes.newSession();
        session.addMessage(ChatMessage.ofUser("q1"));
        session.addMessage(ChatMessage.ofAssistant("a1"));

        Fakes.StubContext ctx = new Fakes.StubContext(session, new ArrayList<>());
        command.execute(null, ctx.in(InputOrigin.NONE), ctx);

        assertTrue(session.getMessages().isEmpty());
        // 纯会话操作命令：无回执、无任务意图
        assertTrue(ctx.out.isEmpty());
        assertNull(ctx.intent);
    }

    @Test
    void emptySessionIsIdempotent() throws Exception {
        AgentSession session = Fakes.newSession();
        Fakes.StubContext ctx = new Fakes.StubContext(session, new ArrayList<>());
        command.execute(null, ctx.in(InputOrigin.NONE), ctx);
        command.execute(null, ctx.in(InputOrigin.NONE), ctx);
        assertTrue(session.getMessages().isEmpty());
    }
}
