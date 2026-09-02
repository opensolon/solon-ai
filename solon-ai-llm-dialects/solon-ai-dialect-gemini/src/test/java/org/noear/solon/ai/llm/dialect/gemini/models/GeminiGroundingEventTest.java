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
import org.noear.solon.ai.chat.*;
import org.noear.solon.ai.chat.event.*;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.session.InMemoryChatSession;
import org.noear.solon.ai.llm.dialect.gemini.GeminiChatDialect;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Gemini models（generateContent）联网搜索来源事件
 *
 * <p>groundingMetadata 在 candidates 内而非独立帧，事件通道就位前这部分信息对订阅方不可见。</p>
 *
 * @author noear
 */
public class GeminiGroundingEventTest {
    private final List<ChatEvent> events = new ArrayList<>();

    private ChatStreamContext newCtx() {
        events.clear();

        ChatConfig config = new ChatConfig();
        config.setModel("gemini-2.5-flash");
        ChatRequest req = new ChatRequest(config, GeminiChatDialect.getInstance(), ChatOptions.of(),
                InMemoryChatSession.builder().build(), ChatMessage.ofSystem("test"), null, true);

        return new ChatStreamContextDefault(config, req, new ChatAccumulator(req, true),
                new ChatStreamSession(), 0, events::add);
    }

    private List<ChatEvent> allOf(ChatEventType type) {
        List<ChatEvent> list = new ArrayList<>();
        for (ChatEvent e : events) {
            if (e.getType() == type) {
                list.add(e);
            }
        }
        return list;
    }

    /**
     * groundingChunks[].web.uri 逐条透出为 CITATION
     */
    @Test
    public void groundingChunksBecomeCitations() {
        ChatStreamContext ctx = newCtx();

        GeminiChatDialect.getInstance().parseResponseJson(ctx, "{\"candidates\":[{"
                + "\"content\":{\"parts\":[{\"text\":\"杭州今天晴\"}],\"role\":\"model\"},"
                + "\"groundingMetadata\":{\"groundingChunks\":["
                + "{\"web\":{\"uri\":\"https://a.example.com\",\"title\":\"A\"}},"
                + "{\"web\":{\"uri\":\"https://b.example.com\",\"title\":\"B\"}}"
                + "]}}]}");

        List<ChatEvent> citations = allOf(ChatEventType.CITATION);
        assertEquals(2, citations.size(), "each grounding chunk should emit one CITATION");
        assertEquals("https://a.example.com", citations.get(0).getText());
        assertEquals("https://b.example.com", citations.get(1).getText());
        assertEquals("google_search", citations.get(0).getSubType());
        assertSame(ChatEventGroup.MEDIA, citations.get(0).getGroup());

        //正文仍走内容项，不受影响；元数据事件与正文来自同一响应帧
        assertTrue(ctx.getAccumulator().hasContentItems());
        assertEquals("杭州今天晴", ctx.getAccumulator().lastItem().getText());
    }

    /**
     * 无 groundingMetadata 时不产生多余事件
     */
    @Test
    public void noGroundingNoCitation() {
        ChatStreamContext ctx = newCtx();

        GeminiChatDialect.getInstance().parseResponseJson(ctx, "{\"candidates\":[{"
                + "\"content\":{\"parts\":[{\"text\":\"hello\"}],\"role\":\"model\"}}]}");

        assertTrue(allOf(ChatEventType.CITATION).isEmpty());
    }
}
