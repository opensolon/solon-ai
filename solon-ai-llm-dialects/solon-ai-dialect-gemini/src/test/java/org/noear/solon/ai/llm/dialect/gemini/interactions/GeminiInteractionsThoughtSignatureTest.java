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
package org.noear.solon.ai.llm.dialect.gemini.interactions;

import org.junit.jupiter.api.Test;
import org.noear.snack4.ONode;
import org.noear.solon.ai.chat.ChatAccumulator;
import org.noear.solon.ai.chat.ChatConfig;
import org.noear.solon.ai.chat.ChatOptions;
import org.noear.solon.ai.chat.ChatRequest;
import org.noear.solon.ai.chat.event.ChatEvent;
import org.noear.solon.ai.chat.event.ChatEventType;
import org.noear.solon.ai.chat.event.ChatStreamContext;
import org.noear.solon.ai.chat.event.ChatStreamContextDefault;
import org.noear.solon.ai.chat.event.ChatStreamSession;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.message.MessageProtocolState;
import org.noear.solon.ai.chat.session.InMemoryChatSession;
import org.noear.solon.ai.chat.tool.ToolCallBuilder;
import org.noear.solon.ai.llm.dialect.gemini.GeminiInteractionsDialect;
import org.noear.solon.ai.llm.dialect.gemini.GeminiMessageStateSupport;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * thought_signature 跨轮回传链路的离线回归（Gemini Interactions API）
 *
 * <p>Interactions 协议把签名保存在 thought step：流式经
 * step.delta(type=thought_signature) 下发，非流式位于 thought.signature。出站时回放独立 thought step。</p>
 *
 * @author noear
 */
public class GeminiInteractionsThoughtSignatureTest {
    private final GeminiInteractionsResponseParser parser = new GeminiInteractionsResponseParser();
    private final GeminiInteractionsRequestBuilder builder = new GeminiInteractionsRequestBuilder();

    private ChatAccumulator newAccumulator(boolean stream) {
        ChatConfig config = new ChatConfig();
        config.setModel("gemini-3-pro");
        ChatRequest req = new ChatRequest(config, GeminiInteractionsDialect.getInstance(), ChatOptions.of(),
                InMemoryChatSession.builder().build(), ChatMessage.ofSystem("test"), null, stream);
        return new ChatAccumulator(req, stream);
    }

    /**
     * 流式入站 → 出站闭环：step.delta(thought_signature) 置位 acc.thinkingSignature，
     * 下一轮请求的第一个 function_call step 携带 thought_signature。
     */
    @Test
    public void streamSignature_replayedOnFirstFunctionCallStep() {
        ChatAccumulator acc = newAccumulator(true);

        parser.parseStreamResponse(ChatStreamContextDefault.ofNoEmit(acc), "{\"event_type\":\"step.start\",\"index\":0,"
                + "\"step\":{\"type\":\"function_call\",\"id\":\"call-1\",\"name\":\"getWeather\"}}");
        parser.parseStreamResponse(ChatStreamContextDefault.ofNoEmit(acc), "{\"event_type\":\"step.delta\",\"index\":0,"
                + "\"delta\":{\"type\":\"thought_signature\",\"signature\":\"sig_int\"}}");

        assertEquals("sig_int", acc.thinkingSignature, "step.delta 应置位 acc.thinkingSignature");

        Map<String, ToolCallBuilder> builders = new LinkedHashMap<>();
        builders.put("getWeather", toolCallBuilder("call-1", "getWeather", "{\"city\":\"hz\"}"));
        builders.put("getWeather#1", toolCallBuilder("call-2", "getWeather", "{\"city\":\"bj\"}"));

        ONode arr = builder.buildAssistantToolCallMessageNode(acc, builders);

        assertTrue(arr.isArray(), arr.toJson());
        assertEquals("thought", arr.get(0).get("type").getString(), arr.toJson());
        assertEquals("sig_int", arr.get(0).get("signature").getString(), arr.toJson());
        assertEquals("function_call", arr.get(1).get("type").getString(), arr.toJson());
        assertFalse(arr.get(1).hasKey("thought_signature"), arr.toJson());
    }

    /**
     * 非流式入站：thought.signature 进入 acc.thinkingSignature 与协议状态，不再双写旧字段。
     */
    @Test
    public void nonStreamSignature_storedOnAccumulatorAndProtocolState() {
        ChatAccumulator acc = newAccumulator(false);
        List<ChatEvent> events = new ArrayList<>();
        ChatStreamContext ctx = new ChatStreamContextDefault(null, acc.getRequest(), acc,
                new ChatStreamSession(), 0, events::add);

        parser.parseNonStreamResponse(ctx, "{\"model\":\"gemini-3-pro\",\"status\":\"completed\","
                + "\"steps\":[{\"type\":\"thought\",\"signature\":\"sig_int\"},"
                + "{\"type\":\"function_call\",\"id\":\"call-1\",\"name\":\"getWeather\","
                + "\"arguments\":{\"city\":\"hz\"}}]}");

        assertEquals("sig_int", acc.thinkingSignature, "非流式 step 上的签名应进入 acc");
        AssistantMessage message = acc.snapshotTerminal().getMessage();
        assertNull(message.getToolCalls().get(0).getThoughtSignature(),
                "新响应不得双写 deprecated ToolCall 字段");
        MessageProtocolState state = message.getProtocolState(
                GeminiMessageStateSupport.INTERACTIONS_PROTOCOL_ID);
        assertNotNull(state, message.toString());
        assertNotNull(state.getSemanticHash(), message.toString());
        assertNull(message.getContentRaw(), "Interactions 新终态不得生成 legacy contentRaw");
        assertFalse(ONode.ofJson(ChatMessage.toJson(message)).hasKey("contentRaw"),
                "新状态 JSON 不应出现 legacy contentRaw");
        List<ChatEvent> signatureEvents = new ArrayList<>();
        for (ChatEvent event : events) {
            if (event.getType() == ChatEventType.THINKING_SIGNATURE) {
                signatureEvents.add(event);
            }
        }
        assertEquals(1, signatureEvents.size(), "thought.signature 必须发出专用事件");
        assertEquals("sig_int", signatureEvents.get(0).getText());
        assertEquals("thought", signatureEvents.get(0).getRawType());
    }

