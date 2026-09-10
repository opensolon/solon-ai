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
import org.noear.solon.ai.chat.*;
import org.noear.solon.ai.chat.event.*;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.session.InMemoryChatSession;
import org.noear.solon.ai.llm.dialect.gemini.GeminiInteractionsDialect;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Interactions 终态矩阵回归（对齐官方 InteractionStatus）：
 * failed → 错误 + finishReason=stop；cancelled/incomplete/budget_exceeded → 非静默；
 * queued/in_progress 不得提前终止。
 */
public class GeminiInteractionsTerminalStatusTest {
    private final GeminiInteractionsResponseParser parser = new GeminiInteractionsResponseParser();
    private final List<ChatEvent> events = new ArrayList<>();

    private ChatStreamContext newCtx(boolean stream) {
        events.clear();
        ChatConfig config = new ChatConfig();
        config.setModel("gemini-3-pro");
        ChatRequest req = new ChatRequest(config, GeminiInteractionsDialect.getInstance(), ChatOptions.of(),
                InMemoryChatSession.builder().build(), ChatMessage.ofSystem("test"), null, stream);
        return new ChatStreamContextDefault(config, req, new ChatAccumulator(req, stream),
                new ChatStreamSession(), 0, events::add);
    }

    @Test
    public void failedStatusBecomesError() {
        ChatStreamContext ctx = newCtx(false);
        parser.parseNonStreamResponse(ctx, "{\"status\":\"failed\",\"steps\":[],"
                + "\"error\":{\"message\":\"model overloaded\"}}");
        assertNotNull(ctx.getAccumulator().getError());
        assertEquals("model overloaded", ctx.getAccumulator().getError().getMessage());
        assertTrue(ctx.getAccumulator().isFinished());
        assertEquals("error", ctx.getAccumulator().lastFinishReason);
    }

    @Test
    public void failedWithoutMessageUsesFallback() {
        ChatStreamContext ctx = newCtx(false);
        parser.parseNonStreamResponse(ctx, "{\"status\":\"failed\",\"steps\":[]}");
        assertNotNull(ctx.getAccumulator().getError());
        assertEquals("Gemini interaction failed", ctx.getAccumulator().getError().getMessage());
    }

    @Test
    public void cancelledAndIncompleteFinishWithoutError() {
        ChatStreamContext ctx = newCtx(false);
        parser.parseNonStreamResponse(ctx, "{\"status\":\"cancelled\",\"steps\":[]}");
        assertTrue(ctx.getAccumulator().isFinished());
        assertNull(ctx.getAccumulator().getError());

        ctx = newCtx(false);
        parser.parseNonStreamResponse(ctx, "{\"status\":\"incomplete\",\"steps\":[]}");
        assertTrue(ctx.getAccumulator().isFinished());
        assertNull(ctx.getAccumulator().getError());
    }

    @Test
    public void budgetExceededMapsToLength() {
        ChatStreamContext ctx = newCtx(false);
        parser.parseNonStreamResponse(ctx, "{\"status\":\"budget_exceeded\",\"steps\":[]}");
        assertTrue(ctx.getAccumulator().isFinished());
        assertEquals("length", ctx.getAccumulator().lastFinishReason);
    }

    @Test
    public void nonTerminalStatusDoesNotFinish() {
        ChatStreamContext ctx = newCtx(false);
        parser.parseNonStreamResponse(ctx, "{\"status\":\"in_progress\",\"steps\":[]}");
        assertFalse(ctx.getAccumulator().isFinished());
        assertNull(ctx.getAccumulator().getError());

        ctx = newCtx(false);
        parser.parseNonStreamResponse(ctx, "{\"status\":\"queued\",\"steps\":[]}");
        assertFalse(ctx.getAccumulator().isFinished());
    }

    @Test
    public void streamErrorEventBecomesError() {
        ChatStreamContext ctx = newCtx(true);
        // 官方 SSE 事件全集之一：error（ErrorEvent），旧实现落入 default 只透 RAW
        parser.parseStreamResponse(ctx, "{\"event_type\":\"error\","
                + "\"error\":{\"message\":\"boom\"}}");
        assertNotNull(ctx.getAccumulator().getError());
        assertEquals("boom", ctx.getAccumulator().getError().getMessage());
        assertTrue(ctx.getAccumulator().isFinished());
    }
}
