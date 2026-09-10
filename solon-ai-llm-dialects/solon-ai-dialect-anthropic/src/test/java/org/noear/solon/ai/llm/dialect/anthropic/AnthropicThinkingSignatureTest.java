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
package org.noear.solon.ai.llm.dialect.anthropic;

import org.junit.jupiter.api.Test;
import org.noear.snack4.ONode;
import org.noear.solon.ai.chat.*;
import org.noear.solon.ai.chat.event.*;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.session.InMemoryChatSession;
import org.noear.solon.ai.chat.tool.ToolCall;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * thinking signature 跨轮回传链路的离线回归（Anthropic）
 *
 * <p>链路三段：
 * <ol>
 *   <li>入站：signature_delta → acc.thinkingSignature + THINKING_SIGNATURE 事件；</li>
 *   <li>终态载体：message_stop 补一帧 contentRaw={thinkingSignature} 的空 choice，
 *       使核心 buildAggregationMessage() 取 last choice 的 contentRaw 时带上签名；</li>
 *   <li>出站：下一轮请求构建从 AssistantMessage.contentRaw 取回签名，写入 thinking 块的 signature。</li>
 * </ol>
 * 任一段断裂都会导致多轮 extended thinking 上下文断裂（服务端丢弃无签名 thinking 块）。</p>
 *
 * @author noear
 */
public class AnthropicThinkingSignatureTest {
    private final AnthropicResponseParser parser = new AnthropicResponseParser();

    private final List<ChatEvent> events = new ArrayList<>();

    private ChatStreamContext newCtx() {
        events.clear();

        ChatConfig config = new ChatConfig();
        config.setModel("claude-sonnet-4-5");
        ChatRequest req = new ChatRequest(config, AnthropicChatDialect.getInstance(), ChatOptions.of(),
                InMemoryChatSession.builder().build(), ChatMessage.ofSystem("test"), null, true);

        return new ChatStreamContextDefault(config, req, new ChatAccumulator(req, true),
                new ChatStreamSession(), 0, events::add);
    }

    private ChatEvent firstOf(ChatEventType type) {
        for (ChatEvent e : events) {
            if (e.getType() == type) {
                return e;
            }
        }
        return null;
    }

    /**
     * 环节 1+2：完整流式序列（thinking → signature → text → message_stop）后，
     * 签名既进 acc.thinkingSignature，也随终态载体帧进 last choice 的 contentRaw。
     */
    @Test
    public void fullStream_signatureLandsOnLastChoiceContentRaw() {
        ChatStreamContext ctx = newCtx();

        parser.parseStreamResponse(ctx, "{\"type\":\"message_start\",\"message\":{\"id\":\"msg_1\","
                + "\"model\":\"claude-sonnet-4-5\",\"usage\":{\"input_tokens\":10,\"output_tokens\":1}}}");
        parser.parseStreamResponse(ctx, "{\"type\":\"content_block_start\",\"index\":0,"
                + "\"content_block\":{\"type\":\"thinking\",\"thinking\":\"\"}}");
        parser.parseStreamResponse(ctx, "{\"type\":\"content_block_delta\",\"index\":0,"
                + "\"delta\":{\"type\":\"thinking_delta\",\"thinking\":\"让我想想\"}}");
        parser.parseStreamResponse(ctx, "{\"type\":\"content_block_delta\",\"index\":0,"
                + "\"delta\":{\"type\":\"signature_delta\",\"signature\":\"sig_abc\"}}");
        parser.parseStreamResponse(ctx, "{\"type\":\"content_block_stop\",\"index\":0}");
        parser.parseStreamResponse(ctx, "{\"type\":\"content_block_start\",\"index\":1,"
                + "\"content_block\":{\"type\":\"text\",\"text\":\"\"}}");
        parser.parseStreamResponse(ctx, "{\"type\":\"content_block_delta\",\"index\":1,"
                + "\"delta\":{\"type\":\"text_delta\",\"text\":\"杭州今天晴\"}}");
        parser.parseStreamResponse(ctx, "{\"type\":\"content_block_stop\",\"index\":1}");
        parser.parseStreamResponse(ctx, "{\"type\":\"message_delta\","
                + "\"delta\":{\"stop_reason\":\"end_turn\"},\"usage\":{\"output_tokens\":20}}");
        parser.parseStreamResponse(ctx, "{\"type\":\"message_stop\"}");

        ChatAccumulator acc = ctx.getAccumulator();

        // (a) 专用事件通道
        ChatEvent e = firstOf(ChatEventType.THINKING_SIGNATURE);
        assertNotNull(e, "signature_delta should emit THINKING_SIGNATURE");
        assertEquals("sig_abc", e.getText());
        assertTrue(e.getRaw().toJson().contains("sig_abc"), e.getRaw().toJson());

        // (b) 聚合器字段（tool 多轮回传路径依赖）
        assertEquals("sig_abc", acc.thinkingSignature);

        ChatResponse terminal = acc.snapshotTerminal();
        assertTrue(terminal.isTerminal());
        AssistantMessage terminalMessage = terminal.getMessage();
        assertNotNull(terminalMessage);

        Object contentRaw = AnthropicMessageStateSupport.resolveData(terminalMessage);
        assertTrue(contentRaw instanceof Map,
                "last choice contentRaw must be a Map carrier, but was: " + contentRaw);
        assertEquals("sig_abc", ((Map<?, ?>) contentRaw).get("thinkingSignature"));

        assertEquals("杭州今天晴", terminal.getText());
        assertEquals("让我想想", terminal.getThinking());
        assertNotNull(firstOf(ChatEventType.TEXT_DELTA));
        assertNotNull(firstOf(ChatEventType.THINKING_DELTA));
    }

