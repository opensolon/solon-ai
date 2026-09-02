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
import org.noear.solon.ai.chat.tool.ToolCall;
import org.noear.solon.ai.chat.tool.ToolCallBuilder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * DashScope 方言的事件序列
 *
 * <p>该方言在 {@code parseResponseJson} 里只解析内容主干：原生帧
 * （{@code output.choices[].message}）只承载内容增量，方言自身<b>不</b>发射内容事件，
 * 内容主干仍以内容项形态交给核心 {@code publishItem} 统一转换为
 * TEXT_DELTA / THINKING_DELTA / TOOL_CALL_*。本测试锁定「不双发」契约与工具分片累积。</p>
 *
 * @author noear
 */
public class DashscopeChatEventTest {
    private final DashscopeChatDialect dialect = DashscopeChatDialect.getInstance();

    private final List<ChatEvent> events = new ArrayList<>();

    private ChatStreamContext newCtx() {
        events.clear();

        ChatConfig config = new ChatConfig();
        config.setModel("qwen-plus");
        ChatRequest req = new ChatRequest(config, dialect, ChatOptions.of(),
                InMemoryChatSession.builder().build(), ChatMessage.ofSystem("test"), null, true);

        return new ChatStreamContextDefault(config, req, new ChatAccumulator(req, true),
                new ChatStreamSession(), 0, events::add);
    }

    /**
     * 模拟核心的逐帧驱动：帧前 reset，解析后按核心 {@code buildToolCallBuilder} 的规则
     * 把工具调用分片累积进 {@code acc.getToolCallBuilders()}（核心该方法为私有，此处镜像同一规则）。
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
     * 原生帧：output.choices[].message（result_format=message）
     */
    private String frame(String messageBody, String finishReason) {
        return "{\"output\":{\"choices\":[{\"finish_reason\":" + finishReason + ","
                + "\"message\":{\"role\":\"assistant\"," + messageBody + "}}]},"
                + "\"usage\":{\"input_tokens\":10,\"output_tokens\":2,\"total_tokens\":12},"
                + "\"request_id\":\"req-1\"}";
    }

    /**
     * 文本增量：内容仍走内容项，方言不发内容事件
     */
    @Test
    public void textDeltaStillGoesThroughChoiceOnly() {
        ChatStreamContext ctx = newCtx();

        feed(ctx, frame("\"content\":\"杭州\"", "null"));
        assertTrue(ctx.getAccumulator().hasContentItems());
        assertEquals("杭州", ctx.getAccumulator().lastItem().getTextRaw());

        feed(ctx, frame("\"content\":\"今天晴\"", "\"stop\""));
        assertEquals("今天晴", ctx.getAccumulator().lastItem().getTextRaw());
        assertTrue(ctx.getAccumulator().isFinished());

        assertTrue(events.isEmpty(), "content frames must not emit dialect events");
        assertNoContentEvents();
    }

    /**
     * 思考增量：百炼用 reasoning_content，仍走内容项
     */
    @Test
    public void reasoningContentDeltaStillGoesThroughChoice() {
        ChatStreamContext ctx = newCtx();

        feed(ctx, frame("\"content\":\"\",\"reasoning_content\":\"先查天气\"", "null"));

        ChatAccumulator acc = ctx.getAccumulator();
        assertTrue(acc.hasContentItems());
        //首帧思考：开启信号帧 + 思考分片帧
        assertEquals(2, acc.getContentItems().size());
        assertTrue(acc.lastItem().isThinking());
        assertEquals("先查天气", acc.lastItem().getThinkingRaw());
        assertEquals("reasoning_content", acc.reasoning_field_name);
        assertTrue(acc.in_thinking);

        assertNoContentEvents();
    }

    /**
     * 思考 → 正文的通道切换仍只有 choice
     */
    @Test
    public void thinkingThenTextStillGoesThroughChoice() {
        ChatStreamContext ctx = newCtx();

        feed(ctx, frame("\"content\":\"\",\"reasoning_content\":\"先查天气\"", "null"));
        feed(ctx, frame("\"content\":\"杭州今天晴\"", "null"));

        ChatAccumulator acc = ctx.getAccumulator();
        assertFalse(acc.in_thinking, "text frame must close the thinking channel");
        assertEquals(2, acc.getContentItems().size());
        assertTrue(acc.getContentItems().get(0).isThinking());
        assertEquals("杭州今天晴", acc.lastItem().getTextRaw());

        assertNoContentEvents();
    }

    /**
     * 流式工具调用分片：DashScope 的 tool_calls 与 OpenAI 兼容形态一致（带 index，arguments 分片下发）
     */
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
        assertEquals(1, acc.getToolCallBuilders().size(), "chunks of one call must share one builder");

