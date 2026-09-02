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
import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.dialect.ChatDialects;
import org.noear.solon.ai.chat.event.ChatEvent;
import org.noear.solon.ai.chat.event.ChatEvents;
import org.noear.solon.ai.chat.event.ChatEventType;
import org.noear.solon.ai.chat.tool.MethodToolProvider;
import org.noear.solon.annotation.Param;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 工具调用递归轮的流式契约（离线 mock 端点，不依赖网络与配额）
 *
 * <p>覆盖两件事：</p>
 * <ol>
 *   <li>递归轮（第二次 LLM 调用）报错时，错误必须传播到订阅方，不能被吞成 complete；</li>
 *   <li>正常两轮工具调用时，STEP 必须配平，RESPONSE_END 全流恰好一次。</li>
 * </ol>
 *
 * <p>这两条此前只能靠真实网关用例覆盖，一旦限流就无法复验；改为本地 mock 后可稳定回归。</p>
 *
 * @author noear
 */
public class ChatStreamRecursionTest {
    /** 第一轮：吐一个工具调用（分片下发 id/name 与 arguments） */
    private static final String ROUND1_SSE =
            "data: {\"id\":\"c1\",\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"tool_calls\":[{\"index\":0,\"id\":\"call_1\",\"type\":\"function\",\"function\":{\"name\":\"get_power_usage\",\"arguments\":\"\"}}]}}]}\n\n" +
            "data: {\"id\":\"c1\",\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"{\\\"deviceId\\\":\\\"76-51\\\"}\"}}]}}]}\n\n" +
            "data: {\"id\":\"c1\",\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"tool_calls\"}]}\n\n" +
            "data: [DONE]\n\n";

    /** 第二轮（正常）：吐正文并带 usage */
    private static final String ROUND2_SSE =
            "data: {\"id\":\"c2\",\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"日用电量为 \"}}]}\n\n" +
            "data: {\"id\":\"c2\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"128 度\"}}]}\n\n" +
            "data: {\"id\":\"c2\",\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":11,\"completion_tokens\":7,\"total_tokens\":18}}\n\n" +
            "data: [DONE]\n\n";

    /** 第二轮（SSE 内联错误）：HTTP 200 但载荷里是 error 对象 */
    private static final String ROUND2_SSE_ERROR =
            "data: {\"error\":{\"message\":\"inline boom\",\"type\":\"server_error\"}}\n\n";

    private HttpServer server;
    private String apiUrl;
    private final AtomicInteger roundCounter = new AtomicInteger();

    /** 第二轮的应答策略 */
    private final AtomicReference<String> secondRoundMode = new AtomicReference<>("ok");

    /** 第一轮的应答策略 */
    private final AtomicReference<String> firstRoundMode = new AtomicReference<>("ok");

    @BeforeEach
    public void setUp() throws IOException {
        roundCounter.set(0);
        firstRoundMode.set("ok");
        secondRoundMode.set("ok");

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", this::handle);
        server.start();

        apiUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/chat/completions";
    }

    @AfterEach
    public void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    private void handle(HttpExchange exchange) throws IOException {
        int round = roundCounter.incrementAndGet();

        //把请求体读完，避免连接复用异常
        exchange.getRequestBody().read(new byte[8192]);

        //内容类型看上去合法，但响应体没有一帧是模型帧
        if (round == 1 && "opaqueBody".equals(firstRoundMode.get())) {
            byte[] body = "upstream error: bad gateway\nplease retry later\n"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
            return;
        }

        if (round >= 2 && "http500".equals(secondRoundMode.get())) {
            byte[] body = "{\"error\":{\"message\":\"upstream boom\",\"type\":\"server_error\"}}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(500, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
            return;
        }

        String sse = ROUND1_SSE;
        if (round >= 2) {
            sse = "sseError".equals(secondRoundMode.get()) ? ROUND2_SSE_ERROR : ROUND2_SSE;
        }

        byte[] body = sse.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
        exchange.sendResponseHeaders(200, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
            out.flush();
        }
    }

    private ChatModel newChatModel() {
        return ChatModel.of(apiUrl)
                .provider("openai")
                .model("mock-model")
                .defaultToolAdd(new MethodToolProvider(new MockTools()))
                .build();
    }

    /**
     * 递归轮报错：必须以 onError 收尾，不能被吞成 onComplete
     */
    @Test
    public void recursionRoundErrorMustPropagate() throws Exception {
        secondRoundMode.set("http500");

        List<ChatEvent> events = new CopyOnWriteArrayList<>();
        AtomicReference<Throwable> errRef = new AtomicReference<>();
        AtomicBoolean completed = new AtomicBoolean(false);
        CountDownLatch latch = new CountDownLatch(1);

        newChatModel().prompt("设备 76-51 的日用电量").stream()
                .subscribe(events::add,
                        err -> {
                            errRef.set(err);
                            latch.countDown();
                        },
                        () -> {
                            completed.set(true);
                            latch.countDown();
                        });

        assertTrue(latch.await(30, TimeUnit.SECONDS), "流未在超时内终止");

        assertEquals(2, roundCounter.get(), "应发起两轮 LLM 调用");
        assertNotNull(errRef.get(), "递归轮报错必须传播到订阅方（不得吞成 complete）");
        assertFalse(completed.get(), "报错后不应再走 onComplete");

        //错误终止时不得发出 RESPONSE_END（终态聚合语义要求：只在正常收尾发一次）
        assertEquals(0, countOf(events, ChatEventType.RESPONSE_END),
                "错误终止不应发 RESPONSE_END");

        //第一轮已完整走完，STEP 应配平
        assertEquals(1, countOf(events, ChatEventType.STEP_START), "第一轮 STEP_START");
        assertEquals(1, countOf(events, ChatEventType.STEP_END), "第一轮 STEP_END");
        assertEquals(1, countOf(events, ChatEventType.TOOL_RESULT), "工具已执行");
    }

    /**
     * 正常两轮：STEP 配平，RESPONSE_END 恰好一次并携带终态
     */
    @Test
    public void normalRecursionKeepsStepBalancedAndSingleResponseEnd() throws Exception {
        secondRoundMode.set("ok");

        List<ChatEvent> events = new CopyOnWriteArrayList<>();
        AtomicReference<Throwable> errRef = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);

        newChatModel().prompt("设备 76-51 的日用电量").stream()
                .subscribe(events::add,
                        err -> {
                            errRef.set(err);
                            latch.countDown();
                        },
                        latch::countDown);

        assertTrue(latch.await(30, TimeUnit.SECONDS), "流未在超时内终止");
        assertNull(errRef.get(), "正常路径不应报错");

        assertEquals(2, roundCounter.get(), "应发起两轮 LLM 调用");

        assertEquals(1, countOf(events, ChatEventType.RESPONSE_START), "RESPONSE_START 恰好一次");
        assertEquals(1, countOf(events, ChatEventType.RESPONSE_END), "RESPONSE_END 全流恰好一次");
        assertEquals(2, countOf(events, ChatEventType.STEP_START), "两轮 STEP_START");
        assertEquals(2, countOf(events, ChatEventType.STEP_END), "两轮 STEP_END");

        //工具调用的完成信号数应等于真实工具调用数（不是 SSE 分片数）
        assertEquals(1, countOf(events, ChatEventType.TOOL_CALL_END), "TOOL_CALL_END 数");
        assertEquals(1, countOf(events, ChatEventType.TOOL_RESULT), "TOOL_RESULT 数");

        //RESPONSE_START 必须是首个事件，RESPONSE_END 必须是末个事件
        assertEquals(ChatEventType.RESPONSE_START, events.get(0).getType(), "首事件");
        assertEquals(ChatEventType.RESPONSE_END, events.get(events.size() - 1).getType(), "末事件");

        //终态聚合可用
        ChatEvent end = lastOf(events, ChatEventType.RESPONSE_END);
        assertNotNull(end.getResponse(), "RESPONSE_END 应携带终态聚合");
        assertNotNull(end.getUsage(), "RESPONSE_END 应携带 usage");
        assertEquals(18, end.getUsage().totalTokens(), "usage 透传");

        //正文只来自第二轮，且不含工具内容
        StringBuilder text = new StringBuilder();
        for (ChatEvent e : events) {
            if (e.getType() == ChatEventType.TEXT_DELTA && e.getText() != null) {
                text.append(e.getText());
            }
        }
        assertEquals("日用电量为 128 度", text.toString(), "正文增量拼接");
    }

