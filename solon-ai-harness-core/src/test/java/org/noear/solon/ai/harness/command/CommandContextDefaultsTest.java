package org.noear.solon.ai.harness.command;

import org.junit.jupiter.api.Test;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CommandContext 接口默认方法单元测试：argAt 越界、argCount、getArgsJoined、
 * color 的 ANSI 剥离与 supportsAnsi 透传、isTerminal 默认 false。
 */
class CommandContextDefaultsTest {

    static class Base implements CommandContext {
        final List<String> args;

        Base(List<String> args) {
            this.args = args;
        }

        @Override
        public AgentSession getSession() {
            return InMemoryAgentSession.of();
        }

        @Override
        public String getRawInput() {
            return "/cmd";
        }

        @Override
        public String getCommandName() {
            return "cmd";
        }

        @Override
        public List<String> getArgs() {
            return args;
        }

        @Override
        public void println(String text) {
        }

        @Override
        public void requestAgentTask(String prompt, String model) {
        }
    }

    @Test
    void defaultsAreSafe() {
        Base ctx = new Base(new ArrayList<>());

        assertFalse(ctx.isTerminal());          // 默认无终端（Web/IM 视角）
        assertFalse(ctx.supportsAnsi());        // 默认不支持 ANSI
        assertNull(ctx.getAgentTaskIntent());   // 未声明意图
        assertNull(ctx.getCommandOutput());     // 默认无缓冲回执
        assertEquals(0, ctx.argCount());
        assertNull(ctx.argAt(0));
        assertEquals("", ctx.getArgsJoined());
    }

    @Test
    void argAtBounds() {
        Base ctx = new Base(Arrays.asList("a", "b"));

        assertEquals("a", ctx.argAt(0));
        assertEquals("b", ctx.argAt(1));
        assertNull(ctx.argAt(2));   // 上界越界返回 null（javadoc 契约）
        // 负索引：实际行为是 Arrays$ArrayList.get(-1) 抛 ArrayIndexOutOfBoundsException
        // （契约只承诺上界）——锁定真实行为，防止未来无意变更
        assertThrows(java.lang.ArrayIndexOutOfBoundsException.class, () -> ctx.argAt(-1));
    }

    @Test
    void nullArgsListIsSafe() {
        Base ctx = new Base(null);

        assertNull(ctx.argAt(0));
        assertEquals(0, ctx.argCount());
        assertEquals("", ctx.getArgsJoined());
    }

    @Test
    void argsJoinedWithSpace() {
        Base ctx = new Base(Arrays.asList("queue", "把测试跑一遍", "并修复"));
        assertEquals("queue 把测试跑一遍 并修复", ctx.getArgsJoined());
    }

    @Test
    void colorStripsAnsiWhenUnsupported() {
        Base ctx = new Base(new ArrayList<>());
        // 不支持 ANSI：转义序列被剥离（/model、/rewind 等命令的回执在 Web 端的形态）
        assertEquals("已回退 1 条记录", ctx.color("\033[2m已回退 1 条记录\033[0m"));
        assertEquals("纯文本", ctx.color("纯文本"));
    }

    @Test
    void colorKeepsAnsiWhenSupported() {
        CommandContext ctx = new Base(new ArrayList<>()) {
            @Override
            public boolean supportsAnsi() {
                return true;
            }
        };
        assertEquals("\033[1m加粗\033[0m", ctx.color("\033[1m加粗\033[0m"));
    }
}
