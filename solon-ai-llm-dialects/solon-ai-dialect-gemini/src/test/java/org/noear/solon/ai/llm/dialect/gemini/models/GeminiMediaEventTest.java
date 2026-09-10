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
import org.noear.solon.ai.chat.ChatAccumulator;
import org.noear.solon.ai.chat.ChatConfig;
import org.noear.solon.ai.chat.ChatOptions;
import org.noear.solon.ai.chat.ChatRequest;
import org.noear.solon.ai.chat.content.ContentBlock;
import org.noear.solon.ai.chat.content.TextBlock;
import org.noear.solon.ai.chat.event.ChatEvent;
import org.noear.solon.ai.chat.event.ChatEventType;
import org.noear.solon.ai.chat.event.ChatStreamContext;
import org.noear.solon.ai.chat.event.ChatStreamContextDefault;
import org.noear.solon.ai.chat.event.ChatStreamSession;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.session.InMemoryChatSession;
import org.noear.solon.ai.llm.dialect.gemini.GeminiChatDialect;
import org.noear.solon.ai.llm.dialect.gemini.GeminiInteractionsDialect;
import org.noear.solon.ai.llm.dialect.gemini.interactions.GeminiInteractionsResponseParser;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Gemini 媒体事件与位置语义回归测试。 */
public class GeminiMediaEventTest {
    @Test
    public void modelsStreamKeepsEqualMediaAtDifferentPositions() {
        List<ChatEvent> events = new ArrayList<>();
        ChatStreamContext ctx = newContext(true, GeminiChatDialect.getInstance(), events);
        GeminiThoughtProcessor processor = new GeminiThoughtProcessor();

        processor.emitStream(ctx, ONode.ofJson("{\"parts\":["
                + "{\"inlineData\":{\"mimeType\":\"image/png\",\"data\":\"same-data\"}},"
                + "{\"inlineData\":{\"mimeType\":\"image/png\",\"data\":\"same-data\"}}]}"),
                0, false);

        List<ChatEvent> mediaEvents = mediaEvents(events);
        assertEquals(2, mediaEvents.size(), "相同内容位于不同 part 时必须保留两个 MEDIA_DONE");
        assertEquals(0, mediaEvents.get(0).getIndex());
        assertEquals(1, mediaEvents.get(1).getIndex());
        assertEquals(mediaEvents.get(0).getBlock().getContent(), mediaEvents.get(1).getBlock().getContent());
    }

    @Test
    public void interactionsNonStreamUsesMediaEventsAndKeepsPositions() {
        List<ChatEvent> events = new ArrayList<>();
        ChatStreamContext ctx = newContext(false, GeminiInteractionsDialect.getInstance(), events);
        GeminiInteractionsResponseParser parser = new GeminiInteractionsResponseParser();

        parser.parseNonStreamResponse(ctx, "{\"status\":\"completed\",\"steps\":["
                + "{\"type\":\"model_output\",\"content\":["
                + "{\"type\":\"inline_data\",\"mime_type\":\"image/png\",\"data\":\"same-data\"},"
                + "{\"type\":\"inline_data\",\"mime_type\":\"image/png\",\"data\":\"same-data\"}]}]}");

        assertEquals(2, mediaEvents(events).size(), "非流式媒体也必须经 MEDIA_DONE 统一归并");
        int terminalMediaCount = 0;
        for (ContentBlock block : ctx.getAccumulator().snapshotTerminal().getBlocks()) {
            if (!(block instanceof TextBlock)) {
                terminalMediaCount++;
            }
        }
        assertEquals(2, terminalMediaCount, "非流式终态必须保留不同位置的相同媒体");
    }

    private ChatStreamContext newContext(boolean stream, org.noear.solon.ai.chat.dialect.ChatDialect dialect,
                                         List<ChatEvent> events) {
        ChatConfig config = new ChatConfig();
        ChatRequest req = new ChatRequest(config, dialect, ChatOptions.of(),
                InMemoryChatSession.builder().build(), ChatMessage.ofSystem("test"), null, stream);
        return new ChatStreamContextDefault(config, req, new ChatAccumulator(req, stream),
                new ChatStreamSession(), 0, events::add);
    }

    private List<ChatEvent> mediaEvents(List<ChatEvent> events) {
        List<ChatEvent> media = new ArrayList<>();
        for (ChatEvent event : events) {
            if (event.getType() == ChatEventType.MEDIA_DONE) {
                media.add(event);
            }
        }
        return media;
    }
}