    /**
     * 正文投影：与事件流的 TEXT_DELTA 拼接结果一致
     */
    @Test
    public void textProjectionMatchesTextDeltas() throws Exception {
        secondRoundMode.set("ok");

        List<String> chunks = new ArrayList<>();
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Throwable> errRef = new AtomicReference<>();

        newChatModel().prompt("设备 76-51 的日用电量").stream()
                .filter(e -> e.is(ChatEventType.TEXT_DELTA) && e.hasText())
                .map(ChatEvent::getText)
                .subscribe(chunks::add,
                        err -> {
                            errRef.set(err);
                            latch.countDown();
                        },
                        latch::countDown);

        assertTrue(latch.await(30, TimeUnit.SECONDS), "流未在超时内终止");
        assertNull(errRef.get(), "正常路径不应报错");

        assertEquals("日用电量为 128 度", String.join("", chunks), "正文投影只出正文");
    }

    /**
     * 递归轮的内联错误（HTTP 200 + 载荷 error 对象）同样必须以 onError 收尾
     *
     * <p>这条路径与 500 不同：错误是在方言解析后才被发现的（resp.getError() != null），
     * 由内层 sink 向外层传递，更容易在方言迁移中被漏接。</p>
     */
    @Test
    public void recursionRoundInlineSseErrorMustPropagate() throws Exception {
        secondRoundMode.set("sseError");

        List<ChatEvent> events = new CopyOnWriteArrayList<>();
        AtomicReference<Throwable> errRef = new AtomicReference<>();
        AtomicBoolean completed = new AtomicBoolean(false);
        CountDownLatch latch = new CountDownLatch(1);

        newChatModel().prompt("设备 76-51 的日用电量").stream()
                .subscribe(events::add,
                        err -> {
                            errRef.set(err);
                            latch.countDown();
                        },
                        () -> {
                            completed.set(true);
                            latch.countDown();
                        });

        assertTrue(latch.await(30, TimeUnit.SECONDS), "流未在超时内终止");

        assertEquals(2, roundCounter.get(), "应发起两轮 LLM 调用");
        assertNotNull(errRef.get(), "内联错误必须传播到订阅方");
        assertFalse(completed.get(), "报错后不应再走 onComplete");
        assertEquals(0, countOf(events, ChatEventType.RESPONSE_END), "错误终止不应发 RESPONSE_END");
    }

