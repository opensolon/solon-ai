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
import org.noear.snack4.ONode;
import org.noear.solon.ai.chat.ChatChoice;
import org.noear.solon.ai.chat.ChatConfig;
import org.noear.solon.ai.chat.ChatOptions;
import org.noear.solon.ai.chat.ChatRequest;
import org.noear.solon.ai.chat.ChatResponseDefault;
import org.noear.solon.ai.chat.content.AudioBlock;
import org.noear.solon.ai.chat.content.ContentBlock;
import org.noear.solon.ai.chat.content.ImageBlock;
import org.noear.solon.ai.chat.content.TextBlock;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.message.ToolMessage;
import org.noear.solon.ai.chat.session.InMemoryChatSession;
import org.noear.solon.ai.chat.tool.ToolCall;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * OpenAI Responses 方言适配单元测试
 * <p>
 * 对齐 OpenAI 官方 Responses API 规范（openapi.transformed.yml / openai-java SDK 模型类）：
 * <ul>
 *   <li>EasyInputMessage 的 content 仅接受 input_text / input_image / input_file</li>
 *   <li>input_audio 为嵌套形态 {@code {type:input_audio, input_audio:{data,format}}}</li>
 *   <li>ResponseReasoningItem 的 summary 为必填数组</li>
 *   <li>ToolChoiceFunction 为扁平形态 {@code {type:function, name}}</li>
 *   <li>ResponseUsage.input_tokens_details 含 cached_tokens / cache_write_tokens</li>
 *   <li>status=incomplete 时按 incomplete_details.reason 回填 finishReason</li>
 * </ul>
 */
public class OpenaiResponsesDialectTest {
    private final OpenaiResponsesRequestBuilder builder = new OpenaiResponsesRequestBuilder();
    private final OpenaiResponsesResponseParser parser = new OpenaiResponsesResponseParser();

    private ChatResponseDefault newResponse(boolean stream) {
        ChatConfig config = new ChatConfig();
        ChatOptions options = ChatOptions.of();
        ChatRequest req = new ChatRequest(config, OpenaiResponsesDialect.getInstance(), options,
                InMemoryChatSession.builder().build(), ChatMessage.ofSystem("test"), null, stream);
        return new ChatResponseDefault(req, stream);
    }

    private ONode build(ChatOptions options, List<ChatMessage> messages) {
        ChatConfig config = new ChatConfig();
        config.setModel("gpt-5.4");
        return builder.build(config, options, messages, false);
    }

    // ==================== input items 形态 ====================

    @Test
    public void assistantMultiModalHistory_useInputContentTypes() {
        // 官方约束：EasyInputMessage(role=assistant) 的 content 不接受 output_text，
        // 与 input_image 混排会被 400；统一走 input_* 形态
        List<ContentBlock> blocks = new ArrayList<>();
        blocks.add(TextBlock.of("看图说话"));
        blocks.add(ImageBlock.ofUrl("https://x.com/a.png"));

        AssistantMessage msg = ChatMessage.ofAssistant("看图说话", blocks);
        ONode root = build(ChatOptions.of(), Collections.singletonList(msg));

        String json = root.toJson();
        assertFalse(json.contains("output_text"), "assistant 输入项不应写 output_text: " + json);
        assertTrue(json.contains("\"input_text\""), json);
        assertTrue(json.contains("\"input_image\""), json);
    }

    @Test
    public void audioBlock_useNestedInputAudio() {
        // 官方 ResponseInputAudio：{type:input_audio, input_audio:{data,format}}
        List<ContentBlock> blocks = new ArrayList<>();
        blocks.add(AudioBlock.ofBase64("AAAA", "audio/wav"));

        ONode root = build(ChatOptions.of(),
                Collections.singletonList(ChatMessage.ofUser("听一下", blocks)));

        ONode audioItem = null;
        for (ONode item : root.get("input").get(0).get("content").getArray()) {
            if ("input_audio".equals(item.get("type").getString())) {
                audioItem = item;
            }
        }

        assertNotNull(audioItem, "应写出 input_audio 项: " + root.toJson());
        assertTrue(audioItem.get("input_audio").isObject(), "input_audio 应为嵌套对象: " + root.toJson());
        assertEquals("AAAA", audioItem.get("input_audio").get("data").getString());
        assertEquals("wav", audioItem.get("input_audio").get("format").getString());
    }

