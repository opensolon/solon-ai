/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.integration;

import com.agentclientprotocol.sdk.agent.transport.WebSocketSolonAcpAgentTransport;
import com.agentclientprotocol.sdk.client.transport.WebSocketSolonAcpClientTransport;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import com.agentclientprotocol.sdk.spec.AcpSchema.JSONRPCMessage;
import com.agentclientprotocol.sdk.spec.AcpSchema.JSONRPCRequest;
import com.agentclientprotocol.sdk.spec.AcpSchema.JSONRPCResponse;
import io.modelcontextprotocol.json.McpJsonMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.noear.solon.Solon;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.net.URI;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ACP Transport 集成测试
 * 测试 Agent 和 Client 之间的真实 WebSocket 通信
 *
 * @author test
 */
@DisplayName("ACP Transport 集成测试")
class AcpTransportIntegrationTest {

    private static final String TEST_HOST = "localhost";
    private static final int TEST_PORT = 18080;
    private static final String TEST_WS_URL = "ws://" + TEST_HOST + ":" + TEST_PORT;

    private McpJsonMapper jsonMapper;
    private WebSocketSolonAcpAgentTransport agentTransport;
    private WebSocketSolonAcpClientTransport clientTransport;
    private AtomicReference<JSONRPCMessage> receivedByAgent;
    private AtomicReference<JSONRPCMessage> receivedByClient;
    private static boolean solonStarted = false;

    @BeforeEach
    void setUp() {
        jsonMapper = McpJsonMapper.getDefault();
        receivedByAgent = new AtomicReference<>();
        receivedByClient = new AtomicReference<>();

        // 启动 Solon 服务器（如果尚未启动）
        if (!solonStarted) {
            Solon.start(AcpTransportIntegrationTest.class, new String[]{
                    "--server.port=" + TEST_PORT
            }, builder -> {
                builder.enableWebSocket(true);
            });
            solonStarted = true;
        }
    }

    @AfterEach
    void tearDown() {
        // 关闭客户端
        if (clientTransport != null) {
            try {
                clientTransport.closeGracefully().block(Duration.ofSeconds(3));
            } catch (Exception e) {
                // ignore
            }
            clientTransport = null;
        }

        // 关闭服务端
        if (agentTransport != null) {
            try {
                agentTransport.closeGracefully().block(Duration.ofSeconds(3));
            } catch (Exception e) {
                // ignore
            }
            agentTransport = null;
        }
    }

    // ==================== 基础连接测试 ====================

    @Test
    @DisplayName("集成测试 - Agent 启动后 Client 可以连接")
    void testClientConnectToAgent() {
        // 1. 启动 Agent
        agentTransport = new WebSocketSolonAcpAgentTransport("/acp-connect", jsonMapper);
        StepVerifier.create(agentTransport.start(msg -> msg))
                .verifyComplete();

        // 等待 Agent 完全启动
        sleep(500);

        // 2. Client 连接
        clientTransport = new WebSocketSolonAcpClientTransport(
                URI.create(TEST_WS_URL + "/acp-connect"),
                jsonMapper
        );

        StepVerifier.create(clientTransport.connect(msg -> Mono.empty()))
                .expectComplete()
                .verify(Duration.ofSeconds(10));
    }

    @Test
    @DisplayName("集成测试 - Client 发送请求，Agent 接收")
    void testClientSendRequestAgentReceive() {
        // 1. 启动 Agent（记录收到的消息）
        agentTransport = new WebSocketSolonAcpAgentTransport("/acp-receive", jsonMapper);

        AtomicInteger requestCount = new AtomicInteger(0);

        Function<Mono<JSONRPCMessage>, Mono<JSONRPCMessage>> agentHandler = msgMono ->
                msgMono.doOnNext(msg -> {
                    requestCount.incrementAndGet();
                    receivedByAgent.set(msg);
                }).then(Mono.empty());

        StepVerifier.create(agentTransport.start(agentHandler))
                .verifyComplete();

        sleep(500);

        // 2. Client 连接
        clientTransport = new WebSocketSolonAcpClientTransport(
                URI.create(TEST_WS_URL + "/acp-receive"),
                jsonMapper
        );

        StepVerifier.create(clientTransport.connect(msg -> Mono.empty()))
                .expectComplete()
                .verify(Duration.ofSeconds(10));

        // 3. Client 发送请求
        JSONRPCRequest request = new JSONRPCRequest(
                AcpSchema.METHOD_INITIALIZE,
                "test-request-id-1",
                new AcpSchema.InitializeRequest(1, new AcpSchema.ClientCapabilities())
        );

        StepVerifier.create(clientTransport.sendMessage(request))
                .expectComplete()
                .verify(Duration.ofSeconds(5));

        // 4. 等待 Agent 接收到消息
        awaitCondition(() -> receivedByAgent.get() != null, Duration.ofSeconds(5));

        // 5. 验证 Agent 收到的消息
        assertNotNull(receivedByAgent.get());
        assertTrue(receivedByAgent.get() instanceof JSONRPCRequest);
        assertEquals("test-request-id-1", ((JSONRPCRequest) receivedByAgent.get()).id());
        assertEquals(1, requestCount.get());
    }

