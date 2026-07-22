package features.ai.core;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.noear.snack4.ONode;
import org.noear.solon.ai.chat.ChatConfig;
import org.noear.solon.ai.chat.ChatOptions;
import org.noear.solon.ai.chat.ChatRequest;
import org.noear.solon.ai.chat.ChatResponseDefault;
import org.noear.solon.ai.chat.content.AudioBlock;
import org.noear.solon.ai.chat.content.ContentBlock;
import org.noear.solon.ai.chat.content.Contents;
import org.noear.solon.ai.chat.content.ImageBlock;
import org.noear.solon.ai.chat.content.TextBlock;
import org.noear.solon.ai.chat.content.VideoBlock;
import org.noear.solon.ai.chat.dialect.AbstractChatDialect;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.message.ToolMessage;
import org.noear.solon.ai.chat.message.UserMessage;
import org.noear.solon.ai.chat.session.InMemoryChatSession;
import org.noear.solon.ai.chat.tool.ToolResult;

import java.util.Arrays;
import java.util.List;

public class AssistantMessageTest {
    @Test
    public void getReasoningShouldRemoveThinkTagsWhenThinking() {
        AssistantMessage message = new AssistantMessage("<think>analysis</think>", true);

        Assertions.assertEquals("analysis", message.getReasoning());
    }

    @Test
    public void plainTextShouldNotBeMultiModal() {
        AssistantMessage message = ChatMessage.ofAssistant("hello");

        Assertions.assertFalse(message.isMultiModal());
        Assertions.assertFalse(message.hasMedia());
        Assertions.assertTrue(message.getBlocks().isEmpty());
        Assertions.assertEquals("hello", message.getContent());
    }

    @Test
    public void ofAssistantWithContentsShouldSupportMultiModal() {
        Contents contents = new Contents("describe")
                .addBlock(ImageBlock.ofUrl("https://example.com/a.png"));

        AssistantMessage message = ChatMessage.ofAssistant(contents);

        Assertions.assertTrue(message.isMultiModal());
        Assertions.assertTrue(message.hasMedia());
        Assertions.assertEquals("describe", message.getContent());
        Assertions.assertEquals(2, message.getBlocks().size());
        Assertions.assertTrue(message.getBlocks().get(0) instanceof TextBlock);
        Assertions.assertTrue(message.getBlocks().get(1) instanceof ImageBlock);
    }

    @Test
    public void ofAssistantWithBlocksVarArgsShouldWork() {
        AssistantMessage message = ChatMessage.ofAssistant(
                "see image",
                ImageBlock.ofUrl("https://example.com/b.png"));

        Assertions.assertTrue(message.isMultiModal());
        Assertions.assertEquals("see image", message.getContent());
        Assertions.assertEquals(2, message.getBlocks().size());
    }

    @Test
    public void ofAssistantShouldNotDuplicateTextBlockWhenBlocksAlreadyContainSameText() {
        List<ContentBlock> blocks = Arrays.asList(
                TextBlock.of("caption"),
                ImageBlock.ofUrl("https://example.com/dup.png"));

        AssistantMessage message = ChatMessage.ofAssistant("caption", blocks);

        Assertions.assertEquals(2, message.getBlocks().size());
        Assertions.assertEquals(1, message.getBlocks().stream().filter(b -> b instanceof TextBlock).count());
    }

    @Test
    public void addMediaBlocksShouldDeduplicateSameInstance() {
        TestResponse tr = new TestResponse();
        ImageBlock image = ImageBlock.ofUrl("https://example.com/once.png");

        tr.addMediaBlocks(Arrays.asList(image));
        tr.addMediaBlocks(Arrays.asList(image));

        Assertions.assertEquals(1, tr.getMediaBlocks().size());
    }

    @Test
    public void addMediaBlocksShouldDeduplicateEquivalentContent() {
        TestResponse tr = new TestResponse();
        ImageBlock a = ImageBlock.ofUrl("https://example.com/eq.png");
        ImageBlock b = ImageBlock.ofUrl("https://example.com/eq.png");

        tr.addMediaBlocks(Arrays.asList(a));
        tr.addMediaBlocks(Arrays.asList(b));

        Assertions.assertEquals(1, tr.getMediaBlocks().size());
    }

