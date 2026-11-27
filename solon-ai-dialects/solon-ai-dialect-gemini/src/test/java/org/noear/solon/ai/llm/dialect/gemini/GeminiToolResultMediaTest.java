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
package org.noear.solon.ai.llm.dialect.gemini;

import org.junit.jupiter.api.Test;
import org.noear.snack4.ONode;
import org.noear.solon.ai.chat.ChatConfig;
import org.noear.solon.ai.chat.ChatOptions;
import org.noear.solon.ai.chat.content.ImageBlock;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.message.ToolMessage;
import org.noear.solon.ai.chat.tool.ToolResult;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Gemini：工具结果里的图片原先被丢掉；现在 Gemini 3+ 原样写进函数响应，其他模型挪到用户消息
 */
public class GeminiToolResultMediaTest {
    static final String DATA = "iVBORw0KGgoAAAANSUhEUg==";

    private static List<ChatMessage> history(String mime) {
        ToolResult result = ToolResult.success("<path>a.png</path>")
                .addBlock(ImageBlock.ofBase64(DATA, mime).filePath("a.png"));
        return Arrays.asList(ChatMessage.ofUser("看图"), new ToolMessage(result, "read", "call_1", false));
    }

    private static ChatConfig config(String model) {
        ChatConfig config = new ChatConfig();
        config.setModel(model);
        return config;
    }

    private static ONode generateContent(ChatConfig config, String mime) {
        return GeminiChatDialect.getInstance().buildRequestJson(config, ChatOptions.of(), history(mime), false);
    }

    private static ONode interactions(ChatConfig config) {
        return GeminiInteractionsDialect.getInstance().buildRequestJson(config, ChatOptions.of(), history("image/png"), false);
    }

    @Test
    void generateContentGemini25MovesImageToUserMessage() {
        ONode req = generateContent(config("gemini-2.5-flash"), "image/png");
        String json = req.toJson();

        assertTrue(json.contains("Attached media from tool result"), json);
        ONode contents = req.get("contents");
        assertEquals(3, contents.size(), json);
        ONode fr = contents.get(1).get("parts").get(0).get("functionResponse");
        assertFalse(fr.hasKey("parts"), json);
        assertEquals(DATA, contents.get(2).get("parts").get(1).get("inlineData").get("data").getString(), json);
    }

    @Test
    void generateContentGemini3WritesFunctionResponseParts() {
        ONode req = generateContent(config("models/gemini-3-flash-preview"), "image/png");
        String json = req.toJson();

        ONode contents = req.get("contents");
        assertEquals(2, contents.size(), json);
        ONode fr = contents.get(1).get("parts").get(0).get("functionResponse");
        assertEquals("<path>a.png</path>", fr.get("response").get("result").getString(), json);
        ONode inline = fr.get("parts").get(0).get("inlineData");
        assertEquals("image/png", inline.get("mimeType").getString(), json);
        assertEquals(DATA, inline.get("data").getString(), json);
        assertFalse(json.contains("Attached media from tool result"), json);
    }

    @Test
    void generateContentGemini3MovesGifToUserMessage() {
        // functionResponse.parts 只收 png / jpeg / webp
        ONode req = generateContent(config("gemini-3-pro"), "image/gif");
        String json = req.toJson();

        assertEquals(3, req.get("contents").size(), json);
        assertFalse(req.get("contents").get(1).get("parts").get(0).get("functionResponse").hasKey("parts"), json);
        assertTrue(json.contains("Attached media from tool result"), json);
    }

    @Test
    void interactionsGemini25MovesImageToUserMessage() {
        String json = interactions(config("gemini-2.5-flash")).toJson();

        assertTrue(json.contains(DATA), json);
        assertTrue(json.contains("Attached media from tool result"), json);
    }

    @Test
    void interactionsGemini3WritesImageIntoFunctionResult() {
        ONode req = interactions(config("gemini-3-flash"));
        String json = req.toJson();

        ONode input = req.get("input");
        assertEquals(2, input.size(), json);
        ONode step = input.get(1);
        assertEquals("function_result", step.get("type").getString(), json);
        ONode result = step.get("result");
        assertEquals("text", result.get(0).get("type").getString(), json);
        assertEquals("<path>a.png</path>", result.get(0).get("text").getString(), json);
        assertEquals("image", result.get(1).get("type").getString(), json);
        assertEquals("image/png", result.get(1).get("mime_type").getString(), json);
        assertEquals(DATA, result.get(1).get("data").getString(), json);
    }

    @Test
    void versionDetection() {
        assertTrue(GeminiChatDialect.supportsMultimodalFunctionResponse(config("gemini-3-flash-preview")));
        assertTrue(GeminiChatDialect.supportsMultimodalFunctionResponse(config("models/Gemini-3.1-Pro")));
        assertTrue(GeminiChatDialect.supportsMultimodalFunctionResponse(config("gemini-10-ultra")));
        assertFalse(GeminiChatDialect.supportsMultimodalFunctionResponse(config("gemini-2.5-pro")));
        assertFalse(GeminiChatDialect.supportsMultimodalFunctionResponse(config("gemini-pro-latest")));
        assertFalse(GeminiChatDialect.supportsMultimodalFunctionResponse(config(null)));
    }
}
