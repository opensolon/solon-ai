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
package org.noear.solon.ai.llm.dialect.ollama;

import org.junit.jupiter.api.Test;
import org.noear.snack4.ONode;
import org.noear.solon.ai.chat.ChatAccumulator;
import org.noear.solon.ai.chat.ChatConfig;
import org.noear.solon.ai.chat.ChatOptions;
import org.noear.solon.ai.chat.ChatRequest;
import org.noear.solon.ai.chat.content.AudioBlock;
import org.noear.solon.ai.chat.content.ContentBlock;
import org.noear.solon.ai.chat.content.ImageBlock;
import org.noear.solon.ai.chat.content.TextBlock;
import org.noear.solon.ai.chat.content.VideoBlock;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.event.ChatStreamContextDefault;
import org.noear.solon.ai.chat.session.InMemoryChatSession;
import org.noear.solon.ai.chat.tool.ToolCall;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Ollama 入站报文解析规约
 *
 * <p>覆盖 {@code parseAssistantMessage}（thinking→reasoning 归一、images/audios/videos 侧车解析与合并、
 * 只有 thinking/tool 消息时补一条带媒体的空文本消息）与 {@code parseToolCall}
 * （arguments 为对象 / JSON 字符串 / 截断分片三种形态）。</p>
 *
 * @author noear
 */
public class OllamaChatDialectParseTest {
    private final OllamaChatDialect dialect = OllamaChatDialect.getInstance();

    private ChatAccumulator accumulator(boolean stream) {
        ChatConfig config = new ChatConfig();
        config.setModel("qwen3:8b");
        ChatRequest req = new ChatRequest(config, dialect, ChatOptions.of(),
                InMemoryChatSession.builder().build(), ChatMessage.ofSystem("test"), null, stream);

        return new ChatAccumulator(req, stream);
    }

    private ImageBlock imageAt(AssistantMessage msg, int index) {
        ContentBlock block = msg.getBlocks().get(index);
        assertTrue(block instanceof ImageBlock, "expect ImageBlock at " + index + " but " + block);
        return (ImageBlock) block;
    }

    /// ////////////////////// thinking → reasoning

    /**
     * Ollama think 模式字段名为 thinking：归一到通用 reasoning 管线
     */
    @Test
    public void thinkingIsMappedToReasoning() {
        ChatAccumulator acc = accumulator(false);
        ONode oMessage = ONode.ofJson("{\"role\":\"assistant\",\"content\":\"\",\"thinking\":\"让我想想\"}");

        List<AssistantMessage> messages = dialect.parseAssistantMessage(acc, oMessage);

        assertEquals("让我想想", oMessage.get("reasoning").getString(), "thinking 应写入 reasoning 字段");
        assertEquals("reasoning", acc.reasoning_field_name);
        assertEquals(1, messages.size());
        assertEquals("让我想想", messages.get(0).getThinkingRaw());
        assertEquals("", messages.get(0).getTextRaw());
    }

    /**
     * 已有 reasoning 字段时不被 thinking 覆盖
     */
    @Test
    public void existingReasoningIsNotOverwritten() {
        ChatAccumulator acc = accumulator(false);
        ONode oMessage = ONode.ofJson(
                "{\"role\":\"assistant\",\"content\":\"\",\"reasoning\":\"原推理\",\"thinking\":\"新想法\"}");

        List<AssistantMessage> messages = dialect.parseAssistantMessage(acc, oMessage);

        assertEquals("原推理", oMessage.get("reasoning").getString());
        assertEquals("原推理", messages.get(0).getThinkingRaw());
    }

    /**
     * reasoning_content 协议优先，thinking 不参与映射
     */
    @Test
    public void reasoningContentTakesPriorityOverThinking() {
        ChatAccumulator acc = accumulator(false);
        ONode oMessage = ONode.ofJson(
                "{\"role\":\"assistant\",\"content\":\"\",\"reasoning_content\":\"原推理\",\"thinking\":\"新想法\"}");

        List<AssistantMessage> messages = dialect.parseAssistantMessage(acc, oMessage);

        assertFalse(oMessage.hasKey("reasoning"));
        assertEquals("reasoning_content", acc.reasoning_field_name);
        assertEquals("原推理", messages.get(0).getThinkingRaw());
    }