        ToolCallBuilder builder = acc.getToolCallBuilders().get("idx:0");
        assertNotNull(builder, "tool call chunks must be keyed by index");
        assertEquals("call_abc", builder.idBuilder.toString());
        assertEquals("get_weather", builder.nameBuilder.toString());
        assertEquals("{\"location\":\"杭州\"}", builder.argumentsBuilder.toString());

        assertTrue(acc.isFinished());
        assertEquals("tool", acc.getLastFinishReasonNormalized());

        assertNoContentEvents();
    }

    /**
     * 未建模帧：本方言没有 RAW 通道，要求既不产内容事件也不污染累积器
     */
    @Test
    public void unknownFrameProducesNothing() {
        ChatStreamContext ctx = newCtx();

        feed(ctx, "{\"output\":{\"choices\":[]},\"some_future_field\":{\"foo\":\"bar\"},\"request_id\":\"req-1\"}");

        ChatAccumulator acc = ctx.getAccumulator();
        assertFalse(acc.hasContentItems(), "unknown frame must not produce a choice");
        assertFalse(acc.isFinished());
        assertTrue(acc.getToolCallBuilders().isEmpty());
        assertEquals("", acc.getAggregationText());
        assertNull(acc.getError());

        //message 里只有未知字段
        feed(ctx, frame("\"some_future_field\":\"x\"", "null"));

        assertFalse(acc.hasContentItems(), "unknown message field must not produce a choice");
        assertTrue(acc.getToolCallBuilders().isEmpty());
        assertNull(acc.getError());

        assertTrue(events.isEmpty(), "unknown frames must not emit events");
    }

    /**
     * 错误帧（百炼错误形态 code/message）：唯一主动发射的是 ERROR（META 组）
     */
    @Test
    public void errorFrameEmitsErrorEventOnly() {
        ChatStreamContext ctx = newCtx();

        feed(ctx, "{\"code\":\"InvalidApiKey\",\"message\":\"Invalid API-key provided.\",\"request_id\":\"req-1\"}");

        assertEquals(1, events.size());
        assertSame(ChatEventType.ERROR, events.get(0).getType());
        assertSame(ChatEventGroup.META, events.get(0).getGroup());
        assertNotNull(ctx.getAccumulator().getError());
        assertTrue(ctx.getAccumulator().getError().getMessage().contains("InvalidApiKey"));

        assertNoContentEvents();
    }

    /**
     * 「不发事件」上下文：解析照常进行，事件被静默丢弃
     */
    @Test
    public void noEmitContextParsesWithoutEvents() {
        events.clear();

        ChatConfig config = new ChatConfig();
        config.setModel("qwen-plus");
        ChatRequest req = new ChatRequest(config, dialect, ChatOptions.of(),
                InMemoryChatSession.builder().build(), ChatMessage.ofSystem("test"), null, true);
        ChatAccumulator acc = new ChatAccumulator(req, true);

        assertDoesNotThrow(() -> dialect.parseResponseJson(
                ChatStreamContextDefault.ofNoEmit(config, acc), frame("\"content\":\"hi\"", "null")));
        assertTrue(acc.hasContentItems());
        assertTrue(events.isEmpty(), "ofNoEmit 上下文不应产出任何事件");
    }

    /// ////////////////////////// 增量输出（非增量帧不得重复累加）

    /**
     * 请求侧：原生协议 parameters.incremental_output 缺省为 false（每帧全量快照），
     * 与核心「逐帧追加」的累积语义叠加会重复累加，因此流式必须显式开启；非流式不写。
     */
    @Test
    public void streamRequestShouldEnableIncrementalOutput() {
        ChatConfig config = new ChatConfig();
        config.setModel("qwen-plus");

        ONode streamReq = dialect.buildRequestJson(config, ChatOptions.of(),
                Collections.singletonList(ChatMessage.ofUser("hi")), true);
        assertTrue(streamReq.get("parameters").hasKey("incremental_output"),
                "stream request must carry parameters.incremental_output");
        assertTrue(streamReq.get("parameters").get("incremental_output").getBoolean());

        ONode nonStreamReq = dialect.buildRequestJson(config, ChatOptions.of(),
                Collections.singletonList(ChatMessage.ofUser("hi")), false);
        assertFalse(nonStreamReq.get("parameters").hasKey("incremental_output"),
                "non-stream request must not carry the stream-only parameter");
    }

    /**
     * 请求侧：用户显式指定时以用户值为准（不覆盖）
     */
    @Test
    public void userIncrementalOutputOptionShouldWin() {
        ChatConfig config = new ChatConfig();
        config.setModel("qwen-plus");

        ChatOptions options = ChatOptions.of();
        options.optionSet("incremental_output", false);

        ONode req = dialect.buildRequestJson(config, options,
                Collections.singletonList(ChatMessage.ofUser("hi")), true);
        assertFalse(req.get("parameters").get("incremental_output").getBoolean(),
                "explicit user value must not be overwritten");
    }

    /**
     * 解析侧兜底：incremental_output 未生效（每帧全量快照）时，正文不得逐帧重复累加。
     *
     * <p>快照判定需要累积长度达到阈值（{@code SnapshotDeltaNormalizer#MIN_MATCH_LENGTH}）才启动，
     * 因此用足够长的正文；短增量仍按普通增量处理（见 {@link #textDeltaStillGoesThroughChoiceOnly}）。</p>
     */
    @Test
    public void snapshotTextFramesMustNotAccumulateTwice() {
        ChatStreamContext ctx = newCtx();
        ChatAccumulator acc = ctx.getAccumulator();
        StringBuilder delivered = new StringBuilder();

        feed(ctx, frame("\"content\":\"杭州今天天气晴朗\"", "null"));
        assertEquals("杭州今天天气晴朗", acc.lastItem().getTextRaw());
        delivered.append(acc.lastItem().getTextRaw());

        //第 2 帧是「第 1 帧 + 新增后缀」的全量快照 → 只应交付新增后缀
        feed(ctx, frame("\"content\":\"杭州今天天气晴朗，气温25度\"", "null"));
        assertEquals("，气温25度", acc.lastItem().getTextRaw());
        delivered.append(acc.lastItem().getTextRaw());

        //完成帧重复整段快照 → 不再交付任何正文
        feed(ctx, frame("\"content\":\"杭州今天天气晴朗，气温25度\"", "\"stop\""));
        String tail = acc.lastItem().getTextRaw();
        assertTrue(tail == null || tail.isEmpty(), "duplicated snapshot frame must deliver no text");
        assertTrue(acc.isFinished());

        //逐帧增量拼起来必须正好等于最后一帧的全量快照（既不重复、也不丢字）
        assertEquals("杭州今天天气晴朗，气温25度", delivered.toString());

        assertNoContentEvents();
    }

    /**
     * 解析侧兜底：reasoning_content 的全量快照同样不得重复累加
     */
    @Test
    public void snapshotReasoningFramesMustNotAccumulateTwice() {
        ChatStreamContext ctx = newCtx();
        ChatAccumulator acc = ctx.getAccumulator();

        feed(ctx, frame("\"content\":\"\",\"reasoning_content\":\"先查一下杭州天气\"", "null"));
        assertEquals("先查一下杭州天气", acc.lastItem().getThinkingRaw());

        feed(ctx, frame("\"content\":\"\",\"reasoning_content\":\"先查一下杭州天气再回答用户\"", "null"));
        assertEquals("再回答用户", acc.lastItem().getThinkingRaw());
        assertTrue(acc.in_thinking);

        assertNoContentEvents();
    }

    /**
     * 真增量帧（incremental_output 已生效）不得被改写：本帧不是已交付文本的前缀延伸时按原样透传
     */
    @Test
    public void realDeltaFramesMustNotBeRewritten() {
        ChatStreamContext ctx = newCtx();
        ChatAccumulator acc = ctx.getAccumulator();

        feed(ctx, frame("\"content\":\"杭州今天天气晴朗\"", "null"));
        assertEquals("杭州今天天气晴朗", acc.lastItem().getTextRaw());

        feed(ctx, frame("\"content\":\"，气温25度\"", "null"));
        assertEquals("，气温25度", acc.lastItem().getTextRaw());

        feed(ctx, frame("\"content\":\"，体感舒适\"", "\"stop\""));
        assertEquals("，体感舒适", acc.lastItem().getTextRaw());

        assertNoContentEvents();
    }

    /**
     * 非流式不做快照归一：一帧即全量，截前缀只会丢内容
     */
    @Test
    public void nonStreamFramesMustNeverBeNormalized() {
        ChatConfig config = new ChatConfig();
        config.setModel("qwen-plus");
        ChatRequest req = new ChatRequest(config, dialect, ChatOptions.of(),
                InMemoryChatSession.builder().build(), ChatMessage.ofSystem("test"), null, false);
        ChatAccumulator acc = new ChatAccumulator(req, false);
        ChatStreamContext ctx = ChatStreamContextDefault.ofNoEmit(config, acc);

        String full = frame("\"content\":\"杭州今天天气晴朗，气温25度\"", "\"stop\"");

        dialect.parseResponseJson(ctx, full);
        assertEquals("杭州今天天气晴朗，气温25度", acc.lastItem().getTextRaw());

        //同一份全量再解析一次（模拟复用上下文）：仍应是全量，不能被当成快照截掉前缀
        acc.reset();
        dialect.parseResponseJson(ctx, full);
        assertEquals("杭州今天天气晴朗，气温25度", acc.lastItem().getTextRaw());
    }
}
