/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.agent.transport;

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
import org.noear.solon.net.websocket.WebSocketRouter;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/**
 * WebSocketSolonAcpAgentTransport 单元测试
 *
 * @author test
 */
@DisplayName("WebSocketSolonAcpAgentTransport 测试")
class WebSocketSolonAcpAgentTransportTest {

    private McpJsonMapper jsonMapper;
    private WebSocketSolonAcpAgentTransport transport;

    @BeforeEach
    void setUp() {
        jsonMapper = McpJsonMapper.getDefault();
        // 确保 Solon 已启动（用于 WebSocket 路由）
        if (Solon.app() == null) {
            Solon.start(WebSocketSolonAcpAgentTransportTest.class, new String[]{}, builder -> {
                builder.enableWebSocket(true);
            });
        }
    }

    @AfterEach
    void tearDown() {
        if (transport != null) {
            transport.closeGracefully().block(Duration.ofSeconds(5));
        }
    }

    // ==================== 构造函数测试 ====================

    @Test
    @DisplayName("构造函数 - 正常创建（使用默认路径）")
    void testConstructorWithDefaultPath() {
        transport = new WebSocketSolonAcpAgentTransport(jsonMapper);
        assertNotNull(transport);
        assertEquals(WebSocketSolonAcpAgentTransport.DEFAULT_ACP_PATH, "/acp");
    }

    @Test
    @DisplayName("构造函数 - 正常创建（使用自定义路径）")
    void testConstructorWithCustomPath() {
        String customPath = "/custom-acp";
        transport = new WebSocketSolonAcpAgentTransport(customPath, jsonMapper);
        assertNotNull(transport);
    }

    @Test
    @DisplayName("构造函数 - 路径为空应抛出异常")
    void testConstructorWithEmptyPath() {
        assertThrows(IllegalArgumentException.class, () -> {
            new WebSocketSolonAcpAgentTransport("", jsonMapper);
        });
    }

    @Test
    @DisplayName("构造函数 - 路径为 null 应抛出异常")
    void testConstructorWithNullPath() {
        assertThrows(IllegalArgumentException.class, () -> {
            new WebSocketSolonAcpAgentTransport(null, jsonMapper);
        });
    }

    @Test
    @DisplayName("构造函数 - JsonMapper 为 null 应抛出异常")
    void testConstructorWithNullJsonMapper() {
        assertThrows(IllegalArgumentException.class, () -> {
            new WebSocketSolonAcpAgentTransport("/acp", null);
        });
    }

    // ==================== idleTimeout 配置测试 ====================

    @Test
    @DisplayName("idleTimeout - 正常设置超时时间")
    void testIdleTimeoutSetting() {
        transport = new WebSocketSolonAcpAgentTransport(jsonMapper);
        Duration timeout = Duration.ofMinutes(10);
        transport.idleTimeout(timeout);
        assertNotNull(transport);
    }

    // ==================== start() 方法测试 ====================

    @Test
    @DisplayName("start - 正常启动")
    void testStartSuccessfully() {
        transport = new WebSocketSolonAcpAgentTransport("/test-acp-1", jsonMapper);

        Function<Mono<JSONRPCMessage>, Mono<JSONRPCMessage>> handler = msg -> msg;

        StepVerifier.create(transport.start(handler))
                .verifyComplete();
    }

    @Test
    @DisplayName("start - 重复启动应返回错误")
    void testStartTwiceShouldFail() {
        transport = new WebSocketSolonAcpAgentTransport("/test-acp-2", jsonMapper);

        Function<Mono<JSONRPCMessage>, Mono<JSONRPCMessage>> handler = msg -> msg;

        // 第一次启动
        transport.start(handler).block(Duration.ofSeconds(5));

        // 第二次启动应失败
        StepVerifier.create(transport.start(handler))
                .expectError(IllegalStateException.class)
                .verify(Duration.ofSeconds(5));
    }

    // ==================== sendMessage() 方法测试 ====================

    @Test
    @DisplayName("sendMessage - 广播消息到所有活跃会话")
    void testSendMessageBroadcast() {
        transport = new WebSocketSolonAcpAgentTransport("/test-acp-3", jsonMapper);
        transport.start(msg -> msg).block(Duration.ofSeconds(5));

        JSONRPCMessage message = new JSONRPCResponse(
                AcpSchema.JSONRPC_VERSION,
                "test-id",
                "test-result",
                null
        );

        StepVerifier.create(transport.sendMessage(message))
                .verifyComplete();
    }

    // ==================== closeGracefully() 方法测试 ====================

    @Test
    @DisplayName("closeGracefully - 正常关闭")
    void testCloseGracefully() {
        transport = new WebSocketSolonAcpAgentTransport("/test-acp-4", jsonMapper);
        transport.start(msg -> msg).block(Duration.ofSeconds(5));

        StepVerifier.create(transport.closeGracefully())
                .verifyComplete();
    }

    @Test
    @DisplayName("closeGracefully - 重复关闭不会出错")
    void testCloseGracefullyTwice() {
        transport = new WebSocketSolonAcpAgentTransport("/test-acp-5", jsonMapper);
        transport.start(msg -> msg).block(Duration.ofSeconds(5));

        // 第一次关闭
        transport.closeGracefully().block(Duration.ofSeconds(5));

        // 第二次关闭不应出错
        StepVerifier.create(transport.closeGracefully())
                .verifyComplete();
    }

    // ==================== awaitTermination() 方法测试 ====================

    @Test
    @DisplayName("awaitTermination - 等待终止信号")
    void testAwaitTermination() {
        transport = new WebSocketSolonAcpAgentTransport("/test-acp-6", jsonMapper);
        transport.start(msg -> msg).block(Duration.ofSeconds(5));

        AtomicBoolean completed = new AtomicBoolean(false);

        // 在关闭后，awaitTermination 应完成
        transport.closeGracefully().block(Duration.ofSeconds(5));

        StepVerifier.create(transport.awaitTermination())
                .verifyComplete();
    }

    // ==================== setExceptionHandler() 方法测试 ====================

    @Test
    @DisplayName("setExceptionHandler - 设置异常处理器")
    void testSetExceptionHandler() {
        transport = new WebSocketSolonAcpAgentTransport("/test-acp-7", jsonMapper);

        AtomicReference<Throwable> capturedException = new AtomicReference<>();
        transport.setExceptionHandler(capturedException::set);

        assertNotNull(transport);
    }

    // ==================== unmarshalFrom() 方法测试 ====================

    @Test
    @DisplayName("unmarshalFrom - 正常反序列化")
    void testUnmarshalFrom() {
        transport = new WebSocketSolonAcpAgentTransport(jsonMapper);

        AcpSchema.InitializeRequest request = new AcpSchema.InitializeRequest(
                1,
                new AcpSchema.ClientCapabilities()
        );

        Object data = jsonMapper.convertValue(request, Object.class);

        AcpSchema.InitializeRequest result = transport.unmarshalFrom(
                data,
                new io.modelcontextprotocol.json.TypeRef<AcpSchema.InitializeRequest>() {}
        );

        assertNotNull(result);
        assertEquals(1, result.protocolVersion());
    }

    // ==================== WebSocket 端点测试 ====================

    @Test
    @DisplayName("AcpWebSocketEndpoint - 创建端点实例")
    void testWebSocketEndpointCreation() {
        transport = new WebSocketSolonAcpAgentTransport("/test-acp-8", jsonMapper);
        WebSocketSolonAcpAgentTransport.AcpWebSocketEndpoint endpoint =
                transport.new AcpWebSocketEndpoint();

        assertNotNull(endpoint);
    }
}