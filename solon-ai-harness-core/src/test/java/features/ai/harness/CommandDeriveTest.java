package features.ai.harness;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.react.intercept.HITLAction;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.chat.ChatConfig;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.ai.harness.HarnessExtension;
import org.noear.solon.ai.harness.command.AbsCommand;
import org.noear.solon.ai.harness.command.CommandDispatcher;
import org.noear.solon.ai.harness.command.CommandInput;
import org.noear.solon.ai.harness.command.CommandOutput;
import org.noear.solon.ai.harness.command.MarkdownCommand;
import org.noear.solon.ai.harness.command.ParsedCommand;
import org.noear.solon.ai.harness.command.builtin.HelpCommand;
import org.noear.solon.ai.harness.command.builtin.ModelCommand;
import org.noear.solon.ai.harness.command.builtin.RewindCommand;
import org.noear.solon.ai.harness.input.BusyPolicy;
import org.noear.solon.ai.harness.input.HarnessInput;
import org.noear.solon.ai.harness.input.InputOrigin;
import org.noear.solon.ai.harness.input.InputOutcome;
import org.noear.solon.ai.harness.scope.InputScope;
import org.noear.solon.lang.Nullable;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 命令二段任务的「同一轮」口径（HarnessInput#derive）与需要会话的命令（Command#requiresSession）。
 */
class CommandDeriveTest {
    @TempDir
    Path workspace;

    /** 记录引擎看到的每一条输入（onInput 在受理后、命令分发前调用） */
    static final class Recorder implements HarnessExtension {
        final List<HarnessInput> inputs = new ArrayList<>();

        @Override
        public void onInput(HarnessEngine engine, HarnessInput input) {
            inputs.add(input);
        }

        /** 二段任务的输入（命令派生的那条不再识别命令） */
        HarnessInput nested() {
            for (HarnessInput in : inputs) {
                if (in.isCommandAware() == false) {
                    return in;
                }
            }
            return null;
        }
    }

    static final class TaskCommand extends AbsCommand {
        final String model;
        int runs;

        TaskCommand(String model) {
            this.model = model;
        }

        @Override
        public String name() {
            return "task";
        }

        @Override
        public String description(@Nullable Locale locale) {
            return "test";
        }

        @Override
        public String[] examples(@Nullable Locale locale) {
            return new String[0];
        }

        @Override
        public void execute(HarnessEngine engine, CommandInput input, CommandOutput output) {
            runs++;
            output.requestAgentTask("go", model);
        }
    }

    private HarnessEngine engine(Recorder recorder, TaskCommand command) {
        ChatConfig model = new ChatConfig();
        model.setName("fake");
        model.setModel("fake-model");
        model.setApiUrl("http://localhost:1/v1");
        model.setApiKey("test");

        HarnessEngine engine = HarnessEngine.of(workspace.toString(), workspace.resolve("home").toString())
                .sessionProvider((id, c) -> InMemoryAgentSession.of(id))
                .modelAdd(model)
                .defaultModel("fake")
                .extensionAdd(recorder)
                .build();
        engine.getCommandRegistry().register(command);
        return engine;
    }

    // ==================== HarnessInput#derive ====================

    @Test
    void deriveCarriesTurnAttributesAndResetsMessageAttributes() {
        AgentSession session = InMemoryAgentSession.of("s1");
        InputOrigin origin = InputOrigin.of("Feishu", "u1", "c1", "m1");
        InputScope scope = InputScope.builder("alice").build();
        HarnessInput src = HarnessInput.of(session, "/x")
                .systemPrompt("sp").locale(Locale.ENGLISH)
                .selectedModel("m").selectedAgent("coder")
                .selectedSkill("s1").selectedMcp("mcp1")
                .scope(scope).origin(origin).terminal(true).principal("alice")
                .attr("effort", "high")
                .reference(".uploads/a.txt")
                .hitl(HITLAction.APPROVE, "call-1").hitlOutput("out")
                .resume(true).explicitAgent("ex").trimContent(true).agentAware(false)
                .whenBusy(BusyPolicy.STEER)
                .commandOutputFactory(in -> null);

        HarnessInput d = src.derive("next");

        //带上本轮属性
        assertSame(session, d.getSession());
        assertEquals("next", d.getText());
        assertEquals("sp", d.getSystemPrompt());
        assertEquals(Locale.ENGLISH, d.getLocale());
        assertEquals("m", d.getSelectedModel());
        assertEquals("coder", d.getSelectedAgent());
        assertEquals(Collections.singletonList("s1"), d.getSelectedSkills());
        assertEquals(Collections.singletonList("mcp1"), d.getSelectedMcps());
        assertSame(scope, d.getScope());
        assertTrue(d.isScopeExplicit(), "显式作用域保持显式，嵌套受理不重新解析");
        assertSame(origin, d.getOrigin());
        assertTrue(d.isTerminal());
        assertEquals("alice", d.getPrincipal());
        assertEquals("high", d.getAttr("effort"));

        //丢掉按条消息的属性
        assertFalse(d.hasAttachments());
        assertFalse(d.isHitlDecision());
        assertNull(d.getHitlCallId());
        assertNull(d.getHitlOutput());
        assertFalse(d.isResume());
        assertNull(d.getExplicitAgent());
        assertFalse(d.isTrimContent());
        assertTrue(d.isAgentAware());
        assertEquals(BusyPolicy.QUEUE, d.getWhenBusy());
        assertNull(d.getCommandOutputFactory());
        assertFalse(d.isProgram());
        assertFalse(d.isCommandAware(), "派生文本不再识别命令");

        //属性是副本，互不影响
        d.attr("effort", "low");
        assertEquals("high", src.getAttr("effort"));
    }

