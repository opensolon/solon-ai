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
import org.noear.solon.ai.AiUsage;
import org.noear.solon.ai.chat.*;
import org.noear.solon.ai.chat.event.*;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.session.InMemoryChatSession;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Anthropic 方言与官方 SDK 协议模型（com.anthropic.models.messages）的对齐补漏
 *
 * <p>覆盖此前存在的缺口：服务端工具结果文本恒 null、thinking 块被挂 cache_control、
 * thinking_tokens 未落地、stop_reason 后三值语义不可见、未建模内容块静默丢弃、
 * 文档类引用取不到文本、OpenAI 风格选项原样透传。</p>
 *
 * @author noear
 */
public class AnthropicProtocolAlignTest {
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

    private ChatEvent firstOf(ChatEventType type) {
        for (ChatEvent e : events) {
            if (e.getType() == type) {
                return e;
            }
        }
        return null;
    }

    /// ///////////////// 服务端工具结果：真实结构下的文本提取

    /**
     * web_search_tool_result 的 content 是 WebSearchResultBlock[]（title/url/page_age/encrypted_content），
     * 协议里根本没有 text 字段——旧实现按 text 取值，恒返回 null
     */
    @Test
    public void webSearchResultTextFromTitleAndUrl() {
        ChatStreamContext ctx = newCtx(true);

        parser.parseStreamResponse(ctx, "{\"type\":\"content_block_start\",\"index\":1,"
                + "\"content_block\":{\"type\":\"web_search_tool_result\",\"tool_use_id\":\"srvtoolu_1\","
                + "\"content\":[{\"type\":\"web_search_result\",\"title\":\"Solon\",\"url\":\"https://solon.noear.org\"},"
                + "{\"type\":\"web_search_result\",\"title\":\"Solon AI\",\"url\":\"https://solon.noear.org/ai\"}]}}");

        ChatEvent e = firstOf(ChatEventType.SERVER_TOOL_RESULT);
        assertNotNull(e);
        assertEquals("Solon - https://solon.noear.org\nSolon AI - https://solon.noear.org/ai", e.getText());
    }

    /**
     * web_fetch_tool_result 的 content 是单个 WebFetchBlock 对象（非数组），旧实现整支跳过
     */
    @Test
    public void webFetchResultTextFromObjectContent() {
        ChatStreamContext ctx = newCtx(true);

        parser.parseStreamResponse(ctx, "{\"type\":\"content_block_start\",\"index\":0,"
                + "\"content_block\":{\"type\":\"web_fetch_tool_result\",\"tool_use_id\":\"srvtoolu_2\","
                + "\"content\":{\"type\":\"web_fetch_result\",\"url\":\"https://a.com/p\","
                + "\"content\":{\"type\":\"document\",\"title\":\"Doc A\"}}}}");

        ChatEvent e = firstOf(ChatEventType.SERVER_TOOL_RESULT);
        assertNotNull(e);
        assertEquals("Doc A - https://a.com/p", e.getText());
    }

    /**
     * code_execution_tool_result：stdout/stderr，而非 text
     */
    @Test
    public void codeExecutionResultTextFromStdio() {
        ChatStreamContext ctx = newCtx(true);

        parser.parseStreamResponse(ctx, "{\"type\":\"content_block_start\",\"index\":0,"
                + "\"content_block\":{\"type\":\"code_execution_tool_result\",\"tool_use_id\":\"srvtoolu_3\","
                + "\"content\":{\"type\":\"code_execution_result\",\"stdout\":\"42\",\"stderr\":\"warn\","
                + "\"return_code\":0,\"content\":[]}}}");

        ChatEvent e = firstOf(ChatEventType.SERVER_TOOL_RESULT);
        assertNotNull(e);
        assertEquals("42\nwarn", e.getText());
    }

    /**
     * 错误形态（content 为 *_tool_result_error）：旧实现既不转文本也不转错误，订阅方完全看不到失败
     */
    @Test
    public void toolResultErrorBecomesText() {
        ChatStreamContext ctx = newCtx(true);

        parser.parseStreamResponse(ctx, "{\"type\":\"content_block_start\",\"index\":0,"
                + "\"content_block\":{\"type\":\"web_search_tool_result\",\"tool_use_id\":\"srvtoolu_4\","
                + "\"content\":{\"type\":\"web_search_tool_result_error\",\"error_code\":\"max_uses_exceeded\"}}}");

        ChatEvent e = firstOf(ChatEventType.SERVER_TOOL_RESULT);
        assertNotNull(e);
        assertEquals("[max_uses_exceeded]", e.getText());
    }

