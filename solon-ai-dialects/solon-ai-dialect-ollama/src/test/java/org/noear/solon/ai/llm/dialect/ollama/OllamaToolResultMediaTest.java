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
package org.noear.solon.ai.llm.dialect.ollama;

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
 * Ollama 原生：tool 消息 content 是字符串，工具结果里的图片挪到用户消息的 images 数组
 */
public class OllamaToolResultMediaTest {
    static final String DATA = "iVBORw0KGgoAAAANSUhEUg==";

    private static ChatConfig config() {
        ChatConfig config = new ChatConfig();
        config.setStandard("ollama");
        config.setApiUrl("http://localhost:11434/api/chat");
        config.setModel("qwen2.5vl:7b");
        return config;
    }

    private static List<ChatMessage> history() {
        ToolResult result = ToolResult.success("<path>a.png</path>")
                .addBlock(ImageBlock.ofBase64(DATA, "image/png").filePath("a.png"));
        return Arrays.asList(ChatMessage.ofUser("看图"), new ToolMessage(result, "read", "call_1", false));
    }

    @Test
    void toolContentIsStringAndImageMovesToUserImages() {
        ONode msgs = OllamaChatDialect.getInstance()
                .buildRequestJson(config(), ChatOptions.of(), history(), false)
                .get("messages");

        assertEquals(3, msgs.size(), msgs.toJson());
        assertEquals("tool", msgs.get(1).get("role").getString());
        assertTrue(msgs.get(1).get("content").isString(), msgs.toJson());
        assertFalse(msgs.get(1).hasKey("images"));

        assertEquals("user", msgs.get(2).get("role").getString());
        assertTrue(msgs.get(2).get("content").isString());
        assertTrue(msgs.get(2).get("images").isArray(), msgs.toJson());
        assertEquals(1, msgs.get(2).get("images").size());
    }
}