    @Test
    public void signatureOnThinkingBlockStartEmitsOnceAndIsSaved() {
        ChatStreamContext ctx = newCtx();

        parser.parseStreamResponse(ctx, "{\"type\":\"content_block_start\",\"index\":3,"
                + "\"content_block\":{\"type\":\"thinking\",\"thinking\":\"plan\","
                + "\"signature\":\"sig_on_start\"}}");
        parser.parseStreamResponse(ctx, "{\"type\":\"content_block_stop\",\"index\":3}");
        parser.parseStreamResponse(ctx, "{\"type\":\"message_stop\"}");

        assertEquals(1, countOf(ChatEventType.THINKING_SIGNATURE),
                "start 直接携带签名且无 signature_delta 时应发唯一签名事件");
        ChatEvent signature = firstOf(ChatEventType.THINKING_SIGNATURE);
        assertEquals("sig_on_start", signature.getText());
        assertEquals(3, signature.getIndex());
        assertEquals("sig_on_start", ctx.getAccumulator().thinkingSignature);

        Map<?, ?> state = AnthropicMessageStateSupport.resolveData(
                ctx.getAccumulator().snapshotTerminal().getMessage());
        assertNotNull(state);
        assertEquals("sig_on_start", state.get("thinkingSignature"));
        ONode block = ONode.ofJson((String) ((List<?>) state.get(
                AnthropicResponseParser.CONTENT_BLOCKS_RAW_KEY)).get(0));
        assertEquals("sig_on_start", block.get("signature").getString());
    }

    /**
     * 兼容网关可能把官方单帧完整 signature 拆成多个 signature_delta。
     * 各分片必须按 content block index 累计，且事件、acc、终态载体保持同一份完整值。
     */
    @Test
    public void fragmentedSignature_accumulatesByBlockIndex() {
        ChatStreamContext ctx = newCtx();

        parser.parseStreamResponse(ctx, "{\"type\":\"content_block_start\",\"index\":2,"
                + "\"content_block\":{\"type\":\"thinking\",\"thinking\":\"\"}}");
        parser.parseStreamResponse(ctx, "{\"type\":\"content_block_delta\",\"index\":2,"
                + "\"delta\":{\"type\":\"thinking_delta\",\"thinking\":\"让我想想\"}}");
        parser.parseStreamResponse(ctx, "{\"type\":\"content_block_delta\",\"index\":2,"
                + "\"delta\":{\"type\":\"signature_delta\",\"signature\":\"sig_\"}}");
        parser.parseStreamResponse(ctx, "{\"type\":\"content_block_delta\",\"index\":2,"
                + "\"delta\":{\"type\":\"signature_delta\",\"signature\":\"abc\"}}");
        parser.parseStreamResponse(ctx, "{\"type\":\"content_block_stop\",\"index\":2}");
        parser.parseStreamResponse(ctx, "{\"type\":\"message_stop\"}");

        ChatAccumulator acc = ctx.getAccumulator();
        assertEquals("sig_abc", acc.thinkingSignature);

        List<ChatEvent> signatureEvents = new ArrayList<>();
        for (ChatEvent event : events) {
            if (event.getType() == ChatEventType.THINKING_SIGNATURE) {
                signatureEvents.add(event);
            }
        }
        assertEquals(2, signatureEvents.size());
        assertEquals("sig_", signatureEvents.get(0).getText());
        assertEquals("sig_abc", signatureEvents.get(1).getText());
        assertEquals(2, signatureEvents.get(1).getIndex());

        AssistantMessage terminalMessage = acc.snapshotTerminal().getMessage();
        Map<?, ?> contentRaw = AnthropicMessageStateSupport.resolveData(terminalMessage);
        assertEquals("sig_abc", contentRaw.get("thinkingSignature"));

        List<?> rawBlocks = (List<?>) contentRaw.get(AnthropicResponseParser.CONTENT_BLOCKS_RAW_KEY);
        assertEquals(1, rawBlocks.size());
        ONode thinkingBlock = ONode.ofJson((String) rawBlocks.get(0));
        assertEquals("让我想想", thinkingBlock.get("thinking").getString());
        assertEquals("sig_abc", thinkingBlock.get("signature").getString());
    }

