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
import org.noear.solon.ai.chat.event.ChatStreamContextDefault;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.session.InMemoryChatSession;
import org.noear.solon.ai.chat.tool.ToolCallBuilder;
import org.noear.solon.ai.llm.dialect.gemini.GeminiInteractionsDialect;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * thought_signature 跨轮回传链路的离线回归（Gemini Interactions API）
 *
 * <p>Interactions 协议里签名以 {@code thought_signature} 出现在 function_call step 上：
 * 流式经 step.delta(type=thought_signature) 下发，非流式直接挂在 step 上。两条入站路径都写入
 * acc.thinkingSignature；出站时只有第一个 function_call step 携带签名。</p>
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
        assertEquals("function_call", arr.get(0).get("type").getString(), arr.toJson());
        assertEquals("sig_int", arr.get(0).get("thought_signature").getString(), arr.toJson());
        assertFalse(arr.get(1).hasKey("thought_signature"),
                "仅第一个 function_call step 应携带签名: " + arr.toJson());
    }

    /**
     * 非流式入站：function_call step 上的 thought_signature 进 acc.thinkingSignature 与 ToolCall。
     */
    @Test
    public void nonStreamSignature_storedOnAccumulatorAndToolCall() {
        ChatAccumulator acc = newAccumulator(false);

        parser.parseNonStreamResponse(ChatStreamContextDefault.ofNoEmit(acc), "{\"model\":\"gemini-3-pro\",\"status\":\"completed\","
                + "\"steps\":[{\"type\":\"function_call\",\"id\":\"call-1\",\"name\":\"getWeather\","
                + "\"arguments\":{\"city\":\"hz\"},\"thought_signature\":\"sig_int\"}]}");

        assertEquals("sig_int", acc.thinkingSignature, "非流式 step 上的签名应进入 acc");
        assertEquals("sig_int",
                acc.lastItem().getToolCalls().get(0).getThoughtSignature(),
                "ToolCall 应携带签名");
    }

    /**
     * 反向锚点：无签名时不写出 thought_signature 字段。
     */
    @Test
    public void noSignature_fieldOmitted() {
        ChatAccumulator acc = newAccumulator(true);

        Map<String, ToolCallBuilder> builders = new LinkedHashMap<>();
        builders.put("getWeather", toolCallBuilder("call-1", "getWeather", "{}"));

        ONode arr = builder.buildAssistantToolCallMessageNode(acc, builders);

        assertFalse(arr.toJson().contains("thought_signature"), arr.toJson());
    }

    private ToolCallBuilder toolCallBuilder(String id, String name, String args) {
        ToolCallBuilder b = new ToolCallBuilder();
        b.idBuilder.append(id);
        b.nameBuilder.append(name);
        b.argumentsBuilder.append(args);
        return b;
    }
}
