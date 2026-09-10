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
import org.noear.solon.ai.chat.message.MessageProtocolState;
import org.noear.solon.ai.chat.source.Citation;
import org.noear.solon.ai.chat.source.SearchResult;
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

    @Test
    public void nonStreamOrderedBlocksRoundTripExactlyOnce() {
        String content = "[{\"type\":\"thinking\",\"thinking\":\"\",\"signature\":\"sig_0\"},"
                + "{\"type\":\"text\",\"text\":\"A\",\"citations\":[]},"
                + "{\"type\":\"mcp_tool_use\",\"id\":\"m1\",\"name\":\"query\",\"input\":{\"q\":1},\"server_name\":\"s\"},"
                + "{\"type\":\"text\",\"text\":\"B\"},"
                + "{\"type\":\"thinking\",\"thinking\":\"second\",\"signature\":\"sig_2\"}]";
        ChatStreamContext ctx = newCtx(false);
        parser.parseNonStreamResponse(ctx, "{\"model\":\"claude-sonnet-4-5\",\"content\":" + content + "}");
        AssistantMessage message = ctx.getAccumulator().snapshotTerminal().getMessage();
        assertTrue(message.hasProtocolState(AnthropicMessageStateSupport.PROTOCOL_ID));
        assertFalse(ChatMessage.toJson(message).contains("contentRaw"));
        AssistantMessage restored = (AssistantMessage) ChatMessage.fromJson(ChatMessage.toJson(message));
        ONode replay = build(ChatOptions.of(), Arrays.asList(restored)).get("messages").get(0).get("content");
        assertEquals(ONode.ofJson(content).toJson(), replay.toJson());
        assertEquals(5, replay.size(), "完整载体优先回放时不得再追加旧分类载体");
    }

    @Test
    public void foreignOrStaleProtocolStateIsNotReplayed() {
        AssistantMessage foreignThinking = AssistantMessage.snapshot(
                "", "foreign", null, null, null, null,
                Collections.singletonMap("openai.responses", new MessageProtocolState(1,
                        Collections.<String, Object>singletonMap("reasoningItemId", "rs_1"))));
        ONode foreignRoot = build(ChatOptions.of(), Arrays.asList(ChatMessage.ofUser("hi"), foreignThinking));
        assertEquals(1, foreignRoot.get("messages").size(), foreignRoot.toJson());

        Map<String, Object> oldData = new LinkedHashMap<>();
        oldData.put(AnthropicResponseParser.CONTENT_BLOCKS_RAW_KEY,
                Collections.singletonList("{\"type\":\"text\",\"text\":\"old\"}"));
        AssistantMessage unbound = restoreWithProtocolState(
                "{\"role\":\"assistant\",\"text\":\"new\",\"thinking\":\"\"}",
                AnthropicMessageStateSupport.PROTOCOL_ID,
                new MessageProtocolState(AnthropicMessageStateSupport.VERSION, oldData));
        ONode unboundContent = build(ChatOptions.of(), Collections.<ChatMessage>singletonList(unbound))
                .get("messages").get(0).get("content");
        assertEquals("new", unboundContent.getString(), "缺失 semanticHash 的新状态必须 fail-closed");

        AssistantMessage old = AssistantMessage.snapshot(
                "old", "", null, null, null, null,
                Collections.singletonMap(AnthropicMessageStateSupport.PROTOCOL_ID,
                        new MessageProtocolState(AnthropicMessageStateSupport.VERSION, oldData)));
        AssistantMessage changed = AssistantMessage.snapshot(
                "new", "", null, null, null, null,
                Collections.singletonMap(AnthropicMessageStateSupport.PROTOCOL_ID,
                        old.getProtocolState(AnthropicMessageStateSupport.PROTOCOL_ID)));

        ONode changedContent = build(ChatOptions.of(), Collections.<ChatMessage>singletonList(changed))
                .get("messages").get(0).get("content");
        assertEquals("new", changedContent.getString(), "语义变更后必须降级为通用字段重建");
    }

    @Test
    public void targetStateWrongVersionFailsClosedEvenWithLegacyRaw() {
        Map<String, Object> wrongVersionData = new LinkedHashMap<>();
        wrongVersionData.put(AnthropicResponseParser.CONTENT_BLOCKS_RAW_KEY,
                Collections.singletonList("{\"type\":\"text\",\"text\":\"wrong-version\"}"));
        AssistantMessage message = restoreWithProtocolState(
                "{\"role\":\"assistant\",\"text\":\"generic\"," +
                        "\"contentRaw\":{\"anthropicContentBlocks\":[" +
                        "\"{\\\"type\\\":\\\"text\\\",\\\"text\\\":\\\"legacy\\\"}\"]}}",
                AnthropicMessageStateSupport.PROTOCOL_ID,
                new MessageProtocolState(AnthropicMessageStateSupport.VERSION + 1, wrongVersionData));

        ONode content = build(ChatOptions.of(), Collections.<ChatMessage>singletonList(message))
                .get("messages").get(0).get("content");
        assertTrue(content.isString(), content.toJson());
        assertEquals("generic", content.getString(),
                "目标协议状态存在但版本错误时不得回退到可能同样陈旧的 legacy raw");
    }

    @Test
    public void foreignStateDoesNotSuppressLegacyAnthropicRaw() {
        AssistantMessage message = restoreWithProtocolState(
                "{\"role\":\"assistant\",\"text\":\"generic\"," +
                        "\"contentRaw\":{\"anthropicContentBlocks\":[" +
                        "\"{\\\"type\\\":\\\"text\\\",\\\"text\\\":\\\"legacy-anthropic\\\"}\"]}}",
                "openai.responses",
                new MessageProtocolState(1,
                        Collections.<String, Object>singletonMap("reasoningItemId", "rs_1")));

        ONode content = build(ChatOptions.of(), Collections.<ChatMessage>singletonList(message))
                .get("messages").get(0).get("content");
        assertEquals("legacy-anthropic", content.get(0).get("text").getString(),
                "外协议状态不能阻止 Anthropic 对自身 legacy raw 的兼容读取");
    }

    @Test
    public void foreignAndAnthropicStatesRemainIsolatedOnSameMessage() {
        Map<String, Object> anthropicData = new LinkedHashMap<>();
        anthropicData.put(AnthropicResponseParser.CONTENT_BLOCKS_RAW_KEY,
                Collections.singletonList("{\"type\":\"text\",\"text\":\"anthropic\"}"));
        Map<String, MessageProtocolState> states = new LinkedHashMap<>();
        states.put("openai.responses", new MessageProtocolState(7,
                Collections.<String, Object>singletonMap("item", "foreign")));
        states.put(AnthropicMessageStateSupport.PROTOCOL_ID,
                new MessageProtocolState(AnthropicMessageStateSupport.VERSION, anthropicData));
        AssistantMessage message = AssistantMessage.snapshot(
                "anthropic", "", null, null, null, null, states);

        ONode content = build(ChatOptions.of(), Collections.<ChatMessage>singletonList(message))
                .get("messages").get(0).get("content");
        assertEquals("anthropic", content.get(0).get("text").getString());
        assertEquals("foreign", message.getProtocolState("openai.responses").getData().get("item"),
                "读取 Anthropic 状态不得串改同消息上的外协议状态");
    }

    @Test
    public void damagedOrderedBlocksFallBackAtomicallyToGenericSemantics() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put(AnthropicResponseParser.CONTENT_BLOCKS_RAW_KEY, Arrays.asList(
                "{\"type\":\"text\",\"text\":\"stale-prefix\"}",
                "{broken"));
        ToolCall call = new ToolCall("0", "toolu_fallback", "fallback_tool", "{}",
                new LinkedHashMap<String, Object>());
        AssistantMessage message = AssistantMessage.snapshot(
                "generic-text", "", Collections.singletonList(call), null,
                null, null,
                Collections.singletonMap(AnthropicMessageStateSupport.PROTOCOL_ID,
                        new MessageProtocolState(AnthropicMessageStateSupport.VERSION, data)));

        ONode content = build(ChatOptions.of(), Collections.<ChatMessage>singletonList(message))
                .get("messages").get(0).get("content");
        assertEquals(2, content.size(), content.toJson());
        assertEquals("generic-text", content.get(0).get("text").getString());
        assertEquals("tool_use", content.get(1).get("type").getString());
        assertEquals("fallback_tool", content.get(1).get("name").getString());
        assertFalse(content.toJson().contains("stale-prefix"),
                "任一有序块损坏时不得部分回放已通过校验的前缀");
    }

    @Test
    public void structuredProtocolStateDataCanBeReplayedAfterJsonRestore() {
        Map<String, Object> textBlock = new LinkedHashMap<>();
        textBlock.put("type", "text");
        textBlock.put("text", "structured");
        Map<String, Object> container = new LinkedHashMap<>();
        container.put("id", "cnt_structured");
        container.put("expires_at", "ignored");
        Map<String, Object> data = new LinkedHashMap<>();
        data.put(AnthropicResponseParser.CONTENT_BLOCKS_RAW_KEY, Collections.singletonList(textBlock));
        data.put(AnthropicResponseParser.CONTAINER_RAW_KEY, container);
        AssistantMessage message = AssistantMessage.snapshot(
                "structured", "", null, null, null, null,
                Collections.singletonMap(AnthropicMessageStateSupport.PROTOCOL_ID,
                        new MessageProtocolState(AnthropicMessageStateSupport.VERSION, data)));
        AssistantMessage restored = (AssistantMessage) ChatMessage.fromJson(ChatMessage.toJson(message));

        ONode root = build(ChatOptions.of(), Collections.<ChatMessage>singletonList(restored));
        assertEquals("structured", root.get("messages").get(0).get("content").get(0).get("text").getString());
        assertEquals("cnt_structured", root.get("container").getString());
        assertFalse(root.toJson().contains("expires_at"));
    }

    @Test
    public void fragmentedStreamSignatureIsAssembledThenReplayed() {
        ChatStreamContext ctx = newCtx(true);
        parser.parseStreamResponse(ctx, "{\"type\":\"content_block_start\",\"index\":2,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}");
        parser.parseStreamResponse(ctx, "{\"type\":\"content_block_delta\",\"index\":2,\"delta\":{\"type\":\"text_delta\",\"text\":\"tail\"}}");
        parser.parseStreamResponse(ctx, "{\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"thinking\",\"thinking\":\"\",\"signature\":\"\"}}");
        parser.parseStreamResponse(ctx, "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"thinking_delta\",\"thinking\":\"plan\"}}");
        parser.parseStreamResponse(ctx, "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"signature_delta\",\"signature\":\"sig_\"}}");
        parser.parseStreamResponse(ctx, "{\"type\":\"content_block_start\",\"index\":1,\"content_block\":{\"type\":\"mcp_tool_use\",\"id\":\"m1\",\"name\":\"q\",\"input\":{}}}");
        parser.parseStreamResponse(ctx, "{\"type\":\"content_block_delta\",\"index\":1,\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"{\\\"x\\\":1}\"}}");
        parser.parseStreamResponse(ctx, "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"signature_delta\",\"signature\":\"abc\"}}");
        parser.parseStreamResponse(ctx, "{\"type\":\"message_stop\"}");

        AssistantMessage message = ctx.getAccumulator().snapshotTerminal().getMessage();
        ONode replay = build(ChatOptions.of(), Arrays.asList(message)).get("messages").get(0).get("content");
        assertEquals("thinking", replay.get(0).get("type").getString());
        assertEquals("plan", replay.get(0).get("thinking").getString());
        assertEquals("sig_abc", replay.get(0).get("signature").getString());
        assertEquals(1, replay.get(1).get("input").get("x").getInt());
        assertEquals("tail", replay.get(2).get("text").getString());
    }

    @Test
    public void nativeServerOnlyToolsCountForParallelAndCache() {
        Map<String, Object> tool = new LinkedHashMap<>();
        tool.put("type", "web_search_20250305");
        tool.put("name", "web_search");
        ONode root = build(ChatOptions.of().optionSet("tools", Collections.singletonList(tool))
                        .optionSet("parallel_tool_calls", false).cacheControl(CacheControl.ofEphemeral()),
                Collections.singletonList(ChatMessage.ofUser("search")));
        assertTrue(root.get("tool_choice").get("disable_parallel_tool_use").getBoolean());
        assertEquals("ephemeral", root.get("tools").get(0).get("cache_control").get("type").getString());
    }



    /**
     * 本地 tool_use 的开始、参数分片和结束都通过 ChatEvent 表达，参数聚合由 ChatAccumulator 完成。
     */
    @Test
    public void streamingTextAndToolKeepsRealOrderedStateAcrossSyntheticToolMessage() {
        ChatStreamContext ctx = newCtx(true);
        ChatAccumulator acc = ctx.getAccumulator();
        parser.parseStreamResponse(ctx, "{\"type\":\"content_block_start\",\"index\":0,"
                + "\"content_block\":{\"type\":\"text\",\"text\":\"\"}}");
        parser.parseStreamResponse(ctx, "{\"type\":\"content_block_delta\",\"index\":0,"
                + "\"delta\":{\"type\":\"text_delta\",\"text\":\"我先查询\"}}");
        parser.parseStreamResponse(ctx, "{\"type\":\"content_block_start\",\"index\":1,"
                + "\"content_block\":{\"type\":\"tool_use\",\"id\":\"toolu_1\",\"name\":\"search\",\"input\":{}}}");
        parser.parseStreamResponse(ctx, "{\"type\":\"content_block_delta\",\"index\":1,"
                + "\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"{\\\"q\\\":\\\"solon\\\"}\"}}");
        parser.parseStreamResponse(ctx, "{\"type\":\"content_block_stop\",\"index\":1}");
        parser.parseStreamResponse(ctx, "{\"type\":\"message_stop\"}");

        // 模拟核心工具递归构建的合成 assistant；它不得覆盖真实流式 parser 保存的有序块。
        ONode syntheticNode = requestBuilder.buildAssistantToolCallMessageNode(acc, acc.getToolCallBuilders());
        for (AssistantMessage item : AnthropicChatDialect.getInstance().parseAssistantMessage(acc, syntheticNode)) {
            acc.mergeTerminalMessage(item);
        }

        AssistantMessage message = acc.snapshotTerminal().getMessage();
        ONode replay = build(ChatOptions.of(), Collections.<ChatMessage>singletonList(message))
                .get("messages").get(0).get("content");
        assertEquals(2, replay.size(), replay.toJson());
        assertEquals("text", replay.get(0).get("type").getString());
        assertEquals("我先查询", replay.get(0).get("text").getString());
        assertEquals("tool_use", replay.get(1).get("type").getString());
        assertEquals("solon", replay.get(1).get("input").get("q").getString());
    }

    @Test
    public void toolUseArgsDeliveredAsShards() {
        ChatStreamContext ctx = newCtx(true);
        ChatAccumulator acc = ctx.getAccumulator();

        parser.parseStreamResponse(ctx, "{\"type\":\"content_block_start\",\"index\":0,"
                + "\"content_block\":{\"type\":\"tool_use\",\"id\":\"toolu_1\",\"name\":\"get_weather\",\"input\":{}}}");

        assertEquals(1, allOf(ChatEventType.TOOL_CALL_START).size(), "block start should open the tool call");
        assertEquals(0, allOf(ChatEventType.TOOL_CALL_ARGS_DELTA).size(), "empty input must not emit an args delta");

        parser.parseStreamResponse(ctx, "{\"type\":\"content_block_delta\",\"index\":0,"
                + "\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"{\\\"city\\\":\"}}");
        parser.parseStreamResponse(ctx, "{\"type\":\"content_block_delta\",\"index\":0,"
                + "\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"\\\"上海\\\"}\"}}");

        List<ChatEvent> args = allOf(ChatEventType.TOOL_CALL_ARGS_DELTA);
        assertEquals(2, args.size(), "每个 input_json_delta 应各自产生事件");
        assertEquals("{\"city\":", args.get(0).getText());
        assertEquals("\"上海\"}", args.get(1).getText());
        assertEquals("{\"city\":\"上海\"}", acc.getToolCallBuilders().get("idx:0").argumentsBuilder.toString());

        parser.parseStreamResponse(ctx, "{\"type\":\"content_block_stop\",\"index\":0}");
        assertEquals(0, allOf(ChatEventType.TOOL_CALL_END).size(),
                "content_block_stop 只释放协议状态，END 由完整流核心统一发出");
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

        assertEquals(1, allOf(ChatEventType.TOOL_CALL_START).size());
        List<ChatEvent> args = allOf(ChatEventType.TOOL_CALL_ARGS_DELTA);
        assertEquals(1, args.size(), "start 携带的 input 初值应作为一条参数增量发出");
        assertEquals("{\"a\":1}", args.get(0).getText());
        assertEquals(0, allOf(ChatEventType.TOOL_CALL_END).size(),
                "parser 不应抢在核心完整聚合前发 END");
        assertEquals("{\"a\":1}", acc.getToolCallBuilders().get("idx:2").argumentsBuilder.toString());
    }

    @Test
    public void initialInputFollowedByDeltaUsesOneValidAuthoritativeObject() {
        ChatStreamContext ctx = newCtx(true);
        ChatAccumulator acc = ctx.getAccumulator();

        parser.parseStreamResponse(ctx, "{\"type\":\"content_block_start\",\"index\":4,"
                + "\"content_block\":{\"type\":\"tool_use\",\"id\":\"toolu_mix\",\"name\":\"f\","
                + "\"input\":{\"from_start\":1}}}");
        parser.parseStreamResponse(ctx, "{\"type\":\"content_block_delta\",\"index\":4,"
                + "\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"{\\\"from_delta\\\":2}\"}}");
        parser.parseStreamResponse(ctx, "{\"type\":\"content_block_stop\",\"index\":4}");

        List<ChatEvent> args = allOf(ChatEventType.TOOL_CALL_ARGS_DELTA);
        assertEquals(1, args.size(), "兼容形态应延迟决策，不能发出 start JSON 后再直接拼 delta JSON");
        assertEquals("{\"from_delta\":2}", args.get(0).getText());
        assertEquals("{\"from_delta\":2}",
                acc.getToolCallBuilders().get("idx:4").argumentsBuilder.toString());
        assertEquals(1, acc.getToolCallBuilders().size(), "通用工具调用不得因兼容参数形态而静默丢失");
    }

    @Test
    public void invalidDeltaAfterInitialInputFallsBackToValidInitialObject() {
        ChatStreamContext ctx = newCtx(true);
        ChatAccumulator acc = ctx.getAccumulator();

        parser.parseStreamResponse(ctx, "{\"type\":\"content_block_start\",\"index\":5,"
                + "\"content_block\":{\"type\":\"tool_use\",\"id\":\"toolu_partial\",\"name\":\"f\","
                + "\"input\":{\"safe\":true}}}");
        parser.parseStreamResponse(ctx, "{\"type\":\"content_block_delta\",\"index\":5,"
                + "\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\",\\\"tail\\\":1}\"}}");
        parser.parseStreamResponse(ctx, "{\"type\":\"content_block_stop\",\"index\":5}");

        assertEquals("{\"safe\":true}",
                acc.getToolCallBuilders().get("idx:5").argumentsBuilder.toString(),
                "无法可靠解释 delta 时应保留 start 的合法对象，不能生成非法累计串");
        assertEquals(1, allOf(ChatEventType.TOOL_CALL_START).size());
    }

    /**
     * 非流式没有流末核心补位，工具调用必须在完整响应内发出唯一的完整生命周期。
     */
    @Test
    public void nonStreamToolUseEmitsCompleteLifecycle() {
        ChatStreamContext ctx = newCtx(false);

        parser.parseNonStreamResponse(ctx, "{\"model\":\"claude-sonnet-4-5\",\"stop_reason\":\"tool_use\","
                + "\"content\":[{\"type\":\"text\",\"text\":\"先查一下\"},"
                + "{\"type\":\"tool_use\",\"id\":\"toolu_1\",\"name\":\"get_weather\","
                + "\"input\":{\"city\":\"杭州\"}}]}");

        List<ChatEvent> starts = allOf(ChatEventType.TOOL_CALL_START);
        List<ChatEvent> args = allOf(ChatEventType.TOOL_CALL_ARGS_DELTA);
        List<ChatEvent> ends = allOf(ChatEventType.TOOL_CALL_END);
        assertEquals(1, starts.size());
        assertEquals(1, args.size());
        assertEquals(1, ends.size());
        assertEquals(1, starts.get(0).getIndex());
        assertEquals(1, args.get(0).getIndex());
        assertEquals(1, ends.get(0).getIndex());
        assertEquals("{\"city\":\"杭州\"}", args.get(0).getText());
        assertEquals("toolu_1", ends.get(0).getToolCallId());
        assertEquals("get_weather", ends.get(0).getToolCall().getName());
        assertEquals("杭州", ends.get(0).getToolCall().getArguments().get("city"));
        assertEquals(1, ctx.getAccumulator().snapshotTerminal().getToolCalls().size());
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
        parser.parseStreamResponse(ctx, "{\"type\":\"content_block_stop\",\"index\":0}");

        List<ChatEvent> deltas = allOf(ChatEventType.SERVER_TOOL_ARGS_DELTA);
        assertEquals(1, deltas.size(), "start 里的 input 初值应发一条参数事件");
        assertEquals("{\"query\":\"solon\"}", deltas.get(0).getText());
        assertEquals("srv_1", deltas.get(0).getToolCallId());

        //服务端工具不是本地 function call，不得进入本地工具聚合
        assertTrue(ctx.getAccumulator().snapshotTerminal().getToolCalls().isEmpty());
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
        assertEquals(0, citations.get(0).getIndex());
        assertEquals("web_search_result_location", citations.get(0).getRaw().get("type").getString());
        Citation webCitation = citations.get(0).getCitation();
        assertNotNull(webCitation);
        assertEquals("web_search_result_location", webCitation.getType());
        assertEquals("A", webCitation.getTitle());
        assertEquals("https://a.dev/x", webCitation.getUrl());
        assertNull(webCitation.getCitedText());
        //文档类定位没有 url，取 cited_text
        assertEquals("page_location", citations.get(1).getSubType());
        assertEquals("第 3 页原文", citations.get(1).getText());
        Citation pageCitation = citations.get(1).getCitation();
        assertNotNull(pageCitation);
        assertEquals("page_location", pageCitation.getType());
        assertEquals("手册", pageCitation.getTitle());
        assertNull(pageCitation.getUrl());
        assertEquals("第 3 页原文", pageCitation.getCitedText());

        ChatResponse terminal = ctx.getAccumulator().snapshotTerminal();
        assertTrue(terminal.isTerminal());
        assertEquals("据报道", terminal.getText());
        assertEquals(2, terminal.getCitations().size());
        assertEquals("A", terminal.getCitations().get(0).getTitle());
        assertEquals("手册", terminal.getCitations().get(1).getTitle());
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

        List<ChatEvent> citations = allOf(ChatEventType.CITATION);
        assertEquals(1, citations.size());
        assertEquals("https://b.dev", citations.get(0).getText());
        assertEquals(0, citations.get(0).getIndex());
        assertEquals("search_result_location", citations.get(0).getCitation().getType());
        assertEquals("https://b.dev", citations.get(0).getCitation().getUrl());
        assertEquals(1, ctx.getAccumulator().snapshotTerminal().getCitations().size());
    }

    /**
     * 标准流式 citations_delta 必须合并进最终 text 块，并在下一轮请求中原样回放。
     */
    @Test
    public void streamCitationDeltasLandInTerminalBlocksAndReplay() {
        ChatStreamContext ctx = newCtx(true);

        parser.parseStreamResponse(ctx, "{\"type\":\"content_block_start\",\"index\":0,"
                + "\"content_block\":{\"type\":\"text\",\"text\":\"\"}}");
        parser.parseStreamResponse(ctx, "{\"type\":\"content_block_delta\",\"index\":0,"
                + "\"delta\":{\"type\":\"text_delta\",\"text\":\"据报道\"}}");
        String firstCitationDelta = "{\"type\":\"content_block_delta\",\"index\":0,"
                + "\"delta\":{\"type\":\"citations_delta\",\"citation\":{"
                + "\"type\":\"web_search_result_location\",\"url\":\"https://a.dev/x\","
                + "\"title\":\"A\",\"cited_text\":\"来源 A\"}}}";
        assertTrue(parser.parseStreamResponse(ctx, firstCitationDelta));
        assertTrue(parser.parseStreamResponse(ctx, firstCitationDelta),
                "兼容网关重放同一 citation delta 时仍应视为已消费，只是不重复发事件或写终态块");
        parser.parseStreamResponse(ctx, "{\"type\":\"content_block_delta\",\"index\":0,"
                + "\"delta\":{\"type\":\"citations_delta\",\"citation\":{"
                + "\"type\":\"page_location\",\"document_index\":1,\"start_page_number\":2,"
                + "\"end_page_number\":2,\"cited_text\":\"来源 B\",\"document_title\":\"手册\"}}}");
        parser.parseStreamResponse(ctx, "{\"type\":\"message_stop\"}");

        AssistantMessage message = ctx.getAccumulator().snapshotTerminal().getMessage();
        Map<?, ?> contentRaw = AnthropicMessageStateSupport.resolveData(message);
        List<?> blocks = (List<?>) contentRaw.get("anthropicContentBlocks");
        assertEquals(1, blocks.size());

        ONode terminalBlock = ONode.ofJson((String) blocks.get(0));
        assertEquals("据报道", terminalBlock.get("text").getString());
        assertEquals(2, terminalBlock.get("citations").size());
        assertEquals("https://a.dev/x", terminalBlock.get("citations").get(0).get("url").getString());
        assertEquals("来源 B", terminalBlock.get("citations").get(1).get("cited_text").getString());

        ONode replay = build(ChatOptions.of(), Collections.singletonList(message))
                .get("messages").get(0).get("content").get(0);
        assertEquals(terminalBlock.toJson(), replay.toJson());
        List<ChatEvent> citationEvents = allOf(ChatEventType.CITATION);
        assertEquals(2, citationEvents.size(), "重复引用 delta 不应产生重复事件");
        Citation first = citationEvents.get(0).getCitation();
        assertNotNull(first);
        assertEquals("web_search_result_location", first.getType());
        assertEquals("A", first.getTitle());
        assertEquals("https://a.dev/x", first.getUrl());
        assertEquals("来源 A", first.getCitedText());
        assertEquals(0, citationEvents.get(0).getIndex());
        assertEquals("content_block_delta", citationEvents.get(0).getRaw().get("type").getString());

        List<Citation> terminalCitations = ctx.getAccumulator().snapshotTerminal().getCitations();
        assertEquals(2, terminalCitations.size());
        assertEquals("手册", terminalCitations.get(1).getTitle());
        assertEquals("来源 B", terminalCitations.get(1).getCitedText());
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
                + "{\"type\":\"web_search_result\",\"id\":\"result_1\",\"index\":4,"
                + "\"title\":\"T\",\"url\":\"u\",\"snippet\":\"摘要\",\"encrypted_content\":\"enc_xyz\"}]}"
                + "]}");

        ChatResponse terminal = ctx.getAccumulator().snapshotTerminal();
        Map<?, ?> contentRaw = AnthropicMessageStateSupport.resolveData(terminal.getMessage());
        assertNotNull(contentRaw, "服务端工具轮次必须留下可回传的 contentRaw");

        List<?> blocks = (List<?>) contentRaw.get("anthropicServerToolBlocks");
        assertEquals(2, blocks.size());
        assertTrue(String.valueOf(blocks.get(1)).contains("enc_xyz"),
                "encrypted_content 是服务端签发的 opaque 凭证，必须原样留存");
        assertTrue(String.valueOf(contentRaw.get("anthropicContainer")).contains("cnt_1"));

        List<SearchResult> searchResults = terminal.getSearchResults();
        assertEquals(1, searchResults.size());
        assertEquals(Integer.valueOf(4), searchResults.get(0).getIndex());
        assertEquals("result_1", searchResults.get(0).getId());
        assertEquals("T", searchResults.get(0).getTitle());
        assertEquals("u", searchResults.get(0).getUrl());
        assertEquals("摘要", searchResults.get(0).getSnippet());
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

        ChatResponse terminal = ctx.getAccumulator().snapshotTerminal();
        Map<?, ?> contentRaw = AnthropicMessageStateSupport.resolveData(terminal.getMessage());
        assertNotNull(contentRaw, "pause_turn 续跑需要一个能带走服务端块的载体帧");
        List<?> blocks = (List<?>) contentRaw.get("anthropicServerToolBlocks");
        assertEquals(2, blocks.size());
        assertTrue(String.valueOf(blocks.get(1)).contains("enc_abc"));
        assertTrue(String.valueOf(contentRaw.get("anthropicContainer")).contains("cnt_9"));
    }

    /**
     * 流式 server_tool_use 从空 input 起步时，块尾必须把所有 input_json_delta
     * 回填到终态 contentRaw 的回放块，不能保留最初的空对象。
     */
    @Test
    public void streamServerToolDeltaInputCompletedInTerminalReplayBlock() {
        ChatStreamContext ctx = newCtx(true);

        parser.parseStreamResponse(ctx, "{\"type\":\"content_block_start\",\"index\":0,"
                + "\"content_block\":{\"type\":\"server_tool_use\",\"id\":\"srv_1\","
                + "\"name\":\"web_search\",\"input\":{}}}");
        parser.parseStreamResponse(ctx, "{\"type\":\"content_block_delta\",\"index\":0,"
                + "\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"{\\\"query\\\":\"}}");
        parser.parseStreamResponse(ctx, "{\"type\":\"content_block_delta\",\"index\":0,"
                + "\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"\\\"solon\\\"}\"}}");
        parser.parseStreamResponse(ctx, "{\"type\":\"content_block_stop\",\"index\":0}");
        parser.parseStreamResponse(ctx, "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"pause_turn\"}}");
        parser.parseStreamResponse(ctx, "{\"type\":\"message_stop\"}");

        List<ChatEvent> deltas = allOf(ChatEventType.SERVER_TOOL_ARGS_DELTA);
        assertEquals(2, deltas.size(), "参数分片应逐片交付");
        assertEquals("{\"query\":", deltas.get(0).getText());
        assertEquals("\"solon\"}", deltas.get(1).getText());

        ChatResponse terminal = ctx.getAccumulator().snapshotTerminal();
        Map<?, ?> contentRaw = AnthropicMessageStateSupport.resolveData(terminal.getMessage());
        assertNotNull(contentRaw, "message_stop 应生成服务端工具终态载体");
        List<?> blocks = (List<?>) contentRaw.get("anthropicServerToolBlocks");
        assertEquals(1, blocks.size());

        ONode replayBlock = ONode.ofJson((String) blocks.get(0));
        assertEquals("server_tool_use", replayBlock.get("type").getString());
        assertEquals("srv_1", replayBlock.get("id").getString());
        assertEquals("web_search", replayBlock.get("name").getString());
        assertEquals("solon", replayBlock.get("input").get("query").getString(),
                "回放块 input 必须是所有 delta 拼成的完整参数");
        assertTrue(terminal.getToolCalls().isEmpty(), "server_tool_use 不应进入本地工具调用");
    }

    /**
     * 出站：历史里的服务端工具块原样回传，位置在 text 之前（与真实响应的块序一致）。
     */
    @Test
    public void serverToolBlocksReplayedInRequest() {
        AssistantMessage history = (AssistantMessage) ChatMessage.fromJson(
                "{\"role\":\"assistant\",\"text\":\"查到了\"," +
                        "\"contentRaw\":{\"anthropicServerToolBlocks\":[" +
                        "\"{\\\"type\\\":\\\"server_tool_use\\\",\\\"id\\\":\\\"srv_1\\\"," +
                        "\\\"name\\\":\\\"web_search\\\",\\\"input\\\":{\\\"query\\\":\\\"q\\\"}}\"," +
                        "\"{\\\"type\\\":\\\"web_search_tool_result\\\",\\\"tool_use_id\\\":\\\"srv_1\\\"," +
                        "\\\"content\\\":[{\\\"type\\\":\\\"web_search_result\\\",\\\"url\\\":\\\"u\\\"," +
                        "\\\"encrypted_content\\\":\\\"enc_xyz\\\"}]}\"]}}");

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
        AssistantMessage history = (AssistantMessage) ChatMessage.fromJson(
                "{\"role\":\"assistant\",\"text\":\"done\"," +
                        "\"contentRaw\":{\"anthropicContainer\":" +
                        "\"{\\\"id\\\":\\\"cnt_1\\\",\\\"expires_at\\\":\\\"2026-01-01T00:00:00Z\\\"}\"}}");

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
        AssistantMessage history = (AssistantMessage) ChatMessage.fromJson(
                "{\"role\":\"assistant\",\"text\":\"done\"," +
                        "\"contentRaw\":{\"anthropicContainer\":\"{\\\"id\\\":\\\"cnt_old\\\"}\"}}");

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
        assertTrue(tool0.get("input_schema").get("properties").isObject());
        assertTrue(tool0.get("input_schema").get("properties").getObject().isEmpty(),
                "无参数工具的 properties 必须是空对象，不能生成空字符串属性名");
        assertFalse(tool0.hasKey("cache_control"));
    }

    @Test
    public void emptyOrInvalidToolSchemaFallsBackToEmptyPropertiesObject() {
        ONode empty = build(ChatOptions.of()
                        .toolAdd("empty", t -> t.description("d").inputSchema("")),
                Collections.singletonList(ChatMessage.ofUser("hi")));
        ONode emptyProperties = empty.get("tools").get(0).get("input_schema").get("properties");
        assertTrue(emptyProperties.isObject());
        assertTrue(emptyProperties.getObject().isEmpty());

        ONode invalid = build(ChatOptions.of()
                        .toolAdd("invalid", t -> t.description("d").inputSchema("{invalid")),
                Collections.singletonList(ChatMessage.ofUser("hi")));
        ONode invalidProperties = invalid.get("tools").get(0).get("input_schema").get("properties");
        assertTrue(invalidProperties.isObject());
        assertTrue(invalidProperties.getObject().isEmpty());
    }

    @Test
    public void nonObjectToolSchemasFallBackToEmptyObject() {
        for (String schema : Arrays.asList("[]", "\"string\"", "123", "null", "{\"type\":\"string\"}")) {
            ONode root = build(ChatOptions.of()
                            .toolAdd("invalid_root", t -> t.description("d").inputSchema(schema)),
                    Collections.singletonList(ChatMessage.ofUser("hi")));
            ONode inputSchema = root.get("tools").get(0).get("input_schema");
            assertEquals("object", inputSchema.get("type").getString(), schema);
            assertTrue(inputSchema.get("properties").isObject(), schema);
            assertTrue(inputSchema.get("properties").getObject().isEmpty(), schema);
        }
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
    private AssistantMessage restoreWithProtocolState(String json, String protocol,
                                                      MessageProtocolState state) {
        ONode node = ONode.ofJson(json);
        ONode stateNode = node.getOrNew("protocolStates").getOrNew(protocol);
        stateNode.set("version", state.getVersion());
        ONode data = stateNode.getOrNew("data");
        for (Map.Entry<String, Object> entry : state.getData().entrySet()) {
            data.set(entry.getKey(), plain(entry.getValue()));
        }
        return (AssistantMessage) ChatMessage.fromJson(node);
    }

    private static Object plain(Object value) {
        if (value instanceof Map) {
            Map<String, Object> map = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                map.put(String.valueOf(entry.getKey()), plain(entry.getValue()));
            }
            return map;
        }
        if (value instanceof java.util.List) {
            java.util.List<Object> list = new java.util.ArrayList<>();
            for (Object item : (java.util.List<?>) value) {
                list.add(plain(item));
            }
            return list;
        }
        return value;
    }

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
