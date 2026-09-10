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
import org.noear.solon.ai.chat.*;
import org.noear.solon.ai.chat.content.ImageBlock;
import org.noear.solon.ai.chat.content.TextBlock;
import org.noear.solon.ai.chat.event.*;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.session.InMemoryChatSession;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Anthropic 方言的事件序列
 *
 * <p>Anthropic 是信息损失最严重的方言：服务端工具被拼成 {@code "[server tool: name]"} 混入正文、
 * 搜索结果内容直接拍平进正文、思考签名寄生 {@code contentRaw}、redacted_thinking 塞进自由 Map、
 * ping 整帧丢弃。本测试锁定这些事件已有独立通道。</p>
 *
 * @author noear
 */
public class AnthropicEventTest {
    private final AnthropicResponseParser parser = new AnthropicResponseParser();

    private final List<ChatEvent> events = new ArrayList<>();

    private ChatStreamContext newCtx() {
        return newCtx(true);
    }

    private ChatStreamContext newNonStreamCtx() {
        return newCtx(false);
    }

    private ChatStreamContext newCtx(boolean stream) {
        events.clear();

        ChatConfig config = new ChatConfig();
        config.setModel("claude-sonnet-4-5");
        ChatRequest req = new ChatRequest(config, AnthropicChatDialect.getInstance(), ChatOptions.of(),
                InMemoryChatSession.builder().build(), ChatMessage.ofSystem("test"), null, stream);

        return new ChatStreamContextDefault(config, req, new ChatAccumulator(req, stream),
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

    private int countOf(ChatEventType type) {
        int count = 0;
        for (ChatEvent event : events) {
            if (event.getType() == type) {
                count++;
            }
        }
        return count;
    }

    /**
     * 心跳：旧实现直接 continue，整帧丢弃
     */
    @Test
    public void pingBecomesHeartbeat() {
        ChatStreamContext ctx = newCtx();

        parser.parseStreamResponse(ctx, "{\"type\":\"ping\"}");

        assertEquals(1, events.size());
        assertSame(ChatEventType.HEARTBEAT, events.get(0).getType());
        assertSame(ChatEventGroup.LIFECYCLE, events.get(0).getGroup());
    }

    /**
     * 思考签名：旧实现只能寄生在 contentRaw 的 "thinkingSignature" 键上
     */
    @Test
    public void signatureDeltaBecomesSignatureEvent() {
        ChatStreamContext ctx = newCtx();

        parser.parseStreamResponse(ctx, "{\"type\":\"content_block_start\",\"index\":0,"
                + "\"content_block\":{\"type\":\"thinking\",\"thinking\":\"\"}}");
        parser.parseStreamResponse(ctx, "{\"type\":\"content_block_delta\",\"index\":0,"
                + "\"delta\":{\"type\":\"signature_delta\",\"signature\":\"sig_abc\"}}");

        ChatEvent e = firstOf(ChatEventType.THINKING_SIGNATURE);
        assertNotNull(e, "signature_delta should emit THINKING_SIGNATURE");
        assertEquals("sig_abc", e.getText());
        assertEquals(0, e.getIndex());
        assertSame(ChatEventGroup.THINKING, e.getGroup());

        parser.parseStreamResponse(ctx, "{\"type\":\"message_stop\"}");
        Object contentRaw = AnthropicMessageStateSupport.resolveData(ctx.getAccumulator().snapshotTerminal().getMessage());
        assertTrue(contentRaw instanceof java.util.Map);
        assertEquals("sig_abc", ((java.util.Map<?, ?>) contentRaw).get("thinkingSignature"));
    }

    /**
     * 安全过滤的推理块：旧实现塞进 contentRaw.redactedThinkingBlocks
     */
    @Test
    public void redactedThinkingBecomesEvent() {
        ChatStreamContext ctx = newCtx();

        parser.parseStreamResponse(ctx, "{\"type\":\"content_block_start\",\"index\":1,"
                + "\"content_block\":{\"type\":\"redacted_thinking\",\"data\":\"b64data\"}}");

        ChatEvent e = firstOf(ChatEventType.THINKING_REDACTED);
        assertNotNull(e, "redacted_thinking should emit THINKING_REDACTED");
        assertEquals("b64data", e.getText());
        assertEquals(1, e.getIndex());
    }

    /**
     * 服务端工具调用：旧实现流式整帧丢弃，非流式拼成 "[server tool: name]"
     */
    @Test
    public void serverToolUseBecomesServerToolStart() {
        ChatStreamContext ctx = newCtx();

        parser.parseStreamResponse(ctx, "{\"type\":\"content_block_start\",\"index\":2,"
                + "\"content_block\":{\"type\":\"server_tool_use\",\"id\":\"srvtoolu_1\",\"name\":\"web_search\"}}");

        ChatEvent e = firstOf(ChatEventType.SERVER_TOOL_START);
        assertNotNull(e, "server_tool_use should emit SERVER_TOOL_START");
        assertEquals("web_search", e.getSubType());
        assertEquals("srvtoolu_1", e.getItemId());
        assertSame(ChatEventGroup.SERVER_TOOL, e.getGroup());

        //关键：仅服务端工具事件时不应生成终态正文消息
        assertNull(ctx.getAccumulator().snapshotTerminal().getMessage());
    }

    /**
     * 服务端工具结果：旧实现把结果内容直接拼进正文，订阅方无法与模型自述区分
     */
    @Test
    public void searchResultBecomesServerToolResult() {
        ChatStreamContext ctx = newCtx();

        parser.parseStreamResponse(ctx, "{\"type\":\"content_block_start\",\"index\":3,"
                + "\"content_block\":{\"type\":\"web_search_tool_result\",\"tool_use_id\":\"srvtoolu_1\","
                + "\"content\":[{\"type\":\"web_search_result\",\"text\":\"杭州今天晴\"}]}}");

        ChatEvent e = firstOf(ChatEventType.SERVER_TOOL_RESULT);
        assertNotNull(e, "web_search_tool_result should emit SERVER_TOOL_RESULT");
        assertEquals("web_search_tool_result", e.getSubType());
        assertEquals("srvtoolu_1", e.getItemId());
        assertEquals("杭州今天晴", e.getText());

        //关键：搜索结果进入类型化终态列表，但不应混入正文
        ChatResponse terminal = ctx.getAccumulator().snapshotTerminal();
        assertNotNull(terminal.getMessage());
        assertEquals("", terminal.getText());
        assertEquals(1, terminal.getSearchResults().size());
        assertNull(terminal.getSearchResults().get(0).getSnippet(),
                "兼容载荷只有 text 时不得把它冒充协议 snippet");
    }

    /**
     * 未建模事件以 RAW 透出，不再静默丢弃
     */
    @Test
    public void unknownEventBecomesRaw() {
        ChatStreamContext ctx = newCtx();

        parser.parseStreamResponse(ctx, "{\"type\":\"some_future_event\",\"foo\":\"bar\"}");

        ChatEvent e = firstOf(ChatEventType.RAW);
        assertNotNull(e, "unknown event should emit RAW");
        assertEquals("some_future_event", e.getRawType());
        assertEquals("bar", e.getRaw().get("foo").getString());
    }

    /**
     * 正文增量通过 TEXT_DELTA 事件直接表达，并由 ChatAccumulator 聚合。
     */
    @Test
    public void textDeltaUsesEventFirstPath() {
        ChatStreamContext ctx = newCtx();

        parser.parseStreamResponse(ctx, "{\"type\":\"content_block_start\",\"index\":0,"
                + "\"content_block\":{\"type\":\"text\",\"text\":\"\"}}");
        parser.parseStreamResponse(ctx, "{\"type\":\"content_block_delta\",\"index\":0,"
                + "\"delta\":{\"type\":\"text_delta\",\"text\":\"hello\"}}");

        ChatEvent delta = firstOf(ChatEventType.TEXT_DELTA);
        assertNotNull(delta);
        assertEquals("hello", delta.getText());
        assertEquals("hello", ctx.getAccumulator().snapshotTerminal().getText());
    }

    /**
     * 思考增量通过 THINKING_DELTA 事件直接表达，并由 ChatAccumulator 聚合。
     */
    @Test
    public void thinkingDeltaUsesEventFirstPath() {
        ChatStreamContext ctx = newCtx();

        parser.parseStreamResponse(ctx, "{\"type\":\"content_block_start\",\"index\":0,"
                + "\"content_block\":{\"type\":\"thinking\",\"thinking\":\"\"}}");
        parser.parseStreamResponse(ctx, "{\"type\":\"content_block_delta\",\"index\":0,"
                + "\"delta\":{\"type\":\"thinking_delta\",\"thinking\":\"让我想想\"}}");

        ChatEvent delta = firstOf(ChatEventType.THINKING_DELTA);
        assertNotNull(delta);
        assertEquals("让我想想", delta.getText());
        assertEquals("让我想想", ctx.getAccumulator().snapshotTerminal().getThinking());
    }

    /**
     * 非流式与流式对称：服务端工具同样走事件通道，不再拼进正文
     *
     * <p>迁移前 {@code call()} 的聚合正文会含 {@code "[server tool: web_search]"} 与搜索结果原文，
     * 而 {@code stream()} 不含——同一服务端行为两条路径语义分叉。</p>
     */
    @Test
    public void nonStreamServerToolBecomesEvents() {
        ChatStreamContext ctx = newNonStreamCtx();

        parser.parseNonStreamResponse(ctx, "{\"model\":\"claude-sonnet-4-5\",\"stop_reason\":\"end_turn\","
                + "\"content\":["
                + "{\"type\":\"server_tool_use\",\"id\":\"srvtoolu_1\",\"name\":\"web_search\"},"
                + "{\"type\":\"web_search_tool_result\",\"tool_use_id\":\"srvtoolu_1\","
                + "\"content\":[{\"type\":\"web_search_result\",\"text\":\"\u676d\u5dde\u4eca\u5929\u6674\"}]},"
                + "{\"type\":\"text\",\"text\":\"\u4eca\u5929\u5929\u6c14\u4e0d\u9519\"}]}");

        ChatEvent start = firstOf(ChatEventType.SERVER_TOOL_START);
        assertNotNull(start, "non-stream server_tool_use should emit SERVER_TOOL_START");
        assertEquals("web_search", start.getSubType());
        assertEquals("srvtoolu_1", start.getItemId());

        ChatEvent result = firstOf(ChatEventType.SERVER_TOOL_RESULT);
        assertNotNull(result, "non-stream tool_result should emit SERVER_TOOL_RESULT");
        assertEquals("srvtoolu_1", result.getItemId());
        assertEquals("\u676d\u5dde\u4eca\u5929\u6674", result.getText());

        //关键：正文只剩模型自述，与流式一致
        ChatResponse terminal = ctx.getAccumulator().snapshotTerminal();
        assertNotNull(terminal.getMessage());
        String content = terminal.getText();
        assertEquals("\u4eca\u5929\u5929\u6c14\u4e0d\u9519", content);
        assertFalse(content.contains("[server tool:"));
        assertFalse(content.contains("\u676d\u5dde\u4eca\u5929\u6674"));
    }

    @Test
    public void nonStreamMcpToolEmitsServerArgsDelta() {
        ChatStreamContext ctx = newNonStreamCtx();
        parser.parseNonStreamResponse(ctx, "{\"model\":\"claude-sonnet-4-5\",\"stop_reason\":\"pause_turn\",\"content\":["
                + "{\"type\":\"mcp_tool_use\",\"id\":\"mcp_1\",\"name\":\"query\",\"input\":{\"q\":1}}]}");
        ChatEvent event = firstOf(ChatEventType.SERVER_TOOL_ARGS_DELTA);
        assertNotNull(event);
        assertEquals("mcp_1", event.getToolCallId());
        assertEquals("{\"q\":1}", event.getText());
    }


    @Test
    public void nonStreamThinkingUsesThinkingDeltaEvent() {
        ChatStreamContext ctx = newNonStreamCtx();

        parser.parseNonStreamResponse(ctx, "{\"model\":\"claude-sonnet-4-5\",\"stop_reason\":\"end_turn\","
                + "\"content\":[{\"type\":\"thinking\",\"thinking\":\"完整思考\",\"signature\":\"sig_ns\"}]}");

        ChatEvent delta = firstOf(ChatEventType.THINKING_DELTA);
        assertNotNull(delta, "非流式 thinking 应与流式 thinking_delta 使用同一事件语义");
        assertEquals("完整思考", delta.getText());
        assertEquals(0, delta.getIndex());
        assertEquals("完整思考", ctx.getAccumulator().snapshotTerminal().getThinking());
    }

    @Test
    public void nonStreamEmptyToolInputsDoNotEmitArgsDelta() {
        ChatStreamContext ctx = newNonStreamCtx();

        parser.parseNonStreamResponse(ctx, "{\"model\":\"claude-sonnet-4-5\",\"stop_reason\":\"tool_use\","
                + "\"content\":["
                + "{\"type\":\"tool_use\",\"id\":\"toolu_empty\",\"name\":\"local\",\"input\":{}},"
                + "{\"type\":\"server_tool_use\",\"id\":\"srv_empty\",\"name\":\"web_search\",\"input\":{}}]}");

        assertEquals(0, countOf(ChatEventType.TOOL_CALL_ARGS_DELTA));
        assertEquals(0, countOf(ChatEventType.SERVER_TOOL_ARGS_DELTA));
        assertEquals(1, countOf(ChatEventType.TOOL_CALL_START));
        assertEquals(1, countOf(ChatEventType.TOOL_CALL_END));
        assertEquals("{}", ctx.getAccumulator().snapshotTerminal().getToolCalls().get(0).getArgumentsStr(),
                "空 input 不发 delta，但终态工具参数仍须规范化为空对象");
    }


    @Test
    public void nonStreamSignatureBecomesEvent() {
        ChatStreamContext ctx = newNonStreamCtx();

        parser.parseNonStreamResponse(ctx, "{\"model\":\"claude-sonnet-4-5\",\"stop_reason\":\"end_turn\","
                + "\"content\":[{\"type\":\"thinking\",\"thinking\":\"\u60f3\u4e00\u4e0b\",\"signature\":\"sig_ns\"}]}");

        ChatEvent e = firstOf(ChatEventType.THINKING_SIGNATURE);
        assertNotNull(e, "non-stream signature should emit THINKING_SIGNATURE");
        assertEquals("sig_ns", e.getText());
        Object contentRaw = AnthropicMessageStateSupport.resolveData(ctx.getAccumulator().snapshotTerminal().getMessage());
        assertTrue(contentRaw instanceof java.util.Map);
        assertEquals("sig_ns", ((java.util.Map<?, ?>) contentRaw).get("thinkingSignature"));
    }

    /**
     * 非流式 redacted_thinking：不再只塞自由 Map
     */
    @Test
    public void nonStreamRedactedThinkingBecomesEvent() {
        ChatStreamContext ctx = newNonStreamCtx();

        parser.parseNonStreamResponse(ctx, "{\"model\":\"claude-sonnet-4-5\",\"stop_reason\":\"end_turn\","
                + "\"content\":[{\"type\":\"redacted_thinking\",\"data\":\"b64ns\"}]}");

        ChatEvent e = firstOf(ChatEventType.THINKING_REDACTED);
        assertNotNull(e, "non-stream redacted_thinking should emit THINKING_REDACTED");
        assertEquals("b64ns", e.getText());
    }

    /**
     * 相同媒体出现在不同 content index 时是两条合法协议项，不能按内容去重。
     */
    @Test
    public void nonStreamDuplicateImagesKeepProtocolIndexes() {
        ChatStreamContext ctx = newNonStreamCtx();

        parser.parseNonStreamResponse(ctx, "{\"model\":\"claude-sonnet-4-5\",\"stop_reason\":\"end_turn\","
                + "\"content\":["
                + "{\"type\":\"image\",\"source\":{\"type\":\"url\",\"url\":\"https://a.dev/same.png\"}},"
                + "{\"type\":\"text\",\"text\":\"两次引用\"},"
                + "{\"type\":\"image\",\"source\":{\"type\":\"url\",\"url\":\"https://a.dev/same.png\"}}]}");

        List<ChatEvent> media = new ArrayList<>();
        for (ChatEvent event : events) {
            if (event.getType() == ChatEventType.MEDIA_DONE) {
                media.add(event);
            }
        }
        assertEquals(2, media.size(), "不同协议 index 的相同媒体都应保留");
        assertEquals(0, media.get(0).getIndex());
        assertEquals(2, media.get(1).getIndex());
        assertEquals(media.get(0).getBlock().getContent(), media.get(1).getBlock().getContent());
        List<org.noear.solon.ai.chat.content.ContentBlock> blocks =
                ctx.getAccumulator().snapshotTerminal().getMessage().getBlocks();
        assertEquals(3, blocks.size(), "终态应保留一条文本块和两条合法重复媒体");
        assertTrue(blocks.get(0) instanceof ImageBlock);
        assertTrue(blocks.get(1) instanceof TextBlock);
        assertEquals("两次引用", blocks.get(1).getContent());
        assertTrue(blocks.get(2) instanceof ImageBlock);
    }

    /**
     * 非流式错误：与流式对称地发 ERROR
     */
    @Test
    public void nonStreamErrorBecomesEvent() {
        ChatStreamContext ctx = newNonStreamCtx();

        parser.parseNonStreamResponse(ctx,
                "{\"error\":{\"type\":\"overloaded_error\",\"message\":\"busy\"}}");

        ChatEvent e = firstOf(ChatEventType.ERROR);
        assertNotNull(e, "non-stream error should emit ERROR");
        assertNotNull(ctx.getAccumulator().getError());
    }

    /**
     * 「不发事件」上下文：解析照常进行，事件被静默丢弃
     *
     * <p>方言单测与仅关心累积结果的调用方都依赖这一降级；{@code ofNoEmit} 让降级显性。</p>
     */
    @Test
    public void noEmitContextParsesWithoutEvents() {
        events.clear();

        ChatConfig config = new ChatConfig();
        ChatRequest req = new ChatRequest(config, AnthropicChatDialect.getInstance(), ChatOptions.of(),
                InMemoryChatSession.builder().build(), ChatMessage.ofSystem("test"), null, true);
        ChatStreamContext ctx = ChatStreamContextDefault.ofNoEmit(config, new ChatAccumulator(req, true));

        assertDoesNotThrow(() -> parser.parseStreamResponse(ctx, "{\"type\":\"ping\"}"));
        assertDoesNotThrow(() -> parser.parseStreamResponse(ctx,
                "{\"type\":\"content_block_start\",\"index\":0,"
                        + "\"content_block\":{\"type\":\"server_tool_use\",\"id\":\"s1\",\"name\":\"web_search\"}}"));

        //这两帧在正常上下文下会发 HEARTBEAT / 服务端工具事件
        assertTrue(events.isEmpty(), "ofNoEmit 上下文不应产出任何事件");
    }
}