    /**
     * 环节 2 幂等：message_stop 之后网关再补 [DONE]，终态载荷与语义事件只保留一次。
     */
    @Test
    public void terminalCarrierFrame_isIdempotentAcrossDone() {
        ChatStreamContext ctx = newCtx();

        parser.parseStreamResponse(ctx, "{\"type\":\"content_block_start\",\"index\":0,"
                + "\"content_block\":{\"type\":\"thinking\",\"thinking\":\"\"}}");
        parser.parseStreamResponse(ctx, "{\"type\":\"content_block_delta\",\"index\":0,"
                + "\"delta\":{\"type\":\"signature_delta\",\"signature\":\"sig_abc\"}}");
        parser.parseStreamResponse(ctx, "{\"type\":\"message_stop\"}");

        ChatResponse afterStop = ctx.getAccumulator().snapshotTerminal();
        assertNotNull(afterStop.getMessage());
        int signatureEvents = countOf(ChatEventType.THINKING_SIGNATURE);
        int thinkingEndEvents = countOf(ChatEventType.THINKING_END);

        parser.parseStreamResponse(ctx, "data: [DONE]");

        ChatResponse afterDone = ctx.getAccumulator().snapshotTerminal();
        assertEquals(afterStop.getText(), afterDone.getText());
        assertEquals(afterStop.getThinking(), afterDone.getThinking());
        assertEquals(afterStop.getFinishReason(), afterDone.getFinishReason());
        assertEquals(AnthropicMessageStateSupport.resolveData(afterStop.getMessage()), AnthropicMessageStateSupport.resolveData(afterDone.getMessage()));
        assertEquals(signatureEvents, countOf(ChatEventType.THINKING_SIGNATURE));
        assertEquals(thinkingEndEvents, countOf(ChatEventType.THINKING_END));

        Object contentRaw = AnthropicMessageStateSupport.resolveData(afterDone.getMessage());
        assertTrue(contentRaw instanceof Map, String.valueOf(contentRaw));
        assertEquals("sig_abc", ((Map<?, ?>) contentRaw).get("thinkingSignature"));
    }

    private int countOf(ChatEventType type) {
        int count = 0;
        for (ChatEvent event : events) {
            if (event.getType() == type) {
                count++;
            }
        }
        return count;
    }

    @Test
    public void requestBuild_replaysSignatureFromContentRaw() {
        AssistantMessage history = (AssistantMessage) ChatMessage.fromJson(
                "{\"role\":\"assistant\",\"text\":\"\",\"thinking\":\"让我想想\"," +
                        "\"contentRaw\":{\"thinkingSignature\":\"sig_abc\"}}");

        ONode root = buildRequest(Arrays.asList(ChatMessage.ofUser("天气"), history,
                ChatMessage.ofUser("那明天呢")));

        String json = root.toJson();
        assertTrue(json.contains("\"type\":\"thinking\""), json);
        assertTrue(json.contains("\"signature\":\"sig_abc\""),
                "thinking block must carry the replayed signature: " + json);
        assertTrue(json.contains("让我想想"), json);
    }

    /**
     * 环节 3（工具多轮）：带 toolCalls 的历史消息同样从 contentRaw 取回签名。
     */
    @Test
    public void requestBuild_replaysSignatureWithToolCalls() {
        AssistantMessage history = (AssistantMessage) ChatMessage.fromJson(
                "{\"role\":\"assistant\",\"text\":\"\",\"thinking\":\"让我想想\"," +
                        "\"contentRaw\":{\"thinkingSignature\":\"sig_abc\"}," +
                        "\"toolCalls\":[{\"index\":\"getWeather\",\"id\":\"toolu_1\"," +
                        "\"name\":\"getWeather\",\"argumentsStr\":\"{}\",\"arguments\":{}}]}");

        ONode root = buildRequest(Arrays.asList(ChatMessage.ofUser("天气"), history));

        ONode content = root.get("messages").get(1).get("content");
        assertEquals("thinking", content.get(0).get("type").getString(), content.toJson());
        assertEquals("sig_abc", content.get(0).get("signature").getString(), content.toJson());
        assertEquals("tool_use", content.get(1).get("type").getString(), content.toJson());
    }

    /**
     * 反向锚点：签名缺失时不得回传 thinking 块（无签名 thinking 会被服务端/兼容网关拒绝）。
     */
    @Test
    public void requestBuild_dropsThinkingWithoutSignature() {
        AssistantMessage history = (AssistantMessage) ChatMessage.fromJson(
                "{\"role\":\"assistant\",\"text\":\"\",\"thinking\":\"让我想想\"," +
                        "\"contentRaw\":{}}");

        String json = buildRequest(Arrays.asList(ChatMessage.ofUser("天气"), history)).toJson();

        assertFalse(json.contains("\"type\":\"thinking\""),
                "thinking without signature must not be replayed: " + json);
    }

    private ONode buildRequest(List<ChatMessage> messages) {
        ChatConfig config = new ChatConfig();
        config.setModel("claude-sonnet-4-5");

        return AnthropicChatDialect.getInstance()
                .buildRequestJson(config, ChatOptions.of(), messages, false);
    }
}
