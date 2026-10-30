package org.noear.solon.ai.harness.command.builtin;

import org.junit.jupiter.api.Test;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.harness.input.InputOrigin;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * /rewind 命令 execute 级单元测试。
 * <p>
 * 覆盖：默认回退 1 条、显式 n 条、0/负数的拒绝回执、非整数参数静默按 1 回退
 * （Assert.isInteger 为 false 时不进分支）、ANSI 转义在非终端上下文被剥离。
 */
class RewindCommandTest {
    private final RewindCommand command = new RewindCommand();

    private static AgentSession sessionWith(int messages) {
        AgentSession session = Fakes.newSession();
        for (int i = 0; i < messages; i++) {
            session.addMessage(ChatMessage.ofUser("m" + i));
        }
        return session;
    }

    @Test
    void defaultRewindsOne() throws Exception {
        AgentSession session = sessionWith(3);
        Fakes.StubContext ctx = new Fakes.StubContext(session, new ArrayList<>());
        command.execute(null, ctx, InputOrigin.NONE);

        assertEquals(2, session.getMessages().size());
        assertTrue(ctx.output().contains("已回退 1 条记录"));
        assertTrue(ctx.output().contains("\033") == false); // ANSI 已被 color() 剥离
    }

    @Test
    void explicitCountRewindsN() throws Exception {
        AgentSession session = sessionWith(5);
        Fakes.StubContext ctx = new Fakes.StubContext(session, Arrays.asList("3"));
        command.execute(null, ctx, InputOrigin.NONE);

        assertEquals(2, session.getMessages().size());
        assertTrue(ctx.output().contains("已回退 3 条记录"));
    }

    @Test
    void zeroOrNegativeIsRejectedWithoutRemoving() throws Exception {
        AgentSession session = sessionWith(2);
        Fakes.StubContext ctx = new Fakes.StubContext(session, Arrays.asList("0"));
        command.execute(null, ctx, InputOrigin.NONE);

        assertEquals(2, session.getMessages().size());
        assertTrue(ctx.output().contains("回退数量必须为正整数"));

        Fakes.StubContext ctx2 = new Fakes.StubContext(session, Arrays.asList("-2"));
        command.execute(null, ctx2, InputOrigin.NONE);
        assertEquals(2, session.getMessages().size());
    }

    @Test
    void nonIntegerFallsBackToSilentOne() throws Exception {
        // 隐式行为锁定：isInteger("abc")=false → count 保持 1，无任何提示
        AgentSession session = sessionWith(3);
        Fakes.StubContext ctx = new Fakes.StubContext(session, Arrays.asList("abc"));
        command.execute(null, ctx, InputOrigin.NONE);

        assertEquals(2, session.getMessages().size());
        assertTrue(ctx.output().contains("已回退 1 条记录"));
    }

    @Test
    void nullArgsSameAsDefault() throws Exception {
        AgentSession session = sessionWith(2);
        Fakes.StubContext ctx = new Fakes.StubContext(session, Collections.singletonList(""));
        command.execute(null, ctx, InputOrigin.NONE);

        assertEquals(1, session.getMessages().size());
    }
}