    @Test
    public void reasoningItem_alwaysCarrySummary() {
        // 官方 ResponseReasoningItem.summary 为必填（可为空数组），缺失会 400
        AssistantMessage thinking = new AssistantMessage("", "先分析一下", true);
        ONode root = build(ChatOptions.of(), Collections.singletonList(thinking));

        ONode item = root.get("input").get(0);
        assertEquals("reasoning", item.get("type").getString());
        assertTrue(item.get("summary").isArray(), "reasoning 项必须含 summary 数组: " + root.toJson());
        assertEquals("reasoning_text", item.get("content").get(0).get("type").getString());
        assertEquals("先分析一下", item.get("content").get(0).get("text").getString());
    }

    @Test
    public void reasoningItem_echoServerIdWithSummary() {
        AssistantMessage thinking = new AssistantMessage("", "x", true);
        thinking.getMetadata().put("reasoning_item_id", "rs_123");
        thinking.getMetadata().put("reasoning_encrypted_content", "enc_abc");

        ONode root = build(ChatOptions.of(), Collections.singletonList(thinking));

        ONode item = root.get("input").get(0);
        assertEquals("rs_123", item.get("id").getString());
        assertEquals("enc_abc", item.get("encrypted_content").getString());
        assertTrue(item.get("summary").isArray(), root.toJson());
    }

    // ==================== options 归一 ====================

    @Test
    public void toolChoice_flattenedForResponses() {
        // Chat Completions: {type:function, function:{name}} → Responses: {type:function, name}
        Map<String, Object> func = new HashMap<>();
        func.put("name", "getWeather");
        Map<String, Object> toolChoice = new HashMap<>();
        toolChoice.put("type", "function");
        toolChoice.put("function", func);

        ChatOptions options = ChatOptions.of().optionSet("tool_choice", toolChoice);
        ONode root = build(options, Collections.singletonList(ChatMessage.ofUser("hi")));

        ONode node = root.get("tool_choice");
        assertEquals("function", node.get("type").getString());
        assertEquals("getWeather", node.get("name").getString());
        assertFalse(node.hasKey("function"), "不应保留嵌套 function: " + root.toJson());
    }

    @Test
    public void toolChoice_stringPassthrough() {
        ChatOptions options = ChatOptions.of().optionSet("tool_choice", "required");
        ONode root = build(options, Collections.singletonList(ChatMessage.ofUser("hi")));

        assertEquals("required", root.get("tool_choice").getString());
    }

    @Test
    public void textFormatOption_keptAsNode() {
        // prepareOutputFormatOptions 写入的是 ONode，不能再走 ofBean 二次序列化
        // （样本用 json_schema：Responses 的 text.format 无 json_object 类型，避免误导）
        ChatOptions options = ChatOptions.of();
        ONode format = new ONode().set("type", "json_schema");
        options.optionSet("text", new ONode().set("format", format));

        ONode root = build(options, Collections.singletonList(ChatMessage.ofUser("hi")));

        assertEquals("json_schema", root.get("text").get("format").get("type").getString());
    }

    @Test
    public void outputFormat_invalidSchema_fallbackToText() {
        // Responses 的 text.format 仅接受 text/json_schema/grammar：
        // 非法 schema 降级为 text（而非 Chat 协议的 json_object），保证请求可合法出站
        ChatOptions options = ChatOptions.of().outputSchema("{not a valid json");
        OpenaiResponsesDialect.getInstance().prepareOutputFormatOptions(options);

        ONode root = build(options, Collections.singletonList(ChatMessage.ofUser("hi")));

        ONode format = root.get("text").get("format");
        assertEquals("text", format.get("type").getString(), root.toJson());
        assertFalse(format.hasKey("name"), "降级后不应残留 json_schema 专属字段: " + root.toJson());
    }

    @Test
    public void outputFormat_validSchema_buildsJsonSchema() {
        ChatOptions options = ChatOptions.of().outputSchema("{\"type\":\"object\",\"properties\":{\"city\":{\"type\":\"string\"}}}");
        OpenaiResponsesDialect.getInstance().prepareOutputFormatOptions(options);

        ONode root = build(options, Collections.singletonList(ChatMessage.ofUser("hi")));

        ONode format = root.get("text").get("format");
        assertEquals("json_schema", format.get("type").getString(), root.toJson());
        assertEquals("output_schema", format.get("name").getString());
        assertTrue(format.get("schema").get("properties").hasKey("city"), root.toJson());
    }