    /**
     * 空 thinking 不写 reasoning（避免产生空推理帧）
     */
    @Test
    public void emptyThinkingIsNotMapped() {
        ChatAccumulator acc = accumulator(true);
        ONode oMessage = ONode.ofJson("{\"role\":\"assistant\",\"content\":\"杭州今天晴\",\"thinking\":\"\"}");

        List<AssistantMessage> messages = dialect.parseAssistantMessage(acc, oMessage);

        assertFalse(oMessage.hasKey("reasoning"));
        assertEquals(1, messages.size());
        assertFalse(messages.get(0).isThinking());
        assertEquals("杭州今天晴", messages.get(0).getTextRaw());
    }

    /// ////////////////////// 侧车媒体

    /**
     * 无侧车字段：原样返回父类结果，累积器无媒体
     */
    @Test
    public void plainTextFrameHasNoMediaBlocks() {
        ChatAccumulator acc = accumulator(true);

        List<AssistantMessage> messages = dialect.parseAssistantMessage(acc,
                ONode.ofJson("{\"role\":\"assistant\",\"content\":\"杭州今天晴\"}"));

        assertEquals(1, messages.size());
        assertTrue(acc.getMediaBlocks().isEmpty());
        assertTrue(messages.get(0).getBlocks().isEmpty());
    }

    /**
     * images 侧车：http / https URL 与裸 base64 三种取值，合并进正文消息
     */
    @Test
    public void imageSidecarMergedIntoTextMessage() {
        ChatAccumulator acc = accumulator(false);

        List<AssistantMessage> messages = dialect.parseAssistantMessage(acc, ONode.ofJson(
                "{\"role\":\"assistant\",\"content\":\"看这个\","
                        + "\"images\":[\"https://example.com/1.png\",\"http://example.com/2.png\",\"QUJD\"]}"));

        assertEquals(1, messages.size());
        AssistantMessage msg = messages.get(0);
        assertEquals("看这个", msg.getTextRaw());
        assertEquals(4, msg.getBlocks().size());
        assertTrue(msg.getBlocks().get(0) instanceof TextBlock);
        assertEquals("https://example.com/1.png", imageAt(msg, 1).getUrl());
        assertNull(imageAt(msg, 1).getData());
        assertEquals("http://example.com/2.png", imageAt(msg, 2).getUrl());
        assertEquals("QUJD", imageAt(msg, 3).getData(), "裸 base64 应作为 data 解析");
        assertNull(imageAt(msg, 3).getUrl());

        assertEquals(3, acc.getMediaBlocks().size(), "侧车媒体需登记到累积器（终态聚合用）");
    }

    /**
     * audios/videos 侧车：data: URL 拆出 base64 与 mime；纯文本帧无正文时补一条空文本消息承载媒体
     */
    @Test
    public void audioAndVideoSidecarProduceExtraMediaMessage() {
        ChatAccumulator acc = accumulator(false);

        List<AssistantMessage> messages = dialect.parseAssistantMessage(acc, ONode.ofJson(
                "{\"role\":\"assistant\",\"content\":\"\","
                        + "\"audios\":[\"data:audio/wav;base64,QUJD\"],"
                        + "\"videos\":[\"https://example.com/v.mp4\"]}"));

        assertEquals(1, messages.size());
        AssistantMessage msg = messages.get(0);
        assertEquals("", msg.getTextRaw());
        assertFalse(msg.isThinking());
        assertEquals(2, msg.getBlocks().size());

        AudioBlock audio = (AudioBlock) msg.getBlocks().get(0);
        assertEquals("QUJD", audio.getData());
        assertEquals("audio/wav", audio.getMimeType());

        VideoBlock video = (VideoBlock) msg.getBlocks().get(1);
        assertEquals("https://example.com/v.mp4", video.getUrl());
    }

    /**
     * 侧车字段非数组（协议异常）：忽略，不影响正文
     */
    @Test
    public void nonArraySidecarIsIgnored() {
        ChatAccumulator acc = accumulator(false);

        List<AssistantMessage> messages = dialect.parseAssistantMessage(acc, ONode.ofJson(
                "{\"role\":\"assistant\",\"content\":\"杭州今天晴\",\"images\":\"not-an-array\"}"));

        assertEquals(1, messages.size());
        assertEquals("杭州今天晴", messages.get(0).getTextRaw());
        assertTrue(acc.getMediaBlocks().isEmpty());
    }

