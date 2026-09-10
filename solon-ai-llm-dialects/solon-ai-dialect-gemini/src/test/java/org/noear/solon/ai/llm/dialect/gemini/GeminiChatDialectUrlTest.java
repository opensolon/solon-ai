/*
 * Copyright 2017-2025 noear.org and authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package org.noear.solon.ai.llm.dialect.gemini;

import org.junit.jupiter.api.Test;
import org.noear.solon.ai.chat.ChatConfig;

import static org.junit.jupiter.api.Assertions.*;

/** Gemini URL 和方言识别回归。 */
public class GeminiChatDialectUrlTest {
    private final GeminiChatDialect dialect = new GeminiChatDialect();

    @Test
    public void completeEndpointSwitchesWithCallMode() {
        assertEquals("https://example.test/v1beta/models/gemini:streamGenerateContent?foo=1&alt=sse",
                dialect.buildApiUrl("https://example.test/v1beta/models/gemini:generateContent?foo=1",
                        "ignored", true));
        assertEquals("https://example.test/v1beta/models/gemini:generateContent?foo=1",
                dialect.buildApiUrl("https://example.test/v1beta/models/gemini:streamGenerateContent?foo=1&alt=sse",
                        "ignored", false));
    }

    @Test
    public void matchesV1EndpointWithQuery() {
        ChatConfig config = new ChatConfig();
        config.setApiUrl("https://example.test/v1/models/gemini:generateContent?key=x");
        assertTrue(dialect.matched(config));
    }

    @Test
    public void v1alphaBaseIsNotDoublePrefixed() {
        // v1alpha 基址（预览/实验 API）不得再拼 v1beta
        assertEquals("https://example.test/v1alpha/models/gemini:generateContent",
                dialect.buildApiUrl("https://example.test/v1alpha/", "gemini", false));
        assertEquals("https://example.test/v1alpha/models/gemini:streamGenerateContent?alt=sse",
                dialect.buildApiUrl("https://example.test/v1alpha/", "gemini", true));
    }
}
