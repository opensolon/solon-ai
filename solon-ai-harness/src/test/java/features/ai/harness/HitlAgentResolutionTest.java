package features.ai.harness;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.agent.react.intercept.HITLAction;
import org.noear.solon.ai.chat.ChatConfig;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.ai.harness.input.HarnessInput;
import org.noear.solon.ai.harness.input.InputOutcome;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * HITL 决策结果中的生效子代理解析。
 *
 * <p>Web 端审批请求通常不携带 selectedAgent（前端只回 callId + action），
 * 若 {@link InputOutcome#getAgentName()} 只回显入参，宿主续流时会错误回落主代理：
 * trace 按代理名分 key，取错代理取不到挂起的 tool_calls，表现为「点了审批但续跑空转」。</p>
 */
class HitlAgentResolutionTest {
    @TempDir
    Path workspace;

    private HarnessEngine engine() {
        ChatConfig model = new ChatConfig();
        model.setName("fake");
        model.setModel("fake-model");
        model.setApiUrl("http://localhost:1/v1");
        model.setApiKey("test");

        return HarnessEngine.of(workspace.toString(), workspace.resolve("home").toString())
                .sessionProvider(InMemoryAgentSession::of)
                .modelAdd(model)
                .defaultModel("fake")
                .build();
    }

    @Test
    void hitlFallsBackToSessionSelectedAgent() {
        HarnessEngine engine = engine();
        AgentSession session = InMemoryAgentSession.of();
        // 宿主在首轮已把「实际生效代理」写入会话上下文
        session.getContext().put(HarnessEngine.CTX_AGENT_SELECTED, "coder");

        InputOutcome outcome = engine.handle(HarnessInput.of(session, null)
                .hitl(HITLAction.APPROVE, null));

        assertEquals(InputOutcome.Kind.HITL, outcome.getKind());
        assertEquals("coder", outcome.getAgentName());
    }

    @Test
    void hitlPrefersExplicitAgent() {
        HarnessEngine engine = engine();
        AgentSession session = InMemoryAgentSession.of();
        session.getContext().put(HarnessEngine.CTX_AGENT_SELECTED, "coder");

        InputOutcome outcome = engine.handle(HarnessInput.of(session, null)
                .selectedAgent("reviewer")
                .hitl(HITLAction.APPROVE, null));

        assertEquals("reviewer", outcome.getAgentName());
    }

    @Test
    void hitlWithoutAnyAgentStaysNull() {
        HarnessEngine engine = engine();
        AgentSession session = InMemoryAgentSession.of();

        InputOutcome outcome = engine.handle(HarnessInput.of(session, null)
                .hitl(HITLAction.APPROVE, null));

        assertNull(outcome.getAgentName());
    }
}
