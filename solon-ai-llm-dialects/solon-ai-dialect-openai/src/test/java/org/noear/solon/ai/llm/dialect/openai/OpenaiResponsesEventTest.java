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
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.session.InMemoryChatSession;

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

        parser.parseStreamResponse(ctx, "{\"type\":\"response.image_generation_call.partial_image\",\"item_id\":\"img_1\"}");

        assertEquals(1, events.size());
        assertSame(ChatEventType.MEDIA_PARTIAL, events.get(0).getType());
        assertSame(ChatEventGroup.MEDIA, events.get(0).getGroup());
    }

    /**
     * 拒答：旧实现按普通文本输出，订阅方无法识别；现在文本降级保留 + 专用事件
     */
    @Test
    public void refusalKeepsTextAndAddsEvent() {
        ChatStreamContext ctx = newCtx();

        parser.parseStreamResponse(ctx, "{\"type\":\"response.refusal.delta\",\"delta\":\"I cannot help\"}");

        //文本降级保留（不破坏现有 UI）
        assertTrue(ctx.getAccumulator().hasContentItems());
        assertEquals("I cannot help", ctx.getAccumulator().lastItem().getText());

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

    /**
     * 引用标注：旧实现无分支，整帧丢弃
     */
    @Test
    public void annotationBecomesCitation() {
        ChatStreamContext ctx = newCtx();

        parser.parseStreamResponse(ctx, "{\"type\":\"response.output_text.annotation.added\",\"item_id\":\"msg_1\","
                + "\"annotation_index\":0,\"annotation\":{\"type\":\"url_citation\",\"url\":\"https://example.com\"}}");

        ChatEvent e = firstOf(ChatEventType.CITATION);
        assertNotNull(e, "annotation should emit CITATION");
        assertEquals("https://example.com", e.getText());
        assertEquals(0, e.getIndex());
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
     * 内容主干仍走内容项（由核心统一转事件并保证边界），方言不重复发射内容事件
     */
    @Test
    public void textDeltaStillGoesThroughChoiceOnly() {
        ChatStreamContext ctx = newCtx();

        parser.parseStreamResponse(ctx, "{\"type\":\"response.output_item.added\",\"item\":{\"id\":\"msg_1\",\"type\":\"message\"}}");
        parser.parseStreamResponse(ctx, "{\"type\":\"response.output_text.delta\",\"delta\":\"hello\"}");

        assertTrue(ctx.getAccumulator().hasContentItems());
        assertEquals("hello", ctx.getAccumulator().lastItem().getText());

        for (ChatEvent e : events) {
            assertNotSame(ChatEventType.TEXT_DELTA, e.getType(),
                    "dialect must not emit content events (core converts content items)");
        }
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
                + "{\"type\":\"url_citation\",\"url\":\"https://weather.example/hz\"}]},"
                + "{\"type\":\"refusal\",\"refusal\":\"\u6b64\u8bf7\u6c42\u65e0\u6cd5\u5b8c\u6210\"}]}]}");

        assertTrue(types().contains(ChatEventType.CITATION), "non-stream must emit CITATION");
        assertEquals("https://weather.example/hz", firstOf(ChatEventType.CITATION).getText());
        assertEquals("\u6b64\u8bf7\u6c42\u65e0\u6cd5\u5b8c\u6210", firstOf(ChatEventType.REFUSAL_DELTA).getText());
        // 拒答终态只发一次（与流式 response.refusal.done 对称）
        assertEquals(1, types().stream().filter(t -> t == ChatEventType.CONTENT_FILTER).count());
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

    /**
     * 非流式的中止与错误：incomplete 走 ABORT，failed 走 ERROR
     */
    @Test
    public void nonStreamEmitsAbortAndError() {
        ChatStreamContext ctx = newNonStreamCtx();
        parser.parseNonStreamResponse(ctx, "{\"id\":\"resp_1\",\"status\":\"incomplete\","
                + "\"incomplete_details\":{\"reason\":\"max_output_tokens\"},\"output\":[]}");
        assertNotNull(firstOf(ChatEventType.ABORT), "incomplete must emit ABORT");
        assertEquals("length", ctx.getAccumulator().lastFinishReason);

        ChatStreamContext ctx2 = newNonStreamCtx();
        parser.parseNonStreamResponse(ctx2, "{\"id\":\"resp_1\",\"status\":\"failed\","
                + "\"error\":{\"message\":\"boom\"}}");
        assertNotNull(firstOf(ChatEventType.ERROR), "failed must emit ERROR");
        assertNotNull(ctx2.getAccumulator().getError());
    }
}
