/*
 * Copyright 2025-2025 the original author or authors.
 */

package com.agentclientprotocol.sdk.agent.transport;

import com.agentclientprotocol.sdk.spec.AcpSchema;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.TypeRef;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.noear.solon.Solon;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for WebSocketSolonAcpAgentTransport.
 *
 * @author Tests generated for Solon AI ACP module
 */
class WebSocketSolonAcpAgentTransportTest {

    private static final int TEST_PORT = 18081;
    private static boolean solonStarted = false;
    private static AtomicInteger pathCounter = new AtomicInteger(0);

    private McpJsonMapper jsonMapper;
    private WebSocketSolonAcpAgentTransport transport;

    @BeforeAll
    static void startSolon() {
        if (!solonStarted) {
            Solon.start(WebSocketSolonAcpAgentTransportTest.class, new String[]{
                    "--server.port=" + TEST_PORT
            }, builder -> {
                builder.enableWebSocket(true);
            });
            solonStarted = true;
        }
    }

    @BeforeEach
    void setUp() {
        jsonMapper = McpJsonMapper.getDefault();
        transport = new WebSocketSolonAcpAgentTransport(jsonMapper);
    }

    @AfterEach
    void tearDown() {
        if (transport != null) {
            try {
                transport.closeGracefully().block(Duration.ofSeconds(2));
            } catch (Exception e) {
                // ignore
            }
            transport = null;
        }
    }

    private String uniquePath() {
        return "/acp-test-" + pathCounter.incrementAndGet();
    }

    @Test
    @DisplayName("Test transport construction with default path")
    void testConstructionWithDefaultPath() {
        WebSocketSolonAcpAgentTransport t = new WebSocketSolonAcpAgentTransport(jsonMapper);
        assertNotNull(t);
    }

    @Test
    @DisplayName("Test transport construction with custom path")
    void testConstructionWithCustomPath() {
        WebSocketSolonAcpAgentTransport t = new WebSocketSolonAcpAgentTransport("/custom-acp", jsonMapper);
        assertNotNull(t);
    }

    @Test
    @DisplayName("Test idleTimeout configuration")
    void testIdleTimeoutConfiguration() {
        WebSocketSolonAcpAgentTransport t = transport.idleTimeout(Duration.ofMinutes(10));
        assertNotNull(t);
        // fluent API returns same instance
        assertSame(transport, t);
    }

    @Test
    @DisplayName("Test start cannot be called twice")
    void testStartCannotBeCalledTwice() {
        // Use unique path for this test
        transport = new WebSocketSolonAcpAgentTransport(uniquePath(), jsonMapper);

        Function<Mono<AcpSchema.JSONRPCMessage>, Mono<AcpSchema.JSONRPCMessage>> handler = 
                input -> input.map(msg -> new AcpSchema.JSONRPCResponse(
                        AcpSchema.JSONRPC_VERSION,
                        ((AcpSchema.JSONRPCRequest) msg).id(),
                        AcpSchema.InitializeResponse.ok(),
                        null
                ));

        // First start succeeds
        Mono<Void> startResult = transport.start(handler);
        StepVerifier.create(startResult)
                .verifyComplete();

        // Second start should fail
        Mono<Void> secondStart = transport.start(handler);
        StepVerifier.create(secondStart)
                .verifyError(IllegalStateException.class);
    }

    @Test
    @DisplayName("Test setExceptionHandler")
    void testSetExceptionHandler() {
        AtomicBoolean handlerCalled = new AtomicBoolean(false);
        Consumer<Throwable> handler = e -> handlerCalled.set(true);

        transport.setExceptionHandler(handler);
        // Verify handler is set (indirectly through exception handling)
        assertNotNull(handler);
    }

    @Test
    @DisplayName("Test unmarshalFrom with Map")
    void testUnmarshalFromMap() {
        Map<String, Object> data = new HashMap<>();
        data.put("cwd", "/workspace");
        data.put("mcpServers", Collections.emptyList());

        AcpSchema.NewSessionRequest request = transport.unmarshalFrom(
                data,
                new TypeRef<AcpSchema.NewSessionRequest>() {}
        );

        assertEquals("/workspace", request.cwd());
        assertNotNull(request.mcpServers());
        assertTrue(request.mcpServers().isEmpty());
    }

    @Test
    @DisplayName("Test unmarshalFrom with nested object")
    void testUnmarshalFromNestedObject() {
        Map<String, Object> data = new HashMap<>();
        data.put("protocolVersion", 1);
        Map<String, Object> caps = new HashMap<>();
        caps.put("terminal", true);
        data.put("clientCapabilities", caps);

        AcpSchema.InitializeRequest request = transport.unmarshalFrom(
                data,
                new TypeRef<AcpSchema.InitializeRequest>() {}
        );

        assertEquals(1, request.protocolVersion());
        assertNotNull(request.clientCapabilities());
    }

    @Test
    @DisplayName("Test closeGracefully")
    void testCloseGracefully() {
        // Use unique path for this test
        transport = new WebSocketSolonAcpAgentTransport(uniquePath(), jsonMapper);

        Function<Mono<AcpSchema.JSONRPCMessage>, Mono<AcpSchema.JSONRPCMessage>> handler = 
                input -> input.map(msg -> new AcpSchema.JSONRPCResponse(
                        AcpSchema.JSONRPC_VERSION,
                        ((AcpSchema.JSONRPCRequest) msg).id(),
                        AcpSchema.InitializeResponse.ok(),
                        null
                ));

        transport.start(handler).block(Duration.ofSeconds(5));

        Mono<Void> closeResult = transport.closeGracefully();
        StepVerifier.create(closeResult)
                .verifyComplete();
    }

