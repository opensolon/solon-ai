package org.noear.solon.ai.harness.command.builtin;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.chat.ChatConfig;
import org.noear.solon.ai.chat.ChatConfigReadonly;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.ai.harness.input.HarnessInput;
import org.noear.solon.ai.harness.input.InputOutcome;
import org.noear.solon.ai.harness.session.interrupt.InterruptCommand;
import org.noear.solon.ai.harness.util.TraceKeys;

import java.nio.file.Path;

import demo.ai.harness.LlmUtil;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 内置命令引擎级系统测试：完整引擎构建 + 全部 7 个核心内置命令注册 + engine.handle 全链路。
 * <p>
 * 命令层行为（execute 语义）已由同包各 CommandTest 单独覆盖；本类验证的是
 * 「命令注册 → ParsedCommand 解析 → CommandDispatcher 分发 → 回执/意图二段装配」
 * 的引擎集成链路——此前该链路只用匿名命令测过，内置命令与真实引擎的装配从未验证。
 * <p>
 * 模型用 LlmUtil.getChatModel().getConfig() 的真实配置：命令链路断言的是装配结果
 * （Kind/回执/request），从不调流，无网络开销。
 */
class BuiltinCommandsSystemTest {
    @TempDir
    Path workspace;

    /** 宿主式构建：注册全部核心内置命令（框架自身不注册，宿主按需装配——与真实宿主一致）。
     *  /queue、/steer、/goal 随插件模块独立，其引擎链路测试在各插件模块内（XxxCommandEngineTest）。 */
    private HarnessEngine engineWithBuiltins() {
        HarnessEngine engine = HarnessEngine.of(workspace.toString(), workspace.resolve("home").toString())
                .sessionProvider(InMemoryAgentSession::of)
                .modelAdd(realModel())
                .defaultModel(realModel().getModel())
                .build();

        engine.getCommandRegistry().register(new HelpCommand());
        engine.getCommandRegistry().register(new ContinueCommand());
        engine.getCommandRegistry().register(new RerunCommand());
        engine.getCommandRegistry().register(new ClearCommand());
        engine.getCommandRegistry().register(new RewindCommand());
        engine.getCommandRegistry().register(new InterruptCommand());
        engine.getCommandRegistry().register(new ModelCommand());
        return engine;
    }

    /** 模型统一取 LlmUtil 真实配置（不造假）：getConfig() 返回只读视图，拷贝为可注册的 ChatConfig */
    static ChatConfig realModel() {
        ChatConfigReadonly ro = LlmUtil.getChatModel().getConfig();
        ChatConfig config = new ChatConfig();
        config.setName(ro.getName());
        config.setModel(ro.getModel());
        config.setApiUrl(ro.getApiUrl());
        config.setApiKey(ro.getApiKey());
        config.setStandard(ro.getStandard());
        return config;
    }

    private static InputOutcome handle(HarnessEngine engine, AgentSession session, String text) {
        return engine.handle(HarnessInput.of(session, text));
    }

    // ---------- /help ----------

    @Test
    void helpListsRegisteredCommandsThroughEngine() {
        HarnessEngine engine = engineWithBuiltins();
        AgentSession session = InMemoryAgentSession.of();

        InputOutcome outcome = handle(engine, session, "/help");

        assertEquals(InputOutcome.Kind.REPLY, outcome.getKind());
        assertEquals("help", outcome.getCommandName());
        assertTrue(outcome.getCommandOutput().contains("/continue"));
        assertTrue(outcome.getCommandOutput().contains("/interrupt"));
        assertTrue(outcome.getCommandOutput().contains("/rewind"));
        assertNull(outcome.getRequest());
    }

    @Test
    void helpTopicShowsUsage() {
        HarnessEngine engine = engineWithBuiltins();
        InputOutcome outcome = handle(engine, InMemoryAgentSession.of(), "/help rewind");

        assertEquals(InputOutcome.Kind.REPLY, outcome.getKind());
        assertTrue(outcome.getCommandOutput().contains("/rewind <n>"));
    }

    // ---------- /model ----------

    @Test
    void modelDefaultsToListsModels() {
        HarnessEngine engine = engineWithBuiltins();
        AgentSession session = InMemoryAgentSession.of();

        InputOutcome outcome = handle(engine, session, "/model");

        assertEquals(InputOutcome.Kind.REPLY, outcome.getKind());
        // getNameOrModel()：配置未设 name 时返回 model 名；当前模型带 active 标记
        assertTrue(outcome.getCommandOutput().contains(realModel().getModel()));
        assertTrue(outcome.getCommandOutput().contains("(active)"));
        // 会话级选择未被写入
        assertNull(session.getContext().getAs(HarnessEngine.CTX_MODEL_SELECTED));
    }

    @Test
    void modelSwitchWritesSessionSelectionAndUnknownRejected() {
        HarnessEngine engine = engineWithBuiltins();
        AgentSession session = InMemoryAgentSession.of();

        String modelName = realModel().getModel();
        InputOutcome ok = handle(engine, session, "/model " + modelName);
        assertEquals(InputOutcome.Kind.REPLY, ok.getKind());
        assertEquals(modelName, session.getContext().getAs(HarnessEngine.CTX_MODEL_SELECTED));

        InputOutcome bad = handle(engine, session, "/model no-such-model");
        assertTrue(bad.getCommandOutput().contains("Model not found"));
        // 未被改写
        assertEquals(modelName, session.getContext().getAs(HarnessEngine.CTX_MODEL_SELECTED));
    }

    // ---------- /rewind、/clear：会话操作命令 ----------