    /**
     * tool_search_tool_result：tool_references[].tool_name
     */
    @Test
    public void toolSearchResultTextFromReferences() {
        ChatStreamContext ctx = newCtx(true);

        parser.parseStreamResponse(ctx, "{\"type\":\"content_block_start\",\"index\":0,"
                + "\"content_block\":{\"type\":\"tool_search_tool_result\",\"tool_use_id\":\"srvtoolu_5\","
                + "\"content\":{\"type\":\"tool_search_tool_search_result\","
                + "\"tool_references\":[{\"type\":\"tool_reference\",\"tool_name\":\"read\"},"
                + "{\"type\":\"tool_reference\",\"tool_name\":\"write\"}]}}}");

        ChatEvent e = firstOf(ChatEventType.SERVER_TOOL_RESULT);
        assertNotNull(e);
        assertEquals("read, write", e.getText());
    }

    /// ///////////////// 内容块覆盖面

    /**
     * container_upload（代码执行产出文件，仅 file_id）：旧实现不匹配任何分支，静默丢弃
     */
    @Test
    public void containerUploadBecomesServerToolResult() {
        ChatStreamContext ctx = newCtx(true);

        parser.parseStreamResponse(ctx, "{\"type\":\"content_block_start\",\"index\":2,"
                + "\"content_block\":{\"type\":\"container_upload\",\"file_id\":\"file_abc\"}}");

        ChatEvent e = firstOf(ChatEventType.SERVER_TOOL_RESULT);
        assertNotNull(e, "container_upload should emit SERVER_TOOL_RESULT");
        assertEquals("container_upload", e.getSubType());
        assertEquals("file_abc", e.getItemId());
        assertEquals("file_abc", e.getText());
    }

    /**
     * 未建模内容块（Beta 侧 compaction / fallback 等）：与顶层未建模事件对称地以 RAW 透出
     */
    @Test
    public void unknownContentBlockBecomesRaw() {
        ChatStreamContext ctx = newCtx(true);

        parser.parseStreamResponse(ctx, "{\"type\":\"content_block_start\",\"index\":3,"
                + "\"content_block\":{\"type\":\"compaction\",\"content\":\"x\"}}");

        ChatEvent e = firstOf(ChatEventType.RAW);
        assertNotNull(e, "unknown content block should emit RAW");
        assertEquals("compaction", e.getSubType());
        assertEquals(3, e.getIndex());
    }

