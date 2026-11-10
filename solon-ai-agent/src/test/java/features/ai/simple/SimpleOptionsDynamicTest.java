/*
 * Copyright 2017-2026 noear.org and authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package features.ai.simple;

import org.junit.jupiter.api.Test;
import org.noear.solon.ai.agent.AgentHandler;
import org.noear.solon.ai.agent.simple.SimpleAgent;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.prompt.Prompt;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证 SimpleOptions 的 role / instruction / systemPrompt 在“请求级动态修正”时
 * 能穿过 copy() 生效（不为 null、不静默失效）。
 */
public class SimpleOptionsDynamicTest {

    /**
     * 用 handler 兜住物理调用，直接检查最终 system 消息
     */
    private static SimpleAgent agentOf(AtomicReference<String> systemPromptHolder, List<String> handlerPrompts) {
        AgentHandler handler = (Prompt prompt, org.noear.solon.ai.agent.AgentSession session) -> {
            for (ChatMessage message : prompt.getMessages()) {
                if (message instanceof org.noear.solon.ai.chat.message.SystemMessage) {
                    systemPromptHolder.set(message.getContent());
                }
            }
            handlerPrompts.add(systemPromptHolder.get());
            return ChatMessage.ofAssistant("ok");
        };

        return SimpleAgent.of().name("simple_agent").handler(handler).build();
    }

    @Test
    public void dynamicRoleAndInstruction() throws Throwable {
        AtomicReference<String> sp = new AtomicReference<>();
        List<String> captured = new ArrayList<>();
        SimpleAgent agent = agentOf(sp, captured);

        agent.prompt("hi")
                .options(o -> o.role("订单管理员").instruction("只处理订单相关问题"))
                .session(org.noear.solon.ai.agent.session.InMemoryAgentSession.of())
                .call();

        String systemPrompt = captured.get(0);
        assertTrue(systemPrompt.contains("## 你的角色"), systemPrompt);
        assertTrue(systemPrompt.contains("订单管理员"), systemPrompt);
        assertTrue(systemPrompt.contains("## 执行指令"), systemPrompt);
        assertTrue(systemPrompt.contains("只处理订单相关问题"), systemPrompt);
    }

    @Test
    public void builderLevelStillWorks() throws Throwable {
        AtomicReference<String> sp = new AtomicReference<>();
        List<String> captured = new ArrayList<>();

        AgentHandler handler = (Prompt prompt, org.noear.solon.ai.agent.AgentSession session) -> {
            for (ChatMessage message : prompt.getMessages()) {
                if (message instanceof org.noear.solon.ai.chat.message.SystemMessage) {
                    sp.set(message.getContent());
                }
            }
            captured.add(sp.get());
            return ChatMessage.ofAssistant("ok");
        };

        SimpleAgent agent = SimpleAgent.of()
                .name("simple_agent")
                .role("默认角色")
                .instruction("默认指令")
                .handler(handler)
                .build();

        agent.prompt("hi")
                .session(org.noear.solon.ai.agent.session.InMemoryAgentSession.of())
                .call();

        assertTrue(captured.get(0).contains("默认角色"), captured.get(0));
        assertTrue(captured.get(0).contains("默认指令"), captured.get(0));
    }

    @Test
    public void dynamicSystemPromptOverride() throws Throwable {
        AtomicReference<String> sp = new AtomicReference<>();
        List<String> captured = new ArrayList<>();
        SimpleAgent agent = agentOf(sp, captured);

        agent.prompt("hi")
                .options(o -> o.systemPrompt(t -> "你是自定义提示词"))
                .session(org.noear.solon.ai.agent.session.InMemoryAgentSession.of())
                .call();

        assertEquals("你是自定义提示词", captured.get(0));
    }
}