    @Test
    public void buildAssistantAudioIdShouldUseSidecarWithoutMetaPollution() {
        TestDialect dialect = new TestDialect();
        AudioBlock audio = AudioBlock.ofUrl("audio://aud_x");
        audio.metaAdd("audio_id", "aud_x");
        audio.metaAdd("source_type", "openai_audio");

        AssistantMessage msg = ChatMessage.ofAssistant("spoken", audio);
        ONode node = dialect.buildChatMessageNode(new ChatConfig(), msg);

        Assertions.assertTrue(node.hasKey("audio"));
        Assertions.assertEquals("aud_x", node.get("audio").get("id").getString());

        // content 数组不应被内部 meta 污染；侧车音频也不应再写 audio_url
        if (node.get("content").isArray()) {
            for (ONode item : node.get("content").getArray()) {
                Assertions.assertFalse(item.hasKey("audio_id"));
                Assertions.assertFalse(item.hasKey("source_type"));
                Assertions.assertNotEquals("audio_url", item.get("type").getString());
            }
        }
    }

    @Test
    public void aggregationShouldNotDuplicateMediaFromLastMessageAndMediaBlocks() {
        TestResponse tr = new TestResponse();
        ImageBlock image = ImageBlock.ofUrl("https://example.com/agg.png");
        tr.appendContent("hello");
        tr.addMediaBlocks(Arrays.asList(image));

        AssistantMessage last = ChatMessage.ofAssistant("hello", image);
        tr.addChoice(new org.noear.solon.ai.chat.ChatChoice(0, new java.util.Date(), "stop", last));

        AssistantMessage agg = tr.getAggregationMessage();
        Assertions.assertNotNull(agg);
        long mediaCount = agg.getBlocks().stream().filter(b -> !(b instanceof TextBlock)).count();
        Assertions.assertEquals(1, mediaCount);
    }

    @Test
    public void jsonRoundTripShouldKeepBlocksType() {
        AssistantMessage message = ChatMessage.ofAssistant(
                "caption",
                ImageBlock.ofUrl("https://example.com/c.png", "image/png"));

        String json = ChatMessage.toJson(message);
        ChatMessage restored = ChatMessage.fromJson(json);

        Assertions.assertTrue(restored instanceof AssistantMessage);
        AssistantMessage am = (AssistantMessage) restored;
        Assertions.assertTrue(am.isMultiModal());
        Assertions.assertEquals("caption", am.getContent());
        Assertions.assertEquals(2, am.getBlocks().size());
        Assertions.assertTrue(am.getBlocks().get(1) instanceof ImageBlock);
        Assertions.assertEquals("https://example.com/c.png", am.getBlocks().get(1).getContent());
    }

    private static ChatResponseDefault newResp(boolean stream) {
        TestDialect dialect = new TestDialect();
        ChatRequest req = new ChatRequest(
                new ChatConfig(),
                dialect,
                ChatOptions.of(),
                InMemoryChatSession.builder().build(),
                null,
                null,
                stream);
        return new ChatResponseDefault(req, stream);
    }

    @Test
    public void parseAssistantMultiModalContentArray() {
        TestDialect dialect = new TestDialect();
        ChatResponseDefault resp = newResp(false);

        ONode oMessage = ONode.ofJson("{"
                + "\"role\":\"assistant\","
                + "\"content\":["
                + "  {\"type\":\"text\",\"text\":\"a cat\"},"
                + "  {\"type\":\"image_url\",\"image_url\":{\"url\":\"https://example.com/cat.png\"}}"
                + "]"
                + "}");

        List<AssistantMessage> list = dialect.parseAssistantMessage(resp, oMessage);
        Assertions.assertEquals(1, list.size());

        AssistantMessage msg = list.get(0);
        Assertions.assertEquals("a cat", msg.getContent());
        Assertions.assertTrue(msg.isMultiModal());
        Assertions.assertEquals(2, msg.getBlocks().size());
        Assertions.assertTrue(msg.getBlocks().get(0) instanceof TextBlock);
        Assertions.assertTrue(msg.getBlocks().get(1) instanceof ImageBlock);
        Assertions.assertEquals("https://example.com/cat.png", ((ImageBlock) msg.getBlocks().get(1)).getUrl());
    }

