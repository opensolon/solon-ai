/*
 * Copyright 2025-2025 the original author or authors.
 */

package com.agentclientprotocol.sdk.client.transport;

import com.agentclientprotocol.sdk.spec.AcpSchema;
import io.modelcontextprotocol.json.McpJsonMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for WebSocketSolonAcpClientTransport.
 *
 * @author Tests generated for Solon AI ACP module
 */
class WebSocketSolonAcpClientTransportTest {

    private McpJsonMapper jsonMapper;

    @BeforeEach
    void setUp() {
        jsonMapper = McpJsonMapper.getDefault();
    }

    @Test
    @DisplayName("Test AcpSchema constants")
    void testAcpSchemaConstants() {
        // Verify method constants exist
        assertEquals("initialize", AcpSchema.METHOD_INITIALIZE);
        assertEquals("session/new", AcpSchema.METHOD_SESSION_NEW);
        assertEquals("session/load", AcpSchema.METHOD_SESSION_LOAD);
        assertEquals("session/prompt", AcpSchema.METHOD_SESSION_PROMPT);
        assertEquals("session/cancel", AcpSchema.METHOD_SESSION_CANCEL);
    }

    @Test
    @DisplayName("Test JSONRPCRequest construction")
    void testJsonRpcRequestConstruction() {
        AcpSchema.JSONRPCRequest request = new AcpSchema.JSONRPCRequest(
                AcpSchema.JSONRPC_VERSION,
                1,
                AcpSchema.METHOD_INITIALIZE,
                null
        );

        assertEquals(AcpSchema.JSONRPC_VERSION, request.jsonrpc());
        assertEquals(1, request.id());
        assertEquals(AcpSchema.METHOD_INITIALIZE, request.method());
        assertNull(request.params());
    }

    @Test
    @DisplayName("Test JSONRPCResponse construction")
    void testJsonRpcResponseConstruction() {
        AcpSchema.JSONRPCResponse response = new AcpSchema.JSONRPCResponse(
                AcpSchema.JSONRPC_VERSION,
                1,
                "test-result",
                null
        );

        assertEquals(AcpSchema.JSONRPC_VERSION, response.jsonrpc());
        assertEquals(1, response.id());
        assertEquals("test-result", response.result());
        assertNull(response.error());
    }

    @Test
    @DisplayName("Test JSONRPCNotification construction")
    void testJsonRpcNotificationConstruction() {
        AcpSchema.JSONRPCNotification notification = new AcpSchema.JSONRPCNotification(
                AcpSchema.JSONRPC_VERSION,
                AcpSchema.METHOD_SESSION_CANCEL,
                new AcpSchema.CancelNotification("session-123")
        );

        assertEquals(AcpSchema.JSONRPC_VERSION, notification.jsonrpc());
        assertEquals(AcpSchema.METHOD_SESSION_CANCEL, notification.method());
        assertNotNull(notification.params());
    }

    @Test
    @DisplayName("Test InitializeRequest construction")
    void testInitializeRequestConstruction() {
        AcpSchema.ClientCapabilities clientCaps = new AcpSchema.ClientCapabilities();
        AcpSchema.InitializeRequest initRequest = new AcpSchema.InitializeRequest(
                AcpSchema.LATEST_PROTOCOL_VERSION,
                clientCaps
        );

        assertEquals(AcpSchema.LATEST_PROTOCOL_VERSION, initRequest.protocolVersion());
        assertNotNull(initRequest.clientCapabilities());
    }

    @Test
    @DisplayName("Test InitializeResponse construction")
    void testInitializeResponseConstruction() {
        AcpSchema.InitializeResponse response = AcpSchema.InitializeResponse.ok();

        assertEquals(1, response.protocolVersion());
        assertNotNull(response.agentCapabilities());
    }

    @Test
    @DisplayName("Test NewSessionRequest construction")
    void testNewSessionRequestConstruction() {
        AcpSchema.NewSessionRequest request = new AcpSchema.NewSessionRequest(
                "/workspace",
                null
        );

        assertEquals("/workspace", request.cwd());
        assertNull(request.mcpServers());
    }

    @Test
    @DisplayName("Test NewSessionResponse construction")
    void testNewSessionResponseConstruction() {
        AcpSchema.NewSessionResponse response = new AcpSchema.NewSessionResponse(
                "session-123",
                null,
                null
        );

        assertEquals("session-123", response.sessionId());
    }

