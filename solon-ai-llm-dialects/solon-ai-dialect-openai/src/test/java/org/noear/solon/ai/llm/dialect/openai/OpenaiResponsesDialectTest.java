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
import org.noear.solon.ai.chat.*;
import org.noear.solon.ai.chat.content.AudioBlock;
import org.noear.solon.ai.chat.content.BlobBlock;
import org.noear.solon.ai.chat.content.ContentBlock;
import org.noear.solon.ai.chat.content.ImageBlock;
import org.noear.solon.ai.chat.content.TextBlock;
import org.noear.solon.ai.chat.event.ChatStreamContextDefault;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.message.MessageProtocolState;
import org.noear.solon.ai.chat.message.ToolMessage;
import org.noear.solon.ai.chat.session.InMemoryChatSession;
import org.noear.solon.ai.chat.tool.ToolCall;
import org.noear.solon.ai.chat.tool.ToolResult;
import org.noear.solon.ai.chat.tool.FunctionToolDesc;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * OpenAI Responses 方言适配单元测试
 * <p>
 * 对齐 OpenAI 官方 Responses API 规范（openapi.transformed.yml / openai-java SDK 模型类）：
 * <ul>
 *   <li>EasyInputMessage 的 content 仅接受 input_text / input_image / input_file</li>
 *   <li>input_audio 仅作为显式开启的兼容网关扩展，官方 Responses 默认拒绝</li>
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
        return newResponse(stream, ChatOptions.of());
    }

    private ChatAccumulator newResponse(boolean stream, ChatOptions options) {
        ChatConfig config = new ChatConfig();
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

    private ONode firstContentItem(ONode root, String type) {
        for (ONode input : root.get("input").getArray()) {
            ONode content = input.getOrNull("content");
            if (content == null || !content.isArray()) {
                continue;
            }
            for (ONode item : content.getArray()) {
                if (type.equals(item.get("type").getString())) {
                    return item;
                }
            }
        }
        return null;
    }

    private boolean hasContentItem(ONode root, String type) {
        return firstContentItem(root, type) != null;
    }

    // ==================== tools 形态 ====================

    @Test
    public void functionTool_strictAndOutputSchema_useApiSpecificShape() {
        FunctionToolDesc tool = new FunctionToolDesc("lookup")
                .description("lookup data")
                .inputSchema("{\"type\":\"object\",\"properties\":{\"id\":{\"type\":\"string\"}},\"required\":[\"id\"],\"additionalProperties\":false}")
                .outputSchema("{\"type\":\"object\",\"properties\":{\"value\":{\"type\":\"string\"}},\"required\":[\"value\"],\"additionalProperties\":false}")
                .strict(true);
        ChatOptions options = ChatOptions.of().toolAdd(tool);

        ONode responses = build(options, Collections.singletonList(ChatMessage.ofUser("go")));
        ONode responsesTool = responses.get("tools").get(0);
        assertTrue(responsesTool.get("strict").getBoolean());
        assertTrue(responsesTool.get("output_schema").isObject());
        assertFalse(responsesTool.hasKey("function"));

        ChatConfig config = new ChatConfig();
        config.setModel("gpt-4.1");
        ONode chat = OpenaiChatDialect.getInstance().buildRequestJson(config, options,
                Collections.singletonList(ChatMessage.ofUser("go")), false);
        ONode chatTool = chat.get("tools").get(0);
        assertTrue(chatTool.get("function").get("strict").getBoolean());
        assertFalse(chatTool.get("function").hasKey("output_schema"),
                "Chat Completions 官方 FunctionDefinition 没有 output_schema");
    }

    @Test
    public void functionTool_invalidSchemas_fallbackWithoutPollutingRequest() {
        FunctionToolDesc tool = new FunctionToolDesc("lookup")
                .inputSchema("not-json")
                .outputSchema("not-json");
        ChatOptions options = ChatOptions.of().toolAdd(tool);

        ONode responsesTool = build(options, Collections.singletonList(ChatMessage.ofUser("go")))
                .get("tools").get(0);
        assertEquals("object", responsesTool.get("parameters").get("type").getString());
        assertFalse(responsesTool.get("strict").getBoolean(), "Responses FunctionTool 必须显式输出默认 strict=false");
        assertFalse(responsesTool.hasKey("output_schema"));

        ChatConfig config = new ChatConfig();
        ONode chatTool = OpenaiChatDialect.getInstance().buildRequestJson(config, options,
                Collections.singletonList(ChatMessage.ofUser("go")), false)
                .get("tools").get(0).get("function");
        assertEquals("object", chatTool.get("parameters").get("type").getString());
    }

    @Test
    public void functionTool_jsonScalarSchemas_areRejectedOrIgnored() {
        FunctionToolDesc tool = new FunctionToolDesc("lookup")
                .inputSchema("[]")
                .outputSchema("\"not-an-object\"");
        ONode responsesTool = build(ChatOptions.of().toolAdd(tool),
                Collections.singletonList(ChatMessage.ofUser("go"))).get("tools").get(0);
        assertEquals("object", responsesTool.get("parameters").get("type").getString());
        assertFalse(responsesTool.hasKey("output_schema"));
    }

    @Test
    public void functionTool_strictRejectsNonStrictSchema() {
        FunctionToolDesc tool = new FunctionToolDesc("lookup")
                .inputSchema("{\"type\":\"object\",\"properties\":{\"id\":{\"type\":\"string\"}}}")
                .strict(true);
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> build(ChatOptions.of().toolAdd(tool), Collections.singletonList(ChatMessage.ofUser("go"))));
        assertTrue(error.getMessage().contains("additionalProperties=false"), error.getMessage());
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
    public void compatibleGatewayAudioBlock_useNestedInputAudioOnlyWhenEnabled() {
        // input_audio 不是官方 ResponseInputContent，只有明确声明兼容网关时才发送。
        List<ContentBlock> blocks = new ArrayList<>();
        blocks.add(AudioBlock.ofBase64("AAAA", "audio/wav"));

        assertThrows(IllegalArgumentException.class, () -> build(ChatOptions.of(),
                Collections.singletonList(ChatMessage.ofUser("听一下", blocks))));
        ONode root = build(ChatOptions.of().optionSet("responses_input_audio_enabled", true),
                Collections.singletonList(ChatMessage.ofUser("听一下", blocks)));
        ONode audioItem = firstContentItem(root, "input_audio");

        assertNotNull(audioItem, "应写出 input_audio 项: " + root.toJson());
        assertTrue(audioItem.get("input_audio").isObject(), "input_audio 应为嵌套对象: " + root.toJson());
        assertEquals("AAAA", audioItem.get("input_audio").get("data").getString());
        assertEquals("wav", audioItem.get("input_audio").get("format").getString());
        assertFalse(root.hasKey("responses_input_audio_enabled"), "本地兼容选项不得透传: " + root.toJson());
    }

    @Test
    public void inputAudioOptionRequiresBooleanTrueAndNeverPassesThrough() {
        List<ContentBlock> audio = Collections.<ContentBlock>singletonList(
                AudioBlock.ofBase64("AAAA", "audio/wav"));

        assertThrows(IllegalArgumentException.class, () -> build(
                ChatOptions.of().optionSet("responses_input_audio_enabled", false),
                Collections.singletonList(ChatMessage.ofUser("听一下", audio))));
        assertThrows(IllegalArgumentException.class, () -> build(
                ChatOptions.of().optionSet("responses_input_audio_enabled", "true"),
                Collections.singletonList(ChatMessage.ofUser("听一下", audio))));

        for (Object value : Arrays.<Object>asList(false, "true", true)) {
            ONode root = build(ChatOptions.of().optionSet("responses_input_audio_enabled", value),
                    Collections.singletonList(ChatMessage.ofUser("纯文本")));
            assertFalse(root.hasKey("responses_input_audio_enabled"),
                    "本地选项不得透传，value=" + value + ": " + root.toJson());
        }
    }

    @Test
    public void urlOnlyAudioFallsBackToInputTextForUserAndAssistant() {
        ChatOptions enabled = ChatOptions.of().optionSet("responses_input_audio_enabled", true);
        List<ContentBlock> blocks = Collections.<ContentBlock>singletonList(
                AudioBlock.ofUrl("https://cdn.example/audio.wav", "audio/wav"));

        ONode user = build(enabled, Collections.singletonList(ChatMessage.ofUser("听一下", blocks)));
        ONode assistant = build(enabled, Collections.singletonList(ChatMessage.ofAssistant("听一下", blocks)));

        assertFalse(hasContentItem(user, "input_audio"), user.toJson());
        assertFalse(hasContentItem(assistant, "input_audio"), assistant.toJson());
        assertEquals("[audio]https://cdn.example/audio.wav",
                findContentText(user, "[audio]"));
        assertEquals("[audio]https://cdn.example/audio.wav",
                findContentText(assistant, "[audio]"));
    }

    @Test
    public void base64AudioMappingIsConsistentForUserAndAssistant() {
        ChatOptions enabled = ChatOptions.of().optionSet("responses_input_audio_enabled", true);
        List<ContentBlock> blocks = Collections.<ContentBlock>singletonList(
                AudioBlock.ofBase64("QUJD", "audio/mpeg"));

        ONode userAudio = firstContentItem(build(enabled,
                Collections.singletonList(ChatMessage.ofUser("听一下", blocks))), "input_audio");
        ONode assistantAudio = firstContentItem(build(enabled,
                Collections.singletonList(ChatMessage.ofAssistant("听一下", blocks))), "input_audio");

        assertNotNull(userAudio);
        assertNotNull(assistantAudio);
        assertEquals(userAudio.toJson(), assistantAudio.toJson());
        assertEquals("mpeg", userAudio.get("input_audio").get("format").getString());
    }

    @Test
    public void emptyAudioNeverEmitsMalformedInputAudio() {
        ChatOptions enabled = ChatOptions.of().optionSet("responses_input_audio_enabled", true);
        List<ContentBlock> emptyAudio = Collections.<ContentBlock>singletonList(
                AudioBlock.ofBase64("", "audio/wav"));

        ONode user = build(enabled, Collections.singletonList(ChatMessage.ofUser("fallback", emptyAudio)));
        ONode assistant = build(enabled,
                Collections.singletonList(ChatMessage.ofAssistant("fallback", emptyAudio)));
        ONode emptyUser = build(enabled, Collections.singletonList(
                ChatMessage.ofUser("", Collections.<ContentBlock>singletonList(AudioBlock.ofUrl("audio://empty")))));
        ONode emptyAssistant = build(enabled, Collections.singletonList(
                ChatMessage.ofAssistant("", Collections.<ContentBlock>singletonList(AudioBlock.ofUrl("audio://empty")))));

        assertFalse(hasContentItem(user, "input_audio"), user.toJson());
        assertFalse(hasContentItem(assistant, "input_audio"), assistant.toJson());
        assertTrue(user.toJson().contains("fallback"), user.toJson());
        assertTrue(assistant.toJson().contains("fallback"), assistant.toJson());
        assertFalse(emptyUser.toJson().contains("input_audio"), emptyUser.toJson());
        assertFalse(emptyAssistant.toJson().contains("input_audio"), emptyAssistant.toJson());
        assertFalse(emptyUser.toJson().contains("audio://empty"), emptyUser.toJson());
        assertFalse(emptyAssistant.toJson().contains("audio://empty"), emptyAssistant.toJson());
        assertTrue(emptyUser.get("input").get(0).hasKey("content"), emptyUser.toJson());
        assertTrue(emptyAssistant.get("input").get(0).hasKey("content"), emptyAssistant.toJson());
    }

    private String findContentText(ONode root, String prefix) {
        for (ONode input : root.get("input").getArray()) {
            ONode content = input.getOrNull("content");
            if (content == null || !content.isArray()) {
                continue;
            }
            for (ONode item : content.getArray()) {
                String text = item.get("text").getString();
                if (text != null && text.startsWith(prefix)) {
                    return text;
                }
            }
        }
        return null;
    }

    @Test
    public void reasoningItem_alwaysCarrySummary() {
        // 官方 ResponseReasoningItem.summary 为必填（可为空数组），缺失会 400
        AssistantMessage thinking = new AssistantMessage("", "先分析一下");
        ONode root = build(ChatOptions.of(), Collections.singletonList(thinking));

        ONode item = root.get("input").get(0);
        assertEquals("reasoning", item.get("type").getString());
        assertTrue(item.get("summary").isArray(), "reasoning 项必须含 summary 数组: " + root.toJson());
        assertEquals("reasoning_text", item.get("content").get(0).get("type").getString());
        assertEquals("先分析一下", item.get("content").get(0).get("text").getString());
    }

    @Test
    public void streamDone_promotesResponsesReplayDataToProtocolState() {
        ChatAccumulator resp = newResponse(true);
        parseStream(resp, "data: {\"type\":\"response.output_item.added\","
                + "\"item\":{\"type\":\"reasoning\",\"id\":\"rs_done\"}}");
        parseStream(resp, "data: {\"type\":\"response.output_item.done\","
                + "\"output_index\":0,\"item\":{\"type\":\"reasoning\",\"id\":\"rs_done\","
                + "\"encrypted_content\":\"enc_done\",\"summary\":[]}}");
        parseStream(resp, "data: {\"type\":\"response.output_text.delta\",\"item_id\":\"msg_1\",\"delta\":\"答案\"}");
        parseStream(resp, "data: [DONE]");

        AssistantMessage message = resp.snapshotTerminal().getMessage();
        MessageProtocolState state = message.getProtocolState(OpenaiResponsesMessageStateSupport.PROTOCOL_ID);
        assertNotNull(state, message.toString());
        assertEquals("rs_done", state.getData().get(OpenaiResponsesMessageStateSupport.REASONING_ITEM_ID));
        assertEquals("enc_done", state.getData().get(OpenaiResponsesMessageStateSupport.REASONING_ENCRYPTED_CONTENT));
        assertNotNull(state.getSemanticHash(), message.toString());
    }
    @Test
    public void reasoningItem_echoServerIdWithSummary() {
        AssistantMessage thinking = new AssistantMessage("", "x");
        thinking.getMetadata().put("reasoning_item_id", "rs_123");
        thinking.getMetadata().put("reasoning_encrypted_content", "enc_abc");

        ONode root = build(ChatOptions.of(), Collections.singletonList(thinking));

        ONode item = root.get("input").get(0);
        assertEquals("rs_123", item.get("id").getString());
        assertEquals("enc_abc", item.get("encrypted_content").getString());
        assertTrue(item.get("summary").isArray(), root.toJson());
    }

    @Test
    public void reasoningItem_protocolStateIsWrittenAndLegacyMetadataRemainsReadable() {
        ChatAccumulator resp = newResponse(false);
        String json = "{\"id\":\"resp_1\",\"model\":\"gpt-5.4\",\"status\":\"completed\",\"output\":["
                + "{\"type\":\"reasoning\",\"id\":\"rs_state\",\"summary\":[],\"encrypted_content\":\"enc_state\"},"
                + "{\"type\":\"message\",\"role\":\"assistant\",\"content\":[{\"type\":\"output_text\",\"text\":\"答案\"}]}]}";

        assertTrue(parse(resp, json));
        AssistantMessage message = resp.snapshotTerminal().getMessage();
        MessageProtocolState state = message.getProtocolState(OpenaiResponsesMessageStateSupport.PROTOCOL_ID);
        assertNotNull(state, message.toString());
        assertEquals(OpenaiResponsesMessageStateSupport.VERSION, state.getVersion());
        assertEquals("rs_state", state.getData().get("reasoning_item_id"));
        assertEquals("enc_state", state.getData().get("reasoning_encrypted_content"));
        assertNotNull(state.getSemanticHash(), message.toString());
        // 新响应不再把协议内部字段写入应用 metadata。
        assertFalse(message.getMetadata().containsKey("reasoning_item_id"));
        assertFalse(message.getMetadata().containsKey("reasoning_encrypted_content"));
    }

    @Test
    public void parserWorkspaceKeysMustNotCollideWithApplicationMetadata() {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("phase", "application-phase");
        metadata.put(OpenaiResponsesMessageStateSupport.AGGREGATION_PHASE, "commentary");
        metadata.put(OpenaiResponsesMessageStateSupport.AGGREGATION_REASONING_ITEM_ID, "rs_internal");

        MessageProtocolState state = OpenaiResponsesMessageStateSupport.fromAggregation(metadata);
        assertNotNull(state);
        assertEquals("commentary", state.getData().get(OpenaiResponsesMessageStateSupport.PHASE));
        OpenaiResponsesMessageStateSupport.removeProtocolKeys(metadata);
        assertEquals("application-phase", metadata.get("phase"));
        assertFalse(metadata.containsKey(OpenaiResponsesMessageStateSupport.AGGREGATION_PHASE));

        AssistantMessage ordinary = AssistantMessage.snapshot(
                "answer", "", null, null, null, null, null,
                Collections.<String, Object>singletonMap("phase", "commentary"));
        ONode request = build(ChatOptions.of(), Collections.singletonList(ordinary));
        assertFalse(request.get("input").get(0).hasKey("phase"), request.toJson());
    }

    @Test
    public void streamToolMessageMustReuseTerminalReplayStateAfterWorkspaceCleanup() {
        ChatAccumulator acc = newResponse(true);
        MessageProtocolState state = new MessageProtocolState(OpenaiResponsesMessageStateSupport.VERSION)
                .dataPut(OpenaiResponsesMessageStateSupport.REASONING_ITEM_ID, "rs_terminal");
        acc.putTerminalProtocolState(OpenaiResponsesMessageStateSupport.PROTOCOL_ID, state);
        acc.getAggregationMetadata().clear();

        AssistantMessage message = OpenaiResponsesDialect.getInstance()
                .parseAssistantMessage(acc, newToolCallNode("answer", "thought")).get(0);

        assertNotSame(state, message.getProtocolState(OpenaiResponsesMessageStateSupport.PROTOCOL_ID));
        assertNull(state.getSemanticHash());
        assertNotNull(message.getProtocolState(
                OpenaiResponsesMessageStateSupport.PROTOCOL_ID).getSemanticHash());
    }

    @Test
    public void protocolState_isPreferredOverLegacyMetadata() {
        Map<String, Object> data = new HashMap<>();
        data.put(OpenaiResponsesMessageStateSupport.REASONING_ITEM_ID, "rs_new");
        data.put(OpenaiResponsesMessageStateSupport.REASONING_ENCRYPTED_CONTENT, "enc_new");
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("reasoning_item_id", "rs_legacy");
        metadata.put("reasoning_encrypted_content", "enc_legacy");
        AssistantMessage message = AssistantMessage.snapshot(
                "答案", "思考", null, null, null, null,
                Collections.singletonMap(OpenaiResponsesMessageStateSupport.PROTOCOL_ID,
                        new MessageProtocolState(OpenaiResponsesMessageStateSupport.VERSION, data)),
                metadata);

        ONode root = build(ChatOptions.of(), Collections.singletonList(message));
        ONode item = root.get("input").get(0);
        assertEquals("rs_new", item.get("id").getString(), root.toJson());
        assertEquals("enc_new", item.get("encrypted_content").getString(), root.toJson());
    }
    @Test
    public void legacyOutputItems_textConflictFallsBackButKeepsReasoningIdentity() {
        AssistantMessage message = new AssistantMessage("当前答案", "当前思考");
        Map<String, Object> oldMessage = new LinkedHashMap<>();
        oldMessage.put("type", "message");
        oldMessage.put("id", "msg_old");
        oldMessage.put("role", "assistant");
        Map<String, Object> oldContent = new LinkedHashMap<>();
        oldContent.put("type", "output_text");
        oldContent.put("text", "旧答案");
        oldMessage.put("content", Collections.singletonList(oldContent));
        message.getMetadata().put(OpenaiResponsesMessageStateSupport.OUTPUT_ITEMS,
                Collections.singletonList(wrapper(0, oldMessage)));
        message.getMetadata().put(OpenaiResponsesMessageStateSupport.MESSAGE_ITEMS,
                Collections.singletonList(Collections.singletonMap("text", "旧答案")));
        message.getMetadata().put(OpenaiResponsesMessageStateSupport.REASONING_ITEM_ID, "rs_legacy");
        message.getMetadata().put(OpenaiResponsesMessageStateSupport.REASONING_ENCRYPTED_CONTENT, "enc_legacy");

        ONode root = build(ChatOptions.of(), Collections.singletonList(message));

        assertEquals(2, root.get("input").size(), root.toJson());
        assertEquals("reasoning", root.get("input").get(0).get("type").getString(), root.toJson());
        assertEquals("rs_legacy", root.get("input").get(0).get("id").getString(), root.toJson());
        assertEquals("enc_legacy", root.get("input").get(0).get("encrypted_content").getString(), root.toJson());
        assertEquals("当前答案", root.get("input").get(1).get("content").getString(), root.toJson());
        assertFalse(root.toJson().contains("msg_old"), root.toJson());
        assertFalse(root.toJson().contains("旧答案"), root.toJson());
    }

    @Test
    public void legacyOutputItems_toolCallConflictFallsBackToCurrentToolCalls() {
        ToolCall currentCall = new ToolCall("call_current", "call_current", "lookup",
                "{\"city\":\"杭州\"}", Collections.<String, Object>singletonMap("city", "杭州"));
        AssistantMessage message = new AssistantMessage("", "",
                Collections.singletonList(currentCall), null);
        Map<String, Object> oldCall = new LinkedHashMap<>();
        oldCall.put("type", "function_call");
        oldCall.put("id", "fc_old");
        oldCall.put("call_id", "call_old");
        oldCall.put("name", "lookup");
        oldCall.put("arguments", "{\"city\":\"上海\"}");
        message.getMetadata().put(OpenaiResponsesMessageStateSupport.OUTPUT_ITEMS,
                Collections.singletonList(wrapper(0, oldCall)));

        ONode root = build(ChatOptions.of(), Collections.singletonList(message));

        assertEquals(1, root.get("input").size(), root.toJson());
        assertEquals("function_call", root.get("input").get(0).get("type").getString(), root.toJson());
        assertEquals("call_current", root.get("input").get(0).get("call_id").getString(), root.toJson());
        assertEquals("{\"city\":\"杭州\"}", root.get("input").get(0).get("arguments").getString(), root.toJson());
        assertFalse(root.toJson().contains("call_old"), root.toJson());
    }

    @Test
    public void targetProtocolState_wrongVersionAndUnboundAreFailClosed() {
        for (MessageProtocolState targetState : Arrays.asList(
                new MessageProtocolState(OpenaiResponsesMessageStateSupport.VERSION + 1)
                        .dataPut(OpenaiResponsesMessageStateSupport.REASONING_ITEM_ID, "rs_wrong_version"),
                new MessageProtocolState(OpenaiResponsesMessageStateSupport.VERSION)
                        .dataPut(OpenaiResponsesMessageStateSupport.REASONING_ITEM_ID, "rs_unbound"))) {
            Map<String, Object> metadata = Collections.<String, Object>singletonMap(
                    OpenaiResponsesMessageStateSupport.REASONING_ITEM_ID, "rs_legacy");
            AssistantMessage message;
            if (targetState.getVersion() != OpenaiResponsesMessageStateSupport.VERSION) {
                message = AssistantMessage.snapshot(
                        "答案", "当前思考", null, null, null, null,
                        Collections.singletonMap(OpenaiResponsesMessageStateSupport.PROTOCOL_ID, targetState),
                        metadata);
            } else {
                ONode node = ONode.ofJson("{\"role\":\"assistant\",\"text\":\"答案\"," +
                        "\"thinking\":\"当前思考\"}");
                node.getOrNew("metadata").set("reasoning_item_id", "rs_legacy");
                node.getOrNew("protocolStates")
                        .getOrNew(OpenaiResponsesMessageStateSupport.PROTOCOL_ID)
                        .set("version", targetState.getVersion())
                        .getOrNew("data").set(OpenaiResponsesMessageStateSupport.REASONING_ITEM_ID,
                                "rs_unbound");
                message = (AssistantMessage) ChatMessage.fromJson(node);
            }

            ONode root = build(ChatOptions.of(), Collections.singletonList(message));

            assertEquals("reasoning", root.get("input").get(0).get("type").getString(), root.toJson());
            assertFalse(root.get("input").get(0).hasKey("id"), root.toJson());
            assertEquals("当前思考", root.get("input").get(0).get("content").get(0).get("text").getString(), root.toJson());
            assertFalse(root.toJson().contains("rs_legacy"), root.toJson());
            assertFalse(root.toJson().contains("rs_wrong_version"), root.toJson());
            assertFalse(root.toJson().contains("rs_unbound"), root.toJson());
        }
    }

    @Test
    public void glmReplayDropsUnsupportedReasoningAndSanitizesFunctionArguments() {
        AssistantMessage message = new AssistantMessage("", "");
        List<Map<String, Object>> items = new ArrayList<>();
        Map<String, Object> reasoning = new HashMap<>();
        reasoning.put("type", "reasoning");
        reasoning.put("id", "rs_1");
        reasoning.put("summary", Collections.emptyList());
        items.add(wrapper(0, reasoning));
        Map<String, Object> function = new HashMap<>();
        function.put("type", "function_call");
        function.put("id", "fc_1");
        function.put("call_id", "call_1");
        function.put("name", "get_weather");
        function.put("arguments", "{}{\"location\":\"杭州\"}");
        items.add(wrapper(1, function));
        message.getMetadata().put("responses_output_items", items);

        ONode root = build("glm-5.3", ChatOptions.of(), Collections.singletonList(message));
        assertEquals(1, root.get("input").size(), root.toJson());
        ONode call = root.get("input").get(0);
        assertEquals("function_call", call.get("type").getString());
        assertEquals("{\"location\":\"杭州\"}", call.get("arguments").getString());
    }

    @Test
    public void glmReasoningReplayCanBeExplicitlyEnabled() {
        AssistantMessage message = new AssistantMessage("", "");
        Map<String, Object> reasoning = new HashMap<>();
        reasoning.put("type", "reasoning");
        reasoning.put("id", "rs_1");
        reasoning.put("summary", Collections.emptyList());
        message.getMetadata().put("responses_output_items",
                Collections.singletonList(wrapper(0, reasoning)));

        ONode root = build("glm-5.3", ChatOptions.of()
                .optionSet("responses_reasoning_replay_enabled", true),
                Collections.singletonList(message));
        assertEquals("reasoning", root.get("input").get(0).get("type").getString());
    }

    private Map<String, Object> wrapper(int index, Map<String, Object> item) {
        Map<String, Object> wrapper = new HashMap<>();
        wrapper.put("output_index", index);
        wrapper.put("item", item);
        return wrapper;
    }


    @Test
    public void parserMessageJsonRoundTrip_replaysOriginalResponsesOutputItems() {
        ChatAccumulator resp = newResponse(false);
        String json = "{\"id\":\"resp_roundtrip\",\"model\":\"gpt-5.4\",\"status\":\"completed\",\"output\":["
                + "{\"type\":\"reasoning\",\"id\":\"rs_roundtrip\",\"summary\":[],\"encrypted_content\":\"enc_roundtrip\"},"
                + "{\"type\":\"message\",\"id\":\"msg_roundtrip\",\"status\":\"completed\",\"role\":\"assistant\","
                + "\"content\":[{\"type\":\"output_text\",\"text\":\"真实答案\",\"annotations\":[]}]},"
                + "{\"type\":\"function_call\",\"id\":\"fc_roundtrip\",\"call_id\":\"call_roundtrip\","
                + "\"name\":\"lookup\",\"arguments\":\"{\\\"city\\\":\\\"杭州\\\"}\"}]}";

        assertTrue(parse(resp, json));
        AssistantMessage parsed = resp.snapshotTerminal().getMessage();
        AssistantMessage restored = (AssistantMessage) ChatMessage.fromJson(ChatMessage.toJson(parsed));
        ONode replay = build(ChatOptions.of(), Collections.singletonList(restored));

        assertEquals(3, replay.get("input").size(), replay.toJson());
        assertEquals("rs_roundtrip", replay.get("input").get(0).get("id").getString(), replay.toJson());
        assertEquals("enc_roundtrip", replay.get("input").get(0).get("encrypted_content").getString(), replay.toJson());
        assertEquals("msg_roundtrip", replay.get("input").get(1).get("id").getString(), replay.toJson());
        assertEquals("真实答案", replay.get("input").get(1).get("content").get(0).get("text").getString(), replay.toJson());
        assertEquals("call_roundtrip", replay.get("input").get(2).get("call_id").getString(), replay.toJson());
        assertEquals("{\"city\":\"杭州\"}", replay.get("input").get(2).get("arguments").getString(), replay.toJson());
    }

    @Test
    public void protocolState_roundTripAndSemanticMismatchFallsBackToCommonFields() {
        Map<String, Object> data = new HashMap<>();
        data.put(OpenaiResponsesMessageStateSupport.REASONING_ITEM_ID, "rs_persisted");
        AssistantMessage source = AssistantMessage.snapshot(
                "答案", "思考", null, null, null, null,
                Collections.singletonMap(OpenaiResponsesMessageStateSupport.PROTOCOL_ID,
                        new MessageProtocolState(OpenaiResponsesMessageStateSupport.VERSION, data)));

        AssistantMessage restored = (AssistantMessage) ChatMessage.fromJson(ChatMessage.toJson(source));
        ONode replay = build(ChatOptions.of(), Collections.singletonList(restored));
        assertEquals("rs_persisted", replay.get("input").get(0).get("id").getString(), replay.toJson());

        AssistantMessage changed = AssistantMessage.snapshot(
                "改写后的答案", "新的思考", null, null, null, null,
                Collections.singletonMap(OpenaiResponsesMessageStateSupport.PROTOCOL_ID,
                        restored.getProtocolState(OpenaiResponsesMessageStateSupport.PROTOCOL_ID)));
        ONode fallback = build(ChatOptions.of(), Collections.singletonList(changed));
        assertFalse(fallback.get("input").get(0).hasKey("id"), fallback.toJson());
        assertEquals("新的思考", fallback.get("input").get(0).get("content").get(0).get("text").getString());
    }

    @Test
    public void foreignProtocolState_isIgnoredByResponsesBuilder() {
        Map<String, Object> foreignData = new HashMap<>();
        foreignData.put(OpenaiResponsesMessageStateSupport.REASONING_ITEM_ID, "must_not_replay");
        AssistantMessage message = AssistantMessage.snapshot(
                "答案", "通用思考", null, null, null, null,
                Collections.singletonMap("anthropic.messages",
                        new MessageProtocolState(1, foreignData)));

        ONode root = build(ChatOptions.of(), Collections.singletonList(message));
        assertFalse(root.get("input").get(0).hasKey("id"), root.toJson());
        assertEquals("通用思考", root.get("input").get(0).get("content").get(0).get("text").getString());
    }
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
    public void outputFormat_jsonScalarSchema_fallbackToJsonObject() {
        ChatOptions options = ChatOptions.of().outputSchema("[]");
        OpenaiResponsesDialect.getInstance().prepareOutputFormatOptions(options);
        ONode root = build(options, Collections.singletonList(ChatMessage.ofUser("hi")));
        assertEquals("json_object", root.get("text").get("format").get("type").getString());
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
        assertFalse(format.get("schema").get("additionalProperties").getBoolean(), root.toJson());
        assertEquals("city", format.get("schema").get("required").get(0).getString());
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

        AssistantMessage msg = resp.snapshotTerminal().getMessage();
        assertNotNull(msg, "非流式应生成终态消息");
        assertEquals("答案", msg.getText());
        assertEquals("思考中", msg.getThinking());
        assertTrue(msg.hasThinking());
        MessageProtocolState state = msg.getProtocolState(OpenaiResponsesMessageStateSupport.PROTOCOL_ID);
        assertNotNull(state, msg.toString());
        assertEquals("rs_1", state.getData().get(OpenaiResponsesMessageStateSupport.REASONING_ITEM_ID));
        assertEquals("enc", state.getData().get(OpenaiResponsesMessageStateSupport.REASONING_ENCRYPTED_CONTENT));
        assertFalse(msg.getMetadata().containsKey("reasoning_item_id"));
        assertEquals("stop", resp.getLastFinishReasonNormalized());
    }

    @Test
    public void nonStream_toolCallsFinishReason() {
        ChatAccumulator resp = newResponse(false);
        String json = "{\"model\":\"gpt-5.4\",\"status\":\"completed\",\"output\":["
                + "{\"type\":\"function_call\",\"call_id\":\"call_1\",\"name\":\"getWeather\","
                + "\"arguments\":\"{\\\"city\\\":\\\"hz\\\"}\"}]}";

        assertTrue(parse(resp, json));

        AssistantMessage message = resp.snapshotTerminal().getMessage();
        assertNotNull(message);
        // 完成原因已是响应级属性：断原始值（框架归一化后为 "tool"）
        assertEquals("tool_calls", resp.lastFinishReason);
        ToolCall call = message.getToolCalls().get(0);
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

        assertEquals("思考", resp.getAggregationThinking());
        assertEquals("enc_x", resp.getAggregationMetadata().get(
                        OpenaiResponsesMessageStateSupport.AGGREGATION_REASONING_ENCRYPTED_CONTENT),
                "output_item.done 的 encrypted_content 应进入命名空间化解析工作区");
        assertEquals("rs_1", resp.getAggregationMetadata().get(
                OpenaiResponsesMessageStateSupport.AGGREGATION_REASONING_ITEM_ID));
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

        reasoning.put("summary", "verbose");
        reasoning.put("unknown_field", "x");
        ONode invalid = build(ChatOptions.of().optionSet("reasoning", reasoning),
                Collections.singletonList(ChatMessage.ofUser("hi")));
        assertFalse(invalid.get("reasoning").hasKey("summary"));
        assertFalse(invalid.get("reasoning").hasKey("unknown_field"));
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
    public void gptNewGenerationAliases_receiveAutomaticReasoning() {
        String[] models = {
                "gpt-5.6", "gpt5.6", "us.openai.gpt-5.6-sol",
                "gpt-6", "gpt6.1", "us.openai.gpt-6.1-pro"
        };
        for (String model : models) {
            ONode root = build(model, ChatOptions.of().thinking(true),
                    Collections.singletonList(ChatMessage.ofUser("hi")));
            assertEquals("auto", root.get("reasoning").get("summary").getString(),
                    model + ": " + root.toJson());
            assertEquals(model, root.get("model").getString(), "出站 model 不应被能力识别改写");
        }
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
        // 4.1：非流式产出的是 text/thinking 合并的单条消息，
        // 不能再以已删除的 isThinking() 作为是否回传 reasoning 项的分闸
        AssistantMessage msg = new AssistantMessage("结论", "思考过程");
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
        AssistantMessage msg = new AssistantMessage("结论", "思考过程");

        ONode root = build(ChatOptions.of(), Collections.singletonList(msg));

        ONode input = root.get("input");
        assertEquals("reasoning", input.get(0).get("type").getString(), root.toJson());
        assertEquals("思考过程", input.get(0).get("content").get(0).get("text").getString(), root.toJson());
        assertEquals("结论", input.get(1).get("content").getString(), root.toJson());
    }

    @Test
    public void mixedLegacyFlagStillReplaysThinkingThenText() {
        AssistantMessage mixed = new AssistantMessage("answer", "thinking");

        ONode root = build(ChatOptions.of(), Collections.singletonList(mixed));

        assertEquals(2, root.get("input").size(), root.toJson());
        assertEquals("reasoning", root.get("input").get(0).get("type").getString());
        assertEquals("answer", root.get("input").get(1).get("content").getString());
    }

    @Test
    public void thinkingOnlyMessage_noEmptyAssistantItem() {
        AssistantMessage thinking = new AssistantMessage("", "只有思考");

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

        AssistantMessage message = resp.snapshotTerminal().getMessage();
        assertNotNull(message, "未识别输出仍应提交空终态消息");
        assertEquals("stop", resp.getLastFinishReasonNormalized());
    }

    @Test
    public void stream_reasoningMetadataAggregatedForReplay() {
        // reasoning metadata 直接写入聚合状态，不再寄生于思考分片内容项。
        ChatAccumulator resp = newResponse(true);

        parse(resp, "data: {\"type\":\"response.output_item.added\","
                + "\"item\":{\"type\":\"reasoning\",\"id\":\"rs_1\"}}");
        parse(resp, "data: {\"type\":\"response.reasoning_text.delta\",\"delta\":\"思考\"}");
        parse(resp, "data: {\"type\":\"response.output_item.done\","
                + "\"item\":{\"type\":\"reasoning\",\"id\":\"rs_1\",\"encrypted_content\":\"enc_x\"}}");
        parse(resp, "data: {\"type\":\"response.output_text.delta\",\"delta\":\"答案\"}");

        AssistantMessage agg = resp.snapshotTerminal().getMessage();
        assertNotNull(agg);
        assertEquals("rs_1", agg.getMetadata().get(
                OpenaiResponsesMessageStateSupport.AGGREGATION_REASONING_ITEM_ID), agg.toString());
        assertEquals("enc_x", agg.getMetadata().get(
                OpenaiResponsesMessageStateSupport.AGGREGATION_REASONING_ENCRYPTED_CONTENT), agg.toString());
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
        assertEquals("rs_only_id", agg.getMetadata().get(
                OpenaiResponsesMessageStateSupport.AGGREGATION_REASONING_ITEM_ID), agg.toString());
    }

    @Test
    public void stream_reasoningMetadataNotDuplicatedAfterDeltas() {
        // 元数据已随思考分片交付时，done 帧不应再补一条重复的空 thinking 消息
        ChatAccumulator resp = newResponse(true);

        parse(resp, "data: {\"type\":\"response.output_item.added\","
                + "\"item\":{\"type\":\"reasoning\",\"id\":\"rs_1\",\"encrypted_content\":\"enc_x\"}}");
        parse(resp, "data: {\"type\":\"response.reasoning_text.delta\",\"delta\":\"思考\"}");
        assertEquals("思考", resp.getAggregationThinking());
        assertEquals("思考", resp.getAggregationThinking(),
                "reasoning delta 和 metadata 应只进入事件聚合");
        parse(resp, "data: {\"type\":\"response.output_item.done\","
                + "\"item\":{\"type\":\"reasoning\",\"id\":\"rs_1\",\"encrypted_content\":\"enc_x\"}}");

        assertEquals("思考", resp.getAggregationThinking());
        assertEquals("思考", resp.getAggregationThinking(),
                "done 只应补 metadata/signature，不应重复思考内容");
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
        MessageProtocolState state = msg.getProtocolState(OpenaiResponsesMessageStateSupport.PROTOCOL_ID);
        assertNotNull(state, msg.toString());
        assertEquals("rs_1", state.getData().get(OpenaiResponsesMessageStateSupport.REASONING_ITEM_ID));
        assertEquals("enc_x", state.getData().get(OpenaiResponsesMessageStateSupport.REASONING_ENCRYPTED_CONTENT));
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
    public void assistantHistory_legacyInlineThinkIsStrippedFromTextBlock() throws Exception {
        List<ContentBlock> blocks = new ArrayList<>();
        blocks.add(TextBlock.of("<think>private reasoning</think>visible answer"));
        blocks.add(ImageBlock.ofUrl("https://x/y.png"));
        ONode legacyJson = ONode.ofJson(ChatMessage.toJson(
                new AssistantMessage("placeholder", "", null, blocks)));
        legacyJson.set("text", null);
        legacyJson.set("content", "<think>private reasoning</think>visible answer");
        AssistantMessage msg = (AssistantMessage) ChatMessage.fromJson(legacyJson.toJson());

        ONode root = build(ChatOptions.of(), Collections.singletonList((ChatMessage) msg));
        String json = root.toJson();
        assertTrue(json.contains("visible answer"), json);
        assertFalse(json.contains("private reasoning"), json);
    }

    @Test
    public void assistantHistory_newModelTextBlockNotStripped() {
        // 4.1 起 text/thinking 已物理分离，TextBlock 不再内嵌 think 标签；
        // 正文恰以 <think> 开头的合法文本不能被当作思考剔除
        List<ContentBlock> blocks = new ArrayList<>();
        blocks.add(TextBlock.of("<think> 标签的用法说明"));
        blocks.add(ImageBlock.ofUrl("https://x/y.png"));
        AssistantMessage msg = new AssistantMessage("<think> 标签的用法说明", "", null, blocks);

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
    public void streamOfficialOutputTextDelta_isAppendedWithoutSnapshotGuessing() {
        ChatAccumulator resp = newResponse(true);

        parseStream(resp, "{\"type\":\"response.output_item.added\",\"item\":{\"id\":\"msg_1\",\"type\":\"message\"}}\n"
                + "{\"type\":\"response.output_text.delta\",\"delta\":\"所有代码修改完成。\"}\n"
                + "{\"type\":\"response.output_text.delta\",\"delta\":\"所有代码修改完成。更新任务进度并运行验证\"}");

        assertEquals("所有代码修改完成。所有代码修改完成。更新任务进度并运行验证",
                resp.getAggregationText());
    }

    @Test
    public void streamReasoningSummaryDelta_isPublishedAsThinking() {
        ChatAccumulator resp = newResponse(true);

        parseStream(resp,
                "{\"type\":\"response.output_item.added\",\"item\":{\"id\":\"rs_1\",\"type\":\"reasoning\"}}\n"
                        + "{\"type\":\"response.reasoning_summary_part.added\",\"item_id\":\"rs_1\"}\n"
                        + "{\"type\":\"response.reasoning_summary_text.delta\",\"item_id\":\"rs_1\",\"delta\":\"正在分析\"}\n"
                        + "{\"type\":\"response.reasoning_summary_text.delta\",\"item_id\":\"rs_1\",\"delta\":\"请求参数\"}");

        assertEquals("正在分析请求参数", resp.getAggregationThinking());
        assertEquals("正在分析请求参数", resp.getAggregationThinking(),
                "reasoning delta 应直接进入事件聚合");
    }

    @Test
    public void streamCumulativeReasoningDelta_isNormalizedToSuffix() {
        ChatAccumulator resp = newResponse(true,
                ChatOptions.of().optionSet("responses_reasoning_delta_mode", "snapshot"));

        parseStream(resp, "{\"type\":\"response.output_item.added\",\"item\":{\"id\":\"rs_1\",\"type\":\"reasoning\"}}\n"
                + "{\"type\":\"response.reasoning_text.delta\",\"delta\":\"补登README目录结构\"}\n"
                + "{\"type\":\"response.reasoning_text.delta\",\"delta\":\"补登README目录结构(新增composables/)\"}");

        assertEquals("补登README目录结构(新增composables/)", resp.getAggregationThinking());
        assertEquals("补登README目录结构(新增composables/)", resp.getAggregationThinking());
    }

    @Test
    public void streamShortLegitDeltas_areNotTreatedAsSnapshot() {
        ChatAccumulator resp = newResponse(true);

        // 合规增量在流首极易偶然构成前缀关系（"好" / "好的"），累计长度未达门槛时不得改写
        parseStream(resp, "{\"type\":\"response.output_item.added\",\"item\":{\"id\":\"msg_1\",\"type\":\"message\"}}\n"
                + "{\"type\":\"response.output_text.delta\",\"delta\":\"好\"}\n"
                + "{\"type\":\"response.output_text.delta\",\"delta\":\"好的\"}");

        assertEquals("好好的", resp.getAggregationText());
    }

    @Test
    public void streamOfficialRepeatedOutputTextDelta_isPreserved() {
        ChatAccumulator resp = newResponse(true);

        parseStream(resp, "{\"type\":\"response.output_item.added\",\"item\":{\"id\":\"msg_1\",\"type\":\"message\"}}\n"
                + "{\"type\":\"response.output_text.delta\",\"delta\":\"所有代码修改完成。\"}\n"
                + "{\"type\":\"response.output_text.delta\",\"delta\":\"所有代码修改完成。更新任务进度\"}\n"
                + "{\"type\":\"response.output_text.delta\",\"delta\":\"所有代码修改完成。更新任务进度\"}");

        assertEquals(3, resp.getAggregationText().split("所有代码修改完成。", -1).length - 1,
                "官方 delta 即使内容相同也都是新增负载");
        assertEquals("所有代码修改完成。所有代码修改完成。更新任务进度所有代码修改完成。更新任务进度",
                resp.getAggregationText());
    }
    @Test
    public void streamDoneEvents_supplyFinalPayloadWhenDeltasAreMissing() {
        ChatAccumulator resp = newResponse(true);
        parseStream(resp, "{\"type\":\"response.output_text.done\",\"item_id\":\"msg_1\","
                + "\"content_index\":0,\"text\":\"done text\"}");
        parseStream(resp, "{\"type\":\"response.reasoning_summary_text.done\",\"item_id\":\"rs_1\","
                + "\"summary_index\":0,\"text\":\"summary text\"}");

        assertEquals("done text", resp.getAggregationText());
        assertEquals("summary text", resp.getAggregationThinking());
    }

    @Test
    public void streamContentPartDone_suppliesOutputText() {
        ChatAccumulator resp = newResponse(true);
        parseStream(resp, "{\"type\":\"response.content_part.done\",\"item_id\":\"msg_1\","
                + "\"content_index\":0,\"part\":{\"type\":\"output_text\",\"text\":\"part text\"}}");
        assertEquals("part text", resp.getAggregationText());
    }

    @Test
    public void streamRefusalDone_suppliesTextWithoutDelta() {
        ChatAccumulator resp = newResponse(true);
        parseStream(resp, "{\"type\":\"response.refusal.done\",\"item_id\":\"msg_1\","
                + "\"content_index\":0,\"refusal\":\"拒答内容\"}");
        assertEquals("拒答内容", resp.getAggregationText());
    }

    @Test
    public void responseMessagePhase_isReplayedOnNextAssistantInput() {
        ChatAccumulator resp = newResponse(false);
        String responseJson = "{\"model\":\"gpt-5.4\",\"status\":\"completed\",\"output\":["
                + "{\"type\":\"message\",\"role\":\"assistant\",\"phase\":\"commentary\","
                + "\"content\":[{\"type\":\"output_text\",\"text\":\"answer\"}]}]}";
        assertTrue(parse(resp, responseJson));
        AssistantMessage message = resp.snapshotTerminal().getMessage();
        MessageProtocolState state = message.getProtocolState(OpenaiResponsesMessageStateSupport.PROTOCOL_ID);
        assertNotNull(state, message.toString());
        assertEquals("commentary", state.getData().get(OpenaiResponsesMessageStateSupport.PHASE));

        ONode replay = build(ChatOptions.of(), Collections.singletonList(message));
        assertEquals("commentary", replay.get("input").get(0).get("phase").getString(), replay.toJson());
    }

    @Test
    public void explicitPromptCacheBreakpoint_isAttachedToLastInputContent() {
        ChatOptions options = ChatOptions.of().optionSet("prompt_cache_breakpoint", "after_tools");
        ONode root = build("gpt-5.6", options, Collections.singletonList(ChatMessage.ofUser("hi")));
        ONode content = root.get("input").get(0).get("content");
        assertTrue(content.isArray(), root.toJson());
        assertEquals("explicit", content.get(content.size() - 1)
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
    @Test
    public void streamOptions_includeObfuscationKeptOnlyForStreamingResponses() {
        ChatConfig config = new ChatConfig();
        config.setModel("gpt-5.4");
        ChatOptions options = ChatOptions.of().optionSet("stream_options",
                Collections.singletonMap("include_obfuscation", false));
        ONode root = builder.build(config, options,
                Collections.singletonList(ChatMessage.ofUser("hi")), true);

        assertFalse(root.get("stream_options").get("include_obfuscation").getBoolean(), root.toJson());

        ONode nonStream = builder.build(config, options,
                Collections.singletonList(ChatMessage.ofUser("hi")), false);
        assertFalse(nonStream.hasKey("stream_options"), nonStream.toJson());
    }

    @Test
    public void streamCompletedFinalResponse_isUsedAsIdempotentFallback() {
        ChatAccumulator resp = newResponse(true);
        assertTrue(parseStream(resp, "{\"type\":\"response.completed\",\"response\":{"
                + "\"id\":\"resp_1\",\"model\":\"gpt-5.4\",\"output\":["
                + "{\"type\":\"message\",\"id\":\"msg_1\",\"role\":\"assistant\","
                + "\"content\":[{\"type\":\"output_text\",\"text\":\"最终答案\"}]}]}}"));
        assertEquals("最终答案", resp.snapshotTerminal().getMessage().getText());

        // 同一终态重复到达不能重复追加。
        parseStream(resp, "{\"type\":\"response.completed\",\"response\":{"
                + "\"id\":\"resp_1\",\"model\":\"gpt-5.4\",\"output\":["
                + "{\"type\":\"message\",\"id\":\"msg_1\",\"role\":\"assistant\","
                + "\"content\":[{\"type\":\"output_text\",\"text\":\"最终答案\"}]}]}}" );
        assertEquals("最终答案", resp.snapshotTerminal().getMessage().getText());
    }

    @Test
    public void streamTextDelta_isolatedByContentIndexAndAppended() {
        ChatAccumulator resp = newResponse(true);
        parseStream(resp, "{\"type\":\"response.output_item.added\",\"item\":{"
                + "\"id\":\"msg_1\",\"type\":\"message\"}}\n"
                + "{\"type\":\"response.content_part.added\",\"item_id\":\"msg_1\",\"content_index\":0,"
                + "\"part\":{\"type\":\"output_text\"}}\n"
                + "{\"type\":\"response.output_text.delta\",\"item_id\":\"msg_1\",\"content_index\":0,\"delta\":\"abcdefgh\"}\n"
                + "{\"type\":\"response.content_part.added\",\"item_id\":\"msg_1\",\"content_index\":1,"
                + "\"part\":{\"type\":\"output_text\"}}\n"
                + "{\"type\":\"response.output_text.delta\",\"item_id\":\"msg_1\",\"content_index\":1,\"delta\":\"ijklmnop\"}\n"
                + "{\"type\":\"response.output_text.delta\",\"item_id\":\"msg_1\",\"content_index\":0,\"delta\":\"abcdefghUPDATED\"}");

        assertEquals("abcdefghijklmnopabcdefghUPDATED", resp.getAggregationText());
    }

    @Test
    public void usageOfficialZeroCachedTokens_winsOverCompatibilityField() {
        ChatAccumulator resp = newResponse(false);
        parse(resp, "{\"model\":\"gpt-5.4\",\"status\":\"completed\",\"output\":[],"
                + "\"usage\":{\"input_tokens\":1,\"output_tokens\":1,\"total_tokens\":2,"
                + "\"input_tokens_details\":{\"cached_tokens\":0,\"cache_write_tokens\":0},\"prompt_cache_hit_tokens\":99}}");
        assertNotNull(resp.getUsage());
        assertEquals(0, resp.getUsage().cacheReadInputTokens());
        assertEquals(0, resp.getUsage().cacheCreationInputTokens());
    }

    @Test
    public void nonStreamOutputAudioPreservesBlockAndTranscript() {
        ChatAccumulator resp = newResponse(false);
        parse(resp, "{\"id\":\"resp_1\",\"model\":\"gpt-5.4\",\"status\":\"completed\",\"output\":["
                + "{\"id\":\"msg_1\",\"type\":\"message\",\"role\":\"assistant\",\"content\":["
                + "{\"type\":\"output_audio\",\"data\":\"QUJD\",\"transcript\":\"hello\"}]}]}");
        AssistantMessage msg = resp.snapshotTerminal().getMessage();
        assertEquals("hello", msg.getText());
        assertTrue(msg.getBlocks().get(1) instanceof AudioBlock);
        AudioBlock audio = (AudioBlock) msg.getBlocks().get(1);
        assertEquals("QUJD", audio.getData());
        assertEquals("hello", audio.metas().get("transcript"));
    }

    @Test
    public void streamOutputAudioMatchesNonStreamTerminalShape() {
        String output = "[{\"id\":\"msg_1\",\"type\":\"message\",\"role\":\"assistant\",\"content\":["
                + "{\"type\":\"output_audio\",\"data\":\"QUJD\",\"transcript\":\"hello\"}]}]";

        ChatAccumulator call = newResponse(false);
        parse(call, "{\"id\":\"resp_1\",\"model\":\"gpt-5.4\",\"status\":\"completed\",\"output\":"
                + output + "}");

        ChatAccumulator stream = newResponse(true);
        parseStream(stream, "{\"type\":\"response.output_audio.delta\",\"delta\":\"QUJD\"}");
        parseStream(stream, "{\"type\":\"response.output_audio.done\"}");
        parseStream(stream, "{\"type\":\"response.completed\",\"response\":{\"id\":\"resp_1\",\"output\":"
                + output + "}}");

        AssistantMessage callMessage = call.snapshotTerminal().getMessage();
        AssistantMessage streamMessage = stream.snapshotTerminal().getMessage();
        assertEquals(callMessage.getText(), streamMessage.getText());
        assertEquals(1, streamMessage.getBlocks().stream().filter(b -> b instanceof AudioBlock).count());
        AudioBlock callAudio = (AudioBlock) callMessage.getBlocks().get(1);
        AudioBlock streamAudio = (AudioBlock) streamMessage.getBlocks().stream()
                .filter(b -> b instanceof AudioBlock).findFirst().get();
        assertEquals(callAudio.getData(), streamAudio.getData());
        assertEquals(callAudio.metas().get("transcript"), streamAudio.metas().get("transcript"));
    }

    @Test
    public void multipleAssistantPhasesAndReasoningItemsReplayIndependently() {
        ChatAccumulator resp = newResponse(false);
        parse(resp, "{\"model\":\"gpt-5.4\",\"status\":\"completed\",\"output\":["
                + "{\"type\":\"reasoning\",\"id\":\"rs_1\",\"summary\":[],\"encrypted_content\":\"enc_1\"},"
                + "{\"type\":\"message\",\"id\":\"msg_1\",\"status\":\"completed\",\"role\":\"assistant\",\"phase\":\"commentary\","
                + "\"content\":[{\"type\":\"output_text\",\"text\":\"working\",\"annotations\":[],\"logprobs\":[]}]},"
                + "{\"type\":\"reasoning\",\"id\":\"rs_2\",\"summary\":[],\"encrypted_content\":\"enc_2\"},"
                + "{\"type\":\"message\",\"id\":\"msg_2\",\"status\":\"completed\",\"role\":\"assistant\",\"phase\":\"final_answer\","
                + "\"content\":[{\"type\":\"output_text\",\"text\":\"done\",\"annotations\":[],\"logprobs\":[]}]}]}");

        ONode replay = build(ChatOptions.of(), Collections.singletonList(
                resp.snapshotTerminal().getMessage()));
        assertEquals("reasoning", replay.get("input").get(0).get("type").getString());
        assertEquals("rs_1", replay.get("input").get(0).get("id").getString());
        assertEquals("commentary", replay.get("input").get(1).get("phase").getString());
        assertEquals("working", replay.get("input").get(1).get("content").get(0).get("text").getString());
        assertEquals("reasoning", replay.get("input").get(2).get("type").getString());
        assertEquals("rs_2", replay.get("input").get(2).get("id").getString());
        assertEquals("final_answer", replay.get("input").get(3).get("phase").getString());
        assertEquals("done", replay.get("input").get(3).get("content").get(0).get("text").getString());
    }

    @Test
    public void promptCacheOptionsAreNormalizedAndBreakpointsAreSchemaSafe() {
        Map<String, Object> cacheOptions = new HashMap<>();
        cacheOptions.put("mode", "explicit");
        cacheOptions.put("ttl", "30m");
        Map<String, Object> breakpointOption = new HashMap<>();
        breakpointOption.put("mode", "explicit");
        breakpointOption.put("ttl", "30m");
        ChatOptions options = ChatOptions.of()
                .optionSet("prompt_cache_options", cacheOptions)
                .optionSet("prompt_cache_retention", "24h")
                .optionSet("prompt_cache_breakpoint", breakpointOption);
        ONode root = build("gpt-5.6", options, Collections.singletonList(ChatMessage.ofUser("hi")));
        assertEquals("explicit", root.get("prompt_cache_options").get("mode").getString());
        assertEquals("30m", root.get("prompt_cache_options").get("ttl").getString());
        assertEquals("24h", root.get("prompt_cache_retention").getString());
        assertFalse(build("gpt-5.6", ChatOptions.of().optionSet("prompt_cache_retention", "1h"),
                Collections.singletonList(ChatMessage.ofUser("hi"))).hasKey("prompt_cache_retention"));
        ONode breakpoint = root.get("input").get(0).get("content").get(0).get("prompt_cache_breakpoint");
        assertEquals(1, breakpoint.getObject().size(), breakpoint.toJson());
        assertEquals("explicit", breakpoint.get("mode").getString());
    }

    @Test
    public void multiplePromptCacheBreakpointsAreCappedAndEnableExplicitModeWhenNeeded() {
        List<ChatMessage> messages = Arrays.asList(
                ChatMessage.ofUser("a"), ChatMessage.ofAssistant("b"),
                ChatMessage.ofUser("c"), ChatMessage.ofAssistant("d"), ChatMessage.ofUser("e"));
        ONode root = build("gpt-5.6", ChatOptions.of().optionSet("prompt_cache_breakpoints",
                Arrays.asList("explicit", "explicit", "explicit", "explicit", "explicit")), messages);
        int count = 0;
        for (ONode item : root.get("input").getArray()) {
            ONode content = item.getOrNull("content");
            if (content != null && content.isArray()) {
                for (ONode part : content.getArray()) {
                    if (part.hasKey("prompt_cache_breakpoint")) count++;
                }
            }
        }
        assertEquals(4, count);
        assertEquals("explicit", root.get("prompt_cache_options").get("mode").getString());

        ONode disabled = build("gpt-5.6", ChatOptions.of().optionSet("prompt_cache_breakpoint", false), messages);
        assertFalse(disabled.hasKey("prompt_cache_options"));
        for (ONode item : disabled.get("input").getArray()) {
            ONode content = item.getOrNull("content");
            if (content != null && content.isArray()) {
                for (ONode part : content.getArray()) assertFalse(part.hasKey("prompt_cache_breakpoint"));
            }
        }
    }

    @Test
    public void officialReasoningDeltaDefaultsToDeltaAndSnapshotModeIsOptIn() {
        ChatAccumulator official = newResponse(true);
        parseStream(official, "{\"type\":\"response.reasoning_text.delta\",\"item_id\":\"rs_1\","
                + "\"content_index\":0,\"delta\":\"abcdefgh\"}\n"
                + "{\"type\":\"response.reasoning_text.delta\",\"item_id\":\"rs_1\","
                + "\"content_index\":0,\"delta\":\"abcdefghX\"}");
        assertEquals("abcdefghabcdefghX", official.getAggregationThinking());
        assertEquals("abcdefghabcdefghX", official.getAggregationThinking());

        ChatAccumulator compatible = newResponse(true,
                ChatOptions.of().optionSet("responses_reasoning_delta_mode", "snapshot"));
        parseStream(compatible, "{\"type\":\"response.reasoning_text.delta\",\"item_id\":\"rs_1\","
                + "\"content_index\":0,\"delta\":\"abcdefgh\"}\n"
                + "{\"type\":\"response.reasoning_text.delta\",\"item_id\":\"rs_1\","
                + "\"content_index\":0,\"delta\":\"abcdefghX\"}");
        assertEquals("abcdefghX", compatible.getAggregationThinking());
        assertEquals("abcdefghX", compatible.getAggregationThinking());
    }

    @Test
    public void terminalFallbackCompletesFieldsWithoutDuplicatingDeliveredContent() {
        ChatAccumulator resp = newResponse(true);
        parseStream(resp, "{\"type\":\"response.output_item.done\",\"output_index\":0,\"item\":{"
                + "\"id\":\"msg_1\",\"type\":\"message\",\"content\":["
                + "{\"type\":\"output_text\",\"text\":\"hello\"}]}}");
        parseStream(resp, "{\"type\":\"response.completed\",\"response\":{\"id\":\"resp_1\",\"output\":[{"
                + "\"id\":\"msg_1\",\"type\":\"message\",\"content\":["
                + "{\"type\":\"output_text\",\"text\":\"hello world\"}] }]}}");
        assertEquals("hello world", resp.snapshotTerminal().getMessage().getText());
    }

    @Test
    public void repeatedCompletedReasoningIsFieldIdempotent() {
        ChatAccumulator resp = newResponse(true);
        String completed = "{\"type\":\"response.completed\",\"response\":{\"output\":[{"
                + "\"id\":\"rs_1\",\"type\":\"reasoning\",\"encrypted_content\":\"enc\","
                + "\"content\":[{\"type\":\"reasoning_text\",\"text\":\"think\"}],\"summary\":[]}]}}";
        parseStream(resp, completed);
        parseStream(resp, completed);
        assertEquals("think", resp.getAggregationThinking());
        AssistantMessage terminal = resp.snapshotTerminal().getMessage();
        assertNotNull(terminal);
        assertEquals("think", terminal.getThinking());
    }

    @Test
    public void nonStreamReasoningSummaryFallbackIsPerItem() {
        ChatAccumulator resp = newResponse(false);
        parse(resp, "{\"status\":\"completed\",\"output\":["
                + "{\"id\":\"rs_1\",\"type\":\"reasoning\",\"summary\":[],"
                + "\"content\":[{\"type\":\"reasoning_text\",\"text\":\"first\"}]},"
                + "{\"id\":\"rs_2\",\"type\":\"reasoning\",\"content\":[],"
                + "\"summary\":[{\"type\":\"summary_text\",\"text\":\"second\"}]}]}");
        assertEquals("firstsecond", resp.snapshotTerminal().getMessage().getThinkingRaw());
    }

    @Test
    public void orderedReplayPreservesServerToolItem() {
        ChatAccumulator resp = newResponse(false);
        parse(resp, "{\"status\":\"completed\",\"output\":["
                + "{\"id\":\"ws_1\",\"type\":\"web_search_call\",\"status\":\"completed\",\"action\":{\"type\":\"search\",\"query\":\"solon\"}},"
                + "{\"id\":\"msg_1\",\"type\":\"message\",\"status\":\"completed\",\"role\":\"assistant\","
                + "\"content\":[{\"type\":\"output_text\",\"text\":\"ok\",\"annotations\":[],\"logprobs\":[]}]}]}");
        ONode root = build(ChatOptions.of(), Collections.singletonList(
                resp.snapshotTerminal().getMessage()));
        assertEquals("web_search_call", root.get("input").get(0).get("type").getString());
        assertEquals("message", root.get("input").get(1).get("type").getString());
        assertEquals("msg_1", root.get("input").get(1).get("id").getString());
    }

    @Test
    public void compatibilityCacheUsageFallsBackOnlyWhenOfficialDetailsAreMissing() {
        ChatAccumulator resp = newResponse(false);
        parse(resp, "{\"model\":\"gpt-5.4\",\"status\":\"completed\",\"output\":[],\"usage\":{"
                + "\"input_tokens\":2,\"output_tokens\":1,\"input_cached_tokens\":7,"
                + "\"input_cache_write_tokens\":8}}");
        assertEquals(7, resp.getUsage().cacheReadInputTokens());
        assertEquals(8, resp.getUsage().cacheCreationInputTokens());
    }
}
