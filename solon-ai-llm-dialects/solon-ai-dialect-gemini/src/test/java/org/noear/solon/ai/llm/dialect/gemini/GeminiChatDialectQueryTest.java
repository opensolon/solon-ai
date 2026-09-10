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

import static org.junit.jupiter.api.Assertions.*;

/**
 * buildApiUrl 的 query 参数边界回归：
 * 流式切换时 alt=sse 的增删不得破坏其它参数，重复 alt 只保留一份。
 */
public class GeminiChatDialectQueryTest {
    private final GeminiChatDialect dialect = new GeminiChatDialect();

    @Test
    public void duplicatedAltParamsCollapseToOne() {
        // 流式：所有 alt（无论取值）统一替换为一份 alt=sse，其它参数保序
        assertEquals("https://example.test/v1beta/models/gemini:streamGenerateContent?foo=1&alt=sse",
                dialect.buildApiUrl(
                        "https://example.test/v1beta/models/gemini:generateContent?alt=other&foo=1&alt=sse",
                        "ignored", true));

        // 非流式：仅清理 alt=sse（流式标记），网关自定义 alt 取值保留
        assertEquals("https://example.test/v1beta/models/gemini:generateContent?foo=1&alt=json",
                dialect.buildApiUrl(
                        "https://example.test/v1beta/models/gemini:streamGenerateContent?alt=sse&foo=1&alt=json",
                        "ignored", false));
    }

    @Test
    public void fragmentIsStrippedBeforeSwitch() {
        assertEquals("https://example.test/v1beta/models/gemini:streamGenerateContent?alt=sse",
                dialect.buildApiUrl(
                        "https://example.test/v1beta/models/gemini:generateContent#frag",
                        "ignored", true));
    }

    @Test
    public void bareBaseAppendsModelAndVersion() {
        assertEquals("https://example.test/v1beta/models/gemini-3:generateContent",
                dialect.buildApiUrl("https://example.test", "gemini-3", false));
        assertEquals("https://example.test/v1beta/models/gemini-3:streamGenerateContent?alt=sse",
                dialect.buildApiUrl("https://example.test/", "gemini-3", true));
    }

    @Test
    public void queryOnBareBaseIsKept() {
        assertEquals("https://example.test/v1beta/models/gemini-3:generateContent?key=x",
                dialect.buildApiUrl("https://example.test?key=x", "gemini-3", false));
    }
}