    /**
     * 侧车数组中的空串项：跳过
     */
    @Test
    public void emptySidecarItemsAreSkipped() {
        ChatAccumulator acc = accumulator(false);

        List<AssistantMessage> messages = dialect.parseAssistantMessage(acc, ONode.ofJson(
                "{\"role\":\"assistant\",\"content\":\"看这个\","
                        + "\"images\":[\"\",\"https://example.com/1.png\"]}"));

        assertEquals(1, acc.getMediaBlocks().size());
        assertEquals(2, messages.get(0).getBlocks().size());
    }

    /**
     * 只有工具调用消息时：媒体不并入 tool_calls 消息，另补一条空文本消息
     */
    @Test
    public void toolCallFrameGetsExtraMediaMessage() {
        ChatAccumulator acc = accumulator(true);

        List<AssistantMessage> messages = dialect.parseAssistantMessage(acc, ONode.ofJson(
                "{\"role\":\"assistant\",\"content\":\"\","
                        + "\"tool_calls\":[{\"function\":{\"name\":\"get_weather\",\"arguments\":{\"city\":\"杭州\"}}}],"
                        + "\"images\":[\"https://example.com/1.png\"]}"));

        assertEquals(2, messages.size());
        assertEquals("get_weather", messages.get(0).getToolCalls().get(0).getName());
        assertTrue(messages.get(0).getBlocks().isEmpty(), "tool_calls 消息不得挂媒体");

        AssistantMessage mediaMsg = messages.get(1);
        assertEquals("", mediaMsg.getTextRaw());
        assertNull(mediaMsg.getToolCalls());
        assertEquals(1, mediaMsg.getBlocks().size());
        assertEquals("https://example.com/1.png", imageAt(mediaMsg, 0).getUrl());
    }

    /**
     * 流式纯思考帧 + 媒体：思考帧不承载媒体，末尾补一条非思考的媒体消息
     */
    @Test
    public void thinkingStreamFrameGetsExtraMediaMessage() {
        ChatAccumulator acc = accumulator(true);

        List<AssistantMessage> messages = dialect.parseAssistantMessage(acc, ONode.ofJson(
                "{\"role\":\"assistant\",\"content\":\"\",\"thinking\":\"让我想想\","
                        + "\"images\":[\"https://example.com/1.png\"]}"));

        assertEquals(3, messages.size(), "开启信号帧 + 思考分片帧 + 媒体补位帧");
        assertTrue(messages.get(0).isThinking());
        assertTrue(messages.get(1).isThinking());
        assertTrue(messages.get(1).getBlocks().isEmpty());

        AssistantMessage mediaMsg = messages.get(2);
        assertFalse(mediaMsg.isThinking());
        assertEquals(1, mediaMsg.getBlocks().size());
    }

    /**
     * 非流式思考帧（思考与正文同体、正文为空）：媒体并入该消息，且不补空 TextBlock
     */
    @Test
    public void mediaMergedIntoMessageWithoutText() {
        ChatAccumulator acc = accumulator(false);

        List<AssistantMessage> messages = dialect.parseAssistantMessage(acc, ONode.ofJson(
                "{\"role\":\"assistant\",\"content\":\"\",\"thinking\":\"让我想想\","
                        + "\"images\":[\"https://example.com/1.png\"]}"));

        assertEquals(1, messages.size());
        AssistantMessage msg = messages.get(0);
        assertEquals("", msg.getTextRaw());
        assertEquals("让我想想", msg.getThinkingRaw());
        assertEquals("reasoning", msg.getReasoningFieldName());
        assertEquals(1, msg.getBlocks().size(), "空文本不应产生 TextBlock");
        assertEquals("https://example.com/1.png", imageAt(msg, 0).getUrl());
    }

    /**
     * 消息本身已有多模态块时：保留原非文本块，再追加侧车媒体
     */
    @Test
    public void mediaMergeKeepsExistingNonTextBlocks() {
        ChatAccumulator acc = accumulator(false);

        List<AssistantMessage> messages = dialect.parseAssistantMessage(acc, ONode.ofJson(
                "{\"role\":\"assistant\",\"content\":["
                        + "{\"type\":\"text\",\"text\":\"看图\"},"
                        + "{\"type\":\"image_url\",\"image_url\":{\"url\":\"https://example.com/1.png\"}}],"
                        + "\"images\":[\"https://example.com/2.png\"]}"));

        assertEquals(1, messages.size());
        AssistantMessage msg = messages.get(0);
        assertEquals("看图", msg.getTextRaw());
        assertEquals(3, msg.getBlocks().size());
        assertTrue(msg.getBlocks().get(0) instanceof TextBlock);
        assertEquals("https://example.com/1.png", imageAt(msg, 1).getUrl());
        assertEquals("https://example.com/2.png", imageAt(msg, 2).getUrl());
    }

