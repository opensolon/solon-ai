/*
 * Copyright 2025-2025 the original author or authors.
 */

package com.agentclientprotocol.sdk;

import java.util.Collections;
import java.util.List;

import com.agentclientprotocol.sdk.spec.AcpSchema;

/**
 * Factory methods for creating test data objects.
 *
 * <p>
 * Provides convenient factory methods for creating ACP protocol types with reasonable
 * defaults for testing. All factory methods create valid instances that can be used
 * immediately in tests.
 * </p>
 *
 * @author Mark Pollack
 * @author Christian Tzolov
 */
public class AcpTestFixtures {

	// ---------------------------
	// Request Factories
	// ---------------------------

	/**
	 * Creates a basic InitializeRequest with protocol version 1.
	 * @return InitializeRequest with default ClientCapabilities
	 */
	public static AcpSchema.InitializeRequest createInitializeRequest() {
		return new AcpSchema.InitializeRequest(1, createClientCapabilities());
	}

	/**
	 * Creates an InitializeRequest with specified capabilities.
	 * @param capabilities the client capabilities
	 * @return InitializeRequest with specified capabilities
	 */
	public static AcpSchema.InitializeRequest createInitializeRequest(AcpSchema.ClientCapabilities capabilities) {
		return new AcpSchema.InitializeRequest(1, capabilities);
	}

	/**
	 * Creates a NewSessionRequest with a test workspace.
	 * @return NewSessionRequest with default test workspace
	 */
	public static AcpSchema.NewSessionRequest createNewSessionRequest() {
		return new AcpSchema.NewSessionRequest("/test/workspace", Collections.emptyList());
	}

	/**
	 * Creates a NewSessionRequest with specified workspace.
	 * @param cwd the workspace directory
	 * @return NewSessionRequest with specified workspace
	 */
	public static AcpSchema.NewSessionRequest createNewSessionRequest(String cwd) {
		return new AcpSchema.NewSessionRequest(cwd, Collections.emptyList());
	}

	/**
	 * Creates a PromptRequest with test session ID and prompt.
	 * @return PromptRequest with default test data
	 */
	public static AcpSchema.PromptRequest createPromptRequest() {
		return new AcpSchema.PromptRequest("test-session-id", Collections.singletonList(createTextContent("Test prompt")));
	}

	/**
	 * Creates a PromptRequest with specified session ID and prompt text.
	 * @param sessionId the session ID
	 * @param promptText the prompt text
	 * @return PromptRequest with specified data
	 */
	public static AcpSchema.PromptRequest createPromptRequest(String sessionId, String promptText) {
		return new AcpSchema.PromptRequest(sessionId, Collections.singletonList(createTextContent(promptText)));
	}

	/**
	 * Creates a LoadSessionRequest with test data.
	 * @return LoadSessionRequest with default test data
	 */
	public static AcpSchema.LoadSessionRequest createLoadSessionRequest() {
		return new AcpSchema.LoadSessionRequest("test-session-id", "/test/workspace", Collections.emptyList());
	}

	/**
	 * Creates an AuthenticateRequest with test method ID.
	 * @return AuthenticateRequest with default method ID
	 */
	public static AcpSchema.AuthenticateRequest createAuthenticateRequest() {
		return new AcpSchema.AuthenticateRequest("test-auth-method");
	}

	// ---------------------------
	// Response Factories
	// ---------------------------

	/**
	 * Creates an InitializeResponse with protocol version 1.
	 * @return InitializeResponse with default AgentCapabilities
	 */
	public static AcpSchema.InitializeResponse createInitializeResponse() {
		return new AcpSchema.InitializeResponse(1, createAgentCapabilities(), Collections.emptyList());
	}

	/**
	 * Creates a NewSessionResponse with test session ID.
	 * @return NewSessionResponse with default test session
	 */
	public static AcpSchema.NewSessionResponse createNewSessionResponse() {
		return new AcpSchema.NewSessionResponse("test-session-id", createSessionModeState(),
				createSessionModelState());
	}

	/**
	 * Creates a NewSessionResponse with specified session ID.
	 * @param sessionId the session ID
	 * @return NewSessionResponse with specified session ID
	 */
	public static AcpSchema.NewSessionResponse createNewSessionResponse(String sessionId) {
		return new AcpSchema.NewSessionResponse(sessionId, createSessionModeState(), createSessionModelState());
	}

	/**
	 * Creates a PromptResponse with "end_turn" stop reason.
	 * @return PromptResponse with default stop reason
	 */
	public static AcpSchema.PromptResponse createPromptResponse() {
		return new AcpSchema.PromptResponse(AcpSchema.StopReason.END_TURN);
	}

	/**
	 * Creates a LoadSessionResponse with default session state.
	 * @return LoadSessionResponse with test data
	 */
	public static AcpSchema.LoadSessionResponse createLoadSessionResponse() {
		return new AcpSchema.LoadSessionResponse(createSessionModeState(), createSessionModelState());
	}

	/**
	 * Creates an AuthenticateResponse.
	 * @return AuthenticateResponse
	 */
	public static AcpSchema.AuthenticateResponse createAuthenticateResponse() {
		return new AcpSchema.AuthenticateResponse();
	}

