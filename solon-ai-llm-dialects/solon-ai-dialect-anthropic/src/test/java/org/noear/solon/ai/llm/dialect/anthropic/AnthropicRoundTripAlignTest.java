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
import org.noear.snack4.ONode;
import org.noear.solon.ai.chat.*;
import org.noear.solon.ai.chat.content.BlobBlock;
import org.noear.solon.ai.chat.content.ImageBlock;
import org.noear.solon.ai.chat.event.*;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.session.InMemoryChatSession;
import org.noear.solon.ai.chat.tool.ToolCall;
import org.noear.solon.ai.chat.tool.ToolResult;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Anthropic 方言的「响应 → 请求」回环与事件增量对齐
 *
 * <p>覆盖四类此前的缺口：</p>
 * <ol>
 *   <li><b>事件增量</b>：本地 tool_use 的 {@code input_json_delta} 要逐片交付（旧实现只在块尾给一次全量），
 *       {@code content_block_start} 已给出的 {@code input} 初值不能丢；</li>
 *   <li><b>call/stream 对称</b>：非流式 text 块内嵌的 {@code citations} 与流式 {@code citations_delta} 同走 CITATION；</li>
 *   <li><b>回环</b>：服务端工具块（{@code server_tool_use} / {@code *_tool_result} / {@code container_upload}）
 *       与代码执行容器必须能原样回传，否则 {@code pause_turn} 续跑退化为重跑、缓存前缀逐轮不稳；</li>
 *   <li><b>请求侧 GA 面</b>：{@code tool_choice.disable_parallel_tool_use}、{@code Tool} 的四个 GA 字段、
 *       {@code document} 块与 {@code image} 的 file source。</li>
 * </ol>
 *
 * @author noear
 */
public class AnthropicRoundTripAlignTest {
    private final AnthropicResponseParser parser = new AnthropicResponseParser();
    private final AnthropicRequestBuilder requestBuilder = new AnthropicRequestBuilder();

    private final List<ChatEvent> events = new ArrayList<>();

    private ChatStreamContext newCtx(boolean stream) {
        events.clear();

        ChatConfig config = new ChatConfig();
        config.setModel("claude-sonnet-4-5");
        ChatRequest req = new ChatRequest(config, AnthropicChatDialect.getInstance(), ChatOptions.of(),
                InMemoryChatSession.builder().build(), ChatMessage.ofSystem("test"), null, stream);

        return new ChatStreamContextDefault(config, req, new ChatAccumulator(req, stream),
                new ChatStreamSession(), 0, events::add);
    }

    private List<ChatEvent> allOf(ChatEventType type) {
        List<ChatEvent> list = new ArrayList<>();
        for (ChatEvent e : events) {
            if (e.getType() == type) {
                list.add(e);
            }
        }
        return list;
    }

    private ONode build(ChatOptions options, List<ChatMessage> messages) {
        ChatConfig config = new ChatConfig();
        config.setModel("claude-sonnet-4-5");
        return requestBuilder.build(config, options, messages, false);
    }

    /**
     * 取第 idx 个内容项的唯一工具调用
     */
    private static ToolCall shardOf(ChatAccumulator acc, int idx) {
        AssistantMessage item = acc.getContentItems().get(idx);
        assertNotNull(item.getToolCalls(), "content item " + idx + " should carry a tool call shard");
        assertEquals(1, item.getToolCalls().size());
        return item.getToolCalls().get(0);
    }

    /// ///////////////// 事件增量：tool_use 参数分片

