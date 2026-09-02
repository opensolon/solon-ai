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
package org.noear.solon.ai.llm.dialect.anthropic;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.event.ChatEvent;
import org.noear.solon.ai.chat.event.ChatEventType;
import org.noear.solon.ai.chat.message.AssistantMessage;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 流式终态收口的事件契约（离线 mock 端点，跑真实的核心事件管道）
 *
 * <p>方言此前用「空 AssistantMessage 帧」编码四种终态语义（thinking 闭合 / finishReason 透传 /
 * thinkingSignature 载体 / 空流补位）。空内容项会被核心统一映射成 TEXT_DELTA
 * （{@code ChatRequestDescDefault#buildItemEvent}），于是每条流末尾都多一条文本为空的
 * 幻影 TEXT_DELTA；仅思考的流还会因此在流末凭空开出一个正文块。</p>
 *
 * <p>本测试锁定收口后的契约：正文流不再出现空 TEXT_DELTA、仅思考流不再出现任何正文事件，
 * 同时签名仍随聚合消息的 contentRaw 交付（多轮 extended thinking 的出站依赖它）。</p>
 *
 * @author noear
 */
public class AnthropicStreamTerminalFrameTest {
    /**
     * 纯正文流（无思考）
     */
    private static final String SSE_TEXT_ONLY =
            "event: message_start\ndata: {\"type\":\"message_start\",\"message\":{\"id\":\"msg_1\",\"model\":\"claude-sonnet-4-5\",\"usage\":{\"input_tokens\":10,\"output_tokens\":1}}}\n\n" +
            "event: content_block_start\ndata: {\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}\n\n" +
            "event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"杭州\"}}\n\n" +
            "event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"今天晴\"}}\n\n" +
            "event: content_block_stop\ndata: {\"type\":\"content_block_stop\",\"index\":0}\n\n" +
            "event: message_delta\ndata: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"},\"usage\":{\"output_tokens\":20}}\n\n" +
            "event: message_stop\ndata: {\"type\":\"message_stop\"}\n\n";

    /**
     * 思考 + 签名 + 正文（extended thinking 的常规形态）
     */
    private static final String SSE_THINKING_THEN_TEXT =
            "event: message_start\ndata: {\"type\":\"message_start\",\"message\":{\"id\":\"msg_2\",\"model\":\"claude-sonnet-4-5\",\"usage\":{\"input_tokens\":10,\"output_tokens\":1}}}\n\n" +
            "event: content_block_start\ndata: {\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"thinking\",\"thinking\":\"\"}}\n\n" +
            "event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"thinking_delta\",\"thinking\":\"让我想想\"}}\n\n" +
            "event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"signature_delta\",\"signature\":\"sig_abc\"}}\n\n" +
            "event: content_block_stop\ndata: {\"type\":\"content_block_stop\",\"index\":0}\n\n" +
            "event: content_block_start\ndata: {\"type\":\"content_block_start\",\"index\":1,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}\n\n" +
            "event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"index\":1,\"delta\":{\"type\":\"text_delta\",\"text\":\"杭州今天晴\"}}\n\n" +
            "event: content_block_stop\ndata: {\"type\":\"content_block_stop\",\"index\":1}\n\n" +
            "event: message_delta\ndata: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"},\"usage\":{\"output_tokens\":20}}\n\n" +
            "event: message_stop\ndata: {\"type\":\"message_stop\"}\n\n";

    /**
     * 仅思考、无正文（max_tokens 截断形态：没有后续块可借道闭合思考）
     */
    private static final String SSE_THINKING_ONLY =
            "event: message_start\ndata: {\"type\":\"message_start\",\"message\":{\"id\":\"msg_3\",\"model\":\"claude-sonnet-4-5\",\"usage\":{\"input_tokens\":10,\"output_tokens\":1}}}\n\n" +
            "event: content_block_start\ndata: {\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"thinking\",\"thinking\":\"\"}}\n\n" +
            "event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"thinking_delta\",\"thinking\":\"让我想想\"}}\n\n" +
            "event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"signature_delta\",\"signature\":\"sig_abc\"}}\n\n" +
            "event: message_delta\ndata: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"max_tokens\"},\"usage\":{\"output_tokens\":20}}\n\n" +
            "event: message_stop\ndata: {\"type\":\"message_stop\"}\n\n";

    /**
     * 正文之后追加一个未闭合、且无签名的思考块（兼容网关的交错形态）：
     * 此时没有任何载体帧要走内容项通道，思考闭合只能靠显式 THINKING_END 事件表达
     */
    private static final String SSE_TEXT_THEN_UNCLOSED_THINKING =
            "event: message_start\ndata: {\"type\":\"message_start\",\"message\":{\"id\":\"msg_4\",\"model\":\"claude-sonnet-4-5\",\"usage\":{\"input_tokens\":10,\"output_tokens\":1}}}\n\n" +
            "event: content_block_start\ndata: {\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}\n\n" +
            "event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"杭州今天晴\"}}\n\n" +
            "event: content_block_start\ndata: {\"type\":\"content_block_start\",\"index\":1,\"content_block\":{\"type\":\"thinking\",\"thinking\":\"再确认一下\"}}\n\n" +
            "event: message_delta\ndata: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"},\"usage\":{\"output_tokens\":20}}\n\n" +
            "event: message_stop\ndata: {\"type\":\"message_stop\"}\n\n";

    private HttpServer server;
    private String apiUrl;
    private final AtomicReference<String> sseBody = new AtomicReference<>(SSE_TEXT_ONLY);

    @BeforeEach
    public void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/messages", this::handle);
        server.start();

        apiUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/messages";
    }

    @AfterEach
    public void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    private void handle(HttpExchange exchange) throws IOException {
        //把请求体读完，避免连接复用异常
        exchange.getRequestBody().read(new byte[8192]);

        byte[] body = sseBody.get().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
        exchange.sendResponseHeaders(200, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
            out.flush();
        }
    }

    private List<ChatEvent> collect(String sse) throws Exception {
        sseBody.set(sse);

        List<ChatEvent> events = new CopyOnWriteArrayList<>();
        AtomicReference<Throwable> errRef = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);

        ChatModel.of(apiUrl)
                .provider("anthropic")
                .model("claude-sonnet-4-5")
                .build()
                .prompt("杭州天气")
                .stream()
                .subscribe(events::add,
                        err -> {
                            errRef.set(err);
                            latch.countDown();
                        },
                        latch::countDown);

        assertTrue(latch.await(30, TimeUnit.SECONDS), "流未在超时内终止");
        assertNull(errRef.get(), "正常路径不应报错");
        return events;
    }

    private static int countOf(List<ChatEvent> events, ChatEventType type) {
        int n = 0;
        for (ChatEvent e : events) {
            if (e.getType() == type) {
                n++;
            }
        }
        return n;
    }

    private static int countEmptyDelta(List<ChatEvent> events, ChatEventType type) {
        int n = 0;
        for (ChatEvent e : events) {
            if (e.getType() == type && (e.getText() == null || e.getText().isEmpty())) {
                n++;
            }
        }
        return n;
    }

    private static String joinText(List<ChatEvent> events, ChatEventType type) {
        StringBuilder buf = new StringBuilder();
        for (ChatEvent e : events) {
            if (e.getType() == type && e.getText() != null) {
                buf.append(e.getText());
            }
        }
        return buf.toString();
    }

    private static AssistantMessage terminalMessage(List<ChatEvent> events) {
        for (int i = events.size() - 1; i >= 0; i--) {
            ChatEvent e = events.get(i);
            if (e.getType() == ChatEventType.RESPONSE_END) {
                assertNotNull(e.getResponse(), "RESPONSE_END 应携带终态聚合");
                return e.getResponse().getMessage();
            }
        }
        return fail("缺少 RESPONSE_END");
    }

    /**
     * 纯正文流：终态不再补空帧，订阅方拿不到任何空 TEXT_DELTA
     */
    @Test
    public void textOnlyStream_hasNoPhantomTextDelta() throws Exception {
        List<ChatEvent> events = collect(SSE_TEXT_ONLY);

        assertEquals(0, countEmptyDelta(events, ChatEventType.TEXT_DELTA),
                "终态空帧不应被映射成空 TEXT_DELTA");
        assertEquals("杭州今天晴", joinText(events, ChatEventType.TEXT_DELTA), "正文增量拼接");

        //正文块边界各一次，思考通道完全不出现
        assertEquals(1, countOf(events, ChatEventType.TEXT_START), "TEXT_START 数");
        assertEquals(1, countOf(events, ChatEventType.TEXT_END), "TEXT_END 数");
        assertEquals(0, countOf(events, ChatEventType.THINKING_START), "不应有思考事件");
        assertEquals(0, countOf(events, ChatEventType.THINKING_END), "不应有思考事件");

        //聚合正文不受影响
        AssistantMessage msg = terminalMessage(events);
        assertNotNull(msg, "终态聚合消息不应为 null");
        assertEquals("杭州今天晴", msg.getText(), "聚合正文");
        assertEquals("stop", lastResponseFinishReason(events), "finishReason 仍透传");
    }

    private static String lastResponseFinishReason(List<ChatEvent> events) {
        for (int i = events.size() - 1; i >= 0; i--) {
            if (events.get(i).getType() == ChatEventType.RESPONSE_END) {
                return events.get(i).getResponse().getFinishReason();
            }
        }
        return null;
    }

    /**
     * 思考 + 签名 + 正文：签名仍随聚合消息 contentRaw 交付（出站多轮回传依赖它），
     * 且不会因为载体帧而多出一个内容块
     */
    @Test
    public void thinkingThenTextStream_keepsSignatureOnAggregationContentRaw() throws Exception {
        List<ChatEvent> events = collect(SSE_THINKING_THEN_TEXT);

        assertEquals("让我想想", joinText(events, ChatEventType.THINKING_DELTA), "思考增量拼接");
        assertEquals("杭州今天晴", joinText(events, ChatEventType.TEXT_DELTA), "正文增量拼接");

        //块边界各恰好一次：载体帧不得关掉当前块又开一个新块
        assertEquals(1, countOf(events, ChatEventType.THINKING_START), "THINKING_START 数");
        assertEquals(1, countOf(events, ChatEventType.THINKING_END), "THINKING_END 数");
        assertEquals(1, countOf(events, ChatEventType.TEXT_START), "TEXT_START 数");
        assertEquals(1, countOf(events, ChatEventType.TEXT_END), "TEXT_END 数");

        //签名走专用事件通道
        ChatEvent sig = null;
        for (ChatEvent e : events) {
            if (e.getType() == ChatEventType.THINKING_SIGNATURE) {
                sig = e;
                break;
            }
        }
        assertNotNull(sig, "signature_delta 应发 THINKING_SIGNATURE");
        assertEquals("sig_abc", sig.getText(), "签名值");

        //签名同时留在聚合消息的 contentRaw 上：AnthropicRequestBuilder 据此重建 thinking 块
        AssistantMessage msg = terminalMessage(events);
        assertNotNull(msg, "终态聚合消息不应为 null");
        assertTrue(msg.getContentRaw() instanceof Map,
                "聚合消息 contentRaw 必须是携带签名的 Map，实际为: " + msg.getContentRaw());
        assertEquals("sig_abc", ((Map<?, ?>) msg.getContentRaw()).get("thinkingSignature"), "回传签名");

        //聚合文本逐字节不变
        assertEquals("杭州今天晴", msg.getText(), "聚合正文");
        assertEquals("让我想想", msg.getThinking(), "聚合思考");

        //已知残留：签名载体必须留在内容项通道，核心会把它映射成一条空内容事件。
        //它被归到「流末所在的块」（此处为正文），因此最多一条，且不会新开块。
        //若核心将来支持「静默内容项」，这里应降为 0。
        assertTrue(countEmptyDelta(events, ChatEventType.TEXT_DELTA) <= 1,
                "载体帧最多产生一条空 TEXT_DELTA");
    }

    /**
     * 仅思考无正文：思考在流末闭合，且不再凭空开出一个正文块
     */
    @Test
    public void thinkingOnlyStream_emitsNoTextEvents() throws Exception {
        List<ChatEvent> events = collect(SSE_THINKING_ONLY);

        assertEquals("让我想想", joinText(events, ChatEventType.THINKING_DELTA), "思考增量拼接");

        assertEquals(1, countOf(events, ChatEventType.THINKING_START), "THINKING_START 数");
        assertEquals(1, countOf(events, ChatEventType.THINKING_END), "THINKING_END 数");

        //关键：终态载体帧不得让仅思考的流末尾多出一个空正文块
        assertEquals(0, countOf(events, ChatEventType.TEXT_START), "不应有正文块");
        assertEquals(0, countOf(events, ChatEventType.TEXT_DELTA), "不应有正文增量");
        assertEquals(0, countOf(events, ChatEventType.TEXT_END), "不应有正文块");

        AssistantMessage msg = terminalMessage(events);
        assertNotNull(msg, "终态聚合消息不应为 null");
        assertEquals("让我想想", msg.getThinking(), "聚合思考");
        assertTrue(msg.getContentRaw() instanceof Map, String.valueOf(msg.getContentRaw()));
        assertEquals("sig_abc", ((Map<?, ?>) msg.getContentRaw()).get("thinkingSignature"), "回传签名");
        assertEquals("max_tokens", lastResponseFinishReason(events), "finishReason 仍透传");
    }

    /**
     * 无载体帧时的思考闭合：改用显式 THINKING_END 事件，不再借空内容项
     */
    @Test
    public void unclosedThinkingWithoutCarrier_emitsThinkingEnd() throws Exception {
        List<ChatEvent> events = collect(SSE_TEXT_THEN_UNCLOSED_THINKING);

        assertEquals("杭州今天晴", joinText(events, ChatEventType.TEXT_DELTA), "正文增量拼接");
        assertEquals("再确认一下", joinText(events, ChatEventType.THINKING_DELTA), "思考增量拼接");

        assertEquals(1, countOf(events, ChatEventType.THINKING_END), "思考应恰好闭合一次");
        assertEquals(0, countEmptyDelta(events, ChatEventType.TEXT_DELTA),
                "思考闭合不应再借空内容项（会变成空 TEXT_DELTA）");
    }
}