    /// ////////////////////// 非对象帧

    /**
     * 非对象帧（数组 / 标量）：直接忽略，不污染累积器
     */
    @Test
    public void nonObjectFrameIsIgnored() {
        ChatAccumulator acc = accumulator(true);

        dialect.parseResponseJson(ChatStreamContextDefault.ofNoEmit(acc), "[1,2]");
        dialect.parseResponseJson(ChatStreamContextDefault.ofNoEmit(acc), "\"just-a-string\"");

        assertFalse(acc.hasContentItems());
        assertNull(acc.getError());
        assertFalse(acc.isFinished());
    }

    /**
     * 结束帧已有正文：不再补一条空文本消息（避免重复尾帧）
     */
    @Test
    public void doneFrameWithTextKeepsSingleContentItem() {
        ChatAccumulator acc = accumulator(true);

        dialect.parseResponseJson(ChatStreamContextDefault.ofNoEmit(acc),
                "{\"model\":\"qwen3:8b\",\"message\":{\"role\":\"assistant\",\"content\":\"完毕\"},"
                        + "\"done\":true,\"done_reason\":\"stop\",\"prompt_eval_count\":10,\"eval_count\":5}");

        assertTrue(acc.isFinished());
        assertEquals(1, acc.getContentItems().size());
        assertEquals("完毕", acc.lastItem().getTextRaw());
        assertEquals(15, acc.getUsage().totalTokens());
    }

    /// ////////////////////// parseToolCall

    /**
     * arguments 为对象（Ollama 常态）：直接结构化
     */
    @Test
    public void toolCallArgumentsAsObject() {
        ChatAccumulator acc = accumulator(true);

        ToolCall call = dialect.parseToolCall(acc, ONode.ofJson(
                "{\"id\":\"call_1\",\"function\":{\"name\":\"get_weather\",\"arguments\":{\"city\":\"杭州\"}}}"));

        assertEquals("get_weather", call.getIndex(), "以函数名为聚合主键");
        assertEquals("call_1", call.getId());
        assertEquals("get_weather", call.getName());
        assertEquals("杭州", call.getArguments().get("city"));
    }

    /**
     * arguments 为完整 JSON 字符串：解析为结构化参数
     */
    @Test
    public void toolCallArgumentsAsJsonString() {
        ChatAccumulator acc = accumulator(true);

        ToolCall call = dialect.parseToolCall(acc, ONode.ofJson(
                "{\"function\":{\"name\":\"get_weather\",\"arguments\":\"{\\\"city\\\":\\\"杭州\\\"}\"}}"));

        assertNull(call.getId(), "ollama 协议可以没有 tool call id");
        assertEquals("get_weather", call.getIndex());
        assertEquals("{\"city\":\"杭州\"}", call.getArgumentsStr());
        assertEquals("杭州", call.getArguments().get("city"));
    }

    /**
     * arguments 是流的中间分片（不完整 JSON）：不结构化，原始串保留给上层继续累积
     */
    @Test
    public void toolCallArgumentsAsIncompleteChunk() {
        ChatAccumulator acc = accumulator(true);

        ToolCall call = dialect.parseToolCall(acc, ONode.ofJson(
                "{\"function\":{\"name\":\"get_weather\",\"arguments\":\"{\\\"city\\\":\"}}"));

        assertEquals("{\"city\":", call.getArgumentsStr());
        assertTrue(call.getArguments().isEmpty(), "不完整分片不得结构化");
    }

    /**
     * arguments 含完整 JSON 块但整体损坏（多余闭合）：修复失败时降级为无结构化参数
     */
    @Test
    public void toolCallArgumentsBrokenBeyondRepair() {
        ChatAccumulator acc = accumulator(true);

        ToolCall call = dialect.parseToolCall(acc, ONode.ofJson(
                "{\"function\":{\"name\":\"get_weather\",\"arguments\":\"{\\\"city\\\":1}}}\"}}"));

        assertEquals("{\"city\":1}}}", call.getArgumentsStr());
        assertTrue(call.getArguments().isEmpty(), "修复失败时不得给出半截参数");
    }
}
