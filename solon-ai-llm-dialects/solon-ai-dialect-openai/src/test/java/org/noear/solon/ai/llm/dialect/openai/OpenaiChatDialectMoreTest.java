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
import org.noear.solon.ai.chat.ChatAccumulator;
import org.noear.solon.ai.chat.ChatConfig;
import org.noear.solon.ai.chat.ChatOptions;
import org.noear.solon.ai.chat.ChatRequest;
import org.noear.solon.ai.chat.event.ChatEvent;
import org.noear.solon.ai.chat.event.ChatEventType;
import org.noear.solon.ai.chat.event.ChatStreamContext;
import org.noear.solon.ai.chat.event.ChatStreamContextDefault;
import org.noear.solon.ai.chat.event.ChatStreamSession;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.message.MessageProtocolState;
import org.noear.solon.ai.chat.session.InMemoryChatSession;
import org.noear.solon.ai.chat.tool.ToolCall;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * OpenAI chat/completions 方言的协议边界补充测试
 *
 * <p>覆盖流终止（[DONE]）、非 JSON 错误文本、{@code object=error} 变体、非流式一次性响应、
 * usage 细分字段（含 DeepSeek 兼容形态）、以及 {@code stream_options.include_usage} 的注入契约。</p>
 */
public class OpenaiChatDialectMoreTest {
    private final OpenaiChatDialect dialect = OpenaiChatDialect.getInstance();

    private final List<ChatEvent> events = new ArrayList<>();