    /**
     * 本地 tool_use：每个 input_json_delta 都要落成一个内容项（核心据此发真增量 TOOL_CALL_ARGS_DELTA），
     * 且 content_block_stop 不得再补一个「全量参数」的内容项——补了会让核心把同一份参数二次累积。
     */
    @Test
    public void toolUseArgsDeliveredAsShards() {
        ChatStreamContext ctx = newCtx(true);
        ChatAccumulator acc = ctx.getAccumulator();

        parser.parseStreamResponse(ctx, "{\"type\":\"content_block_start\",\"index\":0,"
                + "\"content_block\":{\"type\":\"tool_use\",\"id\":\"toolu_1\",\"name\":\"get_weather\",\"input\":{}}}");

        assertEquals(1, acc.getContentItems().size(), "block start should open the tool call");
        ToolCall head = shardOf(acc, 0);
        assertEquals("toolu_1", head.getId());
        assertEquals("0", head.getIndex(), "分片归属必须用协议的块 index，否则并行工具会串参数");
        assertEquals("get_weather", head.getName());
        assertNull(head.getArgumentsStr(), "空对象 input 不能变成 \"{}\" 前缀，否则与后续分片拼成脏值");

        parser.parseStreamResponse(ctx, "{\"type\":\"content_block_delta\",\"index\":0,"
                + "\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"{\\\"city\\\":\"}}");
        parser.parseStreamResponse(ctx, "{\"type\":\"content_block_delta\",\"index\":0,"
                + "\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"\\\"上海\\\"}\"}}");

        assertEquals(3, acc.getContentItems().size(), "每个 input_json_delta 应各自成项（真增量）");
        assertEquals("{\"city\":", shardOf(acc, 1).getArgumentsStr());
        assertEquals("\"上海\"}", shardOf(acc, 2).getArgumentsStr());

        parser.parseStreamResponse(ctx, "{\"type\":\"content_block_stop\",\"index\":0}");
        assertEquals(3, acc.getContentItems().size(),
                "content_block_stop 不能再补全量参数项：核心会二次累积并多发一条全量 ARGS_DELTA");

        //各分片按序拼接即为完整参数（核心 ToolCallBuilder 的累积等价物）
        StringBuilder joined = new StringBuilder();
        for (int i = 1; i < acc.getContentItems().size(); i++) {
            joined.append(shardOf(acc, i).getArgumentsStr());
        }
        assertEquals("{\"city\":\"上海\"}", joined.toString());
    }

    /**
     * content_block_start 已给出非空 input（网关 / eager input streaming）时必须读走，
     * 否则该工具的参数静默退化为空对象。
     */
    @Test
    public void toolUseInitialInputFromBlockStart() {
        ChatStreamContext ctx = newCtx(true);
        ChatAccumulator acc = ctx.getAccumulator();

        parser.parseStreamResponse(ctx, "{\"type\":\"content_block_start\",\"index\":2,"
                + "\"content_block\":{\"type\":\"tool_use\",\"id\":\"toolu_9\",\"name\":\"f\","
                + "\"input\":{\"a\":1}}}");
        parser.parseStreamResponse(ctx, "{\"type\":\"content_block_stop\",\"index\":2}");

        assertEquals(1, acc.getContentItems().size());
        ToolCall call = shardOf(acc, 0);
        assertEquals("2", call.getIndex());
        assertEquals("{\"a\":1}", call.getArgumentsStr(),
                "start 携带的 input 初值不读，参数就只剩空对象");
    }

    /**
     * 服务端工具的 input 初值同样要透出（走 SERVER_TOOL_ARGS_DELTA，不进本地工具调用）。
     */
    @Test
    public void serverToolInitialInputEmitted() {
        ChatStreamContext ctx = newCtx(true);

        parser.parseStreamResponse(ctx, "{\"type\":\"content_block_start\",\"index\":0,"
                + "\"content_block\":{\"type\":\"server_tool_use\",\"id\":\"srv_1\",\"name\":\"web_search\","
                + "\"input\":{\"query\":\"solon\"}}}");

        List<ChatEvent> deltas = allOf(ChatEventType.SERVER_TOOL_ARGS_DELTA);
        assertEquals(1, deltas.size(), "start 里的 input 初值应发一条参数事件");
        assertEquals("{\"query\":\"solon\"}", deltas.get(0).getText());
        assertEquals("srv_1", deltas.get(0).getToolCallId());

        //服务端工具不是本地 function call，不得进入内容项
        assertTrue(ctx.getAccumulator().hasContentItems() == false);
    }

    /// ///////////////// call/stream 对称：非流式 citations

    /**
     * 非流式 text 块内嵌的 citations：旧实现只读 text 字段，call() 完全看不到引用。
     */
    @Test
    public void nonStreamTextCitationsEmitEvents() {
        ChatStreamContext ctx = newCtx(false);

        parser.parseNonStreamResponse(ctx, "{\"model\":\"claude-sonnet-4-5\",\"stop_reason\":\"end_turn\","
                + "\"content\":[{\"type\":\"text\",\"text\":\"据报道\",\"citations\":["
                + "{\"type\":\"web_search_result_location\",\"url\":\"https://a.dev/x\",\"title\":\"A\"},"
                + "{\"type\":\"page_location\",\"cited_text\":\"第 3 页原文\",\"document_title\":\"手册\"}"
                + "]}]}");

        List<ChatEvent> citations = allOf(ChatEventType.CITATION);
        assertEquals(2, citations.size(), "内嵌 citations 应逐条发 CITATION");
        assertEquals("web_search_result_location", citations.get(0).getSubType());
        assertEquals("https://a.dev/x", citations.get(0).getText());
        //文档类定位没有 url，取 cited_text
        assertEquals("page_location", citations.get(1).getSubType());
        assertEquals("第 3 页原文", citations.get(1).getText());

        //正文不受影响
        assertEquals("据报道", ctx.getAccumulator().lastItem().getContent());
    }

    /**
     * 流式 content_block_start 的 text 块若内嵌 citations（非增量形态），同样要透出。
     */
    @Test
    public void streamTextBlockStartCitationsEmitEvents() {
        ChatStreamContext ctx = newCtx(true);

        parser.parseStreamResponse(ctx, "{\"type\":\"content_block_start\",\"index\":0,"
                + "\"content_block\":{\"type\":\"text\",\"text\":\"x\",\"citations\":["
                + "{\"type\":\"search_result_location\",\"url\":\"https://b.dev\"}]}}");

        assertEquals(1, allOf(ChatEventType.CITATION).size());
        assertEquals("https://b.dev", allOf(ChatEventType.CITATION).get(0).getText());
    }

    /// ///////////////// 回环：服务端工具块与容器

    /**
     * 非流式：服务端工具块要原样留存进 contentRaw（含 encrypted_content 这类无法本地重建的凭证）。
     */
    @Test
    public void nonStreamServerToolBlocksKeptInContentRaw() {
        ChatStreamContext ctx = newCtx(false);

        parser.parseNonStreamResponse(ctx, "{\"model\":\"claude-sonnet-4-5\",\"stop_reason\":\"pause_turn\","
                + "\"container\":{\"id\":\"cnt_1\",\"expires_at\":\"2026-01-01T00:00:00Z\"},"
                + "\"content\":["
                + "{\"type\":\"server_tool_use\",\"id\":\"srv_1\",\"name\":\"web_search\",\"input\":{\"query\":\"q\"}},"
                + "{\"type\":\"web_search_tool_result\",\"tool_use_id\":\"srv_1\",\"content\":["
                + "{\"type\":\"web_search_result\",\"title\":\"T\",\"url\":\"u\",\"encrypted_content\":\"enc_xyz\"}]}"
                + "]}");

        Map<?, ?> contentRaw = (Map<?, ?>) ctx.getAccumulator().lastItem().getContentRaw();
        assertNotNull(contentRaw, "服务端工具轮次必须留下可回传的 contentRaw");

        List<?> blocks = (List<?>) contentRaw.get("anthropicServerToolBlocks");
        assertEquals(2, blocks.size());
        assertTrue(String.valueOf(blocks.get(1)).contains("enc_xyz"),
                "encrypted_content 是服务端签发的 opaque 凭证，必须原样留存");
        assertTrue(String.valueOf(contentRaw.get("anthropicContainer")).contains("cnt_1"));
    }

    /**
     * 流式 pause_turn：服务端工具块随终态载体帧进 contentRaw（这条流没有本地 tool_use，
     * 不走 ToolCallBuilder 路径，载体帧是唯一出口）。
     */
    @Test
    public void streamServerToolBlocksRideOnTerminalCarrier() {
        ChatStreamContext ctx = newCtx(true);

        parser.parseStreamResponse(ctx, "{\"type\":\"message_start\",\"message\":{\"id\":\"msg_1\","
                + "\"model\":\"claude-sonnet-4-5\",\"container\":{\"id\":\"cnt_9\"}}}");
        parser.parseStreamResponse(ctx, "{\"type\":\"content_block_start\",\"index\":0,"
                + "\"content_block\":{\"type\":\"server_tool_use\",\"id\":\"srv_1\",\"name\":\"web_search\"}}");
        parser.parseStreamResponse(ctx, "{\"type\":\"content_block_start\",\"index\":1,"
                + "\"content_block\":{\"type\":\"web_search_tool_result\",\"tool_use_id\":\"srv_1\","
                + "\"content\":[{\"type\":\"web_search_result\",\"title\":\"T\",\"url\":\"u\","
                + "\"encrypted_content\":\"enc_abc\"}]}}");
        parser.parseStreamResponse(ctx, "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"pause_turn\"}}");
        parser.parseStreamResponse(ctx, "{\"type\":\"message_stop\"}");

        //pause_turn 用非终态 STATUS 表达（不能用 ABORT：会与终态收口帧抢块边界）
        List<ChatEvent> status = allOf(ChatEventType.STATUS);
        assertEquals(1, status.size());
        assertEquals("pause_turn", status.get(0).getSubType());

        Map<?, ?> contentRaw = (Map<?, ?>) ctx.getAccumulator().lastItem().getContentRaw();
        assertNotNull(contentRaw, "pause_turn 续跑需要一个能带走服务端块的载体帧");
        List<?> blocks = (List<?>) contentRaw.get("anthropicServerToolBlocks");
        assertEquals(2, blocks.size());
        assertTrue(String.valueOf(blocks.get(1)).contains("enc_abc"));
        assertTrue(String.valueOf(contentRaw.get("anthropicContainer")).contains("cnt_9"));
    }

    /**
     * 出站：历史里的服务端工具块原样回传，位置在 text 之前（与真实响应的块序一致）。
     */
    @Test
    public void serverToolBlocksReplayedInRequest() {
        Map<String, Object> contentRaw = new LinkedHashMap<>();
        contentRaw.put("anthropicServerToolBlocks", Arrays.asList(
                "{\"type\":\"server_tool_use\",\"id\":\"srv_1\",\"name\":\"web_search\",\"input\":{\"query\":\"q\"}}",
                "{\"type\":\"web_search_tool_result\",\"tool_use_id\":\"srv_1\",\"content\":"
                        + "[{\"type\":\"web_search_result\",\"url\":\"u\",\"encrypted_content\":\"enc_xyz\"}]}"));

        AssistantMessage history = new AssistantMessage("查到了", "", false,
                contentRaw, null, null, null);

        ONode root = build(ChatOptions.of(), Arrays.asList(ChatMessage.ofUser("查一下"), history,
                ChatMessage.ofUser("继续")));

        ONode content = root.get("messages").get(1).get("content");
        assertTrue(content.isArray(), "带服务端块的 assistant 消息必须用块数组形态：" + content.toJson());
        assertEquals("server_tool_use", content.get(0).get("type").getString());
        assertEquals("web_search_tool_result", content.get(1).get("type").getString());
        assertEquals("text", content.get(2).get("type").getString(), "服务端块应排在正文之前");
        assertTrue(content.toJson().contains("enc_xyz"));
    }

    /**
     * 出站：容器 id 回填顶层 container（协议接受 id 字符串；expires_at 属于响应侧，不能原样送回）。
     */
    @Test
    public void containerReplayedAsTopLevelId() {
        Map<String, Object> contentRaw = new LinkedHashMap<>();
        contentRaw.put("anthropicContainer", "{\"id\":\"cnt_1\",\"expires_at\":\"2026-01-01T00:00:00Z\"}");

        AssistantMessage history = new AssistantMessage("done", "", false,
                contentRaw, null, null, null);

        ONode root = build(ChatOptions.of(), Arrays.asList(ChatMessage.ofUser("跑个脚本"), history,
                ChatMessage.ofUser("再跑一次")));

        assertEquals("cnt_1", root.get("container").getString());
        assertFalse(root.toJson().contains("expires_at"), "expires_at 不是请求侧字段");
    }

    /**
     * 用户显式给了顶层 container 时不被历史覆盖。
     */
    @Test
    public void explicitContainerNotOverridden() {
        Map<String, Object> contentRaw = new LinkedHashMap<>();
        contentRaw.put("anthropicContainer", "{\"id\":\"cnt_old\"}");
        AssistantMessage history = new AssistantMessage("done", "", false, contentRaw, null, null, null);

        ONode root = build(ChatOptions.of().optionSet("container", "cnt_new"),
                Arrays.asList(ChatMessage.ofUser("hi"), history));

        assertEquals("cnt_new", root.get("container").getString());
    }

    /// ///////////////// 请求侧：并行工具开关

    /**
     * parallel_tool_calls=false → tool_choice.disable_parallel_tool_use=true
     * （旧实现把它当非法字段整个丢弃，用户的「禁止并行」诉求静默失效）。
     */
    @Test
    public void parallelToolCallsFalseMapsToToolChoice() {
        ONode root = build(ChatOptions.of()
                        .optionSet("parallel_tool_calls", false)
                        .toolAdd("get_weather", t -> t.description("查天气").stringParamAdd("city", "城市")),
                Collections.singletonList(ChatMessage.ofUser("hi")));

        assertEquals("auto", root.get("tool_choice").get("type").getString());
        assertTrue(root.get("tool_choice").get("disable_parallel_tool_use").getBoolean());
        //不能作为非法顶层字段透传
        assertFalse(root.hasKey("parallel_tool_calls"));
    }

    /**
     * true 是默认行为，不写出；无 tools 时该字段无意义，也不许凭空造出 tool_choice。
     */
    @Test
    public void parallelToolCallsTrueOrNoToolsNotWritten() {
        ONode enabled = build(ChatOptions.of()
                        .optionSet("parallel_tool_calls", true)
                        .toolAdd("f", t -> t.description("d")),
                Collections.singletonList(ChatMessage.ofUser("hi")));
        assertFalse(enabled.toJson().contains("disable_parallel_tool_use"));

        ONode noTools = build(ChatOptions.of().optionSet("parallel_tool_calls", false),
                Collections.singletonList(ChatMessage.ofUser("hi")));
        assertFalse(noTools.hasKey("tool_choice"), "没有 tools 时不该凭空冒出 tool_choice");
    }

    /**
     * 协议上 ToolChoiceNone 没有 disable_parallel_tool_use 字段，写上去会被 schema 拒。
     */
    @Test
    public void parallelToolCallsSkippedForNoneChoice() {
        ONode root = build(ChatOptions.of()
                        .optionSet("tool_choice", "none")
                        .optionSet("parallel_tool_calls", false)
                        .toolAdd("f", t -> t.description("d")),
                Collections.singletonList(ChatMessage.ofUser("hi")));

        assertEquals("none", root.get("tool_choice").get("type").getString());
        assertFalse(root.toJson().contains("disable_parallel_tool_use"));
    }

    /**
     * 原生 Map 形态里的 disable_parallel_tool_use 同样要透传（旧实现只拷 type 与 name）。
     */
    @Test
    public void nativeToolChoiceDisableParallelPassthrough() {
        Map<String, Object> choice = new LinkedHashMap<>();
        choice.put("type", "any");
        choice.put("disable_parallel_tool_use", true);

        ONode root = build(ChatOptions.of()
                        .optionSet("tool_choice", choice)
                        .toolAdd("f", t -> t.description("d")),
                Collections.singletonList(ChatMessage.ofUser("hi")));

        assertEquals("any", root.get("tool_choice").get("type").getString());
        assertTrue(root.get("tool_choice").get("disable_parallel_tool_use").getBoolean());
    }

    /// ///////////////// 请求侧：Tool 的 GA 字段

    /**
     * 逐工具的 GA 协议字段（defer_loading / eager_input_streaming / allowed_callers / input_examples）。
     */
    @Test
    public void perToolProtocolFieldsWritten() {
        Map<String, Object> weather = new LinkedHashMap<>();
        weather.put("defer_loading", true);
        weather.put("eager_input_streaming", true);
        weather.put("allowed_callers", Arrays.asList("direct", "code_execution_20250825"));
        weather.put("input_examples", Collections.singletonList(
                Collections.singletonMap("city", "上海")));

        Map<String, Object> configs = new LinkedHashMap<>();
        configs.put("get_weather", weather);

        ONode root = build(ChatOptions.of()
                        .optionSet("anthropic_tools", configs)
                        .toolAdd("get_weather", t -> t.description("查天气").stringParamAdd("city", "城市"))
                        .toolAdd("other", t -> t.description("d")),
                Collections.singletonList(ChatMessage.ofUser("hi")));

        ONode tool0 = root.get("tools").get(0);
        assertEquals("get_weather", tool0.get("name").getString());
        assertTrue(tool0.get("defer_loading").getBoolean());
        assertTrue(tool0.get("eager_input_streaming").getBoolean());
        assertEquals(2, tool0.get("allowed_callers").size());
        assertEquals("上海", tool0.get("input_examples").get(0).get("city").getString());

        //未配置的工具不受影响
        assertFalse(root.get("tools").get(1).hasKey("defer_loading"));
        //方言自身消费的选项不进请求体
        assertFalse(root.hasKey("anthropic_tools"));
    }

    /**
     * 保留字段不许被旁路配置改写（改了会让工具名与实际可调用的函数错位、或撞坏缓存断点预算）。
     */
    @Test
    public void perToolReservedFieldsNotOverridable() {
        Map<String, Object> hack = new LinkedHashMap<>();
        hack.put("name", "evil");
        hack.put("description", "evil");
        hack.put("input_schema", Collections.singletonMap("type", "string"));
        hack.put("cache_control", Collections.singletonMap("type", "ephemeral"));

        ONode root = build(ChatOptions.of()
                        .optionSet("anthropic_tools", Collections.singletonMap("f", hack))
                        .toolAdd("f", t -> t.description("正经描述")),
                Collections.singletonList(ChatMessage.ofUser("hi")));

        ONode tool0 = root.get("tools").get(0);
        assertEquals("f", tool0.get("name").getString());
        assertEquals("正经描述", tool0.get("description").getString());
        assertEquals("object", tool0.get("input_schema").get("type").getString());
        assertFalse(tool0.hasKey("cache_control"));
    }

    /// ///////////////// 请求侧：document 与 image file source

    /**
     * BlobBlock → document 块：旧实现在所有 content 构建处只认 TextBlock / ImageBlock，PDF 被静默丢弃。
     */
    @Test
    public void blobBlockBecomesDocumentBlock() {
        String pdf = Base64.getEncoder().encodeToString("%PDF-1.7".getBytes());

        ONode root = build(ChatOptions.of(), Collections.singletonList(
                ChatMessage.ofUser("看这份文件", BlobBlock.of(pdf, "application/pdf"))));

        ONode content = root.get("messages").get(0).get("content");
        ONode doc = null;
        for (ONode block : content.getArray()) {
            if ("document".equals(block.get("type").getString())) {
                doc = block;
            }
        }
        assertNotNull(doc, "PDF 附件应生成 document 块：" + content.toJson());
        assertEquals("base64", doc.get("source").get("type").getString());
        assertEquals("application/pdf", doc.get("source").get("media_type").getString());
        assertEquals(pdf, doc.get("source").get("data").getString());
    }

    /**
     * text/plain 走协议的 PlainTextSource（明文 data），不是 base64。
     */
    @Test
    public void plainTextBlobUsesTextSource() {
        String data = Base64.getEncoder().encodeToString("hello doc".getBytes());

        ONode root = build(ChatOptions.of(), Collections.singletonList(
                ChatMessage.ofUser("读一下", BlobBlock.of(data, "text/plain"))));

        ONode content = root.get("messages").get(0).get("content");
        ONode doc = content.get(content.size() - 1);
        assertEquals("document", doc.get("type").getString());
        assertEquals("text", doc.get("source").get("type").getString());
        assertEquals("text/plain", doc.get("source").get("media_type").getString());
        assertEquals("hello doc", doc.get("source").get("data").getString());
    }

    /**
     * 用户消息里的 url 图片：旧实现在这条路径只写 base64 source，url 图片会得到 data:null 的非法块。
     */
    @Test
    public void userUrlImageUsesUrlSource() {
        ONode root = build(ChatOptions.of(), Collections.singletonList(
                ChatMessage.ofUser("看图", ImageBlock.ofUrl("https://a.dev/x.png"))));

        ONode image = root.get("messages").get(0).get("content").get(1);
        assertEquals("image", image.get("type").getString());
        assertEquals("url", image.get("source").get("type").getString());
        assertEquals("https://a.dev/x.png", image.get("source").get("url").getString());
    }

    /**
     * Files API 的 file_id 引用 → source.type=file（旧实现会把它当 url 发出去而被拒）。
     */
    @Test
    public void imageFileIdBecomesFileSource() {
        ONode direct = build(ChatOptions.of(), Collections.singletonList(
                ChatMessage.ofUser("看图", ImageBlock.ofUrl("file_011CQabc"))));
        ONode s1 = direct.get("messages").get(0).get("content").get(1).get("source");
        assertEquals("file", s1.get("type").getString());
        assertEquals("file_011CQabc", s1.get("file_id").getString());

        ONode prefixed = build(ChatOptions.of(), Collections.singletonList(
                ChatMessage.ofUser("看图", ImageBlock.ofUrl("anthropic-file:file_zzz"))));
        ONode s2 = prefixed.get("messages").get(0).get("content").get(1).get("source");
        assertEquals("file", s2.get("type").getString());
        assertEquals("file_zzz", s2.get("file_id").getString());

        //真实 url 不能被误判：路径里带 file_ 的图片仍走 url source
        ONode real = build(ChatOptions.of(), Collections.singletonList(
                ChatMessage.ofUser("看图", ImageBlock.ofUrl("https://a.dev/file_1.png"))));
        assertEquals("url", real.get("messages").get(0).get("content").get(1)
                .get("source").get("type").getString());
    }

    /**
     * tool_result.content 的块变体：document 直传，无对应变体的块退化为 text 而不是静默丢弃。
     */
    @Test
    public void toolResultSupportsDocumentAndFallback() {
        String pdf = Base64.getEncoder().encodeToString("%PDF".getBytes());

        ToolResult result = new ToolResult()
                .addText("见附件")
                .addBlock(BlobBlock.of(pdf, "application/pdf"));

        ONode root = build(ChatOptions.of(), Arrays.asList(
                ChatMessage.ofUser("导出一下"),
                ChatMessage.ofTool(result, "export", "toolu_1", false)));

        ONode block = root.get("messages").get(1).get("content").get(0);
        assertEquals("tool_result", block.get("type").getString());
        assertEquals("toolu_1", block.get("tool_use_id").getString());

        ONode inner = block.get("content");
        assertTrue(inner.isArray(), inner.toJson());
        assertEquals("text", inner.get(0).get("type").getString());
        assertEquals("document", inner.get(1).get("type").getString());
        assertEquals(pdf, inner.get(1).get("source").get("data").getString());
    }
}
