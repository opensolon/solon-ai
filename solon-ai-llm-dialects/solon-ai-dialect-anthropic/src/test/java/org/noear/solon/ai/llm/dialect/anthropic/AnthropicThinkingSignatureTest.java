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

        // (c) 终态载体帧：last choice 的 contentRaw 必须是携带签名的 Map
        //     （核心 buildAggregationMessage 以此作为聚合消息的 contentRaw）
        Object contentRaw = acc.lastItem().getContentRaw();
        assertTrue(contentRaw instanceof Map,
                "last choice contentRaw must be a Map carrier, but was: " + contentRaw);
        assertEquals("sig_abc", ((Map<?, ?>) contentRaw).get("thinkingSignature"));

        // 内容主干仍由 choice 逐帧承载（正文/思考的最终聚合在核心 publishItem 完成），
        // 载体帧本身不带内容，不污染正文
        assertTrue(hasChoiceText(acc, "杭州今天晴"), "text_delta 应仍以 choice 承载");
        assertTrue(hasChoiceThinking(acc, "让我想想"), "thinking_delta 应仍以 choice 承载");
        assertEquals("", acc.lastItem().getText(), "载体帧必须是空内容帧");
    }

    private boolean hasChoiceText(ChatAccumulator acc, String text) {
        for (AssistantMessage c : acc.getContentItems()) {
            if (text.equals(c.getText())) {
                return true;
            }
        }
        return false;
    }

    private boolean hasChoiceThinking(ChatAccumulator acc, String thinking) {
        for (AssistantMessage c : acc.getContentItems()) {
            if (thinking.equals(c.getThinkingRaw())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 环节 2 幂等：message_stop 之后网关再补 [DONE]，载体帧只补一次。
     */
    @Test
    public void terminalCarrierFrame_isIdempotentAcrossDone() {
        ChatStreamContext ctx = newCtx();

        parser.parseStreamResponse(ctx, "{\"type\":\"content_block_start\",\"index\":0,"
                + "\"content_block\":{\"type\":\"thinking\",\"thinking\":\"\"}}");
        parser.parseStreamResponse(ctx, "{\"type\":\"content_block_delta\",\"index\":0,"
                + "\"delta\":{\"type\":\"signature_delta\",\"signature\":\"sig_abc\"}}");
        parser.parseStreamResponse(ctx, "{\"type\":\"message_stop\"}");

        int sizeAfterStop = ctx.getAccumulator().getContentItems().size();

        parser.parseStreamResponse(ctx, "data: [DONE]");

        assertEquals(sizeAfterStop, ctx.getAccumulator().getContentItems().size(),
                "terminal carrier frame must be emitted once");
        Object contentRaw = ctx.getAccumulator().lastItem().getContentRaw();
        assertTrue(contentRaw instanceof Map, String.valueOf(contentRaw));
        assertEquals("sig_abc", ((Map<?, ?>) contentRaw).get("thinkingSignature"));
    }

    /**
     * 环节 3：下一轮请求构建从 contentRaw 取回签名（无工具、仅思考的历史消息）。
     */
    @Test
    public void requestBuild_replaysSignatureFromContentRaw() {
        Map<String, Object> contentRaw = new LinkedHashMap<>();
        contentRaw.put("thinkingSignature", "sig_abc");

        AssistantMessage history = new AssistantMessage("", "让我想想", false,
                contentRaw, null, null, null);

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
        Map<String, Object> contentRaw = new LinkedHashMap<>();
        contentRaw.put("thinkingSignature", "sig_abc");

        ToolCall call = new ToolCall("getWeather", "toolu_1", "getWeather", "{}", new HashMap<>());
        AssistantMessage history = new AssistantMessage("", "让我想想", false,
                contentRaw, null, Collections.singletonList(call), null);

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
        AssistantMessage history = new AssistantMessage("", "让我想想", false,
                new LinkedHashMap<String, Object>(), null, null, null);

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
