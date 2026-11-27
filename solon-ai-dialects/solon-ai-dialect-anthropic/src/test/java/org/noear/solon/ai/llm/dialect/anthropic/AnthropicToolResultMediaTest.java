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
package org.noear.solon.ai.llm.dialect.anthropic;

import org.junit.jupiter.api.Test;
import org.noear.snack4.ONode;
import org.noear.solon.ai.chat.ChatConfig;
import org.noear.solon.ai.chat.ChatOptions;
import org.noear.solon.ai.chat.content.ImageBlock;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.message.ToolMessage;
import org.noear.solon.ai.chat.tool.ToolResult;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Anthropic：工具结果里的图片缺省原样放进 tool_result.content
 */
public class AnthropicToolResultMediaTest {
    static final String DATA = "iVBORw0KGgoAAAANSUhEUg==";

    private static ChatConfig config() {
        ChatConfig config = new ChatConfig();
        config.setProvider("anthropic");
        config.setApiUrl("https://api.anthropic.com/v1/messages");
        config.setModel("claude-sonnet-4-5");
        return config;
    }

    private static List<ChatMessage> history() {
        ToolResult result = ToolResult.success("<path>a.png</path>")
                .addBlock(ImageBlock.ofBase64(DATA, "image/png").filePath("a.png"));
        return Arrays.asList(ChatMessage.ofUser("看图"), new ToolMessage(result, "read", "call_1", false));
    }

    @Test
    void imageStaysInToolResultByDefault() {
        ONode req = AnthropicChatDialect.getInstance().buildRequestJson(config(), ChatOptions.of(), history(), false);
        String json = req.toJson();

        assertTrue(json.contains("\"tool_result\""), json);
        assertTrue(json.contains(DATA), json);
        assertFalse(json.contains("Attached media from tool result"), json);
    }

    @Test
    void textOnlyModelGetsNoteInsteadOfImage() {
        ChatConfig config = config();
        config.setInputModalities(Collections.singletonList("text"));
        String json = AnthropicChatDialect.getInstance().buildRequestJson(config, ChatOptions.of(), history(), false).toJson();

        assertFalse(json.contains(DATA), json);
        assertTrue(json.contains("does not accept image input"), json);
    }
}