    @Test
    public void parseAssistantAudioSidecar() {
        TestDialect dialect = new TestDialect();
        ChatResponseDefault resp = newResp(false);

        ONode oMessage = ONode.ofJson("{"
                + "\"role\":\"assistant\","
                + "\"content\":null,"
                + "\"audio\":{"
                + "  \"id\":\"aud_1\","
                + "  \"data\":\"AAAA\","
                + "  \"transcript\":\"hello audio\","
                + "  \"expires_at\":123"
                + "}"
                + "}");

        List<AssistantMessage> list = dialect.parseAssistantMessage(resp, oMessage);
        Assertions.assertEquals(1, list.size());

        AssistantMessage msg = list.get(0);
        Assertions.assertEquals("hello audio", msg.getContent());
        Assertions.assertTrue(msg.isMultiModal());
        Assertions.assertTrue(msg.hasMedia());

        boolean hasAudio = false;
        for (ContentBlock block : msg.getBlocks()) {
            if (block instanceof AudioBlock) {
                hasAudio = true;
                Assertions.assertEquals("aud_1", block.metas().get("audio_id"));
                Assertions.assertEquals("AAAA", ((AudioBlock) block).getData());
            }
        }
        Assertions.assertTrue(hasAudio);
    }

    @Test
    public void parsePlainTextShouldRemainCompatible() {
        TestDialect dialect = new TestDialect();
        ChatResponseDefault resp = newResp(false);

        ONode oMessage = ONode.ofJson("{\"role\":\"assistant\",\"content\":\"plain\"}");
        List<AssistantMessage> list = dialect.parseAssistantMessage(resp, oMessage);

        Assertions.assertEquals(1, list.size());
        AssistantMessage msg = list.get(0);
        Assertions.assertEquals("plain", msg.getContent());
        Assertions.assertFalse(msg.isMultiModal());
        Assertions.assertTrue(msg.getBlocks().isEmpty());
    }

    @Test
    public void buildAssistantMessageNodeShouldEmitContentArrayWhenMultiModal() {
        TestDialect dialect = new TestDialect();
        AssistantMessage msg = ChatMessage.ofAssistant(
                "look",
                ImageBlock.ofUrl("https://example.com/x.png"));

        ONode node = dialect.buildChatMessageNode(new ChatConfig(), msg);
        Assertions.assertEquals("assistant", node.get("role").getString());
        Assertions.assertTrue(node.get("content").isArray());
        Assertions.assertEquals(2, node.get("content").getArray().size());
        Assertions.assertEquals("text", node.get("content").get(0).get("type").getString());
        Assertions.assertEquals("look", node.get("content").get(0).get("text").getString());
        Assertions.assertEquals("image_url", node.get("content").get(1).get("type").getString());
    }

    @Test
    public void buildAssistantMessageNodePlainTextShouldStayString() {
        TestDialect dialect = new TestDialect();
        AssistantMessage msg = ChatMessage.ofAssistant("hello");

        ONode node = dialect.buildChatMessageNode(new ChatConfig(), msg);
        Assertions.assertTrue(node.get("content").isValue());
        Assertions.assertEquals("hello", node.get("content").getString());
    }

    @Test
    public void buildAssistantMultiModalShouldStripThinkTagsFromTextBlocks() {
        TestDialect dialect = new TestDialect();
        List<ContentBlock> blocks = Arrays.asList(
                TextBlock.of("<think>secret</think>answer"),
                ImageBlock.ofUrl("https://example.com/t.png"));
        AssistantMessage msg = new AssistantMessage(
                "<think>secret</think>answer",
                false, null, null, null, null, blocks);

        ONode node = dialect.buildChatMessageNode(new ChatConfig(), msg);
        Assertions.assertTrue(node.get("content").isArray());

        String textOut = null;
        for (ONode item : node.get("content").getArray()) {
            if ("text".equals(item.get("type").getString())) {
                textOut = item.get("text").getString();
            }
        }
        Assertions.assertEquals("answer", textOut);
        Assertions.assertFalse(textOut.contains("<think>"));
    }

