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
package org.noear.solon.ai.llm.dialect.dashscope;

import org.junit.jupiter.api.Test;
import org.noear.snack4.ONode;
import org.noear.solon.ai.chat.ChatConfig;
import org.noear.solon.ai.chat.content.AudioBlock;
import org.noear.solon.ai.chat.content.BlobBlock;
import org.noear.solon.ai.chat.content.ContentBlock;
import org.noear.solon.ai.chat.content.Contents;
import org.noear.solon.ai.chat.content.ImageBlock;
import org.noear.solon.ai.chat.content.TextBlock;
import org.noear.solon.ai.chat.content.VideoBlock;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.message.ChatMessage;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * DashScope 原生 content 形态：多模态用 {@code [{image|audio|video|text}]}，单模态保持 string
 *
 * <p>与 OpenAI 兼容形态（{@code {"type":"image_url","image_url":{...}}}）不同，
 * 原生协议把媒体直接放在同名键上。会话压缩后不可播的媒体块必须跳过，
 * 且不能因此写出空 content（空 content 会被端点拒绝）。</p>
 *
 * @author noear
 */
public class DashscopeChatDialectMessageTest {
    private final DashscopeChatDialect dialect = DashscopeChatDialect.getInstance();
    private final ChatConfig config = new ChatConfig();

    /**
     * 会话截断后的媒体：无 data、无 url、无侧车 id → 不可播
     */
    private static ImageBlock truncatedImage() {
        return ImageBlock.ofUrl(null);
    }

    /**
     * 仅有侧车 id 的媒体：可播判定通过，但没有可写出的数据串 → 仍应跳过
     */
    private static ImageBlock sidecarOnlyImage() {
        return ImageBlock.ofUrl(null).metaAdd("id", "img_1");
    }

    private ONode node(ChatMessage message) {
        return dialect.buildChatMessageNode(config, message);
    }

    /// ////////////////////////// 用户消息

    /**
     * 纯文本用户消息：content 保持 string（兼容纯文本模型）
     */
    @Test
    public void userTextKeepsStringContent() {
        ONode oNode = node(ChatMessage.ofUser("杭州天气"));

        assertEquals("user", oNode.get("role").getString());
        assertTrue(oNode.get("content").isValue(), "单模态 content 必须是 string");
        assertEquals("杭州天气", oNode.get("content").getString());
    }

    /**
     * 纯文本但内容为 null：写出空串占位（缺 content 键会被端点拒绝）
     */
    @Test
    public void userNullTextBecomesEmptyString() {
        ONode oNode = node(ChatMessage.ofUser((String) null));

        assertTrue(oNode.hasKey("content"));
        assertEquals("", oNode.get("content").getString());
    }

    /**
     * 多模态用户消息：原生数组形态，文本块与媒体块按序写出
     */
    @Test
    public void userMultiModalUsesNativeContentArray() {
        ONode content = node(ChatMessage.ofUser("看图",
                ImageBlock.ofUrl("https://example.com/a.png"))).get("content");

        assertTrue(content.isArray(), "多模态 content 必须是数组");
        assertEquals(2, content.getArray().size());
        assertEquals("看图", content.get(0).get("text").getString());
        assertEquals("https://example.com/a.png", content.get(1).get("image").getString(),
                "原生协议直接用 image 键，而非 OpenAI 的 image_url 对象");
    }

    /**
     * 无文本块时补文本投影（保证多模态帧仍带可读文本）
     */
    @Test
    public void userMediaOnlyGetsTextProjection() {
        Contents contents = new Contents();
        contents.addBlock(ImageBlock.ofUrl("https://example.com/a.png"));
        contents.metas().put("k", "v");

        ONode content = node(ChatMessage.ofUser(contents)).get("content");

        assertEquals(1, content.getArray().size(), "content 为 null 时无可投影文本");
        assertEquals("https://example.com/a.png", content.get(0).get("image").getString());
    }

    /**
     * 媒体全不可播且无文本：退回 string 空串，不得写出空数组
     */
    @Test
    public void userAllMediaTruncatedFallsBackToEmptyString() {
        ONode oNode = node(ChatMessage.ofUser(truncatedImage()));

        assertFalse(oNode.get("content").isArray(), "空数组不得写出");
        assertEquals("", oNode.get("content").getString());
    }

    /**
     * 媒体全不可播但有（空）文本：退回原文本 string
     */
    @Test
    public void userAllMediaTruncatedFallsBackToOriginalText() {
        //文本块为空串：既不写入数组，也不触发文本投影
        ChatMessage message = ChatMessage.ofUser("", truncatedImage());

        ONode oNode = node(message);

        assertFalse(oNode.get("content").isArray());
        assertEquals("", oNode.get("content").getString());
    }

    /// ////////////////////////// 助理消息

