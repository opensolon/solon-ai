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
import org.noear.solon.ai.chat.ChatConfig;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Anthropic 方言的匹配与地址补全测试。
 *
 * @author noear
 */
public class AnthropicChatDialectConfigTest {
    private final AnthropicChatDialect dialect = AnthropicChatDialect.getInstance();

    private ChatConfig config(String standard, String provider, String apiUrl) {
        ChatConfig config = new ChatConfig();
        config.setStandard(standard);
        config.setProvider(provider);
        config.setApiUrl(apiUrl);
        return config;
    }

    @Test
    public void apiUrlFragmentIsRemovedBeforePathCompletion() {
        assertEquals("https://api.anthropic.com/v1/messages",
                dialect.getApiUrl(config(null, null, "https://api.anthropic.com#alias")));
        assertEquals("https://api.anthropic.com/v1/messages",
                dialect.getApiUrl(config(null, null, "https://api.anthropic.com/v1#alias")));
        assertEquals("https://api.anthropic.com/v1/messages",
                dialect.getApiUrl(config(null, null, "https://api.anthropic.com/v1/messages#alias")));
    }

    @Test
    public void apiUrlQueryIsPreservedAfterPathCompletion() {
        assertEquals("https://gateway.test/v1/messages?tenant=a",
                dialect.getApiUrl(config(null, null, "https://gateway.test?tenant=a#alias")));
        assertEquals("https://gateway.test/v2/messages?tenant=a",
                dialect.getApiUrl(config(null, null, "https://gateway.test/v2?tenant=a#alias")));
        assertEquals("https://gateway.test/v2/messages?tenant=a",
                dialect.getApiUrl(config(null, null, "https://gateway.test/v2/messages?tenant=a#alias")));
    }

    @Test
    public void matchedIgnoresQueryAndFragmentOnMessagesEndpoint() {
        assertTrue(dialect.matched(config(null, null,
                "https://gateway.test/v1/messages?tenant=a#alias")));
        assertFalse(dialect.matched(config(null, null,
                "https://gateway.test/v1?tenant=a#alias")));
        assertFalse(dialect.matched(config("openai", null,
                "https://gateway.test/v1/messages#alias")));
    }
}