    @Test
    public void stripThinkTagsUtility() {
        Assertions.assertEquals("answer", AssistantMessage.stripThinkTags("<think>x</think>answer"));
        Assertions.assertEquals("", AssistantMessage.stripThinkTags("<think>only"));
        Assertions.assertEquals("plain", AssistantMessage.stripThinkTags("plain"));
        Assertions.assertEquals("", AssistantMessage.stripThinkTags(null));
    }

    @Test
    public void buildAssistantMessageByToolMessagesShouldMergeMedia() {
        TestDialect dialect = new TestDialect();
        AssistantMessage toolCall = ChatMessage.ofAssistant("calling");

        ToolResult result = new ToolResult("screenshot ok")
                .addBlock(ImageBlock.ofUrl("https://example.com/shot.png"));
        ToolMessage toolMessage = ChatMessage.ofTool(result, "shot", "call_1", true);

        AssistantMessage direct = dialect.buildAssistantMessageByToolMessages(toolCall, Arrays.asList(toolMessage));
        Assertions.assertTrue(direct.getContent().contains("screenshot ok"));
        Assertions.assertTrue(direct.isMultiModal());
        Assertions.assertTrue(direct.hasMedia());
        Assertions.assertEquals("tool", direct.getMetadataAs("reason"));
    }

    @Test
    public void aggregationMessageShouldIncludeMediaBlocks() {
        // 流式依赖 contentBuilder。使用子类访问 protected contentBuilder
        TestResponse tr = new TestResponse();
        tr.appendContent("stream text");
        tr.addMediaBlocks(Arrays.asList(ImageBlock.ofUrl("https://example.com/s.png")));
        tr.addChoice(new org.noear.solon.ai.chat.ChatChoice(
                0, new java.util.Date(), "stop", ChatMessage.ofAssistant("stream text")));

        AssistantMessage agg = tr.getAggregationMessage();
        Assertions.assertNotNull(agg);
        Assertions.assertEquals("stream text", agg.getContent());
        Assertions.assertTrue(agg.isMultiModal());
        Assertions.assertTrue(agg.hasMedia());
    }
    
    @Test
    public void buildToolAndUserMessageShouldNotPolluteContentItemWithInternalMeta() {
        TestDialect dialect = new TestDialect();
    
        ImageBlock toolImage = ImageBlock.ofUrl("https://example.com/tool.png");
        toolImage.metaAdd("audio_id", "should_not_appear");
        toolImage.metaAdd("source_type", "tool_internal");
        ToolResult result = new ToolResult("ok").addBlock(toolImage);
        ToolMessage toolMessage = ChatMessage.ofTool(result, "shot", "call_meta", false);
    
        ONode toolNode = dialect.buildChatMessageNode(new ChatConfig(), toolMessage);
        Assertions.assertTrue(toolNode.get("content").isArray());
        for (ONode item : toolNode.get("content").getArray()) {
            Assertions.assertFalse(item.hasKey("audio_id"));
            Assertions.assertFalse(item.hasKey("source_type"));
        }
    
        ImageBlock userImage = ImageBlock.ofUrl("https://example.com/user.png");
        userImage.metaAdd("source_type", "user_internal");
        UserMessage userMessage = ChatMessage.ofUser("hi", userImage);
        ONode userNode = dialect.buildChatMessageNode(new ChatConfig(), userMessage);
        Assertions.assertTrue(userNode.get("content").isArray());
        for (ONode item : userNode.get("content").getArray()) {
            Assertions.assertFalse(item.hasKey("source_type"));
            Assertions.assertFalse(item.hasKey("audio_id"));
        }
    }
    
    @Test
    public void getMessageShouldFallbackToAggregationWhenOnlyMediaBlocks() {
        TestResponse tr = new TestResponse();
        ImageBlock image = ImageBlock.ofUrl("https://example.com/only-media.png");
        tr.addMediaBlocks(Arrays.asList(image));
    
        // 无 choice 时，流式仅 media 也应能 getMessage
        Assertions.assertFalse(tr.hasChoices());
        AssistantMessage msg = tr.getMessage();
        Assertions.assertNotNull(msg);
        Assertions.assertTrue(msg.hasMedia());
        Assertions.assertEquals(1, msg.getBlocks().stream().filter(b -> b instanceof ImageBlock).count());
    }
    
