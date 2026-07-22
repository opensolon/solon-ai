package features.ai.dialect;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.noear.snack4.ONode;
import org.noear.solon.ai.chat.ChatConfig;
import org.noear.solon.ai.chat.ChatOptions;
import org.noear.solon.ai.chat.ChatRequest;
import org.noear.solon.ai.chat.ChatResponseDefault;
import org.noear.solon.ai.chat.content.AudioBlock;
import org.noear.solon.ai.chat.content.ImageBlock;
import org.noear.solon.ai.chat.content.TextBlock;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.message.UserMessage;
import org.noear.solon.ai.chat.session.InMemoryChatSession;
import org.noear.solon.ai.llm.dialect.anthropic.AnthropicChatDialect;
import org.noear.solon.ai.llm.dialect.dashscope.DashscopeChatDialect;
import org.noear.solon.ai.llm.dialect.gemini.GeminiChatDialect;
import org.noear.solon.ai.llm.dialect.gemini.GeminiInteractionsDialect;
import org.noear.solon.ai.llm.dialect.ollama.OllamaChatDialect;
import org.noear.solon.ai.chat.content.VideoBlock;
import org.noear.solon.ai.llm.dialect.openai.OpenaiChatDialect;
import org.noear.solon.ai.llm.dialect.openai.OpenaiResponsesDialect;

/**
 * 各方言 Assistant 多模态适配单测（mock JSON，不依赖真实 API）
 *
 * @since 3.9
 */
public class DialectAssistantMultimodalTest {