    @Test
    public void toolMessage_nullContent_outputFallbackEmpty() {
        // output 为官方必填字段：工具无返回（null）时兜底空串，避免端点 400
        ToolMessage tool = ChatMessage.ofTool(null, "getWeather", "call_1");

        ONode root = build(ChatOptions.of(), Collections.singletonList(tool));

        ONode item = root.get("input").get(0);
        assertEquals("function_call_output", item.get("type").getString());
        assertEquals("call_1", item.get("call_id").getString());
        assertEquals("", item.get("output").getString(), "null 应兜底为空串: " + root.toJson());
        assertNotNull(item.get("output"), "output 字段必须存在: " + root.toJson());
    }

    @Test
    public void maxTokens_mappedAndNotOverriding() {
        ChatOptions options = ChatOptions.of()
                .optionSet("max_tokens", 100)
                .optionSet("max_output_tokens", 200);

        ONode root = build(options, Collections.singletonList(ChatMessage.ofUser("hi")));

        assertEquals(200, root.get("max_output_tokens").getInt(), "显式 max_output_tokens 优先: " + root.toJson());
    }

    @Test
    public void unsupportedChatCompletionsOptions_dropped() {
        ChatOptions options = ChatOptions.of()
                .optionSet("stop", Arrays.asList("\n"))
                .optionSet("frequency_penalty", 0.5)
                .optionSet("stream_options", Collections.singletonMap("include_usage", true))
                .optionSet("temperature", 0.7);

        ONode root = build(options, Collections.singletonList(ChatMessage.ofUser("hi")));

        assertFalse(root.hasKey("stop"), root.toJson());
        assertFalse(root.hasKey("frequency_penalty"), root.toJson());
        assertFalse(root.hasKey("stream_options"), root.toJson());
        assertTrue(root.hasKey("temperature"), "受支持的参数应保留: " + root.toJson());
    }

    @Test
    public void instructions_mergedWithSystemMessage() {
        ChatOptions options = ChatOptions.of().optionSet("instructions", "额外要求");
        List<ChatMessage> messages = Arrays.asList(
                ChatMessage.ofSystem("你是助手"),
                ChatMessage.ofUser("hi"));

        ONode root = build(options, messages);

        String instructions = root.get("instructions").getString();
        assertTrue(instructions.contains("你是助手"), instructions);
        assertTrue(instructions.contains("额外要求"), instructions);
    }

    // ==================== 非流式解析 ====================

    @Test
    public void nonStream_thinkingAndTextInOneMessage() {
        ChatResponseDefault resp = newResponse(false);
        String json = "{\"id\":\"resp_1\",\"model\":\"gpt-5.4\",\"status\":\"completed\",\"output\":["
                + "{\"type\":\"reasoning\",\"id\":\"rs_1\",\"summary\":[],\"encrypted_content\":\"enc\","
                + "\"content\":[{\"type\":\"reasoning_text\",\"text\":\"思考中\"}]},"
                + "{\"type\":\"message\",\"role\":\"assistant\",\"content\":[{\"type\":\"output_text\",\"text\":\"答案\"}]}"
                + "]}";

        assertTrue(parser.parseResponse(resp, json));

        assertEquals(1, resp.getChoices().size(), "非流式应合并为单条消息");
        AssistantMessage msg = resp.getChoices().get(0).getMessage();
        assertEquals("答案", msg.getText());
        assertEquals("思考中", msg.getThinking());
        assertFalse(msg.isThinking());
        assertEquals("rs_1", msg.getMetadata().get("reasoning_item_id"));
        assertEquals("enc", msg.getMetadata().get("reasoning_encrypted_content"));
        assertEquals("stop", resp.getChoices().get(0).getFinishReason());
    }

    @Test
    public void nonStream_toolCallsFinishReason() {
        ChatResponseDefault resp = newResponse(false);
        String json = "{\"model\":\"gpt-5.4\",\"status\":\"completed\",\"output\":["
                + "{\"type\":\"function_call\",\"call_id\":\"call_1\",\"name\":\"getWeather\","
                + "\"arguments\":\"{\\\"city\\\":\\\"hz\\\"}\"}]}";

        assertTrue(parser.parseResponse(resp, json));

        assertEquals(1, resp.getChoices().size());
        assertEquals("tool_calls", resp.getChoices().get(0).getFinishReason());
        ToolCall call = resp.getChoices().get(0).getMessage().getToolCalls().get(0);
        assertEquals("call_1", call.getId());
        assertEquals("getWeather", call.getName());
    }