    /**
     * server_tool_use / mcp_tool_use 同为服务端执行的工具调用：必须登记 StreamToolState，
     * 否则随后的 input_json_delta 因取不到状态被静默丢弃（旧实现把 mcp_tool_use 归到 RAW、不建 state，
     * 而同族的 mcp_tool_result 反因 _tool_result 后缀被正常归一，半边通半边断）
     */
    @Test
    public void mcpToolUseIsRegisteredAsServerTool() {
        ChatStreamContext ctx = newCtx(true);

        parser.parseStreamResponse(ctx, "{\"type\":\"content_block_start\",\"index\":2,"
                + "\"content_block\":{\"type\":\"mcp_tool_use\",\"id\":\"mcp_1\",\"name\":\"query\"}}");
        parser.parseStreamResponse(ctx, "{\"type\":\"content_block_delta\",\"index\":2,"
                + "\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"{\\\"q\\\":1}\"}}");

        ChatEvent start = firstOf(ChatEventType.SERVER_TOOL_START);
        assertNotNull(start, "mcp_tool_use should emit SERVER_TOOL_START");
        assertEquals("query", start.getSubType());
        assertEquals("mcp_1", start.getItemId());

        ChatEvent args = firstOf(ChatEventType.SERVER_TOOL_ARGS_DELTA);
        assertNotNull(args, "服务端工具的参数分片不应被丢弃");
        assertEquals("mcp_1", args.getToolCallId());
        assertEquals("{\"q\":1}", args.getText());

        //服务端工具不是本地 function call，不能被拼进工具调用历史
        parser.parseStreamResponse(ctx, "{\"type\":\"content_block_stop\",\"index\":2}");
        assertFalse(ctx.getAccumulator().hasToolCallBuilders());
    }

    /**
     * 未建模增量：旧实现的 delta 分支是无 else 的 if/else-if 链，
     * 整帧静默丢弃且 hasContent 不置位，可能被上层判为不可识别响应。
     * GA 的 RawContentBlockDelta 今后变长时，此兜底保证不静默丢帧
     */
    @Test
    public void unknownContentBlockDeltaBecomesRaw() {
        ChatStreamContext ctx = newCtx(true);

        boolean consumed = parser.parseStreamResponse(ctx, "{\"type\":\"content_block_delta\",\"index\":1,"
                + "\"delta\":{\"type\":\"some_future_delta\",\"content\":\"c\"}}");

        ChatEvent e = firstOf(ChatEventType.RAW);
        assertNotNull(e, "unknown delta should emit RAW");
        assertEquals("some_future_delta", e.getSubType());
        assertEquals(1, e.getIndex());
        assertTrue(consumed, "RAW 是已消费的合法模型帧，不能让调用方误判为不可识别响应");
    }

    /**
     * 文档类引用（char_location）无 url，只有 cited_text / document_title——旧实现固定取 url，恒 null
     */
    @Test
    public void documentCitationFallsBackToCitedText() {
        ChatStreamContext ctx = newCtx(true);

        parser.parseStreamResponse(ctx, "{\"type\":\"content_block_delta\",\"index\":0,"
                + "\"delta\":{\"type\":\"citations_delta\",\"citation\":{\"type\":\"char_location\","
                + "\"cited_text\":\"\u88ab\u5f15\u7528\u7684\u539f\u6587\",\"document_title\":\"\u624b\u518c\","
                + "\"document_index\":0,\"start_char_index\":1,\"end_char_index\":9}}}");

        ChatEvent e = firstOf(ChatEventType.CITATION);
        assertNotNull(e);
        assertEquals("char_location", e.getSubType());
        assertEquals("\u88ab\u5f15\u7528\u7684\u539f\u6587", e.getText());
    }

    /// ///////////////// usage

    /**
     * output_tokens_details.thinking_tokens → AiUsage.thinkTokens()（此前硬编码 0）
     */
    @Test
    public void thinkingTokensLandInUsage() {
        ChatStreamContext ctx = newCtx(true);

        parser.parseStreamResponse(ctx, "{\"type\":\"message_start\",\"message\":{\"id\":\"msg_1\","
                + "\"model\":\"claude-sonnet-4-5\",\"usage\":{\"input_tokens\":10,\"output_tokens\":1,"
                + "\"cache_creation_input_tokens\":5,\"cache_read_input_tokens\":20,"
                + "\"cache_creation\":{\"ephemeral_5m_input_tokens\":5,\"ephemeral_1h_input_tokens\":0}}}}");
        parser.parseStreamResponse(ctx, "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"},"
                + "\"usage\":{\"output_tokens\":30,\"output_tokens_details\":{\"thinking_tokens\":12}}}");

        AiUsageAssert.assertUsage(ctx.getAccumulator().getUsage());
    }

    /**
     * 5m / 1h 分账明细仍保留在 source 中（合并 message_delta 时不能被冲掉）
     */
    @Test
    public void cacheCreationDetailsSurviveMerge() {
        ChatStreamContext ctx = newCtx(true);

        parser.parseStreamResponse(ctx, "{\"type\":\"message_start\",\"message\":{\"id\":\"msg_2\","
                + "\"model\":\"claude-sonnet-4-5\",\"usage\":{\"input_tokens\":10,\"output_tokens\":1,"
                + "\"cache_creation\":{\"ephemeral_5m_input_tokens\":7,\"ephemeral_1h_input_tokens\":3}}}}");
        parser.parseStreamResponse(ctx, "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"},"
                + "\"usage\":{\"output_tokens\":9}}");

