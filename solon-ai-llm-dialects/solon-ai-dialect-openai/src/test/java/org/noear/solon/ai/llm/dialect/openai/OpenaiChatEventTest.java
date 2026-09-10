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
package org.noear.solon.ai.llm.dialect.openai;

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
 * OpenAI chat/completions 方言的事件序列
 *
 * <p>该方言将内容主干直接翻译成 TEXT_DELTA / THINKING_DELTA / TOOL_CALL_*，
 * 并由 ChatAccumulator 统一归并。本测试锁定事件不重复、工具调用按官方 index 聚合等契约。</p>
 *
 * @author noear
 */
public class OpenaiChatEventTest {
    private final OpenaiChatDialect dialect = OpenaiChatDialect.getInstance();

    private final List<ChatEvent> events = new ArrayList<>();

    private ChatStreamContext newCtx() {
        events.clear();

        ChatConfig config = new ChatConfig();
        config.setModel("gpt-4o-mini");
        ChatRequest req = new ChatRequest(config, dialect, ChatOptions.of(),
                InMemoryChatSession.builder().build(), ChatMessage.ofSystem("test"), null, true);

        return new ChatStreamContextDefault(config, req, new ChatAccumulator(req, true),
                new ChatStreamSession(), 0, events::add);
    }

    /**
     * 模拟核心的逐帧驱动：帧前 reset（清掉上一帧错误），解析产生的 ChatEvent
     * 由 ChatStreamContext 统一归并到 accumulator。
     */
    private void feed(ChatStreamContext ctx, String data) {
        ctx.getAccumulator().reset();
        dialect.parseResponseJson(ctx, data);
    }

    private long countOf(ChatEventType type) {
        long count = 0;
        for (ChatEvent event : events) {
            if (event.is(type)) {
                count++;
            }
        }
        return count;
    }

    private String textChunk(String content) {
        return "{\"id\":\"chatcmpl-1\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"gpt-4o-mini\","
                + "\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"" + content + "\"},"
                + "\"finish_reason\":null}]}";
    }

    /**
     * 文本增量：由方言发出 TEXT_DELTA，并由累积器聚合
     */
    @Test
    public void textDeltaStillGoesThroughChoiceOnly() {
        ChatStreamContext ctx = newCtx();

        feed(ctx, textChunk("杭州"));
        assertEquals("杭州", events.get(events.size() - 1).getText());
        assertEquals("杭州", ctx.getAccumulator().getAggregationText());

        feed(ctx, textChunk("今天晴"));
        assertEquals("今天晴", events.get(events.size() - 1).getText());
        assertEquals("杭州今天晴", ctx.getAccumulator().getAggregationText());
        assertEquals(2, countOf(ChatEventType.TEXT_DELTA));
    }

    @Test
    public void multipleChoicesOnlyFirstEntersSingleResultAccumulator() {
        ChatStreamContext ctx = newCtx();

        feed(ctx, "{\"id\":\"chatcmpl-1\",\"object\":\"chat.completion.chunk\",\"model\":\"gpt-4o-mini\","
                + "\"choices\":["
                + "{\"index\":0,\"delta\":{\"content\":\"first\"},\"finish_reason\":\"stop\"},"
                + "{\"index\":1,\"delta\":{\"content\":\"second\"},\"finish_reason\":\"stop\"}]}");

        assertEquals("first", ctx.getAccumulator().getAggregationText());
        assertEquals(1, countOf(ChatEventType.TEXT_DELTA));
    }

    /**
     * 思考增量（reasoning_content 变体，DeepSeek / 多数兼容端点）：思考仍走内容项
     */
    @Test
    public void reasoningContentDeltaStillGoesThroughChoice() {
        ChatStreamContext ctx = newCtx();

        feed(ctx, "{\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"gpt-4o-mini\","
                + "\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"reasoning_content\":\"先看天气\"},"
                + "\"finish_reason\":null}]}");

        ChatAccumulator acc = ctx.getAccumulator();
        assertEquals("先看天气", acc.getAggregationThinking());
        assertEquals("reasoning_content", acc.reasoning_field_name);

        assertEquals(1, countOf(ChatEventType.THINKING_DELTA));
        assertEquals("先看天气", events.get(events.size() - 1).getText());
    }

    /**
     * 思考增量（reasoning 变体，OpenRouter 等）：字段名归一到 reasoning，内容仍走内容项
     */
    @Test
    public void reasoningDeltaVariantStillGoesThroughChoice() {
        ChatStreamContext ctx = newCtx();

        feed(ctx, "{\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"gpt-4o-mini\","
                + "\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"reasoning\":\"先看天气\"},"
                + "\"finish_reason\":null}]}");

        ChatAccumulator acc = ctx.getAccumulator();
        assertEquals("先看天气", acc.getAggregationThinking());
        assertEquals("reasoning", acc.reasoning_field_name);

        assertEquals(1, countOf(ChatEventType.THINKING_DELTA));
        assertEquals("先看天气", events.get(events.size() - 1).getText());
    }

