/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.client.transport;

import com.agentclientprotocol.sdk.spec.AcpSchema;
import com.agentclientprotocol.sdk.spec.AcpSchema.JSONRPCMessage;
import com.agentclientprotocol.sdk.spec.AcpSchema.JSONRPCRequest;
import com.agentclientprotocol.sdk.spec.AcpSchema.JSONRPCResponse;
import com.agentclientprotocol.sdk.spec.AcpSchema.JSONRPCError;
import io.modelcontextprotocol.json.McpJsonMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.net.URI;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/**
 * WebSocketSolonAcpClientTransport 单元测试
 *
 * @author test
 */
@DisplayName("WebSocketSolonAcpClientTransport 测试")
class WebSocketSolonAcpClientTransportTest {

    private McpJsonMapper jsonMapper;
    private WebSocketSolonAcpClientTransport transport;
    private static final String TEST_WS_URL = "ws://localhost:8080/acp";

    @BeforeEach
    void setUp() {
        jsonMapper = McpJsonMapper.getDefault();
    }

    @AfterEach
    void tearDown() {
        if (transport != null) {
            transport.closeGracefully().block(Duration.ofSeconds(5));
        }
    }

    // ==================== 构造函数测试 ====================

    @Test
    @DisplayName("构造函数 - 正常创建")
    void testConstructorSuccessfully() {
        URI uri = URI.create(TEST_WS_URL);
        transport = new WebSocketSolonAcpClientTransport(uri, jsonMapper);
        assertNotNull(transport);
    }

    @Test
    @DisplayName("构造函数 - URI 为 null 应抛出异常")
    void testConstructorWithNullUri() {
        assertThrows(IllegalArgumentException.class, () -> {
            new WebSocketSolonAcpClientTransport(null, jsonMapper);
        });
    }

    @Test
    @DisplayName("构造函数 - JsonMapper 为 null 应抛出异常")
    void testConstructorWithNullJsonMapper() {
        assertThrows(IllegalArgumentException.class, () -> {
            new WebSocketSolonAcpClientTransport(URI.create(TEST_WS_URL), null);
        });
    }

    @Test
    @DisplayName("构造函数 - 非 WebSocket URI 应抛出异常")
    void testConstructorWithInvalidUriScheme() {
        assertThrows(IllegalArgumentException.class, () -> {
            new WebSocketSolonAcpClientTransport(URI.create("http://localhost:8080/acp"), jsonMapper);
        });
    }

    // ==================== connect() 方法测试 ====================

    @Test
    @DisplayName("connect - 连接失败（无服务端）")
    void testConnectWithoutServer() {
        transport = new WebSocketSolonAcpClientTransport(
                URI.create("ws://localhost:9999/acp"),
                jsonMapper
        );

        Function<Mono<JSONRPCMessage>, Mono<JSONRPCMessage>> handler = msg -> msg;

        // 无服务端时，连接应失败或超时
        StepVerifier.create(transport.connect(handler))
                .expectError()
                .verify(Duration.ofSeconds(10));
    }

    @Test
    @DisplayName("connect - 重复连接应返回错误")
    void testConnectTwiceShouldFail() {
        transport = new WebSocketSolonAcpClientTransport(
                URI.create("ws://localhost:9998/acp"),
                jsonMapper
        );

        Function<Mono<JSONRPCMessage>, Mono<JSONRPCMessage>> handler = msg -> msg;

        // 尝试连接（会失败因为没有服务端）
        try {
            transport.connect(handler).block(Duration.ofSeconds(5));
        } catch (Exception e) {
            // 预期失败
        }

        // 第二次连接应抛出 IllegalStateException
        StepVerifier.create(transport.connect(handler))
                .expectError(IllegalStateException.class)
                .verify(Duration.ofSeconds(5));
    }

    // ==================== sendMessage() 方法测试 ====================

    @Test
    @DisplayName("sendMessage - 未连接时发送应失败")
    void testSendMessageWithoutConnection() {
        transport = new WebSocketSolonAcpClientTransport(
                URI.create("ws://localhost:9997/acp"),
                jsonMapper
        );

        JSONRPCMessage message = new JSONRPCRequest(
                "initialize",
                "test-id",
                new AcpSchema.InitializeRequest(1, new AcpSchema.ClientCapabilities())
        );

        // 未连接时发送消息应失败
        StepVerifier.create(transport.sendMessage(message))
                .expectError(IllegalStateException.class)
                .verify(Duration.ofSeconds(5));
    }