    @Test
    void deriveKeepsResolvedAndRestoredScopeFlags() {
        InputScope scope = InputScope.builder("alice").build();

        HarnessInput resolved = HarnessInput.of(null, "x").resolvedScope(scope).derive("y");
        assertFalse(resolved.isScopeExplicit(), "解析所得的作用域派生后仍按解析处理（重新解析）");
        assertSame(scope, resolved.getScope());

        HarnessInput restored = HarnessInput.of(null, "x").restoredScope(scope).derive("y");
        assertTrue(restored.isScopeRestored());
        assertTrue(restored.isScopeExplicit());
    }

    // ==================== 二段任务 ====================

    @Test
    void nestedTaskKeepsExplicitScopeOriginAndAttrs() {
        Recorder recorder = new Recorder();
        HarnessEngine engine = engine(recorder, new TaskCommand(null));
        AgentSession session = InMemoryAgentSession.of("s1");
        InputOrigin origin = InputOrigin.of("Feishu", "u1", "c1", "m1");
        InputScope scope = InputScope.builder("alice").commands("task").build();

        InputOutcome outcome = engine.handle(HarnessInput.of(session, "/task")
                .origin(origin).scope(scope).attr("effort", "high").locale(Locale.ENGLISH));

        assertEquals(InputOutcome.Kind.TASK, outcome.getKind());
        HarnessInput nested = recorder.nested();
        assertNotNull(nested, "命令声明任务后须经引擎二段受理");
        assertSame(scope, nested.getScope(), "宿主显式收窄的作用域不能在二段任务里丢失");
        assertTrue(nested.isScopeExplicit());
        assertSame(origin, nested.getOrigin(), "来源随二段任务走（回复路由、消息元数据）");
        assertEquals("high", nested.getAttr("effort"), "宿主域属性（推理强度等）随二段任务走");
        assertEquals(Locale.ENGLISH, nested.getLocale());
        assertEquals("go", nested.getText());
    }

    @Test
    void nestedTaskModelFallsBackToTurnSelection() {
        Recorder recorder = new Recorder();
        HarnessEngine engine = engine(recorder, new TaskCommand(null));

        engine.handle(HarnessInput.of(InMemoryAgentSession.of("s1"), "/task").selectedModel("fake"));
        assertEquals("fake", recorder.nested().getSelectedModel(), "命令未声明模型时沿用本轮的模型选择");
    }

    @Test
    void nestedTaskModelPrefersCommandDeclaration() {
        Recorder recorder = new Recorder();
        HarnessEngine engine = engine(recorder, new TaskCommand("declared"));

        engine.handle(HarnessInput.of(InMemoryAgentSession.of("s1"), "/task").selectedModel("fake"));
        assertEquals("declared", recorder.nested().getSelectedModel(), "命令声明的模型优先");
    }

    @Test
    void nestedTaskDoesNotMutateOriginalInput() {
        Recorder recorder = new Recorder();
        HarnessEngine engine = engine(recorder, new TaskCommand(null));
        HarnessInput original = HarnessInput.of(InMemoryAgentSession.of("s1"), "/task").attr("k", "v");

        engine.handle(original);

        assertNotSame(original, recorder.nested());
        assertTrue(original.isCommandAware());
        assertEquals("/task", original.getText());
    }

    // ==================== requiresSession ====================

    @Test
    void sessionRequiredCommandIsRejectedWithoutSession() throws Exception {
        TaskCommand command = new TaskCommand(null);
        HarnessEngine engine = engine(new Recorder(), command);

        InputOutcome outcome = CommandDispatcher.dispatch(engine, HarnessInput.of(null, "/task").locale(Locale.CHINESE),
                ParsedCommand.parse("/task"));

        assertNotNull(outcome);
        assertTrue(outcome.isCommand());
        assertEquals(InputOutcome.Kind.REPLY, outcome.getKind());
        assertEquals(CommandDispatcher.sessionRequiredHint("task", Locale.CHINESE), outcome.getCommandOutput());
        assertTrue(outcome.getCommandOutput().startsWith("/task "));
        assertEquals(0, command.runs, "需要会话的命令在无会话时不得执行");
    }

    @Test
    void helpRunsWithoutSession() {
        HarnessEngine engine = engine(new Recorder(), new TaskCommand(null));
        //内置命令由宿主注册
        engine.getCommandRegistry().register(new HelpCommand());

        InputOutcome outcome = engine.handle(HarnessInput.of(null, "/help"));

        assertEquals(InputOutcome.Kind.REPLY, outcome.getKind());
        assertTrue(outcome.getCommandOutput().contains("/task"), outcome.getCommandOutput());
    }

    @Test
    void builtinsDeclareSessionRequirement() {
        assertFalse(new HelpCommand().requiresSession());
        assertFalse(new MarkdownCommand("x", "t", null, null).requiresSession());
        assertTrue(new RewindCommand().requiresSession());
        assertTrue(new ModelCommand().requiresSession());
    }
}
