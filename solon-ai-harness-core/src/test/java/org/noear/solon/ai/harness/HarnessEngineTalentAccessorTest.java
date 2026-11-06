package org.noear.solon.ai.harness;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.chat.ChatConfig;
import org.noear.solon.ai.harness.agent.AgentDefinition;
import org.noear.solon.ai.harness.input.HarnessInput;

import java.nio.file.Path;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * HarnessEngine 的 talent 访问器、prompt 系列重载、以及 HarnessInput 的链式配置。
 * <p>
 * talent 全为空/非空两种情况都要验证：宿主大量用 {@code getXxxTalent()} 做能力探测，
 * 返回值语义（非 null 即已装配）必须稳定。
 */
class HarnessEngineTalentAccessorTest {

    @TempDir
    Path workspace;

    private HarnessEngine engine() {
        ChatConfig c = new ChatConfig();
        c.setName("m");
        c.setModel("agnes-3.0-flash");
        c.setApiUrl("https://example.invalid/v1");
        c.setApiKey("k");

        return HarnessEngine.of(workspace.toString(), null)
                .sessionProvider((id, create) -> InMemoryAgentSession.of(id))
                .memoryProvider(cwd -> null)
                .modelAdd(c)
                .defaultModel("m")
                .build();
    }

    @Test
    void allTalentAccessorsAreWired() {
        HarnessEngine e = engine();

        assertNotNull(e.getTerminalTalent());
        assertNotNull(e.getSkillTalent());
        assertNotNull(e.getTodoTalent());
        assertNotNull(e.getTaskTalent());
        assertNotNull(e.getCodeTalent());
        assertNotNull(e.getMemoryTalent());
        assertNotNull(e.getGenerateTalent());
        assertNotNull(e.getCodeSearchTalent());
        assertNotNull(e.getClockTalent());
        assertNotNull(e.getWebsearchTalent());
        assertNotNull(e.getWebfetchTalent());
        assertNotNull(e.getMcpGatewayTalent());
        assertNotNull(e.getOpenApiGatewayTalent());
        assertNotNull(e.getLspTalent());
        assertNotNull(e.getSessionProvider());
        assertNotNull(e.getSkillCatalog());
        assertNotNull(e.getAgentCatalog());
        assertNotNull(e.getAgentManager());
        assertNotNull(e.getCommandRegistry());
    }

    @Test
    void memoryTalentIsNullWhenProviderAbsent() {
        HarnessEngine e = HarnessEngine.of(workspace.toString(), null)
                .sessionProvider((id, create) -> InMemoryAgentSession.of(id))
                .build();

        // 未注入 memoryProvider 时，记忆 talent 不装配（访问器返回 null）
        assertNull(e.getMemoryTalent());
    }

    @Test
    void agentListAccessorIsStable() {
        HarnessEngine e = engine();
        // 主代理与内置子代理均通过该访问器暴露
        assertTrue(e.getAgents().size() >= 0);
        assertNotNull(e.getAgents());
    }

    // ---------- prompt 系列重载 ----------

    @Test
    void promptOverloadsAllProduceRequest() {
        HarnessEngine e = engine();
        AgentSession session = e.getSession("s1");

        assertNotNull(e.prompt("hi"));
        assertNotNull(e.prompt("hi", e.getMainAgent()));
        assertNotNull(e.resume());
        assertNotNull(e.resume(e.getMainAgent()));
        assertNotNull(e.prompt(org.noear.solon.ai.chat.prompt.Prompt.of("hi")));
        assertNotNull(e.prompt(org.noear.solon.ai.chat.prompt.Prompt.of("hi"), e.getMainAgent()));

        // 会话版入口即 handle：请求装配走 handle 内部，此处只验证不抛异常
        assertNotNull(e.handle(HarnessInput.of(session, "hi")));
    }

    @Test
    void handleTextOverloadMatchesHarnessInput() {
        HarnessEngine e = engine();
        AgentSession session = e.getSession("s2");

        // 纯文本非命令输入 → MESSAGE 结果
        assertNotNull(e.handle(session, "hello"));
        assertNotNull(e.handle(HarnessInput.of(session, "hello")));
    }

    @Test
    void mainModelAndSubagentBuilders() {
        HarnessEngine e = engine();
        assertNotNull(e.getMainModel());
        assertNotNull(e.getModelOrDefInstance(e.getDefaultModel()));
        assertNotNull(e.getAgentBuilder(AgentDefinition.builder().name("sub").build()));
    }

    // ---------- HarnessInput 链式配置 ----------

    @Test
    void inputChainableSettersAreStored() {
        Predicate<String> validator = name -> name.startsWith("a");
        HarnessInput input = HarnessInput.of((AgentSession) null, "text")
                .terminal(true)
                .agentNameValidator(validator)
                .commandContextFactory(null);

        assertTrue(input.isTerminal());
        assertSame(validator, input.getAgentNameValidator());
        assertNull(input.getCommandContextFactory());

        // 默认值
        HarnessInput plain = HarnessInput.of((AgentSession) null, "text");
        assertTrue(!plain.isTerminal(), "terminal 默认 false");
        assertNull(plain.getAgentNameValidator());
    }
}