    @Test
    public void assistantSteps_newProtocolStateJsonRoundTrip_replaysThoughtStep() {
        ChatAccumulator acc = newAccumulator(false);
        List<AssistantMessage> messages = GeminiInteractionsDialect.getInstance().parseAssistantMessage(acc,
                ONode.ofJson("[{\"type\":\"thought\",\"signature\":\"sig_step\"},"
                        + "{\"type\":\"function_call\",\"id\":\"call-1\","
                        + "\"name\":\"getWeather\",\"arguments\":{\"city\":\"hz\"}},"
                        + "{\"type\":\"function_call\",\"id\":\"call-2\","
                        + "\"name\":\"getWeather\",\"arguments\":{\"city\":\"bj\"}}]"));

        assertEquals(1, messages.size());
        AssistantMessage message = messages.get(0);
        assertNull(message.getToolCalls().get(0).getThoughtSignature());
        assertNull(message.getToolCalls().get(1).getThoughtSignature());
        MessageProtocolState state = message.getProtocolState(
                GeminiMessageStateSupport.INTERACTIONS_PROTOCOL_ID);
        assertNotNull(state);
        assertEquals(GeminiMessageStateSupport.VERSION, state.getVersion());
        assertNotNull(state.getSemanticHash());
        assertNull(message.getContentRaw());

        String json = ChatMessage.toJson(message);
        ONode serialized = ONode.ofJson(json);
        assertTrue(serialized.hasKey("protocolStates"), json);
        assertFalse(serialized.hasKey("contentRaw"), json);
        assertFalse(serialized.get("toolCalls").get(0).hasKey("thoughtSignature"), json);

        AssistantMessage restored = (AssistantMessage) ChatMessage.fromJson(json);
        MessageProtocolState restoredState = restored.getProtocolState(
                GeminiMessageStateSupport.INTERACTIONS_PROTOCOL_ID);
        assertNotNull(restoredState, json);
        assertEquals(GeminiMessageStateSupport.VERSION, restoredState.getVersion());
        assertEquals(state.getSemanticHash(), restoredState.getSemanticHash());
        assertNull(restored.getContentRaw());

        ONode request = builder.build(new ChatConfig(), ChatOptions.of(),
                java.util.Collections.<ChatMessage>singletonList(restored), false);
        assertEquals("thought", request.get("input").get(0).get("type").getString(), request.toJson());
        assertEquals("sig_step", request.get("input").get(0).get("signature").getString(), request.toJson());
        assertEquals("function_call", request.get("input").get(1).get("type").getString(), request.toJson());
        assertEquals("call-1", request.get("input").get(1).get("id").getString(), request.toJson());
    }


    @Test
    public void legacyToolCallJson_replaysSignatureWithoutProtocolState() {
        AssistantMessage restored = (AssistantMessage) ChatMessage.fromJson(
                "{\"role\":\"assistant\",\"text\":\"\",\"thinking\":\"\"," +
                        "\"toolCalls\":[{\"index\":\"0\",\"id\":\"call-1\"," +
                        "\"name\":\"getWeather\",\"argumentsStr\":\"{}\"," +
                        "\"arguments\":{},\"thoughtSignature\":\"sig_old\"}]}");

        assertFalse(restored.hasProtocolStates());
        assertEquals("sig_old", restored.getToolCalls().get(0).getThoughtSignature());
        ONode request = builder.build(new ChatConfig(), ChatOptions.of(),
                java.util.Collections.<ChatMessage>singletonList(restored), false);
        assertEquals("thought", request.get("input").get(0).get("type").getString(), request.toJson());
        assertEquals("sig_old", request.get("input").get(0).get("signature").getString(), request.toJson());
    }

    @Test
    public void noSignature_fieldOmitted() {
        ChatAccumulator acc = newAccumulator(true);

        Map<String, ToolCallBuilder> builders = new LinkedHashMap<>();
        builders.put("getWeather", toolCallBuilder("call-1", "getWeather", "{}"));

        ONode arr = builder.buildAssistantToolCallMessageNode(acc, builders);

        assertFalse(arr.toJson().contains("signature"), arr.toJson());
    }

    private ToolCallBuilder toolCallBuilder(String id, String name, String args) {
        ToolCallBuilder b = new ToolCallBuilder();
        b.idBuilder.append(id);
        b.nameBuilder.append(name);
        b.argumentsBuilder.append(args);
        return b;
    }
}
