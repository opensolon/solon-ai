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
 * abnormalFinishReason 与 finish_message 兼容矩阵回归。
 *
 * <p>与官方 SDK 的 checkFinishReason 语义对齐：STOP/MAX_TOKENS/FINISH_REASON_UNSPECIFIED
 * 之外的终止原因必须显式报错，snake_case 的 finish_message 是兼容网关字段。</p>
 */
public class GeminiAbnormalFinishReasonTest {
    private final List<ChatEvent> events = new ArrayList<>();

    private ChatStreamContext context() {
        events.clear();
        ChatConfig config = new ChatConfig();
        config.setModel("gemini-2.5-flash");
        ChatRequest req = new ChatRequest(config, GeminiChatDialect.getInstance(), ChatOptions.of(),
                InMemoryChatSession.builder().build(), ChatMessage.ofSystem("test"), null, false);
        return new ChatStreamContextDefault(config, req, new ChatAccumulator(req, false),
                new ChatStreamSession(), 0, events::add);
    }

    private void parse(String candidates) {
        GeminiChatDialect.getInstance().parseResponseJson(context(),
                "{\"candidates\":[" + candidates + "]}");
    }

    private ChatStreamContext parseAndReturn(String candidates) {
        ChatStreamContext ctx = context();
        GeminiChatDialect.getInstance().parseResponseJson(ctx,
                "{\"candidates\":[" + candidates + "]}");
        return ctx;
    }

    @Test
    public void recitationIsReported() {
        ChatStreamContext ctx = parseAndReturn(
                "{\"content\":{\"parts\":[{\"text\":\"x\"}]},\"finishReason\":\"RECITATION\"}");
        assertNotNull(ctx.getAccumulator().getError());
        assertTrue(ctx.getAccumulator().getError().getMessage().contains("RECITATION"));
    }

    @Test
    public void malformedFunctionCallIsReported() {
        ChatStreamContext ctx = parseAndReturn("{\"finishReason\":\"MALFORMED_FUNCTION_CALL\"}");
        assertNotNull(ctx.getAccumulator().getError());
        assertTrue(ctx.getAccumulator().getError().getMessage().contains("MALFORMED_FUNCTION_CALL"));
    }

    @Test
    public void imageSafetyIsReported() {
        ChatStreamContext ctx = parseAndReturn("{\"finishReason\":\"IMAGE_SAFETY\"}");
        assertNotNull(ctx.getAccumulator().getError());
        assertTrue(ctx.getAccumulator().getError().getMessage().contains("IMAGE_SAFETY"));
    }

    @Test
    public void snakeCaseFinishMessageIsHonored() {
        ChatStreamContext ctx = parseAndReturn(
                "{\"finishReason\":\"PROHIBITED_CONTENT\",\"finish_message\":\"gateway said no\"}");
        assertNotNull(ctx.getAccumulator().getError());
        assertTrue(ctx.getAccumulator().getError().getMessage().contains("gateway said no"));
        assertTrue(ctx.getAccumulator().getError().getMessage().contains("PROHIBITED_CONTENT"));
    }

    @Test
    public void stopAndMaxTokensAreNotErrors() {
        assertNull(parseAndReturn(
                "{\"content\":{\"parts\":[{\"text\":\"ok\"}]},\"finishReason\":\"STOP\"}")
                .getAccumulator().getError());
        assertNull(parseAndReturn(
                "{\"content\":{\"parts\":[{\"text\":\"ok\"}]},\"finishReason\":\"MAX_TOKENS\"}")
                .getAccumulator().getError());
    }

    @Test
    public void errorFrameEmitsErrorEventWithRaw() {
        ChatStreamContext ctx = context();
        GeminiChatDialect.getInstance().parseResponseJson(ctx,
                "{\"error\":{\"message\":\"quota exceeded\"}}");

        assertNotNull(ctx.getAccumulator().getError());
        ChatEvent e = firstOf(ChatEventType.ERROR);
        assertNotNull(e, "error frame should emit ERROR event");
        assertEquals("quota exceeded", e.getError().getMessage());
        assertNotNull(e.getRaw(), "ERROR event should carry the parsed raw frame");
        assertEquals("quota exceeded", e.getRaw().get("error").get("message").getString());
    }

    @Test
    public void malformedJsonFrameIsSkippedQuietly() {
        ChatStreamContext ctx = context();
        assertDoesNotThrow(() ->
                GeminiChatDialect.getInstance().parseResponseJson(ctx, "not-json{"));
        assertNull(ctx.getAccumulator().getError());
    }

    private ChatEvent firstOf(ChatEventType type) {
        for (ChatEvent e : events) {
            if (e.getType() == type) {
                return e;
            }
        }
        return null;
    }
}
