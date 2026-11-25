package org.noear.solon.ai.harness.agent;

import org.junit.jupiter.api.Test;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 会话级子代理：存入的定义读回后元数据与系统提示词（正文）都要完整。
 */
class SessionAgentsTest {
    @Test
    void getRestoresMetadataAndSystemPrompt() {
        AgentSession session = InMemoryAgentSession.of("s1");
        String prompt = "you are helper\n\n- rule 1\n- rule 2";

        assertTrue(SessionAgents.putIfAbsent(session, AgentDefinition.builder()
                .name("helper")
                .description("helps")
                .systemPrompt(prompt)
                .metadata(m -> m.setTools(Arrays.asList("read", "grep")))
                .build(), SessionAgents.MAX_PER_SESSION));

        AgentDefinition def = SessionAgents.get(session, "helper");
        assertNotNull(def);
        assertEquals("helper", def.getName());
        assertEquals("helps", def.getDescription());
        assertEquals(Arrays.asList("read", "grep"), def.getMetadata().getTools());
        assertEquals(prompt, def.getSystemPrompt());
    }
}