    private static ChatResponseDefault newResp(boolean stream, org.noear.solon.ai.chat.dialect.ChatDialect dialect) {
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
    public void ollamaBuildAssistantShouldUseImagesSidecar() {
        OllamaChatDialect dialect = OllamaChatDialect.getInstance();
        AssistantMessage msg = ChatMessage.ofAssistant(
                "a cat",
                ImageBlock.ofBase64("iVBORw0KGgo=", "image/png"));

        ONode node = dialect.buildChatMessageNode(new ChatConfig(), msg);
        Assertions.assertEquals("assistant", node.get("role").getString());
        Assertions.assertTrue(node.get("content").isValue());
        Assertions.assertEquals("a cat", node.get("content").getString());
        Assertions.assertTrue(node.hasKey("images"));
        Assertions.assertEquals(1, node.get("images").getArray().size());
        Assertions.assertFalse(node.get("content").isArray());
    }

    @Test
    public void ollamaParseAssistantShouldReadImagesSidecar() {
        OllamaChatDialect dialect = OllamaChatDialect.getInstance();
        ChatResponseDefault resp = newResp(false, dialect);

        ONode oMessage = ONode.ofJson("{"
                + "\"role\":\"assistant\","
                + "\"content\":\"generated\","
                + "\"images\":[\"iVBORw0KGgo=\"]"
                + "}");

        java.util.List<AssistantMessage> list = dialect.parseAssistantMessage(resp, oMessage);
        Assertions.assertFalse(list.isEmpty());

        AssistantMessage found = null;
        for (AssistantMessage m : list) {
            if (m.hasMedia()) {
                found = m;
                break;
            }
        }
        Assertions.assertNotNull(found);
        Assertions.assertTrue(found.isMultiModal());
        Assertions.assertTrue(found.getBlocks().stream().anyMatch(b -> b instanceof ImageBlock));
    }

    @Test
    public void dashscopeBuildAssistantMultiModalShouldUseNativeArray() {
        DashscopeChatDialect dialect = DashscopeChatDialect.getInstance();
        AssistantMessage msg = ChatMessage.ofAssistant(
                "描述",
                ImageBlock.ofUrl("https://example.com/a.png"));

        ONode node = dialect.buildChatMessageNode(new ChatConfig(), msg);
        Assertions.assertEquals("assistant", node.get("role").getString());
        Assertions.assertTrue(node.get("content").isArray());

        boolean hasImage = false;
        boolean hasText = false;
        for (ONode item : node.get("content").getArray()) {
            if (item.hasKey("image")) {
                hasImage = true;
                Assertions.assertEquals("https://example.com/a.png", item.get("image").getString());
            }
            if (item.hasKey("text")) {
                hasText = true;
            }
            // 不应是 OpenAI type/image_url
            Assertions.assertFalse(item.hasKey("type") && "image_url".equals(item.get("type").getString()));
        }
        Assertions.assertTrue(hasImage);
        Assertions.assertTrue(hasText);
    }

    @Test
    public void dashscopeBuildAssistantPlainTextShouldStayString() {
        DashscopeChatDialect dialect = DashscopeChatDialect.getInstance();
        AssistantMessage msg = ChatMessage.ofAssistant("hello");

        ONode node = dialect.buildChatMessageNode(new ChatConfig(), msg);
        Assertions.assertTrue(node.get("content").isValue());
        Assertions.assertEquals("hello", node.get("content").getString());
    }

    @Test
    public void dashscopeBuildUserPlainTextShouldStayString() {
        DashscopeChatDialect dialect = DashscopeChatDialect.getInstance();
        ONode node = dialect.buildChatMessageNode(new ChatConfig(), ChatMessage.ofUser("hello user"));

        Assertions.assertEquals("user", node.get("role").getString());
        Assertions.assertTrue(node.get("content").isValue());
        Assertions.assertEquals("hello user", node.get("content").getString());
        Assertions.assertFalse(node.get("content").isArray());
    }

    @Test
    public void dashscopeBuildUserMultiModalShouldUseNativeArray() {
        DashscopeChatDialect dialect = DashscopeChatDialect.getInstance();
        ONode node = dialect.buildChatMessageNode(
                new ChatConfig(),
                ChatMessage.ofUser("看图", ImageBlock.ofUrl("https://example.com/u.png")));

        Assertions.assertTrue(node.get("content").isArray());
        boolean hasImage = false;
        boolean hasText = false;
        for (ONode item : node.get("content").getArray()) {
            if (item.hasKey("image")) {
                hasImage = true;
            }
            if (item.hasKey("text")) {
                hasText = true;
            }
        }
        Assertions.assertTrue(hasImage);
        Assertions.assertTrue(hasText);
    }

    @Test
    public void openaiResponsesStreamImageGenerationCallShouldOnlyAddMediaBlocks() {
        OpenaiResponsesDialect dialect = OpenaiResponsesDialect.getInstance();
        ChatResponseDefault resp = newResp(true, dialect);

        String streamJson = "data: {\"type\":\"response.output_item.done\","
                + "\"item\":{\"type\":\"image_generation_call\",\"id\":\"ig_stream_1\","
                + "\"status\":\"completed\",\"result\":\"iVBORw0KGgo=\"}}";

        Assertions.assertTrue(dialect.parseResponseJson(new ChatConfig(), resp, streamJson));
        // 流式 image_generation_call 只收 media，不推空文本 choice
        Assertions.assertFalse(resp.hasChoices());
        Assertions.assertFalse(resp.getMediaBlocks().isEmpty());
        Assertions.assertTrue(resp.getMediaBlocks().stream().anyMatch(b -> b instanceof ImageBlock));
    }

    @Test
    public void openaiResponsesParseImageGenerationCall() {
        OpenaiResponsesDialect dialect = OpenaiResponsesDialect.getInstance();
        ChatResponseDefault resp = newResp(false, dialect);

        String json = "{"
                + "\"model\":\"gpt-test\","
                + "\"status\":\"completed\","
                + "\"output\":["
                + "  {\"type\":\"message\",\"content\":[{\"type\":\"output_text\",\"text\":\"done\"}]},"
                + "  {\"type\":\"image_generation_call\",\"id\":\"ig_1\",\"status\":\"completed\",\"result\":\"iVBORw0KGgo=\"}"
                + "]"
                + "}";

        Assertions.assertTrue(dialect.parseResponseJson(new ChatConfig(), resp, json));
        Assertions.assertTrue(resp.hasChoices());

        AssistantMessage msg = resp.getMessage();
        Assertions.assertNotNull(msg);
        Assertions.assertTrue(msg.isMultiModal() || msg.hasMedia());
        Assertions.assertTrue(msg.getBlocks().stream().anyMatch(b -> b instanceof ImageBlock));
        Assertions.assertTrue(msg.getContent().contains("done") || msg.getBlocks().stream().anyMatch(b -> b instanceof TextBlock));
    }

    @Test
    public void openaiResponsesBuildAssistantMultiModalHistory() {
        OpenaiResponsesDialect dialect = OpenaiResponsesDialect.getInstance();
        AssistantMessage msg = ChatMessage.ofAssistant(
                "caption",
                ImageBlock.ofUrl("https://example.com/x.png"));

        // 通过 buildRequestJson 验证 input 形态
        ChatConfig config = new ChatConfig();
        config.setModel("gpt-test");
        ONode root = dialect.buildRequestJson(
                config,
                ChatOptions.of(),
                java.util.Collections.singletonList(msg),
                false);

        Assertions.assertTrue(root.hasKey("input"));
        ONode input = root.get("input");
        Assertions.assertTrue(input.isArray());
        Assertions.assertTrue(input.getArray().size() >= 1);

        ONode first = input.get(0);
        Assertions.assertEquals("assistant", first.get("role").getString());
        Assertions.assertTrue(first.get("content").isArray());
    }

    @Test
    public void anthropicParseAssistantImageBlock() {
        AnthropicChatDialect dialect = AnthropicChatDialect.getInstance();
        ChatResponseDefault resp = newResp(false, dialect);

        String json = "{"
                + "\"model\":\"claude-test\","
                + "\"content\":["
                + "  {\"type\":\"text\",\"text\":\"see image\"},"
                + "  {\"type\":\"image\",\"source\":{\"type\":\"base64\",\"media_type\":\"image/png\",\"data\":\"iVBORw0KGgo=\"}}"
                + "],"
                + "\"stop_reason\":\"end_turn\""
                + "}";

        Assertions.assertTrue(dialect.parseResponseJson(new ChatConfig(), resp, json));
        AssistantMessage msg = resp.getMessage();
        Assertions.assertNotNull(msg);
        Assertions.assertTrue(msg.hasMedia());
        Assertions.assertTrue(msg.getBlocks().stream().anyMatch(b -> b instanceof ImageBlock));
    }

    @Test
    public void anthropicBuildAssistantMultiModal() {
        AnthropicChatDialect dialect = AnthropicChatDialect.getInstance();
        AssistantMessage msg = ChatMessage.ofAssistant(
                "look",
                ImageBlock.ofBase64("iVBORw0KGgo=", "image/png"));

        ChatConfig config = new ChatConfig();
        config.setModel("claude-test");
        ONode root = dialect.buildRequestJson(
                config,
                ChatOptions.of(),
                java.util.Collections.singletonList(msg),
                false);

        Assertions.assertTrue(root.hasKey("messages"));
        ONode messages = root.get("messages");
        Assertions.assertTrue(messages.isArray());
        ONode assistant = messages.get(0);
        Assertions.assertEquals("assistant", assistant.get("role").getString());
        Assertions.assertTrue(assistant.get("content").isArray());

        boolean hasImage = false;
        for (ONode item : assistant.get("content").getArray()) {
            if ("image".equals(item.get("type").getString())) {
                hasImage = true;
            }
        }
        Assertions.assertTrue(hasImage);
    }

    @Test
    public void geminiParseInlineDataAsImageBlock() {
        GeminiChatDialect dialect = GeminiChatDialect.getInstance();
        ChatResponseDefault resp = newResp(false, dialect);

        String json = "{"
                + "\"candidates\":[{"
                + "  \"content\":{"
                + "    \"parts\":["
                + "      {\"text\":\"generated image\"},"
                + "      {\"inline_data\":{\"mime_type\":\"image/png\",\"data\":\"iVBORw0KGgo=\"}}"
                + "    ]"
                + "  },"
                + "  \"finishReason\":\"STOP\""
                + "}]"
                + "}";

        Assertions.assertTrue(dialect.parseResponseJson(new ChatConfig(), resp, json));
        Assertions.assertTrue(resp.hasChoices());

        boolean hasMedia = false;
        for (org.noear.solon.ai.chat.ChatChoice c : resp.getChoices()) {
            if (c.getMessage() != null && c.getMessage().hasMedia()) {
                hasMedia = true;
                Assertions.assertTrue(c.getMessage().getBlocks().stream().anyMatch(b -> b instanceof ImageBlock));
            }
        }
        Assertions.assertTrue(hasMedia);
    }

    @Test
    public void geminiBuildAssistantMultiModalParts() {
        GeminiChatDialect dialect = GeminiChatDialect.getInstance();
        AssistantMessage msg = ChatMessage.ofAssistant(
                "caption",
                ImageBlock.ofBase64("iVBORw0KGgo=", "image/png"));

        ChatConfig config = new ChatConfig();
        config.setModel("gemini-test");
        ONode root = dialect.buildRequestJson(
                config,
                ChatOptions.of(),
                java.util.Collections.singletonList(msg),
                false);

        Assertions.assertTrue(root.hasKey("contents"));
        ONode parts = root.get("contents").get(0).get("parts");
        Assertions.assertTrue(parts.isArray());

        boolean hasInline = false;
        boolean hasText = false;
        for (ONode p : parts.getArray()) {
            if (p.hasKey("inline_data") || p.hasKey("inlineData")) {
                hasInline = true;
            }
            if (p.hasKey("text")) {
                hasText = true;
            }
        }
        Assertions.assertTrue(hasText);
        Assertions.assertTrue(hasInline);
    }

    @Test
    public void geminiInteractionsParseModelOutputMedia() {
        GeminiInteractionsDialect dialect = GeminiInteractionsDialect.getInstance();
        ChatResponseDefault resp = newResp(false, dialect);

        String json = "{"
                + "\"model\":\"gemini-test\","
                + "\"status\":\"completed\","
                + "\"steps\":["
                + "  {\"type\":\"model_output\",\"content\":["
                + "    {\"type\":\"text\",\"text\":\"hi\"},"
                + "    {\"type\":\"inline_data\",\"mime_type\":\"image/png\",\"data\":\"iVBORw0KGgo=\"}"
                + "  ]}"
                + "]"
                + "}";

        Assertions.assertTrue(dialect.parseResponseJson(new ChatConfig(), resp, json));
        Assertions.assertTrue(resp.hasChoices());

        boolean hasMedia = false;
        for (org.noear.solon.ai.chat.ChatChoice c : resp.getChoices()) {
            if (c.getMessage() != null && c.getMessage().hasMedia()) {
                hasMedia = true;
            }
        }
        Assertions.assertTrue(hasMedia);
    }

    @Test
    public void openaiResponsesUserAudioUrlShouldNotWriteNullData() {
        OpenaiResponsesDialect dialect = OpenaiResponsesDialect.getInstance();
        UserMessage user = ChatMessage.ofUser(
                "listen",
                AudioBlock.ofUrl("https://example.com/a.mp3"));

        ChatConfig config = new ChatConfig();
        config.setModel("gpt-test");
        ONode root = dialect.buildRequestJson(
                config,
                ChatOptions.of(),
                java.util.Collections.singletonList(user),
                false);

        ONode content = root.get("input").get(0).get("content");
        Assertions.assertTrue(content.isArray());
        for (ONode item : content.getArray()) {
            if ("input_audio".equals(item.get("type").getString())) {
                Assertions.fail("URL audio should not be written as input_audio with null data");
            }
        }
        boolean hasTextFallback = false;
        for (ONode item : content.getArray()) {
            if ("input_text".equals(item.get("type").getString())
                    && item.get("text").getString() != null
                    && item.get("text").getString().contains("[audio]")) {
                hasTextFallback = true;
            }
        }
        Assertions.assertTrue(hasTextFallback);
    }

    @Test
    public void anthropicPlainAssistantShouldStripThinkTags() {
        AnthropicChatDialect dialect = AnthropicChatDialect.getInstance();
        AssistantMessage msg = ChatMessage.ofAssistant("<think>secret</think>visible");

        ChatConfig config = new ChatConfig();
        config.setModel("claude-test");
        ONode root = dialect.buildRequestJson(
                config,
                ChatOptions.of(),
                java.util.Collections.singletonList(msg),
                false);

        ONode assistant = root.get("messages").get(0);
        Assertions.assertTrue(assistant.get("content").isValue());
        Assertions.assertEquals("visible", assistant.get("content").getString());
    }

    @Test
    public void geminiPlainAssistantShouldStripThinkTags() {
        GeminiChatDialect dialect = GeminiChatDialect.getInstance();
        AssistantMessage msg = ChatMessage.ofAssistant("<think>secret</think>visible");

        ChatConfig config = new ChatConfig();
        config.setModel("gemini-test");
        ONode root = dialect.buildRequestJson(
                config,
                ChatOptions.of(),
                java.util.Collections.singletonList(msg),
                false);

        ONode parts = root.get("contents").get(0).get("parts");
        Assertions.assertTrue(parts.isArray());
        Assertions.assertEquals("visible", parts.get(0).get("text").getString());
    }

    @Test
    public void ollamaShouldSkipTruncatedEmptyMedia() {
        OllamaChatDialect dialect = OllamaChatDialect.getInstance();
        ImageBlock truncated = ImageBlock.ofBase64("", "image/png");
        truncated.metaAdd("storage", "external");
        truncated.metaAdd("data_truncated", true);

        AssistantMessage msg = ChatMessage.ofAssistant("caption", truncated);
        ONode node = dialect.buildChatMessageNode(new ChatConfig(), msg);

        Assertions.assertEquals("caption", node.get("content").getString());
        Assertions.assertFalse(node.hasKey("images"),
                "truncated empty media should not produce images sidecar");
    }

    @Test
    public void dashscopeShouldSkipTruncatedEmptyMedia() {
        DashscopeChatDialect dialect = DashscopeChatDialect.getInstance();
        ImageBlock truncated = ImageBlock.ofBase64("", "image/png");
        truncated.metaAdd("storage", "external");

        AssistantMessage msg = ChatMessage.ofAssistant("keep", truncated);
        ONode node = dialect.buildChatMessageNode(new ChatConfig(), msg);

        if (node.get("content").isArray()) {
            for (ONode item : node.get("content").getArray()) {
                Assertions.assertFalse(item.hasKey("image"),
                        "truncated empty media should not write image field");
            }
        } else {
            Assertions.assertEquals("keep", node.get("content").getString());
        }
    }

    @Test
    public void dashscopeUserTruncatedMediaShouldFallbackToString() {
        DashscopeChatDialect dialect = DashscopeChatDialect.getInstance();
        ImageBlock truncated = ImageBlock.ofBase64("", "image/png");
        truncated.metaAdd("storage", "external");
        truncated.metaAdd("data_truncated", true);

        // 仅截断媒体、无文本：应回退 string，避免 content: []
        UserMessage mediaOnly = ChatMessage.ofUser("", truncated);
        ONode emptyTextNode = dialect.buildChatMessageNode(new ChatConfig(), mediaOnly);
        Assertions.assertFalse(emptyTextNode.get("content").isArray(),
                "user truncated media-only should fallback to string content");

        // 有文本 + 截断媒体：文本应保留为 string 或仅含 text 的数组
        UserMessage withText = ChatMessage.ofUser("user caption", truncated);
        ONode withTextNode = dialect.buildChatMessageNode(new ChatConfig(), withText);
        if (withTextNode.get("content").isArray()) {
            boolean hasImage = false;
            boolean hasText = false;
            for (ONode item : withTextNode.get("content").getArray()) {
                if (item.hasKey("image")) {
                    hasImage = true;
                }
                if (item.hasKey("text")) {
                    hasText = true;
                    Assertions.assertEquals("user caption", item.get("text").getString());
                }
            }
            Assertions.assertFalse(hasImage);
            Assertions.assertTrue(hasText);
        } else {
            Assertions.assertEquals("user caption", withTextNode.get("content").getString());
        }
    }
    @Test
    public void anthropicParseAssistantImageAndToolUseMixed() {
        AnthropicChatDialect dialect = AnthropicChatDialect.getInstance();
        ChatResponseDefault resp = newResp(false, dialect);

        // 模拟 Claude 返回 text + image + tool_use 混合响应
        String json = "{"
                + "\"model\":\"claude-test\","
                + "\"content\":["
                + "  {\"type\":\"text\",\"text\":\"I will analyze this image\"},"
                + "  {\"type\":\"image\",\"source\":{\"type\":\"base64\",\"media_type\":\"image/png\",\"data\":\"iVBORw0KGgo=\"}},"
                + "  {\"type\":\"tool_use\",\"id\":\"tool_123\",\"name\":\"analyze\",\"input\":{\"x\":1}}"
                + "],"
                + "\"stop_reason\":\"tool_use\""
                + "}";

        Assertions.assertTrue(dialect.parseResponseJson(new ChatConfig(), resp, json));
        Assertions.assertTrue(resp.hasChoices());

        AssistantMessage msg = resp.getMessage();
        Assertions.assertNotNull(msg);
        // 应同时有媒requir媒体块和工具调用
        Assertions.assertTrue(msg.hasMedia(), "image block should be preserved alongside tool_use");
        Assertions.assertTrue(msg.getBlocks().stream().anyMatch(b -> b instanceof ImageBlock),
                "blocks should contain ImageBlock");
        Assertions.assertFalse(msg.getToolCalls().isEmpty(), "toolCalls should be parsed");
        Assertions.assertEquals("analyze", msg.getToolCalls().get(0).getName());
        // 文本也应保留
        Assertions.assertTrue(msg.getContent().contains("I will analyze this image"));
    }

    // ==================== 新增：OpenAI Chat 方言专属测试 ====================

    @Test
    public void openaiChatBuildAssistantMultiModalShouldUseContentArray() {
        OpenaiChatDialect dialect = OpenaiChatDialect.getInstance();
        AssistantMessage msg = ChatMessage.ofAssistant(
                "see this",
                ImageBlock.ofUrl("https://example.com/a.png"));

        ONode node = dialect.buildChatMessageNode(new ChatConfig(), msg);
        Assertions.assertEquals("assistant", node.get("role").getString());
        Assertions.assertTrue(node.get("content").isArray());

        boolean hasText = false, hasImage = false;
        for (ONode item : node.get("content").getArray()) {
            if ("text".equals(item.get("type").getString())) hasText = true;
            if ("image_url".equals(item.get("type").getString())) hasImage = true;
        }
        Assertions.assertTrue(hasText);
        Assertions.assertTrue(hasImage);
    }

    @Test
    public void openaiChatBuildAssistantPlainTextShouldStayString() {
        OpenaiChatDialect dialect = OpenaiChatDialect.getInstance();
        AssistantMessage msg = ChatMessage.ofAssistant("hello");

        ONode node = dialect.buildChatMessageNode(new ChatConfig(), msg);
        Assertions.assertEquals("assistant", node.get("role").getString());
        Assertions.assertTrue(node.get("content").isValue());
        Assertions.assertEquals("hello", node.get("content").getString());
    }

    @Test
    public void openaiChatBuildAssistantWithVideoShouldEmitVideoUrl() {
        OpenaiChatDialect dialect = OpenaiChatDialect.getInstance();
        AssistantMessage msg = ChatMessage.ofAssistant(
                "watch this",
                VideoBlock.ofUrl("https://example.com/v.mp4"));

        ONode node = dialect.buildChatMessageNode(new ChatConfig(), msg);
        Assertions.assertTrue(node.get("content").isArray());
        boolean hasVideo = false;
        for (ONode item : node.get("content").getArray()) {
            if ("video_url".equals(item.get("type").getString())) {
                hasVideo = true;
                Assertions.assertEquals("https://example.com/v.mp4",
                        item.get("video_url").get("url").getString());
            }
        }
        Assertions.assertTrue(hasVideo);
    }

    @Test
    public void openaiChatBuildAssistantWithAudioIdShouldUseSidecar() {
        OpenaiChatDialect dialect = OpenaiChatDialect.getInstance();
        AudioBlock audio = AudioBlock.ofUrl("https://example.com/a.mp3");
        audio.metaAdd("audio_id", "audio_abc123");
        AssistantMessage msg = ChatMessage.ofAssistant("listen", audio);

        ONode node = dialect.buildChatMessageNode(new ChatConfig(), msg);
        // audio_id 存在时走侧车，不出现在 content 数组
        Assertions.assertTrue(node.get("content").isArray());
        boolean hasAudioUrl = false;
        for (ONode item : node.get("content").getArray()) {
            if ("audio_url".equals(item.get("type").getString())) hasAudioUrl = true;
        }
        Assertions.assertFalse(hasAudioUrl, "audio_id should use sidecar not content array");
        Assertions.assertTrue(node.hasKey("audio"));
        Assertions.assertEquals("audio_abc123", node.get("audio").get("id").getString());
    }

    @Test
    public void openaiChatParseAssistantVideoUrlContent() {
        OpenaiChatDialect dialect = OpenaiChatDialect.getInstance();
        ChatResponseDefault resp = newResp(false, dialect);

        String json = "{"
                + "\"model\":\"gpt-test\","
                + "\"choices\":[{\"index\":0,\"finish_reason\":\"stop\",\"message\":{"
                + "  \"role\":\"assistant\","
                + "  \"content\":["
                + "    {\"type\":\"text\",\"text\":\"here is a video\"},"
                + "    {\"type\":\"video_url\",\"video_url\":{\"url\":\"https://example.com/v.mp4\"}}"
                + "  ]"
                + "}}]"
                + "}";

        Assertions.assertTrue(dialect.parseResponseJson(new ChatConfig(), resp, json));
        Assertions.assertTrue(resp.hasChoices());
        AssistantMessage msg = resp.getMessage();
        Assertions.assertNotNull(msg);
        Assertions.assertTrue(msg.hasMedia());
        Assertions.assertTrue(msg.getBlocks().stream().anyMatch(b -> b instanceof VideoBlock));
    }

    // ==================== 新增：Ollama audio/video 倒车解析 ====================

    @Test
    public void ollamaParseAssistantShouldReadAudiosSidecar() {
        OllamaChatDialect dialect = OllamaChatDialect.getInstance();
        ChatResponseDefault resp = newResp(false, dialect);

        ONode oMessage = ONode.ofJson("{"
                + "\"role\":\"assistant\","
                + "\"content\":\"audio generated\","
                + "\"audios\":[\"UklGRiQ=\"]"
                + "}");

        java.util.List<AssistantMessage> list = dialect.parseAssistantMessage(resp, oMessage);
        Assertions.assertFalse(list.isEmpty());

        AssistantMessage found = null;
        for (AssistantMessage m : list) {
            if (m.hasMedia()) { found = m; break; }
        }
        Assertions.assertNotNull(found);
        Assertions.assertTrue(found.getBlocks().stream().anyMatch(b -> b instanceof AudioBlock),
                "blocks should contain AudioBlock from audios sidecar");
    }

    @Test
    public void ollamaParseAssistantShouldReadVideosSidecar() {
        OllamaChatDialect dialect = OllamaChatDialect.getInstance();
        ChatResponseDefault resp = newResp(false, dialect);

        ONode oMessage = ONode.ofJson("{"
                + "\"role\":\"assistant\","
                + "\"content\":\"video generated\","
                + "\"videos\":[\"AAAAIGZ0\"]"
                + "}");

        java.util.List<AssistantMessage> list = dialect.parseAssistantMessage(resp, oMessage);
        Assertions.assertFalse(list.isEmpty());

        AssistantMessage found = null;
        for (AssistantMessage m : list) {
            if (m.hasMedia()) { found = m; break; }
        }
        Assertions.assertNotNull(found);
        Assertions.assertTrue(found.getBlocks().stream().anyMatch(b -> b instanceof VideoBlock),
                "blocks should contain VideoBlock from videos sidecar");
    }

    // ==================== 新增：DashScope audio/video 构建及响应解析 ====================

    @Test
    public void dashscopeBuildAssistantShouldUseAudioField() {
        DashscopeChatDialect dialect = DashscopeChatDialect.getInstance();
        AssistantMessage msg = ChatMessage.ofAssistant(
                "listen",
                AudioBlock.ofUrl("https://example.com/a.mp3"));

        ONode node = dialect.buildChatMessageNode(new ChatConfig(), msg);
        Assertions.assertTrue(node.get("content").isArray());
        boolean hasAudio = false, hasText = false;
        for (ONode item : node.get("content").getArray()) {
            if (item.hasKey("audio")) {
                hasAudio = true;
                Assertions.assertEquals("https://example.com/a.mp3", item.get("audio").getString());
            }
            if (item.hasKey("text")) hasText = true;
        }
        Assertions.assertTrue(hasAudio);
        Assertions.assertTrue(hasText);
    }

    @Test
    public void dashscopeBuildAssistantShouldUseVideoField() {
        DashscopeChatDialect dialect = DashscopeChatDialect.getInstance();
        AssistantMessage msg = ChatMessage.ofAssistant(
                "watch",
                VideoBlock.ofUrl("https://example.com/v.mp4"));

        ONode node = dialect.buildChatMessageNode(new ChatConfig(), msg);
        Assertions.assertTrue(node.get("content").isArray());
        boolean hasVideo = false;
        for (ONode item : node.get("content").getArray()) {
            if (item.hasKey("video")) {
                hasVideo = true;
                Assertions.assertEquals("https://example.com/v.mp4", item.get("video").getString());
            }
        }
        Assertions.assertTrue(hasVideo);
    }

    @Test
    public void dashscopeParseAssistantImageContentArray() {
        DashscopeChatDialect dialect = DashscopeChatDialect.getInstance();
        ChatResponseDefault resp = newResp(false, dialect);

        // DashScope 服务端返回 result_format=message 时的 OpenAI 兼容 content 数组
        String json = "{"
                + "\"output\":{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{"
                + "  \"role\":\"assistant\","
                + "  \"content\":["
                + "    {\"type\":\"text\",\"text\":\"generated\"},"
                + "    {\"type\":\"image_url\",\"image_url\":{\"url\":\"https://example.com/gen.png\"}}"
                + "  ]"
                + "}}]}"
                + "}";

        Assertions.assertTrue(dialect.parseResponseJson(new ChatConfig(), resp, json));
        Assertions.assertTrue(resp.hasChoices());
        AssistantMessage msg = resp.getMessage();
        Assertions.assertNotNull(msg);
        Assertions.assertTrue(msg.hasMedia());
        Assertions.assertTrue(msg.getBlocks().stream().anyMatch(b -> b instanceof ImageBlock));
    }

    // ==================== 新增：Anthropic URL image 响应解析 + 请求构建混合 ====================

    @Test
    public void anthropicParseAssistantImageUrlBlock() {
        AnthropicChatDialect dialect = AnthropicChatDialect.getInstance();
        ChatResponseDefault resp = newResp(false, dialect);

        String json = "{"
                + "\"model\":\"claude-test\","
                + "\"content\":"
                + "  [{\"type\":\"text\",\"text\":\"see url image\"},"
                + "   {\"type\":\"image\",\"source\":{\"type\":\"url\",\"url\":\"https://example.com/u.png\"}}]"
                + ",\"stop_reason\":\"end_turn\""
                + "}";

        Assertions.assertTrue(dialect.parseResponseJson(new ChatConfig(), resp, json));
        AssistantMessage msg = resp.getMessage();
        Assertions.assertNotNull(msg);
        Assertions.assertTrue(msg.hasMedia());
        Assertions.assertTrue(msg.getBlocks().stream().anyMatch(b -> b instanceof ImageBlock));
    }

    @Test
    public void anthropicBuildAssistantImageAndToolUseMixed() {
        AnthropicChatDialect dialect = AnthropicChatDialect.getInstance();

        // 构造一个含 image + toolCalls 的 AssistantMessage
        java.util.List<org.noear.solon.ai.chat.tool.ToolCall> toolCalls = java.util.Collections.singletonList(
                new org.noear.solon.ai.chat.tool.ToolCall("get_weather", "call_1", "get_weather", "{\"city\":\"Paris\"}", null));
        java.util.Map<String, Object> funcMap = new java.util.HashMap<>();
        funcMap.put("name", "get_weather");
        funcMap.put("arguments", "{\"city\":\"Paris\"}");
        java.util.Map<String, Object> rawMap = new java.util.HashMap<>();
        rawMap.put("id", "call_1");
        rawMap.put("type", "function");
        rawMap.put("function", funcMap);
        java.util.List<java.util.Map> toolCallsRaw = java.util.Collections.singletonList(rawMap);

        AssistantMessage msg = new AssistantMessage(
                "I will check", false, "I will check",
                toolCallsRaw, toolCalls, null,
                java.util.Arrays.asList(
                        TextBlock.of("I will check"),
                        ImageBlock.ofUrl("https://example.com/a.png")));

        ChatConfig config = new ChatConfig();
        config.setModel("claude-test");
        ONode root = dialect.buildRequestJson(
                config, ChatOptions.of(),
                java.util.Collections.singletonList(msg), false);

        ONode assistant = root.get("messages").get(0);
        Assertions.assertEquals("assistant", assistant.get("role").getString());
        Assertions.assertTrue(assistant.get("content").isArray());

        boolean hasText = false, hasImage = false, hasToolUse = false;
        for (ONode item : assistant.get("content").getArray()) {
            String type = item.get("type").getString();
            if ("text".equals(type)) hasText = true;
            if ("image".equals(type)) hasImage = true;
            if ("tool_use".equals(type)) hasToolUse = true;
        }
        Assertions.assertTrue(hasText, "content should contain text block");
        Assertions.assertTrue(hasImage, "content should contain image block");
        Assertions.assertTrue(hasToolUse, "content should contain tool_use block");
    }

    @Test
    public void anthropicParseThinkingToolUseShouldKeepSignatureAndStopReason() {
        AnthropicChatDialect dialect = AnthropicChatDialect.getInstance();
        ChatResponseDefault resp = newResp(false, dialect);

        String json = "{"
                + "\"model\":\"claude-test\","
                + "\"content\":["
                + "  {\"type\":\"thinking\",\"thinking\":\"need tool\",\"signature\":\"sig-abc\"},"
                + "  {\"type\":\"tool_use\",\"id\":\"call_1\",\"name\":\"spotIntro\",\"input\":{}}"
                + "],"
                + "\"stop_reason\":\"tool_use\""
                + "}";

        Assertions.assertTrue(dialect.parseResponseJson(new ChatConfig(), resp, json));
        Assertions.assertTrue(resp.hasChoices());

        AssistantMessage msg = resp.getMessage();
        Assertions.assertNotNull(msg);
        Assertions.assertEquals("tool_use", resp.getChoices().get(0).getFinishReason());
        Assertions.assertEquals("tool_use", resp.lastFinishReason);
        Assertions.assertEquals("need tool", msg.getReasoning());
        Assertions.assertTrue(msg.getContentRaw() instanceof java.util.Map);
        Assertions.assertEquals("sig-abc",
                ((java.util.Map<?, ?>) msg.getContentRaw()).get("thinkingSignature"));
        Assertions.assertFalse(msg.getToolCalls().isEmpty());
        Assertions.assertEquals("spotIntro", msg.getToolCalls().get(0).getName());
    }

    @Test
    public void anthropicBuildThinkingToolUseShouldSkipBlankTextAndEmptySignature() {
        AnthropicChatDialect dialect = AnthropicChatDialect.getInstance();

        java.util.List<org.noear.solon.ai.chat.tool.ToolCall> toolCalls = java.util.Collections.singletonList(
                new org.noear.solon.ai.chat.tool.ToolCall("call_1", "call_1", "spotIntro", "{}", java.util.Collections.emptyMap()));
        java.util.Map<String, Object> funcMap = new java.util.HashMap<>();
        funcMap.put("name", "spotIntro");
        funcMap.put("arguments", "{}");
        java.util.Map<String, Object> rawMap = new java.util.HashMap<>();
        rawMap.put("id", "call_1");
        rawMap.put("type", "function");
        rawMap.put("function", funcMap);
        java.util.List<java.util.Map> toolCallsRaw = java.util.Collections.singletonList(rawMap);

        // 模拟非流式：thinking 后无正文，stripThinkTags 会留下 "\n\n"
        // 无有效 signature 时，tool 多轮不应回传 thinking（兼容网关 EMPTY_RESPONSE）
        java.util.Map<String, Object> contentRaw = new java.util.LinkedHashMap<>();
        contentRaw.put("thinking", "need tool");
        contentRaw.put("thinkingSignature", ""); // 空 signature 视为无效

        AssistantMessage msg = new AssistantMessage(
                "<think>\n\nneed tool</think>\n\n",
                false, contentRaw, toolCallsRaw, toolCalls, null, null);

        ChatConfig config = new ChatConfig();
        config.setModel("claude-test");
        ONode root = dialect.buildRequestJson(
                config, ChatOptions.of(),
                java.util.Collections.singletonList(msg), false);

        ONode assistant = root.get("messages").get(0);
        Assertions.assertEquals("assistant", assistant.get("role").getString());
        Assertions.assertTrue(assistant.get("content").isArray());

        boolean hasThinking = false;
        boolean hasBlankText = false;
        boolean hasToolUse = false;
        for (ONode item : assistant.get("content").getArray()) {
            String type = item.get("type").getString();
            if ("thinking".equals(type)) {
                hasThinking = true;
            } else if ("text".equals(type)) {
                String text = item.get("text").getString();
                if (text == null || text.trim().isEmpty()) {
                    hasBlankText = true;
                }
            } else if ("tool_use".equals(type)) {
                hasToolUse = true;
                Assertions.assertEquals("spotIntro", item.get("name").getString());
            }
        }

        Assertions.assertFalse(hasThinking, "thinking without valid signature must not be written back");
        Assertions.assertTrue(hasToolUse, "content should contain tool_use block");
        Assertions.assertFalse(hasBlankText, "blank text block must not be written");
    }

    @Test
    public void anthropicBuildThinkingToolUseShouldWriteValidSignature() {
        AnthropicChatDialect dialect = AnthropicChatDialect.getInstance();

        java.util.List<org.noear.solon.ai.chat.tool.ToolCall> toolCalls = java.util.Collections.singletonList(
                new org.noear.solon.ai.chat.tool.ToolCall("call_2", "call_2", "currentTime", "{}", java.util.Collections.emptyMap()));
        java.util.Map<String, Object> funcMap = new java.util.HashMap<>();
        funcMap.put("name", "currentTime");
        funcMap.put("arguments", "{}");
        java.util.Map<String, Object> rawMap = new java.util.HashMap<>();
        rawMap.put("id", "call_2");
        rawMap.put("type", "function");
        rawMap.put("function", funcMap);
        java.util.List<java.util.Map> toolCallsRaw = java.util.Collections.singletonList(rawMap);

        java.util.Map<String, Object> contentRaw = new java.util.LinkedHashMap<>();
        contentRaw.put("thinking", "call time tool");
        contentRaw.put("thinkingSignature", "sig-valid-001");

        AssistantMessage msg = new AssistantMessage(
                "<think>\n\ncall time tool</think>\n\n",
                false, contentRaw, toolCallsRaw, toolCalls, null, null);

        ChatConfig config = new ChatConfig();
        config.setModel("claude-test");
        ONode root = dialect.buildRequestJson(
                config, ChatOptions.of(),
                java.util.Collections.singletonList(msg), false);

        ONode assistant = root.get("messages").get(0);
        boolean foundSignature = false;
        for (ONode item : assistant.get("content").getArray()) {
            if ("thinking".equals(item.get("type").getString())) {
                Assertions.assertEquals("sig-valid-001", item.get("signature").getString());
                foundSignature = true;
            }
            if ("text".equals(item.get("type").getString())) {
                Assertions.fail("blank text block must not be written when only thinking exists");
            }
        }
        Assertions.assertTrue(foundSignature, "valid signature should be written back");
    }

    @Test
    public void anthropicParseNonStreamShouldAcceptPlainTextGatewayError() {
        AnthropicChatDialect dialect = AnthropicChatDialect.getInstance();
        ChatResponseDefault resp = newResp(false, dialect);
    
        // 网关常直接返回纯文本："error code: 502"（不是 JSON）
        Assertions.assertTrue(dialect.parseResponseJson(new ChatConfig(), resp, "error code: 502"));
        Assertions.assertNotNull(resp.getError());
        Assertions.assertTrue(resp.getError().getMessage().contains("error code: 502"));
        Assertions.assertFalse(resp.hasChoices());
    }
    
    @Test
    public void anthropicParseNonStreamShouldAcceptBadGatewayText() {
        AnthropicChatDialect dialect = AnthropicChatDialect.getInstance();
        ChatResponseDefault resp = newResp(false, dialect);
    
        Assertions.assertTrue(dialect.parseResponseJson(new ChatConfig(), resp, "error code: 502"));
        Assertions.assertNotNull(resp.getError());
        Assertions.assertTrue(resp.getError().getMessage().contains("502"));
    }
    
    @Test
    public void anthropicParseStreamShouldAcceptPlainTextGatewayError() {
        AnthropicChatDialect dialect = AnthropicChatDialect.getInstance();
        ChatResponseDefault resp = newResp(true, dialect);
    
        Assertions.assertTrue(dialect.parseResponseJson(new ChatConfig(), resp, "error code: 502"));
        Assertions.assertNotNull(resp.getError());
        Assertions.assertTrue(resp.getError().getMessage().contains("error code: 502"));
    }
    
    // ==================== 新增：Gemini Chat file_data 构建/解析 + audio/video inline_data ====================

    @Test
    public void geminiBuildAssistantFileDataShouldUseFileUri() {
        GeminiChatDialect dialect = GeminiChatDialect.getInstance();
        AssistantMessage msg = ChatMessage.ofAssistant(
                "caption",
                ImageBlock.ofUrl("https://example.com/cloud-file.png"));

        ChatConfig config = new ChatConfig();
        config.setModel("gemini-test");
        ONode root = dialect.buildRequestJson(
                config, ChatOptions.of(),
                java.util.Collections.singletonList(msg), false);

        ONode parts = root.get("contents").get(0).get("parts");
        Assertions.assertTrue(parts.isArray());

        boolean hasFileData = false;
        for (ONode p : parts.getArray()) {
            if (p.hasKey("file_data") || p.hasKey("fileData")) {
                hasFileData = true;
                ONode fd = p.hasKey("file_data") ? p.get("file_data") : p.get("fileData");
                Assertions.assertEquals("https://example.com/cloud-file.png", fd.get("file_uri").getString());
            }
        }
        Assertions.assertTrue(hasFileData, "URL image should produce file_data");
    }

    @Test
    public void geminiParseInlineDataAudioShouldCreateAudioBlock() {
        GeminiChatDialect dialect = GeminiChatDialect.getInstance();
        ChatResponseDefault resp = newResp(false, dialect);

        String json = "{"
                + "\"candidates\":[{"
                + "  \"content\":{"
                + "    \"parts\":["
                + "      {\"text\":\"audio result\"},"
                + "      {\"inline_data\":{\"mime_type\":\"audio/mpeg\",\"data\":\"UklGRiQ=\"}}"
                + "    ]"
                + "  },"
                + "  \"finishReason\":\"STOP\""
                + "}]"
                + "}";

        Assertions.assertTrue(dialect.parseResponseJson(new ChatConfig(), resp, json));
        Assertions.assertTrue(resp.hasChoices());

        boolean hasAudio = false;
        for (org.noear.solon.ai.chat.ChatChoice c : resp.getChoices()) {
            if (c.getMessage() != null && c.getMessage().hasMedia()) {
                hasAudio = c.getMessage().getBlocks().stream().anyMatch(b -> b instanceof AudioBlock);
            }
        }
        Assertions.assertTrue(hasAudio, "audio/mpeg inline_data should produce AudioBlock");
    }

    @Test
    public void geminiParseInlineDataVideoShouldCreateVideoBlock() {
        GeminiChatDialect dialect = GeminiChatDialect.getInstance();
        ChatResponseDefault resp = newResp(false, dialect);

        String json = "{"
                + "\"candidates\":[{"
                + "  \"content\":{"
                + "    \"parts\":["
                + "      {\"text\":\"video result\"},"
                + "      {\"inline_data\":{\"mime_type\":\"video/mp4\",\"data\":\"AAAAIGZ0\"}}"
                + "    ]"
                + "  },"
                + "  \"finishReason\":\"STOP\""
                + "}]"
                + "}";

        Assertions.assertTrue(dialect.parseResponseJson(new ChatConfig(), resp, json));
        Assertions.assertTrue(resp.hasChoices());

        boolean hasVideo = false;
        for (org.noear.solon.ai.chat.ChatChoice c : resp.getChoices()) {
            if (c.getMessage() != null && c.getMessage().hasMedia()) {
                hasVideo = c.getMessage().getBlocks().stream().anyMatch(b -> b instanceof VideoBlock);
            }
        }
        Assertions.assertTrue(hasVideo, "video/mp4 inline_data should produce VideoBlock");
    }

    // ==================== 新增：Gemini Interactions 请求构建 ====================

    @Test
    public void geminiInteractionsBuildAssistantWithMedia() {
        GeminiInteractionsDialect dialect = GeminiInteractionsDialect.getInstance();
        AssistantMessage msg = ChatMessage.ofAssistant(
                "here is media",
                ImageBlock.ofBase64("iVBORw0KGgo=", "image/png"));

        ChatConfig config = new ChatConfig();
        config.setModel("gemini-test");
        ONode root = dialect.buildRequestJson(
                config, ChatOptions.of(),
                java.util.Collections.singletonList(msg), false);

        Assertions.assertTrue(root.hasKey("input"), "should have input array");
        ONode inputArr = root.get("input");
        Assertions.assertTrue(inputArr.isArray());
        Assertions.assertFalse(inputArr.getArray().isEmpty(), "input should not be empty");

        String json = root.toJson();
        Assertions.assertTrue(json.contains("inline_data") || json.contains("inlineData"),
                "should contain inline_data for base64 image");
        Assertions.assertTrue(json.contains("iVBORw0KGgo="),
                "should contain the base64 data");
    }

    // ==================== 新增：OpenAI Responses base64 input_image/input_audio 构建 ====================

    @Test
    public void openaiResponsesBuildAssistantBase64ImageShouldUseInputImage() {
        OpenaiResponsesDialect dialect = OpenaiResponsesDialect.getInstance();
        AssistantMessage msg = ChatMessage.ofAssistant(
                "caption",
                ImageBlock.ofBase64("iVBORw0KGgo=", "image/png"));

        ChatConfig config = new ChatConfig();
        config.setModel("gpt-test");
        ONode root = dialect.buildRequestJson(
                config, ChatOptions.of(),
                java.util.Collections.singletonList(msg), false);

        ONode content = root.get("input").get(0).get("content");
        Assertions.assertTrue(content.isArray());
        boolean hasInputImage = false;
        for (ONode item : content.getArray()) {
            if ("input_image".equals(item.get("type").getString())) {
                hasInputImage = true;
                Assertions.assertNotNull(item.get("image_url").getString());
                Assertions.assertTrue(item.get("image_url").getString().contains("iVBORw0KGgo="));
            }
        }
        Assertions.assertTrue(hasInputImage, "base64 ImageBlock should produce input_image");
    }

    @Test
    public void openaiResponsesBuildAssistantBase64AudioShouldUseInputAudio() {
        OpenaiResponsesDialect dialect = OpenaiResponsesDialect.getInstance();
        AssistantMessage msg = ChatMessage.ofAssistant(
                "listen",
                AudioBlock.ofBase64("UklGRiQ=", "audio/wav"));

        ChatConfig config = new ChatConfig();
        config.setModel("gpt-test");
        ONode root = dialect.buildRequestJson(
                config, ChatOptions.of(),
                java.util.Collections.singletonList(msg), false);

        ONode content = root.get("input").get(0).get("content");
        Assertions.assertTrue(content.isArray());
        boolean hasInputAudio = false;
        for (ONode item : content.getArray()) {
            if ("input_audio".equals(item.get("type").getString())) {
                hasInputAudio = true;
                Assertions.assertEquals("UklGRiQ=", item.get("data").getString());
                Assertions.assertEquals("wav", item.get("format").getString());
            }
        }
        Assertions.assertTrue(hasInputAudio, "base64 AudioBlock should produce input_audio with format");
    }
}