    @Test
    public void sessionToJsonShouldCompactOversizedInlineBase64() {
        // 构造超过阈值的 base64 数据
        StringBuilder big = new StringBuilder(ChatMessage.SESSION_INLINE_BASE64_MAX_CHARS + 100);
        while (big.length() <= ChatMessage.SESSION_INLINE_BASE64_MAX_CHARS) {
            big.append("ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/=");
        }
        ImageBlock image = ImageBlock.ofBase64(big.toString(), "image/png");
        image.metaAdd("id", "img_keep");
    
        AssistantMessage message = ChatMessage.ofAssistant("caption", image);
        String json = ChatMessage.toJson(message);
        Assertions.assertFalse(json.contains(big.substring(0, 80)), "oversized base64 should be stripped");
        Assertions.assertTrue(json.contains("external") || json.contains("data_truncated"));
    
        // 小 base64 保持原样
        ImageBlock small = ImageBlock.ofBase64("AAAA", "image/png");
        String smallJson = ChatMessage.toJson(ChatMessage.ofAssistant("s", small));
        Assertions.assertTrue(smallJson.contains("AAAA"));
    
        // compact=false 保留大数据
        String fullJson = ChatMessage.toJson(message, false);
        Assertions.assertTrue(fullJson.length() > ChatMessage.SESSION_INLINE_BASE64_MAX_CHARS);
    }
    
    @Test
    public void contentApisShouldFallbackWhenOnlyMediaBlocks() {
        TestResponse tr = new TestResponse();
        ImageBlock image = ImageBlock.ofUrl("https://example.com/only-media-content.png");
        tr.addMediaBlocks(Arrays.asList(image));
    
        // 与 getMessage 对齐：仅 media 时 content API 不抛 NPE，也不恒为 null/false 失配
        Assertions.assertFalse(tr.hasChoices());
        Assertions.assertNotNull(tr.getMessage());
        Assertions.assertFalse(tr.hasContent()); // 仅 media，文本为空
        Assertions.assertEquals("", tr.getContent());
        Assertions.assertEquals("", tr.getResultContent());
    }
    
    @Test
    public void buildShouldSkipTruncatedEmptyMediaAndKeepText() {
        TestDialect dialect = new TestDialect();
    
        // 模拟 Session 压缩后：data 清空、无 url，仅有截断标记
        ImageBlock truncated = ImageBlock.ofBase64("", "image/png");
        truncated.metaAdd("storage", "external");
        truncated.metaAdd("data_truncated", true);
    
        AssistantMessage msg = ChatMessage.ofAssistant("keep text", truncated);
        ONode node = dialect.buildChatMessageNode(new ChatConfig(), msg);
    
        // 文本应保留；空 media 不应写出 image_url
        if (node.get("content").isArray()) {
            boolean hasImage = false;
            boolean hasText = false;
            for (ONode item : node.get("content").getArray()) {
                if ("image_url".equals(item.get("type").getString())) {
                    hasImage = true;
                }
                if ("text".equals(item.get("type").getString())) {
                    hasText = true;
                    Assertions.assertEquals("keep text", item.get("text").getString());
                }
            }
            Assertions.assertTrue(hasText);
            Assertions.assertFalse(hasImage, "truncated empty media should be skipped");
        } else {
            // 若仅剩文本，可降级为 string content
            Assertions.assertEquals("keep text", node.get("content").getString());
        }
    }
    
    @Test
    public void buildShouldSkipTruncatedEmptyMediaForUserAndTool() {
        TestDialect dialect = new TestDialect();
    
        ImageBlock truncated = new ImageBlock();
        truncated.metaAdd("storage", "external");
        truncated.metaAdd("data_truncated", true);
    
        UserMessage user = ChatMessage.ofUser("u", truncated);
        ONode userNode = dialect.buildChatMessageNode(new ChatConfig(), user);
        if (userNode.get("content").isArray()) {
            for (ONode item : userNode.get("content").getArray()) {
                Assertions.assertNotEquals("image_url", item.get("type").getString());
            }
        }
    
        ToolResult result = new ToolResult("ok").addBlock(truncated);
        ToolMessage tool = ChatMessage.ofTool(result, "shot", "call_trunc", false);
        ONode toolNode = dialect.buildChatMessageNode(new ChatConfig(), tool);
        if (toolNode.get("content").isArray()) {
            for (ONode item : toolNode.get("content").getArray()) {
                Assertions.assertNotEquals("image_url", item.get("type").getString());
            }
        }
    }