    @Test
    public void nonStream_incompleteStatusMappedToLength() {
        ChatResponseDefault resp = newResponse(false);
        String json = "{\"model\":\"gpt-5.4\",\"status\":\"incomplete\","
                + "\"incomplete_details\":{\"reason\":\"max_output_tokens\"},\"output\":["
                + "{\"type\":\"message\",\"role\":\"assistant\",\"content\":[{\"type\":\"output_text\",\"text\":\"半句\"}]}"
                + "]}";

        assertTrue(parser.parseResponse(resp, json));

        assertEquals("length", resp.getChoices().get(0).getFinishReason());
    }

    @Test
    public void nonStream_usageCacheWriteTokens() {
        ChatResponseDefault resp = newResponse(false);
        String json = "{\"model\":\"gpt-5.4\",\"status\":\"completed\",\"output\":[],"
                + "\"usage\":{\"input_tokens\":100,\"output_tokens\":20,\"total_tokens\":120,"
                + "\"input_tokens_details\":{\"cached_tokens\":30,\"cache_write_tokens\":40},"
                + "\"output_tokens_details\":{\"reasoning_tokens\":5}}}";

        assertTrue(parser.parseResponse(resp, json));

        assertNotNull(resp.getUsage());
        assertEquals(100, resp.getUsage().promptTokens());
        assertEquals(20, resp.getUsage().completionTokens());
        assertEquals(5, resp.getUsage().thinkTokens());
        assertEquals(30, resp.getUsage().cacheReadInputTokens());
        assertEquals(40, resp.getUsage().cacheCreationInputTokens());
    }

    @Test
    public void nonStream_errorObjectMessageExtracted() {
        ChatResponseDefault resp = newResponse(false);
        String json = "{\"error\":{\"message\":\"invalid model\",\"type\":\"invalid_request_error\"}}";

        assertTrue(parser.parseResponse(resp, json));

        assertNotNull(resp.getError());
        assertTrue(resp.getError().getMessage().contains("invalid model"), resp.getError().getMessage());
        assertTrue(resp.getError().getMessage().contains("invalid_request_error"), resp.getError().getMessage());
    }

    // ==================== 流式解析 ====================

    @Test
    public void stream_topLevelErrorObjectExtracted() {
        ChatResponseDefault resp = newResponse(true);
        String frame = "data: {\"error\":{\"message\":\"rate limited\",\"code\":\"rate_limit_exceeded\"}}";

        assertTrue(parser.parseResponse(resp, frame));

        assertNotNull(resp.getError());
        assertTrue(resp.getError().getMessage().contains("rate limited"), resp.getError().getMessage());
    }

    @Test
    public void stream_reasoningEncryptedContentCapturedOnItemDone() {
        ChatResponseDefault resp = newResponse(true);

        parser.parseResponse(resp, "data: {\"type\":\"response.output_item.added\","
                + "\"item\":{\"type\":\"reasoning\",\"id\":\"rs_1\"}}");
        parser.parseResponse(resp, "data: {\"type\":\"response.reasoning_text.delta\",\"delta\":\"思考\"}");
        parser.parseResponse(resp, "data: {\"type\":\"response.output_item.done\","
                + "\"item\":{\"type\":\"reasoning\",\"id\":\"rs_1\",\"encrypted_content\":\"enc_x\"}}");

        boolean found = false;
        for (int i = 0; i < resp.getChoices().size(); i++) {
            Object enc = resp.getChoices().get(i).getMessage().getMetadata().get("reasoning_encrypted_content");
            if ("enc_x".equals(enc)) {
                found = true;
            }
        }
        assertTrue(found, "output_item.done 的 encrypted_content 应被捕获用于多轮回放");
    }

