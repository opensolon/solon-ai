package org.noear.solon.ai.harness.command;

import org.junit.jupiter.api.Test;
import org.noear.solon.ai.harness.input.HarnessInput;
import org.noear.solon.ai.harness.input.InputOutcome;
import org.junit.jupiter.api.io.TempDir;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.lang.Nullable;

import java.nio.file.Path;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 命令停用语义：停用不能等同「未注册」，否则 "/clear" 原文会被当作用户消息发给模型。
 */
class CommandDispatcherDisabledTest {
    @TempDir
    Path workspace;

    private static final CommandOutputFactory BUFFERED = in -> new BufferedCommandOutput();

    private static HarnessInput input(String raw) {
        return HarnessInput.of(InMemoryAgentSession.of(), raw).terminal(true).commandOutputFactory(BUFFERED);
    }

    private HarnessEngine newEngine() {
        return HarnessEngine.of(workspace.toString(), workspace.resolve("home").toString())
                .sessionProvider((id, c) -> InMemoryAgentSession.of(id))
                .build();
    }

    private static final class ProbeCommand extends AbsCommand {
        @Override
        public String name() {
            return "probe";
        }

        @Override
        public String description(@Nullable Locale locale) {
            return "测试命令";
        }

        @Override
        public String[] examples(@Nullable Locale locale) {
            return new String[]{"/probe"};
        }

        @Override
        public void execute(HarnessEngine engine, CommandInput input, CommandOutput output) {
            output.println("executed");
        }
    }

    @Test
    void disabledCommandYieldsUnavailableInsteadOfFallingThrough() throws Exception {
        HarnessEngine engine = newEngine();
        ProbeCommand command = new ProbeCommand();
        engine.getCommandRegistry().register(command);
        command.setEnabled(false);

        InputOutcome outcome = CommandDispatcher.dispatch(engine, input("/probe"), ParsedCommand.parse("/probe"));

        assertNotNull(outcome, "停用的命令仍是命令，不能返回 null（否则原文会进入模型）");
        assertEquals(InputOutcome.Kind.REPLY, outcome.getKind());
        assertTrue(outcome.isCommand());
        assertTrue(outcome.getCommandOutput().contains("已停用"), "须给出「已停用」回执");
    }

    @Test
    void unregisteredCommandStillFallsThroughAsPlainInput() throws Exception {
        HarnessEngine engine = newEngine();

        InputOutcome outcome = CommandDispatcher.dispatch(engine, input("/nope"), ParsedCommand.parse("/nope"));

        assertNull(outcome, "未注册仍按普通输入处理");
    }

    @Test
    void enabledCommandExecutesNormally() throws Exception {
        HarnessEngine engine = newEngine();
        engine.getCommandRegistry().register(new ProbeCommand());

        InputOutcome outcome = CommandDispatcher.dispatch(engine, input("/probe"), ParsedCommand.parse("/probe"));

        assertNotNull(outcome);
        assertEquals("executed\n", outcome.getCommandOutput());
    }
}
