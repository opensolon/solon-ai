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
import org.noear.solon.ai.chat.ChatAccumulator;
import org.noear.solon.ai.chat.event.ChatEvent;
import org.noear.solon.ai.chat.event.ChatEventType;
import org.noear.solon.ai.chat.event.ChatStreamContext;
import org.noear.solon.ai.chat.event.ChatStreamContextDefault;
import org.noear.solon.ai.chat.event.ChatStreamSession;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.session.InMemoryChatSession;
import org.noear.solon.ai.chat.tool.ToolCall;
import org.noear.solon.ai.chat.tool.ToolCallBuilder;
import org.noear.solon.ai.llm.dialect.gemini.GeminiChatDialect;

import java.util.ArrayList;
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

    private ChatAccumulator newResponse(boolean stream) {
        ChatConfig config = new ChatConfig();
        ChatOptions options = ChatOptions.of();
        ChatRequest req = new ChatRequest(config, GeminiChatDialect.getInstance(), options,
                InMemoryChatSession.builder().build(), ChatMessage.ofSystem("test"), null, stream);
        return new ChatAccumulator(req, stream);
    }

    @Test
    public void parseFunctionCall_withServerId() {
        ChatAccumulator resp = newResponse(true);
        ONode oContent = ONode.ofJson("{\"parts\":[{\"functionCall\":{\"name\":\"getWeather\"," +
                "\"args\":{\"city\":\"hz\"},\"id\":\"call-abc-123\"}}]}");

        List<AssistantMessage> messages = processor.parse(resp, oContent);

        assertEquals(1, messages.size());
        ToolCall call = messages.get(0).getToolCalls().get(0);
        assertEquals("call-abc-123", call.getId(), "应解析服务端返回的真实 id");
        assertEquals("getWeather", call.getName());
    }

    @Test
    public void parseThoughtAndText_doesNotCreateLegacyContentRawMirror() {
        ChatAccumulator resp = newResponse(false);
        ONode content = ONode.ofJson("{\"parts\":["
                + "{\"thought\":true,\"text\":\"内部思考\"},"
                + "{\"text\":\"最终答案\"}]}" );

        AssistantMessage message = processor.parse(resp, content).get(0);
        assertEquals("内部思考", message.getThinking());
        assertEquals("最终答案", message.getText());
        assertNull(message.getContentRaw(), "Gemini 新消息不应重复生成 contentRaw 镜像");
    }
    @Test
    public void parseFunctionCall_withoutServerId_idIsNull() {
        // Gemini 2.5 / OpenAI 兼容网关不返回 id：ToolCall.id 保持 null，
        // 回传时按 Gemini 2.5 的 name 关联方式（不写 id），避免本地伪造 id 导致网关关联失败
        ChatAccumulator resp = newResponse(true);
        ONode oContent = ONode.ofJson("{\"parts\":[{\"functionCall\":{\"name\":\"getWeather\"," +
                "\"args\":{\"city\":\"hz\"}}}]}");

        List<AssistantMessage> messages = processor.parse(resp, oContent);

        ToolCall call = messages.get(0).getToolCalls().get(0);
        assertNull(call.getId(), "无服务端 id 时应保持 null（不伪造 id）");
    }

    @Test
    public void parseFunctionCall_streaming_parallelSameName_distinctIndex() {
        // 同一 chunk 并行调用同名函数：index 用 name#n 区分（流式聚合 key），id 保留各自服务端 id
        ChatAccumulator resp = newResponse(true);
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
        ChatAccumulator resp = newResponse(true);
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
    public void parseFunctionCall_continuationFrame_emitsSingleStart() {
        ChatConfig config = new ChatConfig();
        ChatRequest req = new ChatRequest(config, GeminiChatDialect.getInstance(), ChatOptions.of(),
                InMemoryChatSession.builder().build(), ChatMessage.ofSystem("test"), null, true);
        List<ChatEvent> events = new ArrayList<>();
        ChatStreamContext ctx = new ChatStreamContextDefault(config, req, new ChatAccumulator(req, true),
                new ChatStreamSession(), 0, events::add);

        ONode frame1 = ONode.ofJson("{\"parts\":[{\"functionCall\":{\"name\":\"getWeather\",\"args\":{}}}]}");
        ONode frame2 = ONode.ofJson("{\"parts\":[{\"functionCall\":{\"name\":\"\",\"args\":{\"city\":\"hz\"}}}]}");
        processor.emitStream(ctx, frame1);
        processor.emitStream(ctx, frame2);
        processor.completeStream(ctx);

        List<ChatEvent> starts = new ArrayList<>();
        for (ChatEvent event : events) {
            if (event.getType() == ChatEventType.TOOL_CALL_START) {
                starts.add(event);
            }
        }
        assertEquals(1, starts.size(), "同一工具跨帧只能发出一个 TOOL_CALL_START");
        assertEquals("getWeather", starts.get(0).getToolCall().getName());
        assertTrue(events.stream()
                        .filter(e -> e.getType() == ChatEventType.TOOL_CALL_ARGS_DELTA)
                        .anyMatch(e -> e.getText() != null && e.getText().contains("\"city\":\"hz\"")),
                "续帧参数仍应正常交付");
    }

    @Test
    public void cumulativeArgsSnapshotsEmitOnlyFinalDelta() {
        ChatConfig config = new ChatConfig();
        ChatRequest req = new ChatRequest(config, GeminiChatDialect.getInstance(), ChatOptions.of(),
                InMemoryChatSession.builder().build(), ChatMessage.ofSystem("test"), null, true);
        List<ChatEvent> events = new ArrayList<>();
        ChatAccumulator acc = new ChatAccumulator(req, true);
        ChatStreamContext ctx = new ChatStreamContextDefault(config, req, acc,
                new ChatStreamSession(), 0, events::add);

        processor.emitStream(ctx, ONode.ofJson("{\"parts\":[{\"functionCall\":{\"name\":\"getWeather\","
                + "\"args\":{\"city\":\"杭\"},\"id\":\"call-1\"}}]}"));
        processor.emitStream(ctx, ONode.ofJson("{\"parts\":[{\"functionCall\":{\"name\":\"getWeather\","
                + "\"args\":{\"city\":\"杭州\"},\"id\":\"call-1\"}}]}"));
        processor.emitStream(ctx, ONode.ofJson("{\"parts\":[{\"functionCall\":{\"name\":\"getWeather\","
                + "\"args\":{\"city\":\"杭州\"},\"id\":\"call-1\"}}]}"));
        processor.completeStream(ctx);

        StringBuilder joined = new StringBuilder();
        long deltaCount = 0;
        for (ChatEvent event : events) {
            if (event.getType() == ChatEventType.TOOL_CALL_ARGS_DELTA) {
                deltaCount++;
                joined.append(event.getText());
            }
        }

        assertEquals(1L, deltaCount, "重复累计快照不得重复当成 ARGS_DELTA");
        assertEquals("{\"city\":\"杭州\"}", joined.toString(), "拼接事件必须等于最终参数快照");
        ToolCallBuilder builder = acc.getToolCallBuilders().values().iterator().next();
        assertEquals(joined.toString(), builder.argumentsBuilder.toString());
    }

    @Test
    public void lateFunctionCallIdKeepsStableKeyAndSingleStart() {
        ChatConfig config = new ChatConfig();
        ChatRequest req = new ChatRequest(config, GeminiChatDialect.getInstance(), ChatOptions.of(),
                InMemoryChatSession.builder().build(), ChatMessage.ofSystem("test"), null, true);
        List<ChatEvent> events = new ArrayList<>();
        ChatAccumulator acc = new ChatAccumulator(req, true);
        ChatStreamContext ctx = new ChatStreamContextDefault(config, req, acc,
                new ChatStreamSession(), 0, events::add);

        processor.emitStream(ctx, ONode.ofJson("{\"parts\":[{\"functionCall\":{\"name\":\"getWeather\","
                + "\"args\":{\"city\":\"hz\"}}}]}"));
        processor.emitStream(ctx, ONode.ofJson("{\"parts\":[{\"functionCall\":{\"name\":\"\","
                + "\"args\":{\"city\":\"hz\"},\"id\":\"call-late\"}}]}"));
        processor.completeStream(ctx);

        List<ChatEvent> starts = new ArrayList<>();
        for (ChatEvent event : events) {
            if (event.getType() == ChatEventType.TOOL_CALL_START) {
                starts.add(event);
            }
        }
        assertEquals(1, starts.size(), "迟到 id 不得造成重复 TOOL_CALL_START");
        assertEquals("call-late", starts.get(0).getToolCallId());
        assertEquals("candidate:0:function:0", starts.get(0).getToolCall().getIndex());
        assertEquals(1, acc.getToolCallBuilders().size(), "同一位置调用只能聚合到一个 builder");
        ToolCallBuilder builder = acc.getToolCallBuilders().values().iterator().next();
        assertEquals("call-late", builder.idBuilder.toString());
        assertEquals("getWeather", builder.nameBuilder.toString());
    }

    @Test
    public void parseFunctionCall_nonStream_withServerId() {
        ChatAccumulator resp = newResponse(false);
        ONode oContent = ONode.ofJson("{\"parts\":[{\"functionCall\":{\"name\":\"getWeather\"," +
                "\"args\":{\"city\":\"hz\"},\"id\":\"call-xyz\"}}]}");

        List<AssistantMessage> messages = processor.parse(resp, oContent);

        ToolCall call = messages.get(0).getToolCalls().get(0);
        assertEquals("call-xyz", call.getId());
    }
}
