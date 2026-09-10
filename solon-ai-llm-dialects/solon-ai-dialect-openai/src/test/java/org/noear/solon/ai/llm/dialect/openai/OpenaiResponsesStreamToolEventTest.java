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
package org.noear.solon.ai.llm.dialect.openai;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.event.ChatEvent;
import org.noear.solon.ai.chat.event.ChatEventType;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * OpenAI Responses 工具调用完整流事件契约。
 */
public class OpenaiResponsesStreamToolEventTest {
    private static final String SSE_TOOL_CALL =
            "data: {\"type\":\"response.created\",\"response\":{\"id\":\"resp_1\",\"model\":\"gpt-5.4\"}}\n\n" +
            "data: {\"type\":\"response.output_item.added\",\"output_index\":0,\"item\":{\"id\":\"fc_1\",\"type\":\"function_call\",\"call_id\":\"call_1\",\"name\":\"weather\",\"arguments\":\"\"}}\n\n" +
            "data: {\"type\":\"response.function_call_arguments.delta\",\"item_id\":\"fc_1\",\"output_index\":0,\"delta\":\"{\\\"city\\\":\\\"hz\\\"}\"}\n\n" +
            "data: {\"type\":\"response.function_call_arguments.done\",\"item_id\":\"fc_1\",\"output_index\":0,\"name\":\"weather\",\"arguments\":\"{\\\"city\\\":\\\"hz\\\"}\"}\n\n" +
            "data: {\"type\":\"response.output_item.done\",\"output_index\":0,\"item\":{\"id\":\"fc_1\",\"type\":\"function_call\",\"call_id\":\"call_1\",\"name\":\"weather\",\"arguments\":\"{\\\"city\\\":\\\"hz\\\"}\"}}\n\n" +
            "data: {\"type\":\"response.completed\",\"response\":{\"id\":\"resp_1\",\"model\":\"gpt-5.4\",\"output\":[{\"id\":\"fc_1\",\"type\":\"function_call\",\"call_id\":\"call_1\",\"name\":\"weather\",\"arguments\":\"{\\\"city\\\":\\\"hz\\\"}\"}]}}\n\n" +
            "data: [DONE]\n\n";

    private static final String SSE_INCOMPLETE =
            "data: {\"type\":\"response.incomplete\",\"response\":{\"id\":\"resp_incomplete\",\"model\":\"gpt-5.4\",\"incomplete_details\":{\"reason\":\"max_output_tokens\"},\"output\":[{\"id\":\"msg_1\",\"type\":\"message\",\"content\":[{\"type\":\"output_text\",\"text\":\"partial\"}]}],\"usage\":{\"input_tokens\":2,\"output_tokens\":1,\"total_tokens\":3}}}\n\n" +
            "data: {\"type\":\"response.completed\",\"response\":{\"id\":\"resp_incomplete\",\"output\":[{\"id\":\"msg_1\",\"type\":\"message\",\"content\":[{\"type\":\"output_text\",\"text\":\"late-success\"}]}]}}\n\n" +
            "data: [DONE]\n\n";

    private static final String SSE_CANCELLED =
            "data: {\"type\":\"response.cancelled\",\"response\":{\"id\":\"resp_cancelled\",\"model\":\"gpt-5.4\",\"output\":[{\"id\":\"msg_1\",\"type\":\"message\",\"content\":[{\"type\":\"output_text\",\"text\":\"cancelled-partial\"}]}]}}\n\n" +
            "data: {\"type\":\"response.output_text.delta\",\"delta\":\"late\"}\n\n" +
            "data: [DONE]\n\n";

    private HttpServer server;
    private String apiUrl;
    private String responseBody;

    @BeforeEach
    public void setUp() throws IOException {
        responseBody = SSE_TOOL_CALL;
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/responses", this::handle);
        server.start();
        apiUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/responses";
    }