    @Test
    public void reasoningSummary_asStringNotArray() {
        // 官方 Reasoning.summary 是字符串枚举（auto/concise/detailed）；数组形态仅属于输出侧 ReasoningItem.summary
        Map<String, Object> reasoning = new HashMap<>();
        reasoning.put("effort", "high");
        reasoning.put("summary", "detailed");
        reasoning.put("context", "all_turns");

        ONode root = build(ChatOptions.of().optionSet("reasoning", reasoning),
                Collections.singletonList(ChatMessage.ofUser("hi")));

        ONode node = root.get("reasoning");
        assertEquals("high", node.get("effort").getString());
        assertFalse(node.get("summary").isArray(), "summary 应为字符串: " + root.toJson());
        assertEquals("detailed", node.get("summary").getString());
        assertEquals("all_turns", node.get("context").getString(), "context 等官方字段应透传: " + root.toJson());
    }

    @Test
    public void reasoningSummary_legacyArrayNormalizedToString() {
        Map<String, Object> reasoning = new HashMap<>();
        reasoning.put("summary", Collections.singletonList("concise"));

        ONode root = build(ChatOptions.of().optionSet("reasoning", reasoning),
                Collections.singletonList(ChatMessage.ofUser("hi")));

        assertEquals("concise", root.get("reasoning").get("summary").getString(), root.toJson());
    }

    @Test
    public void include_addedOnlyWhenStoreFalse() {
        // encrypted_content 仅在 include 显式请求时返回；store=false 时又必须靠它回放 reasoning
        ONode statelessRoot = build(ChatOptions.of().optionSet("store", false),
                Collections.singletonList(ChatMessage.ofUser("hi")));
        assertTrue(statelessRoot.get("include").isArray(), statelessRoot.toJson());
        assertEquals("reasoning.encrypted_content",
                statelessRoot.get("include").get(0).getString(), statelessRoot.toJson());

        ONode statefulRoot = build(ChatOptions.of(), Collections.singletonList(ChatMessage.ofUser("hi")));
        assertFalse(statefulRoot.hasKey("include"), "默认 store=true 不应自动补 include: " + statefulRoot.toJson());
    }

    @Test
    public void maxCompletionTokens_mappedToMaxOutputTokens() {
        ONode root = build(ChatOptions.of().optionSet("max_completion_tokens", 128),
                Collections.singletonList(ChatMessage.ofUser("hi")));

        assertEquals(128, root.get("max_output_tokens").getInt(), root.toJson());
        assertFalse(root.hasKey("max_completion_tokens"), root.toJson());
    }

    @Test
    public void mergedAssistantMessage_replayReasoningAndContent() {
        // 4.1：非流式产出的是 text/thinking 合并的单条消息（isThinking=false），
        // 不能再以 isThinking() 作为是否回传 reasoning 项的分闸
        AssistantMessage msg = new AssistantMessage("结论", "思考过程", false);
        msg.getMetadata().put("reasoning_item_id", "rs_9");

        ONode root = build(ChatOptions.of(), Collections.singletonList(msg));

        ONode input = root.get("input");
        assertEquals(2, input.size(), "reasoning 与正文应并列输出: " + root.toJson());
        assertEquals("reasoning", input.get(0).get("type").getString(), root.toJson());
        assertEquals("rs_9", input.get(0).get("id").getString(), root.toJson());
        assertEquals("assistant", input.get(1).get("role").getString(), root.toJson());
        assertEquals("结论", input.get(1).get("content").getString(), root.toJson());
    }

    @Test
    public void mergedAssistantMessage_replayThinkingWithoutMetadata() {
        AssistantMessage msg = new AssistantMessage("结论", "思考过程", false);

        ONode root = build(ChatOptions.of(), Collections.singletonList(msg));

        ONode input = root.get("input");
        assertEquals("reasoning", input.get(0).get("type").getString(), root.toJson());
        assertEquals("思考过程", input.get(0).get("content").get(0).get("text").getString(), root.toJson());
        assertEquals("结论", input.get(1).get("content").getString(), root.toJson());
    }

    @Test
    public void thinkingOnlyMessage_noEmptyAssistantItem() {
        AssistantMessage thinking = new AssistantMessage("", "只有思考", true);

        ONode root = build(ChatOptions.of(), Collections.singletonList(thinking));

        assertEquals(1, root.get("input").size(), "纯思考分片不应补空 assistant 项: " + root.toJson());
        assertEquals("reasoning", root.get("input").get(0).get("type").getString());
    }

