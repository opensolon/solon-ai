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
 * <p>该方言在 {@code parseResponseJson} 里只解析内容主干：Ollama chat 帧
 * （{@code message} + {@code done}）只承载内容增量，方言自身<b>不</b>发射内容事件，
 * 内容主干仍以内容项形态交给核心 {@code publishItem} 统一转换为
 * TEXT_DELTA / THINKING_DELTA / TOOL_CALL_*。本测试锁定「不双发」契约与工具调用累积。</p>
 *
 * @author noear
 */
public class OllamaChatEventTest {
    private final OllamaChatDialect dialect = OllamaChatDialect.getInstance();

    private final List<ChatEvent> events = new ArrayList<>();

    private ChatStreamContext newCtx() {
        events.clear();

        ChatConfig config = new ChatConfig();
        config.setModel("qwen3:8b");
        ChatRequest req = new ChatRequest(config, dialect, ChatOptions.of(),
                InMemoryChatSession.builder().build(), ChatMessage.ofSystem("test"), null, true);

        return new ChatStreamContextDefault(config, req, new ChatAccumulator(req, true),
                new ChatStreamSession(), 0, events::add);
    }

    /**
     * 模拟核心的逐帧驱动：帧前 reset，解析后按核心 {@code buildToolCallBuilder} 的规则
     * 把工具调用累积进 {@code acc.getToolCallBuilders()}（核心该方法为私有，此处镜像同一规则）。
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

    /**
     * Ollama /api/chat 流式帧
     */
    private String frame(String messageBody, boolean done) {
        return "{\"model\":\"qwen3:8b\",\"created_at\":\"2025-01-01T00:00:00.000000000Z\","
                + "\"message\":{\"role\":\"assistant\"," + messageBody + "},"
                + "\"done\":" + done + "}";
    }

    /**
     * 文本增量：内容仍走内容项，方言不发内容事件
     */
    @Test
    public void textDeltaStillGoesThroughChoiceOnly() {
        ChatStreamContext ctx = newCtx();

        feed(ctx, frame("\"content\":\"杭州\"", false));
        assertTrue(ctx.getAccumulator().hasContentItems());
        assertEquals("杭州", ctx.getAccumulator().lastItem().getTextRaw());

        feed(ctx, frame("\"content\":\"今天晴\"", false));
        assertEquals("今天晴", ctx.getAccumulator().lastItem().getTextRaw());

        assertTrue(events.isEmpty(), "content frames must not emit dialect events");
        assertNoContentEvents();
    }

    /**
     * 思考增量：Ollama think 模式字段为 thinking，映射到通用 reasoning 管线后仍走内容项
     */
    @Test
    public void thinkingDeltaStillGoesThroughChoice() {
        ChatStreamContext ctx = newCtx();

        feed(ctx, frame("\"content\":\"\",\"thinking\":\"让我想想\"", false));

        ChatAccumulator acc = ctx.getAccumulator();
        assertTrue(acc.hasContentItems());
        //首帧思考：开启信号帧 + 思考分片帧
        assertEquals(2, acc.getContentItems().size());
        assertTrue(acc.lastItem().isThinking());
        assertEquals("让我想想", acc.lastItem().getThinkingRaw());
        //thinking 归一到 reasoning 字段名（回传时按该字段写出）
        assertEquals("reasoning", acc.reasoning_field_name);
        assertTrue(acc.in_thinking);

        assertNoContentEvents();
    }

    /**
     * 思考 → 正文的通道切换仍只有 choice
     */
    @Test
    public void thinkingThenTextStillGoesThroughChoice() {
        ChatStreamContext ctx = newCtx();

        feed(ctx, frame("\"content\":\"\",\"thinking\":\"让我想想\"", false));
        feed(ctx, frame("\"content\":\"杭州今天晴\"", false));

        ChatAccumulator acc = ctx.getAccumulator();
        assertFalse(acc.in_thinking, "text frame must close the thinking channel");
        assertEquals(2, acc.getContentItems().size());
        assertTrue(acc.getContentItems().get(0).isThinking());
        assertEquals("杭州今天晴", acc.lastItem().getTextRaw());

        assertNoContentEvents();
    }

    /**
     * 工具调用：Ollama 不分片下发 arguments（一帧给出完整对象，且无 id / index），
     * 方言以函数名为聚合主键；核心据此累积出唯一 builder
     */
    @Test
    public void toolCallAccumulatesByFunctionName() {
        ChatStreamContext ctx = newCtx();

        feed(ctx, frame("\"content\":\"\",\"tool_calls\":[{\"function\":{\"name\":\"get_weather\","
                + "\"arguments\":{\"location\":\"杭州\"}}}]", false));

        ChatAccumulator acc = ctx.getAccumulator();
        assertEquals(1, acc.getToolCallBuilders().size());

        ToolCallBuilder builder = acc.getToolCallBuilders().get("get_weather");
        assertNotNull(builder, "ollama tool calls are keyed by function name (no id/index in protocol)");
        assertEquals("get_weather", builder.nameBuilder.toString());
        assertEquals("", builder.idBuilder.toString(), "ollama does not carry a tool call id");

        //参数已解析成结构化 map（不走字符串分片累积）
        ToolCall call = acc.lastItem().getToolCalls().get(0);
        assertEquals("get_weather", call.getName());
        assertEquals("杭州", call.getArguments().get("location"));

        assertNoContentEvents();
    }

    /**
     * 两个工具调用在同一帧：按函数名隔离出两个 builder
     */
    @Test
    public void parallelToolCallsAreIsolatedByName() {
        ChatStreamContext ctx = newCtx();

        feed(ctx, frame("\"content\":\"\",\"tool_calls\":["
                + "{\"function\":{\"name\":\"f1\",\"arguments\":{\"a\":1}}},"
                + "{\"function\":{\"name\":\"f2\",\"arguments\":{\"b\":2}}}]", false));

        ChatAccumulator acc = ctx.getAccumulator();
        assertEquals(2, acc.getToolCallBuilders().size());
        assertNotNull(acc.getToolCallBuilders().get("f1"));
        assertNotNull(acc.getToolCallBuilders().get("f2"));

        assertNoContentEvents();
    }

    /**
     * 结束帧：done=true 时统计 usage 并补位 choice，仍不发内容事件
     */
    @Test
    public void doneFrameFinishesWithoutContentEvents() {
        ChatStreamContext ctx = newCtx();

        feed(ctx, "{\"model\":\"qwen3:8b\",\"created_at\":\"2025-01-01T00:00:00.000000000Z\","
                + "\"message\":{\"role\":\"assistant\",\"content\":\"\"},\"done\":true,"
                + "\"done_reason\":\"stop\",\"prompt_eval_count\":10,\"eval_count\":5}");

        ChatAccumulator acc = ctx.getAccumulator();
        assertTrue(acc.isFinished());
        assertTrue(acc.hasContentItems());
        assertNotNull(acc.getUsage());
        assertEquals(10, acc.getUsage().promptTokens());
        assertEquals(5, acc.getUsage().completionTokens());
        assertEquals("stop", acc.getLastFinishReasonNormalized());

        assertTrue(events.isEmpty(), "done frame must not emit dialect events");
        assertNoContentEvents();
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
        assertFalse(acc.hasContentItems(), "unknown message field must not produce a choice");
        assertFalse(acc.isFinished());
        assertTrue(acc.getToolCallBuilders().isEmpty());
        assertEquals("", acc.getAggregationText());
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

        assertNoContentEvents();
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
        assertTrue(acc.hasContentItems());
        assertTrue(events.isEmpty(), "ofNoEmit 上下文不应产出任何事件");
    }
}
