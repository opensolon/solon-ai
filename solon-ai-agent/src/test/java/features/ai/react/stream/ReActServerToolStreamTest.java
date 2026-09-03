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
package features.ai.react.stream;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.agent.AgentEvent;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.react.ReActAgent;
import org.noear.solon.ai.agent.react.ReActTrace;
import org.noear.solon.ai.agent.react.task.AbsToolCallEvent;
import org.noear.solon.ai.agent.react.task.ActionEndEvent;
import org.noear.solon.ai.agent.react.task.ActionStartEvent;
import org.noear.solon.ai.agent.react.task.ReasonDeltaEvent;
import org.noear.solon.ai.agent.react.task.ReasonEndEvent;
import org.noear.solon.ai.agent.react.RunEndEvent;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.dialect.ChatDialects;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 服务端工具（联网搜索）在 ReAct 流式下的契约（离线 mock 端点，不依赖网络与配额）
 *
 * <p>语料为 Anthropic 的 {@code server_tool_use} + {@code web_search_tool_result} 帧。这类工具由模型服务方
 * 在同一次调用内执行完毕，不经过 ActionTask，因此<b>不构成智能体自己的 Action/Observation</b>：</p>
 * <ul>
 *   <li>ReasonTask 只对外输出思考与正文流，不得因此发出任何工具/动作类事件（工具事件只由 ActionTask 发）；</li>
 *   <li>搜索结果不得混入正文增量（否则外部检索内容会被渲染成模型自述）；</li>
 *   <li>服务端工具一经发起就已产生外部副作用与计费，流中断时不得重放整个请求。</li>
 * </ul>
 *
 * @author noear
 */
public class ReActServerToolStreamTest {
    /**
     * 一轮响应：思考(带签名) -> 服务端联网搜索 -> 搜索结果 -> 正文终答
     */
    private static final String SSE =
            "data: {\"type\":\"message_start\",\"message\":{\"id\":\"msg_1\",\"model\":\"claude-sonnet-4-5\",\"usage\":{\"input_tokens\":12,\"output_tokens\":0}}}\n\n" +
            "data: {\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"thinking\",\"thinking\":\"\"}}\n\n" +
            "data: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"thinking_delta\",\"thinking\":\"需要查一下天气\"}}\n\n" +
            "data: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"signature_delta\",\"signature\":\"sig_abc\"}}\n\n" +
            "data: {\"type\":\"content_block_stop\",\"index\":0}\n\n" +
            "data: {\"type\":\"content_block_start\",\"index\":1,\"content_block\":{\"type\":\"server_tool_use\",\"id\":\"srvtoolu_1\",\"name\":\"web_search\"}}\n\n" +
            "data: {\"type\":\"content_block_delta\",\"index\":1,\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"{\\\"query\\\":\\\"杭州天气\\\"}\"}}\n\n" +
            "data: {\"type\":\"content_block_stop\",\"index\":1}\n\n" +
            "data: {\"type\":\"content_block_start\",\"index\":2,\"content_block\":{\"type\":\"web_search_tool_result\",\"tool_use_id\":\"srvtoolu_1\",\"content\":[{\"type\":\"web_search_result\",\"text\":\"杭州今天晴，22 度\"}]}}\n\n" +
            "data: {\"type\":\"content_block_stop\",\"index\":2}\n\n" +
            "data: {\"type\":\"content_block_start\",\"index\":3,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}\n\n" +
            "data: {\"type\":\"content_block_delta\",\"index\":3,\"delta\":{\"type\":\"text_delta\",\"text\":\"杭州今天晴，22 度。\"}}\n\n" +
            "data: {\"type\":\"content_block_stop\",\"index\":3}\n\n" +
            "data: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"},\"usage\":{\"output_tokens\":9}}\n\n" +
            "data: {\"type\":\"message_stop\"}\n\n";

    /**
     * 只有「搜索已发起」：无思考、无正文、无搜索结果。
     * <p>用于隔离验证重试门控：旧白名单不包含 {@code SERVER_TOOL_START/ARGS_DELTA}，
     * 此处流中断会被当成「什么都没输出」而重放整个请求，造成联网搜索二次执行与计费。</p>
     */
    private static final String SSE_SEARCH_STARTED =
            "data: {\"type\":\"message_start\",\"message\":{\"id\":\"msg_2\",\"model\":\"claude-sonnet-4-5\",\"usage\":{\"input_tokens\":12,\"output_tokens\":0}}}\n\n" +
            "data: {\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"server_tool_use\",\"id\":\"srvtoolu_2\",\"name\":\"web_search\"}}\n\n" +
            "data: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"{\\\"query\\\":\\\"\u676d\u5dde\u5929\u6c14\\\"}\"}}\n\n";

    private HttpServer server;
    private String apiUrl;
    private final AtomicInteger requestCount = new AtomicInteger(0);

    @BeforeEach
    public void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/messages", this::handle);
        server.createContext("/v1/truncated", this::handleTruncated);
        server.start();

        apiUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/messages";
    }

