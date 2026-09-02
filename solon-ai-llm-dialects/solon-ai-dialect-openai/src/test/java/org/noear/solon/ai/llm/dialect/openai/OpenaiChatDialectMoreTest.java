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
import org.noear.solon.ai.chat.session.InMemoryChatSession;
import org.noear.solon.ai.chat.tool.ToolCall;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

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
        StringBuilder buf = new StringBuilder();
        for (AssistantMessage item : acc.getContentItems()) {
            if (item.getTextRaw() != null) {
                buf.append(item.getTextRaw());
            }
        }
        return buf.toString();
    }

    // ==================== 流终止与非结构化错误 ====================

    @Test
    public void doneMarker_finishesWithPlaceholderItem() {
        ChatStreamContext ctx = newCtx(true);
        ChatAccumulator acc = ctx.getAccumulator();

        dialect.parseResponseJson(ctx, "[DONE]");

        assertTrue(acc.isFinished(), "[DONE] 必须结束本步");
        assertEquals(1, acc.getContentItems().size(), "未收到任何内容时应补一条空消息，避免上层拿到 null");
        assertEquals("", acc.lastItem().getText());

        // 已完成后再来一次 [DONE]（个别中转会重复下发）：不得再补内容项
        dialect.parseResponseJson(ctx, "[DONE]");
        assertEquals(1, acc.getContentItems().size());
        assertTrue(events.isEmpty(), "[DONE] 不产生方言事件");
    }

    @Test
    public void doneMarkerAfterContent_keepsExistingItems() {
        ChatStreamContext ctx = newCtx(true);
        ChatAccumulator acc = ctx.getAccumulator();

        dialect.parseResponseJson(ctx, contentChunk("你好"));
        dialect.parseResponseJson(ctx, "[DONE]");

        assertEquals(2, acc.getContentItems().size(), "[DONE] 补位帧在正文之后");
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
        assertFalse(acc.hasContentItems());
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
        assertFalse(ctx.getAccumulator().hasContentItems(), "错误帧不产生内容项");
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
        assertTrue(acc.hasContentItems());
        assertEquals("杭州今天晴", acc.lastItem().getText());
        assertEquals("先查天气", acc.lastItem().getThinking());
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
        assertEquals("ok", acc.lastItem().getText());
    }

    @Test
    public void nonStream_emptyChoices_placeholderItem() {
        ChatStreamContext ctx = newCtx(false);
        ChatAccumulator acc = ctx.getAccumulator();

        dialect.parseResponseJson(ctx, "{\"object\":\"chat.completion\",\"model\":\"m\",\"choices\":[]}");

        assertTrue(acc.isFinished());
        assertEquals(1, acc.getContentItems().size(), "空 choices 也要补位，保证 getMessage() 非 null");
        assertEquals("", acc.lastItem().getText());
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
        assertFalse(acc.hasContentItems(), "usage 帧不产生内容项");
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
        int before = acc.getContentItems().size();

        // 整帧都是重复快照，但带 finish_reason：内容要丢弃，完成流程不能丢
        dialect.parseResponseJson(ctx, "{\"object\":\"chat.completion.chunk\",\"model\":\"gpt-4o\",\"choices\":[{\"index\":0,"
                + "\"delta\":{\"content\":\"所有代码修改完成。更新任务进度\"},\"finish_reason\":\"stop\"}]}");

        assertEquals(before, acc.getContentItems().size(), "重复快照不得产生新内容项");
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

        AssistantMessage last = acc.lastItem();
        assertNotNull(last.getToolCalls(), "工具调用分片不得被快照判定丢弃");
        ToolCall call = last.getToolCalls().get(0);
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
                "正文只交付一次，拒答文本由核心投影补在后");
    }

    @Test
    public void duplicatedSnapshotFrameWithNullToolCalls_isDropped() {
        ChatStreamContext ctx = newCtx(true);
        ChatAccumulator acc = ctx.getAccumulator();

        dialect.parseResponseJson(ctx, contentChunk("所有代码修改完成。"));
        dialect.parseResponseJson(ctx, contentChunk("所有代码修改完成。更新任务进度"));
        int before = acc.getContentItems().size();

        // tool_calls 显式为 null（官方帧常见写法）等于无工具调用：重复快照帧仍应整帧丢弃
        dialect.parseResponseJson(ctx, "{\"object\":\"chat.completion.chunk\",\"model\":\"gpt-4o\",\"choices\":[{\"index\":0,"
                + "\"delta\":{\"content\":\"所有代码修改完成。更新任务进度\",\"tool_calls\":null},\"finish_reason\":null}]}");

        assertEquals(before, acc.getContentItems().size(), "空 tool_calls 不能阻止重复快照帧的丢弃");
        assertEquals("所有代码修改完成。更新任务进度", joinText(acc));
    }

    @Test
    public void roleOnlyDelta_producesNoContent() {
        ChatStreamContext ctx = newCtx(true);

        dialect.parseResponseJson(ctx, "{\"object\":\"chat.completion.chunk\",\"model\":\"m\","
                + "\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\"},\"finish_reason\":null}]}");

        assertFalse(ctx.getAccumulator().hasContentItems(), "role 帧无文本可交付");
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
        assertEquals(1, acc.getContentItems().size(), "完成时无内容应补位");
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
        assertFalse(ctx.getAccumulator().hasContentItems());
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
        assertFalse(ctx.getAccumulator().hasContentItems(), "错误帧应中止内容解析");
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
    public void singletonAndCapabilityFlags() {
        assertSame(dialect, OpenaiChatDialect.getInstance());
        assertTrue(dialect.isDefault(), "OpenAI chat/completions 是默认方言");
        assertFalse(dialect.matched(new ChatConfig()), "默认方言不参与 matched 竞争");
    }
}