    /**
     * HTTP 200 + 非模型响应体（且内容类型不是 html，绕过内容类型守卫）
     *
     * <p>典型来源：反向代理或网关把错误文本以 200 + text/plain 返回。旧实现下方言会
     * 逐帧告警 skip malformed frame 然后正常 complete，订阅方得到一个「成功的空流」，
     * 正是本类用例要防的静默失败。</p>
     */
    @Test
    public void opaqueBodyMustNotBecomeSilentEmptyStream() throws Exception {
        firstRoundMode.set("opaqueBody");

        List<ChatEvent> events = new CopyOnWriteArrayList<>();
        AtomicReference<Throwable> errRef = new AtomicReference<>();
        AtomicBoolean completed = new AtomicBoolean(false);
        CountDownLatch latch = new CountDownLatch(1);

        newChatModel().prompt("hello").stream()
                .subscribe(events::add,
                        err -> {
                            errRef.set(err);
                            latch.countDown();
                        },
                        () -> {
                            completed.set(true);
                            latch.countDown();
                        });

        assertTrue(latch.await(30, TimeUnit.SECONDS), "流未在超时内终止");

        assertNotNull(errRef.get(), "无法识别的响应体必须报错，而不是静默空流");
        assertFalse(completed.get(), "报错后不应再走 onComplete");
        assertEquals(0, countOf(events, ChatEventType.RESPONSE_END), "报错终止不应发 RESPONSE_END");
    }

    /**
     * 同上，但走「方言宽容单帧损坍」的路径
     *
     * <p>openai-responses 方言对损坍帧是告警并跳过（这对真实流中的单帧截断是对的），
     * 所以不能靠方言抛异常来发现整个响应体都不是模型流——这正是核心层守卫存在的理由。</p>
     */
    @Test
    public void opaqueBodyMustFailEvenWhenDialectSkipsMalformedFrames() throws Exception {
        firstRoundMode.set("opaqueBody");

        List<ChatEvent> events = new CopyOnWriteArrayList<>();
        AtomicReference<Throwable> errRef = new AtomicReference<>();
        AtomicBoolean completed = new AtomicBoolean(false);
        CountDownLatch latch = new CountDownLatch(1);

        ChatModel.of(apiUrl)
                .standard(ChatDialects.OPENAI_RESPONSES)
                .model("mock-model")
                .build()
                .prompt("hello").stream()
                .subscribe(events::add,
                        err -> {
                            errRef.set(err);
                            latch.countDown();
                        },
                        () -> {
                            completed.set(true);
                            latch.countDown();
                        });

        assertTrue(latch.await(30, TimeUnit.SECONDS), "流未在超时内终止");

        assertNotNull(errRef.get(), "方言跳过损坍帧时，核心层必须兵底报错");
        assertFalse(completed.get(), "报错后不应再走 onComplete");
        assertEquals(0, countOf(events, ChatEventType.RESPONSE_END), "报错终止不应发 RESPONSE_END");
        assertTrue(errRef.get().getMessage().contains("unrecognizable"),
                "错误消息应指向配置问题，实际：" + errRef.get().getMessage());
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

    private static ChatEvent lastOf(List<ChatEvent> events, ChatEventType type) {
        ChatEvent found = null;
        for (ChatEvent e : events) {
            if (e.getType() == type) {
                found = e;
            }
        }
        return found;
    }

    public static class MockTools {
        @ToolMapping(description = "查询设备日用电量")
        public String get_power_usage(@Param(description = "设备编号") String deviceId) {
            return "{\"deviceId\":\"" + deviceId + "\",\"kwh\":128}";
        }
    }
}
