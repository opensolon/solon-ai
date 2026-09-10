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
import org.noear.solon.ai.chat.content.AudioBlock;
import org.noear.solon.ai.chat.event.*;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.message.MessageProtocolState;
import org.noear.solon.ai.chat.session.InMemoryChatSession;
import org.noear.solon.ai.chat.source.Citation;
import org.noear.solon.ai.chat.tool.ToolCall;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * OpenAI Responses 方言的事件序列
 *
 * <p>验证「旧实现下被整帧丢弃或只能降级成文本」的事件，现在能以显式事件传出。</p>
 *
 * @author noear
 */
public class OpenaiResponsesEventTest {
    private final OpenaiResponsesResponseParser parser = new OpenaiResponsesResponseParser();

    private final List<ChatEvent> events = new ArrayList<>();

    private ChatStreamContext newCtx() {
        events.clear();

        ChatConfig config = new ChatConfig();
        config.setModel("gpt-5.4");
        ChatOptions options = ChatOptions.of();
        ChatRequest req = new ChatRequest(config, OpenaiResponsesDialect.getInstance(), options,
                InMemoryChatSession.builder().build(), ChatMessage.ofSystem("test"), null, true);

        return new ChatStreamContextDefault(config, req, new ChatAccumulator(req, true),
                new ChatStreamSession(), 0, events::add);
    }