    /**
     * 思考 → 正文的通道切换：仍只有 choice，没有方言事件
     */
    @Test
    public void thinkingThenTextStillGoesThroughChoice() {
        ChatStreamContext ctx = newCtx();

        feed(ctx, "{\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"gpt-4o-mini\","
                + "\"choices\":[{\"index\":0,\"delta\":{\"reasoning_content\":\"先看天气\"},\"finish_reason\":null}]}");
        assertTrue(ctx.getAccumulator().in_thinking);

        feed(ctx, textChunk("杭州今天晴"));

        ChatAccumulator acc = ctx.getAccumulator();
        assertEquals("先看天气", acc.getAggregationThinking());
        assertEquals("杭州今天晴", acc.getAggregationText());

        assertEquals(1, countOf(ChatEventType.THINKING_DELTA));
        assertEquals(1, countOf(ChatEventType.TEXT_DELTA));
    }

    /**
     * 流式工具调用分片（官方 ChatCompletionMessageToolCallChunk 形态）：
     * id 仅首片携带，index 才是聚合主键，arguments 逐片累积
     */
    @Test
    public void toolCallChunksAccumulateByIndex() {
        ChatStreamContext ctx = newCtx();

        feed(ctx, "{\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"gpt-4o-mini\","
                + "\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":null,"
                + "\"tool_calls\":[{\"index\":0,\"id\":\"call_abc\",\"type\":\"function\","
                + "\"function\":{\"name\":\"get_weather\",\"arguments\":\"\"}}]},\"finish_reason\":null}]}");
        feed(ctx, "{\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"gpt-4o-mini\","
                + "\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":0,"
                + "\"function\":{\"arguments\":\"{\\\"location\\\":\"}}]},\"finish_reason\":null}]}");
        feed(ctx, "{\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"gpt-4o-mini\","
                + "\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":0,"
                + "\"function\":{\"arguments\":\"\\\"杭州\\\"}\"}}]},\"finish_reason\":\"tool_calls\"}]}");

        ChatAccumulator acc = ctx.getAccumulator();
        assertEquals(1, acc.getToolCallBuilders().size(), "chunks of one call must share one builder");

        ToolCallBuilder builder = acc.getToolCallBuilders().get("idx:0");
        assertNotNull(builder, "tool call chunks must be keyed by delta index");
        assertEquals("call_abc", builder.idBuilder.toString());
        assertEquals("get_weather", builder.nameBuilder.toString());
        assertEquals("{\"location\":\"杭州\"}", builder.argumentsBuilder.toString());

        assertTrue(acc.isFinished());
        assertEquals("tool", acc.getLastFinishReasonNormalized());

        assertEquals(1, countOf(ChatEventType.TOOL_CALL_START));
        assertEquals(2, countOf(ChatEventType.TOOL_CALL_ARGS_DELTA));
    }

