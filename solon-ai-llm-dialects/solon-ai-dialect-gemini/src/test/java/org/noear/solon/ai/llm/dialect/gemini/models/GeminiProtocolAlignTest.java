/*
 * Copyright 2017-2025 noear.org and authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package org.noear.solon.ai.llm.dialect.gemini.models;

import org.junit.jupiter.api.Test;
import org.noear.snack4.ONode;
import org.noear.solon.ai.chat.ChatConfig;
import org.noear.solon.ai.chat.ChatOptions;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.message.SystemMessage;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Google Generate Content wire contract 回归。 */
public class GeminiProtocolAlignTest {
    private final GeminiRequestBuilder builder = new GeminiRequestBuilder();

    @Test
    public void requestUsesUrlModelAndCamelCaseSystemInstruction() {
        ChatConfig config = new ChatConfig();
        config.setModel("gemini-3-flash-preview");
        ONode root = builder.build(config, ChatOptions.of(), Arrays.asList(
                new SystemMessage("one"), new SystemMessage("two"), ChatMessage.ofUser("hello")), false);

        assertFalse(root.hasKey("model"), root.toJson());
        assertFalse(root.hasKey("system_instruction"), root.toJson());
        assertEquals("one\n\ntwo", root.get("systemInstruction").get("parts").get(0).get("text").getString());
    }

    @Test
    public void commonOptionsAreNestedAndNativeConfigIsPreserved() {
        ChatConfig config = new ChatConfig();
        config.setModel("gemini-3-flash-preview");
        Map<String, Object> nativeConfig = new LinkedHashMap<>();
        nativeConfig.put("stopSequences", Arrays.asList("END"));
        nativeConfig.put("topK", "12");
        nativeConfig.put("seed", "7");
        nativeConfig.put("responseJsonSchema", java.util.Collections.singletonMap("type", "object"));

        ChatOptions options = ChatOptions.of()
                .optionSet("generationConfig", nativeConfig)
                .optionSet("top_p", "0.8")
                .optionSet("max_completion_tokens", "1024")
                .optionSet("tool_choice", "none");
        ONode root = builder.build(config, options, java.util.Collections.singletonList(ChatMessage.ofUser("hello")), false);
        ONode generation = root.get("generationConfig");

        assertFalse(root.hasKey("top_p"), root.toJson());
        assertFalse(root.hasKey("max_completion_tokens"), root.toJson());
        assertFalse(root.hasKey("tool_choice"), root.toJson());
        assertEquals(0.8D, generation.get("topP").getDouble(), 0.0001D);
        assertEquals(1024, generation.get("maxOutputTokens").getInt());
        assertEquals(12, generation.get("topK").getInt());
        assertEquals(7, generation.get("seed").getInt());
        assertEquals("END", generation.get("stopSequences").get(0).getString());
        assertEquals("object", generation.get("responseJsonSchema").get("type").getString());
    }

    @Test
    public void outputSchemaUsesNativeStructuredOutput() {
        ChatConfig config = new ChatConfig();
        config.setModel("gemini-3-flash-preview");
        ChatOptions options = ChatOptions.of().outputSchema("{\"type\":\"object\",\"properties\":{}}");
        ONode root = builder.build(config, options,
                java.util.Collections.singletonList(ChatMessage.ofUser("hello")), false);
        ONode generation = root.get("generationConfig");

        assertEquals("application/json", generation.get("responseMimeType").getString());
        assertEquals("object", generation.get("responseJsonSchema").get("type").getString());
    }
}
