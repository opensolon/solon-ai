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
package org.noear.solon.ai.llm.dialect.gemini.interactions;

import org.junit.jupiter.api.Test;
import org.noear.snack4.ONode;
import org.noear.solon.ai.chat.ChatConfig;
import org.noear.solon.ai.chat.ChatOptions;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.message.SystemMessage;
import org.noear.solon.ai.chat.tool.FunctionToolDesc;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Gemini Interactions 请求回放过滤测试。
 */
public class GeminiInteractionsRequestBuilderTest {
    @Test
    public void requestReplayKeepsMixedAndCarrierMessages() {
        GeminiInteractionsRequestBuilder builder = new GeminiInteractionsRequestBuilder();
        ChatConfig config = new ChatConfig();
        config.setModel("gemini-3-flash");

        AssistantMessage thinkingOnly = new AssistantMessage("", "drop");
        AssistantMessage mixed = new AssistantMessage("answer", "thinking");
        AssistantMessage carrier = new AssistantMessage("", "carrier");
        carrier.addMetadata("thought_signature", "sig");

        ONode root = builder.build(config, ChatOptions.of(),
                Arrays.asList(thinkingOnly, mixed, carrier), false);

        assertEquals(1, root.get("input").size(), root.toJson());
        assertEquals("model_output", root.get("input").get(0).get("type").getString());
        assertEquals("answer", root.get("input").get(0).get("content").get(0).get("text").getString());
    }

    @Test
    public void currentTopLevelAndGenerationConfigContract() {
        GeminiInteractionsRequestBuilder builder = new GeminiInteractionsRequestBuilder();
        ChatConfig config = new ChatConfig();
        config.setModel("gemini-3.5-flash");
        ChatOptions options = ChatOptions.of().thinking(true)
                .optionSet("reasoning_effort", "minimal")
                .optionSet("max_tokens", "256")
                .optionSet("tool_choice", "required");

        ONode root = builder.build(config, options, Arrays.asList(
                new SystemMessage("one"), new SystemMessage("two"), ChatMessage.ofUser("hello")), true);

        assertEquals("one\n\ntwo", root.get("system_instruction").getString());
        assertFalse(root.hasKey("config"), root.toJson());
        ONode generation = root.get("generation_config");
        assertEquals(256, generation.get("max_output_tokens").getInt());
        assertEquals("minimal", generation.get("thinking_level").getString());
        assertEquals("auto", generation.get("thinking_summaries").getString());
        assertEquals("any", generation.get("tool_choice").getString());
    }

    @Test
    public void namedToolChoiceUsesAllowedTools() {
        GeminiInteractionsRequestBuilder builder = new GeminiInteractionsRequestBuilder();
        ChatConfig config = new ChatConfig();
        config.setModel("gemini-3.5-flash");
        Map<String, Object> choice = new LinkedHashMap<>();
        choice.put("type", "function");
        choice.put("function", Collections.singletonMap("name", "weather"));
        ChatOptions options = ChatOptions.of().optionSet("tool_choice", choice);
        options.toolAdd(new FunctionToolDesc("weather"));

        ONode root = builder.build(config, options,
                Collections.singletonList(ChatMessage.ofUser("hello")), false);
        ONode allowed = root.get("generation_config").get("tool_choice").get("allowed_tools");
        assertEquals("any", allowed.get("mode").getString());
        assertEquals("weather", allowed.get("tools").get(0).getString());
        assertFalse(root.hasKey("tool_choice"), root.toJson());
    }

    @Test
    public void structuredOutputUsesTextJsonFormat() {
        GeminiInteractionsRequestBuilder builder = new GeminiInteractionsRequestBuilder();
        ChatConfig config = new ChatConfig();
        config.setModel("gemini-3.5-flash");
        ONode root = builder.build(config,
                ChatOptions.of().outputSchema("{\"type\":\"object\"}"),
                Collections.singletonList(ChatMessage.ofUser("hello")), false);

        ONode format = root.get("response_format").get(0);
        assertEquals("text", format.get("type").getString());
        assertEquals("application/json", format.get("mime_type").getString());
        assertEquals("object", format.get("schema").get("type").getString());
    }
}