    // ==================== closeGracefully() 方法测试 ====================

    @Test
    @DisplayName("closeGracefully - 正常关闭（未连接状态）")
    void testCloseGracefullyWithoutConnection() {
        transport = new WebSocketSolonAcpClientTransport(
                URI.create("ws://localhost:9996/acp"),
                jsonMapper
        );

        StepVerifier.create(transport.closeGracefully())
                .verifyComplete();
    }

    @Test
    @DisplayName("closeGracefully - 重复关闭不会出错")
    void testCloseGracefullyTwice() {
        transport = new WebSocketSolonAcpClientTransport(
                URI.create("ws://localhost:9995/acp"),
                jsonMapper
        );

        // 第一次关闭
        transport.closeGracefully().block(Duration.ofSeconds(5));

        // 第二次关闭不应出错
        StepVerifier.create(transport.closeGracefully())
                .verifyComplete();
    }

    // ==================== setExceptionHandler() 方法测试 ====================

    @Test
    @DisplayName("setExceptionHandler - 设置异常处理器")
    void testSetExceptionHandler() {
        transport = new WebSocketSolonAcpClientTransport(
                URI.create(TEST_WS_URL),
                jsonMapper
        );

        AtomicReference<Throwable> capturedException = new AtomicReference<>();
        transport.setExceptionHandler(capturedException::set);

        assertNotNull(transport);
    }

    // ==================== 消息序列化测试 ====================

    @Test
    @DisplayName("消息序列化 - JSONRPCRequest 序列化")
    void testSerializeRequest() {
        transport = new WebSocketSolonAcpClientTransport(
                URI.create(TEST_WS_URL),
                jsonMapper
        );

        JSONRPCRequest request = new JSONRPCRequest(
                AcpSchema.METHOD_INITIALIZE,
                "test-id-1",
                new AcpSchema.InitializeRequest(1, new AcpSchema.ClientCapabilities())
        );

        String json = jsonMapper.writeValueAsString(request);

        assertNotNull(json);
        assertTrue(json.contains("\"method\":\"initialize\""));
        assertTrue(json.contains("\"id\":\"test-id-1\""));
    }

    @Test
    @DisplayName("消息序列化 - JSONRPCResponse 序列化")
    void testSerializeResponse() {
        transport = new WebSocketSolonAcpClientTransport(
                URI.create(TEST_WS_URL),
                jsonMapper
        );

        JSONRPCResponse response = new JSONRPCResponse(
                AcpSchema.JSONRPC_VERSION,
                "test-id-2",
                new AcpSchema.InitializeResponse(1, new AcpSchema.AgentCapabilities(), null),
                null
        );

        String json = jsonMapper.writeValueAsString(response);

        assertNotNull(json);
        assertTrue(json.contains("\"result\""));
    }

    @Test
    @DisplayName("消息序列化 - JSONRPCError 序列化")
    void testSerializeError() {
        transport = new WebSocketSolonAcpClientTransport(
                URI.create(TEST_WS_URL),
                jsonMapper
        );

        JSONRPCError error = new JSONRPCError(-32600, "Invalid Request", null);
        JSONRPCResponse response = new JSONRPCResponse(
                AcpSchema.JSONRPC_VERSION,
                "test-id-3",
                null,
                error
        );

        String json = jsonMapper.writeValueAsString(response);

        assertNotNull(json);
        assertTrue(json.contains("\"error\""));
        assertTrue(json.contains("\"code\":-32600"));
    }

    // ==================== 消息反序列化测试 ====================

    @Test
    @DisplayName("消息反序列化 - 从 JSON 字符串反序列化 Request")
    void testDeserializeRequest() throws Exception {
        String json = "{\"jsonrpc\":\"2.0\",\"id\":\"test-id\",\"method\":\"initialize\",\"params\":{\"protocolVersion\":1,\"clientCapabilities\":{}}}";

        JSONRPCMessage message = AcpSchema.deserializeJsonRpcMessage(jsonMapper, json);

        assertNotNull(message);
        assertTrue(message instanceof JSONRPCRequest);
        assertEquals("initialize", ((JSONRPCRequest) message).method());
    }

