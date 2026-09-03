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
     * 未建模内容块（Beta 侧 mcp_tool_use / compaction 等）：与顶层未建模事件对称地以 RAW 透出
     */
    @Test
    public void unknownContentBlockBecomesRaw() {
        ChatStreamContext ctx = newCtx(true);

        parser.parseStreamResponse(ctx, "{\"type\":\"content_block_start\",\"index\":3,"
                + "\"content_block\":{\"type\":\"mcp_tool_use\",\"id\":\"mcp_1\",\"name\":\"x\"}}");

        ChatEvent e = firstOf(ChatEventType.RAW);
        assertNotNull(e, "unknown content block should emit RAW");
        assertEquals("mcp_tool_use", e.getSubType());
        assertEquals(3, e.getIndex());
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
}
