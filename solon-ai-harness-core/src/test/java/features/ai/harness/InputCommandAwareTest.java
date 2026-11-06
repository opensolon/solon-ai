package features.ai.harness;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.agent.react.intercept.HITL;
import org.noear.solon.ai.agent.react.intercept.HITLAction;
import org.noear.solon.ai.agent.react.intercept.HITLTask;
import org.noear.solon.ai.chat.ChatConfig;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.ai.harness.command.Command;
import org.noear.solon.ai.harness.command.CommandContext;
import org.noear.solon.ai.harness.input.HarnessInput;
import org.noear.solon.ai.harness.input.InputOrigin;
import org.noear.solon.ai.harness.input.InputOutcome;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * commandAware 开关：决定「以命令前缀开头的文本」是否被当作命令执行
 */
class InputCommandAwareTest {
    @TempDir
    Path workspace;

    private HarnessEngine engine() {
        ChatConfig model = new ChatConfig();
        model.setName("fake");
        model.setModel("fake-model");
        model.setApiUrl("http://localhost:1/v1");
        model.setApiKey("test");

        HarnessEngine engine = HarnessEngine.of(workspace.toString(), workspace.resolve("home").toString())
                .sessionProvider((id, c) -> InMemoryAgentSession.of(id))
                .modelAdd(model)
                .defaultModel("fake")
                .build();

        //harness 不内置命令，内置命令由宿主注册；这里注册一条最简命令用于验证分发链路
        engine.getCommandRegistry().register(new Command() {
            @Override
            public String name() {
                return "ping";
            }

            @Override
            public String description() {
                return "ping test";
            }

            @Override
            public String[] examples() {
                return new String[]{"ping"};
            }

            @Override
            public void execute(HarnessEngine engine, CommandContext ctx, InputOrigin origin) {
                ctx.println("pong");
            }
        });

        return engine;
    }

    @Test
    void commandPrefixTreatedAsCommandByDefault() {
        HarnessEngine engine = engine();
        AgentSession session = InMemoryAgentSession.of();

        InputOutcome outcome = engine.handle(HarnessInput.of(session, "/ping"));

        assertEquals(InputOutcome.Kind.REPLY, outcome.getKind());
        assertEquals("ping", outcome.getCommandName());
        assertEquals("pong\n", outcome.getCommandOutput());
    }

    @Test
    void commandAwareOffSkipsDispatchAndKeepsTextIntact() {
        HarnessEngine engine = engine();
        AgentSession session = InMemoryAgentSession.of();

        InputOutcome outcome = engine.handle(HarnessInput.of(session, "/ping").commandAware(false));

        //定时任务/排队输入：文本以 / 开头也按普通消息处理，且原文不被剥离
        assertFalse(outcome.isCommand());
        assertEquals(InputOutcome.Kind.TASK, outcome.getKind());
        //原文不被剥离：仍以 / 开头（从提示词里取，InputOutcome 不再单独暴露文本）
        assertTrue(outcome.getPrompt().getMessages().get(0).getContent().startsWith("/ping"));
        assertNotNull(outcome.getRequest());
    }

//    @Test
//    void dispatchCommandStillDetectsCommandForQuickCheck() {
//        HarnessEngine engine = engine();
//        AgentSession session = InMemoryAgentSession.of();
//
//        //宿主「是不是命令」的快速判断与 commandAware 无关：它本来只回答命令问题
//        assertNotNull(engine.dispatchCommand(session, HarnessInput.of(session, "/ping"), null));
//    }

    @Test
    void hitlDecisionWithoutTextIsNotIgnored() {
        HarnessEngine engine = engine();
        AgentSession session = InMemoryAgentSession.of();

        //审批请求通常不带文本：HITL 分支必须先于空输入判空，否则决策被静默丢弃
        InputOutcome outcome = engine.handle(HarnessInput.of(session, null).hitl(HITLAction.APPROVE, "call-1"));

        assertEquals(InputOutcome.Kind.HITL, outcome.getKind());
    }

    @Test
    void hitlDecisionWithOutputRewriteIsStillHitl() {
        HarnessEngine engine = engine();
        AgentSession session = InMemoryAgentSession.of();

        InputOutcome outcome = engine.handle(HarnessInput.of(session, null)
                .hitl(HITLAction.APPROVE, "call-2")
                .hitlOutput("工具结果改写"));

        assertEquals(InputOutcome.Kind.HITL, outcome.getKind());
    }

    @Test
    void hitlDecisionWithResumeReturnsTaskInOneCall() {
        HarnessEngine engine = engine();
        AgentSession session = InMemoryAgentSession.of();

        //单卡同步审批：决策写入与续流装配合为一次调用，且只走 handle 一个入口。
        //必须有真实挂起任务：无挂起的审批视为过期卡，引擎拒绝续流（见 HitlResumeSelfCheckTest）
        HITLTask task = new HITLTask("call-3", "bash",
                java.util.Collections.singletonMap("command", "ls"), "确认执行");
        session.getContext().put(HITL.PENDING_TASKS, java.util.Collections.singletonList(task));
        session.getContext().put(HITL.LAST_INTERVENED, task);

        InputOutcome outcome = engine.handle(HarnessInput.of(session, null)
                .hitl(HITLAction.APPROVE, "call-3")
                .resume(true)
                .selectedAgent("coder"));

        assertEquals(InputOutcome.Kind.TASK, outcome.getKind());
        assertNotNull(outcome.getRequest());
        assertEquals("coder", outcome.getAgentName());
    }

    @Test
    void resumeAloneStillReturnsTask() {
        HarnessEngine engine = engine();
        AgentSession session = InMemoryAgentSession.of();

        //宿主已批量写完全部决策后统一恢复：不传 hitl，只传 resume
        InputOutcome outcome = engine.handle(HarnessInput.of(session, null).resume(true));

        assertEquals(InputOutcome.Kind.TASK, outcome.getKind());
        assertNotNull(outcome.getRequest());
    }
}