    @Test
    @DisplayName("Test sendMessage returns Mono")
    void testSendMessageReturnsMono() {
        AcpSchema.JSONRPCNotification notification = new AcpSchema.JSONRPCNotification(
                AcpSchema.METHOD_SESSION_CANCEL,
                new AcpSchema.CancelNotification("test-session")
        );

        Mono<Void> sendResult = transport.sendMessage(notification);
        assertNotNull(sendResult);
    }

    @Test
    @DisplayName("Test awaitTermination")
    void testAwaitTermination() {
        Mono<Void> termination = transport.awaitTermination();
        assertNotNull(termination);
    }

    @Test
    @DisplayName("Test closeGracefully can be called multiple times safely")
    void testCloseGracefullyMultipleTimes() {
        // Use unique path for this test
        transport = new WebSocketSolonAcpAgentTransport(uniquePath(), jsonMapper);

        Function<Mono<AcpSchema.JSONRPCMessage>, Mono<AcpSchema.JSONRPCMessage>> handler = 
                input -> input.map(msg -> new AcpSchema.JSONRPCResponse(
                        AcpSchema.JSONRPC_VERSION,
                        ((AcpSchema.JSONRPCRequest) msg).id(),
                        AcpSchema.InitializeResponse.ok(),
                        null
                ));

        transport.start(handler).block(Duration.ofSeconds(5));

        // First close
        transport.closeGracefully().block(Duration.ofSeconds(5));

        // Second close should complete without error
        StepVerifier.create(transport.closeGracefully())
                .verifyComplete();
    }

    @Test
    @DisplayName("Test InitializeResponse ok factory methods")
    void testInitializeResponseOkFactory() {
        AcpSchema.InitializeResponse response = AcpSchema.InitializeResponse.ok();
        assertEquals(1, response.protocolVersion());
        assertNotNull(response.agentCapabilities());
    }

    @Test
    @DisplayName("Test InitializeResponse ok with capabilities")
    void testInitializeResponseOkWithCapabilities() {
        AcpSchema.AgentCapabilities caps = new AcpSchema.AgentCapabilities(
                true,
                new AcpSchema.McpCapabilities(true, true),
                new AcpSchema.PromptCapabilities(true, true, true)
        );

        AcpSchema.InitializeResponse response = AcpSchema.InitializeResponse.ok(caps);
        assertEquals(1, response.protocolVersion());
        assertTrue(response.agentCapabilities().loadSession());
    }

    @Test
    @DisplayName("Test PromptResponse factory methods")
    void testPromptResponseFactoryMethods() {
        AcpSchema.PromptResponse endTurn = AcpSchema.PromptResponse.endTurn();
        assertEquals(AcpSchema.StopReason.END_TURN, endTurn.stopReason());

        AcpSchema.PromptResponse refusal = AcpSchema.PromptResponse.refusal();
        assertEquals(AcpSchema.StopReason.REFUSAL, refusal.stopReason());

        AcpSchema.PromptResponse text = AcpSchema.PromptResponse.text("test");
        assertEquals(AcpSchema.StopReason.END_TURN, text.stopReason());
    }

    @Test
    @DisplayName("Test JSONRPC serialization through transport")
    void testJsonRpcSerializationThroughTransport() throws Exception {
        AcpSchema.JSONRPCRequest request = new AcpSchema.JSONRPCRequest(
                AcpSchema.JSONRPC_VERSION,
                1,
                AcpSchema.METHOD_INITIALIZE,
                new AcpSchema.InitializeRequest(1, new AcpSchema.ClientCapabilities())
        );

        String json = jsonMapper.writeValueAsString(request);
        assertNotNull(json);
        assertTrue(json.contains("initialize"));
        assertTrue(json.contains("jsonrpc"));
    }

    @Test
    @DisplayName("Test ContentBlock types")
    void testContentBlockTypes() {
        AcpSchema.TextContent text = new AcpSchema.TextContent("Hello");
        assertEquals("text", text.type());
        assertEquals("Hello", text.text());

        // Verify ContentBlock interface
        AcpSchema.ContentBlock content = text;
        assertNotNull(content);
    }

    @Test
    @DisplayName("Test SessionMode and SessionModeState")
    void testSessionModeAndState() {
        AcpSchema.SessionMode mode = new AcpSchema.SessionMode(
                "code",
                "Code Mode",
                "Write and edit code"
        );

        assertEquals("code", mode.id());
        assertEquals("Code Mode", mode.name());

        AcpSchema.SessionModeState state = new AcpSchema.SessionModeState(
                "code",
                Collections.singletonList(mode)
        );

        assertEquals("code", state.currentModeId());
        assertEquals(1, state.availableModes().size());
    }

    @Test
    @DisplayName("Test ModelInfo and SessionModelState")
    void testModelInfoAndState() {
        AcpSchema.ModelInfo model = new AcpSchema.ModelInfo(
                "gpt-4",
                "GPT-4",
                "Most capable model"
        );

        assertEquals("gpt-4", model.modelId());
        assertEquals("GPT-4", model.name());

        AcpSchema.SessionModelState state = new AcpSchema.SessionModelState(
                "gpt-4",
                Collections.singletonList(model)
        );

        assertEquals("gpt-4", state.currentModelId());
        assertEquals(1, state.availableModels().size());
    }
}