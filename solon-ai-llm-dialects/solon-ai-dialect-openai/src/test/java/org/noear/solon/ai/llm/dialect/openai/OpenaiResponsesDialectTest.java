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
import org.noear.solon.ai.chat.ChatConfig;
import org.noear.solon.ai.chat.ChatOptions;
import org.noear.solon.ai.chat.ChatRequest;
import org.noear.solon.ai.chat.ChatAccumulator;
import org.noear.solon.ai.chat.content.AudioBlock;
import org.noear.solon.ai.chat.content.BlobBlock;
import org.noear.solon.ai.chat.content.ContentBlock;
import org.noear.solon.ai.chat.content.ImageBlock;
import org.noear.solon.ai.chat.content.TextBlock;
import org.noear.solon.ai.chat.event.ChatStreamContextDefault;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.message.ToolMessage;
import org.noear.solon.ai.chat.session.InMemoryChatSession;
import org.noear.solon.ai.chat.tool.ToolCall;
import org.noear.solon.ai.chat.tool.ToolResult;

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

    private ChatAccumulator newResponse(boolean stream) {
        ChatConfig config = new ChatConfig();
        ChatOptions options = ChatOptions.of();
        ChatRequest req = new ChatRequest(config, OpenaiResponsesDialect.getInstance(), options,
                InMemoryChatSession.builder().build(), ChatMessage.ofSystem("test"), null, stream);
        return new ChatAccumulator(req, stream);
    }

    /**
     * 走解析器的流上下文入口；本测试类只校验累积结果，故用「不发事件」的上下文
     */
    private boolean parse(ChatAccumulator resp, String json) {
        return parser.parseResponse(ChatStreamContextDefault.ofNoEmit(resp), json);
    }

    /**
     * 同上，但直接指定流式分支（不经 stream 标记路由）
     */
    private boolean parseStream(ChatAccumulator resp, String json) {
        return parser.parseStreamResponse(ChatStreamContextDefault.ofNoEmit(resp), json);
    }

    private ONode build(ChatOptions options, List<ChatMessage> messages) {
        return build("gpt-5.4", options, messages);
    }

    private ONode build(String model, ChatOptions options, List<ChatMessage> messages) {
        ChatConfig config = new ChatConfig();
        config.setModel(model);
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
        // prepareOutputFormatOptions 写入的是 ONode，不能再走 ofBean 二次序列化；
        // json_object 也是 Responses text.format 的官方合法类型。
        ChatOptions options = ChatOptions.of();
        ONode format = new ONode().set("type", "json_object");
        options.optionSet("text", new ONode().set("format", format));

        ONode root = build(options, Collections.singletonList(ChatMessage.ofUser("hi")));

        assertEquals("json_object", root.get("text").get("format").get("type").getString());
    }

    @Test
    public void outputFormat_invalidSchema_fallbackToJsonObject() {
        // schema 无法解析时不能继续使用 json_schema；降级到官方支持的旧式 JSON mode，
        // 保留输出必须为合法 JSON 的保证。
        ChatOptions options = ChatOptions.of().outputSchema("{not a valid json");
        OpenaiResponsesDialect.getInstance().prepareOutputFormatOptions(options);

        ONode root = build(options, Collections.singletonList(ChatMessage.ofUser("hi")));

        ONode format = root.get("text").get("format");
        assertEquals("json_object", format.get("type").getString(), root.toJson());
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
    public void toolMessage_textOutput_keepsStringShape() {
        ToolMessage tool = ChatMessage.ofTool("晴天", "getWeather", "call_1");

        ONode root = build(ChatOptions.of(), Collections.singletonList(tool));

        ONode output = root.get("input").get(0).get("output");
        assertFalse(output.isArray(), "纯文本工具结果应保持字符串形态: " + root.toJson());
        assertEquals("晴天", output.getString());
    }

    @Test
    public void toolMessage_multimodalOutput_usesContentArray() {
        ToolResult result = new ToolResult()
                .addText("结果如下")
                .addBlock(ImageBlock.ofUrl("https://x.com/result.png"));
        ToolMessage tool = ChatMessage.ofTool(result, "render", "call_2", false);

        ONode root = build(ChatOptions.of(), Collections.singletonList(tool));

        ONode output = root.get("input").get(0).get("output");
        assertTrue(output.isArray(), "多模态工具结果应使用官方内容数组: " + root.toJson());
        assertEquals("input_text", output.get(0).get("type").getString());
        assertEquals("结果如下", output.get(0).get("text").getString());
        assertEquals("input_image", output.get(1).get("type").getString());
        assertEquals("https://x.com/result.png", output.get(1).get("image_url").getString());
    }

    @Test
    public void toolMessage_blobOutput_usesInputFile() {
        ToolResult result = new ToolResult().addBlock(BlobBlock.of("QUJD", "application/pdf"));
        ToolMessage tool = ChatMessage.ofTool(result, "export", "call_3", false);

        ONode root = build(ChatOptions.of(), Collections.singletonList(tool));

        ONode output = root.get("input").get(0).get("output");
        assertTrue(output.isArray(), "文件工具结果应使用官方内容数组: " + root.toJson());
        assertEquals("input_file", output.get(0).get("type").getString());
        assertEquals("QUJD", output.get(0).get("file_data").getString());
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
        ChatAccumulator resp = newResponse(false);
        String json = "{\"id\":\"resp_1\",\"model\":\"gpt-5.4\",\"status\":\"completed\",\"output\":["
                + "{\"type\":\"reasoning\",\"id\":\"rs_1\",\"summary\":[],\"encrypted_content\":\"enc\","
                + "\"content\":[{\"type\":\"reasoning_text\",\"text\":\"思考中\"}]},"
                + "{\"type\":\"message\",\"role\":\"assistant\",\"content\":[{\"type\":\"output_text\",\"text\":\"答案\"}]}"
                + "]}";

        assertTrue(parse(resp, json));

        assertEquals(1, resp.getContentItems().size(), "非流式应合并为单条消息");
        AssistantMessage msg = resp.getContentItems().get(0);
        assertEquals("答案", msg.getText());
        assertEquals("思考中", msg.getThinking());
        assertFalse(msg.isThinking());
        assertEquals("rs_1", msg.getMetadata().get("reasoning_item_id"));
        assertEquals("enc", msg.getMetadata().get("reasoning_encrypted_content"));
        assertEquals("stop", resp.getLastFinishReasonNormalized());
    }

    @Test
    public void nonStream_toolCallsFinishReason() {
        ChatAccumulator resp = newResponse(false);
        String json = "{\"model\":\"gpt-5.4\",\"status\":\"completed\",\"output\":["
                + "{\"type\":\"function_call\",\"call_id\":\"call_1\",\"name\":\"getWeather\","
                + "\"arguments\":\"{\\\"city\\\":\\\"hz\\\"}\"}]}";

        assertTrue(parse(resp, json));

        assertEquals(1, resp.getContentItems().size());
        // 完成原因已是响应级属性：断原始值（框架归一化后为 "tool"）
        assertEquals("tool_calls", resp.lastFinishReason);
        ToolCall call = resp.getContentItems().get(0).getToolCalls().get(0);
        assertEquals("call_1", call.getId());
        assertEquals("getWeather", call.getName());
    }

    @Test
    public void nonStream_incompleteStatusMappedToLength() {
        ChatAccumulator resp = newResponse(false);
        String json = "{\"model\":\"gpt-5.4\",\"status\":\"incomplete\","
                + "\"incomplete_details\":{\"reason\":\"max_output_tokens\"},\"output\":["
                + "{\"type\":\"message\",\"role\":\"assistant\",\"content\":[{\"type\":\"output_text\",\"text\":\"半句\"}]}"
                + "]}";

        assertTrue(parse(resp, json));

        assertEquals("length", resp.lastFinishReason);
    }

    @Test
    public void nonStream_usageCacheWriteTokens() {
        ChatAccumulator resp = newResponse(false);
        String json = "{\"model\":\"gpt-5.4\",\"status\":\"completed\",\"output\":[],"
                + "\"usage\":{\"input_tokens\":100,\"output_tokens\":20,\"total_tokens\":120,"
                + "\"input_tokens_details\":{\"cached_tokens\":30,\"cache_write_tokens\":40},"
                + "\"output_tokens_details\":{\"reasoning_tokens\":5}}}";

        assertTrue(parse(resp, json));

        assertNotNull(resp.getUsage());
        assertEquals(100, resp.getUsage().promptTokens());
        assertEquals(20, resp.getUsage().completionTokens());
        assertEquals(5, resp.getUsage().thinkTokens());
        assertEquals(30, resp.getUsage().cacheReadInputTokens());
        assertEquals(40, resp.getUsage().cacheCreationInputTokens());
    }

    @Test
    public void nonStream_errorObjectMessageExtracted() {
        ChatAccumulator resp = newResponse(false);
        String json = "{\"error\":{\"message\":\"invalid model\",\"type\":\"invalid_request_error\"}}";

        assertTrue(parse(resp, json));

        assertNotNull(resp.getError());
        assertTrue(resp.getError().getMessage().contains("invalid model"), resp.getError().getMessage());
        assertTrue(resp.getError().getMessage().contains("invalid_request_error"), resp.getError().getMessage());
    }

    // ==================== 流式解析 ====================

    @Test
    public void stream_topLevelErrorObjectExtracted() {
        ChatAccumulator resp = newResponse(true);
        String frame = "data: {\"error\":{\"message\":\"rate limited\",\"code\":\"rate_limit_exceeded\"}}";

        assertTrue(parse(resp, frame));

        assertNotNull(resp.getError());
        assertTrue(resp.getError().getMessage().contains("rate limited"), resp.getError().getMessage());
    }

    @Test
    public void stream_reasoningEncryptedContentCapturedOnItemDone() {
        ChatAccumulator resp = newResponse(true);

        parse(resp, "data: {\"type\":\"response.output_item.added\","
                + "\"item\":{\"type\":\"reasoning\",\"id\":\"rs_1\"}}");
        parse(resp, "data: {\"type\":\"response.reasoning_text.delta\",\"delta\":\"思考\"}");
        parse(resp, "data: {\"type\":\"response.output_item.done\","
                + "\"item\":{\"type\":\"reasoning\",\"id\":\"rs_1\",\"encrypted_content\":\"enc_x\"}}");

        boolean found = false;
        for (int i = 0; i < resp.getContentItems().size(); i++) {
            Object enc = resp.getContentItems().get(i).getMetadata().get("reasoning_encrypted_content");
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
    public void thinkingOn_requestsReasoningSummary() {
        // thinking(true) 是统一 API 中“希望可观察推理”的明确意图；Responses 需要 summary=auto 才会返回摘要事件。
        ONode root = build(ChatOptions.of().thinking(true),
                Collections.singletonList(ChatMessage.ofUser("hi")));

        ONode reasoning = root.get("reasoning");
        assertEquals("auto", reasoning.get("summary").getString(), root.toJson());
        assertFalse(reasoning.hasKey("effort"), "thinking(true) 不应臆造固定 effort: " + root.toJson());
    }

    @Test
    public void thinkingOnWithEffort_requestsSummaryAndKeepsEffort() {
        ONode root = build(ChatOptions.of().thinking(true).reasoning_effort("high"),
                Collections.singletonList(ChatMessage.ofUser("hi")));

        ONode reasoning = root.get("reasoning");
        assertEquals("high", reasoning.get("effort").getString(), root.toJson());
        assertEquals("auto", reasoning.get("summary").getString(), root.toJson());
    }

    @Test
    public void thinkingOff_doesNotRequestReasoningSummary() {
        ONode root = build(ChatOptions.of().thinking(false).reasoning_effort("high"),
                Collections.singletonList(ChatMessage.ofUser("hi")));

        ONode reasoning = root.get("reasoning");
        assertEquals("none", reasoning.get("effort").getString(), root.toJson());
        assertFalse(reasoning.hasKey("summary"), "关闭推理时不应请求 summary: " + root.toJson());
    }

    @Test
    public void explicitReasoning_canControlSummaryCompatibility() {
        // 兼容端点可用显式 reasoning 完全接管，避免自动添加不支持的 summary 字段。
        Map<String, Object> reasoning = new HashMap<>();
        reasoning.put("effort", "high");
        ONode root = build("gpt-4o", ChatOptions.of().thinking(true).optionSet("reasoning", reasoning),
                Collections.singletonList(ChatMessage.ofUser("hi")));

        ONode node = root.get("reasoning");
        assertEquals("high", node.get("effort").getString(), root.toJson());
        assertFalse(node.hasKey("summary"), root.toJson());
    }

    @Test
    public void gpt5ModelAliases_receiveAutomaticReasoning() {
        ONode canonical = build("gpt-5.6", ChatOptions.of().thinking(true),
                Collections.singletonList(ChatMessage.ofUser("hi")));
        assertEquals("auto", canonical.get("reasoning").get("summary").getString(), canonical.toJson());

        // 兼容网关常用无连字符别名；只放宽能力判断，出站 model 保持调用方原值。
        ONode compact = build("gpt5.6", ChatOptions.of().thinking(true),
                Collections.singletonList(ChatMessage.ofUser("hi")));
        assertEquals("auto", compact.get("reasoning").get("summary").getString(), compact.toJson());

        // 带供应商前缀的模型 ID（如 Bedrock）也应识别 GPT-5 家族。
        ONode prefixed = build("us.openai.gpt-5.6-sol", ChatOptions.of().thinking(true),
                Collections.singletonList(ChatMessage.ofUser("hi")));
        assertEquals("auto", prefixed.get("reasoning").get("summary").getString(), prefixed.toJson());
    }

    @Test
    public void reasoningEffortAlone_doesNotRequestVisibleSummary() {
        // effort 控制推理投入；Responses 的可展示摘要需要单独请求 summary。
        ONode root = build("gpt-5.6", ChatOptions.of().reasoning_effort("high"),
                Collections.singletonList(ChatMessage.ofUser("hi")));

        assertEquals("high", root.get("reasoning").get("effort").getString(), root.toJson());
        assertFalse(root.get("reasoning").hasKey("summary"), root.toJson());
    }

    @Test
    public void nonReasoningModels_doNotReceiveAutomaticReasoning() {
        ONode gpt4o = build("gpt-4o", ChatOptions.of().thinking(true).reasoning_effort("high"),
                Collections.singletonList(ChatMessage.ofUser("hi")));
        assertFalse(gpt4o.hasKey("reasoning"), "非推理模型不应自动发送 reasoning: " + gpt4o.toJson());

        ONode unknown = build("vendor-model", ChatOptions.of().thinking(false),
                Collections.singletonList(ChatMessage.ofUser("hi")));
        assertFalse(unknown.hasKey("reasoning"), "未知模型应保守跳过自动 reasoning: " + unknown.toJson());
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
        ChatAccumulator resp = newResponse(false);
        String json = "{\"model\":\"gpt-5.4\",\"status\":\"completed\",\"output\":["
                + "{\"type\":\"web_search_call\",\"id\":\"ws_1\",\"status\":\"completed\"}]}";

        assertTrue(parse(resp, json));

        assertEquals(1, resp.getContentItems().size(), "应补一条空消息内容项");
        assertNotNull(resp.getContentItems().get(0));
        assertEquals("stop", resp.getLastFinishReasonNormalized());
    }

    @Test
    public void stream_reasoningMetadataAggregatedForReplay() {
        // 流式：reasoning 元数据在思考分片上，不在最后一片；需经聚合交给会话，否则多轮回放断链
        ChatAccumulator resp = newResponse(true);

        parse(resp, "data: {\"type\":\"response.output_item.added\","
                + "\"item\":{\"type\":\"reasoning\",\"id\":\"rs_1\"}}");
        parse(resp, "data: {\"type\":\"response.reasoning_text.delta\",\"delta\":\"思考\"}");
        parse(resp, "data: {\"type\":\"response.output_item.done\","
                + "\"item\":{\"type\":\"reasoning\",\"id\":\"rs_1\",\"encrypted_content\":\"enc_x\"}}");
        parse(resp, "data: {\"type\":\"response.output_text.delta\",\"delta\":\"答案\"}");

        AssistantMessage agg = resp.snapshotTerminal().getMessage();
        assertNotNull(agg);
        assertEquals("rs_1", agg.getMetadata().get("reasoning_item_id"), agg.toString());
        assertEquals("enc_x", agg.getMetadata().get("reasoning_encrypted_content"), agg.toString());
    }
    @Test
    public void stream_reasoningIdDeliveredWithoutDeltas() {
        // store=true 且未请求 reasoning.summary 时，reasoning 项没有任何 delta 帧，
        // done 帧的 id 与 added 帧相同；若按「与 added 帧是否不同」判定就不会补元数据消息，
        // 导致 reasoning_item_id 拿不到、多轮回放断链
        ChatAccumulator resp = newResponse(true);

        parse(resp, "data: {\"type\":\"response.output_item.added\","
                + "\"item\":{\"type\":\"reasoning\",\"id\":\"rs_only_id\",\"summary\":[]}}");
        assertTrue(parse(resp, "data: {\"type\":\"response.output_item.done\","
                + "\"item\":{\"type\":\"reasoning\",\"id\":\"rs_only_id\",\"summary\":[]}}"));
        parse(resp, "data: {\"type\":\"response.output_text.delta\",\"delta\":\"答案\"}");

        AssistantMessage agg = resp.snapshotTerminal().getMessage();
        assertNotNull(agg);
        assertEquals("rs_only_id", agg.getMetadata().get("reasoning_item_id"), agg.toString());
    }

    @Test
    public void stream_reasoningMetadataNotDuplicatedAfterDeltas() {
        // 元数据已随思考分片交付时，done 帧不应再补一条重复的空 thinking 消息
        ChatAccumulator resp = newResponse(true);

        parse(resp, "data: {\"type\":\"response.output_item.added\","
                + "\"item\":{\"type\":\"reasoning\",\"id\":\"rs_1\",\"encrypted_content\":\"enc_x\"}}");
        parse(resp, "data: {\"type\":\"response.reasoning_text.delta\",\"delta\":\"思考\"}");
        int beforeDone = resp.getContentItems().size();
        parse(resp, "data: {\"type\":\"response.output_item.done\","
                + "\"item\":{\"type\":\"reasoning\",\"id\":\"rs_1\",\"encrypted_content\":\"enc_x\"}}");

        assertEquals(beforeDone, resp.getContentItems().size(), "元数据已交付，不应重复补消息");
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
        ChatAccumulator resp = newResponse(true);
        OpenaiResponsesDialect dialect = OpenaiResponsesDialect.getInstance();

        // 思考分片交付 reasoning 元数据（供多轮回放）
        parse(resp, "data: {\"type\":\"response.output_item.added\","
                + "\"item\":{\"type\":\"reasoning\",\"id\":\"rs_1\"}}");
        parse(resp, "data: {\"type\":\"response.reasoning_text.delta\",\"delta\":\"想一下\"}");
        parse(resp, "data: {\"type\":\"response.output_item.done\","
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
        ChatAccumulator resp = newResponse(true);
        OpenaiResponsesDialect dialect = OpenaiResponsesDialect.getInstance();

        parse(resp, "data: {\"type\":\"response.output_item.added\","
                + "\"item\":{\"type\":\"reasoning\",\"id\":\"rs_1\"}}");
        parse(resp, "data: {\"type\":\"response.reasoning_text.delta\",\"delta\":\"想一下\"}");

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
        ChatAccumulator resp = newResponse(true);

        parseStream(resp, "{\"type\":\"response.output_item.added\",\"item\":{\"id\":\"msg_1\",\"type\":\"message\"}}\n"
                + "{\"type\":\"response.output_text.delta\",\"delta\":\"所有代码修改完成。\"}\n"
                + "{\"type\":\"response.output_text.delta\",\"delta\":\"所有代码修改完成。更新任务进度并运行验证\"}");

        assertEquals("所有代码修改完成。更新任务进度并运行验证",
                resp.getContentItems().get(0).getTextRaw()
                        + resp.getContentItems().get(1).getTextRaw());
    }

    @Test
    public void streamReasoningSummaryDelta_isPublishedAsThinking() {
        ChatAccumulator resp = newResponse(true);

        parseStream(resp,
                "{\"type\":\"response.output_item.added\",\"item\":{\"id\":\"rs_1\",\"type\":\"reasoning\"}}\n"
                        + "{\"type\":\"response.reasoning_summary_part.added\",\"item_id\":\"rs_1\"}\n"
                        + "{\"type\":\"response.reasoning_summary_text.delta\",\"item_id\":\"rs_1\",\"delta\":\"正在分析\"}\n"
                        + "{\"type\":\"response.reasoning_summary_text.delta\",\"item_id\":\"rs_1\",\"delta\":\"请求参数\"}");

        assertEquals(2, resp.getContentItems().size(), resp.toString());
        assertTrue(resp.getContentItems().get(0).isThinking(), resp.toString());
        assertTrue(resp.getContentItems().get(1).isThinking(), resp.toString());
        assertEquals("正在分析", resp.getContentItems().get(0).getThinkingRaw());
        assertEquals("请求参数", resp.getContentItems().get(1).getThinkingRaw());

        // parser 只负责产出分片；核心 ChatRequestDesc 发布分片时才写入 aggregationThinking。
        StringBuilder thinking = new StringBuilder();
        for (AssistantMessage choice : resp.getContentItems()) {
            thinking.append(choice.getThinkingRaw());
        }
        assertEquals("正在分析请求参数", thinking.toString());
    }

    @Test
    public void streamCumulativeReasoningDelta_isNormalizedToSuffix() {
        ChatAccumulator resp = newResponse(true);

        parseStream(resp, "{\"type\":\"response.output_item.added\",\"item\":{\"id\":\"rs_1\",\"type\":\"reasoning\"}}\n"
                + "{\"type\":\"response.reasoning_text.delta\",\"delta\":\"补登README目录结构\"}\n"
                + "{\"type\":\"response.reasoning_text.delta\",\"delta\":\"补登README目录结构(新增composables/)\"}");

        assertEquals("补登README目录结构(新增composables/)",
                resp.getContentItems().get(0).getThinkingRaw()
                        + resp.getContentItems().get(1).getThinkingRaw());
    }

    @Test
    public void streamShortLegitDeltas_areNotTreatedAsSnapshot() {
        ChatAccumulator resp = newResponse(true);

        // 合规增量在流首极易偶然构成前缀关系（"好" / "好的"），累计长度未达门槛时不得改写
        parseStream(resp, "{\"type\":\"response.output_item.added\",\"item\":{\"id\":\"msg_1\",\"type\":\"message\"}}\n"
                + "{\"type\":\"response.output_text.delta\",\"delta\":\"好\"}\n"
                + "{\"type\":\"response.output_text.delta\",\"delta\":\"好的\"}");

        StringBuilder buf = new StringBuilder();
        for (AssistantMessage choice : resp.getContentItems()) {
            buf.append(choice.getTextRaw());
        }
        assertEquals("好好的", buf.toString());
    }

    @Test
    public void streamDuplicatedSnapshotFrame_isDropped() {
        ChatAccumulator resp = newResponse(true);

        parseStream(resp, "{\"type\":\"response.output_item.added\",\"item\":{\"id\":\"msg_1\",\"type\":\"message\"}}\n"
                + "{\"type\":\"response.output_text.delta\",\"delta\":\"所有代码修改完成。\"}\n"
                + "{\"type\":\"response.output_text.delta\",\"delta\":\"所有代码修改完成。更新任务进度\"}\n"
                + "{\"type\":\"response.output_text.delta\",\"delta\":\"所有代码修改完成。更新任务进度\"}");

        assertEquals(2, resp.getContentItems().size(), "完全重复的快照帧不应产生新的 choice");
        assertEquals("所有代码修改完成。更新任务进度",
                resp.getContentItems().get(0).getTextRaw()
                        + resp.getContentItems().get(1).getTextRaw());
    }
    @Test
    public void explicitPromptCacheBreakpoint_isAttachedToLastInputContent() {
        ChatOptions options = ChatOptions.of().optionSet("prompt_cache_breakpoint", "after_tools");
        ONode root = build(options, Collections.singletonList(ChatMessage.ofUser("hi")));
        ONode content = root.get("input").get(0).get("content");
        assertTrue(content.isArray(), root.toJson());
        assertEquals("after_tools", content.get(content.size() - 1)
                .get("prompt_cache_breakpoint").get("mode").getString(), root.toJson());
    }

    @Test
    public void zeroResponsesUsage_isPreserved() {
        ChatAccumulator resp = newResponse(false);
        assertTrue(parse(resp, "{\"model\":\"gpt-5.4\",\"status\":\"completed\",\"output\":[],"
                + "\"usage\":{\"input_tokens\":0,\"output_tokens\":0,\"total_tokens\":0}}"));
        assertNotNull(resp.getUsage());
        assertEquals(0, resp.getUsage().totalTokens());
    }

}