    @Test
    @DisplayName("Test PromptRequest construction")
    void testPromptRequestConstruction() {
        AcpSchema.TextContent textContent = new AcpSchema.TextContent("Hello, Agent!");
        AcpSchema.PromptRequest request = new AcpSchema.PromptRequest(
                "session-123",
                Collections.singletonList(textContent)
        );

        assertEquals("session-123", request.sessionId());
        assertNotNull(request.prompt());
        assertEquals(1, request.prompt().size());
        assertEquals("Hello, Agent!", request.text());
    }

    @Test
    @DisplayName("Test PromptResponse construction")
    void testPromptResponseConstruction() {
        AcpSchema.PromptResponse response = AcpSchema.PromptResponse.endTurn();

        assertEquals(AcpSchema.StopReason.END_TURN, response.stopReason());
    }

    @Test
    @DisplayName("Test TextContent construction")
    void testTextContentConstruction() {
        AcpSchema.TextContent content = new AcpSchema.TextContent("test message");

        assertEquals("text", content.type());
        assertEquals("test message", content.text());
    }

    @Test
    @DisplayName("Test CancelNotification construction")
    void testCancelNotificationConstruction() {
        AcpSchema.CancelNotification cancel = new AcpSchema.CancelNotification("session-123");

        assertEquals("session-123", cancel.sessionId());
    }

    @Test
    @DisplayName("Test JSON serialization and deserialization")
    void testJsonSerializationDeserialization() throws Exception {
        // Create a request
        AcpSchema.JSONRPCRequest request = new AcpSchema.JSONRPCRequest(
                AcpSchema.JSONRPC_VERSION,
                1,
                AcpSchema.METHOD_INITIALIZE,
                new AcpSchema.InitializeRequest(
                        AcpSchema.LATEST_PROTOCOL_VERSION,
                        new AcpSchema.ClientCapabilities()
                )
        );

        // Serialize
        String json = jsonMapper.writeValueAsString(request);

        // Deserialize
        AcpSchema.JSONRPCMessage message = AcpSchema.deserializeJsonRpcMessage(jsonMapper, json);

        assertNotNull(message);
        assertTrue(message instanceof AcpSchema.JSONRPCRequest);
        AcpSchema.JSONRPCRequest deserialized = (AcpSchema.JSONRPCRequest) message;
        assertEquals(AcpSchema.METHOD_INITIALIZE, deserialized.method());
    }

    @Test
    @DisplayName("Test JSONRPCError construction")
    void testJsonRpcErrorConstruction() {
        AcpSchema.JSONRPCError error = new AcpSchema.JSONRPCError(
                -32600,
                "Invalid Request",
                null
        );

        assertEquals(-32600, error.code());
        assertEquals("Invalid Request", error.message());
    }

    @Test
    @DisplayName("Test SessionNotification construction")
    void testSessionNotificationConstruction() {
        AcpSchema.TextContent content = new AcpSchema.TextContent("Agent response");
        AcpSchema.AgentMessageChunk update = new AcpSchema.AgentMessageChunk(
                "agent_message_chunk",
                content
        );
        AcpSchema.SessionNotification notification = new AcpSchema.SessionNotification(
                "session-123",
                update
        );

        assertEquals("session-123", notification.sessionId());
        assertNotNull(notification.update());
    }

    @Test
    @DisplayName("Test ClientCapabilities defaults")
    void testClientCapabilitiesDefaults() {
        AcpSchema.ClientCapabilities caps = new AcpSchema.ClientCapabilities();

        assertNotNull(caps.fs());
        assertFalse(caps.terminal());
    }

    @Test
    @DisplayName("Test AgentCapabilities defaults")
    void testAgentCapabilitiesDefaults() {
        AcpSchema.AgentCapabilities caps = new AcpSchema.AgentCapabilities();

        assertFalse(caps.loadSession());
        assertNotNull(caps.mcpCapabilities());
        assertNotNull(caps.promptCapabilities());
    }

    @Test
    @DisplayName("Test deserialization of response")
    void testResponseDeserialization() throws Exception {
        String responseJson = "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"sessionId\":\"test-session\"}}";

        AcpSchema.JSONRPCMessage message = AcpSchema.deserializeJsonRpcMessage(jsonMapper, responseJson);

        assertNotNull(message);
        assertTrue(message instanceof AcpSchema.JSONRPCResponse);
    }

    @Test
    @DisplayName("Test deserialization of notification")
    void testNotificationDeserialization() throws Exception {
        String notificationJson = "{\"jsonrpc\":\"2.0\",\"method\":\"session/cancel\",\"params\":{\"sessionId\":\"test-session\"}}";

        AcpSchema.JSONRPCMessage message = AcpSchema.deserializeJsonRpcMessage(jsonMapper, notificationJson);

        assertNotNull(message);
        assertTrue(message instanceof AcpSchema.JSONRPCNotification);
    }
}