    @Test
    public void userText_notStrippedOfThinkTags() {
        // think 剔除只能用于 assistant 侧；用户正常文本包含该字样时不能被清空
        List<ContentBlock> blocks = new ArrayList<>();
        blocks.add(TextBlock.of("请解释 <think> 标签的作用"));
        blocks.add(ImageBlock.ofUrl("https://x.com/a.png"));

        ONode root = build(ChatOptions.of(),
                Collections.singletonList(ChatMessage.ofUser("请解释", blocks)));

        ONode contentArray = root.get("input").get(0).get("content");
        boolean keptThinkText = false;
        for (ONode item : contentArray.getArray()) {
            if ("input_text".equals(item.get("type").getString())
                    && item.get("text").getString().contains("<think>")) {
                keptThinkText = true;
            }
        }
        assertTrue(keptThinkText, "用户文本不应被 think 剔除逻辑清空: " + root.toJson());
    }

    @Test
    public void nonStream_unrecognizedOutputStillHasChoice() {
        // output 全是未识别项（web_search_call 等）时，不能让上层 getMessage() 拿到 null
        ChatResponseDefault resp = newResponse(false);
        String json = "{\"model\":\"gpt-5.4\",\"status\":\"completed\",\"output\":["
                + "{\"type\":\"web_search_call\",\"id\":\"ws_1\",\"status\":\"completed\"}]}";

        assertTrue(parser.parseResponse(resp, json));

        assertEquals(1, resp.getChoices().size(), "应补一条空消息 choice");
        assertNotNull(resp.getChoices().get(0).getMessage());
        assertEquals("stop", resp.getChoices().get(0).getFinishReason());
    }

    @Test
    public void stream_reasoningMetadataAggregatedForReplay() {
        // 流式：reasoning 元数据在思考分片上，不在最后一片；需经聚合交给会话，否则多轮回放断链
        ChatResponseDefault resp = newResponse(true);

        parser.parseResponse(resp, "data: {\"type\":\"response.output_item.added\","
                + "\"item\":{\"type\":\"reasoning\",\"id\":\"rs_1\"}}");
        parser.parseResponse(resp, "data: {\"type\":\"response.reasoning_text.delta\",\"delta\":\"思考\"}");
        parser.parseResponse(resp, "data: {\"type\":\"response.output_item.done\","
                + "\"item\":{\"type\":\"reasoning\",\"id\":\"rs_1\",\"encrypted_content\":\"enc_x\"}}");
        parser.parseResponse(resp, "data: {\"type\":\"response.output_text.delta\",\"delta\":\"答案\"}");

        AssistantMessage agg = resp.getAggregationMessage();
        assertNotNull(agg);
        assertEquals("rs_1", agg.getMetadata().get("reasoning_item_id"), agg.toString());
        assertEquals("enc_x", agg.getMetadata().get("reasoning_encrypted_content"), agg.toString());
    }
    @Test
    public void stream_reasoningIdDeliveredWithoutDeltas() {
        // store=true 且未请求 reasoning.summary 时，reasoning 项没有任何 delta 帧，
        // done 帧的 id 与 added 帧相同；若按「与 added 帧是否不同」判定就不会补元数据消息，
        // 导致 reasoning_item_id 拿不到、多轮回放断链
        ChatResponseDefault resp = newResponse(true);

        parser.parseResponse(resp, "data: {\"type\":\"response.output_item.added\","
                + "\"item\":{\"type\":\"reasoning\",\"id\":\"rs_only_id\",\"summary\":[]}}");
        assertTrue(parser.parseResponse(resp, "data: {\"type\":\"response.output_item.done\","
                + "\"item\":{\"type\":\"reasoning\",\"id\":\"rs_only_id\",\"summary\":[]}}"));
        parser.parseResponse(resp, "data: {\"type\":\"response.output_text.delta\",\"delta\":\"答案\"}");

        AssistantMessage agg = resp.getAggregationMessage();
        assertNotNull(agg);
        assertEquals("rs_only_id", agg.getMetadata().get("reasoning_item_id"), agg.toString());
    }

    @Test
    public void stream_reasoningMetadataNotDuplicatedAfterDeltas() {
        // 元数据已随思考分片交付时，done 帧不应再补一条重复的空 thinking 消息
        ChatResponseDefault resp = newResponse(true);

        parser.parseResponse(resp, "data: {\"type\":\"response.output_item.added\","
                + "\"item\":{\"type\":\"reasoning\",\"id\":\"rs_1\",\"encrypted_content\":\"enc_x\"}}");
        parser.parseResponse(resp, "data: {\"type\":\"response.reasoning_text.delta\",\"delta\":\"思考\"}");
        int beforeDone = resp.getChoices().size();
        parser.parseResponse(resp, "data: {\"type\":\"response.output_item.done\","
                + "\"item\":{\"type\":\"reasoning\",\"id\":\"rs_1\",\"encrypted_content\":\"enc_x\"}}");

        assertEquals(beforeDone, resp.getChoices().size(), "元数据已交付，不应重复补消息");
    }

