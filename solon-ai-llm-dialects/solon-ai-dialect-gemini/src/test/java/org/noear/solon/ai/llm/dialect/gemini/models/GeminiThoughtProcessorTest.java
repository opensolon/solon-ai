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
package org.noear.solon.ai.llm.dialect.gemini.models;

import org.junit.jupiter.api.Test;
import org.noear.snack4.ONode;
import org.noear.solon.ai.chat.ChatConfig;
import org.noear.solon.ai.chat.ChatOptions;
import org.noear.solon.ai.chat.ChatRequest;
import org.noear.solon.ai.chat.ChatResponseDefault;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.session.InMemoryChatSession;
import org.noear.solon.ai.chat.tool.ToolCall;
import org.noear.solon.ai.llm.dialect.gemini.GeminiChatDialect;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * GeminiThoughtProcessor 解析单元测试
 * <p>
 * 对齐 Google Gemini 3+ 官方规范：functionCall 携带唯一调用 id，
 * 解析时需保留该 id（用于 functionResponse / 历史 functionCall 回传）。
 */
public class GeminiThoughtProcessorTest {
    private final GeminiThoughtProcessor processor = new GeminiThoughtProcessor();

    private ChatResponseDefault newResponse(boolean stream) {
        ChatConfig config = new ChatConfig();
        ChatOptions options = ChatOptions.of();
        ChatRequest req = new ChatRequest(config, GeminiChatDialect.getInstance(), options,
                InMemoryChatSession.builder().build(), ChatMessage.ofSystem("test"), null, stream);
        return new ChatResponseDefault(req, stream);
    }

    @Test
    public void parseFunctionCall_withServerId() {
        ChatResponseDefault resp = newResponse(true);
        ONode oContent = ONode.ofJson("{\"parts\":[{\"functionCall\":{\"name\":\"getWeather\"," +
                "\"args\":{\"city\":\"hz\"},\"id\":\"call-abc-123\"}}]}");

        List<AssistantMessage> messages = processor.parse(resp, oContent);

        assertEquals(1, messages.size());
        ToolCall call = messages.get(0).getToolCalls().get(0);
        assertEquals("call-abc-123", call.getId(), "应解析服务端返回的真实 id");
        assertEquals("getWeather", call.getName());
    }

    @Test
    public void parseFunctionCall_withoutServerId_idIsNull() {
        // Gemini 2.5 / OpenAI 兼容网关不返回 id：ToolCall.id 保持 null，
        // 回传时按 Gemini 2.5 的 name 关联方式（不写 id），避免本地伪造 id 导致网关关联失败
        ChatResponseDefault resp = newResponse(true);
        ONode oContent = ONode.ofJson("{\"parts\":[{\"functionCall\":{\"name\":\"getWeather\"," +
                "\"args\":{\"city\":\"hz\"}}}]}");

        List<AssistantMessage> messages = processor.parse(resp, oContent);

        ToolCall call = messages.get(0).getToolCalls().get(0);
        assertNull(call.getId(), "无服务端 id 时应保持 null（不伪造 id）");
    }

    @Test
    public void parseFunctionCall_streaming_parallelSameName_distinctIndex() {
        // 同一 chunk 并行调用同名函数：index 用 name#n 区分（流式聚合 key），id 保留各自服务端 id
        ChatResponseDefault resp = newResponse(true);
        ONode oContent = ONode.ofJson("{\"parts\":[" +
                "{\"functionCall\":{\"name\":\"getWeather\",\"args\":{\"city\":\"hz\"},\"id\":\"call-1\"}}," +
                "{\"functionCall\":{\"name\":\"getWeather\",\"args\":{\"city\":\"bj\"},\"id\":\"call-2\"}}]}");

        List<AssistantMessage> messages = processor.parse(resp, oContent);

        List<ToolCall> calls = messages.get(0).getToolCalls();
        assertEquals(2, calls.size());
        assertEquals("getWeather", calls.get(0).getIndex());
        assertEquals("getWeather#1", calls.get(1).getIndex());
        assertEquals("call-1", calls.get(0).getId());
        assertEquals("call-2", calls.get(1).getId());
    }

    @Test
    public void parseFunctionCall_continuationFrame_nameRestoredFromLast() {
        // OpenAI 兼容网关（如 bearlab.ai）流式转 Gemini 时把 functionCall 分帧发送：
        // 帧2 只带 args、name 为空。应视为续帧，从 lastToolCallId 恢复函数名。
        ChatResponseDefault resp = newResponse(true);
        ONode frame1 = ONode.ofJson("{\"parts\":[{\"functionCall\":{\"name\":\"getWeather\",\"args\":{}}}]}");
        ONode frame2 = ONode.ofJson("{\"parts\":[{\"functionCall\":{\"name\":\"\",\"args\":{\"city\":\"hz\"}}}]}");

        List<AssistantMessage> m1 = processor.parse(resp, frame1);
        List<AssistantMessage> m2 = processor.parse(resp, frame2);

        assertEquals("getWeather", m1.get(0).getToolCalls().get(0).getName());
        ToolCall call2 = m2.get(0).getToolCalls().get(0);
        assertEquals("getWeather", call2.getName(), "续帧应恢复函数名");
        assertEquals("hz", call2.getArguments().get("city"));
    }

    @Test
    public void parseFunctionCall_nonStream_withServerId() {
        ChatResponseDefault resp = newResponse(false);
        ONode oContent = ONode.ofJson("{\"parts\":[{\"functionCall\":{\"name\":\"getWeather\"," +
                "\"args\":{\"city\":\"hz\"},\"id\":\"call-xyz\"}}]}");

        List<AssistantMessage> messages = processor.parse(resp, oContent);

        ToolCall call = messages.get(0).getToolCalls().get(0);
        assertEquals("call-xyz", call.getId());
    }
}