    @Test
    public void projectTextContentShouldNotPutBase64IntoContent() {
        TestDialect dialect = new TestDialect();

        // 纯 base64 媒体：content 投影必须为空，媒体只在 blocks
        String base64 = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==";
        ONode base64Content = new ONode().asArray();
        base64Content.addNew()
                .set("type", "image_url")
                .getOrNew("image_url")
                .set("url", "data:image/png;base64," + base64);

        String projectedBase64 = dialect.projectTextContentPublic(
                dialect.parseAssistantContentBlocksPublic(null, base64Content, null));
        Assertions.assertNull(projectedBase64, "pure base64 media must not project into content");

        // 纯 URL 媒体：允许短 URL 投影
        ONode urlContent = new ONode().asArray();
        urlContent.addNew()
                .set("type", "image_url")
                .getOrNew("image_url")
                .set("url", "https://example.com/pure-media.png");

        String projectedUrl = dialect.projectTextContentPublic(
                dialect.parseAssistantContentBlocksPublic(null, urlContent, null));
        Assertions.assertEquals("https://example.com/pure-media.png", projectedUrl);

        // 文本 + 媒体：只投影文本
        ONode mixed = new ONode().asArray();
        mixed.addNew().set("type", "text").set("text", "caption");
        mixed.addNew()
                .set("type", "image_url")
                .getOrNew("image_url")
                .set("url", "https://example.com/mixed.png");
        String projectedMixed = dialect.projectTextContentPublic(
                dialect.parseAssistantContentBlocksPublic(null, mixed, null));
        Assertions.assertEquals("caption", projectedMixed);
    }

    @Test
    public void buildUserAndToolShouldFallbackWhenAllMediaTruncated() {
        TestDialect dialect = new TestDialect();

        // 纯截断媒体、无可播内容且无文本块时，应回退为 string content，避免 content: []
        ImageBlock truncated = new ImageBlock();
        truncated.metaAdd("storage", "external");
        truncated.metaAdd("data_truncated", true);

        UserMessage user = ChatMessage.ofUser(truncated);
        ONode userNode = dialect.buildChatMessageNode(new ChatConfig(), user);
        Assertions.assertFalse(userNode.get("content").isArray(),
                "user content should fallback to string when media all skipped");
        // 无文本投影时回退为空串
        Assertions.assertEquals("", userNode.get("content").getString() == null ? "" : userNode.get("content").getString());
        
        // 有文本 + 截断媒体：文本应保留（数组仅 text 或 string 均可）
        UserMessage userWithText = ChatMessage.ofUser("user text kept", truncated);
        ONode userTextNode = dialect.buildChatMessageNode(new ChatConfig(), userWithText);
        if (userTextNode.get("content").isArray()) {
            boolean hasText = false;
            boolean hasImage = false;
            for (ONode item : userTextNode.get("content").getArray()) {
                if ("text".equals(item.get("type").getString())) {
                    hasText = true;
                    Assertions.assertEquals("user text kept", item.get("text").getString());
                }
                if ("image_url".equals(item.get("type").getString())) {
                    hasImage = true;
                }
            }
            Assertions.assertTrue(hasText);
            Assertions.assertFalse(hasImage);
        } else {
            Assertions.assertEquals("user text kept", userTextNode.get("content").getString());
        }
    
        ToolResult result = new ToolResult("").addBlock(truncated);
        ToolMessage tool = ChatMessage.ofTool(result, "shot", "call_empty_arr", false);
        ONode toolNode = dialect.buildChatMessageNode(new ChatConfig(), tool);
        Assertions.assertFalse(toolNode.get("content").isArray(),
                "tool content should fallback to string when media all skipped");
    }

    /**
     * 可实例化的测试方言
     */
    static class TestDialect extends AbstractChatDialect {
        @Override
        public boolean matched(ChatConfig config) {
            return true;
        }

        @Override
        public boolean parseResponseJson(ChatConfig config, ChatResponseDefault resp, String json) {
            return false;
        }

        /** 暴露 projectTextContent 便于单测 */
        public String projectTextContentPublic(List<ContentBlock> blocks) {
            return projectTextContent(blocks);
        }