    // ==================== 流式工具调用轮（聚合出口） ====================

    private ONode newToolCallNode(String text, String thinking) {
        ONode node = new ONode();
        node.set("role", "assistant");
        node.set("content", text);
        node.set("reasoning_content", thinking);
        node.getOrNew("tool_calls").asArray().addNew()
                .set("id", "call_1")
                .set("type", "function")
                .getOrNew("function")
                .set("name", "get_weather")
                .set("arguments", "{}");
        return node;
    }

    @Test
    public void streamToolCallRound_singleMessageCarriesToolCalls() {
        // 父类会把「正文 + reasoning_content」同帧双通道拆成多条思考信号消息，
        // 导致 get(0) 不带 tool_calls（工具不被执行）且 reasoning 元数据被重复挂载
        ChatResponseDefault resp = newResponse(true);
        OpenaiResponsesDialect dialect = OpenaiResponsesDialect.getInstance();

        // 思考分片交付 reasoning 元数据（供多轮回放）
        parser.parseResponse(resp, "data: {\"type\":\"response.output_item.added\","
                + "\"item\":{\"type\":\"reasoning\",\"id\":\"rs_1\"}}");
        parser.parseResponse(resp, "data: {\"type\":\"response.reasoning_text.delta\",\"delta\":\"想一下\"}");
        parser.parseResponse(resp, "data: {\"type\":\"response.output_item.done\","
                + "\"item\":{\"type\":\"reasoning\",\"id\":\"rs_1\",\"encrypted_content\":\"enc_x\"}}");

        List<AssistantMessage> messages = dialect.parseAssistantMessage(resp, newToolCallNode("我来查天气", "想一下"));

        assertEquals(1, messages.size(), "工具调用轮应只落一条会话消息: " + messages);
        AssistantMessage msg = messages.get(0);
        assertTrue(msg.isToolCalls(), "首条消息必须携带 tool_calls，否则工具不会被执行");
        assertEquals("我来查天气", msg.getText());
        assertEquals("想一下", msg.getThinking());
        assertEquals("rs_1", msg.getMetadata().get("reasoning_item_id"), msg.toString());
        assertEquals("enc_x", msg.getMetadata().get("reasoning_encrypted_content"), msg.toString());
    }

    @Test
    public void streamToolCallRound_replayHasSingleReasoningItem() {
        // 回放：一条消息 → 一个 reasoning 项（此前多条消息各带同 id 元数据，会重复输出）
        ChatResponseDefault resp = newResponse(true);
        OpenaiResponsesDialect dialect = OpenaiResponsesDialect.getInstance();

        parser.parseResponse(resp, "data: {\"type\":\"response.output_item.added\","
                + "\"item\":{\"type\":\"reasoning\",\"id\":\"rs_1\"}}");
        parser.parseResponse(resp, "data: {\"type\":\"response.reasoning_text.delta\",\"delta\":\"想一下\"}");

        List<ChatMessage> history = new ArrayList<>();
        history.addAll(dialect.parseAssistantMessage(resp, newToolCallNode("查询中", "想一下")));

        ONode root = build(ChatOptions.of(), history);

        int reasoningCount = 0;
        int functionCallCount = 0;
        for (ONode item : root.get("input").getArray()) {
            String type = item.get("type").getString();
            if ("reasoning".equals(type)) {
                reasoningCount++;
                assertEquals("rs_1", item.get("id").getString(), root.toJson());
            } else if ("function_call".equals(type)) {
                functionCallCount++;
            }
        }
        assertEquals(1, reasoningCount, "同一 reasoning id 只应回放一次: " + root.toJson());
        assertEquals(1, functionCallCount, root.toJson());
    }

