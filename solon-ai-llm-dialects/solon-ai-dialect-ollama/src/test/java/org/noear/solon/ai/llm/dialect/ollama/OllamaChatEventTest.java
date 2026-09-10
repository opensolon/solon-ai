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
package org.noear.solon.ai.llm.dialect.ollama;

import org.junit.jupiter.api.Test;
import org.noear.solon.ai.chat.*;
import org.noear.solon.ai.chat.event.*;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.session.InMemoryChatSession;
import org.noear.solon.ai.chat.tool.ToolCall;
import org.noear.solon.ai.chat.tool.ToolCallBuilder;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Ollama 方言的事件序列
 *
 * <p>Ollama chat 流式帧通过语义 {@link ChatEvent} 表达正文、思考与工具调用，
 * 并由 {@link ChatAccumulator#acceptEvent(ChatEvent)} 统一聚合。本测试同时锁定事件序列
 * 与聚合终态，避免同一分片被重复计入。</p>
 *
 * @author noear
 */
public class OllamaChatEventTest {
    private final OllamaChatDialect dialect = OllamaChatDialect.getInstance();

    private final List<ChatEvent> events = new ArrayList<>();

    private ChatStreamContext newCtx() {
        return newCtx(ChatOptions.of());
    }

    private ChatStreamContext newCtx(ChatOptions options) {
        return newCtx(options, true);
    }

    private ChatStreamContext newCtx(ChatOptions options, boolean stream) {
        events.clear();

        ChatConfig config = new ChatConfig();
        config.setModel("qwen3:8b");
        ChatRequest req = new ChatRequest(config, dialect, options,
                InMemoryChatSession.builder().build(), ChatMessage.ofSystem("test"), null, stream);

        return new ChatStreamContextDefault(config, req, new ChatAccumulator(req, stream),
                new ChatStreamSession(), 0, events::add);
    }

    /**
     * 模拟核心的逐帧驱动：帧前 reset，语义事件由流上下文自动归并。
     */
    private void feed(ChatStreamContext ctx, String data) {
        ctx.getAccumulator().reset();
        dialect.parseResponseJson(ctx, data);
    }

    private List<ChatEvent> eventsOf(ChatEventType type) {
        List<ChatEvent> matches = new ArrayList<>();
        for (ChatEvent event : events) {
            if (event.getType() == type) {
                matches.add(event);
            }
        }
        return matches;
    }

    private List<String> textsOf(ChatEventType type) {
        List<String> texts = new ArrayList<>();
        for (ChatEvent event : eventsOf(type)) {
            texts.add(event.getText());
        }
        return texts;
    }

    private List<String> textsOfIndex(ChatEventType type, int index) {
        List<String> texts = new ArrayList<>();
        for (ChatEvent event : eventsOf(type)) {
            if (event.getIndex() == index) {
                texts.add(event.getText());
            }
        }
        return texts;
    }

    /**
     * Ollama /api/chat 流式帧
     */
    private String frame(String messageBody, boolean done) {
        return "{\"model\":\"qwen3:8b\",\"created_at\":\"2025-01-01T00:00:00.000000000Z\","
                + "\"message\":{\"role\":\"assistant\"," + messageBody + "},"
                + "\"done\":" + done + "}";
    }

    /**
     * 文本增量通过事件发出，并进入正文聚合。
     */
    @Test
    public void textDeltaIsEmittedAndAggregated() {
        ChatStreamContext ctx = newCtx();

        feed(ctx, frame("\"content\":\"杭州\"", false));
        feed(ctx, frame("\"content\":\"今天晴\"", false));

        assertEquals(java.util.Arrays.asList("杭州", "今天晴"), textsOf(ChatEventType.TEXT_DELTA));
        assertEquals("杭州今天晴", ctx.getAccumulator().getAggregationText());
        assertEquals("杭州今天晴", ctx.getAccumulator().snapshotTerminal().getText());
    }

    /**
     * Ollama thinking 字段归一到通用思考事件与聚合。
     */
    @Test
    public void thinkingDeltaIsEmittedAndAggregated() {
        ChatStreamContext ctx = newCtx();

        feed(ctx, frame("\"content\":\"\",\"thinking\":\"让我想想\"", false));

        ChatAccumulator acc = ctx.getAccumulator();
        assertEquals(java.util.Collections.singletonList("让我想想"),
                textsOf(ChatEventType.THINKING_DELTA));
        assertEquals("让我想想", acc.getAggregationThinking());
        assertEquals("reasoning", acc.reasoning_field_name);
        assertTrue(acc.in_thinking);
        assertEquals("让我想想", acc.snapshotTerminal().getThinking());
    }

    /**
     * 思考转正文时切换事件通道，同时保留各自聚合结果。
     */
    @Test
    public void thinkingThenTextSwitchesEventChannel() {
        ChatStreamContext ctx = newCtx();

        feed(ctx, frame("\"content\":\"\",\"thinking\":\"让我想想\"", false));
        feed(ctx, frame("\"content\":\"杭州今天晴\"", false));

        ChatAccumulator acc = ctx.getAccumulator();
        assertFalse(acc.in_thinking, "text frame must close the thinking channel");
        assertEquals(java.util.Collections.singletonList("让我想想"),
                textsOf(ChatEventType.THINKING_DELTA));
        assertEquals(java.util.Collections.singletonList("杭州今天晴"),
                textsOf(ChatEventType.TEXT_DELTA));
        assertEquals("让我想想", acc.getAggregationThinking());
        assertEquals("杭州今天晴", acc.getAggregationText());
    }

    /**
     * 工具调用无 id / index 时，以当前帧 tool_calls 数组位置作为稳定身份。
     */
    @Test
    public void toolCallWithoutIdentityUsesArrayPosition() {
        ChatStreamContext ctx = newCtx();

        feed(ctx, frame("\"content\":\"\",\"tool_calls\":[{\"function\":{\"name\":\"get_weather\","
                + "\"arguments\":{\"location\":\"杭州\"}}}]", false));

        ChatAccumulator acc = ctx.getAccumulator();
        assertEquals(1, acc.getToolCallBuilders().size());

        ToolCallBuilder builder = acc.getToolCallBuilders().get("idx:0");
        assertNotNull(builder);
        assertEquals("get_weather", builder.nameBuilder.toString());
        assertEquals("", builder.idBuilder.toString(), "ollama does not carry a tool call id");

        assertEquals(1, eventsOf(ChatEventType.TOOL_CALL_START).size());
        assertEquals(0, eventsOf(ChatEventType.TOOL_CALL_START).get(0).getIndex());
        assertEquals(1, eventsOf(ChatEventType.TOOL_CALL_ARGS_DELTA).size());
        ToolCall call = eventsOf(ChatEventType.TOOL_CALL_START).get(0).getToolCall();
        assertNotNull(call);
        assertEquals("get_weather", call.getName());
        assertEquals("{\"location\":\"杭州\"}",
                eventsOf(ChatEventType.TOOL_CALL_ARGS_DELTA).get(0).getText());
        assertEquals("{\"location\":\"杭州\"}", builder.argumentsBuilder.toString());
    }

    /**
     * 同名并行调用不能按函数名合并；无显式身份时分别落到数组位置。
     */
    @Test
    public void parallelSameNameToolCallsAreIsolatedByPosition() {
        ChatStreamContext ctx = newCtx();

        feed(ctx, frame("\"content\":\"\",\"tool_calls\":["
                + "{\"function\":{\"name\":\"lookup\",\"arguments\":{\"city\":\"杭州\"}}},"
                + "{\"function\":{\"name\":\"lookup\",\"arguments\":{\"city\":\"上海\"}}}]", false));

        ChatAccumulator acc = ctx.getAccumulator();
        assertEquals(2, acc.getToolCallBuilders().size());
        assertEquals("{\"city\":\"杭州\"}",
                acc.getToolCallBuilders().get("idx:0").argumentsBuilder.toString());
        assertEquals("{\"city\":\"上海\"}",
                acc.getToolCallBuilders().get("idx:1").argumentsBuilder.toString());
        assertEquals("lookup", acc.getToolCallBuilders().get("idx:0").nameBuilder.toString());
        assertEquals("lookup", acc.getToolCallBuilders().get("idx:1").nameBuilder.toString());
    }

    /**
     * 显式身份优先级为顶层 index、function.index、id；函数名不参与身份选择。
     */
    @Test
    public void toolCallIdentityUsesProtocolPriority() {
        ChatStreamContext ctx = newCtx();

        feed(ctx, frame("\"content\":\"\",\"tool_calls\":["
                + "{\"index\":3,\"id\":\"call_top\",\"function\":{\"index\":7,\"name\":\"same\",\"arguments\":{\"a\":1}}},"
                + "{\"id\":\"call_function\",\"function\":{\"index\":4,\"name\":\"same\",\"arguments\":{\"b\":2}}},"
                + "{\"id\":\"call_id\",\"function\":{\"name\":\"same\",\"arguments\":{\"c\":3}}}]", false));

        assertNotNull(ctx.getAccumulator().getToolCallBuilders().get("idx:3"));
        assertNotNull(ctx.getAccumulator().getToolCallBuilders().get("idx:4"));
        assertNotNull(ctx.getAccumulator().getToolCallBuilders().get("call_id"));
        assertNull(ctx.getAccumulator().getToolCallBuilders().get("same"));
    }

    /**
     * 累计字符串快照只发布新增后缀，重复终帧不再重复追加。
     */
    @Test
    public void cumulativeToolArgumentSnapshotsBecomeExactDeltas() {
        ChatStreamContext ctx = newCtx(
                ChatOptions.of().optionSet("ollama_tool_arguments_mode", "snapshot"));

        feed(ctx, frame("\"content\":\"\",\"tool_calls\":[{\"index\":0,\"function\":{"
                + "\"name\":\"lookup\",\"arguments\":\"{\\\"city\\\":\\\"杭\"}}]", false));
        feed(ctx, frame("\"content\":\"\",\"tool_calls\":[{\"index\":0,\"function\":{"
                + "\"name\":\"lookup\",\"arguments\":\"{\\\"city\\\":\\\"杭州\\\"}\"}}]", false));
        feed(ctx, frame("\"content\":\"\",\"tool_calls\":[{\"index\":0,\"function\":{"
                + "\"name\":\"lookup\",\"arguments\":\"{\\\"city\\\":\\\"杭州\\\"}\"}}]", true));

        assertEquals(java.util.Arrays.asList("{\"city\":\"杭", "州\"}"),
                textsOf(ChatEventType.TOOL_CALL_ARGS_DELTA));
        assertEquals("{\"city\":\"杭州\"}",
                String.join("", textsOfIndex(ChatEventType.TOOL_CALL_ARGS_DELTA, 0)));
        ToolCallBuilder builder = ctx.getAccumulator().getToolCallBuilders().get("idx:0");
        assertNotNull(builder);
        assertEquals("{\"city\":\"杭州\"}", builder.argumentsBuilder.toString());
        assertEquals("{\"city\":\"杭州\"}",
                ctx.getAccumulator().snapshotTerminal().getToolCalls().get(0).getArgumentsStr());
    }

    /**
     * 结构化对象参数是完整快照；完全重复帧不得重复追加。
     */
    @Test
    public void duplicatedStructuredToolArgumentSnapshotIsDropped() {
        ChatStreamContext ctx = newCtx();
        String body = "\"content\":\"\",\"tool_calls\":[{\"index\":0,\"function\":{"
                + "\"name\":\"lookup\",\"arguments\":{\"city\":\"杭州\"}}}]";

        feed(ctx, frame(body, false));
        feed(ctx, frame(body, true));

        assertEquals(java.util.Collections.singletonList("{\"city\":\"杭州\"}"),
                textsOf(ChatEventType.TOOL_CALL_ARGS_DELTA));
        assertEquals("{\"city\":\"杭州\"}",
                ctx.getAccumulator().getToolCallBuilders().get("idx:0").argumentsBuilder.toString());
    }

    /**
     * 非前缀关系的真实参数增量保持原样，不能按快照误裁剪。
     */
    @Test
    public void genuineToolArgumentDeltasRemainUnchanged() {
        ChatStreamContext ctx = newCtx();

        feed(ctx, frame("\"content\":\"\",\"tool_calls\":[{\"index\":0,\"function\":{"
                + "\"name\":\"lookup\",\"arguments\":\"{\\\"city\\\":\\\"\"}}]", false));
        feed(ctx, frame("\"content\":\"\",\"tool_calls\":[{\"index\":0,\"function\":{"
                + "\"name\":\"lookup\",\"arguments\":\"杭州\\\"}\"}}]", false));

        assertEquals(java.util.Arrays.asList("{\"city\":\"", "杭州\"}"),
                textsOf(ChatEventType.TOOL_CALL_ARGS_DELTA));
        assertEquals("{\"city\":\"杭州\"}",
                ctx.getAccumulator().getToolCallBuilders().get("idx:0").argumentsBuilder.toString());
    }

    /**
     * 默认字符串参数始终按 delta 处理，即使合法增量恰好是已累积内容的长前缀。
     */
    @Test
    public void longPrefixStringDeltaIsNotGuessedAsSnapshot() {
        ChatStreamContext ctx = newCtx();

        feed(ctx, frame("\"content\":\"\",\"tool_calls\":[{\"index\":0,\"function\":{"
                + "\"name\":\"lookup\",\"arguments\":\"abcdefghijklmnop\"}}]", false));
        feed(ctx, frame("\"content\":\"\",\"tool_calls\":[{\"index\":0,\"function\":{"
                + "\"name\":\"lookup\",\"arguments\":\"abcdefghijklmnoXYZ\"}}]", false));

        assertEquals("abcdefghijklmnopabcdefghijklmnoXYZ",
                ctx.getAccumulator().getToolCallBuilders().get("idx:0").argumentsBuilder.toString());
    }
    /**
     * 非流式 thinking + text + media 必须共同保留，且媒体只发一次完成事件。
     */
    @Test
    public void nonStreamThinkingTextAndMediaAreKeptWithSingleMediaDone() {
        ChatStreamContext ctx = newCtx(ChatOptions.of(), false);

        dialect.parseResponseJson(ctx, frame("\"content\":\"最终答案\",\"thinking\":\"推理过程\","
                + "\"images\":[\"https://example.com/a.png\"]", true));

        AssistantMessage message = ctx.getAccumulator().snapshotTerminal().getMessage();
        assertNotNull(message);
        assertEquals("最终答案", message.getTextRaw());
        assertEquals("推理过程", message.getThinkingRaw());
        assertEquals(2, message.getBlocks().size());
        assertEquals(1, eventsOf(ChatEventType.MEDIA_DONE).size());
        assertEquals(1, ctx.getAccumulator().getMediaBlocks().size());
    }

    /**
     * 流式侧车媒体只发一次 MEDIA_DONE，并正确进入终态聚合。
     */
    @Test
    public void streamingMediaEmitsSingleDoneEventAndAggregates() {
        ChatStreamContext ctx = newCtx();

        feed(ctx, frame("\"content\":\"图片\",\"images\":[\"https://example.com/a.png\"]", false));

        assertEquals(1, eventsOf(ChatEventType.MEDIA_DONE).size());
        assertEquals("https://example.com/a.png", eventsOf(ChatEventType.MEDIA_DONE).get(0).getBlock().getContent());
        assertEquals(1, ctx.getAccumulator().getMediaBlocks().size());
        assertEquals(2, ctx.getAccumulator().snapshotTerminal().getBlocks().size());
    }

    /**
     * 结束帧：done=true 时记录完成状态与 usage，不产生空内容事件。
     */
    @Test
    public void doneFrameFinishesWithoutContentEvents() {
        ChatStreamContext ctx = newCtx();

        feed(ctx, "{\"model\":\"qwen3:8b\",\"created_at\":\"2025-01-01T00:00:00.000000000Z\","
                + "\"message\":{\"role\":\"assistant\",\"content\":\"\"},\"done\":true,"
                + "\"done_reason\":\"stop\",\"prompt_eval_count\":10,\"eval_count\":5}");

        ChatAccumulator acc = ctx.getAccumulator();
        assertTrue(acc.isFinished());
        assertNotNull(acc.getUsage());
        assertEquals(10, acc.getUsage().promptTokens());
        assertEquals(5, acc.getUsage().completionTokens());
        assertEquals("stop", acc.getLastFinishReasonNormalized());
        assertTrue(eventsOf(ChatEventType.TEXT_DELTA).isEmpty());
        assertTrue(eventsOf(ChatEventType.THINKING_DELTA).isEmpty());
        assertEquals("", acc.getAggregationText());
        assertEquals("", acc.getAggregationThinking());
    }

    @Test
    public void crossFrameMediaIsIdempotentButSameFrameDuplicatesRemain() {
        ChatStreamContext ctx = newCtx();
        String media = "https://example.com/a.png";

        feed(ctx, frame("\"content\":\"有图\",\"images\":[\"" + media + "\",\"" + media + "\"]", false));
        feed(ctx, frame("\"content\":\"\",\"images\":[\"" + media + "\",\"" + media + "\"]", true));

        assertEquals(2, eventsOf(ChatEventType.MEDIA_DONE).size());
        assertEquals(2, ctx.getAccumulator().getMediaBlocks().size());
    }

    @Test
    public void growingSnapshotKeepsNewSameMediaPart() {
        ChatStreamContext ctx = newCtx();
        String media = "https://example.com/a.png";

        feed(ctx, frame("\"content\":\"\",\"images\":[\"" + media + "\"]", false));
        feed(ctx, frame("\"content\":\"\",\"images\":[\"" + media + "\",\"" + media + "\"]", false));
        feed(ctx, frame("\"content\":\"\",\"images\":[\"" + media + "\",\"" + media + "\"]", true));

        assertEquals(2, eventsOf(ChatEventType.MEDIA_DONE).size());
        assertEquals(2, ctx.getAccumulator().getMediaBlocks().size());
    }

    /**
     * 未建模帧：本方言没有 RAW 通道，要求既不产内容事件也不污染累积器
     */
    @Test
    public void unknownFrameProducesNothing() {
        ChatStreamContext ctx = newCtx();

        //message 里只有未知字段（未来协议扩展）
        feed(ctx, frame("\"some_future_field\":{\"foo\":\"bar\"}", false));

        ChatAccumulator acc = ctx.getAccumulator();
        assertFalse(acc.isFinished());
        assertTrue(acc.getToolCallBuilders().isEmpty());
        assertEquals("", acc.getAggregationText());
        assertEquals("", acc.getAggregationThinking());
        assertNull(acc.getError());

        assertTrue(events.isEmpty(), "unknown frames must not emit events");
    }

    /**
     * 错误帧：唯一主动发射的是 ERROR（META 组）
     */
    @Test
    public void errorFrameEmitsErrorEventOnly() {
        ChatStreamContext ctx = newCtx();

        feed(ctx, "{\"error\":\"model 'qwen3:8b' not found\"}");

        assertEquals(1, events.size());
        assertSame(ChatEventType.ERROR, events.get(0).getType());
        assertSame(ChatEventGroup.META, events.get(0).getGroup());
        assertNotNull(ctx.getAccumulator().getError());
        assertTrue(ctx.getAccumulator().getError().getMessage().contains("not found"));

        assertEquals("", ctx.getAccumulator().getAggregationText());
        assertEquals("", ctx.getAccumulator().getAggregationThinking());
    }

    /**
     * 「不发事件」上下文：解析照常进行，事件被静默丢弃
     */
    @Test
    public void noEmitContextParsesWithoutEvents() {
        events.clear();

        ChatConfig config = new ChatConfig();
        config.setModel("qwen3:8b");
        ChatRequest req = new ChatRequest(config, dialect, ChatOptions.of(),
                InMemoryChatSession.builder().build(), ChatMessage.ofSystem("test"), null, true);
        ChatAccumulator acc = new ChatAccumulator(req, true);

        assertDoesNotThrow(() -> dialect.parseResponseJson(ChatStreamContextDefault.ofNoEmit(acc),
                frame("\"content\":\"hi\"", false)));
        assertEquals("hi", acc.getAggregationText());
        assertEquals("hi", acc.snapshotTerminal().getText());
        assertTrue(events.isEmpty(), "ofNoEmit 上下文不应向 emitter 投递事件");
    }
}