    /**
     * 单模态助理消息：content 保持 string
     */
    @Test
    public void assistantTextKeepsStringContent() {
        ONode oNode = node(ChatMessage.ofAssistant("杭州今天晴"));

        assertEquals("assistant", oNode.get("role").getString());
        assertEquals("杭州今天晴", oNode.get("content").getString());
        assertFalse(oNode.hasKey("reasoning_content"), "百炼多轮不回传思考内容");
    }

    /**
     * 单模态且文本为空：本方言不写出 content 键
     */
    @Test
    public void assistantEmptyTextWritesNoContentKey() {
        ONode oNode = node(new AssistantMessage(""));

        assertFalse(oNode.hasKey("content"));
        assertEquals("assistant", oNode.get("role").getString());
    }

    /**
     * 思考内容不回传（与父类 OpenAI 风格的差异点）
     */
    @Test
    public void assistantThinkingIsNotSentBack() {
        AssistantMessage message = new AssistantMessage("结论", "推理过程", false)
                .reasoningFieldName("reasoning_content");

        ONode oNode = node(message);

        assertEquals("结论", oNode.get("content").getString());
        assertFalse(oNode.hasKey("reasoning_content"));
        assertFalse(oNode.hasKey("reasoning"));
    }

    /**
     * 多模态助理消息：原生数组形态
     */
    @Test
    public void assistantMultiModalUsesNativeContentArray() {
        AssistantMessage message = new AssistantMessage("生成好了", "", false, null, null, null, null,
                Collections.singletonList((ContentBlock) ImageBlock.ofUrl("https://example.com/b.png")));

        ONode content = node(message).get("content");

        assertTrue(content.isArray());
        assertEquals(2, content.getArray().size());
        assertEquals("https://example.com/b.png", content.get(0).get("image").getString());
        assertEquals("生成好了", content.get(1).get("text").getString(), "无文本块时补文本投影");
    }

    /**
     * 多模态但媒体已截断：只剩文本投影，不得写出空数组
     */
    @Test
    public void assistantTruncatedMediaKeepsTextProjection() {
        AssistantMessage message = new AssistantMessage("仅剩文本", "", false, null, null, null, null,
                Collections.singletonList((ContentBlock) truncatedImage()));

        ONode content = node(message).get("content");

        assertTrue(content.isArray());
        assertEquals(1, content.getArray().size());
        assertEquals("仅剩文本", content.get(0).get("text").getString());
    }

    /**
     * 多模态但媒体已截断且无文本：写出空 content 数组（无可投影的文本）
     */
    @Test
    public void assistantTruncatedMediaWithoutTextYieldsEmptyArray() {
        AssistantMessage message = new AssistantMessage("", "", false, null, null, null, null,
                Collections.singletonList((ContentBlock) truncatedImage()));

        ONode content = node(message).get("content");

        assertTrue(content.isArray());
        assertTrue(content.getArray().isEmpty(), "无媒体、无文本时无内容可写");
    }

    /**
     * 工具调用回传：出站净化后必须是合法 JSON 的 arguments（截断片段会被端点 400 拒绝）
     */
    @Test
    public void assistantToolCallsAreSanitizedOnOutbound() {
        ONode oNode = node(assistantWithToolCallArguments("{\"location\":"));

        ONode call = oNode.get("tool_calls").get(0);
        assertEquals("call_1", call.get("id").getString());
        assertEquals("get_weather", call.get("function").get("name").getString());

        String arguments = call.get("function").get("arguments").getString();
        assertNotNull(arguments);
        assertTrue(ONode.ofJson(arguments).isObject(),
                "损坏的 arguments 必须被修复为合法 JSON 对象：" + arguments);
    }

    /**
     * 完好的 arguments 原样保留
     */
    @Test
    public void assistantIntactToolCallArgumentsAreKept() {
        ONode oNode = node(assistantWithToolCallArguments("{\"location\":\"杭州\"}"));

        String arguments = oNode.get("tool_calls").get(0).get("function").get("arguments").getString();
        assertEquals("杭州", ONode.ofJson(arguments).get("location").getString());
    }

    private AssistantMessage assistantWithToolCallArguments(String arguments) {
        Map<String, Object> function = new LinkedHashMap<>();
        function.put("name", "get_weather");
        function.put("arguments", arguments);

        Map<String, Object> call = new LinkedHashMap<>();
        call.put("id", "call_1");
        call.put("type", "function");
        call.put("function", function);

        List<Map> toolCallsRaw = new ArrayList<>();
        toolCallsRaw.add(call);

        return new AssistantMessage("", "", false, null, toolCallsRaw, null, null, null);
    }

    /// ////////////////////////// 内容块写出规则

