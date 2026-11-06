package org.noear.solon.ai.harness.command;

import org.junit.jupiter.api.Test;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.mount.source.FileMountSource;

import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 命令接口默认实现与意图载体。
 *
 * <p>这些默认值是命令分发器的判定依据（是否终端专属、是否允许忙碌运行），
 * 各命令覆写时必须显式取舍，故单独锁定。</p>
 */
class CommandContractTest {

    /** 最小命令实现：只用接口默认值。 */
    private static class MinimalCommand implements Command {
        @Override
        public String name() {
            return "minimal";
        }

        @Override
        public String description() {
            return "最小实现";
        }

        @Override
        public String[] examples() {
            return new String[]{"/minimal"};
        }

        @Override
        public void execute(org.noear.solon.ai.harness.HarnessEngine engine, CommandContext ctx,
                            org.noear.solon.ai.harness.input.InputOrigin origin) {
            ctx.println("ok");
        }
    }

    @Test
    void interfaceDefaults() {
        Command cmd = new MinimalCommand();
        // 默认不绑定模型、不限终端、忙碌时不可运行
        assertNull(cmd.model());
        assertFalse(cmd.terminalOnly());
        assertFalse(cmd.runnableWhenBusy());
    }

    @Test
    void examplesIsPartOfContract() {
        assertEquals(1, new MinimalCommand().examples().length);
    }

    @Test
    void deferredModelResolvesFromContext() {
        // 命令可用 model() 声明偏好；具体解析在分发器侧，此处锁定声明能力
        Command withModel = new MinimalCommand() {
            @Override
            public String model() {
                return "gpt-4";
            }
        };
        assertEquals("gpt-4", withModel.model());
    }

    @Test
    void terminalOnlyDefaultIsFalseAndOverridable() {
        assertFalse(new MinimalCommand().terminalOnly());
        Command terminal = new MinimalCommand() {
            @Override
            public boolean terminalOnly() {
                return true;
            }
        };
        assertTrue(terminal.terminalOnly());
    }

    @Test
    void runnableWhenBusyDefaultIsFalse() {
        // 会改会话结构或启新任务的命令不得覆写为 true
        assertFalse(new MinimalCommand().runnableWhenBusy());
    }

    // ========== AgentTaskIntent ==========

    @Test
    void intentResumeWhenPromptEmpty() {
        assertTrue(new AgentTaskIntent(null, null).isResume());
        assertTrue(new AgentTaskIntent("", null).isResume());
        assertFalse(new AgentTaskIntent("做事", null).isResume());
    }

    @Test
    void intentToStringDistinguishesResume() {
        String resumed = new AgentTaskIntent(null, null).toString();
        String normal = new AgentTaskIntent("做事", null).toString();

        assertTrue(resumed.contains("<resume>"));
        assertFalse(normal.contains("<resume>"));
        assertTrue(normal.contains("做事"));
        // 无模型时不出现 model 段
        assertFalse(normal.contains("model="));
    }

    @Test
    void intentToStringIncludesModelWhenPresent() {
        AgentTaskIntent intent = new AgentTaskIntent("做事", "gpt-4");
        assertTrue(intent.toString().contains("model=gpt-4"));
    }

    // ========== BufferedCommandContext ==========

    private static CommandContext ctx(String rawInput, String name, String... args) {
        return new BufferedCommandContext(
                InMemoryAgentSession.of("s1"), rawInput, name, Arrays.asList(args), false);
    }

    @Test
    void bufferedContextExposesRawInputAndName() {
        CommandContext c = ctx("/queue hello", "queue", "hello");
        assertEquals("/queue hello", c.getRawInput());
        assertEquals("queue", c.getCommandName());
        assertEquals(Collections.singletonList("hello"), c.getArgs());
        assertFalse(c.isTerminal());
    }

    @Test
    void bufferedContextCollectsOutput() {
        CommandContext c = ctx("/help", "help");
        c.println("第一行");
        c.println("第二行");

        String out = c.getCommandOutput();
        assertTrue(out.contains("第一行"));
        assertTrue(out.contains("第二行"));
    }

    @Test
    void bufferedContextBindsSession() {
        AgentSession session = InMemoryAgentSession.of("s1");
        CommandContext c = new BufferedCommandContext(session, "/x", "x",
                Collections.<String>emptyList(), true);

        assertEquals(session, c.getSession());
        assertTrue(c.isTerminal());
    }

    @Test
    void bufferedContextArgAccess() {
        CommandContext c = ctx("/steer a b", "steer", "a", "b");
        assertEquals("a", c.argAt(0));
        assertEquals("b", c.argAt(1));
        assertNull(c.argAt(9));
        // 接口默认实现只判上界：负索引会落到 List.get 抛 AIOOBE（现状锁定）
        assertThrows(IndexOutOfBoundsException.class, () -> c.argAt(-1));
    }

    // ========== ParsedCommand 与意图组合 ==========

    @Test
    void intentFromParsedCommandArgs() {
        ParsedCommand parsed = ParsedCommand.parse("/queue 帮我跑测试");
        AgentTaskIntent intent = new AgentTaskIntent(parsed.getArgsJoined(), null);

        assertFalse(intent.isResume());
        assertEquals("帮我跑测试", intent.getPrompt());
    }

    @Test
    void loadFromDirectoryIgnoresMissingDir() {
        CommandRegistry registry = new CommandRegistry();
        MarkdownCommandLoader.loadFrom(FileMountSource.of(
                Paths.get(System.getProperty("java.io.tmpdir"), "no-such-dir-xyz")), "", registry);

        assertTrue(registry.all().isEmpty());
    }

    // ========== CommandRegistry 基础 ==========

    @Test
    void registryRegisterAndLookup() {
        CommandRegistry registry = new CommandRegistry();
        Command cmd = new MinimalCommand();
        registry.register(cmd);

        assertEquals(cmd, registry.find("minimal"));
        assertNull(registry.find("nope"));
        assertEquals(1, registry.all().size());
    }

    @Test
    void registryFirstRegistrationWins() {
        CommandRegistry registry = new CommandRegistry();
        Command a = new MinimalCommand();
        Command b = new MinimalCommand();
        registry.register(a);
        registry.register(b);

        // 同名先注册者优先（不被后注册覆盖）
        assertEquals(a, registry.find("minimal"));
    }

    @Test
    void registryRejectsNullWithNpe() {
        CommandRegistry registry = new CommandRegistry();
        // 现状：register 未做 null 守卫，直接 NPE（调用方都是框架内部，无外部受害者）
        assertThrows(NullPointerException.class, () -> registry.register(null));
        assertTrue(registry.all().isEmpty());
        // ConcurrentHashMap 不接受 null key：find(null) 同样 NPE
        assertThrows(NullPointerException.class, () -> registry.find(null));
    }

    @Test
    void registryListsNames() {
        CommandRegistry registry = new CommandRegistry();
        registry.register(new MinimalCommand());
        List<String> names = registry.names();
        assertTrue(names.contains("minimal"));
    }
}
