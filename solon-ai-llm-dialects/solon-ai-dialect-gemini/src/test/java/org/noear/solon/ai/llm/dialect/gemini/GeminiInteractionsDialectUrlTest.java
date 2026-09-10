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
package org.noear.solon.ai.llm.dialect.gemini;

import org.junit.jupiter.api.Test;
import org.noear.solon.ai.chat.ChatConfig;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Gemini Interactions 方言 URL 规则回归
 *
 * <p>自动补全逻辑依赖正则 /v\d+(beta)?/? 判断是否已带版本段，
 * 该矩阵锁定各网关前缀下的最终 URL。</p>
 */
public class GeminiInteractionsDialectUrlTest {
    private final GeminiInteractionsDialect dialect = new GeminiInteractionsDialect();

    private String apiUrl(String url) {
        ChatConfig config = new ChatConfig();
        config.setApiUrl(url);
        return dialect.getApiUrl(config);
    }

    @Test
    public void fullInteractionEndpointIsUntouched() {
        assertEquals("https://example.test/v1beta/interactions",
                apiUrl("https://example.test/v1beta/interactions"));
    }

    @Test
    public void missingSlashIsNormalized() {
        assertEquals("https://example.test/v1beta/interactions",
                apiUrl("https://example.test/v1beta/interactions/"));
    }

    @Test
    public void bareHostGetsV1Interactions() {
        assertEquals("https://example.test/v1/interactions",
                apiUrl("https://example.test"));
        assertEquals("https://example.test/v1/interactions",
                apiUrl("https://example.test/"));
    }

    @Test
    public void versionedPrefixKeepsItsVersion() {
        assertEquals("https://example.test/v1/interactions", apiUrl("https://example.test/v1"));
        assertEquals("https://example.test/v1/interactions", apiUrl("https://example.test/v1/"));
        assertEquals("https://example.test/v1beta/interactions", apiUrl("https://example.test/v1beta"));
        assertEquals("https://example.test/v1beta/interactions", apiUrl("https://example.test/v1beta/"));
    }

    @Test
    public void alphaVersionIsNotDoublePrefixed() {
        // 正则 /v\d+(beta)?/? 恰好不匹配 v1alpha：不再追加 v1/，直接拼 /v1alpha/interactions（现状锁定）
        assertEquals("https://example.test/v1alpha/interactions",
                apiUrl("https://example.test/v1alpha/"));
    }

    @Test
    public void queryAndFragmentArePreservedOrStripped() {
        assertEquals("https://example.test/v1beta/interactions?foo=1",
                apiUrl("https://example.test/v1beta/interactions?foo=1"));
        assertEquals("https://example.test/v1beta/interactions?foo=1",
                apiUrl("https://example.test/v1beta/interactions?foo=1#section"));
    }

    @Test
    public void matchedRequiresInteractionsSuffix() {
        ChatConfig config = new ChatConfig();
        config.setApiUrl("https://example.test/v1beta/interactions");
        assertTrue(dialect.matched(config));

        config.setApiUrl("https://example.test/v1beta/interactions?alt=sse");
        assertTrue(dialect.matched(config));

        config.setApiUrl("https://example.test/v1beta/models/gemini:generateContent");
        assertFalse(dialect.matched(config));
    }
}
