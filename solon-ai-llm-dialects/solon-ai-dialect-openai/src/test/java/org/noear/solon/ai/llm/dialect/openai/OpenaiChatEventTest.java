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
 * <p>该方言在 {@code parseResponseJson} 里只解析内容主干：流式帧只承载内容增量，
 * 方言自身<b>不</b>发射内容事件，内容主干仍以内容项形态交给核心 {@code publishItem} 统一转换为
 * TEXT_DELTA / THINKING_DELTA / TOOL_CALL_*。本测试锁定这条转换路径的两个契约：
 * 一是「不双发」（方言侧不出现 TEXT / THINKING / TOOL_CALL 组事件），
 * 二是工具调用分片按官方 {@code index} 主键正确累积。</p>
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
     * 模拟核心的逐帧驱动：帧前 reset（清掉上一帧的分片），解析后按核心 {@code buildToolCallBuilder}
     * 的规则把工具调用分片累积进 {@code acc.getToolCallBuilders()}。
     *
     * <p>核心的累积方法是私有的，这里按同一规则镜像一份，用来断言「方言给出的 ToolCall 分片
     * （index 主键 / id 首片胜出 / arguments 片段）能被正确累积」。</p>
     */
    private void feed(ChatStreamContext ctx, String data) {
        ChatAccumulator acc = ctx.getAccumulator();
        acc.reset();

        dialect.parseResponseJson(ctx, data);

        if (acc.hasContentItems() == false) {
            return;
        }

        AssistantMessage msg = acc.lastItem();
        if (msg == null || msg.getToolCalls() == null || msg.getToolCalls().isEmpty()) {
            return;
        }

        for (ToolCall call : msg.getToolCalls()) {
            ToolCallBuilder builder = acc.getToolCallBuilders()
                    .computeIfAbsent(call.getIndex(), k -> new ToolCallBuilder());

            if (call.getId() != null && builder.idBuilder.length() == 0) {
                builder.idBuilder.append(call.getId());
            }
            if (call.getName() != null) {
                if (builder.nameBuilder.length() == 0) {
                    builder.nameBuilder.append(call.getName());
                } else if (call.getName().contentEquals(builder.nameBuilder) == false) {
                    builder.nameBuilder.append(call.getName());
                }
            }
            if (call.getArgumentsStr() != null) {
                builder.argumentsBuilder.append(call.getArgumentsStr());
            }
        }
    }

    /**
     * 内容事件只能由核心从 choice 转换产出，方言侧不得出现
     */
    private void assertNoContentEvents() {
        for (ChatEvent e : events) {
            assertNotSame(ChatEventGroup.TEXT, e.getGroup(),
                    "dialect must not emit TEXT events (core converts content items)");
            assertNotSame(ChatEventGroup.THINKING, e.getGroup(),
                    "dialect must not emit THINKING events (core converts content items)");
            assertNotSame(ChatEventGroup.TOOL_CALL, e.getGroup(),
                    "dialect must not emit TOOL_CALL events (core converts content items)");
        }
    }

    private String textChunk(String content) {
        return "{\"id\":\"chatcmpl-1\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"gpt-4o-mini\","
                + "\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"" + content + "\"},"
                + "\"finish_reason\":null}]}";
    }

    /**
     * 文本增量：内容仍走内容项，方言不发内容事件
     */
    @Test
    public void textDeltaStillGoesThroughChoiceOnly() {
        ChatStreamContext ctx = newCtx();

        feed(ctx, textChunk("杭州"));
        assertTrue(ctx.getAccumulator().hasContentItems());
        assertEquals("杭州", ctx.getAccumulator().lastItem().getTextRaw());

        feed(ctx, textChunk("今天晴"));
        assertTrue(ctx.getAccumulator().hasContentItems());
        assertEquals("今天晴", ctx.getAccumulator().lastItem().getTextRaw());

        assertTrue(events.isEmpty(), "content frames must not emit dialect events");
        assertNoContentEvents();
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
        assertTrue(acc.hasContentItems());
        //首帧思考：开启信号帧 + 思考分片帧
        assertEquals(2, acc.getContentItems().size());
        assertTrue(acc.lastItem().isThinking());
        assertEquals("先看天气", acc.lastItem().getThinkingRaw());
        assertEquals("reasoning_content", acc.reasoning_field_name);

        assertNoContentEvents();
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
        assertTrue(acc.hasContentItems());
        assertTrue(acc.lastItem().isThinking());
        assertEquals("先看天气", acc.lastItem().getThinkingRaw());
        assertEquals("reasoning", acc.reasoning_field_name);

        assertNoContentEvents();
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
        assertFalse(acc.in_thinking, "text frame must close the thinking channel");
        //闭合信号帧 + 正文帧
        assertEquals(2, acc.getContentItems().size());
        assertTrue(acc.getContentItems().get(0).isThinking());
        assertEquals("杭州今天晴", acc.lastItem().getTextRaw());

        assertNoContentEvents();
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

        assertNoContentEvents();
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

        assertNoContentEvents();
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
        assertFalse(acc.hasContentItems(), "unknown frame must not produce a choice");
        assertFalse(acc.isFinished());
        assertTrue(acc.getToolCallBuilders().isEmpty());
        assertEquals("", acc.getAggregationText());
        assertNull(acc.getError());

        //其二：delta 里只有未知字段
        feed(ctx, "{\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"gpt-4o-mini\","
                + "\"choices\":[{\"index\":0,\"delta\":{\"some_future_field\":\"x\"},\"finish_reason\":null}]}");

        assertFalse(acc.hasContentItems(), "unknown delta field must not produce a choice");
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

        assertNoContentEvents();
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
        assertTrue(acc.hasContentItems());
        assertTrue(events.isEmpty(), "ofNoEmit 上下文不应产出任何事件");
    }
}