    /**
     * 各媒体类型的原生键位：image / audio / video / text
     */
    @Test
    public void allNativeMediaKinds() {
        List<ContentBlock> blocks = new ArrayList<>();
        blocks.add(ImageBlock.ofBase64("QUJD", "image/png"));
        blocks.add(AudioBlock.ofUrl("https://example.com/a.mp3"));
        blocks.add(VideoBlock.ofUrl("https://example.com/a.mp4"));
        blocks.add(TextBlock.of("说明"));

        ONode content = node(ChatMessage.ofUser(new Contents().addBlocks(blocks))).get("content");

        assertEquals(4, content.getArray().size());
        assertEquals("data:image/png;base64,QUJD", content.get(0).get("image").getString(),
                "base64 需带 mime 前缀");
        assertEquals("https://example.com/a.mp3", content.get(1).get("audio").getString());
        assertEquals("https://example.com/a.mp4", content.get(2).get("video").getString());
        assertEquals("说明", content.get(3).get("text").getString());
    }

    /**
     * 视频块的可选 VL 参数：fps / max_frames 与 video 同级
     */
    @Test
    public void videoCarriesOptionalVlParameters() {
        VideoBlock video = VideoBlock.ofUrl("https://example.com/a.mp4");
        video.metaAdd("fps", 2);
        video.metaAdd("max_frames", 16);

        ONode item = node(ChatMessage.ofUser(video)).get("content").get(0);

        assertEquals("https://example.com/a.mp4", item.get("video").getString());
        assertEquals(2, item.get("fps").getInt());
        assertEquals(16, item.get("max_frames").getInt());
    }

    /**
     * 无 VL 参数时不写出 fps / max_frames
     */
    @Test
    public void videoWithoutMetasWritesNoVlParameters() {
        ONode item = node(ChatMessage.ofUser(VideoBlock.ofUrl("https://example.com/a.mp4")))
                .get("content").get(0);

        assertFalse(item.hasKey("fps"));
        assertFalse(item.hasKey("max_frames"));
    }

    /**
     * 自定义媒体实现的 metas() 允许为 null：不得因此 NPE，也不写出 VL 参数
     */
    @Test
    public void videoWithNullMetasIsStillWritten() {
        ONode item = node(ChatMessage.ofUser(new NullMetasVideoBlock("https://example.com/a.mp4")))
                .get("content").get(0);

        assertEquals("https://example.com/a.mp4", item.get("video").getString());
        assertFalse(item.hasKey("fps"));
        assertFalse(item.hasKey("max_frames"));
    }

    /**
     * metas() 返回 null 的视频块（模拟第三方 ContentBlock 实现）
     */
    private static class NullMetasVideoBlock extends VideoBlock {
        NullMetasVideoBlock(String url) {
            this.url = url;
        }

        @Override
        public Map<String, Object> metas() {
            return null;
        }
    }

    /**
     * 不可播 / 无数据串的媒体一律跳过（各类型独立判定），且不影响其它块
     */
    @Test
    public void unplayableAndDatalessBlocksAreSkipped() {
        List<ContentBlock> blocks = new ArrayList<>();
        blocks.add(truncatedImage());
        blocks.add(sidecarOnlyImage());
        blocks.add(AudioBlock.ofUrl(null));
        blocks.add(AudioBlock.ofUrl(null).metaAdd("audio_id", "au_1"));
        blocks.add(VideoBlock.ofUrl(null));
        blocks.add(VideoBlock.ofUrl(null).metaAdd("id", "vd_1"));
        blocks.add(TextBlock.of(""));
        blocks.add(BlobBlock.of("MTIz", "application/octet-stream"));
        blocks.add(ImageBlock.ofUrl("https://example.com/ok.png"));

        ONode content = node(ChatMessage.ofUser(new Contents().addBlocks(blocks))).get("content");

        assertEquals(1, content.getArray().size(),
                "仅可播且有数据串的块才写出（未建模的块类型也跳过）：" + content.toJson());
        assertEquals("https://example.com/ok.png", content.get(0).get("image").getString());
    }

    /**
     * 已有文本块时不再补投影（避免文本重复）
     */
    @Test
    public void textBlockSuppressesTextProjection() {
        List<ContentBlock> blocks = new ArrayList<>();
        blocks.add(TextBlock.of("正文"));
        blocks.add(ImageBlock.ofUrl("https://example.com/a.png"));

        ChatMessage message = ChatMessage.ofUser(new Contents().addBlocks(blocks));
        ONode content = node(message).get("content");

        int textCount = 0;
        for (ONode item : content.getArray()) {
            if (item.hasKey("text")) {
                textCount++;
            }
        }
        assertEquals(1, textCount, "文本只能出现一次：" + content.toJson());
    }

    /**
     * 空文本块不占位（也不阻止后续文本投影）
     */
    @Test
    public void emptyTextBlockDoesNotSuppressProjection() {
        ONode content = node(ChatMessage.ofUser(
                new Contents().addBlock(TextBlock.of(""))
                        .addBlock(ImageBlock.ofUrl("https://example.com/a.png"))
                        .addText("补充说明"))).get("content");

        List<String> texts = new ArrayList<>();
        for (ONode item : content.getArray()) {
            if (item.hasKey("text")) {
                texts.add(item.get("text").getString());
            }
        }
        assertEquals(Arrays.asList("补充说明"), texts);
    }
}