    @Test
    @DisplayName("集成测试 - 双向通信：Client 发送请求，Agent 返回响应")
    void testBidirectionalCommunication() {
        // 1. 启动 Agent（处理初始化请求并返回响应）
        agentTransport = new WebSocketSolonAcpAgentTransport("/acp-bidirectional", jsonMapper);

        Function<Mono<JSONRPCMessage>, Mono<JSONRPCMessage>> agentHandler = msgMono ->
                msgMono.flatMap(msg -> {
                    if (msg instanceof JSONRPCRequest) {
                        JSONRPCRequest request = (JSONRPCRequest) msg;
                        if (AcpSchema.METHOD_INITIALIZE.equals(request.method())) {
                            // 返回初始化响应
                            JSONRPCResponse response = new JSONRPCResponse(
                                    AcpSchema.JSONRPC_VERSION,
                                    request.id(),
                                    new AcpSchema.InitializeResponse(1, new AcpSchema.AgentCapabilities(), null),
                                    null
                            );
                            return Mono.just(response);
                        }
                    }
                    return Mono.empty();
                });

        StepVerifier.create(agentTransport.start(agentHandler))
                .verifyComplete();

        sleep(500);

        // 2. Client 连接
        clientTransport = new WebSocketSolonAcpClientTransport(
                URI.create(TEST_WS_URL + "/acp-bidirectional"),
                jsonMapper
        );

        AtomicReference<JSONRPCMessage> clientReceivedResponse = new AtomicReference<>();

        StepVerifier.create(clientTransport.connect(msgMono ->
                    msgMono.doOnNext(clientReceivedResponse::set).then(Mono.empty())
                ))
                .expectComplete()
                .verify(Duration.ofSeconds(10));

        // 3. Client 发送初始化请求
        JSONRPCRequest initRequest = new JSONRPCRequest(
                AcpSchema.METHOD_INITIALIZE,
                "init-request-1",
                new AcpSchema.InitializeRequest(1, new AcpSchema.ClientCapabilities())
        );

        StepVerifier.create(clientTransport.sendMessage(initRequest))
                .expectComplete()
                .verify(Duration.ofSeconds(5));

        // 4. 等待 Client 接收到响应
        awaitCondition(() -> clientReceivedResponse.get() != null, Duration.ofSeconds(5));

        // 5. 验证响应
        assertNotNull(clientReceivedResponse.get());
        assertTrue(clientReceivedResponse.get() instanceof JSONRPCResponse);
        assertEquals("init-request-1", ((JSONRPCResponse) clientReceivedResponse.get()).id());
    }

    @Test
    @DisplayName("集成测试 - 多个 Client 同时连接")
    void testMultipleClientsConnect() {
        // 1. 启动 Agent
        agentTransport = new WebSocketSolonAcpAgentTransport("/acp-multi", jsonMapper);

        AtomicInteger connectionCount = new AtomicInteger(0);

        Function<Mono<JSONRPCMessage>, Mono<JSONRPCMessage>> agentHandler = msgMono ->
                msgMono.doOnNext(msg -> connectionCount.incrementAndGet())
                        .then(Mono.empty());

        StepVerifier.create(agentTransport.start(agentHandler))
                .verifyComplete();

        sleep(500);

        // 2. 创建多个 Client 并连接
        int clientCount = 3;
        WebSocketSolonAcpClientTransport[] clients = new WebSocketSolonAcpClientTransport[clientCount];

        for (int i = 0; i < clientCount; i++) {
            clients[i] = new WebSocketSolonAcpClientTransport(
                    URI.create(TEST_WS_URL + "/acp-multi"),
                    jsonMapper
            );

            StepVerifier.create(clients[i].connect(msg -> Mono.empty()))
                    .expectComplete()
                    .verify(Duration.ofSeconds(10));
        }

        // 3. 每个 Client 发送一条消息
        for (int i = 0; i < clientCount; i++) {
            JSONRPCRequest request = new JSONRPCRequest(
                    AcpSchema.METHOD_INITIALIZE,
                    "client-" + i,
                    new AcpSchema.InitializeRequest(1, new AcpSchema.ClientCapabilities())
            );

            StepVerifier.create(clients[i].sendMessage(request))
                    .expectComplete()
                    .verify(Duration.ofSeconds(5));
        }

        // 4. 等待所有消息被处理
        awaitCondition(() -> connectionCount.get() == clientCount, Duration.ofSeconds(5));

        // 5. 验证所有消息都被接收
        assertEquals(clientCount, connectionCount.get());

        // 6. 关闭所有 Client
        for (int i = 0; i < clientCount; i++) {
            try {
                clients[i].closeGracefully().block(Duration.ofSeconds(3));
            } catch (Exception e) {
                // ignore
            }
        }
    }