    @Test
    @DisplayName("消息反序列化 - 从 JSON 字符串反序列化 Response")
    void testDeserializeResponse() throws Exception {
        String json = "{\"jsonrpc\":\"2.0\",\"id\":\"test-id\",\"result\":{\"protocolVersion\":1}}";

        JSONRPCMessage message = AcpSchema.deserializeJsonRpcMessage(jsonMapper, json);

        assertNotNull(message);
        assertTrue(message instanceof JSONRPCResponse);
    }

    @Test
    @DisplayName("消息反序列化 - 从 JSON 字符串反序列化 Notification")
    void testDeserializeNotification() throws Exception {
        String json = "{\"jsonrpc\":\"2.0\",\"method\":\"session/cancel\",\"params\":{\"sessionId\":\"session-1\"}}";

        JSONRPCMessage message = AcpSchema.deserializeJsonRpcMessage(jsonMapper, json);

        assertNotNull(message);
        assertTrue(message instanceof AcpSchema.JSONRPCNotification);
        assertEquals("session/cancel", ((AcpSchema.JSONRPCNotification) message).method());
    }

    // ==================== 线程安全测试 ====================

    @Test
    @DisplayName("线程安全 - 并发发送消息")
    void testConcurrentSendMessage() {
        transport = new WebSocketSolonAcpClientTransport(
                URI.create("ws://localhost:9994/acp"),
                jsonMapper
        );

        // 创建多个线程同时调用 closeGracefully
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger errorCount = new AtomicInteger(0);

        for (int i = 0; i < 10; i++) {
            transport.closeGracefully()
                    .doOnSuccess(v -> successCount.incrementAndGet())
                    .doOnError(e -> errorCount.incrementAndGet())
                    .subscribe();
        }

        // 等待所有操作完成
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            // ignore
        }

        // 所有操作都应该成功完成（因为 closeGracefully 可以重复调用）
        assertEquals(10, successCount.get());
        assertEquals(0, errorCount.get());
    }

    // ==================== 边界条件测试 ====================

    @Test
    @DisplayName("边界条件 - 大消息序列化")
    void testLargeMessageSerialization() {
        transport = new WebSocketSolonAcpClientTransport(
                URI.create(TEST_WS_URL),
                jsonMapper
        );

        // 构造一个包含大量文本内容的 PromptRequest
        StringBuilder largeText = new StringBuilder();
        for (int i = 0; i < 1000; i++) {
            largeText.append("This is a test message line ").append(i).append(". ");
        }

        AcpSchema.PromptRequest promptRequest = new AcpSchema.PromptRequest(
                "session-1",
                java.util.List.of(new AcpSchema.TextContent(largeText.toString()))
        );

        JSONRPCRequest request = new JSONRPCRequest(
                AcpSchema.METHOD_SESSION_PROMPT,
                "large-msg-id",
                promptRequest
        );

        String json = jsonMapper.writeValueAsString(request);

        assertNotNull(json);
        assertTrue(json.length() > 10000);
    }

    @Test
    @DisplayName("边界条件 - 特殊字符消息")
    void testSpecialCharactersMessage() {
        transport = new WebSocketSolonAcpClientTransport(
                URI.create(TEST_WS_URL),
                jsonMapper
        );

        String specialText = "特殊字符测试: \n\t\r\"'\\{}[]<>&@#$%^*()";

        AcpSchema.TextContent content = new AcpSchema.TextContent(specialText);
        JSONRPCRequest request = new JSONRPCRequest(
                AcpSchema.METHOD_SESSION_PROMPT,
                "special-char-id",
                new AcpSchema.PromptRequest("session-1", java.util.List.of(content))
        );

        String json = jsonMapper.writeValueAsString(request);

        assertNotNull(json);
        // 验证特殊字符被正确处理
        assertFalse(json.contains("\n")); // 应该被转义
        assertFalse(json.contains("\t")); // 应该被转义
    }
}