    @Test
    public void assistantHistory_newModelTextBlockNotStripped() {
        // 4.1 起 text/thinking 已物理分离，TextBlock 不再内嵌 think 标签；
        // 正文恰以 <think> 开头的合法文本不能被当作思考剔除
        List<ContentBlock> blocks = new ArrayList<>();
        blocks.add(TextBlock.of("<think> 标签的用法说明"));
        blocks.add(ImageBlock.ofUrl("https://x/y.png"));
        AssistantMessage msg = new AssistantMessage("<think> 标签的用法说明", "", false,
                null, null, null, null, blocks);

        ONode root = build(ChatOptions.of(), Collections.singletonList((ChatMessage) msg));

        boolean kept = false;
        for (ONode item : root.get("input").getArray()) {
            if ("assistant".equals(item.get("role").getString()) == false) {
                continue;
            }
            for (ONode c : item.get("content").getArray()) {
                if ("input_text".equals(c.get("type").getString())
                        && c.get("text").getString().contains("<think>")) {
                    kept = true;
                }
            }
        }
        assertTrue(kept, "新模型数据的正文不应被 think 剔除: " + root.toJson());
    }

    @Test
    public void streamCumulativeOutputTextDelta_isNormalizedToSuffix() {
        ChatResponseDefault resp = newResponse(true);

        parser.parseStreamResponse(resp, "{\"type\":\"response.output_item.added\",\"item\":{\"id\":\"msg_1\",\"type\":\"message\"}}\n"
                + "{\"type\":\"response.output_text.delta\",\"delta\":\"所有代码修改完成。\"}\n"
                + "{\"type\":\"response.output_text.delta\",\"delta\":\"所有代码修改完成。更新任务进度并运行验证\"}");

        assertEquals("所有代码修改完成。更新任务进度并运行验证",
                resp.getChoices().get(0).getMessage().getTextRaw()
                        + resp.getChoices().get(1).getMessage().getTextRaw());
    }

    @Test
    public void streamCumulativeReasoningDelta_isNormalizedToSuffix() {
        ChatResponseDefault resp = newResponse(true);

        parser.parseStreamResponse(resp, "{\"type\":\"response.output_item.added\",\"item\":{\"id\":\"rs_1\",\"type\":\"reasoning\"}}\n"
                + "{\"type\":\"response.reasoning_text.delta\",\"delta\":\"补登README目录结构\"}\n"
                + "{\"type\":\"response.reasoning_text.delta\",\"delta\":\"补登README目录结构(新增composables/)\"}");

        assertEquals("补登README目录结构(新增composables/)",
                resp.getChoices().get(0).getMessage().getThinkingRaw()
                        + resp.getChoices().get(1).getMessage().getThinkingRaw());
    }

    @Test
    public void streamShortLegitDeltas_areNotTreatedAsSnapshot() {
        ChatResponseDefault resp = newResponse(true);

        // 合规增量在流首极易偶然构成前缀关系（"好" / "好的"），累计长度未达门槛时不得改写
        parser.parseStreamResponse(resp, "{\"type\":\"response.output_item.added\",\"item\":{\"id\":\"msg_1\",\"type\":\"message\"}}\n"
                + "{\"type\":\"response.output_text.delta\",\"delta\":\"好\"}\n"
                + "{\"type\":\"response.output_text.delta\",\"delta\":\"好的\"}");

        StringBuilder buf = new StringBuilder();
        for (ChatChoice choice : resp.getChoices()) {
            buf.append(choice.getMessage().getTextRaw());
        }
        assertEquals("好好的", buf.toString());
    }

    @Test
    public void streamDuplicatedSnapshotFrame_isDropped() {
        ChatResponseDefault resp = newResponse(true);

        parser.parseStreamResponse(resp, "{\"type\":\"response.output_item.added\",\"item\":{\"id\":\"msg_1\",\"type\":\"message\"}}\n"
                + "{\"type\":\"response.output_text.delta\",\"delta\":\"所有代码修改完成。\"}\n"
                + "{\"type\":\"response.output_text.delta\",\"delta\":\"所有代码修改完成。更新任务进度\"}\n"
                + "{\"type\":\"response.output_text.delta\",\"delta\":\"所有代码修改完成。更新任务进度\"}");

        assertEquals(2, resp.getChoices().size(), "完全重复的快照帧不应产生新的 choice");
        assertEquals("所有代码修改完成。更新任务进度",
                resp.getChoices().get(0).getMessage().getTextRaw()
                        + resp.getChoices().get(1).getMessage().getTextRaw());
    }
}
