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
import org.noear.solon.ai.chat.ChatRequest;
import org.noear.solon.ai.chat.dialect.ChatDialect;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.session.InMemoryChatSession;
import org.noear.solon.core.Props;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Gemini {@code labels} 要求值为字符串：配置读进来的纯数字串在转为请求数据（{@link ChatRequest#toRequestData()}）后仍是字符串，
 * 同一请求里其它选项照常还原为强类型
 *
 * @author noear
 */
public class GeminiOptionValueTypeTest {
    private static ChatConfig config(String provider) {
        Props props = new Props();
        props.put("provider", provider);
        props.put("model", "gemini-2.5-flash");
        props.put("defaultOptions.labels.year", "2024");
        props.put("defaultOptions.labels.team", "007");
        props.put("defaultOptions.labels.enabled", "true");
        return props.bindTo(new ChatConfig());
    }

    private static ONode toRequest(ChatConfig config, ChatDialect dialect, String numericKey) {
        ChatOptions options = ChatOptions.of();
        options.putAll(config.getModelOptions());
        options.optionSet(numericKey, "3"); //代码里设置的数字串照常转为数字

        InMemoryChatSession session = new InMemoryChatSession("test");
        session.addMessage(ChatMessage.ofUser("hello"));

        ChatRequest req = new ChatRequest(config, dialect, options, session, null, null, false);
        return ONode.ofJson(req.toRequestData());
    }

    private static void assertLabelsAreStrings(ONode root, ChatConfig config) {
        ONode labels = root.get("labels");
        String json = root.toJson();

        assertTrue(labels.get("year").isString(), json);
        assertEquals("2024", labels.get("year").getString());
        assertEquals("007", labels.get("team").getString());
        assertTrue(labels.get("enabled").isString(), json);
        assertEquals("true", labels.get("enabled").getString());

        //配置载体不变
        assertEquals("2024", ((Map) config.getModelOptions().option("labels")).get("year"));
    }

    @Test
    public void chatLabelsStayStrings() {
        ChatConfig config = config("gemini");
        ONode root = toRequest(config, GeminiChatDialect.getInstance(), "max_tokens");

        assertLabelsAreStrings(root, config);

        ONode maxOutputTokens = root.get("generationConfig").get("maxOutputTokens");
        assertTrue(maxOutputTokens.isNumber(), root.toJson());
        assertEquals(3, maxOutputTokens.getInt());
    }

    @Test
    public void interactionsLabelsStayStrings() {
        ChatConfig config = config("gemini-interactions");
        config.getModelOptions().optionSet("background", "true");
        ONode root = toRequest(config, GeminiInteractionsDialect.getInstance(), "max_tokens");

        assertLabelsAreStrings(root, config);

        ONode maxOutputTokens = root.get("generation_config").get("max_output_tokens");
        assertTrue(maxOutputTokens.isNumber(), root.toJson());
        assertEquals(3, maxOutputTokens.getInt());

        //方言原样透传的顶层字段：由核心层还原为布尔（与 labels 同一条透传路径）
        assertTrue(root.get("background").isBoolean(), root.toJson());
        assertTrue(root.get("background").getBoolean());
    }
}