    @AfterEach
    public void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    private void handle(HttpExchange exchange) throws IOException {
        exchange.getRequestBody().read(new byte[8192]);
        byte[] body = responseBody.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
        exchange.sendResponseHeaders(200, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
            out.flush();
        }
    }

    @Test
    public void completeStreamHasSingleToolCallEnd() throws Exception {
        List<ChatEvent> events = executeStream();

        assertEquals(1, countOf(events, ChatEventType.TOOL_CALL_START));
        assertEquals(1, countOf(events, ChatEventType.TOOL_CALL_ARGS_DELTA));
        assertEquals(1, countOf(events, ChatEventType.TOOL_CALL_END));
        assertEquals(1, countOf(events, ChatEventType.RESPONSE_END));

        ChatEvent end = lastOf(events, ChatEventType.RESPONSE_END);
        assertNotNull(end);
        assertNotNull(end.getResponse());
        assertEquals(1, end.getResponse().getToolCalls().size());
        assertEquals("hz", end.getResponse().getToolCalls().get(0).getArguments().get("city"));
    }

    @Test
    public void incompleteStatusPrecedesCoreOnlyResponseEnd() throws Exception {
        responseBody = SSE_INCOMPLETE;
        List<ChatEvent> events = executeStream();

        assertEquals(1, countOf(events, ChatEventType.STATUS));
        assertEquals(0, countOf(events, ChatEventType.ABORT));
        assertEquals(0, countOf(events, ChatEventType.ERROR));
        assertEquals(1, countOf(events, ChatEventType.RESPONSE_END));
        ChatEvent status = lastOf(events, ChatEventType.STATUS);
        assertEquals("response.incomplete", status.getRawType());
        assertEquals("max_output_tokens", status.getSubType());
        assertEquals("length", status.getText());
        assertTrue(events.indexOf(status) < events.indexOf(lastOf(events, ChatEventType.RESPONSE_END)));
        assertEquals("partial", lastOf(events, ChatEventType.RESPONSE_END).getResponse().getMessage().getText());
    }

    @Test
    public void cancelledStatusPrecedesCoreOnlyResponseEndAndDropsLateFrames() throws Exception {
        responseBody = SSE_CANCELLED;
        List<ChatEvent> events = executeStream();

        assertEquals(1, countOf(events, ChatEventType.STATUS));
        assertEquals(0, countOf(events, ChatEventType.ABORT));
        assertEquals(0, countOf(events, ChatEventType.ERROR));
        assertEquals(1, countOf(events, ChatEventType.RESPONSE_END));
        ChatEvent status = lastOf(events, ChatEventType.STATUS);
        ChatEvent end = lastOf(events, ChatEventType.RESPONSE_END);
        assertEquals("response.cancelled", status.getRawType());
        assertEquals("cancelled", status.getText());
        assertTrue(events.indexOf(status) < events.indexOf(end));
        assertEquals("cancelled-partial", end.getResponse().getMessage().getText());
    }

    private List<ChatEvent> executeStream() throws Exception {
        List<ChatEvent> events = new CopyOnWriteArrayList<>();
        AtomicReference<Throwable> error = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);

        ChatModel.of(apiUrl)
                .provider("openai-responses")
                .model("gpt-5.4")
                .build()
                .prompt("test")
                .options(o -> o.autoToolCall(false))
                .stream()
                .subscribe(events::add, e -> {
                    error.set(e);
                    latch.countDown();
                }, latch::countDown);

        assertTrue(latch.await(30, TimeUnit.SECONDS), "流未在超时内终止");
        assertNull(error.get(), "Responses 流不应报错");
        return events;
    }

    private static int countOf(List<ChatEvent> events, ChatEventType type) {
        int count = 0;
        for (ChatEvent event : events) {
            if (event.getType() == type) {
                count++;
            }
        }
        return count;
    }

    private static ChatEvent lastOf(List<ChatEvent> events, ChatEventType type) {
        ChatEvent found = null;
        for (ChatEvent event : events) {
            if (event.getType() == type) {
                found = event;
            }
        }
        return found;
    }
}
