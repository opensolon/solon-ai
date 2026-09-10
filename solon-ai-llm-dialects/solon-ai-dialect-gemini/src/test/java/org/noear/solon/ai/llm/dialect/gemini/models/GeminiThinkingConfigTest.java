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
package org.noear.solon.ai.llm.dialect.gemini.models;

import org.junit.jupiter.api.Test;
import org.noear.snack4.ONode;
import org.noear.solon.ai.chat.ChatConfig;
import org.noear.solon.ai.chat.ChatOptions;
import org.noear.solon.ai.chat.message.ChatMessage;

import static org.junit.jupiter.api.Assertions.*;

/**
 * thinking 配置回归：
 * <ul>
 *   <li>cleanThoughtContent：仅清理标题型短包裹行，正常加粗正文不受影响</li>
 *   <li>reasoning_effort → thinkingBudget（2.5）/ thinkingLevel（3.x）分支矩阵</li>
 * </ul>
 */
public class GeminiThinkingConfigTest {
    private final GeminiThoughtProcessor processor = new GeminiThoughtProcessor();
    private final GeminiRequestBuilder builder = new GeminiRequestBuilder();

    // ==================== cleanThoughtContent ====================

    @Test
    public void shortHeadingWrapIsRemoved() {
        assertEquals("正文", processor.cleanThoughtContent("**思考过程**\n正文"));
        assertEquals("正文", processor.cleanThoughtContent("**\n正文"));
        assertNull(processor.cleanThoughtContent(null));
        assertEquals("", processor.cleanThoughtContent(""));
    }

    @Test
    public void longBoldLineIsKept() {
        // 整行加粗但属于正常思考正文（超长），不得误删
        String boldSentence = "**这句话是一段足够长的思考正文加粗内容因此不会被当成标题清理掉**";
        assertTrue(boldSentence.trim().length() > GeminiThoughtProcessor.CLEAN_THOUGHT_HEADING_MAX_LENGTH);
        assertEquals(boldSentence + "\n第二行", processor.cleanThoughtContent(boldSentence + "\n第二行"));
    }

    @Test
    public void multilineThoughtKeepsOrder() {
        String content = "步骤一\n**小标题**\n步骤二";
        assertEquals("步骤一\n步骤二", processor.cleanThoughtContent(content));
    }

    // ==================== reasoning_effort → thinking ====================

    private ONode thinkingNode(String model, String effort) {
        ChatConfig config = new ChatConfig();
        config.setModel(model);
        ChatOptions options = ChatOptions.of();
        if (effort != null) {
            options.options().put("reasoning_effort", effort);
        }
        ONode root = builder.build(config, options,
                java.util.Collections.singletonList(ChatMessage.ofUser("hi")), false);
        ONode gen = root.getOrNull("generationConfig");
        return gen == null ? null : gen.getOrNull("thinkingConfig");
    }

    @Test
    public void gemini25UsesThinkingBudget() {
        ONode tc = thinkingNode("gemini-2.5-flash", "high");
        assertNotNull(tc);
        assertEquals(16000, tc.get("thinkingBudget").getInt());
        assertNull(tc.getOrNull("thinkingLevel"));

        assertEquals(32768, thinkingNode("gemini-2.5-pro", "max").get("thinkingBudget").getInt());
        assertEquals(24576, thinkingNode("gemini-2.5-flash", "max").get("thinkingBudget").getInt());
        assertEquals(1024, thinkingNode("gemini-2.5-flash", "low").get("thinkingBudget").getInt());
        assertEquals(4096, thinkingNode("gemini-2.5-flash", "medium").get("thinkingBudget").getInt());
    }

    @Test
    public void gemini3UsesThinkingLevel() {
        ONode tc = thinkingNode("gemini-3-pro", "high");
        assertNotNull(tc);
        assertEquals("high", tc.get("thinkingLevel").getString());
        assertNull(tc.getOrNull("thinkingBudget"));

        assertEquals("minimal", thinkingNode("gemini-3-flash", "minimal").get("thinkingLevel").getString());
        assertEquals("low", thinkingNode("gemini-3-pro", "minimal").get("thinkingLevel").getString());
        assertEquals("medium", thinkingNode("gemini-3-pro", "medium").get("thinkingLevel").getString());
        assertEquals("high", thinkingNode("gemini-3-pro", "max").get("thinkingLevel").getString());
    }

    @Test
    public void noEffortMeansNoThinkingConfig() {
        assertNull(thinkingNode("gemini-2.5-flash", null));
        assertNull(thinkingNode("gemini-3-pro", null));
    }
}
