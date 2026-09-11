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
package org.noear.solon.ai.llm.dialect.dashscope;

import org.junit.jupiter.api.Test;
import org.noear.snack4.ONode;
import org.noear.solon.ai.chat.*;
import org.noear.solon.ai.chat.event.*;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.session.InMemoryChatSession;
import org.noear.solon.ai.chat.tool.ToolCallBuilder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * DashScope 方言的 Event-first 流式契约。
 *
 * <p>流式正文、思考和工具调用直接发射语义事件，并由
 * {@link ChatAccumulator#acceptEvent(ChatEvent)} 聚合；流式结果通过事件与聚合状态验证，
 * 非流式完整消息通过 {@link ChatAccumulator#snapshotTerminal()} 验证。</p>
 *
 * @author noear
 */
public class DashscopeChatEventTest {
    private final DashscopeChatDialect dialect = DashscopeChatDialect.getInstance();
    private final List<ChatEvent> events = new ArrayList<>();

    private ChatStreamContext newCtx() {
        return newCtx(ChatOptions.of());
    }

    private ChatStreamContext newCtx(ChatOptions options) {
        events.clear();

        ChatConfig config = new ChatConfig();
        config.setModel("qwen-plus");
        ChatRequest req = new ChatRequest(config, dialect, options,
                InMemoryChatSession.builder().build(), ChatMessage.ofSystem("test"), null, true);

        return new ChatStreamContextDefault(config, req, new ChatAccumulator(req, true),
                new ChatStreamSession(), 0, events::add);
    }

    private void feed(ChatStreamContext ctx, String data) {
        ctx.getAccumulator().reset();
        dialect.parseResponseJson(ctx, data);
    }

    private List<ChatEvent> eventsOf(ChatEventType type) {
        return events.stream().filter(e -> e.getType() == type).collect(Collectors.toList());
    }

    private List<String> textsOf(ChatEventType type) {
        return eventsOf(type).stream().map(ChatEvent::getText).collect(Collectors.toList());
    }

    private String frame(String messageBody, String finishReason) {
        return "{\"output\":{\"choices\":[{\"finish_reason\":" + finishReason + ","
                + "\"message\":{\"role\":\"assistant\"," + messageBody + "}}]},"
                + "\"usage\":{\"input_tokens\":10,\"output_tokens\":2,\"total_tokens\":12},"
                + "\"request_id\":\"req-1\"}";
    }

    @Test
    public void textDeltaIsEmittedAndAggregated() {
        ChatStreamContext ctx = newCtx();
        ChatAccumulator acc = ctx.getAccumulator();

        feed(ctx, frame("\"content\":\"杭州\"", "null"));
        feed(ctx, frame("\"content\":\"今天晴\"", "\"stop\""));

        assertEquals(java.util.Arrays.asList("杭州", "今天晴"), textsOf(ChatEventType.TEXT_DELTA));
        assertEquals("杭州今天晴", acc.getAggregationText());
        assertTrue(acc.isFinished());
    }

    @Test
    public void multipleChoicesOnlyFirstEntersSingleResultAccumulator() {
        ChatStreamContext ctx = newCtx();
        feed(ctx, "{\"output\":{\"choices\":["
                + "{\"finish_reason\":\"stop\",\"message\":{\"role\":\"assistant\",\"content\":\"first\"}},"
                + "{\"finish_reason\":\"stop\",\"message\":{\"role\":\"assistant\",\"content\":\"second\"}}]},"
                + "\"request_id\":\"req-1\"}");

        assertEquals("first", ctx.getAccumulator().getAggregationText());
        assertEquals(Collections.singletonList("first"), textsOf(ChatEventType.TEXT_DELTA));
    }

    @Test
    public void reasoningContentDeltaIsEmittedAndAggregated() {
        ChatStreamContext ctx = newCtx();
        ChatAccumulator acc = ctx.getAccumulator();

        feed(ctx, frame("\"content\":\"\",\"reasoning_content\":\"先查天气\"", "null"));

        assertEquals(Collections.singletonList("先查天气"), textsOf(ChatEventType.THINKING_DELTA));
        assertEquals("先查天气", acc.getAggregationThinking());
        assertEquals("reasoning_content", acc.reasoning_field_name);
    }

    @Test
    public void thinkingThenTextSwitchesEventChannel() {
        ChatStreamContext ctx = newCtx();
        ChatAccumulator acc = ctx.getAccumulator();

        feed(ctx, frame("\"content\":\"\",\"reasoning_content\":\"先查天气\"", "null"));
        feed(ctx, frame("\"content\":\"杭州今天晴\"", "null"));

        assertEquals(Collections.singletonList("先查天气"), textsOf(ChatEventType.THINKING_DELTA));
        assertEquals(Collections.singletonList("杭州今天晴"), textsOf(ChatEventType.TEXT_DELTA));
        assertEquals("先查天气", acc.getAggregationThinking());
        assertEquals("杭州今天晴", acc.getAggregationText());
    }

    @Test
    public void toolCallChunksAccumulateByIndex() {
        ChatStreamContext ctx = newCtx();

        feed(ctx, frame("\"content\":\"\",\"tool_calls\":[{\"index\":0,\"id\":\"call_abc\",\"type\":\"function\","
                + "\"function\":{\"name\":\"get_weather\",\"arguments\":\"\"}}]", "null"));
        feed(ctx, frame("\"content\":\"\",\"tool_calls\":[{\"index\":0,\"type\":\"function\","
                + "\"function\":{\"name\":\"\",\"arguments\":\"{\\\"location\\\":\"}}]", "null"));
        feed(ctx, frame("\"content\":\"\",\"tool_calls\":[{\"index\":0,\"type\":\"function\","
                + "\"function\":{\"name\":\"\",\"arguments\":\"\\\"杭州\\\"}\"}}]", "\"tool_calls\""));

        ChatAccumulator acc = ctx.getAccumulator();
        assertEquals(1, eventsOf(ChatEventType.TOOL_CALL_START).size(), "one call must start once");
        assertEquals(java.util.Arrays.asList("{\"location\":", "\"杭州\"}"),
                textsOf(ChatEventType.TOOL_CALL_ARGS_DELTA));
        assertEquals(1, acc.getToolCallBuilders().size(), "chunks of one call must share one builder");

        ToolCallBuilder builder = acc.getToolCallBuilders().get("idx:0");
        assertNotNull(builder, "tool call chunks must be keyed by index");
        assertEquals("call_abc", builder.idBuilder.toString());
        assertEquals("get_weather", builder.nameBuilder.toString());
        assertEquals("{\"location\":\"杭州\"}", builder.argumentsBuilder.toString());
        assertTrue(acc.isFinished());
        assertEquals("tool", acc.getLastFinishReasonNormalized());
    }

    @Test
    public void unknownFrameProducesNothing() {
        ChatStreamContext ctx = newCtx();
        ChatAccumulator acc = ctx.getAccumulator();

        feed(ctx, "{\"output\":{\"choices\":[]},\"some_future_field\":{\"foo\":\"bar\"},\"request_id\":\"req-1\"}");
        feed(ctx, frame("\"some_future_field\":\"x\"", "null"));

        assertTrue(events.isEmpty(), "unknown frames must not emit events");
        assertFalse(acc.isFinished());
        assertTrue(acc.getToolCallBuilders().isEmpty());
        assertEquals("", acc.getAggregationText());
        assertEquals("", acc.getAggregationThinking());
        assertNull(acc.getError());
    }

    @Test
    public void errorFrameEmitsErrorEventOnly() {
        ChatStreamContext ctx = newCtx();

        feed(ctx, "{\"code\":\"InvalidApiKey\",\"message\":\"Invalid API-key provided.\",\"request_id\":\"req-1\"}");

        assertEquals(1, events.size());
        assertSame(ChatEventType.ERROR, events.get(0).getType());
        assertSame(ChatEventGroup.META, events.get(0).getGroup());
        assertNotNull(ctx.getAccumulator().getError());
        assertTrue(ctx.getAccumulator().getError().getMessage().contains("InvalidApiKey"));
    }

    @Test
    public void repeatedSearchSnapshotsEmitTypedResultsOnce() {
        ChatStreamContext ctx = newCtx();
        String searchResults = "\"search_info\":{\"search_results\":["
                + "{\"index\":1,\"title\":\"天气\",\"url\":\"https://example.com/1\","
                + "\"snippet\":\"杭州天气预报\"},"
                + "{\"index\":2,\"title\":\"气象台\",\"url\":\"https://example.com/2\"}]}";

        feed(ctx, "{\"output\":{" + searchResults + ",\"choices\":[{\"finish_reason\":null,"
                + "\"message\":{\"role\":\"assistant\",\"content\":\"杭州\"}}]},\"request_id\":\"req-1\"}");
        feed(ctx, "{\"output\":{" + searchResults + ",\"choices\":[{\"finish_reason\":\"stop\","
                + "\"message\":{\"role\":\"assistant\",\"content\":\"今天晴\"}}]},\"request_id\":\"req-1\"}");

        List<ChatEvent> searchEvents = eventsOf(ChatEventType.SEARCH_RESULT);
        assertEquals(2, searchEvents.size(), "完成帧重复的完整搜索快照不得重复发事件");
        assertEquals(Integer.valueOf(1), searchEvents.get(0).getSearchResult().getIndex());
        assertEquals("杭州天气预报", searchEvents.get(0).getSearchResult().getSnippet());
        assertEquals(Integer.valueOf(2), searchEvents.get(1).getSearchResult().getIndex());
        assertNull(searchEvents.get(1).getSearchResult().getSnippet(), "未明确提供摘要时保持 null");

        ChatAccumulator acc = ctx.getAccumulator();
        AssistantMessage message = acc.snapshotTerminal().getMessage();
        assertNotNull(message);
        assertNotNull(message.getSearchResults());
        assertEquals(2, message.getSearchResults().size());
        assertNull(message.getSearchResultsRaw(), "新解析路径不得写旧 raw 字段");

    }

    @Test
    public void noEmitContextStillAggregatesEvents() {
        ChatConfig config = new ChatConfig();
        config.setModel("qwen-plus");
        ChatRequest req = new ChatRequest(config, dialect, ChatOptions.of(),
                InMemoryChatSession.builder().build(), ChatMessage.ofSystem("test"), null, true);
        ChatAccumulator acc = new ChatAccumulator(req, true);

        assertDoesNotThrow(() -> dialect.parseResponseJson(
                ChatStreamContextDefault.ofNoEmit(config, acc), frame("\"content\":\"hi\"", "null")));
        assertEquals("hi", acc.getAggregationText(), "events must aggregate even without an emitter");
        assertTrue(events.isEmpty());
    }

    @Test
    public void streamRequestShouldEnableIncrementalOutput() {
        ChatConfig config = new ChatConfig();
        config.setModel("qwen-plus");

        ONode streamReq = dialect.buildRequestJson(config, ChatOptions.of(),
                Collections.singletonList(ChatMessage.ofUser("hi")), true);
        assertTrue(streamReq.get("parameters").hasKey("incremental_output"));
        assertTrue(streamReq.get("parameters").get("incremental_output").getBoolean());

        ONode nonStreamReq = dialect.buildRequestJson(config, ChatOptions.of(),
                Collections.singletonList(ChatMessage.ofUser("hi")), false);
        assertFalse(nonStreamReq.get("parameters").hasKey("incremental_output"));
    }

    @Test
    public void userIncrementalOutputOptionShouldWin() {
        ChatConfig config = new ChatConfig();
        config.setModel("qwen-plus");
        ChatOptions options = ChatOptions.of();
        options.optionSet("incremental_output", false);

        ONode req = dialect.buildRequestJson(config, options,
                Collections.singletonList(ChatMessage.ofUser("hi")), true);
        assertFalse(req.get("parameters").get("incremental_output").getBoolean());
    }

    @Test
    public void snapshotTextFramesEmitOnlyNewSuffix() {
        ChatStreamContext ctx = newCtx();
        ChatAccumulator acc = ctx.getAccumulator();

        feed(ctx, frame("\"content\":\"杭州今天天气晴朗\"", "null"));
        feed(ctx, frame("\"content\":\"杭州今天天气晴朗，气温25度\"", "null"));
        feed(ctx, frame("\"content\":\"杭州今天天气晴朗，气温25度\"", "\"stop\""));

        assertEquals(java.util.Arrays.asList("杭州今天天气晴朗", "，气温25度"),
                textsOf(ChatEventType.TEXT_DELTA));
        assertEquals("杭州今天天气晴朗，气温25度", acc.getAggregationText());
        assertTrue(acc.isFinished());
    }

    @Test
    public void snapshotReasoningFramesEmitOnlyNewSuffix() {
        ChatStreamContext ctx = newCtx();
        ChatAccumulator acc = ctx.getAccumulator();

        feed(ctx, frame("\"content\":\"\",\"reasoning_content\":\"先查一下杭州天气\"", "null"));
        feed(ctx, frame("\"content\":\"\",\"reasoning_content\":\"先查一下杭州天气再回答用户\"", "null"));

        assertEquals(java.util.Arrays.asList("先查一下杭州天气", "再回答用户"),
                textsOf(ChatEventType.THINKING_DELTA));
        assertEquals("先查一下杭州天气再回答用户", acc.getAggregationThinking());
    }

    @Test
    public void realDeltaFramesAreNotRewritten() {
        ChatStreamContext ctx = newCtx();
        ChatAccumulator acc = ctx.getAccumulator();

        feed(ctx, frame("\"content\":\"杭州今天天气晴朗\"", "null"));
        feed(ctx, frame("\"content\":\"，气温25度\"", "null"));
        feed(ctx, frame("\"content\":\"，体感舒适\"", "\"stop\""));

        assertEquals(java.util.Arrays.asList("杭州今天天气晴朗", "，气温25度", "，体感舒适"),
                textsOf(ChatEventType.TEXT_DELTA));
        assertEquals("杭州今天天气晴朗，气温25度，体感舒适", acc.getAggregationText());
    }

    @Test
    public void explicitSnapshotToolArgumentsAreExactAndIsolatedByCall() {
        ChatOptions options = ChatOptions.of().optionSet("incremental_output", false);
        ChatStreamContext ctx = newCtx(options);

        // 两个调用故意使用相同函数名，证明隔离身份绝不依赖函数名；短首快照验证确定性模式无阈值。
        feed(ctx, frame("\"content\":\"\",\"tool_calls\":["
                + "{\"index\":0,\"id\":\"call_a\",\"type\":\"function\","
                + "\"function\":{\"name\":\"lookup\",\"arguments\":\"{\\\"a\\\":\"}},"
                + "{\"index\":1,\"id\":\"call_b\",\"type\":\"function\","
                + "\"function\":{\"name\":\"lookup\",\"arguments\":\"{\\\"b\\\":\"}}]", "null"));
        feed(ctx, frame("\"content\":\"\",\"tool_calls\":["
                + "{\"index\":0,\"id\":\"call_a\",\"type\":\"function\","
                + "\"function\":{\"name\":\"lookup\",\"arguments\":\"{\\\"a\\\":1}\"}},"
                + "{\"index\":1,\"id\":\"call_b\",\"type\":\"function\","
                + "\"function\":{\"name\":\"lookup\",\"arguments\":\"{\\\"b\\\":2}\"}}]", "null"));
        // 终帧重复最后完整快照，不能再产生 ARGS_DELTA。
        feed(ctx, frame("\"content\":\"\",\"tool_calls\":["
                + "{\"index\":0,\"id\":\"call_a\",\"type\":\"function\","
                + "\"function\":{\"name\":\"lookup\",\"arguments\":\"{\\\"a\\\":1}\"}},"
                + "{\"index\":1,\"id\":\"call_b\",\"type\":\"function\","
                + "\"function\":{\"name\":\"lookup\",\"arguments\":\"{\\\"b\\\":2}\"}}]", "\"tool_calls\""));

        assertEquals(java.util.Arrays.asList("{\"a\":", "{\"b\":", "1}", "2}"),
                textsOf(ChatEventType.TOOL_CALL_ARGS_DELTA),
                "ARGS_DELTA 拼接必须只包含首快照与后续真实后缀");

        ChatAccumulator acc = ctx.getAccumulator();
        assertEquals("{\"a\":1}", acc.getToolCallBuilders().get("idx:0").argumentsBuilder.toString());
        assertEquals("{\"b\":2}", acc.getToolCallBuilders().get("idx:1").argumentsBuilder.toString());
    }

    @Test
    public void heuristicSnapshotToolArgumentsDropCumulativeSuffixAndDuplicate() {
        ChatStreamContext ctx = newCtx();

        feed(ctx, frame("\"content\":\"\",\"tool_calls\":[{\"index\":0,\"id\":\"call_h\","
                + "\"type\":\"function\",\"function\":{\"name\":\"lookup\","
                + "\"arguments\":\"{\\\"city\\\":\"}}]", "null"));
        feed(ctx, frame("\"content\":\"\",\"tool_calls\":[{\"index\":0,\"id\":\"call_h\","
                + "\"type\":\"function\",\"function\":{\"name\":\"lookup\","
                + "\"arguments\":\"{\\\"city\\\":\\\"杭州\\\"}\"}}]", "null"));
        feed(ctx, frame("\"content\":\"\",\"tool_calls\":[{\"index\":0,\"id\":\"call_h\","
                + "\"type\":\"function\",\"function\":{\"name\":\"lookup\","
                + "\"arguments\":\"{\\\"city\\\":\\\"杭州\\\"}\"}}]", "\"tool_calls\""));

        assertEquals(java.util.Arrays.asList("{\"city\":", "\"杭州\"}"),
                textsOf(ChatEventType.TOOL_CALL_ARGS_DELTA));
        assertEquals("{\"city\":\"杭州\"}",
                ctx.getAccumulator().getToolCallBuilders().get("idx:0").argumentsBuilder.toString());
    }

    @Test
    public void snapshotToolArgumentIdentityCanSwitchFromIdToIndexAndPosition() {
        ChatStreamContext ctx = newCtx();

        feed(ctx, frame("\"content\":\"\",\"tool_calls\":[{\"id\":\"call_late\","
                + "\"function\":{\"name\":\"lookup\",\"arguments\":\"abcdefgh\"}}]", "null"));
        feed(ctx, frame("\"content\":\"\",\"tool_calls\":[{\"index\":0,"
                + "\"function\":{\"arguments\":\"abcdefghA\"}}]", "null"));
        feed(ctx, frame("\"content\":\"\",\"tool_calls\":[{"
                + "\"function\":{\"arguments\":\"abcdefghAB\"}}]", "null"));

        assertEquals(java.util.Arrays.asList("abcdefgh", "A", "B"),
                textsOf(ChatEventType.TOOL_CALL_ARGS_DELTA));
    }

    @Test
    public void snapshotToolArgumentIdentityCanSwitchFromPositionToIdAndIndex() {
        ChatStreamContext ctx = newCtx();

        feed(ctx, frame("\"content\":\"\",\"tool_calls\":[{\"function\":{\"name\":\"lookup\","
                + "\"arguments\":\"abcdefgh\"}}]", "null"));
        feed(ctx, frame("\"content\":\"\",\"tool_calls\":[{\"id\":\"call_late\","
                + "\"function\":{\"arguments\":\"abcdefghA\"}}]", "null"));
        feed(ctx, frame("\"content\":\"\",\"tool_calls\":[{\"index\":0,"
                + "\"function\":{\"arguments\":\"abcdefghAB\"}}]", "null"));

        assertEquals(java.util.Arrays.asList("abcdefgh", "A", "B"),
                textsOf(ChatEventType.TOOL_CALL_ARGS_DELTA));
    }

    @Test
    public void explicitSnapshotToolArgumentsIgnoreAdditionalChoices() {
        ChatOptions options = ChatOptions.of().optionSet("incremental_output", false);
        ChatStreamContext ctx = newCtx(options);

        String first = "{\"output\":{\"choices\":["
                + "{\"finish_reason\":null,\"message\":{\"role\":\"assistant\",\"tool_calls\":["
                + "{\"index\":0,\"function\":{\"name\":\"same\",\"arguments\":\"{\"}}]}},"
                + "{\"finish_reason\":null,\"message\":{\"role\":\"assistant\",\"tool_calls\":["
                + "{\"index\":0,\"function\":{\"name\":\"same\",\"arguments\":\"[\"}}]}}]}}";
        String second = "{\"output\":{\"choices\":["
                + "{\"finish_reason\":null,\"message\":{\"role\":\"assistant\",\"tool_calls\":["
                + "{\"index\":0,\"function\":{\"name\":\"same\",\"arguments\":\"{1}\"}}]}},"
                + "{\"finish_reason\":null,\"message\":{\"role\":\"assistant\",\"tool_calls\":["
                + "{\"index\":0,\"function\":{\"name\":\"same\",\"arguments\":\"[2]\"}}]}}]}}";

        feed(ctx, first);
        feed(ctx, second);

        assertEquals(java.util.Arrays.asList("{", "1}"),
                textsOf(ChatEventType.TOOL_CALL_ARGS_DELTA),
                "单结果模型只消费首个 choice，额外候选不得进入主累积器");
    }

    @Test
    public void nonStreamFramesKeepCompleteMessage() {
        ChatConfig config = new ChatConfig();
        config.setModel("qwen-plus");
        ChatRequest req = new ChatRequest(config, dialect, ChatOptions.of(),
                InMemoryChatSession.builder().build(), ChatMessage.ofSystem("test"), null, false);
        ChatAccumulator acc = new ChatAccumulator(req, false);
        ChatStreamContext ctx = ChatStreamContextDefault.ofNoEmit(config, acc);
        String full = frame("\"content\":\"杭州今天天气晴朗，气温25度\"", "\"stop\"");

        dialect.parseResponseJson(ctx, full);
        assertNotNull(acc.snapshotTerminal().getMessage());
        assertEquals("杭州今天天气晴朗，气温25度", acc.snapshotTerminal().getText());

        acc.reset();
        dialect.parseResponseJson(ctx, full);
        assertNotNull(acc.snapshotTerminal().getMessage());
        assertEquals("杭州今天天气晴朗，气温25度", acc.snapshotTerminal().getText());
    }
}
