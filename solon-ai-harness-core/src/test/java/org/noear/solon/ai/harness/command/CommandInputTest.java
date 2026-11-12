package org.noear.solon.ai.harness.command;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.ai.harness.input.HarnessInput;
import org.noear.solon.ai.harness.input.InputOrigin;
import org.noear.solon.ai.harness.scope.InputScope;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CommandInput：命令参数访问、渠道/语言/本轮属性的只读委托。
 */
class CommandInputTest {
    @TempDir
    Path workspace;

    private static CommandInput of(String raw) {
        return CommandInput.of(HarnessInput.of(InMemoryAgentSession.of("s1"), raw), ParsedCommand.parse(raw), null);
    }

    @Test
    void exposesCommandParts() {
        CommandInput in = of("/Queue hello world");

        assertEquals("/Queue hello world", in.getRawInput());
        assertEquals("queue", in.getCommandName());
        assertEquals(Arrays.asList("hello", "world"), in.getArgs());
        assertEquals(2, in.argCount());
        assertEquals("hello world", in.getArgsJoined());
        assertEquals("s1", in.getSession().getSessionId());
    }

    @Test
    void argAtBounds() {
        CommandInput in = of("/steer a b");

        assertEquals("a", in.argAt(0));
        assertEquals("b", in.argAt(1));
        assertNull(in.argAt(2));    // 上界越界返回 null
        assertNull(in.argAt(-1));   // 负索引同样返回 null（与 ParsedCommand 一致）
    }

    @Test
    void emptyArgsAreSafe() {
        CommandInput in = of("/help");

        assertEquals(0, in.argCount());
        assertNull(in.argAt(0));
        assertEquals("", in.getArgsJoined());
        assertTrue(in.getArgs().isEmpty());
    }

    @Test
    void directParsedCommandNormalizesArgs() {
        ParsedCommand cmd = ParsedCommand.of("Queue", null, null);
        assertEquals("queue", cmd.getName());
        assertTrue(cmd.getArgs().isEmpty());

        ParsedCommand withArgs = ParsedCommand.of("x", Collections.singletonList("a b"), "/x");
        assertEquals("a b", withArgs.argAt(0), "直接构造不再按空白切分");
        assertThrows(UnsupportedOperationException.class, () -> withArgs.getArgs().add("c"));
    }

    @Test
    void delegatesChannelAndTurnAttributes() {
        AgentSession session = InMemoryAgentSession.of("s1");
        InputOrigin origin = InputOrigin.of("Feishu", "u1", "c1", "m1");
        InputScope scope = InputScope.builder("alice").build();
        HarnessInput input = HarnessInput.of(session, "/x")
                .origin(origin)
                .terminal(true)
                .systemPrompt("sp")
                .selectedModel("m")
                .selectedAgent("coder")
                .principal("alice")
                .selectedSkill("s1")
                .selectedMcp("mcp1")
                .scope(scope)
                .attr("effort", "high");

        CommandInput in = CommandInput.of(input, ParsedCommand.parse("/x"), Locale.ENGLISH);

        assertSame(session, in.getSession());
        assertSame(origin, in.getOrigin());
        assertTrue(in.isTerminal());
        assertEquals(Locale.ENGLISH, in.getLocale());
        assertEquals("sp", in.getSystemPrompt());
        assertEquals("m", in.getSelectedModel());
        assertEquals("coder", in.getSelectedAgent());
        assertEquals("alice", in.getPrincipal());
        assertTrue(in.hasSkillSelection());
        assertEquals(Collections.singletonList("s1"), in.getSelectedSkills());
        assertTrue(in.hasMcpSelection());
        assertEquals(Collections.singletonList("mcp1"), in.getSelectedMcps());
        assertSame(scope, in.getScope());
        assertTrue(in.isScopeExplicit());
        assertEquals("high", in.getAttr("effort"));
        assertEquals(Collections.singletonMap("effort", "high"), in.getAttrs());
    }

    @Test
    void defaultsWhenInputIsBare() {
        CommandInput in = CommandInput.of(HarnessInput.of(null, "/x"), ParsedCommand.parse("/x"), null);

        assertNull(in.getSession());
        assertSame(InputOrigin.NONE, in.getOrigin());
        assertFalse(in.isTerminal());
        assertNull(in.getLocale());
        assertFalse(in.hasSkillSelection());
        assertTrue(in.getSelectedSkills().isEmpty());
        assertNull(in.getScope());
        assertFalse(in.isScopeExplicit());
        assertTrue(in.getAttrs().isEmpty());
    }

    @Test
    void readOnlyViews() {
        HarnessInput input = HarnessInput.of(null, "/x").selectedSkill("a").selectedMcp("b").attr("k", "v");
        CommandInput in = CommandInput.of(input, ParsedCommand.parse("/x y"), null);

        assertThrows(UnsupportedOperationException.class, () -> in.getAttrs().put("k2", "v2"));
        assertThrows(UnsupportedOperationException.class, () -> in.getSelectedSkills().add("c"));
        assertThrows(UnsupportedOperationException.class, () -> in.getSelectedMcps().add("c"));
        assertThrows(UnsupportedOperationException.class, () -> in.getArgs().add("z"));
    }

    @Test
    void effectiveLocaleComesFromEngine() {
        HarnessEngine engine = HarnessEngine.of(workspace.toString(), workspace.resolve("home").toString())
                .sessionProvider((id, c) -> InMemoryAgentSession.of(id))
                .locale(Locale.ENGLISH)
                .build();

        //输入未指定语言：取引擎实例语言
        HarnessInput bare = HarnessInput.of(null, "/x");
        assertEquals(engine.localeOf(bare), CommandInput.of(engine, bare, ParsedCommand.parse("/x")).getLocale());
        assertEquals(Locale.ENGLISH, CommandInput.of(engine, bare, ParsedCommand.parse("/x")).getLocale());

        //输入指定语言：输入优先
        HarnessInput zh = HarnessInput.of(null, "/x").locale(Locale.CHINESE);
        assertEquals(Locale.CHINESE, CommandInput.of(engine, zh, ParsedCommand.parse("/x")).getLocale());
        //原始值与生效值含义不同
        assertNull(bare.getLocale());
    }
}
