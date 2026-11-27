/*
 * Copyright 2017-2026 noear.org and authors
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
import org.noear.solon.ai.chat.content.AbsMedia;
import org.noear.solon.ai.chat.content.AudioBlock;
import org.noear.solon.ai.chat.content.ContentBlock;
import org.noear.solon.ai.chat.content.ImageBlock;
import org.noear.solon.ai.chat.content.TextBlock;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.message.ToolMessage;
import org.noear.solon.ai.chat.message.UserMessage;
import org.noear.solon.ai.chat.tool.ToolResult;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 工具结果里的媒体：OpenAI Chat（缺省挪到用户消息）与 Responses（缺省原样）
 */
public class ToolResultMediaTest {
    static final String DATA = "iVBORw0KGgoAAAANSUhEUg==";

    static ToolMessage imageTool(String callId) {
        ToolResult result = ToolResult.success("<path>docs/a.png</path>")
                .addBlock(ImageBlock.ofBase64(DATA, "image/png").filePath("docs/a.png"));
        return new ToolMessage(result, "read", callId, false);
    }

    static List<ChatMessage> history() {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.ofUser("看看这张图"));
        messages.add(imageTool("call_1"));
        messages.add(ChatMessage.ofTool("ok", "ls", "call_2"));
        messages.add(ChatMessage.ofUser("继续"));
        return messages;
    }

    private static ChatConfig chatConfig() {
        ChatConfig config = new ChatConfig();
        config.setApiUrl("https://api.openai.com/v1");
        config.setModel("gpt-4o");
        return config;
    }

    /**
     * prepareRequestMedia 是方言内部步骤（protected），测试经子类调用；
     * 媒体留在工具结果里，便于按消息位置核对总量控制（挪到用户消息另有用例）
     */
    private static final class Probe extends OpenaiChatDialect {
        List<ChatMessage> run(ChatConfig config, List<ChatMessage> messages) {
            return prepareRequestMedia(config, messages);
        }

        @Override
        protected boolean keepsToolResultMedia(ChatConfig config, AbsMedia<?> media) {
            return true;
        }
    }

    private static final Probe PROBE = new Probe();

    private static List<ChatMessage> prepare(ChatConfig config, List<ChatMessage> messages) {
        return PROBE.run(config, messages);
    }

    private static ONode buildChat(ChatConfig config, List<ChatMessage> messages) {
        return OpenaiChatDialect.getInstance().buildRequestJson(config, ChatOptions.of(), messages, false);
    }

    private static List<ChatMessage> manyImages(int n, String data) {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.ofUser("看图"));
        for (int i = 0; i < n; i++) {
            ToolResult result = ToolResult.success("<path>p" + i + ".png</path>")
                    .addBlock(ImageBlock.ofBase64(data, "image/png").filePath("p" + i + ".png"));
            messages.add(new ToolMessage(result, "read", "call_" + i, false));
        }
        return messages;
    }

    private static boolean hasImage(ChatMessage msg) {
        List<ContentBlock> blocks = msg instanceof ToolMessage ? ((ToolMessage) msg).getBlocks() : ((UserMessage) msg).getBlocks();
        for (ContentBlock block : blocks) {
            if (block instanceof ImageBlock) {
                return true;
            }
        }
        return false;
    }

    private static String textOf(ChatMessage msg) {
        StringBuilder buf = new StringBuilder();
        for (ContentBlock block : ((UserMessage) msg).getBlocks()) {
            if (block instanceof TextBlock) {
                buf.append(block.getContent()).append('\n');
            }
        }
        return buf.toString();
    }

    private static List<Integer> keptImages(List<ChatMessage> messages) {
        List<Integer> kept = new ArrayList<>();
        for (int i = 1; i < messages.size(); i++) {
            if (hasImage(messages.get(i))) {
                kept.add(i - 1);
            }
        }
        return kept;
    }

    @Test
    void imageCountOverLimitDropsOldestInWholeSteps() {
        ChatConfig config = chatConfig();

        List<ChatMessage> hundred = manyImages(100, DATA);
        assertSame(hundred, prepare(config, hundred));

        // 101 张：整段删 8 张（步长 8），留 93 张
        List<ChatMessage> out = prepare(config, manyImages(101, DATA));
        assertEquals(93, keptImages(out).size());
        assertEquals(8, (int) keptImages(out).get(0));
        ToolMessage first = (ToolMessage) out.get(1);
        assertTrue(first.getContent().startsWith("<path>p0.png</path>"), first.getContent());
        assertTrue(first.getContent().contains("[image removed from this request to save context: p0.png; read it again"), first.getContent());
        assertTrue(first.getContent().contains("Do not make claims about its contents from memory"), first.getContent());
        assertEquals("call_0", first.getToolCallId());

        // 102~108 张：切点不动（仍删前 8 张），前缀稳定
        assertEquals(8, (int) keptImages(prepare(config, manyImages(108, DATA))).get(0));
        // 109 张：再前移一整段
        assertEquals(16, (int) keptImages(prepare(config, manyImages(109, DATA))).get(0));
    }

    @Test
    void imageBytesOverLimitDropOldestInWholeSteps() {
        ChatConfig config = chatConfig();
        char[] mb = new char[1024 * 1024];
        Arrays.fill(mb, 'A');
        String data = new String(mb);

        // 20 × 1M = 20M 未超限：不动
        List<ChatMessage> fit = manyImages(20, data);
        assertSame(fit, prepare(config, fit));

        // 只超体积（7 × 4M = 28M，不足一个步长）：删到只剩最新一张
        char[] four = new char[4 * 1024 * 1024];
        Arrays.fill(four, 'A');
        List<ChatMessage> out = prepare(config, manyImages(7, new String(four)));
        assertEquals(Collections.singletonList(6), keptImages(out));

        // 切点稳定：16 × 2M = 32M 与 17 × 2M = 34M 都只删前 8 张（原先删到 16M 低水位，后者会多删一张）
        List<Integer> a = keptImages(prepare(config, manyImages(16, new String(mb).concat(new String(mb)))));
        List<Integer> b = keptImages(prepare(config, manyImages(17, new String(mb).concat(new String(mb)))));
        assertEquals(8, (int) a.get(0));
        assertEquals(a.get(0), b.get(0));
    }

    @Test
    void userAttachmentsCountTowardsTheLimit() {
        ChatConfig config = chatConfig();
        List<ChatMessage> messages = new ArrayList<>();
        for (int i = 0; i < 101; i++) {
            messages.add(ChatMessage.ofUser("img " + i, ImageBlock.ofBase64(DATA, "image/png")));
        }

        List<ChatMessage> out = prepare(config, messages);
        String first = textOf(out.get(0));
        assertFalse(hasImage(out.get(0)));
        assertTrue(first.contains("image removed from this request"), first);
        assertTrue(first.contains("img 0"), first);
        assertFalse(hasImage(out.get(7)));
        assertTrue(hasImage(out.get(8)));
    }

    @Test
    void chatMovesImageToUserMessageAfterToolRun() {
        List<ChatMessage> messages = history();
        ONode req = buildChat(chatConfig(), messages);
        ONode msgs = req.get("messages");

        assertEquals(5, msgs.size(), req.toJson());
        assertEquals("user", msgs.get(0).get("role").getString());
        assertEquals("tool", msgs.get(1).get("role").getString());
        assertEquals("tool", msgs.get(2).get("role").getString());
        assertEquals("user", msgs.get(3).get("role").getString());
        assertEquals("user", msgs.get(4).get("role").getString());

        // 工具消息只剩文本（字符串），并提示附件在下一条用户消息里
        ONode tool = msgs.get(1).get("content");
        assertTrue(tool.isString(), req.toJson());
        assertTrue(tool.getString().contains("<path>docs/a.png</path>"));
        assertTrue(tool.getString().contains("next user message"));
        assertEquals("call_1", msgs.get(1).get("tool_call_id").getString());

        // 插入的用户消息排在整段工具结果之后、下一条用户消息之前
        ONode attached = msgs.get(3).get("content");
        assertTrue(attached.isArray());
        assertEquals("text", attached.get(0).get("type").getString());
        assertTrue(attached.get(0).get("text").getString().contains("read, call_id=call_1"));
        assertEquals("image_url", attached.get(1).get("type").getString());
        assertEquals("data:image/png;base64," + DATA, attached.get(1).get("image_url").get("url").getString());
        assertEquals("继续", msgs.get(4).get("content").getString());

        // 不修改传入的历史
        assertEquals(4, messages.size());
        assertTrue(((ToolMessage) messages.get(1)).isMultiModal());
    }

    @Test
    void chatTrailingToolRunStillGetsUserMessage() {
        List<ChatMessage> messages = new ArrayList<>(history().subList(0, 3));
        ONode msgs = buildChat(chatConfig(), messages).get("messages");

        assertEquals(4, msgs.size());
        assertEquals("user", msgs.get(3).get("role").getString());
        assertEquals("image_url", msgs.get(3).get("content").get(1).get("type").getString());
    }

    @Test
    void mistralGetsAssistantBridgeBeforeMovedMedia() {
        // Mistral 不允许 tool 后直接跟 user：先插 assistant "Done."（opencode 同口径）
        ChatConfig config = chatConfig();
        config.setModel("mistral-large-latest");
        ONode msgs = buildChat(config, history()).get("messages");

        assertEquals(6, msgs.size(), msgs.toJson());
        assertEquals("tool", msgs.get(2).get("role").getString());
        assertEquals("assistant", msgs.get(3).get("role").getString());
        assertEquals("Done.", msgs.get(3).get("content").getString());
        assertEquals("user", msgs.get(4).get("role").getString());
        assertEquals("image_url", msgs.get(4).get("content").get(1).get("type").getString());
        assertEquals("继续", msgs.get(5).get("content").getString());
    }

    @Test
    void textOnlyModelNeverReceivesImages() {
        ChatConfig config = chatConfig();
        config.setInputModalities(Collections.singletonList("text"));
        ONode req = buildChat(config, history());

        assertFalse(req.toJson().contains(DATA));
        assertTrue(req.get("messages").get(1).get("content").getString().contains("does not accept image input"));
    }

    @Test
    void textOnlyModelNotesUnacceptedImagesBeforeBounding() {
        // 模型不收的图不占总量：101 张全部提示“换模型”，不会先被当成超量“已删除”
        ChatConfig config = chatConfig();
        config.setInputModalities(Collections.singletonList("text"));
        List<ChatMessage> out = prepare(config, manyImages(101, DATA));

        assertTrue(keptImages(out).isEmpty());
        for (int i = 1; i < out.size(); i++) {
            String content = out.get(i).getContent();
            assertTrue(content.contains("does not accept image input"), content);
            assertFalse(content.contains("removed from this request"), content);
        }
    }

    @Test
    void oversizedSingleImageIsReplacedWithNote() {
        // 单张超过 5MiB（base64）的图（如 MCP 大截图）换成文字，否则留在历史里每轮都会被拒收
        ChatConfig config = chatConfig();
        char[] big = new char[5 * 1024 * 1024 + 1];
        Arrays.fill(big, 'A');
        char[] limit = new char[5 * 1024 * 1024];
        Arrays.fill(limit, 'A');

        List<ChatMessage> messages = new ArrayList<>(manyImages(1, new String(big)));
        messages.addAll(manyImages(1, new String(limit)).subList(1, 2));
        List<ChatMessage> out = prepare(config, messages);

        assertFalse(hasImage(out.get(1)));
        String content = out.get(1).getContent();
        assertTrue(content.contains("[image omitted: p0.png; too large to send (5242881 base64 chars, limit 5242880)]"), content);
        assertTrue(content.startsWith("<path>p0.png</path>"), content);
        // 恰好在上限的照发
        assertTrue(hasImage(out.get(2)));
        // 用户附件同样处理
        List<ChatMessage> user = Collections.singletonList(ChatMessage.ofUser("看", ImageBlock.ofBase64(new String(big), "image/png")));
        assertFalse(hasImage(prepare(config, user).get(0)));
    }

    @Test
    void textOnlyModelNeverReceivesUserAttachments() {
        // 用户上传的附件同样按声明的输入类型处理（含会话中途换到纯文本模型后的历史附件）
        ChatConfig config = chatConfig();
        config.setInputModalities(Collections.singletonList("text"));
        List<ChatMessage> messages = Collections.singletonList(
                ChatMessage.ofUser("这是截图", ImageBlock.ofBase64(DATA, "image/png").filePath("shot.png")));
        String json = buildChat(config, messages).toJson();

        assertFalse(json.contains(DATA), json);
        assertTrue(json.contains("这是截图"), json);
        assertTrue(json.contains("[image omitted: shot.png; the current model does not accept image input"), json);

        // 未声明时不限制
        assertTrue(buildChat(chatConfig(), messages).toJson().contains(DATA));
    }

    @Test
    void truncatedImageBecomesReReadHint() {
        ToolResult result = ToolResult.success("<path>docs/a.png</path>")
                .addBlock(ImageBlock.ofBase64("", "image/png").filePath("docs/a.png"));
        List<ChatMessage> messages = Arrays.asList(ChatMessage.ofUser("hi"),
                new ToolMessage(result, "read", "call_1", false));
        ONode msgs = buildChat(chatConfig(), messages).get("messages");

        assertEquals(2, msgs.size());
        String content = msgs.get(1).get("content").getString();
        assertTrue(content.contains("not retained in conversation history: docs/a.png"), content);
    }

    @Test
    void truncatedUserAttachmentLeavesANote() {
        // 原先各方言静默跳过截断的用户附件，模型不知道这里曾有图片
        List<ChatMessage> messages = Collections.singletonList(
                ChatMessage.ofUser("这是截图", ImageBlock.ofBase64("", "image/png").filePath("shot.png")));
        String json = buildChat(chatConfig(), messages).toJson();

        assertTrue(json.contains("这是截图"), json);
        assertTrue(json.contains("[image attachment not retained in conversation history: shot.png]"), json);
    }

    @Test
    void historyWithoutMediaIsReturnedAsIs() {
        List<ChatMessage> messages = Arrays.asList(ChatMessage.ofUser("hi"), ChatMessage.ofTool("ok", "ls", "c1"));
        assertSame(messages, prepare(chatConfig(), messages));
    }

    @Test
    void responsesKeepsImageInFunctionCallOutputByDefault() {
        ChatConfig config = new ChatConfig();
        config.setApiUrl("https://api.openai.com/v1/responses");
        config.setModel("gpt-5");
        ONode req = OpenaiResponsesDialect.getInstance().buildRequestJson(config, ChatOptions.of(), history(), false);

        String json = req.toJson();
        assertTrue(json.contains("input_image"), json);
        assertFalse(json.contains("Attached media from tool result"), json);
    }

    @Test
    void responsesTurnsAudioIntoNoteInsteadOfDroppingIt() {
        ChatConfig config = new ChatConfig();
        config.setApiUrl("https://api.openai.com/v1/responses");
        config.setModel("gpt-5");
        ToolResult result = ToolResult.success("recorded")
                .addBlock(AudioBlock.ofBase64("UklGRg==", "audio/wav").filePath("a.wav"));
        List<ChatMessage> messages = Arrays.asList(ChatMessage.ofUser("listen"), new ToolMessage(result, "record", "call_9", false));

        String json = OpenaiResponsesDialect.getInstance().buildRequestJson(config, ChatOptions.of(), messages, false).toJson();
        assertTrue(json.contains("[audio omitted: a.wav; function_call_output does not accept audio]"), json);
        assertFalse(json.contains("UklGRg=="), json);
        assertFalse(json.contains("Attached media from tool result"), json);
    }

}
