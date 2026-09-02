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
package org.noear.solon.ai.llm.dialect.gemini.interactions;

import org.junit.jupiter.api.Test;
import org.noear.solon.ai.chat.*;
import org.noear.solon.ai.chat.event.*;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.session.InMemoryChatSession;
import org.noear.solon.ai.chat.tool.ToolCall;
import org.noear.solon.ai.llm.dialect.gemini.GeminiInteractionsDialect;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Gemini Interactions 方言的事件序列
 *
 * <p>Interactions 协议已定义 google_search_call / google_search_result 步骤类型，
 * 但旧实现下核心层无对应表达，这些步骤只能落成文本或消失。本测试锁定其独立事件通道。</p>
 *
 * @author noear
 */
public class GeminiInteractionsEventTest {
    private final GeminiInteractionsResponseParser parser = new GeminiInteractionsResponseParser();

    private final List<ChatEvent> events = new ArrayList<>();

    private ChatStreamContext newCtx() {
        events.clear();

        ChatConfig config = new ChatConfig();
        config.setModel("gemini-3-pro");
        ChatRequest req = new ChatRequest(config, GeminiInteractionsDialect.getInstance(), ChatOptions.of(),
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
     * 交互生命周期：旧实现只用于设置 model，整帧丢弃
     */
    @Test
    public void interactionLifecycleBecomesStatus() {
        ChatStreamContext ctx = newCtx();

        parser.parseStreamResponse(ctx, "{\"event_type\":\"interaction.created\","
                + "\"interaction\":{\"id\":\"int_1\",\"model\":\"gemini-3-pro\"}}");

        ChatEvent e = firstOf(ChatEventType.STATUS);
        assertNotNull(e, "interaction.created should emit STATUS");
        assertEquals("int_1", e.getItemId());
        assertSame(ChatEventGroup.LIFECYCLE, e.getGroup());
        assertEquals("interaction.created", e.getRawType());
    }

    /**
     * Google 搜索调用：协议已有步骤类型，旧实现无核心表达
     */
    @Test
    public void googleSearchCallBecomesServerToolStart() {
        ChatStreamContext ctx = newCtx();

        parser.parseStreamResponse(ctx, "{\"event_type\":\"step.start\",\"index\":0,"
                + "\"step\":{\"type\":\"google_search_call\",\"id\":\"gs_1\"}}");

        ChatEvent e = firstOf(ChatEventType.SERVER_TOOL_START);
        assertNotNull(e, "google_search_call should emit SERVER_TOOL_START");
        assertEquals("google_search_call", e.getSubType());
        assertEquals("gs_1", e.getItemId());
        assertEquals(0, e.getIndex());
        assertSame(ChatEventGroup.SERVER_TOOL, e.getGroup());
    }

    /**
     * Google 搜索结果：旧实现只能落成文本或消失
     */
    @Test
    public void googleSearchResultBecomesServerToolResult() {
        ChatStreamContext ctx = newCtx();

        parser.parseStreamResponse(ctx, "{\"event_type\":\"step.stop\",\"index\":1,"
                + "\"step\":{\"type\":\"google_search_result\",\"id\":\"gs_1\"}}");

        ChatEvent e = firstOf(ChatEventType.SERVER_TOOL_RESULT);
        assertNotNull(e, "google_search_result should emit SERVER_TOOL_RESULT");
        assertEquals("google_search_result", e.getSubType());
        assertEquals("gs_1", e.getItemId());
    }

    /**
     * 内容型步骤（text / thought）仍走内容项，方言不重复发射内容事件
     */
    @Test
    public void contentStepsDoNotEmitContentEvents() {
        ChatStreamContext ctx = newCtx();

        parser.parseStreamResponse(ctx, "{\"event_type\":\"step.start\",\"index\":0,"
                + "\"step\":{\"type\":\"text\"}}");
        parser.parseStreamResponse(ctx, "{\"event_type\":\"step.delta\",\"index\":0,"
                + "\"step\":{\"type\":\"text\"},\"delta\":{\"text\":\"hello\"}}");

        for (ChatEvent e : events) {
            assertNotSame(ChatEventType.TEXT_DELTA, e.getType(),
                    "dialect must not emit content events (core converts content items)");
            assertNotSame(ChatEventType.THINKING_DELTA, e.getType());
        }
    }

    /**
     * 未建模事件以 RAW 透出，不再静默丢弃
     */
    @Test
    public void unknownEventBecomesRaw() {
        ChatStreamContext ctx = newCtx();

        parser.parseStreamResponse(ctx, "{\"event_type\":\"interaction.some_future\",\"foo\":\"bar\"}");

        ChatEvent e = firstOf(ChatEventType.RAW);
        assertNotNull(e, "unknown event should emit RAW");
        assertEquals("interaction.some_future", e.getRawType());
        assertEquals("bar", e.getRaw().get("foo").getString());
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
        ChatRequest req = new ChatRequest(config, GeminiInteractionsDialect.getInstance(), ChatOptions.of(),
                InMemoryChatSession.builder().build(), ChatMessage.ofSystem("test"), null, true);
        ChatStreamContext ctx = ChatStreamContextDefault.ofNoEmit(config, new ChatAccumulator(req, true));

        //该帧在正常上下文下会发步骤边界与 Google 搜索事件
        assertDoesNotThrow(() -> parser.parseStreamResponse(ctx,
                "{\"event_type\":\"step.start\",\"index\":0,\"step\":{\"type\":\"google_search_call\",\"id\":\"gs_1\"}}"));

        assertTrue(events.isEmpty(), "ofNoEmit 上下文不应产出任何事件");
    }
    /**
     * 非流式：思考签名与 Google 搜索步骤也要有事件
     *
     * <p>修前非流式分支只收 {@code acc}，物理上发不出任何事件；而这些语义并非流式独有。</p>
     */
    @Test
    public void nonStreamEmitsSignatureAndServerToolEvents() {
        events.clear();

        ChatConfig config = new ChatConfig();
        config.setModel("gemini-3-pro");
        ChatRequest req = new ChatRequest(config, GeminiInteractionsDialect.getInstance(), ChatOptions.of(),
                InMemoryChatSession.builder().build(), ChatMessage.ofSystem("test"), null, false);
        ChatStreamContext ctx = new ChatStreamContextDefault(config, req, new ChatAccumulator(req, false),
                new ChatStreamSession(), 0, events::add);

        parser.parseNonStreamResponse(ctx, "{\"id\":\"v1_1\",\"model\":\"gemini-3-pro\",\"status\":\"completed\","
                + "\"steps\":["
                + "{\"type\":\"thought\",\"summary\":[{\"type\":\"text\",\"text\":\"\u5148\u67e5\u5929\u6c14\"}],\"signature\":\"sig-1\"},"
                + "{\"type\":\"google_search_call\",\"id\":\"gs_1\"},"
                + "{\"type\":\"model_output\",\"content\":[{\"type\":\"text\",\"text\":\"\u676d\u5dde\u4eca\u5929\u6674\"}]}]}");

        ChatEvent sig = firstOf(ChatEventType.THINKING_SIGNATURE);
        assertNotNull(sig, "non-stream must emit THINKING_SIGNATURE");
        assertEquals("sig-1", sig.getText());

        ChatEvent tool = firstOf(ChatEventType.SERVER_TOOL_RESULT);
        assertNotNull(tool, "non-stream must emit SERVER_TOOL_RESULT");
        assertEquals("google_search_call", tool.getSubType());

        //内容主干仍走内容项，方言不重复发射内容事件
        assertNull(firstOf(ChatEventType.TEXT_DELTA));
        assertTrue(ctx.getAccumulator().hasContentItems());
    }

    /**
     * 服务端工具步骤的收尾必须是 RESULT
     *
     * <p>按「是否 start」二态映射时，google_search_call 的 step.stop 会被当成 ARGS_DELTA 发出，
     * 该服务端工具永远等不到配对的结束事件，订阅方状态机只能一直停在「搜索中」。</p>
     */
    @Test
    public void googleSearchCallLifecycleIsStartDeltaResult() {
        ChatStreamContext ctx = newCtx();

        parser.parseStreamResponse(ctx, "{\"event_type\":\"step.start\",\"index\":0,"
                + "\"step\":{\"type\":\"google_search_call\",\"id\":\"gs_1\"}}");
        parser.parseStreamResponse(ctx, "{\"event_type\":\"step.delta\",\"index\":0,"
                + "\"step\":{\"type\":\"google_search_call\",\"id\":\"gs_1\"},\"delta\":{\"type\":\"arguments_delta\",\"arguments\":\"{}\"}}");
        parser.parseStreamResponse(ctx, "{\"event_type\":\"step.stop\",\"index\":0,"
                + "\"step\":{\"type\":\"google_search_call\",\"id\":\"gs_1\"}}");

        List<ChatEventType> serverToolPhases = new ArrayList<>();
        for (ChatEvent e : events) {
            if (e.getGroup() == ChatEventGroup.SERVER_TOOL) {
                serverToolPhases.add(e.getType());
            }
        }

        assertEquals(3, serverToolPhases.size(), "每个 step 帧应恰好一个服务端工具事件");
        assertSame(ChatEventType.SERVER_TOOL_START, serverToolPhases.get(0));
        assertSame(ChatEventType.SERVER_TOOL_ARGS_DELTA, serverToolPhases.get(1));
        assertSame(ChatEventType.SERVER_TOOL_RESULT, serverToolPhases.get(2), "step.stop 必须收口为 RESULT");
    }

    /**
     * 并发隔离：两个流的步骤序号都是 0，跨帧状态必须各自独立
     *
     * <p>解析器由静态单例方言持有。跨帧状态放实例字段时，两个并发请求共用同一张按 step index
     * 分组的表：后开始的流覆盖先开始的流，参数增量拼到别人的工具调用上。</p>
     */
    @Test
    public void concurrentStreamsDoNotShareStepState() {
        ChatAccumulator accA = newAccumulator();
        ChatAccumulator accB = newAccumulator();
        ChatStreamContext ctxA = ChatStreamContextDefault.ofNoEmit(accA);
        ChatStreamContext ctxB = ChatStreamContextDefault.ofNoEmit(accB);

        //两个交互的步骤序号都从 0 开始，交错到达
        parser.parseStreamResponse(ctxA, "{\"event_type\":\"step.start\",\"index\":0,"
                + "\"step\":{\"type\":\"function_call\",\"id\":\"call-a\",\"name\":\"getWeather\"}}");
        parser.parseStreamResponse(ctxB, "{\"event_type\":\"step.start\",\"index\":0,"
                + "\"step\":{\"type\":\"function_call\",\"id\":\"call-b\",\"name\":\"getTime\"}}");

        parser.parseStreamResponse(ctxA, "{\"event_type\":\"step.delta\",\"index\":0,"
                + "\"delta\":{\"type\":\"arguments_delta\",\"arguments\":\"{\\\"city\\\":\\\"hz\\\"}\"}}");
        parser.parseStreamResponse(ctxB, "{\"event_type\":\"step.delta\",\"index\":0,"
                + "\"delta\":{\"type\":\"arguments_delta\",\"arguments\":\"{\\\"city\\\":\\\"bj\\\"}\"}}");

        parser.parseStreamResponse(ctxA, "{\"event_type\":\"step.stop\",\"index\":0}");
        parser.parseStreamResponse(ctxB, "{\"event_type\":\"step.stop\",\"index\":0}");

        ToolCall callA = accA.lastItem().getToolCalls().get(0);
        ToolCall callB = accB.lastItem().getToolCalls().get(0);

        assertEquals("getWeather", callA.getName());
        assertEquals("call-a", callA.getId());
        assertEquals("{\"city\":\"hz\"}", callA.getArgumentsStr());

        assertEquals("getTime", callB.getName());
        assertEquals("call-b", callB.getId());
        assertEquals("{\"city\":\"bj\"}", callB.getArgumentsStr());
    }

    private ChatAccumulator newAccumulator() {
        ChatConfig config = new ChatConfig();
        config.setModel("gemini-3-pro");
        ChatRequest req = new ChatRequest(config, GeminiInteractionsDialect.getInstance(), ChatOptions.of(),
                InMemoryChatSession.builder().build(), ChatMessage.ofSystem("test"), null, true);
        return new ChatAccumulator(req, true);
    }
}