	// ---------------------------
	// Content Factories
	// ---------------------------

	/**
	 * Creates a TextContent block with specified text.
	 * @param text the text content
	 * @return TextContent with specified text
	 */
	public static AcpSchema.TextContent createTextContent(String text) {
		return new AcpSchema.TextContent(text);
	}

	/**
	 * Creates an ImageContent block with test data.
	 * @return ImageContent with test image data
	 */
	public static AcpSchema.ImageContent createImageContent() {
		return new AcpSchema.ImageContent("image", "base64-encoded-data", "image/png", null, null, null);
	}

	/**
	 * Creates an AudioContent block with test data.
	 * @return AudioContent with test audio data
	 */
	public static AcpSchema.AudioContent createAudioContent() {
		return new AcpSchema.AudioContent("audio", "base64-encoded-data", "audio/wav", null, null);
	}

	// ---------------------------
	// Capabilities Factories
	// ---------------------------

	/**
	 * Creates default ClientCapabilities.
	 * @return ClientCapabilities with no capabilities enabled
	 */
	public static AcpSchema.ClientCapabilities createClientCapabilities() {
		return new AcpSchema.ClientCapabilities();
	}

	/**
	 * Creates ClientCapabilities with filesystem support.
	 * @return ClientCapabilities with filesystem enabled
	 */
	public static AcpSchema.ClientCapabilities createClientCapabilitiesWithFs() {
		return new AcpSchema.ClientCapabilities(new AcpSchema.FileSystemCapability(true, true), false);
	}

	/**
	 * Creates ClientCapabilities with terminal support.
	 * @return ClientCapabilities with terminal enabled
	 */
	public static AcpSchema.ClientCapabilities createClientCapabilitiesWithTerminal() {
		return new AcpSchema.ClientCapabilities(new AcpSchema.FileSystemCapability(), true);
	}

	/**
	 * Creates default AgentCapabilities.
	 * @return AgentCapabilities with no capabilities enabled
	 */
	public static AcpSchema.AgentCapabilities createAgentCapabilities() {
		return new AcpSchema.AgentCapabilities();
	}

	/**
	 * Creates AgentCapabilities with session loading support.
	 * @return AgentCapabilities with loadSession enabled
	 */
	public static AcpSchema.AgentCapabilities createAgentCapabilitiesWithLoadSession() {
		return new AcpSchema.AgentCapabilities(true, new AcpSchema.McpCapabilities(), new AcpSchema.PromptCapabilities());
	}

	// ---------------------------
	// Session State Factories
	// ---------------------------

	/**
	 * Creates a SessionModeState with test mode.
	 * @return SessionModeState with default mode
	 */
	public static AcpSchema.SessionModeState createSessionModeState() {
		AcpSchema.SessionMode mode = new AcpSchema.SessionMode("code", "Code Mode", "Code editing mode");
		return new AcpSchema.SessionModeState("code", Collections.singletonList(mode));
	}

	/**
	 * Creates a SessionModelState with test model.
	 * @return SessionModelState with default model
	 */
	public static AcpSchema.SessionModelState createSessionModelState() {
		AcpSchema.ModelInfo model = new AcpSchema.ModelInfo("test-model", "Test Model", "Test model description");
		return new AcpSchema.SessionModelState("test-model", Collections.singletonList(model));
	}

	// ---------------------------
	// JSON-RPC Factories
	// ---------------------------

	/**
	 * Creates a JSON-RPC request.
	 * @param method the method name
	 * @param id the request ID
	 * @param params the parameters
	 * @return JSONRPCRequest with specified data
	 */
	public static AcpSchema.JSONRPCRequest createJsonRpcRequest(String method, Object id, Object params) {
		return new AcpSchema.JSONRPCRequest(method, id, params);
	}

	/**
	 * Creates a JSON-RPC notification.
	 * @param method the method name
	 * @param params the parameters
	 * @return JSONRPCNotification with specified data
	 */
	public static AcpSchema.JSONRPCNotification createJsonRpcNotification(String method, Object params) {
		return new AcpSchema.JSONRPCNotification(method, params);
	}

	/**
	 * Creates a JSON-RPC success response.
	 * @param id the request ID
	 * @param result the result object
	 * @return JSONRPCResponse with success result
	 */
	public static AcpSchema.JSONRPCResponse createJsonRpcResponse(Object id, Object result) {
		return new AcpSchema.JSONRPCResponse(AcpSchema.JSONRPC_VERSION, id, result, null);
	}

	/**
	 * Creates a JSON-RPC error response.
	 * @param id the request ID
	 * @param errorCode the error code
	 * @param errorMessage the error message
	 * @return JSONRPCResponse with error
	 */
	public static AcpSchema.JSONRPCResponse createJsonRpcErrorResponse(Object id, int errorCode, String errorMessage) {
		AcpSchema.JSONRPCError error = new AcpSchema.JSONRPCError(errorCode, errorMessage, null);
		return new AcpSchema.JSONRPCResponse(AcpSchema.JSONRPC_VERSION, id, null, error);
	}

}
