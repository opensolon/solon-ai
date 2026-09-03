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
package features.ai.agent.stream;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.agent.Agent;
import org.noear.solon.ai.agent.AgentEvent;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.agent.simple.SimpleAgent;
import org.noear.solon.ai.agent.simple.SimpleInterceptor;
import org.noear.solon.ai.agent.simple.SimpleTrace;
import org.noear.solon.ai.agent.team.TeamAgent;
import org.noear.solon.ai.agent.team.TeamTrace;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.dialect.ChatDialects;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 订阅端中途取消时的流式契约（离线 mock 端点）
 *
 * <p>4.1 起终态取自 {@code RESPONSE_END}（或分步终态 {@code STEP_END}）携带的聚合，而
 * {@code takeUntil(isStreamCancelled)} 会在订阅端取消时提前完成事件流——若取消发生在首轮未完成时，
 * 流中既无 {@code RESPONSE_END} 也无 {@code STEP_END}，归约结果为 <b>null</b>。</p>
 *
 * <p>因此所有归约调用点都必须判 null：直接 {@code response.isEmpty()} 会 NPE
 * （旧的 {@code blockLast()} 在同场景返回的是最后一帧，不为 null，所以这是 4.1 新引入的风险点）。
 * 取消也不该被当成模型故障去重试：重放同样会被立即取消，只会白花模型调用。</p>
 *
 * @author noear
 */
public class AgentStreamCancelTest {
    /**
     * 思考 + 多段正文：保证订阅端取消发生在模型流进行中（而非流已结束之后）
     */
    private static final String SSE =
            "data: {\"type\":\"message_start\",\"message\":{\"id\":\"msg_1\",\"model\":\"claude-sonnet-4-5\",\"usage\":{\"input_tokens\":8,\"output_tokens\":0}}}\n\n" +
            "data: {\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}\n\n" +
            "data: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"第一段\"}}\n\n" +
            "data: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"第二段\"}}\n\n" +
            "data: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"第三段\"}}\n\n" +
            "data: {\"type\":\"content_block_stop\",\"index\":0}\n\n" +
            "data: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"},\"usage\":{\"output_tokens\":6}}\n\n" +
            "data: {\"type\":\"message_stop\"}\n\n";

    private HttpServer server;
    private String apiUrl;

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
        //取消会中断执行线程，清理中断位，避免影响后续用例
        Thread.interrupted();
    }

    private void handle(HttpExchange exchange) throws IOException {
        exchange.getRequestBody().read(new byte[8192]);

        byte[] body = SSE.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
        exchange.sendResponseHeaders(200, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    private ChatModel chatModel() {
        return ChatModel.of(apiUrl)
                .apiKey("sk-test")
                .standard(ChatDialects.ANTHROPIC)
                .model("claude-sonnet-4-5")
                .build();
    }

    @Test
    @DisplayName("Simple：订阅端取消后仍能优雅收尾（归约为 null 不得 NPE）")
    public void simpleAgentCancelledMidStream() {
        AtomicBoolean ended = new AtomicBoolean(false);

        SimpleAgent agent = SimpleAgent.of(chatModel())
                .name("cancel_simple")
                .defaultInterceptorAdd(new SimpleInterceptor() {
                    @Override
                    public void onSimpleEnd(SimpleTrace trace) {
                        ended.set(true);
                    }
                })
                .build();

        AgentSession session = InMemoryAgentSession.of("cancel_simple");

        //take(2)：第 1 个是 SimpleStartEvent，第 2 个是首个正文增量，之后即取消
        List<AgentEvent> events = assertDoesNotThrow(() -> agent.prompt("你好")
                .session(session)
                .stream()
                .take(2)
                .collectList()
                .block());

        assertNotNull(events);
        assertEquals(2, events.size());

        //收尾钩子必须跑到：NPE 会让 callWithRetry 直接抛出，onSimpleEnd 永远不会执行
        assertTrue(ended.get(), "订阅端取消不应让流程半途抛出，收尾钩子仍要执行");
    }

    @Test
    @DisplayName("Supervisor：订阅端取消不得被记成团队致命错误")
    public void supervisorCancelledMidStream() {
        ChatModel chatModel = chatModel();

        Agent member = SimpleAgent.of(chatModel)
                .name("worker")
                .role("执行者")
                .instruction("直接回答。")
                .build();

        TeamAgent team = TeamAgent.of(chatModel)
                .name("cancel_team")
                .agentAdd(member)
                .maxTurns(3)
                .build();

        AgentSession session = InMemoryAgentSession.of("cancel_team");

        //take(2)：第 1 个是 TeamStartEvent，第 2 个是 Supervisor 的首个增量，之后即取消
        List<AgentEvent> events = assertDoesNotThrow(() -> team.prompt("你好")
                .session(session)
                .stream()
                .take(2)
                .collectList()
                .block());

        assertNotNull(events);

        TeamTrace trace = TeamTrace.getCurrent(session.getContext());
        if (trace != null) {
            for (TeamTrace.TeamRecord record : trace.getRecords()) {
                assertFalse(String.valueOf(record.getContent()).contains("Runtime Error"),
                        "订阅端取消不是致命错误，不应留下 Runtime Error 记录: " + record.getContent());
            }
        }
    }
}