    private List<ChatEventType> types() {
        List<ChatEventType> list = new ArrayList<>();
        for (ChatEvent e : events) {
            list.add(e.getType());
        }
        return list;
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
     * 生命周期帧：旧实现只用于内部设置 model，整帧丢弃
     */
    @Test
    public void lifecycleFramesBecomeStatusEvents() {
        ChatStreamContext ctx = newCtx();

        parser.parseStreamResponse(ctx, "{\"type\":\"response.created\",\"response\":{\"id\":\"resp_1\",\"model\":\"gpt-5.4\"}}");
        parser.parseStreamResponse(ctx, "{\"type\":\"response.queued\",\"response\":{\"id\":\"resp_1\",\"model\":\"gpt-5.4\"}}");
        parser.parseStreamResponse(ctx, "{\"type\":\"response.in_progress\",\"response\":{\"id\":\"resp_1\",\"model\":\"gpt-5.4\"}}");

        assertEquals(3, events.size());
        for (ChatEvent e : events) {
            assertSame(ChatEventType.STATUS, e.getType());
            assertSame(ChatEventGroup.LIFECYCLE, e.getGroup());
            assertEquals("resp_1", e.getItemId());
        }
        assertEquals("response.created", events.get(0).getRawType());
        assertEquals("response.queued", events.get(1).getRawType());
    }

    /**
     * 联网搜索：旧实现完全无分支，整帧丢弃
     */
    @Test
    public void webSearchBecomesServerToolEvents() {
        ChatStreamContext ctx = newCtx();

        parser.parseStreamResponse(ctx, "{\"type\":\"response.web_search_call.in_progress\",\"item_id\":\"ws_1\",\"output_index\":0}");
        parser.parseStreamResponse(ctx, "{\"type\":\"response.web_search_call.searching\",\"item_id\":\"ws_1\",\"output_index\":0}");
        parser.parseStreamResponse(ctx, "{\"type\":\"response.web_search_call.completed\",\"item_id\":\"ws_1\",\"output_index\":0}");

        assertEquals(java.util.Arrays.asList(
                ChatEventType.SERVER_TOOL_START,
                ChatEventType.SERVER_TOOL_START,
                ChatEventType.SERVER_TOOL_RESULT), types());

        for (ChatEvent e : events) {
            assertEquals("web_search_call", e.getSubType());
            assertEquals("ws_1", e.getItemId());
            assertSame(ChatEventGroup.SERVER_TOOL, e.getGroup());
        }
    }

    /**
     * 代码执行与 MCP：同样属服务端工具，靠 subType 区分而不膨胀枚举
     */
    @Test
    public void codeInterpreterAndMcpShareServerToolGroup() {
        ChatStreamContext ctx = newCtx();

        parser.parseStreamResponse(ctx, "{\"type\":\"response.code_interpreter_call.in_progress\",\"item_id\":\"ci_1\"}");
        parser.parseStreamResponse(ctx, "{\"type\":\"response.code_interpreter_call_code.delta\",\"item_id\":\"ci_1\",\"delta\":\"print(1)\"}");
        parser.parseStreamResponse(ctx, "{\"type\":\"response.mcp_call.completed\",\"item_id\":\"mcp_1\"}");

        assertEquals(java.util.Arrays.asList(
                ChatEventType.SERVER_TOOL_START,
                ChatEventType.SERVER_TOOL_ARGS_DELTA,
                ChatEventType.SERVER_TOOL_RESULT), types());

        assertEquals("code_interpreter_call", events.get(0).getSubType());
        assertEquals("code_interpreter_call_code", events.get(1).getSubType());
        assertEquals("print(1)", events.get(1).getText());
        assertEquals("mcp_call", events.get(2).getSubType());
    }

    /**
     * 图像渐进帧属媒体语义，不是工具语义
     */
    @Test
    public void partialImageBecomesMediaPartial() {
        ChatStreamContext ctx = newCtx();

        parser.parseStreamResponse(ctx, "{\"type\":\"response.image_generation_call.partial_image\",\"item_id\":\"img_1\","
                + "\"partial_image_b64\":\"QUJD\",\"partial_image_index\":2}");

        assertEquals(1, events.size());
        assertSame(ChatEventType.MEDIA_PARTIAL, events.get(0).getType());
        assertSame(ChatEventGroup.MEDIA, events.get(0).getGroup());
        assertEquals(Integer.valueOf(2), events.get(0).attrAs("partial_image_index"));
        assertNotNull(events.get(0).getBlock());
    }

    /**
     * 拒答：旧实现按普通文本输出，订阅方无法识别；现在文本降级保留 + 专用事件
     */
    @Test
    public void refusalKeepsTextAndAddsEvent() {
        ChatStreamContext ctx = newCtx();

        parser.parseStreamResponse(ctx, "{\"type\":\"response.refusal.delta\",\"delta\":\"I cannot help\"}");

        //文本降级保留（不破坏现有 UI）
        assertEquals("I cannot help", ctx.getAccumulator().getAggregationText());
        assertEquals("I cannot help", ctx.getAccumulator().snapshotTerminal().getMessage().getText());

        //同时有专用事件
        ChatEvent e = firstOf(ChatEventType.REFUSAL_DELTA);
        assertNotNull(e, "refusal should emit REFUSAL_DELTA");
        assertEquals("I cannot help", e.getText());
        assertSame(ChatEventGroup.SAFETY, e.getGroup());
    }

    /**
     * 思考签名：旧实现只能寄生在伪造空 thinking 消息的 metadata 里
     */
    @Test
    public void reasoningEncryptedContentBecomesSignatureEvent() {
        ChatStreamContext ctx = newCtx();

        parser.parseStreamResponse(ctx, "{\"type\":\"response.output_item.added\",\"item\":{\"id\":\"rs_1\",\"type\":\"reasoning\"}}");
        parser.parseStreamResponse(ctx, "{\"type\":\"response.output_item.done\",\"item\":{\"id\":\"rs_1\",\"type\":\"reasoning\",\"encrypted_content\":\"enc_abc\"}}");

        ChatEvent e = firstOf(ChatEventType.THINKING_SIGNATURE);
        assertNotNull(e, "encrypted_content should emit THINKING_SIGNATURE");
        assertEquals("enc_abc", e.getText());
        assertEquals("rs_1", e.getItemId());
        assertSame(ChatEventGroup.THINKING, e.getGroup());
    }

    @Test
    public void completedOnlyReasoningEmitsSignatureOnce() {
        ChatStreamContext ctx = newCtx();
        String completed = "{\"type\":\"response.completed\",\"response\":{\"id\":\"resp_1\",\"output\":[{"
                + "\"id\":\"rs_1\",\"type\":\"reasoning\",\"encrypted_content\":\"enc_final\","
                + "\"summary\":[]}]}}";

        parser.parseStreamResponse(ctx, completed);
        parser.parseStreamResponse(ctx, completed);

        assertEquals(1, types().stream().filter(t -> t == ChatEventType.THINKING_SIGNATURE).count());
        ChatEvent signature = firstOf(ChatEventType.THINKING_SIGNATURE);
        assertEquals("enc_final", signature.getText());
        assertEquals("rs_1", signature.getItemId());
        MessageProtocolState state = ctx.getAccumulator().getTerminalProtocolStates()
                .get(OpenaiResponsesMessageStateSupport.PROTOCOL_ID);
        assertNotNull(state);
        assertEquals("enc_final", state.getData()
                .get(OpenaiResponsesMessageStateSupport.REASONING_ENCRYPTED_CONTENT));
    }

    /**
     * 引用标注：旧实现无分支，整帧丢弃
     */
    @Test
    public void annotationBecomesCitation() {
        ChatStreamContext ctx = newCtx();

        parser.parseStreamResponse(ctx, "{\"type\":\"response.output_text.annotation.added\",\"item_id\":\"msg_1\","
                + "\"annotation_index\":0,\"annotation\":{\"type\":\"url_citation\","
                + "\"title\":\"Example\",\"url\":\"https://example.com\",\"cited_text\":\"quoted text\"}}");

        ChatEvent e = firstOf(ChatEventType.CITATION);
        assertNotNull(e, "annotation should emit CITATION");
        assertEquals("https://example.com", e.getText());
        assertEquals(0, e.getIndex());
        Citation citation = e.getCitation();
        assertNotNull(citation, "annotation should expose typed citation");
        assertEquals("url_citation", citation.getType());
        assertEquals("Example", citation.getTitle());
        assertEquals("https://example.com", citation.getUrl());
        assertEquals("quoted text", citation.getCitedText());

        AssistantMessage terminal = ctx.getAccumulator().snapshotTerminal().getMessage();
        assertNotNull(terminal);
        assertEquals(1, terminal.getCitations().size());
        assertSame(citation, terminal.getCitations().get(0));
    }

    /**
     * 未建模事件以 RAW 透出，不再静默丢弃（RAW 默认不投递给订阅方，但已可被拦截器观测）
     */
    @Test
    public void unknownEventBecomesRaw() {
        ChatStreamContext ctx = newCtx();

        parser.parseStreamResponse(ctx, "{\"type\":\"response.some_future_event\",\"foo\":\"bar\"}");

        ChatEvent e = firstOf(ChatEventType.RAW);
        assertNotNull(e, "unknown event should emit RAW");
        assertEquals("response.some_future_event", e.getRawType());
        assertEquals("bar", e.getRaw().get("foo").getString());
    }

    /**
     * OpenAI Responses 的正文增量直接由方言发出 TEXT_DELTA；核心负责归并，避免再经 contentItems 二次投影。
     */
    @Test
    public void textDeltaIsEmittedByDialectAndAggregated() {
        ChatStreamContext ctx = newCtx();

        parser.parseStreamResponse(ctx, "{\"type\":\"response.output_item.added\",\"item\":{\"id\":\"msg_1\",\"type\":\"message\"}}");
        parser.parseStreamResponse(ctx, "{\"type\":\"response.output_text.delta\",\"item_id\":\"msg_1\",\"output_index\":0,\"content_index\":0,\"sequence_number\":3,\"delta\":\"hello\"}");

        ChatEvent event = firstOf(ChatEventType.TEXT_DELTA);
        assertNotNull(event);
        assertEquals("response.output_text.delta", event.getRawType());
        assertEquals("msg_1", event.getItemId());
        assertEquals(0, event.getIndex());
        assertEquals("hello", event.getText());
        assertEquals(Integer.valueOf(0), event.attrAs("output_index"));
        assertEquals(Integer.valueOf(0), event.attrAs("content_index"));
        assertEquals(Long.valueOf(3), event.attrAs("sequence_number"));
        assertEquals("hello", ctx.getAccumulator().getAggregationText());
        assertEquals("hello", ctx.getAccumulator().getAggregationText());
    }

    /**
     * 正文已经由方言直接事件化，且不会再通过内容项重复投影。
     */
    @Test
    public void textDeltaStillGoesThroughChoiceOnly() {
        ChatStreamContext ctx = newCtx();

        parser.parseStreamResponse(ctx, "{\"type\":\"response.output_item.added\",\"item\":{\"id\":\"msg_1\",\"type\":\"message\"}}");
        parser.parseStreamResponse(ctx, "{\"type\":\"response.output_text.delta\",\"delta\":\"hello\"}");

        assertEquals("hello", ctx.getAccumulator().getAggregationText());
        assertEquals("hello", ctx.getAccumulator().getAggregationText());

        ChatEvent text = firstOf(ChatEventType.TEXT_DELTA);
        assertNotNull(text);
        assertEquals("hello", text.getText());
    }

    /**
     * reasoning_text 增量直接由方言发出 THINKING_DELTA，并保留 Responses 索引属性。
     */
    @Test
    public void reasoningTextDeltaIsEmittedByDialectAndAggregated() {
        ChatStreamContext ctx = newCtx();

        parser.parseStreamResponse(ctx, "{\"type\":\"response.output_item.added\",\"output_index\":0,"
                + "\"item\":{\"id\":\"rs_1\",\"type\":\"reasoning\"}}");
        // delta 故意不带 item_id，验证从当前 reasoning item 回退。
        parser.parseStreamResponse(ctx, "{\"type\":\"response.reasoning_text.delta\",\"output_index\":0,"
                + "\"content_index\":1,\"sequence_number\":4,\"delta\":\"thinking\"}");

        ChatEvent event = firstOf(ChatEventType.THINKING_DELTA);
        assertNotNull(event);
        assertEquals("response.reasoning_text.delta", event.getRawType());
        assertEquals("rs_1", event.getItemId());
        assertEquals(0, event.getIndex());
        assertEquals("thinking", event.getText());
        assertEquals(Integer.valueOf(1), event.attrAs("content_index"));
        assertEquals(Long.valueOf(4), event.attrAs("sequence_number"));
        assertEquals("thinking", ctx.getAccumulator().getAggregationThinking());
        assertEquals("rs_1", ctx.getAccumulator().getAggregationMetadata().get(
                OpenaiResponsesMessageStateSupport.AGGREGATION_REASONING_ITEM_ID));
        assertEquals("thinking", ctx.getAccumulator().getAggregationThinking());
    }

    /**
     * reasoning summary 与 reasoning content 使用各自索引命名空间，但都归并为 THINKING_DELTA。
     */
    @Test
    public void reasoningSummaryDeltaIsEmittedByDialectAndAggregated() {
        ChatStreamContext ctx = newCtx();

        parser.parseStreamResponse(ctx, "{\"type\":\"response.output_item.added\",\"output_index\":0,"
                + "\"item\":{\"id\":\"rs_1\",\"type\":\"reasoning\"}}");
        parser.parseStreamResponse(ctx, "{\"type\":\"response.reasoning_summary_text.delta\","
                + "\"item_id\":\"rs_1\",\"output_index\":0,\"summary_index\":2,"
                + "\"sequence_number\":5,\"delta\":\"summary\"}");

        ChatEvent event = firstOf(ChatEventType.THINKING_DELTA);
        assertNotNull(event);
        assertEquals("response.reasoning_summary_text.delta", event.getRawType());
        assertEquals("rs_1", event.getItemId());
        assertEquals(Integer.valueOf(2), event.attrAs("summary_index"));
        assertEquals(Long.valueOf(5), event.attrAs("sequence_number"));
        assertEquals("summary", ctx.getAccumulator().getAggregationThinking());
        assertEquals("summary", ctx.getAccumulator().getAggregationThinking());
    }

    /**
     * done 帧只补齐缺失后缀，仍走 THINKING_DELTA，不回退到 thinking 内容项。
     */
    @Test
    public void reasoningDoneFallbackEmitsOnlyMissingThinkingDelta() {
        ChatStreamContext ctx = newCtx();

        parser.parseStreamResponse(ctx, "{\"type\":\"response.reasoning_text.delta\",\"item_id\":\"rs_1\","
                + "\"content_index\":0,\"delta\":\"think\"}");
        parser.parseStreamResponse(ctx, "{\"type\":\"response.reasoning_text.done\",\"item_id\":\"rs_1\","
                + "\"content_index\":0,\"text\":\"thinking\"}");

        List<String> deltas = new ArrayList<>();
        for (ChatEvent event : events) {
            if (event.getType() == ChatEventType.THINKING_DELTA) {
                deltas.add(event.getText());
            }
        }
        assertEquals(java.util.Arrays.asList("think", "ing"), deltas);
        assertEquals("thinking", ctx.getAccumulator().getAggregationThinking());
        assertEquals("thinking", ctx.getAccumulator().getAggregationThinking());
    }

    /**
     * completed 的 reasoning 快照直接按缺失后缀发事件，重复终态帧保持幂等。
     */
    @Test
    public void completedReasoningFallbackIsEventBasedAndIdempotent() {
        ChatStreamContext ctx = newCtx();
        String completed = "{\"type\":\"response.completed\",\"response\":{\"id\":\"resp_1\",\"output\":[{"
                + "\"id\":\"rs_1\",\"type\":\"reasoning\",\"encrypted_content\":\"enc\","
                + "\"content\":[{\"type\":\"reasoning_text\",\"text\":\"think\"}],\"summary\":[]}]}}";

        parser.parseStreamResponse(ctx, completed);
        parser.parseStreamResponse(ctx, completed);

        assertEquals("think", ctx.getAccumulator().getAggregationThinking());
        assertEquals(1, types().stream().filter(t -> t == ChatEventType.THINKING_DELTA).count());
        ChatEvent thinking = firstOf(ChatEventType.THINKING_DELTA);
        assertEquals("response.reasoning_text.final", thinking.getRawType());
        assertEquals("rs_1", thinking.getItemId());
        MessageProtocolState state = ctx.getAccumulator().snapshotTerminal().getMessage()
                .getProtocolState(OpenaiResponsesMessageStateSupport.PROTOCOL_ID);
        assertNotNull(state);
        assertEquals("enc", state.getData()
                .get(OpenaiResponsesMessageStateSupport.REASONING_ENCRYPTED_CONTENT));
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
        ChatRequest req = new ChatRequest(config, OpenaiResponsesDialect.getInstance(), ChatOptions.of(),
                InMemoryChatSession.builder().build(), ChatMessage.ofSystem("test"), null, true);
        ChatStreamContext ctx = ChatStreamContextDefault.ofNoEmit(config, new ChatAccumulator(req, true));

        //该帧在正常上下文下会发服务端工具事件
        assertDoesNotThrow(() -> parser.parseStreamResponse(ctx,
                "{\"type\":\"response.web_search_call.in_progress\",\"item_id\":\"ws_1\"}"));

        assertTrue(events.isEmpty(), "ofNoEmit 上下文不应产出任何事件");
    }
    /**
     * 非流式上下文（stream=false）
     */
    private ChatStreamContext newNonStreamCtx() {
        events.clear();

        ChatConfig config = new ChatConfig();
        config.setModel("gpt-5.4");
        ChatRequest req = new ChatRequest(config, OpenaiResponsesDialect.getInstance(), ChatOptions.of(),
                InMemoryChatSession.builder().build(), ChatMessage.ofSystem("test"), null, false);

        return new ChatStreamContextDefault(config, req, new ChatAccumulator(req, false),
                new ChatStreamSession(), 0, events::add);
    }

    /**
     * 非流式的引用与拒答：与流式对称
     *
     * <p>这些语义不是流式独有的。修前非流式分支只收 {@code acc}，物理上发不出任何事件，
     * 导致 CITATION / REFUSAL_DELTA / CONTENT_FILTER 全部静默丢失。</p>
     */
    @Test
    public void nonStreamEmitsCitationAndRefusalEvents() {
        ChatStreamContext ctx = newNonStreamCtx();

        parser.parseNonStreamResponse(ctx, "{\"id\":\"resp_1\",\"model\":\"gpt-5.4\",\"status\":\"completed\","
                + "\"output\":[{\"type\":\"message\",\"content\":["
                + "{\"type\":\"output_text\",\"text\":\"\u676d\u5dde\u4eca\u5929\u6674\",\"annotations\":["
                + "{\"type\":\"url_citation\",\"title\":\"Hangzhou Weather\","
                + "\"url\":\"https://weather.example/hz\",\"cited_text\":\"Sunny today\"}]},"
                + "{\"type\":\"refusal\",\"refusal\":\"\u6b64\u8bf7\u6c42\u65e0\u6cd5\u5b8c\u6210\"}]}]}");

        assertTrue(types().contains(ChatEventType.CITATION), "non-stream must emit CITATION");
        ChatEvent citationEvent = firstOf(ChatEventType.CITATION);
        assertEquals("https://weather.example/hz", citationEvent.getText());
        Citation citation = citationEvent.getCitation();
        assertNotNull(citation, "non-stream annotation should expose typed citation");
        assertEquals("url_citation", citation.getType());
        assertEquals("Hangzhou Weather", citation.getTitle());
        assertEquals("https://weather.example/hz", citation.getUrl());
        assertEquals("Sunny today", citation.getCitedText());
        assertEquals("\u6b64\u8bf7\u6c42\u65e0\u6cd5\u5b8c\u6210", firstOf(ChatEventType.REFUSAL_DELTA).getText());
        // 拒答终态只发一次（与流式 response.refusal.done 对称）
        assertEquals(1, types().stream().filter(t -> t == ChatEventType.CONTENT_FILTER).count());

        AssistantMessage terminal = ctx.getAccumulator().snapshotTerminal().getMessage();
        assertNotNull(terminal);
        assertEquals(1, terminal.getCitations().size());
        assertSame(citation, terminal.getCitations().get(0));
    }

    /**
     * 非流式的服务端工具项：旧实现整项丢弃
     */
    @Test
    public void nonStreamEmitsServerToolResult() {
        ChatStreamContext ctx = newNonStreamCtx();

        parser.parseNonStreamResponse(ctx, "{\"id\":\"resp_1\",\"model\":\"gpt-5.4\",\"status\":\"completed\","
                + "\"output\":[{\"type\":\"web_search_call\",\"id\":\"ws_1\"},"
                + "{\"type\":\"message\",\"content\":[{\"type\":\"output_text\",\"text\":\"ok\"}]}]}");

        ChatEvent e = firstOf(ChatEventType.SERVER_TOOL_RESULT);
        assertNotNull(e, "non-stream must emit SERVER_TOOL_RESULT");
        assertEquals("web_search_call", e.getSubType());
        assertEquals("ws_1", e.getItemId());
    }

    @Test
    public void nonStreamFunctionCallKeepsSingleCompleteLifecycle() {
        ChatStreamContext ctx = newNonStreamCtx();

        parser.parseNonStreamResponse(ctx, "{\"id\":\"resp_1\",\"model\":\"gpt-5.4\",\"status\":\"completed\","
                + "\"output\":[{\"id\":\"fc_1\",\"type\":\"function_call\",\"call_id\":\"call_1\","
                + "\"name\":\"weather\",\"arguments\":\"{\\\"city\\\":\\\"hz\\\"}\"}]}");

        assertEquals(1, types().stream().filter(t -> t == ChatEventType.TOOL_CALL_START).count());
        assertEquals(1, types().stream().filter(t -> t == ChatEventType.TOOL_CALL_ARGS_DELTA).count());
        assertEquals(1, types().stream().filter(t -> t == ChatEventType.TOOL_CALL_END).count());
        ChatEvent end = firstOf(ChatEventType.TOOL_CALL_END);
        assertEquals("call_1", end.getToolCallId());
        assertEquals("hz", end.getToolCall().getArguments().get("city"));
        assertEquals(1, ctx.getAccumulator().snapshotTerminal().getToolCalls().size());
    }

    @Test
    public void nonStreamReasoningEmitsSignature() {
        ChatStreamContext ctx = newNonStreamCtx();

        parser.parseNonStreamResponse(ctx, "{\"id\":\"resp_1\",\"model\":\"gpt-5.4\",\"status\":\"completed\","
                + "\"output\":[{\"id\":\"rs_1\",\"type\":\"reasoning\","
                + "\"encrypted_content\":\"enc_non_stream\",\"summary\":[]}]}");

        assertEquals(1, types().stream().filter(t -> t == ChatEventType.THINKING_SIGNATURE).count());
        ChatEvent signature = firstOf(ChatEventType.THINKING_SIGNATURE);
        assertEquals("enc_non_stream", signature.getText());
        assertEquals("rs_1", signature.getItemId());
        MessageProtocolState state = ctx.getAccumulator().snapshotTerminal().getMessage()
                .getProtocolState(OpenaiResponsesMessageStateSupport.PROTOCOL_ID);
        assertNotNull(state);
        assertEquals("enc_non_stream",
                state.getData().get(OpenaiResponsesMessageStateSupport.REASONING_ENCRYPTED_CONTENT));
    }

    /**
     * 非流式 incomplete / cancelled 是供应商状态，failed 仍是错误。
     */
    @Test
    public void nonStreamUsesStatusForIncompleteAndCancelledButErrorForFailed() {
        ChatStreamContext incomplete = newNonStreamCtx();
        parser.parseNonStreamResponse(incomplete, "{\"id\":\"resp_1\",\"status\":\"incomplete\","
                + "\"incomplete_details\":{\"reason\":\"max_output_tokens\"},\"output\":[]}");
        ChatEvent incompleteStatus = firstOf(ChatEventType.STATUS);
        assertNotNull(incompleteStatus, "incomplete must emit STATUS");
        assertEquals("response.incomplete", incompleteStatus.getRawType());
        assertEquals("max_output_tokens", incompleteStatus.getSubType());
        assertEquals("length", incompleteStatus.getText());
        assertEquals("length", incompleteStatus.attrAs("finish_reason"));
        assertEquals("length", incomplete.getAccumulator().lastFinishReason);
        assertFalse(types().contains(ChatEventType.ABORT));

        ChatStreamContext cancelled = newNonStreamCtx();
        parser.parseNonStreamResponse(cancelled, "{\"id\":\"resp_2\",\"status\":\"cancelled\",\"output\":[]}");
        ChatEvent cancelledStatus = firstOf(ChatEventType.STATUS);
        assertNotNull(cancelledStatus, "cancelled must emit STATUS");
        assertEquals("response.cancelled", cancelledStatus.getRawType());
        assertEquals("cancelled", cancelledStatus.getSubType());
        assertEquals("cancelled", cancelled.getAccumulator().lastFinishReason);
        assertFalse(types().contains(ChatEventType.ABORT));

        ChatStreamContext failed = newNonStreamCtx();
        parser.parseNonStreamResponse(failed, "{\"id\":\"resp_3\",\"status\":\"failed\","
                + "\"error\":{\"message\":\"boom\"}}");
        assertNotNull(firstOf(ChatEventType.ERROR), "failed must emit ERROR");
        assertNotNull(failed.getAccumulator().getError());
        assertTrue(failed.getAccumulator().isFinished(), "failed 响应必须结束累积器");
    }

    @Test
    public void nonStreamTerminalStatusIsLastAfterToolMediaAndUsage() {
        ChatStreamContext ctx = newNonStreamCtx();
        parser.parseNonStreamResponse(ctx, "{\"id\":\"resp_cancelled\",\"model\":\"gpt-5.4\","
                + "\"status\":\"cancelled\",\"output\":["
                + "{\"id\":\"ws_1\",\"type\":\"web_search_call\",\"status\":\"completed\"},"
                + "{\"id\":\"msg_1\",\"type\":\"message\",\"content\":["
                + "{\"type\":\"output_audio\",\"data\":\"QUJD\",\"transcript\":\"hello\"}]},"
                + "{\"id\":\"fc_1\",\"type\":\"function_call\",\"call_id\":\"call_1\","
                + "\"name\":\"weather\",\"arguments\":\"{\\\"city\\\":\\\"hz\\\"}\"}],"
                + "\"usage\":{\"input_tokens\":3,\"output_tokens\":2,\"total_tokens\":5}}");

        assertEquals(java.util.Arrays.asList(
                ChatEventType.SERVER_TOOL_RESULT,
                ChatEventType.TEXT_DELTA,
                ChatEventType.MEDIA_DONE,
                ChatEventType.TOOL_CALL_START,
                ChatEventType.TOOL_CALL_ARGS_DELTA,
                ChatEventType.TOOL_CALL_END,
                ChatEventType.STATUS), types());
        assertEquals("response.cancelled", events.get(events.size() - 1).getRawType());
        assertEquals(5, ctx.getAccumulator().getUsage().totalTokens());
        assertEquals("hello", ctx.getAccumulator().snapshotTerminal().getMessage().getText());
    }

    @Test
    public void streamTerminalStatusFollowsFinalOutputAndRejectsLateSuccessFrames() {
        ChatStreamContext ctx = newCtx();
        parser.parseStreamResponse(ctx, "{\"type\":\"response.cancelled\",\"sequence_number\":7,"
                + "\"response\":{\"id\":\"resp_cancelled\",\"model\":\"gpt-5.4\",\"output\":["
                + "{\"id\":\"msg_1\",\"type\":\"message\",\"content\":["
                + "{\"type\":\"output_text\",\"text\":\"partial\"}]},"
                + "{\"id\":\"fc_1\",\"type\":\"function_call\",\"call_id\":\"call_1\","
                + "\"name\":\"weather\",\"arguments\":\"{}\"},"
                + "{\"id\":\"img_1\",\"type\":\"image_generation_call\",\"result\":\"QUJD\"}],"
                + "\"usage\":{\"input_tokens\":4,\"output_tokens\":1,\"total_tokens\":5}}}");

        assertEquals(java.util.Arrays.asList(
                ChatEventType.TEXT_DELTA,
                ChatEventType.TOOL_CALL_START,
                ChatEventType.TOOL_CALL_ARGS_DELTA,
                ChatEventType.MEDIA_DONE,
                ChatEventType.STATUS), types());
        ChatEvent status = events.get(events.size() - 1);
        assertEquals("response.cancelled", status.getRawType());
        assertEquals("cancelled", status.getText());
        assertEquals(Long.valueOf(7), status.attrAs("sequence_number"));
        assertFalse(types().contains(ChatEventType.ABORT));
        assertEquals(5, ctx.getAccumulator().getUsage().totalTokens());

        int eventCount = events.size();
        parser.parseStreamResponse(ctx, "{\"type\":\"response.output_text.delta\",\"delta\":\"late\"}");
        parser.parseStreamResponse(ctx, "{\"type\":\"response.completed\",\"response\":{"
                + "\"id\":\"resp_cancelled\",\"output\":[{\"id\":\"msg_1\",\"type\":\"message\","
                + "\"content\":[{\"type\":\"output_text\",\"text\":\"late-success\"}]}]}}");
        assertEquals(eventCount, events.size(), "供应商终态之后不得继续发内容或成功终态事件");
        assertEquals("partial", ctx.getAccumulator().getAggregationText());
    }

    @Test
    public void streamIncompleteKeepsReasonAndUsesStatus() {
        ChatStreamContext ctx = newCtx();
        parser.parseStreamResponse(ctx, "{\"type\":\"response.incomplete\",\"response\":{"
                + "\"id\":\"resp_incomplete\",\"incomplete_details\":{\"reason\":\"max_output_tokens\"},"
                + "\"output\":[{\"id\":\"msg_1\",\"type\":\"message\",\"content\":["
                + "{\"type\":\"output_text\",\"text\":\"partial\"}]}]}}");

        assertEquals(java.util.Arrays.asList(ChatEventType.TEXT_DELTA, ChatEventType.STATUS), types());
        ChatEvent status = events.get(events.size() - 1);
        assertEquals("response.incomplete", status.getRawType());
        assertEquals("max_output_tokens", status.getSubType());
        assertEquals("length", status.getText());
        assertEquals("length", ctx.getAccumulator().lastFinishReason);
        assertFalse(types().contains(ChatEventType.ABORT));
    }

    @Test
    public void streamTerminalErrorsFinishAndProvideFallbackMessage() {
        ChatStreamContext ctx = newCtx();
        parser.parseStreamResponse(ctx, "{\"error\":{\"message\":\"boom\"}}");
        assertTrue(ctx.getAccumulator().isFinished());
        assertNull(ctx.getAccumulator().snapshotTerminal().getMessage());

        ChatStreamContext failed = newCtx();
        parser.parseStreamResponse(failed, "{\"type\":\"response.failed\",\"sequence_number\":9,"
                + "\"response\":{\"id\":\"resp_failed\",\"error\":{\"message\":\"bad\"}}}");
        assertTrue(failed.getAccumulator().isFinished());
        assertNull(failed.getAccumulator().snapshotTerminal().getMessage());
        assertEquals("resp_failed", failed.getProviderResponseId());
        assertEquals(Long.valueOf(9), firstOf(ChatEventType.ERROR).attrAs("sequence_number"));
    }

    @Test
    public void audioAndTranscriptKeepTheirNativeShapes() {
        ChatStreamContext ctx = newCtx();
        parser.parseStreamResponse(ctx, "{\"type\":\"response.audio.delta\",\"delta\":\"QUJD\",\"sequence_number\":1}");
        ChatEvent audio = firstOf(ChatEventType.MEDIA_PARTIAL);
        assertNotNull(audio);
        assertTrue(audio.getBlock() instanceof AudioBlock);
        assertNull(audio.getText(), "音频 Base64 不应伪装为普通文本");

        parser.parseStreamResponse(ctx, "{\"type\":\"response.audio.transcript.delta\",\"delta\":\"hello\",\"sequence_number\":2}");
        ChatEvent transcript = events.get(events.size() - 1);
        assertEquals("audio_transcript", transcript.getSubType());
        assertEquals("hello", transcript.getText());

        assertEquals("hello", ctx.getAccumulator().snapshotTerminal().getMessage().getText(),
                "流式 transcript 应与非流式一致投影为终态正文");

        parser.parseStreamResponse(ctx, "{\"type\":\"response.audio.done\",\"sequence_number\":3}");
        AssistantMessage completeMessage = ctx.getAccumulator().snapshotTerminal().getMessage();
        assertEquals(1, completeMessage.getBlocks().stream()
                .filter(block -> block instanceof AudioBlock).count());
        AudioBlock complete = (AudioBlock) completeMessage.getBlocks().stream()
                .filter(block -> block instanceof AudioBlock).findFirst().get();
        assertEquals("QUJD", complete.getData());
        assertEquals("hello", complete.metas().get("transcript"));

        parser.parseStreamResponse(ctx, "{\"type\":\"response.completed\",\"response\":{\"output\":[{"
                + "\"id\":\"msg_audio\",\"type\":\"message\",\"content\":[{\"type\":\"output_audio\","
                + "\"data\":\"QUJD\",\"transcript\":\"hello\"}]}]}}");
        assertEquals(1, ctx.getAccumulator().snapshotTerminal().getMessage().getBlocks().stream()
                        .filter(block -> block instanceof AudioBlock).count(),
                "completed 不应重复加入已交付音频");
    }

    @Test
    public void officialAudioTranscriptDonePatchesTextAndDeliveredAudioIdempotently() {
        ChatStreamContext ctx = newCtx();
        parser.parseStreamResponse(ctx, "{\"type\":\"response.output_audio.delta\",\"delta\":\"QUJD\"}");
        parser.parseStreamResponse(ctx, "{\"type\":\"response.output_audio.done\"}");

        String transcriptDone = "{\"type\":\"response.output_audio_transcript.done\","
                + "\"transcript\":\"hello\"}";
        parser.parseStreamResponse(ctx, transcriptDone);
        parser.parseStreamResponse(ctx, transcriptDone);

        AssistantMessage message = ctx.getAccumulator().snapshotTerminal().getMessage();
        assertEquals("hello", message.getText());
        assertEquals(1, message.getBlocks().stream()
                .filter(block -> block instanceof AudioBlock).count());
        AudioBlock audio = (AudioBlock) message.getBlocks().stream()
                .filter(block -> block instanceof AudioBlock).findFirst().get();
        assertEquals("hello", audio.metas().get("transcript"),
                "transcript 晚于 audio.done 时应补入已交付媒体 metadata");
        assertEquals(1, types().stream().filter(t -> t == ChatEventType.TEXT_DELTA).count(),
                "重复 transcript.done 不应重复投影正文");
    }

    @Test
    public void functionDoneRecoversWithoutAddedAndKeepsCallsIsolated() {
        ChatStreamContext ctx = newCtx();
        String expectedArgs = "{\"city\":\"hz\"}";

        parser.parseStreamResponse(ctx, "{\"type\":\"response.function_call_arguments.delta\","
                + "\"item_id\":\"fc_1\",\"output_index\":0,\"delta\":\"{\\\"city\\\":\"}");
        parser.parseStreamResponse(ctx, "{\"type\":\"response.function_call_arguments.delta\","
                + "\"item_id\":\"fc_1\",\"output_index\":0,\"delta\":\"\\\"hz\\\"}\"}");
        parser.parseStreamResponse(ctx, "{\"type\":\"response.function_call_arguments.done\","
                + "\"item_id\":\"fc_1\",\"output_index\":0,\"name\":\"weather\","
                + "\"arguments\":\"{\\\"city\\\":\\\"hz\\\"}\"}");

        assertTrue(ctx.getAccumulator().snapshotTerminal().getToolCalls().isEmpty(),
                "未知真实 call_id 时不能形成可执行工具调用");
        assertEquals(0, types().stream().filter(t -> t == ChatEventType.TOOL_CALL_ARGS_DELTA).count(),
                "call_id 未知时不能提前发布无归属的参数分片");

        String outputDone = "{\"type\":\"response.output_item.done\",\"output_index\":0,"
                + "\"item\":{\"id\":\"fc_1\",\"type\":\"function_call\",\"call_id\":\"call_1\","
                + "\"name\":\"weather\",\"arguments\":\"{\\\"city\\\":\\\"hz\\\"}\"}}";
        parser.parseStreamResponse(ctx, outputDone);
        // SSE 重放同一 done 帧时，终态与事件均必须幂等。
        parser.parseStreamResponse(ctx, outputDone);

        AssistantMessage terminal = ctx.getAccumulator().snapshotTerminal().getMessage();
        assertTrue(terminal.isToolCalls());
        assertEquals(1, terminal.getToolCalls().size());
        ToolCall call = terminal.getToolCalls().get(0);
        assertEquals("weather", call.getName());
        assertEquals("call_1", call.getId());
        assertEquals(expectedArgs, call.getArgumentsStr(), "终态参数必须保留全部未知 id 阶段的分片");
        assertEquals("hz", call.getArguments().get("city"));

        List<ChatEvent> argsDeltas = new ArrayList<>();
        for (ChatEvent event : events) {
            if (event.getType() == ChatEventType.TOOL_CALL_ARGS_DELTA) {
                argsDeltas.add(event);
            }
        }
        StringBuilder deliveredArgs = new StringBuilder();
        for (ChatEvent event : argsDeltas) {
            deliveredArgs.append(event.getText());
            assertEquals("call_1", event.getToolCallId(), "参数事件必须关联后补的真实 call_id");
        }
        assertEquals(1, argsDeltas.size(), "后补 call_id 时应只补发一次完整参数");
        assertEquals(expectedArgs, deliveredArgs.toString(), "参数事件必须完整且不重复");
        assertEquals(1, types().stream().filter(t -> t == ChatEventType.TOOL_CALL_START).count());
        assertEquals(0, types().stream().filter(t -> t == ChatEventType.TOOL_CALL_END).count(),
                "流式 parser 不直接发 END，由完整流核心统一收口");
    }

    @Test
    public void signatureRefusalAndAnnotationEventsAreIdempotent() {
        ChatStreamContext ctx = newCtx();
        String reasoning = "{\"type\":\"response.output_item.done\",\"output_index\":0,"
                + "\"item\":{\"id\":\"rs_1\",\"type\":\"reasoning\",\"encrypted_content\":\"enc\"}}";
        parser.parseStreamResponse(ctx, reasoning);
        parser.parseStreamResponse(ctx, reasoning);
        parser.parseStreamResponse(ctx, "{\"type\":\"response.completed\",\"response\":{\"output\":[{"
                + "\"id\":\"rs_1\",\"type\":\"reasoning\",\"encrypted_content\":\"enc\",\"summary\":[]}]}}");
        assertEquals(1, types().stream().filter(t -> t == ChatEventType.THINKING_SIGNATURE).count(), events.toString());

        String refusal = "{\"type\":\"response.refusal.done\",\"item_id\":\"msg_1\","
                + "\"output_index\":1,\"content_index\":0,\"refusal\":\"no\"}";
        parser.parseStreamResponse(ctx, refusal);
        parser.parseStreamResponse(ctx, refusal);
        assertEquals(1, types().stream().filter(t -> t == ChatEventType.CONTENT_FILTER).count());

        String annotation = "{\"type\":\"response.output_text.annotation.added\",\"item_id\":\"msg_1\","
                + "\"output_index\":1,\"content_index\":0,\"annotation_index\":2,\"sequence_number\":8,"
                + "\"annotation\":{\"type\":\"url_citation\",\"url\":\"https://example.com\"}}";
        parser.parseStreamResponse(ctx, annotation);
        parser.parseStreamResponse(ctx, annotation);
        assertEquals(1, types().stream().filter(t -> t == ChatEventType.CITATION).count());
        ChatEvent citation = firstOf(ChatEventType.CITATION);
        assertEquals("url_citation", citation.getSubType());
        assertEquals(Integer.valueOf(0), citation.attrAs("content_index"));
        assertEquals(Integer.valueOf(1), citation.attrAs("output_index"));
        assertEquals(Long.valueOf(8), citation.attrAs("sequence_number"));
    }

    @Test
    public void genericOutputItemDoneAndShellPayloadStayObservable() {
        ChatStreamContext ctx = newCtx();
        parser.parseStreamResponse(ctx, "{\"type\":\"response.output_item.done\",\"output_index\":0,"
                + "\"sequence_number\":4,\"item\":{\"id\":\"pc_1\",\"type\":\"computer_call_output\"}}");
        ChatEvent result = firstOf(ChatEventType.SERVER_TOOL_RESULT);
        assertNotNull(result);
        assertEquals("computer_call_output", result.getSubType());

        parser.parseStreamResponse(ctx, "{\"type\":\"response.shell_call_output_content.delta\","
                + "\"item_id\":\"sh_1\",\"output_index\":1,\"command_index\":0,\"sequence_number\":5,"
                + "\"delta\":{\"stdout\":\"ok\",\"stderr\":\"warn\"}}");
        ChatEvent shell = events.get(events.size() - 1);
        assertEquals("ok", shell.attrAs("stdout"));
        assertEquals("warn", shell.attrAs("stderr"));
        assertEquals(Integer.valueOf(0), shell.attrAs("command_index"));

        String completed = "{\"type\":\"response.web_search_call.completed\",\"item_id\":\"ws_1\","
                + "\"output_index\":2,\"sequence_number\":6}";
        parser.parseStreamResponse(ctx, completed);
        parser.parseStreamResponse(ctx, completed);
        parser.parseStreamResponse(ctx, "{\"type\":\"response.output_item.done\",\"output_index\":2,"
                + "\"item\":{\"id\":\"ws_1\",\"type\":\"web_search_call\"}}");
        assertEquals(2, types().stream().filter(t -> t == ChatEventType.SERVER_TOOL_RESULT).count(),
                "同一工具显式 terminal 与 output_item.done fallback 应共享幂等键");

        String partial = "{\"type\":\"response.image_generation_call.partial_image\",\"item_id\":\"ig_1\","
                + "\"output_index\":3,\"partial_image_b64\":\"aW1n\"}";
        parser.parseStreamResponse(ctx, partial);
        parser.parseStreamResponse(ctx, partial);
        assertEquals(1, types().stream().filter(t -> t == ChatEventType.MEDIA_PARTIAL).count(),
                "缺少 partial_image_index 时也应按内容签名幂等");
    }

    @Test
    public void reasoningSummaryIncompleteIsStatusNotResponseAbort() {
        ChatStreamContext ctx = newCtx();
        parser.parseStreamResponse(ctx, "{\"type\":\"response.reasoning_summary_part.done\","
                + "\"item_id\":\"rs_1\",\"output_index\":0,\"summary_index\":0,\"status\":\"incomplete\","
                + "\"part\":{\"type\":\"summary_text\",\"text\":\"partial\"}}");
        assertNotNull(firstOf(ChatEventType.STATUS));
        assertFalse(types().contains(ChatEventType.ABORT));
    }
}