        ONode source = ctx.getAccumulator().getUsage().getSource();
        assertEquals(7, source.get("cache_creation").get("ephemeral_5m_input_tokens").getInt());
        assertEquals(3, source.get("cache_creation").get("ephemeral_1h_input_tokens").getInt());
    }

    /// ///////////////// stop_reason 语义

    /**
     * refusal：旧实现只把原始串塞进 lastFinishReason，拒答对订阅方不可见
     */
    @Test
    public void refusalBecomesContentFilter() {
        ChatStreamContext ctx = newCtx(true);

        parser.parseStreamResponse(ctx, "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"refusal\","
                + "\"stop_details\":{\"type\":\"refusal\",\"category\":\"cyber\",\"explanation\":\"policy\"}}}");

        ChatEvent e = firstOf(ChatEventType.CONTENT_FILTER);
        assertNotNull(e, "refusal should emit CONTENT_FILTER");
        assertEquals("cyber", e.getSubType());
        assertEquals("policy", e.getText());
        assertSame(ChatEventGroup.SAFETY, e.getGroup());
        //原始值仍透传，不做归一化以免掩盖具体成因
        assertEquals("refusal", ctx.getAccumulator().lastFinishReason);
    }

    /**
     * pause_turn（服务端工具轮次暂停，需续跑）：以 STATUS 透出。
     * 刻意不用 ABORT——它在归一化器里会提前关闭未闭合块，与随后的终态收口帧抢块边界
     */
    @Test
    public void pauseTurnBecomesStatus() {
        ChatStreamContext ctx = newCtx(true);

        parser.parseStreamResponse(ctx, "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"pause_turn\"}}");

        ChatEvent e = firstOf(ChatEventType.STATUS);
        assertNotNull(e, "pause_turn should emit STATUS");
        assertEquals("pause_turn", e.getSubType());
        assertNull(firstOf(ChatEventType.ABORT));
    }

    /**
     * stop_sequence：命中的是哪条自定义停止序列，旧实现连这个字段都不读
     */
    @Test
    public void stopSequenceIsExposed() {
        ChatStreamContext ctx = newCtx(true);

        parser.parseStreamResponse(ctx, "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"stop_sequence\","
                + "\"stop_sequence\":\"</done>\"}}");

        ChatEvent e = firstOf(ChatEventType.STATUS);
        assertNotNull(e);
        assertEquals("stop_sequence", e.getSubType());
        assertEquals("</done>", e.getText());
    }

    /**
     * 容器（代码执行沙盒）：message_start 给出，message_delta 可刷新；旧实现整块丢弃
     */
    @Test
    public void containerIsCaptured() {
        ChatStreamContext ctx = newCtx(true);

        parser.parseStreamResponse(ctx, "{\"type\":\"message_start\",\"message\":{\"id\":\"msg_3\","
                + "\"model\":\"claude-sonnet-4-5\","
                + "\"container\":{\"id\":\"container_1\",\"expires_at\":\"2026-01-01T00:00:00Z\"}}}");

        ONode container = AnthropicResponseParser.container(ctx.getAccumulator());
        assertNotNull(container, "container should be captured");
        assertEquals("container_1", container.get("id").getString());
    }

    /**
     * 非流式与流式对称：refusal / container / 未建模块
     */
    @Test
    public void nonStreamStopReasonAndContainer() {
        ChatStreamContext ctx = newCtx(false);

        parser.parseNonStreamResponse(ctx, "{\"model\":\"claude-sonnet-4-5\",\"stop_reason\":\"refusal\","
                + "\"stop_details\":{\"type\":\"refusal\",\"category\":\"cyber\",\"explanation\":\"no\"},"
                + "\"container\":{\"id\":\"container_2\"},"
                + "\"content\":[{\"type\":\"container_upload\",\"file_id\":\"file_ns\"},"
                + "{\"type\":\"compaction\",\"foo\":\"bar\"}]}");

        assertNotNull(firstOf(ChatEventType.CONTENT_FILTER), "non-stream refusal should emit CONTENT_FILTER");
        assertEquals("container_2",
                AnthropicResponseParser.container(ctx.getAccumulator()).get("id").getString());

        ChatEvent upload = firstOf(ChatEventType.SERVER_TOOL_RESULT);
        assertNotNull(upload);
        assertEquals("file_ns", upload.getText());

        assertNotNull(firstOf(ChatEventType.RAW), "unknown non-stream block should emit RAW");
    }

    /// ///////////////// 请求侧：缓存断点

    /**
     * thinking / redacted_thinking 在协议上没有 cache_control 字段，挂上去整条请求 400。
     * 触发路径：只有 thinking（有 signature、无正文、无 tool_calls）的 assistant 消息落在滚动窗口内
     */
    @Test
    public void cacheBreakpointNeverLandsOnThinkingBlock() {
        Map<String, Object> contentRaw = new LinkedHashMap<>();
        contentRaw.put("thinkingSignature", "sig_x");

        AssistantMessage thinkingOnly = new AssistantMessage("", "\u60f3\u4e00\u4e0b",
                false, contentRaw, null, null, null, null);

        List<ChatMessage> messages = Arrays.asList(
                ChatMessage.ofSystem("sys"),
                ChatMessage.ofUser("hi"),
                thinkingOnly);

        ChatConfig config = new ChatConfig();
        config.setModel("claude-sonnet-4-5");
        ChatOptions options = ChatOptions.of().cacheControl(CacheControl.ofEphemeral());

        ONode root = requestBuilder.build(config, options, messages, false);
        ONode messagesNode = root.get("messages");

        for (ONode messageNode : messagesNode.getArray()) {
            ONode content = messageNode.get("content");
            if (content.isArray() == false) {
                continue;
            }
            for (ONode block : content.getArray()) {
                String type = block.get("type").getString();
                if ("thinking".equals(type) || "redacted_thinking".equals(type)) {
                    assertFalse(block.hasKey("cache_control"),
                            "thinking 块不能承载 cache_control，协议上没有该字段");
                }
            }
        }

        //断点没有白扔：跳过 thinking 消息后仍落在可承载的 user 文本块上
        ONode userContent = messagesNode.get(0).get("content");
        assertTrue(userContent.isArray());
        assertTrue(userContent.get(-1).hasKey("cache_control"));
    }

    /**
     * ttl 合法值写出，非法值拦掉（协议 CacheControlEphemeral.Ttl 仅 5m / 1h）
     */
    @Test
    public void cacheTtlIsValidated() {
        ChatConfig config = new ChatConfig();
        config.setModel("claude-sonnet-4-5");
        List<ChatMessage> messages = Arrays.asList(ChatMessage.ofSystem("sys"), ChatMessage.ofUser("hi"));

        ONode ok = requestBuilder.build(config,
                ChatOptions.of().cacheControl(CacheControl.ofEphemeral("1h")), messages, false);
        assertEquals("1h", ok.get("system").get(0).get("cache_control").get("ttl").getString());

        ONode bad = requestBuilder.build(config,
                ChatOptions.of().cacheControl(CacheControl.ofEphemeral("10m")), messages, false);
        assertFalse(bad.get("system").get(0).get("cache_control").hasKey("ttl"),
                "非法 ttl 不应透传");
    }

    /// ///////////////// 请求侧：选项归一

    /**
     * OpenAI 风格字段在 Anthropic 顶层不存在，透传即 400。
     * 统一 API 的 ModelOptionsAmend 对外暴露了这些 setter，任一被调用就会污染请求体
     */
    @Test
    public void openaiOnlyOptionsAreDropped() {
        ChatConfig config = new ChatConfig();
        config.setModel("claude-sonnet-4-5");
        List<ChatMessage> messages = Arrays.asList(ChatMessage.ofUser("hi"));

        ChatOptions options = ChatOptions.of()
                .optionSet("frequency_penalty", 0.5)
                .optionSet("presence_penalty", 0.5)
                .optionSet("response_format", "json_object")
                .optionSet("n", 2)
                .optionSet("seed", 42)
                .optionSet("temperature", 0.7);

        ONode root = requestBuilder.build(config, options, messages, false);

        assertFalse(root.hasKey("frequency_penalty"));
        assertFalse(root.hasKey("presence_penalty"));
        assertFalse(root.hasKey("response_format"));
        assertFalse(root.hasKey("n"));
        assertFalse(root.hasKey("seed"));
        //合法字段照常透传
        assertTrue(root.hasKey("temperature"));
    }

    /**
     * 命名差异归一：stop → stop_sequences、user → metadata.user_id、
     * max_completion_tokens → max_tokens
     */
    @Test
    public void optionNamesAreNormalized() {
        ChatConfig config = new ChatConfig();
        config.setModel("claude-sonnet-4-5");
        List<ChatMessage> messages = Arrays.asList(ChatMessage.ofUser("hi"));

        ChatOptions options = ChatOptions.of()
                .optionSet("stop", Arrays.asList("</done>", "END"))
                .optionSet("user", "u-1")
                .optionSet("max_completion_tokens", 4096);

        ONode root = requestBuilder.build(config, options, messages, false);

        assertFalse(root.hasKey("stop"));
        assertEquals("</done>", root.get("stop_sequences").get(0).getString());
        assertEquals("END", root.get("stop_sequences").get(1).getString());

        assertFalse(root.hasKey("user"));
        assertEquals("u-1", root.get("metadata").get("user_id").getString());

        assertFalse(root.hasKey("max_completion_tokens"));
        assertEquals(4096, root.get("max_tokens").getInt());
    }

    /**
     * 单值 stop 也要转成数组（协议 stop_sequences 是 List<String>）
     */
    @Test
    public void singleStopBecomesArray() {
        ChatConfig config = new ChatConfig();
        config.setModel("claude-sonnet-4-5");

        ONode root = requestBuilder.build(config,
                ChatOptions.of().optionSet("stop", "</done>"),
                Arrays.asList(ChatMessage.ofUser("hi")), false);

        assertTrue(root.get("stop_sequences").isArray());
        assertEquals("</done>", root.get("stop_sequences").get(0).getString());
    }

    /**
     * usage 断言辅助（放在内部类里，避免污染测试方法可读性）
     */
    static class AiUsageAssert {
        static void assertUsage(org.noear.solon.ai.AiUsage usage) {
            assertNotNull(usage);
            //input(10) + cacheCreation(5) + cacheRead(20) 归一为“全部输入 token”
            assertEquals(35L, usage.promptTokens());
            assertEquals(30L, usage.completionTokens());
            assertEquals(12L, usage.thinkTokens(), "thinking_tokens 必须落到 AiUsage");
            assertEquals(5L, usage.cacheCreationInputTokens());
            assertEquals(20L, usage.cacheReadInputTokens());
        }
    }

    /// ///////////////// 缓存写入的 TTL 明细

    /**
     * {@code usage.cache_creation} 的 5m / 1h 拆分必须落到 AiUsage 字段：
     * 1h 写入单价是 5m 的两倍，只有汇总值算不出真实缓存成本
     */
    @Test
    public void cacheCreationTtlBreakdownLandsInUsage() {
        ChatStreamContext ctx = newCtx(true);

        parser.parseStreamResponse(ctx, "{\"type\":\"message_start\",\"message\":{\"id\":\"msg_5\","
                + "\"model\":\"claude-sonnet-4-5\",\"usage\":{\"input_tokens\":10,\"output_tokens\":1,"
                + "\"cache_creation_input_tokens\":100,"
                + "\"cache_creation\":{\"ephemeral_5m_input_tokens\":40,\"ephemeral_1h_input_tokens\":60}}}}");

        AiUsage usage = ctx.getAccumulator().getUsage();
        assertEquals(100L, usage.cacheCreationInputTokens());
        assertEquals(40L, usage.cacheCreation5mInputTokens());
        assertEquals(60L, usage.cacheCreation1hInputTokens());
    }

    /**
     * 只给明细、不给汇总的形态：由明细求和补出汇总，否则缓存写入部分会从 promptTokens 里消失
     */
    @Test
    public void cacheCreationAggregateDerivedFromBreakdown() {
        ChatStreamContext ctx = newCtx(true);

        parser.parseStreamResponse(ctx, "{\"type\":\"message_start\",\"message\":{\"id\":\"msg_6\","
                + "\"model\":\"claude-sonnet-4-5\",\"usage\":{\"input_tokens\":10,\"output_tokens\":1,"
                + "\"cache_creation\":{\"ephemeral_5m_input_tokens\":7,\"ephemeral_1h_input_tokens\":3}}}}");

        AiUsage usage = ctx.getAccumulator().getUsage();
        assertEquals(10L, usage.cacheCreationInputTokens(), "汇总缺失时由 5m+1h 补出");
        assertEquals(20L, usage.promptTokens(), "input(10) + 缓存写入(5m 7 + 1h 3) 归一为全部输入 token");
    }

    /// ///////////////// stop_reason：截断类语义

    /**
     * {@code max_tokens} 与 {@code model_context_window_exceeded} 旧实现只落在 lastFinishReason 字符串里，
     * 订阅方无法与正常 end_turn 区分。用 STATUS 而不是 ERROR：服务端返回的是携带可用内容的合法消息
     */
    @Test
    public void truncationStopReasonsBecomeStatus() {
        for (String reason : new String[]{"max_tokens", "model_context_window_exceeded"}) {
            ChatStreamContext ctx = newCtx(true);

            parser.parseStreamResponse(ctx, "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\""
                    + reason + "\"}}");

            ChatEvent e = firstOf(ChatEventType.STATUS);
            assertNotNull(e, reason + " 应有事件通道");
            assertEquals(reason, e.getSubType());
            assertNull(firstOf(ChatEventType.ERROR), reason + " 不能抬为 ERROR（会弃掉已生成内容）");
            assertEquals(reason, ctx.getAccumulator().lastFinishReason);
        }
    }

    /**
     * 非流式的 stop 事件序必须与流式一致：内容块事件在前、stop 在后。
     * 旧实现在遍历 content 之前就发 stop，按事件序做状态机的订阅方会看到 call() 与 stream() 行为分叉
     */
    @Test
    public void nonStreamStopEventComesAfterContentEvents() {
        ChatStreamContext ctx = newCtx(false);

        parser.parseNonStreamResponse(ctx, "{\"id\":\"msg_7\",\"model\":\"claude-sonnet-4-5\","
                + "\"stop_reason\":\"max_tokens\",\"content\":["
                + "{\"type\":\"server_tool_use\",\"id\":\"srv_1\",\"name\":\"web_search\"}]}");

        int serverToolAt = -1;
        int statusAt = -1;
        for (int i = 0; i < events.size(); i++) {
            ChatEventType type = events.get(i).getType();
            if (type == ChatEventType.SERVER_TOOL_START && serverToolAt < 0) {
                serverToolAt = i;
            } else if (type == ChatEventType.STATUS && statusAt < 0) {
                statusAt = i;
            }
        }

        assertTrue(serverToolAt >= 0, "server_tool_use 应发 SERVER_TOOL_START");
        assertTrue(statusAt >= 0, "max_tokens 应发 STATUS");
        assertTrue(serverToolAt < statusAt, "内容块事件必须在 stop 事件之前（与流式同序）");
    }

    /// ///////////////// 请求侧：缓存机制冲突消解

    /**
     * 用户自带顶层 {@code cache_control}（协议合法：服务端自动给最后一个可缓存块加断点）时，
     * 方言不能再打自己的块级断点：两套叠加会超出每请求 4 个断点上限而整条 400
     */
    @Test
    public void nativeTopLevelCacheControlDisablesManualBreakpoints() {
        ChatConfig config = new ChatConfig();
        config.setModel("claude-sonnet-4-5");

        Map<String, Object> nativeCache = new LinkedHashMap<>();
        nativeCache.put("type", "ephemeral");

        ONode root = requestBuilder.build(config,
                ChatOptions.of()
                        .cacheControl(CacheControl.ofEphemeral())
                        .optionSet("cache_control", nativeCache),
                Arrays.asList(ChatMessage.ofSystem("sys"), ChatMessage.ofUser("hi")), false);

        //顶层字段本身是合法 GA 字段，继续透传
        assertEquals("ephemeral", root.get("cache_control").get("type").getString());
        //system 退回纯字符串形态（不再为了挂断点而转成 block 数组）
        assertTrue(root.get("system").isString(), "不应再为挂断点而把 system 转成数组");
        for (ONode messageNode : root.get("messages").getArray()) {
            ONode content = messageNode.get("content");
            if (content.isArray() == false) {
                continue;
            }
            for (ONode block : content.getArray()) {
                assertFalse(block.hasKey("cache_control"),
                        "顶层 cache_control 已接管断点，方言不得重复标记");
            }
        }
    }

    /// ///////////////// 旁路解析：redacted_thinking

    /**
     * {@code parseAssistantMessage} 旁路旧实现只认 thinking/text/image/tool_use，redacted_thinking 整块丢弃
     * → contentRaw 无 redactedThinkingBlocks → opaque 块无法原样回传，多轮 extended thinking 断链
     */
    @Test
    public void assistantMessageKeepsRedactedThinkingBlocks() {
        ChatStreamContext ctx = newCtx(false);

        ONode oMessage = ONode.ofJson("{\"role\":\"assistant\",\"content\":["
                + "{\"type\":\"redacted_thinking\",\"data\":\"opaque_1\"},"
                + "{\"type\":\"redacted_thinking\",\"data\":\"opaque_2\"},"
                + "{\"type\":\"tool_use\",\"id\":\"t1\",\"name\":\"f\",\"input\":{}}]}");

        List<AssistantMessage> list = AnthropicChatDialect.getInstance()
                .parseAssistantMessage(ctx.getAccumulator(), oMessage);

        AssistantMessage msg = list.get(list.size() - 1);
        Object raw = msg.getContentRaw();
        assertTrue(raw instanceof Map);
        Object blocks = ((Map<?, ?>) raw).get("redactedThinkingBlocks");
        assertTrue(blocks instanceof List);
        assertEquals(Arrays.asList("opaque_1", "opaque_2"), blocks,
                "opaque 块必须逐块保留，拼接会损坏 base64");
    }

    /// ///////////////// usage：按次计费维度与计价修饰量

    /**
     * {@code usage.server_tool_use} 是按「次」独立计费的维度（与 token 不同计价单位），
     * 旧实现整块不读；同时不得并入 promptTokens/totalTokens，否则等于把次数当 token 算
     */
    @Test
    public void serverToolRequestCountsLandInUsage() {
        ChatStreamContext ctx = newCtx(true);

        parser.parseStreamResponse(ctx, "{\"type\":\"message_start\",\"message\":{\"id\":\"msg_8\","
                + "\"model\":\"claude-sonnet-4-5\",\"usage\":{\"input_tokens\":10,\"output_tokens\":2,"
                + "\"server_tool_use\":{\"web_search_requests\":3,\"web_fetch_requests\":1},"
                + "\"service_tier\":\"priority\",\"inference_geo\":\"us-east\"}}}");

        AiUsage usage = ctx.getAccumulator().getUsage();
        assertEquals(3L, usage.webSearchRequests());
        assertEquals(1L, usage.webFetchRequests());
        assertEquals("priority", usage.serviceTier(), "各档位单价不同，缺该值算不出真实成本");
        assertEquals("us-east", usage.inferenceGeo());
        assertEquals(10L, usage.promptTokens(), "按次计费维度不得并入 token 统计");
        assertEquals(12L, usage.totalTokens());
    }

    /**
     * 只有服务端工具轮次、没有 token 产出的帧也必须产出 usage：
     * 旧的「无 token 就返回 null」判定会把已计费的搜索次数整帧丢掉
     */
    @Test
    public void serverToolOnlyUsageIsNotDropped() {
        ChatStreamContext ctx = newCtx(true);

        parser.parseStreamResponse(ctx, "{\"type\":\"message_start\",\"message\":{\"id\":\"msg_9\","
                + "\"model\":\"claude-sonnet-4-5\",\"usage\":{\"input_tokens\":0,\"output_tokens\":0,"
                + "\"server_tool_use\":{\"web_search_requests\":2}}}}");

        AiUsage usage = ctx.getAccumulator().getUsage();
        assertNotNull(usage, "已计费的搜索次数不能因为 0 token 而被丢弃");
        assertEquals(2L, usage.webSearchRequests());
    }

    /**
     * 步内合并：{@code server_tool_use} 在 message_start 与 message_delta 都出现且是累计快照，按 max（不能相加）；
     * {@code service_tier} / {@code inference_geo} 只在 message_start 给（MessageDeltaUsage 无此两字段），
     * 不能被 message_delta 解出的 null 覆盖掉
     */
    @Test
    public void serviceTierSurvivesMessageDeltaMerge() {
        ChatStreamContext ctx = newCtx(true);

        parser.parseStreamResponse(ctx, "{\"type\":\"message_start\",\"message\":{\"id\":\"msg_10\","
                + "\"model\":\"claude-sonnet-4-5\",\"usage\":{\"input_tokens\":10,\"output_tokens\":1,"
                + "\"server_tool_use\":{\"web_search_requests\":2},"
                + "\"service_tier\":\"batch\",\"inference_geo\":\"eu-west\"}}}");
        parser.parseStreamResponse(ctx, "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"},"
                + "\"usage\":{\"output_tokens\":50,\"server_tool_use\":{\"web_search_requests\":2}}}");

        AiUsage usage = ctx.getAccumulator().getUsage();
        assertEquals(2L, usage.webSearchRequests(), "累计快照按 max，相加会重复计次");
        assertEquals("batch", usage.serviceTier(), "message_delta 无此字段，不得覆盖为 null");
        assertEquals("eu-west", usage.inferenceGeo());
        assertEquals(50L, usage.completionTokens());
    }


    /// ///////////////// 请求侧：thinking.display

    /**
     * 协议 ThinkingConfigEnabled 带 {@code display}（summarized | omitted）。旧实现只在 adaptive 路径写，
     * 经典 type=enabled 路径整块不写，经典模型用户无法关掉思考摘要
     */
    @Test
    public void classicThinkingDisplayIsPassedThrough() {
        ChatConfig config = new ChatConfig();
        config.setModel("claude-sonnet-4-5");

        Map<String, Object> thinking = new LinkedHashMap<>();
        thinking.put("type", "enabled");
        thinking.put("budget_tokens", 2048);
        thinking.put("display", "omitted");

        ONode root = requestBuilder.build(config,
                ChatOptions.of().optionSet("thinking", thinking).optionSet("max_tokens", 8192),
                Arrays.asList(ChatMessage.ofUser("hi")), false);

        ONode thinkingNode = root.get("thinking");
        assertEquals("enabled", thinkingNode.get("type").getString());
        assertEquals(2048, thinkingNode.get("budget_tokens").getInt());
        assertEquals("omitted", thinkingNode.get("display").getString());
    }

    /**
     * 未显式指定时不写 display（不改变现有请求行为）
     */
    @Test
    public void classicThinkingWithoutDisplayStaysUnset() {
        ChatConfig config = new ChatConfig();
        config.setModel("claude-sonnet-4-5");

        ONode root = requestBuilder.build(config,
                ChatOptions.of().optionSet("thinking", 2048).optionSet("max_tokens", 8192),
                Arrays.asList(ChatMessage.ofUser("hi")), false);

        assertFalse(root.get("thinking").hasKey("display"));
    }
}