        /** 暴露 parseAssistantContentBlocks 便于单测 */
        public List<ContentBlock> parseAssistantContentBlocksPublic(
                ChatResponseDefault resp, ONode oContent, ONode oMessage) {
            return parseAssistantContentBlocks(resp, oContent, oMessage);
        }
    }

    /**
     * 暴露 contentBuilder 写入，便于聚合测试
     */
    static class TestResponse extends ChatResponseDefault {
        public TestResponse() {
            super(new ChatRequest(
                    new ChatConfig(),
                    new TestDialect(),
                    ChatOptions.of(),
                    InMemoryChatSession.builder().build(),
                    null,
                    null,
                    true), true);
        }

        public void appendContent(String text) {
            contentBuilder.append(text);
        }
    }

    // ==================== 新增：多 media blocks 顺序保持 ====================

    @Test
    public void multiMediaBlocksOrderShouldSurviveJsonRoundTrip() {
        // 构造含 ImageBlock + AudioBlock + VideoBlock 的多模态消息
        AssistantMessage msg = ChatMessage.ofAssistant(
                "mixed media",
                ImageBlock.ofUrl("https://example.com/img.png"),
                AudioBlock.ofUrl("https://example.com/aud.mp3"),
                VideoBlock.ofUrl("https://example.com/vid.mp4"));

        Assertions.assertEquals(4, msg.getBlocks().size());
        Assertions.assertTrue(msg.getBlocks().get(0) instanceof TextBlock);
        Assertions.assertTrue(msg.getBlocks().get(1) instanceof ImageBlock);
        Assertions.assertTrue(msg.getBlocks().get(2) instanceof AudioBlock);
        Assertions.assertTrue(msg.getBlocks().get(3) instanceof VideoBlock);

        // JSON 序列化 -> 反序列化后顺序保持
        String json = ChatMessage.toJson(msg);
        ChatMessage restored = ChatMessage.fromJson(json);
        Assertions.assertTrue(restored instanceof AssistantMessage);
        AssistantMessage amRestored = (AssistantMessage) restored;

        Assertions.assertNotNull(amRestored.getBlocks());
        Assertions.assertEquals(4, amRestored.getBlocks().size());
        Assertions.assertTrue(amRestored.getBlocks().get(0) instanceof TextBlock);
        Assertions.assertTrue(amRestored.getBlocks().get(1) instanceof ImageBlock);
        Assertions.assertTrue(amRestored.getBlocks().get(2) instanceof AudioBlock);
        Assertions.assertTrue(amRestored.getBlocks().get(3) instanceof VideoBlock);
    }

    // ==================== 新增：空 blocks 列表构造不抛异常 ====================

    @Test
    public void emptyBlocksListShouldNotThrow() {
        AssistantMessage msg = new AssistantMessage(
                "", false, null, null, null, null, java.util.Collections.emptyList());

        Assertions.assertFalse(msg.isMultiModal());
        Assertions.assertFalse(msg.hasMedia());
        Assertions.assertTrue(msg.getBlocks().isEmpty());
        Assertions.assertEquals("", msg.getContent());
    }

    // ==================== 新增：reasoning 回传 + content=null 保障验证 ====================

