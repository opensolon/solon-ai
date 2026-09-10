/*
 * Copyright 2017-2025 noear.org and authors
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
import org.noear.solon.ai.agent.Agent;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.agent.simple.SimpleAgent;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.ChatRequestDesc;
import org.noear.solon.ai.chat.ChatResponse;
import org.noear.solon.ai.chat.ChatSession;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.message.MessageProtocolState;
import org.noear.solon.ai.chat.source.Citation;
import org.noear.solon.ai.chat.source.SearchResult;
import org.noear.solon.ai.chat.prompt.Prompt;

import java.util.Collections;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class SimpleAgentThinkingOnlyTest {
    @Test
    public void thinkingOnlyResponseFallsBackToNormalFinalText() throws Throwable {
        ChatModel chatModel = mock(ChatModel.class);
        ChatRequestDesc request = mock(ChatRequestDesc.class);
        ChatResponse response = mock(ChatResponse.class);
        AssistantMessage thinkingOnly = AssistantMessage.snapshot(
                "", "recovered reasoning", null, null,
                Collections.singletonList(new SearchResult().title("Solon").url("https://solon.noear.org")),
                Collections.singletonList(new Citation().type("url_citation").url("https://solon.noear.org/docs")),
                Collections.singletonMap("vendor.protocol", new MessageProtocolState(1)),
                Collections.<String, Object>singletonMap("trace", "kept"));

        when(chatModel.prompt(any(Prompt.class))).thenReturn(request);
        when(request.session(any(ChatSession.class))).thenReturn(request);
        when(request.options(any(Consumer.class))).thenReturn(request);
        when(request.call()).thenReturn(response);
        when(response.isEmpty()).thenReturn(false);
        when(response.getMessage()).thenReturn(thinkingOnly);

        AssistantMessage result = SimpleAgent.of(chatModel)
                .retryConfig(1, 0L)
                .build()
                .prompt("question")
                .session(InMemoryAgentSession.of("thinking-only"))
                .call()
                .getMessage();

        assertEquals("recovered reasoning", result.getText());
        assertEquals("recovered reasoning", result.getContent());
        assertFalse(result.hasThinking(), "ReAct-compatible fallback should be a normal final answer");
        assertEquals("https://solon.noear.org", result.getSearchResults().get(0).getUrl());
        assertEquals("https://solon.noear.org/docs", result.getCitations().get(0).getUrl());
        assertEquals("kept", result.getMetadata().get("trace"));
        assertNull(result.getProtocolState("vendor.protocol"), "语义变化后不得继承精确回放状态");
    }

    @Test
    public void sourceProjectionKeepsLegacyInlineThinkingWithoutReplayCarriers() throws Throwable {
        ChatModel chatModel = mock(ChatModel.class);
        ChatRequestDesc request = mock(ChatRequestDesc.class);
        ChatResponse response = mock(ChatResponse.class);
        AssistantMessage legacy = (AssistantMessage) ChatMessage.fromJson(
                "{\"role\":\"assistant\",\"content\":\"<think>legacy reasoning</think>old answer\"," +
                        "\"contentRaw\":{\"legacy\":true},\"reasoningFieldName\":\"reasoning_content\"," +
                        "\"metadata\":{\"_agent_\":\"delegate\",\"source\":\"projected answer\"}," +
                        "\"protocolStates\":{\"vendor.protocol\":{\"version\":1,\"data\":{}}}}");

        when(chatModel.prompt(any(Prompt.class))).thenReturn(request);
        when(request.session(any(ChatSession.class))).thenReturn(request);
        when(request.options(any(Consumer.class))).thenReturn(request);
        when(request.call()).thenReturn(response);
        when(response.isEmpty()).thenReturn(false);
        when(response.getMessage()).thenReturn(legacy);

        AssistantMessage result = SimpleAgent.of(chatModel)
                .retryConfig(1, 0L)
                .build()
                .prompt("question")
                .session(InMemoryAgentSession.of("source-projection"))
                .call()
                .getMessage();

        assertNotSame(legacy, result);
        assertEquals("projected answer", result.getText());
        assertEquals("legacy reasoning", result.getThinking());
        assertNull(result.getContentRaw());
        assertNull(result.getReasoningFieldName());
        assertFalse(result.hasProtocolStates());
        assertEquals("legacy reasoning", legacy.getThinking());
    }
}
