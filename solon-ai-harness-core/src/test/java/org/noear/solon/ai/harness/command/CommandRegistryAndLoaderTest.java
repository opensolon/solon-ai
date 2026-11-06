package org.noear.solon.ai.harness.command;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.noear.solon.ai.harness.input.InputOrigin;
import org.noear.solon.ai.talents.mount.source.FileMountSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CommandRegistry 注册语义 + MarkdownCommandLoader 目录加载单元测试。
 * <p>
 * 覆盖：同名注册保留先者、排序输出、目录递归与命名空间命名
 * （deploy/staging.md → deploy:staging）、frontmatter 解析、空目录静默返回。
 */
class CommandRegistryAndLoaderTest {

    static Command cmd(String name) {
        return new Command() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public String description() {
                return "desc-" + name;
            }

            @Override
            public String[] examples() {
                return new String[]{"/" + name};
            }

            @Override
            public void execute(org.noear.solon.ai.harness.HarnessEngine engine,
                                CommandContext ctx, InputOrigin origin) {
            }
        };
    }

    @Test
    void registerKeepsFirstWinsOnDuplicate() {
        CommandRegistry registry = new CommandRegistry();
        registry.register(cmd("a"));
        registry.register(cmd("a"));

        assertEquals(1, registry.all().size());
        assertEquals("desc-a", registry.find("a").description());
    }

    @Test
    void namesAndAllAreSorted() {
        CommandRegistry registry = new CommandRegistry();
        registry.register(cmd("zebra"));
        registry.register(cmd("alpha"));
        registry.register(cmd("mid"));

        assertEquals(java.util.Arrays.asList("alpha", "mid", "zebra"), registry.names());
        assertEquals("alpha", registry.all().get(0).name());
    }

    @Test
    void findUnknownReturnsNull() {
        assertNull(new CommandRegistry().find("nope"));
    }

    @Test
    void loadFromMissingDirectoryIsSilent() {
        CommandRegistry registry = new CommandRegistry();
        registry.load(FileMountSource.of("no/such/dir"), "");
        assertTrue(registry.names().isEmpty());
    }

    @Test
    void loadRegistersNestedNamespaceCommands(@TempDir Path base) throws IOException {
        Files.write(base.resolve("commit.md"),
                ("---\ndescription: Create a git commit\n---\n帮我把改动提交：$ARGUMENTS").getBytes(StandardCharsets.UTF_8));
        Files.createDirectories(base.resolve("deploy"));
        Files.write(base.resolve("deploy/staging.md"),
                ("---\ndescription: 部署到预发\nmodel: fake\n---\n部署 $1 到 $2").getBytes(StandardCharsets.UTF_8));
        // 非 md 文件被忽略
        Files.write(base.resolve("notes.txt"), "x".getBytes(StandardCharsets.UTF_8));

        CommandRegistry registry = new CommandRegistry();
        MarkdownCommandLoader.loadFrom(FileMountSource.of(base), "", registry);

        assertEquals(java.util.Arrays.asList("commit", "deploy:staging"), registry.names());

        Command commit = registry.find("commit");
        assertTrue(commit instanceof MarkdownCommand);
        assertEquals("Create a git commit", commit.description());
        // 空参数列表：$ARGUMENTS 替换为空串（args.isEmpty() 分支）
        assertEquals("帮我把改动提交：", ((MarkdownCommand) commit).getResolvedPrompt(new ArrayList<>()));

        // model 元数据透传到命令
        assertEquals("fake", registry.find("deploy:staging").model());
    }

    @Test
    void markdownCommandResolvesPositionalAndArgumentsVariables() {
        MarkdownCommand command = new MarkdownCommand("demo",
                "位置：$1 和 $2；全部：$ARGUMENTS", null, null);

        assertEquals("位置：a 和 b；全部：a b",
                command.getResolvedPrompt(java.util.Arrays.asList("a", "b")));
        // 参数缺失时不替换越界占位符
        assertEquals("位置：a 和 $2；全部：a",
                command.getResolvedPrompt(java.util.Arrays.asList("a")));
        // null 参数列表安全
        assertEquals("位置：$1 和 $2；全部：", command.getResolvedPrompt(null));
    }

    @Test
    void markdownCommandDefaultsDescriptionAndEmptyExamples() {
        MarkdownCommand command = new MarkdownCommand("x", "tpl", null, null);
        assertEquals("Custom command: x", command.description());
        assertEquals(0, command.examples().length);
    }

    @Test
    void markdownCommandExecuteDeclaresTaskIntent() throws Exception {
        MarkdownCommand command = new MarkdownCommand("x", "模板 $ARGUMENTS", null, "m1");
        StubCtx ctx = new StubCtx(java.util.Arrays.asList("参数"));
        command.execute(null, ctx, InputOrigin.NONE);

        assertNotNull(ctx.intent);
        assertEquals("模板 参数", ctx.intent.getPrompt());
        assertEquals("m1", ctx.intent.getModel());
    }

    static class StubCtx implements CommandContext {
        final List<String> args;
        org.noear.solon.ai.harness.command.AgentTaskIntent intent;

        StubCtx(List<String> args) {
            this.args = args;
        }

        @Override
        public org.noear.solon.ai.agent.AgentSession getSession() {
            return null;
        }

        @Override
        public String getRawInput() {
            return null;
        }

        @Override
        public String getCommandName() {
            return "x";
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
            this.intent = new org.noear.solon.ai.harness.command.AgentTaskIntent(prompt, model);
        }
    }
}