    @Test
    public void buildAssistantWithReasoningAndToolCallsShouldWriteReasoningContent() {
        // 模拟第一次 stream 结束后的场景：
        // content 含 think 标签 + 推理文本，getResultContent() 为空，有 tool_calls
        // 构建第二次请求时应回传 reasoning_content（DeepSeek 等模型多轮推理需要），且 content 应为 null
        TestDialect dialect = new TestDialect();

        java.util.List<java.util.Map> toolCallsRaw = new java.util.ArrayList<>();
        java.util.Map<String, Object> tc = new java.util.HashMap<>();
        tc.put("id", "call_001");
        tc.put("type", "function");
        java.util.Map<String, Object> tcFn = new java.util.HashMap<>();
        tcFn.put("name", "get_weather");
        tcFn.put("arguments", "{\"location\": \"杭州\"}");
        tc.put("function", tcFn);
        toolCallsRaw.add(tc);

        // content 含 think 标签和推理文本，getResultContent() 会剥离标签后为空
        AssistantMessage msg = new AssistantMessage(
                "<think>用户想知道杭州天气。</think>",
                false, null, toolCallsRaw, null, null, null)
                .reasoningFieldName("reasoning_content");

        ONode node = dialect.buildChatMessageNode(new ChatConfig(), msg);

        // 1. 应包含 reasoning_content 字段（DeepSeek 等模型多轮推理需要回传）
        Assertions.assertTrue(node.hasKey("reasoning_content"),
                "reasoning_content should be written to request JSON for multi-turn reasoning");
        Assertions.assertEquals("用户想知道杭州天气。",
                node.get("reasoning_content").getString());

        // 2. 有 tool_calls 时，content 字段必须存在（可为 null）
        Assertions.assertTrue(node.hasKey("content"),
                "content field must be present when tool_calls exist");

        // 3. content 应为 null（纯推理无实际回复内容，getResultContent() 为空）
        Assertions.assertTrue(node.get("content").isNull(),
                "content should be null when only reasoning + tool_calls");

        // 4. tool_calls 应正确写出
        Assertions.assertTrue(node.hasKey("tool_calls"));
        Assertions.assertEquals(1, node.get("tool_calls").getArray().size());
        Assertions.assertEquals("get_weather",
                node.get("tool_calls").get(0).get("function").get("name").getString());
    }

    @Test
    public void buildAssistantWithTextAndToolCallsShouldNotWriteEmptyReasoning() {
        // 有正常文本内容 + tool_calls + reasoningFieldName 的场景
        // 但 content 无 think 标签 -> getReasoning() 为空 -> reasoning_content 不应写出
        TestDialect dialect = new TestDialect();

        java.util.List<java.util.Map> toolCallsRaw = new java.util.ArrayList<>();
        java.util.Map<String, Object> tc = new java.util.HashMap<>();
        tc.put("id", "call_002");
        tc.put("type", "function");
        java.util.Map<String, Object> tcFn = new java.util.HashMap<>();
        tcFn.put("name", "search_www");
        tcFn.put("arguments", "{\"key\": \"天气\"}");
        tc.put("function", tcFn);
        toolCallsRaw.add(tc);

        AssistantMessage msg = new AssistantMessage(
                "让我帮你搜索天气信息。",
                false, null, toolCallsRaw, null, null, null)
                .reasoningFieldName("reasoning_content");

        ONode node = dialect.buildChatMessageNode(new ChatConfig(), msg);

        // reasoning 内容为空（无 think 标签）-> 不应写出 reasoning_content
        Assertions.assertFalse(node.hasKey("reasoning_content"),
                "reasoning_content should NOT be written when reasoning is empty");

        // content 应为实际文本
        Assertions.assertTrue(node.get("content").isValue());
        Assertions.assertEquals("让我帮你搜索天气信息。", node.get("content").getString());

        // tool_calls 正确
        Assertions.assertTrue(node.hasKey("tool_calls"));
        Assertions.assertEquals("search_www",
                node.get("tool_calls").get(0).get("function").get("name").getString());
    }

    @Test
    public void buildAssistantWithReasoningFieldNameButNoToolCallsShouldWriteReasoning() {
        // 仅有 reasoningFieldName 且 reasoning 内容非空，但无 tool_calls 且 getResultContent() 为空
        // reasoning_content 应回传；content 不写出（无 tool_calls 时不强加 null）
        TestDialect dialect = new TestDialect();

        // content 含 think 标签，getResultContent() 为空
        AssistantMessage msg = new AssistantMessage(
                "<think>仅推理无工具调用</think>",
                false, null, null, null, null, null)
                .reasoningFieldName("reasoning_content");

        ONode node = dialect.buildChatMessageNode(new ChatConfig(), msg);

        // reasoning 内容非空 -> 应回传
        Assertions.assertTrue(node.hasKey("reasoning_content"),
                "reasoning_content should be written when reasoning is non-empty");
        Assertions.assertEquals("仅推理无工具调用",
                node.get("reasoning_content").getString());

        // 无 tool_calls 时不强加 content=null
        // getResultContent() 为空，content 字段不写出（原有行为保持）
        Assertions.assertFalse(node.hasKey("content"),
                "content should not be written when empty and no tool_calls");
    }
}