    @Test
    void rewindThroughEngineRemovesMessages() {
        HarnessEngine engine = engineWithBuiltins();
        AgentSession session = InMemoryAgentSession.of();
        session.addMessage(ChatMessage.ofUser("q1"));
        session.addMessage(ChatMessage.ofAssistant("a1"));
        session.addMessage(ChatMessage.ofUser("q2"));

        InputOutcome outcome = handle(engine, session, "/rewind 2");

        assertEquals(InputOutcome.Kind.REPLY, outcome.getKind());
        assertEquals(1, session.getMessages().size());
        assertTrue(outcome.getCommandOutput().contains("已回退 2 条记录"));
    }

    @Test
    void clearThroughEngineEmptiesSession() {
        HarnessEngine engine = engineWithBuiltins();
        AgentSession session = InMemoryAgentSession.of();
        session.addMessage(ChatMessage.ofUser("q1"));

        InputOutcome outcome = handle(engine, session, "/clear");

        assertEquals(InputOutcome.Kind.REPLY, outcome.getKind());
        assertTrue(session.getMessages().isEmpty());
        // 纯会话操作命令无回执内容（BufferedCommandContext 收集到空）
        assertNotNull(outcome);
    }

    // ---------- /continue：意图声明 → 二段装配 ----------

    @Test
    void continueWithoutTraceReturnsReplyWithHint() {
        HarnessEngine engine = engineWithBuiltins();
        AgentSession session = InMemoryAgentSession.of();

        InputOutcome outcome = handle(engine, session, "/continue");

        // 前置拦截命中：回执提示、无任务装配
        assertEquals(InputOutcome.Kind.REPLY, outcome.getKind());
        assertTrue(outcome.getCommandOutput().contains("没有可继续的任务"));
        assertNull(outcome.getRequest());
    }

    @Test
    void continueWithEndTraceAssemblesResumeTask() {
        HarnessEngine engine = engineWithBuiltins();
        AgentSession session = InMemoryAgentSession.of();

        org.noear.solon.ai.agent.react.ReActTrace trace =
                ContinueCommandTest.newTrace(session);
        ContinueCommandTest.setOriginalPrompt(trace, org.noear.solon.ai.chat.prompt.Prompt.of("跑完的任务"));
        trace.setRoute(org.noear.solon.ai.agent.react.ReActAgent.ID_END);
        session.getContext().put(TraceKeys.TRACE_KEY_MAIN, trace);

        InputOutcome outcome = handle(engine, session, "/continue");

        // 命令声明续流意图 → 分发器二段装配 → TASK + 回执并存
        assertEquals(InputOutcome.Kind.TASK, outcome.getKind());
        assertEquals("continue", outcome.getCommandName());
        assertNotNull(outcome.getRequest());
        // 续流任务不带新 prompt（命令意图 prompt 为空）
        assertNull(outcome.getPrompt());
    }

    // ---------- /rerun：意图声明 → 二段装配 ----------

    @Test
    void rerunAssemblesTaskWithLastUserMessage() {
        HarnessEngine engine = engineWithBuiltins();
        AgentSession session = InMemoryAgentSession.of();
        session.addMessage(ChatMessage.ofUser("上一次的问题"));
        session.addMessage(ChatMessage.ofAssistant("上一次的回答"));

        InputOutcome outcome = handle(engine, session, "/rerun");

        assertEquals(InputOutcome.Kind.TASK, outcome.getKind());
        assertEquals("rerun", outcome.getCommandName());
        assertNotNull(outcome.getRequest());
        assertEquals("上一次的问题", outcome.getPrompt().getUserContent());
        // 会话里的 User 已被弹出
        assertTrue(session.getMessages().isEmpty());
    }

    // ---------- /interrupt、/queue、/steer：运行态命令 ----------

    @Test
    void interruptWithoutTaskReturnsHintReply() {
        HarnessEngine engine = engineWithBuiltins();
        InputOutcome outcome = handle(engine, InMemoryAgentSession.of(), "/interrupt");

        assertEquals(InputOutcome.Kind.REPLY, outcome.getKind());
        assertTrue(outcome.getCommandOutput().contains("当前没有正在执行的任务"));
    }

    // ---------- terminalOnly 契约经引擎 ----------

    @Test
    void helpInNonTerminalChannelHidesTerminalOnlyCommands() {
        // HarnessInput.isTerminal 默认 false（模拟 Web/IM 渠道）：
        // 注册一个 terminalOnly 命令，验证引擎链路对渠道能力的裁剪
        HarnessEngine engine = engineWithBuiltins();
        engine.getCommandRegistry().register(new TerminalOnlyCommand());

        InputOutcome outcome = handle(engine, InMemoryAgentSession.of(), "/help");

        // Web/IM 视角：terminalOnly 命令不在清单
        assertFalse(outcome.getCommandOutput().contains("/term-only"));
        // 但详情查询给出明确不可用提示
        InputOutcome topic = handle(engine, InMemoryAgentSession.of(), "/help term-only");
        assertTrue(topic.getCommandOutput().contains("仅支持 CLI"));
    }

    /** 测试桩：仅在终端渠道可见的命令 */
    static class TerminalOnlyCommand implements org.noear.solon.ai.harness.command.Command {
        @Override
        public String name() {
            return "term-only";
        }

        @Override
        public String description() {
            return "仅终端";
        }

        @Override
        public String[] examples() {
            return new String[]{"/term-only"};
        }

        @Override
        public boolean terminalOnly() {
            return true;
        }

        @Override
        public void execute(HarnessEngine engine, org.noear.solon.ai.harness.command.CommandContext ctx,
                            org.noear.solon.ai.harness.input.InputOrigin origin) {
            ctx.println("should not run");
        }
    }
}