    /**
     * 两个并行工具调用：按 index 隔离，不串号
     */
    @Test
    public void parallelToolCallChunksAreIsolatedByIndex() {
        ChatStreamContext ctx = newCtx();

        feed(ctx, "{\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"gpt-4o-mini\","
                + "\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":["
                + "{\"index\":0,\"id\":\"call_a\",\"type\":\"function\",\"function\":{\"name\":\"f1\",\"arguments\":\"{\\\"a\\\":\"}},"
                + "{\"index\":1,\"id\":\"call_b\",\"type\":\"function\",\"function\":{\"name\":\"f2\",\"arguments\":\"{\\\"b\\\":\"}}"
                + "]},\"finish_reason\":null}]}");
        feed(ctx, "{\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"gpt-4o-mini\","
                + "\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":["
                + "{\"index\":0,\"function\":{\"arguments\":\"1}\"}},"
                + "{\"index\":1,\"function\":{\"arguments\":\"2}\"}}"
                + "]},\"finish_reason\":null}]}");

        ChatAccumulator acc = ctx.getAccumulator();
        assertEquals(2, acc.getToolCallBuilders().size());
        assertEquals("call_a", acc.getToolCallBuilders().get("idx:0").idBuilder.toString());
        assertEquals("{\"a\":1}", acc.getToolCallBuilders().get("idx:0").argumentsBuilder.toString());
        assertEquals("call_b", acc.getToolCallBuilders().get("idx:1").idBuilder.toString());
        assertEquals("{\"b\":2}", acc.getToolCallBuilders().get("idx:1").argumentsBuilder.toString());

        assertEquals(2, countOf(ChatEventType.TOOL_CALL_START));
        assertEquals(4, countOf(ChatEventType.TOOL_CALL_ARGS_DELTA));
    }

    /**
     * 未建模帧：本方言没有 RAW 通道（无自有事件分支），要求既不产出内容事件也不污染累积器
     */
    @Test
    public void unknownFrameProducesNothing() {
        ChatStreamContext ctx = newCtx();

        //其一：顶层带未知字段、无 choices（形似官方 usage-only chunk 的未来变体）
        feed(ctx, "{\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"gpt-4o-mini\","
                + "\"choices\":[],\"some_future_field\":{\"foo\":\"bar\"}}");

        ChatAccumulator acc = ctx.getAccumulator();
        assertEquals("", acc.getAggregationText(), "unknown frame must not produce text");
        assertFalse(acc.isFinished());
        assertTrue(acc.getToolCallBuilders().isEmpty());
        assertEquals("", acc.getAggregationText());
        assertNull(acc.getError());

        //其二：delta 里只有未知字段
        feed(ctx, "{\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"gpt-4o-mini\","
                + "\"choices\":[{\"index\":0,\"delta\":{\"some_future_field\":\"x\"},\"finish_reason\":null}]}");

        assertEquals("", acc.getAggregationText(), "unknown delta field must not produce text");
        assertTrue(acc.getToolCallBuilders().isEmpty());
        assertNull(acc.getError());

        assertTrue(events.isEmpty(), "unknown frames must not emit events");
    }

    /**
     * 错误帧：唯一由本方言主动发射的事件是 ERROR（META 组），仍不是内容事件
     */
    @Test
    public void errorFrameEmitsErrorEventOnly() {
        ChatStreamContext ctx = newCtx();

        feed(ctx, "{\"error\":{\"message\":\"invalid api key\",\"type\":\"invalid_request_error\"}}");

        assertEquals(1, events.size());
        assertSame(ChatEventType.ERROR, events.get(0).getType());
        assertSame(ChatEventGroup.META, events.get(0).getGroup());
        assertNotNull(ctx.getAccumulator().getError());
        assertTrue(ctx.getAccumulator().getError().getMessage().contains("invalid api key"));

        assertEquals(1, events.size());
    }

    /**
     * 「不发事件」上下文：解析照常进行，事件被静默丢弃
     */
    @Test
    public void noEmitContextParsesWithoutEvents() {
        events.clear();

        ChatConfig config = new ChatConfig();
        config.setModel("gpt-4o-mini");
        ChatRequest req = new ChatRequest(config, dialect, ChatOptions.of(),
                InMemoryChatSession.builder().build(), ChatMessage.ofSystem("test"), null, true);
        ChatAccumulator acc = new ChatAccumulator(req, true);

        assertDoesNotThrow(() -> dialect.parseResponseJson(ChatStreamContextDefault.ofNoEmit(acc), textChunk("hi")));
        assertEquals("hi", acc.getAggregationText());
        assertTrue(events.isEmpty(), "ofNoEmit 上下文不应产出任何事件");
    }

    /**
     * 显式 id 先出现，随后切换到 index 或完全匿名位置时，所有别名仍须共享同一参数快照状态。
     */
    @Test
    public void toolArgumentSnapshotIdentityCanSwitchFromIdToIndexAndPosition() {
        ChatStreamContext ctx = newCtx();
        feed(ctx, "{\"object\":\"chat.completion.chunk\",\"model\":\"gpt-4o-mini\",\"choices\":["
                + "{\"index\":0,\"delta\":{\"tool_calls\":[{\"id\":\"call_late\","
                + "\"function\":{\"name\":\"lookup\",\"arguments\":\"abcdefgh\"}}]},\"finish_reason\":null}]} ");
        feed(ctx, "{\"object\":\"chat.completion.chunk\",\"model\":\"gpt-4o-mini\",\"choices\":["
                + "{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":0,"
                + "\"function\":{\"arguments\":\"abcdefghA\"}}]},\"finish_reason\":null}]} ");
        feed(ctx, "{\"object\":\"chat.completion.chunk\",\"model\":\"gpt-4o-mini\",\"choices\":["
                + "{\"index\":0,\"delta\":{\"tool_calls\":[{\"function\":{\"arguments\":\"abcdefghAB\"}}]},"
                + "\"finish_reason\":null}]} ");

        assertEquals(java.util.Arrays.asList("abcdefgh", "A", "B"), argumentDeltas(events));
    }

    /**
     * 首片只有数组位置，id 与 index 依次迟到时，后续显式身份须绑定回已有参数快照状态。
     */
    @Test
    public void toolArgumentSnapshotIdentityCanSwitchFromPositionToIdAndIndex() {
        ChatStreamContext ctx = newCtx();
        feed(ctx, "{\"object\":\"chat.completion.chunk\",\"model\":\"gpt-4o-mini\",\"choices\":["
                + "{\"index\":0,\"delta\":{\"tool_calls\":[{\"function\":{\"name\":\"lookup\","
                + "\"arguments\":\"abcdefgh\"}}]},\"finish_reason\":null}]} ");
        feed(ctx, "{\"object\":\"chat.completion.chunk\",\"model\":\"gpt-4o-mini\",\"choices\":["
                + "{\"index\":0,\"delta\":{\"tool_calls\":[{\"id\":\"call_late\","
                + "\"function\":{\"arguments\":\"abcdefghA\"}}]},\"finish_reason\":null}]} ");
        feed(ctx, "{\"object\":\"chat.completion.chunk\",\"model\":\"gpt-4o-mini\",\"choices\":["
                + "{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":0,"
                + "\"function\":{\"arguments\":\"abcdefghAB\"}}]},\"finish_reason\":null}]} ");

        assertEquals(java.util.Arrays.asList("abcdefgh", "A", "B"), argumentDeltas(events));
    }

    /**
     * 参数快照按 choice + call index 隔离：同名并行调用不能共享函数名基准；id 迟到也不能改变既有通道。
     */
    @Test
    public void toolArgumentSnapshotsAreIsolatedByChoiceAndCallIdentity() {
        ChatStreamContext ctx = newCtx();
        feed(ctx, "{\"object\":\"chat.completion.chunk\",\"model\":\"gpt-4o-mini\",\"choices\":["
                + "{\"index\":0,\"delta\":{\"tool_calls\":["
                + "{\"index\":0,\"function\":{\"name\":\"lookup\",\"arguments\":\"abcdefgh\"}},"
                + "{\"index\":1,\"function\":{\"name\":\"lookup\",\"arguments\":\"ijklmnop\"}}"
                + "]},\"finish_reason\":null}]} ");
        feed(ctx, "{\"object\":\"chat.completion.chunk\",\"model\":\"gpt-4o-mini\",\"choices\":["
                + "{\"index\":0,\"delta\":{\"tool_calls\":["
                + "{\"index\":0,\"id\":\"late_a\",\"function\":{\"arguments\":\"abcdefghA\"}},"
                + "{\"index\":1,\"id\":\"late_b\",\"function\":{\"arguments\":\"ijklmnopB\"}}"
                + "]},\"finish_reason\":null}]} ");
        feed(ctx, "{\"object\":\"chat.completion.chunk\",\"model\":\"gpt-4o-mini\",\"choices\":["
                + "{\"index\":0,\"delta\":{\"tool_calls\":["
                + "{\"index\":0,\"function\":{\"arguments\":\"abcdefghA\"}},"
                + "{\"index\":1,\"function\":{\"arguments\":\"ijklmnopB\"}}"
                + "]},\"finish_reason\":\"tool_calls\"}]} ");

        ChatAccumulator acc = ctx.getAccumulator();
        assertEquals("abcdefghA", acc.getToolCallBuilders().get("idx:0").argumentsBuilder.toString());
        assertEquals("ijklmnopB", acc.getToolCallBuilders().get("idx:1").argumentsBuilder.toString());
        assertEquals(java.util.Arrays.asList("abcdefgh", "ijklmnop", "A", "B"), argumentDeltas(events));
        assertEquals("abcdefghA", acc.snapshotTerminal().getToolCalls().get(0).getArgumentsStr());
        assertEquals("ijklmnopB", acc.snapshotTerminal().getToolCalls().get(1).getArgumentsStr());
    }

    /**
     * 单结果响应只消费首个 choice；额外候选的同 index 工具调用不得进入主事件流。
     */
    @Test
    public void additionalChoicesDoNotEnterToolArgumentStream() {
        ChatStreamContext ctx = newCtx();
        feed(ctx, "{\"object\":\"chat.completion.chunk\",\"model\":\"gpt-4o-mini\",\"choices\":["
                + "{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"name\":\"lookup\",\"arguments\":\"choice0-\"}}]},\"finish_reason\":null},"
                + "{\"index\":1,\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"name\":\"lookup\",\"arguments\":\"choice1-\"}}]},\"finish_reason\":null}]} ");
        feed(ctx, "{\"object\":\"chat.completion.chunk\",\"model\":\"gpt-4o-mini\",\"choices\":["
                + "{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"choice0-final\"}}]},\"finish_reason\":null},"
                + "{\"index\":1,\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"choice1-final\"}}]},\"finish_reason\":null}]} ");

        assertEquals(java.util.Arrays.asList("choice0-", "final"), argumentDeltas(events));
    }

    private List<String> argumentDeltas(List<ChatEvent> all) {
        List<String> result = new ArrayList<>();
        for (ChatEvent event : all) {
            if (event.is(ChatEventType.TOOL_CALL_ARGS_DELTA)) {
                result.add(event.getText());
            }
        }
        return result;
    }
}