    private ChatStreamContext newCtx(boolean stream) {
        events.clear();

        ChatConfig config = new ChatConfig();
        config.setModel("gpt-4o");
        ChatRequest req = new ChatRequest(config, dialect, ChatOptions.of(),
                InMemoryChatSession.builder().build(), ChatMessage.ofSystem("test"), null, stream);

        return new ChatStreamContextDefault(config, req, new ChatAccumulator(req, stream),
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

    private String contentChunk(String content) {
        return "{\"id\":\"chatcmpl-1\",\"object\":\"chat.completion.chunk\",\"model\":\"gpt-4o\",\"choices\":[{\"index\":0,"
                + "\"delta\":{\"role\":\"assistant\",\"content\":\"" + content + "\"},\"finish_reason\":null}]}";
    }

    private String joinText(ChatAccumulator acc) {
        return acc.getAggregationText();
    }

    private long countOf(ChatEventType type) {
        long count = 0;
        for (ChatEvent event : events) {
            if (event.is(type)) {
                count++;
            }
        }
        return count;
    }

    // ==================== 请求协议隔离 ====================

    @Test
    public void responsesStateAndLegacyMetadata_doNotLeakIntoChatCompletionsRequest() {

        Map<String, Object> item = new LinkedHashMap<>();
        item.put("type", "message");
        item.put("id", "msg_responses_only");
        item.put("role", "assistant");
        item.put("content", Collections.singletonList(
                Collections.<String, Object>singletonMap("type", "output_text")));
        Map<String, Object> wrapper = new LinkedHashMap<>();
        wrapper.put("output_index", 0);
        wrapper.put("item", item);
        Map<String, Object> stateData = new LinkedHashMap<>();
        stateData.put(OpenaiResponsesMessageStateSupport.OUTPUT_ITEMS,
                Collections.singletonList(wrapper));
        stateData.put(OpenaiResponsesMessageStateSupport.REASONING_ITEM_ID, "rs_state_only");
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put(OpenaiResponsesMessageStateSupport.OUTPUT_ITEMS,
                Collections.singletonList(wrapper));
        metadata.put(OpenaiResponsesMessageStateSupport.REASONING_ITEM_ID, "rs_legacy_only");
        metadata.put(OpenaiResponsesMessageStateSupport.REASONING_ENCRYPTED_CONTENT, "enc_legacy_only");
        AssistantMessage message = AssistantMessage.snapshot(
                "答案", "", null, null, null, null,
                Collections.singletonMap(OpenaiResponsesMessageStateSupport.PROTOCOL_ID,
                        new MessageProtocolState(OpenaiResponsesMessageStateSupport.VERSION, stateData)),
                metadata);

        ChatConfig config = new ChatConfig();
        config.setModel("gpt-4o");
        ONode root = dialect.buildRequestJson(config, ChatOptions.of(),
                Collections.singletonList((ChatMessage) message), false);
        String json = root.toJson();

        assertEquals("答案", root.get("messages").get(0).get("content").getString(), json);
        assertFalse(json.contains("openai.responses"), json);
        assertFalse(json.contains(OpenaiResponsesMessageStateSupport.OUTPUT_ITEMS), json);
        assertFalse(json.contains(OpenaiResponsesMessageStateSupport.REASONING_ITEM_ID), json);
        assertFalse(json.contains("rs_state_only"), json);
        assertFalse(json.contains("rs_legacy_only"), json);
        assertFalse(json.contains("enc_legacy_only"), json);
    }

    // ==================== 流终止与非结构化错误 ====================

    @Test
    public void doneMarker_finishesWithPlaceholderItem() {
        ChatStreamContext ctx = newCtx(true);
        ChatAccumulator acc = ctx.getAccumulator();

        dialect.parseResponseJson(ctx, "[DONE]");

        assertTrue(acc.isFinished(), "[DONE] 必须结束本步");
        assertNull(acc.snapshotTerminal().getMessage(), "无内容的 [DONE] 不应伪造占位消息");

        // 已完成后再来一次 [DONE]（个别中转会重复下发）：不得改变终态
        dialect.parseResponseJson(ctx, "[DONE]");
        assertNull(acc.snapshotTerminal().getMessage());
        assertTrue(events.isEmpty(), "[DONE] 不产生方言事件");
    }

    @Test
    public void doneMarkerAfterContent_keepsExistingItems() {
        ChatStreamContext ctx = newCtx(true);
        ChatAccumulator acc = ctx.getAccumulator();

        dialect.parseResponseJson(ctx, contentChunk("你好"));
        dialect.parseResponseJson(ctx, "[DONE]");

        assertEquals(1, countOf(ChatEventType.TEXT_DELTA));
        assertEquals("你好", joinText(acc));
        assertTrue(acc.isFinished());
    }

    @Test
    public void plainErrorText_notJson_stillReportsError() {
        ChatStreamContext ctx = newCtx(true);

        // 部分中转直接输出非 JSON 的 "error xxx"，不能进 ONode.ofJson
        dialect.parseResponseJson(ctx, "error upstream connection reset");

        assertNotNull(ctx.getAccumulator().getError());
        assertTrue(ctx.getAccumulator().getError().getMessage().contains("upstream connection reset"));

        ChatEvent e = firstOf(ChatEventType.ERROR);
        assertNotNull(e, "非 JSON 错误文本也要发 ERROR 事件");
        assertEquals("error", e.getRawType());
        assertTrue(e.getRaw() == null || e.getRaw().isNull(), "非 JSON 帧没有可用的原始节点");
    }

    @Test
    public void nonObjectFrame_ignoredSilently() {
        ChatStreamContext ctx = newCtx(true);

        dialect.parseResponseJson(ctx, "[1,2]");
        dialect.parseResponseJson(ctx, "123");

        ChatAccumulator acc = ctx.getAccumulator();
        assertEquals("", acc.getAggregationText());
        assertNull(acc.getError());
        assertFalse(acc.isFinished());
        assertTrue(events.isEmpty());
    }

    // ==================== object=error 的三种形态 ====================

    @Test
    public void objectErrorFrame_flatMessage() {
        ChatStreamContext ctx = newCtx(true);

        dialect.parseResponseJson(ctx, "{\"object\":\"error\",\"message\":\"上游过载\",\"code\":500}");

        assertNotNull(ctx.getAccumulator().getError());
        assertEquals("上游过载", ctx.getAccumulator().getError().getMessage());
        assertNotNull(firstOf(ChatEventType.ERROR));
        assertNotNull(firstOf(ChatEventType.ERROR).getRaw(), "JSON 帧应带原始节点");
        assertNull(ctx.getAccumulator().snapshotTerminal().getMessage(), "错误帧不产生终态消息");
    }

    @Test
    public void objectErrorFrame_nestedErrorObject() {
        ChatStreamContext ctx = newCtx(true);

        dialect.parseResponseJson(ctx,
                "{\"object\":\"error\",\"error\":{\"message\":\"quota exhausted\",\"type\":\"insufficient_quota\"}}");

        assertEquals("[insufficient_quota] quota exhausted", ctx.getAccumulator().getError().getMessage());
    }

    @Test
    public void objectErrorFrame_withoutAnyMessage() {
        ChatStreamContext ctx = newCtx(true);

        dialect.parseResponseJson(ctx, "{\"object\":\"error\"}");

        assertEquals("Unknown error", ctx.getAccumulator().getError().getMessage());
    }

    // ==================== 非流式 ====================

    @Test
    public void nonStream_messageWithThinkingAndUsage() {
        ChatStreamContext ctx = newCtx(false);
        ChatAccumulator acc = ctx.getAccumulator();

        dialect.parseResponseJson(ctx, "{\"id\":\"chatcmpl-9\",\"object\":\"chat.completion\",\"model\":\"gpt-4o\","
                + "\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"杭州今天晴\","
                + "\"reasoning_content\":\"先查天气\"},\"finish_reason\":\"stop\"}],"
                + "\"usage\":{\"prompt_tokens\":12,\"completion_tokens\":8,\"total_tokens\":20}}");

        assertTrue(acc.isFinished(), "非流式一次即全部，必须标记完成");
        assertEquals("gpt-4o", acc.getModel());
        assertEquals("chatcmpl-9", ctx.getProviderResponseId(), "供应商响应标识用于排障关联");
        assertEquals("stop", acc.lastFinishReason);
        AssistantMessage message = acc.snapshotTerminal().getMessage();
        assertNotNull(message);
        assertEquals("杭州今天晴", message.getText());
        assertEquals("先查天气", message.getThinking());
        assertEquals(20, acc.getUsage().totalTokens());
    }

    @Test
    public void nonStream_missingFinishReasonStillFinishes() {
        ChatStreamContext ctx = newCtx(false);
        ChatAccumulator acc = ctx.getAccumulator();

        // 部分兼容端点不回 finish_reason：仍要标完成，否则上层拿到 isFinished=false 会挂起
        dialect.parseResponseJson(ctx, "{\"object\":\"chat.completion\",\"model\":\"m\","
                + "\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"ok\"}}]}");

        assertTrue(acc.isFinished());
        assertEquals("ok", acc.snapshotTerminal().getMessage().getText());
    }

    @Test
    public void nonStream_emptyChoices_placeholderItem() {
        ChatStreamContext ctx = newCtx(false);
        ChatAccumulator acc = ctx.getAccumulator();

        dialect.parseResponseJson(ctx, "{\"object\":\"chat.completion\",\"model\":\"m\",\"choices\":[]}");

        assertTrue(acc.isFinished());
        AssistantMessage message = acc.snapshotTerminal().getMessage();
        assertNotNull(message, "空 choices 仍应提交非流式空终态消息");
        assertEquals("", message.getText());
    }

    // ==================== usage 细分字段 ====================

    @Test
    public void usageOnlyChunk_detailsParsedAndTotalFallback() {
        ChatStreamContext ctx = newCtx(true);
        ChatAccumulator acc = ctx.getAccumulator();

        // 官方 include_usage=true 的最后一帧：choices 为空数组或缺省；total_tokens 为 optional
        dialect.parseResponseJson(ctx, "{\"object\":\"chat.completion.chunk\",\"model\":\"gpt-4o\","
                + "\"usage\":{\"prompt_tokens\":100,\"completion_tokens\":20,"
                + "\"completion_tokens_details\":{\"reasoning_tokens\":7},"
                + "\"prompt_tokens_details\":{\"cached_tokens\":30,\"cache_write_tokens\":5}}}");

        assertNotNull(acc.getUsage());
        assertEquals(100, acc.getUsage().promptTokens());
        assertEquals(20, acc.getUsage().completionTokens());
        assertEquals(120, acc.getUsage().totalTokens(), "total_tokens 缺省应为输入+输出");
        assertEquals(7, acc.getUsage().thinkTokens());
        assertEquals(30, acc.getUsage().cacheReadInputTokens());
        assertEquals(5, acc.getUsage().cacheCreationInputTokens());
        assertEquals("", acc.getAggregationText(), "usage 帧不产生正文");
    }

    @Test
    public void usageDeepSeekCompatFallbackFields() {
        ChatStreamContext ctx = newCtx(true);
        ChatAccumulator acc = ctx.getAccumulator();

        // details 节点存在但没有对应字段时，回落到 think_tokens / prompt_cache_hit_tokens（DeepSeek 形态）；
        // choices 非数组（个别端点）不得中断解析
        dialect.parseResponseJson(ctx, "{\"object\":\"chat.completion.chunk\",\"model\":\"m\",\"choices\":{},"
                + "\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":5,\"total_tokens\":99,"
                + "\"completion_tokens_details\":{},\"prompt_tokens_details\":{},"
                + "\"think_tokens\":6,\"prompt_cache_hit_tokens\":7}}");

        assertEquals(99, acc.getUsage().totalTokens(), "显式 total_tokens 优先");
        assertEquals(6, acc.getUsage().thinkTokens());
        assertEquals(7, acc.getUsage().cacheReadInputTokens());
        assertEquals(0, acc.getUsage().cacheCreationInputTokens());
    }

    // ==================== 快照归一的协议边界 ====================

    @Test
    public void duplicatedSnapshotFrameWithFinishReason_stillCompletes() {
        ChatStreamContext ctx = newCtx(true);
        ChatAccumulator acc = ctx.getAccumulator();

        dialect.parseResponseJson(ctx, contentChunk("所有代码修改完成。"));
        dialect.parseResponseJson(ctx, contentChunk("所有代码修改完成。更新任务进度"));
        long before = countOf(ChatEventType.TEXT_DELTA);

        // 整帧都是重复快照，但带 finish_reason：内容要丢弃，完成流程不能丢
        dialect.parseResponseJson(ctx, "{\"object\":\"chat.completion.chunk\",\"model\":\"gpt-4o\",\"choices\":[{\"index\":0,"
                + "\"delta\":{\"content\":\"所有代码修改完成。更新任务进度\"},\"finish_reason\":\"stop\"}]}");

        assertEquals(before, countOf(ChatEventType.TEXT_DELTA), "重复快照不得产生新正文事件");
        assertTrue(acc.isFinished());
        assertEquals("stop", acc.lastFinishReason);
        assertEquals("所有代码修改完成。更新任务进度", joinText(acc));
    }

    @Test
    public void duplicatedSnapshotFrameWithToolCalls_keepsToolCall() {
        ChatStreamContext ctx = newCtx(true);
        ChatAccumulator acc = ctx.getAccumulator();

        dialect.parseResponseJson(ctx, contentChunk("所有代码修改完成。"));
        dialect.parseResponseJson(ctx, contentChunk("所有代码修改完成。更新任务进度"));

        // 文本部分是重复快照，但同帧带工具调用分片：整帧不能丢
        dialect.parseResponseJson(ctx, "{\"object\":\"chat.completion.chunk\",\"model\":\"gpt-4o\",\"choices\":[{\"index\":0,"
                + "\"delta\":{\"content\":\"所有代码修改完成。更新任务进度\",\"tool_calls\":[{\"index\":0,\"id\":\"call_1\","
                + "\"type\":\"function\",\"function\":{\"name\":\"get_weather\",\"arguments\":\"{}\"}}]},"
                + "\"finish_reason\":null}]}");

        ChatEvent toolStart = firstOf(ChatEventType.TOOL_CALL_START);
        assertNotNull(toolStart, "工具调用分片不得被快照判定丢弃");
        ToolCall call = toolStart.getToolCall();
        assertEquals("call_1", call.getId());
        assertEquals("get_weather", call.getName());
        assertEquals("所有代码修改完成。更新任务进度", joinText(acc), "重复的文本不得再次交付");
    }

    @Test
    public void duplicatedSnapshotFrameWithRefusal_keepsFrame() {
        ChatStreamContext ctx = newCtx(true);
        ChatAccumulator acc = ctx.getAccumulator();

        dialect.parseResponseJson(ctx, contentChunk("所有代码修改完成。"));
        dialect.parseResponseJson(ctx, contentChunk("所有代码修改完成。更新任务进度"));

        // 正文是重复快照，但同帧带官方独有的 refusal：整帧不能丢，拒答要能交付
        dialect.parseResponseJson(ctx, "{\"object\":\"chat.completion.chunk\",\"model\":\"gpt-4o\",\"choices\":[{\"index\":0,"
                + "\"delta\":{\"content\":\"所有代码修改完成。更新任务进度\",\"refusal\":\"后续内容无法提供\"},"
                + "\"finish_reason\":null}]}");

        assertNotNull(firstOf(ChatEventType.REFUSAL_DELTA), "拒答事件不得被快照判定吞掉");
        assertEquals("后续内容无法提供", firstOf(ChatEventType.REFUSAL_DELTA).getText());
        assertEquals("所有代码修改完成。更新任务进度后续内容无法提供", joinText(acc),
                "正文只交付一次，拒答事件同时进入文本聚合");
    }

    @Test
    public void duplicatedSnapshotFrameWithNullToolCalls_isDropped() {
        ChatStreamContext ctx = newCtx(true);
        ChatAccumulator acc = ctx.getAccumulator();

        dialect.parseResponseJson(ctx, contentChunk("所有代码修改完成。"));
        dialect.parseResponseJson(ctx, contentChunk("所有代码修改完成。更新任务进度"));
        long before = countOf(ChatEventType.TEXT_DELTA);

        // tool_calls 显式为 null（官方帧常见写法）等于无工具调用：重复快照帧仍应整帧丢弃
        dialect.parseResponseJson(ctx, "{\"object\":\"chat.completion.chunk\",\"model\":\"gpt-4o\",\"choices\":[{\"index\":0,"
                + "\"delta\":{\"content\":\"所有代码修改完成。更新任务进度\",\"tool_calls\":null},\"finish_reason\":null}]}");

        assertEquals(before, countOf(ChatEventType.TEXT_DELTA), "空 tool_calls 不能阻止重复快照帧的丢弃");
        assertEquals("所有代码修改完成。更新任务进度", joinText(acc));
    }

    @Test
    public void roleOnlyDelta_producesNoContent() {
        ChatStreamContext ctx = newCtx(true);

        dialect.parseResponseJson(ctx, "{\"object\":\"chat.completion.chunk\",\"model\":\"m\","
                + "\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\"},\"finish_reason\":null}]}");

        assertEquals("", ctx.getAccumulator().getAggregationText(), "role 帧无文本可交付");
        assertTrue(events.isEmpty());
    }

    @Test
    public void chunkWithoutDelta_finishesWithPlaceholder() {
        ChatStreamContext ctx = newCtx(true);
        ChatAccumulator acc = ctx.getAccumulator();

        // 只有 finish_reason 的收尾帧（无 delta 字段）
        dialect.parseResponseJson(ctx, "{\"object\":\"chat.completion.chunk\",\"model\":\"m\","
                + "\"choices\":[{\"index\":0,\"finish_reason\":\"stop\"}]}");

        assertTrue(acc.isFinished());
        assertEquals("stop", acc.lastFinishReason);
        assertNull(acc.snapshotTerminal().getMessage(), "完成时无内容不应伪造占位消息");
        assertTrue(events.isEmpty(), "无 delta / message 时不应发拒答事件");
    }

    @Test
    public void deltaNull_isTolerated() {
        ChatStreamContext ctx = newCtx(true);

        // 非规范形态：delta 显式为 null（个别端点的收尾帧）。不得抛异常，也不能凭空造内容
        assertDoesNotThrow(() -> dialect.parseResponseJson(ctx,
                "{\"object\":\"chat.completion.chunk\",\"model\":\"m\","
                        + "\"choices\":[{\"index\":0,\"delta\":null,\"finish_reason\":null}]}"));
        assertNull(ctx.getAccumulator().getError());
        assertEquals("", ctx.getAccumulator().getAggregationText());
    }

    // ==================== 拒答事件 ====================

    @Test
    public void refusalWithoutObjectField_usesChunkRawType() {
        ChatStreamContext ctx = newCtx(true);

        dialect.parseResponseJson(ctx, "{\"model\":\"m\",\"choices\":[{\"index\":0,"
                + "\"delta\":{\"refusal\":\"我不能协助该请求\"},\"finish_reason\":null}]}");

        ChatEvent e = firstOf(ChatEventType.REFUSAL_DELTA);
        assertNotNull(e);
        assertEquals("我不能协助该请求", e.getText());
        assertEquals("chat.completion.chunk", e.getRawType(), "缺省 object 时按流式帧类型兜底");
    }

    @Test
    public void refusalEmptyString_noEvent() {
        ChatStreamContext ctx = newCtx(true);

        dialect.parseResponseJson(ctx, "{\"object\":\"chat.completion.chunk\",\"model\":\"m\","
                + "\"choices\":[{\"index\":0,\"delta\":{\"refusal\":\"\"},\"finish_reason\":null}]}");

        assertNull(firstOf(ChatEventType.REFUSAL_DELTA), "空 refusal 不应发事件");
    }

    @Test
    public void nonStreamRefusal_usesMessageNodeAndObjectRawType() {
        ChatStreamContext ctx = newCtx(false);

        dialect.parseResponseJson(ctx, "{\"object\":\"chat.completion\",\"model\":\"m\","
                + "\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":null,"
                + "\"refusal\":\"不予回答\"},\"finish_reason\":\"stop\"}]}");

        ChatEvent e = firstOf(ChatEventType.REFUSAL_DELTA);
        assertNotNull(e, "非流式的 message.refusal 同样要发事件");
        assertEquals("不予回答", e.getText());
        assertEquals("chat.completion", e.getRawType());
    }

    @Test
    public void refusalWithContent_doesNotShiftTextBaseline() {
        ChatStreamContext ctx = newCtx(true);
        ChatAccumulator acc = ctx.getAccumulator();

        // 同帧既有正文又有 refusal 时，核心不会把 refusal 投影进文本，故不得记入正文累积基准
        dialect.parseResponseJson(ctx, "{\"object\":\"chat.completion.chunk\",\"model\":\"m\","
                + "\"choices\":[{\"index\":0,\"delta\":{\"content\":\"正常输出的一段话\",\"refusal\":\"局部拒答\"},"
                + "\"finish_reason\":null}]}");
        dialect.parseResponseJson(ctx, contentChunk("正常输出的一段话继续"));

        assertEquals("正常输出的一段话继续", joinText(acc), "基准正确时第二帧应被识别为快照并只交付新增");
        assertNotNull(firstOf(ChatEventType.REFUSAL_DELTA));
    }

    @Test
    public void errorInsideChoicesFrame_emitsErrorWithRaw() {
        ChatStreamContext ctx = newCtx(true);

        // 顶层 error 与 choices 同帧（个别兼容端点）：错误优先，事件带原始节点
        dialect.parseResponseJson(ctx, "{\"model\":\"m\",\"error\":{\"message\":\"bad request\"},"
                + "\"choices\":[{\"index\":0,\"delta\":{\"content\":\"x\"},\"finish_reason\":null}]}");

        assertEquals("bad request", ctx.getAccumulator().getError().getMessage());
        ChatEvent e = firstOf(ChatEventType.ERROR);
        assertNotNull(e);
        assertNotNull(e.getRaw());
        assertEquals("", ctx.getAccumulator().getAggregationText(), "错误帧应中止内容解析");
    }

    // ==================== 请求构建 ====================

    @Test
    public void streamRequest_injectsIncludeUsage() {
        ChatConfig config = new ChatConfig();
        config.setModel("gpt-4o");

        ONode root = dialect.buildRequestJson(config, ChatOptions.of(),
                Collections.<ChatMessage>singletonList(ChatMessage.ofUser("hi")), true);

        assertTrue(root.get("stream_options").get("include_usage").getBoolean(),
                "流式不带 include_usage 时 usage 恒为 null: " + root.toJson());
    }

    @Test
    public void streamRequest_keepsUserStreamOptions() {
        ChatConfig config = new ChatConfig();
        config.setModel("gpt-4o");
        ChatOptions options = ChatOptions.of()
                .optionSet("stream_options", Collections.singletonMap("include_usage", false));

        ONode root = dialect.buildRequestJson(config, options,
                Collections.<ChatMessage>singletonList(ChatMessage.ofUser("hi")), true);

        assertFalse(root.get("stream_options").get("include_usage").getBoolean(),
                "用户显式配置不应被覆盖: " + root.toJson());
    }

    @Test
    public void nonStreamRequest_noStreamOptions() {
        ChatConfig config = new ChatConfig();
        config.setModel("gpt-4o");

        ONode root = dialect.buildRequestJson(config, ChatOptions.of(),
                Arrays.asList(ChatMessage.ofSystem("sys"), ChatMessage.ofUser("hi")), false);

        assertFalse(root.hasKey("stream_options"), "非流式不需要 stream_options: " + root.toJson());
    }

    @Test
    public void systemMessageRole_autoAdaptsKnownOpenAiModels() {
        String[] developerModels = {
                "o1", "o1-2024-12-17", "o3-mini", "o4-mini",
                "gpt-5", "gpt5.6", "us.openai.gpt-5.6-sol",
                "gpt-6", "gpt6.1", "us.openai.gpt-6.1-pro"
        };
        for (String model : developerModels) {
            assertEquals("developer", buildInstructionRole(model, ChatOptions.of()), model);
        }

        String[] systemModels = {
                "gpt-4o", "gpt-4.1", "o1-preview", "o1-mini",
                "vendor-model", "not-o3-model", "not-gpt-6-model", "gpt-60"
        };
        for (String model : systemModels) {
            assertEquals("system", buildInstructionRole(model, ChatOptions.of()), model);
        }
    }

    @Test
    public void systemMessageRole_allowsExplicitOverrideWithoutProtocolLeak() {
        ChatOptions forceSystem = ChatOptions.of()
                .optionSet(OpenaiChatDialect.OPTION_INSTRUCTION_ROLE, "SYSTEM");
        ONode systemRoot = buildInstructionRequest("o3", forceSystem);
        assertEquals("system", systemRoot.get("messages").get(0).get("role").getString());
        assertFalse(systemRoot.hasKey(OpenaiChatDialect.OPTION_INSTRUCTION_ROLE));
        assertEquals("SYSTEM", forceSystem.option(OpenaiChatDialect.OPTION_INSTRUCTION_ROLE),
                "构建请求不应修改调用方 options");

        ChatOptions forceDeveloper = ChatOptions.of()
                .optionSet(OpenaiChatDialect.OPTION_INSTRUCTION_ROLE, "developer");
        assertEquals("developer", buildInstructionRole("vendor-model", forceDeveloper));
    }

    @Test
    public void systemMessageRole_rejectsInvalidPolicyAndKeepsCoreMessageSemantic() {
        ChatMessage systemMessage = ChatMessage.ofSystem("sys");
        ChatOptions invalid = ChatOptions.of()
                .optionSet(OpenaiChatDialect.OPTION_INSTRUCTION_ROLE, "automatic");
        ChatConfig config = new ChatConfig();
        config.setModel("o3");

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> dialect.buildRequestJson(config, invalid,
                        Arrays.asList(systemMessage, ChatMessage.ofUser("hi")), false));
        assertTrue(error.getMessage().contains(OpenaiChatDialect.OPTION_INSTRUCTION_ROLE));
        assertEquals("SYSTEM", systemMessage.getRole().name(),
                "自动转换只能发生在线协议 JSON，不能改变核心消息角色");
    }

    @Test
    public void assistantReasoningFieldIsSelectedByTargetConfig() {
        AssistantMessage history = (AssistantMessage) ChatMessage.fromJson(
                "{\"role\":\"assistant\",\"text\":\"answer\",\"thinking\":\"thought\"," +
                        "\"reasoningFieldName\":\"role\"}");

        ChatConfig deepseek = new ChatConfig();
        deepseek.setModel("deepseek-reasoner");
        ONode deepseekMessage = dialect.buildChatMessageNode(deepseek, history);
        assertEquals("assistant", deepseekMessage.get("role").getString());
        assertEquals("thought", deepseekMessage.get("reasoning_content").getString());
        assertFalse(deepseekMessage.hasKey("reasoning"));

        ChatConfig openrouter = new ChatConfig();
        openrouter.setModel("vendor/reasoner");
        openrouter.setProvider("openrouter");
        ONode openrouterMessage = dialect.buildChatMessageNode(openrouter, history);
        assertEquals("thought", openrouterMessage.get("reasoning").getString());
        assertFalse(openrouterMessage.hasKey("reasoning_content"));

        ChatConfig openai = new ChatConfig();
        openai.setModel("gpt-4o");
        ONode openaiMessage = dialect.buildChatMessageNode(openai, history);
        assertFalse(openaiMessage.hasKey("reasoning"));
        assertFalse(openaiMessage.hasKey("reasoning_content"));
    }

    @Test
    public void assistantTypedToolCallsAreRebuiltWithoutLegacyRaw() {
        ToolCall call = new ToolCall("0", "call_typed", "search", null,
                Collections.<String, Object>singletonMap("q", "solon"));
        AssistantMessage history = new AssistantMessage("", "",
                Collections.singletonList(call), null);

        ONode message = dialect.buildChatMessageNode(new ChatConfig(), history);
        ONode function = message.get("tool_calls").get(0).get("function");
        assertEquals("call_typed", message.get("tool_calls").get(0).get("id").getString());
        assertEquals("search", function.get("name").getString());
        assertEquals("solon", ONode.ofJson(function.get("arguments").getString()).get("q").getString());
        assertNull(history.getToolCallsRaw());
    }

    private String buildInstructionRole(String model, ChatOptions options) {
        return buildInstructionRequest(model, options)
                .get("messages").get(0).get("role").getString();
    }

    private ONode buildInstructionRequest(String model, ChatOptions options) {
        ChatConfig config = new ChatConfig();
        config.setModel(model);
        return dialect.buildRequestJson(config, options,
                Arrays.asList(ChatMessage.ofSystem("sys"), ChatMessage.ofUser("hi")), false);
    }

    @Test
    public void singletonAndCapabilityFlags() {
        assertSame(dialect, OpenaiChatDialect.getInstance());
        assertTrue(dialect.isDefault(), "OpenAI chat/completions 是默认方言");
        assertFalse(dialect.matched(new ChatConfig()), "默认方言不参与 matched 竞争");
    }
}