    private String truncatedApiUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/truncated";
    }

    @AfterEach
    public void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    private void handle(HttpExchange exchange) throws IOException {
        requestCount.incrementAndGet();

        //把请求体读完，避免连接复用异常
        exchange.getRequestBody().read(new byte[8192]);

        byte[] body = SSE.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
        exchange.sendResponseHeaders(200, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    /**
     * 搜索已发起但流在结果前被截断：声明的 Content-Length 大于实际写入量，客户端读取时报 IO 异常
     */
    private void handleTruncated(HttpExchange exchange) throws IOException {
        requestCount.incrementAndGet();

        exchange.getRequestBody().read(new byte[8192]);

        byte[] body = SSE_SEARCH_STARTED.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
        //声明比实际更长，写完就关，制造不完整响应
        exchange.sendResponseHeaders(200, body.length + 4096);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
            out.flush();
        } catch (IOException ignored) {
            //客户端断开时服务端也会报错，与本用例无关
        }
    }

    @Test
    @DisplayName("服务端工具：不污染正文流、不冒充 Action")
    public void serverToolStaysOutOfReasonStream() {
        ChatModel chatModel = ChatModel.of(apiUrl)
                .apiKey("sk-test")
                .standard(ChatDialects.ANTHROPIC)
                .model("claude-sonnet-4-5")
                .build();

        ReActAgent agent = ReActAgent.of(chatModel).name("server-tool").build();
        AgentSession session = InMemoryAgentSession.of("stream_server_tool");

        List<AgentEvent> events = agent.prompt("杭州今天天气怎么样？")
                .session(session)
                .stream()
                .collectList()
                .block();

        assertNotNull(events);

        //1. 推理阶段不得发出任何动作/工具类事件（那是 ActionTask 的职责，本轮没有本地工具调用）
        for (AgentEvent event : events) {
            assertFalse(event instanceof AbsToolCallEvent,
                    "推理阶段不应发出工具事件: " + event.getClass().getSimpleName());
            assertFalse(event instanceof ActionStartEvent,
                    "推理阶段不应发出动作事件: " + event.getClass().getSimpleName());
            assertFalse(event instanceof ActionEndEvent,
                    "推理阶段不应发出动作事件: " + event.getClass().getSimpleName());
        }

        //2. 正文/思考增量只含模型自述，搜索结果不得混入
        List<ReasonDeltaEvent> deltas = events.stream()
                .filter(e -> e instanceof ReasonDeltaEvent)
                .map(e -> (ReasonDeltaEvent) e)
                .collect(Collectors.toList());

        assertFalse(deltas.isEmpty(), "应有思考与正文增量");

        StringBuilder text = new StringBuilder();
        StringBuilder thinking = new StringBuilder();
        for (ReasonDeltaEvent delta : deltas) {
            if (delta.isThinking()) {
                thinking.append(delta.getText());
            } else {
                text.append(delta.getText());
            }
        }

        assertEquals("需要查一下天气", thinking.toString());
        assertEquals("杭州今天晴，22 度。", text.toString(), "正文流只应是模型自述，不含搜索结果原文");

        //3. 推理结束事件仍在，且终态正文不含搜索原文（搜索结果既不发事件也不拼进正文）
        ReasonEndEvent reasonEnd = events.stream()
                .filter(e -> e instanceof ReasonEndEvent)
                .map(e -> (ReasonEndEvent) e)
                .findFirst()
                .orElse(null);
        assertNotNull(reasonEnd, "应有推理结束事件");

        ReActTrace trace = reasonEnd.getTrace();
        assertEquals("杭州今天晴，22 度。", trace.getFinalAnswer());
        assertFalse(trace.isAbnormal(), "正常结束");
    }

    @Test
    @DisplayName("服务端工具：搜索已发起后流中断，不得重放请求")
    public void serverToolStartedBlocksRetry() {
        ChatModel chatModel = ChatModel.of(truncatedApiUrl())
                .apiKey("sk-test")
                .standard(ChatDialects.ANTHROPIC)
                .model("claude-sonnet-4-5")
                //截断后客户端会一直等剩下的 body，靠读超时结束；超时本身是可重试异常，
                //正好用来验证「服务端工具已发起就不得重试」这一门控
                .timeout(Duration.ofSeconds(3))
                .build();

        ReActAgent agent = ReActAgent.of(chatModel).name("server-tool-retry").build();
        AgentSession session = InMemoryAgentSession.of("stream_server_tool_retry");

        requestCount.set(0);

        List<AgentEvent> events = agent.prompt("杭州今天天气怎么样？")
                .session(session)
                .stream()
                .collectList()
                .block();

        assertNotNull(events);

        //搜索已发起（已计费、已产生外部副作用），流中断不得重放整个请求
        assertEquals(1, requestCount.get(), "服务端工具已发起，不得重试导致二次执行");

        //确认确实走的是失败路径（否则上面的 requestCount==1 可能是「流平稳结束」的假绿）
        RunEndEvent runEnd = events.stream()
                .filter(e -> e instanceof RunEndEvent)
                .map(e -> (RunEndEvent) e)
                .findFirst()
                .orElse(null);
        assertNotNull(runEnd, "应有运行结束事件");
        assertTrue(runEnd.getTrace().isAbnormal(), "流被截断应以异常结束收尾");
    }
}