    @Test
    @DisplayName("集成测试 - Client 断开后重连")
    void testClientReconnect() {
        // 1. 启动 Agent
        agentTransport = new WebSocketSolonAcpAgentTransport("/acp-reconnect", jsonMapper);

        StepVerifier.create(agentTransport.start(msg -> Mono.empty()))
                .verifyComplete();

        sleep(500);

        // 2. Client 第一次连接
        clientTransport = new WebSocketSolonAcpClientTransport(
                URI.create(TEST_WS_URL + "/acp-reconnect"),
                jsonMapper
        );

        StepVerifier.create(clientTransport.connect(msg -> Mono.empty()))
                .expectComplete()
                .verify(Duration.ofSeconds(10));

        // 3. 断开连接
        StepVerifier.create(clientTransport.closeGracefully())
                .expectComplete()
                .verify(Duration.ofSeconds(5));

        // 4. 创建新的 Client 实例重新连接
        WebSocketSolonAcpClientTransport newClientTransport = new WebSocketSolonAcpClientTransport(
                URI.create(TEST_WS_URL + "/acp-reconnect"),
                jsonMapper
        );

        StepVerifier.create(newClientTransport.connect(msg -> Mono.empty()))
                .expectComplete()
                .verify(Duration.ofSeconds(10));

        // 5. 清理
        try {
            newClientTransport.closeGracefully().block(Duration.ofSeconds(3));
        } catch (Exception e) {
            // ignore
        }
    }

    // ==================== 异常处理测试 ====================

    @Test
    @DisplayName("集成测试 - Agent 处理异常情况")
    void testAgentHandleException() {
        // 1. 启动 Agent（模拟处理异常）
        agentTransport = new WebSocketSolonAcpAgentTransport("/acp-error", jsonMapper);

        AtomicReference<Throwable> agentException = new AtomicReference<>();
        agentTransport.setExceptionHandler(agentException::set);

        Function<Mono<JSONRPCMessage>, Mono<JSONRPCMessage>> agentHandler = msgMono ->
                msgMono.flatMap(msg -> Mono.error(new RuntimeException("Simulated processing error")));

        StepVerifier.create(agentTransport.start(agentHandler))
                .verifyComplete();

        sleep(500);

        // 2. Client 连接
        clientTransport = new WebSocketSolonAcpClientTransport(
                URI.create(TEST_WS_URL + "/acp-error"),
                jsonMapper
        );

        StepVerifier.create(clientTransport.connect(msg -> Mono.empty()))
                .expectComplete()
                .verify(Duration.ofSeconds(10));

        // 3. Client 发送请求
        JSONRPCRequest request = new JSONRPCRequest(
                AcpSchema.METHOD_INITIALIZE,
                "error-test-1",
                new AcpSchema.InitializeRequest(1, new AcpSchema.ClientCapabilities())
        );

        // 发送应该成功（异常在 Agent 端被处理）
        StepVerifier.create(clientTransport.sendMessage(request))
                .expectComplete()
                .verify(Duration.ofSeconds(5));
    }

    // ==================== 辅助方法 ====================

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void awaitCondition(java.util.function.Supplier<Boolean> condition, Duration timeout) {
        long start = System.currentTimeMillis();
        long timeoutMs = timeout.toMillis();
        while (!condition.get() && (System.currentTimeMillis() - start) < timeoutMs) {
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }
}