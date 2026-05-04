/*
 * Copyright 2025-2025 the original author or authors.
 */

package com.agentclientprotocol.sdk.spec;

import com.fasterxml.jackson.annotation.*;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.TypeRef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Agent Client Protocol (ACP) Schema based on
 * <a href="https://agentclientprotocol.com/">Agent Client Protocol specification</a>.
 *
 * This schema defines all request, response, and notification types used in ACP. ACP is a
 * protocol for communication between code editors (clients) and coding agents.
 *
 * @author Mark Pollack
 * @author Christian Tzolov
 */
public final class AcpSchema {

	private static final Logger logger = LoggerFactory.getLogger(AcpSchema.class);

	private static final TypeRef<HashMap<String, Object>> MAP_TYPE_REF = new TypeRef<HashMap<String, Object>>() {
	};

	private AcpSchema() {
	}

	public static final String JSONRPC_VERSION = "2.0";

	public static final int LATEST_PROTOCOL_VERSION = 1;

	/**
	 * Deserializes a JSON-RPC message from a JSON string into the appropriate message
	 * type (request, response, or notification).
	 * @param jsonMapper The JSON mapper to use for deserialization
	 * @param jsonText The JSON text to deserialize
	 * @return The deserialized JSON-RPC message
	 * @throws IOException If deserialization fails
	 * @throws IllegalArgumentException If the JSON structure doesn't match any known
	 * message type
	 */
	public static JSONRPCMessage deserializeJsonRpcMessage(McpJsonMapper jsonMapper, String jsonText)
			throws IOException {

		logger.debug("Received JSON message: {}", jsonText);

		HashMap<String, Object> map = jsonMapper.readValue(jsonText, MAP_TYPE_REF);

		// Determine message type based on specific JSON structure
		if (map.containsKey("method") && map.containsKey("id")) {
			return jsonMapper.convertValue(map, JSONRPCRequest.class);
		}
		else if (map.containsKey("method") && !map.containsKey("id")) {
			return jsonMapper.convertValue(map, JSONRPCNotification.class);
		}
		else if (map.containsKey("result") || map.containsKey("error")) {
			return jsonMapper.convertValue(map, JSONRPCResponse.class);
		}

		throw new IllegalArgumentException("Cannot deserialize JSONRPCMessage: " + jsonText);
	}

	// ---------------------------
	// Method Names (Agent Methods - client calls these)
	// ---------------------------

	public static final String METHOD_INITIALIZE = "initialize";

	public static final String METHOD_AUTHENTICATE = "authenticate";

	public static final String METHOD_SESSION_NEW = "session/new";

	public static final String METHOD_SESSION_LOAD = "session/load";

	public static final String METHOD_SESSION_PROMPT = "session/prompt";

	public static final String METHOD_SESSION_SET_MODE = "session/set_mode";

	public static final String METHOD_SESSION_SET_MODEL = "session/set_model";

	public static final String METHOD_SESSION_CANCEL = "session/cancel";

	// ---------------------------
	// Method Names (Client Methods - agent calls these)
	// ---------------------------

	public static final String METHOD_SESSION_REQUEST_PERMISSION = "session/request_permission";

	public static final String METHOD_SESSION_UPDATE = "session/update";

	public static final String METHOD_FS_READ_TEXT_FILE = "fs/read_text_file";

	public static final String METHOD_FS_WRITE_TEXT_FILE = "fs/write_text_file";

	public static final String METHOD_TERMINAL_CREATE = "terminal/create";

	public static final String METHOD_TERMINAL_OUTPUT = "terminal/output";

	public static final String METHOD_TERMINAL_RELEASE = "terminal/release";

	public static final String METHOD_TERMINAL_WAIT_FOR_EXIT = "terminal/wait_for_exit";

	public static final String METHOD_TERMINAL_KILL = "terminal/kill";

	// ---------------------------
	// JSON-RPC Message Types
	// ---------------------------

	/**
	 * A JSON-RPC request that expects a response.
	 *
	 * @param jsonrpc The JSON-RPC version (must be "2.0")
	 * @param id A unique identifier for the request
	 * @param method The name of the method to be invoked
	 * @param params Parameters for the method call
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public static final class JSONRPCRequest implements JSONRPCMessage {
		private final @JsonProperty("jsonrpc") String jsonrpc;
		private final @JsonProperty("id") Object id;
		private final @JsonProperty("method") String method;
		private final @JsonProperty("params") Object params;

		public JSONRPCRequest(@JsonProperty("jsonrpc") String jsonrpc, @JsonProperty("id") Object id, @JsonProperty("method") String method, @JsonProperty("params") Object params) {
			this.jsonrpc = jsonrpc;
			this.id = id;
			this.method = method;
			this.params = params;
		}

		public JSONRPCRequest(String method, Object id, Object params) {
			this(JSONRPC_VERSION, id, method, params);
		}

		public String jsonrpc() { return jsonrpc; }
		public Object id() { return id; }
		public String method() { return method; }
		public Object params() { return params; }

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null || getClass() != o.getClass()) return false;
			JSONRPCRequest that = (JSONRPCRequest) o;
			return Objects.equals(jsonrpc, that.jsonrpc) && Objects.equals(id, that.id) && Objects.equals(method, that.method) && Objects.equals(params, that.params);
		}

		@Override
		public int hashCode() {
			return Objects.hash(jsonrpc, id, method, params);
		}

		@Override
		public String toString() {
			return "JSONRPCRequest[jsonrpc=" + jsonrpc + ", id=" + id + ", method=" + method + ", params=" + params + "]";
		}
	}

	/**
	 * A JSON-RPC notification that does not expect a response.
	 *
	 * @param jsonrpc The JSON-RPC version (must be "2.0")
	 * @param method The name of the method to be invoked
	 * @param params Parameters for the method call
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public static final class JSONRPCNotification implements JSONRPCMessage {
		private final @JsonProperty("jsonrpc") String jsonrpc;
		private final @JsonProperty("method") String method;
		private final @JsonProperty("params") Object params;

		public JSONRPCNotification(@JsonProperty("jsonrpc") String jsonrpc, @JsonProperty("method") String method, @JsonProperty("params") Object params) {
			this.jsonrpc = jsonrpc;
			this.method = method;
			this.params = params;
		}

		public JSONRPCNotification(String method, Object params) {
			this(JSONRPC_VERSION, method, params);
		}

		public String jsonrpc() { return jsonrpc; }
		public String method() { return method; }
		public Object params() { return params; }

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null || getClass() != o.getClass()) return false;
			JSONRPCNotification that = (JSONRPCNotification) o;
			return Objects.equals(jsonrpc, that.jsonrpc) && Objects.equals(method, that.method) && Objects.equals(params, that.params);
		}

		@Override
		public int hashCode() {
			return Objects.hash(jsonrpc, method, params);
		}

		@Override
		public String toString() {
			return "JSONRPCNotification[jsonrpc=" + jsonrpc + ", method=" + method + ", params=" + params + "]";
		}
	}

	/**
	 * A JSON-RPC response to a request.
	 *
	 * @param jsonrpc The JSON-RPC version (must be "2.0")
	 * @param id The request ID this response corresponds to
	 * @param result The result of the method call (null if error occurred)
	 * @param error The error information (null if successful)
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public static final class JSONRPCResponse implements JSONRPCMessage {
		private final @JsonProperty("jsonrpc") String jsonrpc;
		private final @JsonProperty("id") Object id;
		private final @JsonProperty("result") Object result;
		private final @JsonProperty("error") JSONRPCError error;

		public JSONRPCResponse(@JsonProperty("jsonrpc") String jsonrpc, @JsonProperty("id") Object id, @JsonProperty("result") Object result, @JsonProperty("error") JSONRPCError error) {
			this.jsonrpc = jsonrpc;
			this.id = id;
			this.result = result;
			this.error = error;
		}

		public String jsonrpc() { return jsonrpc; }
		public Object id() { return id; }
		public Object result() { return result; }
		public JSONRPCError error() { return error; }

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null || getClass() != o.getClass()) return false;
			JSONRPCResponse that = (JSONRPCResponse) o;
			return Objects.equals(jsonrpc, that.jsonrpc) && Objects.equals(id, that.id) && Objects.equals(result, that.result) && Objects.equals(error, that.error);
		}

		@Override
		public int hashCode() {
			return Objects.hash(jsonrpc, id, result, error);
		}

		@Override
		public String toString() {
			return "JSONRPCResponse[jsonrpc=" + jsonrpc + ", id=" + id + ", result=" + result + ", error=" + error + "]";
		}
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public static final class JSONRPCError {
		private final @JsonProperty("code") int code;
		private final @JsonProperty("message") String message;
		private final @JsonProperty("data") Object data;

		public JSONRPCError(@JsonProperty("code") int code, @JsonProperty("message") String message, @JsonProperty("data") Object data) {
			this.code = code;
			this.message = message;
			this.data = data;
		}

		public int code() { return code; }
		public String message() { return message; }
		public Object data() { return data; }

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null || getClass() != o.getClass()) return false;
			JSONRPCError that = (JSONRPCError) o;
			return code == that.code && Objects.equals(message, that.message) && Objects.equals(data, that.data);
		}

		@Override
		public int hashCode() {
			return Objects.hash(code, message, data);
		}

		@Override
		public String toString() {
			return "JSONRPCError[code=" + code + ", message=" + message + ", data=" + data + "]";
		}
	}

	/**
	 * Base type for all JSON-RPC messages.
	 */
	public interface JSONRPCMessage {

		String jsonrpc();

	}

	// ---------------------------
	// Agent Methods (Client → Agent)
	// ---------------------------

	/**
	 * Initialize request - establishes connection and negotiates capabilities
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public static final class InitializeRequest {
		private final @JsonProperty("protocolVersion") Integer protocolVersion;
		private final @JsonProperty("clientCapabilities") ClientCapabilities clientCapabilities;
		private final @JsonProperty("clientInfo") Implementation clientInfo;
		private final @JsonProperty("_meta") Map<String, Object> meta;

		public InitializeRequest(@JsonProperty("protocolVersion") Integer protocolVersion, @JsonProperty("clientCapabilities") ClientCapabilities clientCapabilities, @JsonProperty("clientInfo") Implementation clientInfo, @JsonProperty("_meta") Map<String, Object> meta) {
			this.protocolVersion = protocolVersion;
			this.clientCapabilities = clientCapabilities;
			this.clientInfo = clientInfo;
			this.meta = meta;
		}

		public InitializeRequest(Integer protocolVersion, ClientCapabilities clientCapabilities) {
			this(protocolVersion, clientCapabilities, null, null);
		}

		public Integer protocolVersion() { return protocolVersion; }
		public ClientCapabilities clientCapabilities() { return clientCapabilities; }
		public Implementation clientInfo() { return clientInfo; }
		public Map<String, Object> meta() { return meta; }

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null || getClass() != o.getClass()) return false;
			InitializeRequest that = (InitializeRequest) o;
			return Objects.equals(protocolVersion, that.protocolVersion) && Objects.equals(clientCapabilities, that.clientCapabilities) && Objects.equals(clientInfo, that.clientInfo) && Objects.equals(meta, that.meta);
		}

		@Override
		public int hashCode() {
			return Objects.hash(protocolVersion, clientCapabilities, clientInfo, meta);
		}

		@Override
		public String toString() {
			return "InitializeRequest[protocolVersion=" + protocolVersion + ", clientCapabilities=" + clientCapabilities + ", clientInfo=" + clientInfo + ", meta=" + meta + "]";
		}
	}

	/**
	 * Initialize response - returns agent capabilities and auth methods
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public static final class InitializeResponse {
		private final @JsonProperty("protocolVersion") Integer protocolVersion;
		private final @JsonProperty("agentCapabilities") AgentCapabilities agentCapabilities;
		private final @JsonProperty("authMethods") List<AuthMethod> authMethods;
		private final @JsonProperty("agentInfo") Implementation agentInfo;
		private final @JsonProperty("_meta") Map<String, Object> meta;

		public InitializeResponse(@JsonProperty("protocolVersion") Integer protocolVersion, @JsonProperty("agentCapabilities") AgentCapabilities agentCapabilities, @JsonProperty("authMethods") List<AuthMethod> authMethods, @JsonProperty("agentInfo") Implementation agentInfo, @JsonProperty("_meta") Map<String, Object> meta) {
			this.protocolVersion = protocolVersion;
			this.agentCapabilities = agentCapabilities;
			this.authMethods = authMethods;
			this.agentInfo = agentInfo;
			this.meta = meta;
		}

		public InitializeResponse(Integer protocolVersion, AgentCapabilities agentCapabilities,
				List<AuthMethod> authMethods) {
			this(protocolVersion, agentCapabilities, authMethods, null, null);
		}

		/**
		 * Creates a default successful initialization response.
		 * Uses protocol version 1 and default agent capabilities.
		 * @return A default InitializeResponse
		 */
		public static InitializeResponse ok() {
			return new InitializeResponse(1, new AgentCapabilities(), null);
		}

		/**
		 * Creates a successful initialization response with the given capabilities.
		 * @param capabilities The agent capabilities to advertise
		 * @return An InitializeResponse with the specified capabilities
		 */
		public static InitializeResponse ok(AgentCapabilities capabilities) {
			return new InitializeResponse(1, capabilities, null);
		}

		public Integer protocolVersion() { return protocolVersion; }
		public AgentCapabilities agentCapabilities() { return agentCapabilities; }
		public List<AuthMethod> authMethods() { return authMethods; }
		public Implementation agentInfo() { return agentInfo; }
		public Map<String, Object> meta() { return meta; }

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null || getClass() != o.getClass()) return false;
			InitializeResponse that = (InitializeResponse) o;
			return Objects.equals(protocolVersion, that.protocolVersion) && Objects.equals(agentCapabilities, that.agentCapabilities) && Objects.equals(authMethods, that.authMethods) && Objects.equals(agentInfo, that.agentInfo) && Objects.equals(meta, that.meta);
		}

		@Override
		public int hashCode() {
			return Objects.hash(protocolVersion, agentCapabilities, authMethods, agentInfo, meta);
		}

		@Override
		public String toString() {
			return "InitializeResponse[protocolVersion=" + protocolVersion + ", agentCapabilities=" + agentCapabilities + ", authMethods=" + authMethods + ", agentInfo=" + agentInfo + ", meta=" + meta + "]";
		}
	}

	/**
	 * Authenticate request - authenticates using specified method
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public static final class AuthenticateRequest {
		private final @JsonProperty("methodId") String methodId;

		public AuthenticateRequest(@JsonProperty("methodId") String methodId) {
			this.methodId = methodId;
		}

		public String methodId() { return methodId; }

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null || getClass() != o.getClass()) return false;
			AuthenticateRequest that = (AuthenticateRequest) o;
			return Objects.equals(methodId, that.methodId);
		}

		@Override
		public int hashCode() {
			return Objects.hash(methodId);
		}

		@Override
		public String toString() {
			return "AuthenticateRequest[methodId=" + methodId + "]";
		}
	}

	/**
	 * Authenticate response
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public static final class AuthenticateResponse {
		public AuthenticateResponse() {
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null || getClass() != o.getClass()) return false;
			AuthenticateResponse that = (AuthenticateResponse) o;
			return true;
		}

		@Override
		public int hashCode() {
			return 0;
		}

		@Override
		public String toString() {
			return "AuthenticateResponse[]";
		}
	}

	/**
	 * Create new session request
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public static final class NewSessionRequest {
		private final @JsonProperty("cwd") String cwd;
		private final @JsonProperty("mcpServers") List<McpServer> mcpServers;
		private final @JsonProperty("_meta") Map<String, Object> meta;

		public NewSessionRequest(@JsonProperty("cwd") String cwd, @JsonProperty("mcpServers") List<McpServer> mcpServers, @JsonProperty("_meta") Map<String, Object> meta) {
			this.cwd = cwd;
			this.mcpServers = mcpServers;
			this.meta = meta;
		}

		public NewSessionRequest(String cwd, List<McpServer> mcpServers) {
			this(cwd, mcpServers, null);
		}

		public String cwd() { return cwd; }
		public List<McpServer> mcpServers() { return mcpServers; }
		public Map<String, Object> meta() { return meta; }

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null || getClass() != o.getClass()) return false;
			NewSessionRequest that = (NewSessionRequest) o;
			return Objects.equals(cwd, that.cwd) && Objects.equals(mcpServers, that.mcpServers) && Objects.equals(meta, that.meta);
		}

		@Override
		public int hashCode() {
			return Objects.hash(cwd, mcpServers, meta);
		}

		@Override
		public String toString() {
			return "NewSessionRequest[cwd=" + cwd + ", mcpServers=" + mcpServers + ", meta=" + meta + "]";
		}
	}

	/**
	 * Create new session response
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public static final class NewSessionResponse {
		private final @JsonProperty("sessionId") String sessionId;
		private final @JsonProperty("modes") SessionModeState modes;
		private final @JsonProperty("models") SessionModelState models;
		private final @JsonProperty("_meta") Map<String, Object> meta;

		public NewSessionResponse(@JsonProperty("sessionId") String sessionId, @JsonProperty("modes") SessionModeState modes, @JsonProperty("models") SessionModelState models, @JsonProperty("_meta") Map<String, Object> meta) {
			this.sessionId = sessionId;
			this.modes = modes;
			this.models = models;
			this.meta = meta;
		}

		public NewSessionResponse(String sessionId, SessionModeState modes, SessionModelState models) {
			this(sessionId, modes, models, null);
		}

		public String sessionId() { return sessionId; }
		public SessionModeState modes() { return modes; }
		public SessionModelState models() { return models; }
		public Map<String, Object> meta() { return meta; }

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null || getClass() != o.getClass()) return false;
			NewSessionResponse that = (NewSessionResponse) o;
			return Objects.equals(sessionId, that.sessionId) && Objects.equals(modes, that.modes) && Objects.equals(models, that.models) && Objects.equals(meta, that.meta);
		}

		@Override
		public int hashCode() {
			return Objects.hash(sessionId, modes, models, meta);
		}

		@Override
		public String toString() {
			return "NewSessionResponse[sessionId=" + sessionId + ", modes=" + modes + ", models=" + models + ", meta=" + meta + "]";
		}
	}

	/**
	 * Load existing session request
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public static final class LoadSessionRequest {
		private final @JsonProperty("sessionId") String sessionId;
		private final @JsonProperty("cwd") String cwd;
		private final @JsonProperty("mcpServers") List<McpServer> mcpServers;
		private final @JsonProperty("_meta") Map<String, Object> meta;

		public LoadSessionRequest(@JsonProperty("sessionId") String sessionId, @JsonProperty("cwd") String cwd, @JsonProperty("mcpServers") List<McpServer> mcpServers, @JsonProperty("_meta") Map<String, Object> meta) {
			this.sessionId = sessionId;
			this.cwd = cwd;
			this.mcpServers = mcpServers;
			this.meta = meta;
		}

		public LoadSessionRequest(String sessionId, String cwd, List<McpServer> mcpServers) {
			this(sessionId, cwd, mcpServers, null);
		}

		public String sessionId() { return sessionId; }
		public String cwd() { return cwd; }
		public List<McpServer> mcpServers() { return mcpServers; }
		public Map<String, Object> meta() { return meta; }

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null || getClass() != o.getClass()) return false;
			LoadSessionRequest that = (LoadSessionRequest) o;
			return Objects.equals(sessionId, that.sessionId) && Objects.equals(cwd, that.cwd) && Objects.equals(mcpServers, that.mcpServers) && Objects.equals(meta, that.meta);
		}

		@Override
		public int hashCode() {
			return Objects.hash(sessionId, cwd, mcpServers, meta);
		}

		@Override
		public String toString() {
			return "LoadSessionRequest[sessionId=" + sessionId + ", cwd=" + cwd + ", mcpServers=" + mcpServers + ", meta=" + meta + "]";
		}
	}

	/**
	 * Load session response
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public static final class LoadSessionResponse {
		private final @JsonProperty("modes") SessionModeState modes;
		private final @JsonProperty("models") SessionModelState models;
		private final @JsonProperty("_meta") Map<String, Object> meta;

		public LoadSessionResponse(@JsonProperty("modes") SessionModeState modes, @JsonProperty("models") SessionModelState models, @JsonProperty("_meta") Map<String, Object> meta) {
			this.modes = modes;
			this.models = models;
			this.meta = meta;
		}

		public LoadSessionResponse(SessionModeState modes, SessionModelState models) {
			this(modes, models, null);
		}

		public SessionModeState modes() { return modes; }
		public SessionModelState models() { return models; }
		public Map<String, Object> meta() { return meta; }

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null || getClass() != o.getClass()) return false;
			LoadSessionResponse that = (LoadSessionResponse) o;
			return Objects.equals(modes, that.modes) && Objects.equals(models, that.models) && Objects.equals(meta, that.meta);
		}

		@Override
		public int hashCode() {
			return Objects.hash(modes, models, meta);
		}

		@Override
		public String toString() {
			return "LoadSessionResponse[modes=" + modes + ", models=" + models + ", meta=" + meta + "]";
		}
	}

	/**
	 * Prompt request - sends user message to agent
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public static final class PromptRequest {
		private final @JsonProperty("sessionId") String sessionId;
		private final @JsonProperty("prompt") List<ContentBlock> prompt;
		private final @JsonProperty("_meta") Map<String, Object> meta;

		public PromptRequest(@JsonProperty("sessionId") String sessionId, @JsonProperty("prompt") List<ContentBlock> prompt, @JsonProperty("_meta") Map<String, Object> meta) {
			this.sessionId = sessionId;
			this.prompt = prompt;
			this.meta = meta;
		}

		public PromptRequest(String sessionId, List<ContentBlock> prompt) {
			this(sessionId, prompt, null);
		}

		/**
		 * Returns the text of the first {@link TextContent} block in the prompt, or an empty
		 * string if no text content is present.
		 */
		public String text() {
			if (prompt == null) {
				return "";
			}
			return prompt.stream()
				.filter(c -> c instanceof TextContent)
				.map(c -> ((TextContent) c).text())
				.findFirst()
				.orElse("");
		}

		public String sessionId() { return sessionId; }
		public List<ContentBlock> prompt() { return prompt; }
		public Map<String, Object> meta() { return meta; }

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null || getClass() != o.getClass()) return false;
			PromptRequest that = (PromptRequest) o;
			return Objects.equals(sessionId, that.sessionId) && Objects.equals(prompt, that.prompt) && Objects.equals(meta, that.meta);
		}

		@Override
		public int hashCode() {
			return Objects.hash(sessionId, prompt, meta);
		}

		@Override
		public String toString() {
			return "PromptRequest[sessionId=" + sessionId + ", prompt=" + prompt + ", meta=" + meta + "]";
		}
	}

	/**
	 * Prompt response - indicates why agent stopped
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public static final class PromptResponse {
		private final @JsonProperty("stopReason") StopReason stopReason;
		private final @JsonProperty("_meta") Map<String, Object> meta;

		public PromptResponse(@JsonProperty("stopReason") StopReason stopReason, @JsonProperty("_meta") Map<String, Object> meta) {
			this.stopReason = stopReason;
			this.meta = meta;
		}

		public PromptResponse(StopReason stopReason) {
			this(stopReason, null);
		}

		/**
		 * Creates a response indicating the agent has finished its turn.
		 * @return A PromptResponse with END_TURN stop reason
		 */
		public static PromptResponse endTurn() {
			return new PromptResponse(StopReason.END_TURN);
		}

		/**
		 * Creates a response indicating the agent has finished its turn with a text result.
		 * Note: The text content should be sent via the context before returning this response.
		 * @param text The text (for documentation purposes; actual content sent via context)
		 * @return A PromptResponse with END_TURN stop reason
		 */
		public static PromptResponse text(String text) {
			// Text content should be sent via context.sendMessage() before returning
			return new PromptResponse(StopReason.END_TURN);
		}

		/**
		 * Creates a response indicating the agent refused the request.
		 * @return A PromptResponse with REFUSAL stop reason
		 */
		public static PromptResponse refusal() {
			return new PromptResponse(StopReason.REFUSAL);
		}

		public StopReason stopReason() { return stopReason; }
		public Map<String, Object> meta() { return meta; }

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null || getClass() != o.getClass()) return false;
			PromptResponse that = (PromptResponse) o;
			return Objects.equals(stopReason, that.stopReason) && Objects.equals(meta, that.meta);
		}

		@Override
		public int hashCode() {
			return Objects.hash(stopReason, meta);
		}

		@Override
		public String toString() {
			return "PromptResponse[stopReason=" + stopReason + ", meta=" + meta + "]";
		}
	}

	/**
	 * Set session mode request
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public static final class SetSessionModeRequest {
		private final @JsonProperty("sessionId") String sessionId;
		private final @JsonProperty("modeId") String modeId;

		public SetSessionModeRequest(@JsonProperty("sessionId") String sessionId, @JsonProperty("modeId") String modeId) {
			this.sessionId = sessionId;
			this.modeId = modeId;
		}

		public String sessionId() { return sessionId; }
		public String modeId() { return modeId; }

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null || getClass() != o.getClass()) return false;
			SetSessionModeRequest that = (SetSessionModeRequest) o;
			return Objects.equals(sessionId, that.sessionId) && Objects.equals(modeId, that.modeId);
		}

		@Override
		public int hashCode() {
			return Objects.hash(sessionId, modeId);
		}

		@Override
		public String toString() {
			return "SetSessionModeRequest[sessionId=" + sessionId + ", modeId=" + modeId + "]";
		}
	}

	/**
	 * Set session mode response
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public static final class SetSessionModeResponse {
		public SetSessionModeResponse() {
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null || getClass() != o.getClass()) return false;
			SetSessionModeResponse that = (SetSessionModeResponse) o;
			return true;
		}

		@Override
		public int hashCode() {
			return 0;
		}

		@Override
		public String toString() {
			return "SetSessionModeResponse[]";
		}
	}

	/**
	 * Set session model request (UNSTABLE)
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public static final class SetSessionModelRequest {
		private final @JsonProperty("sessionId") String sessionId;
		private final @JsonProperty("modelId") String modelId;

		public SetSessionModelRequest(@JsonProperty("sessionId") String sessionId, @JsonProperty("modelId") String modelId) {
			this.sessionId = sessionId;
			this.modelId = modelId;
		}

		public String sessionId() { return sessionId; }
		public String modelId() { return modelId; }

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null || getClass() != o.getClass()) return false;
			SetSessionModelRequest that = (SetSessionModelRequest) o;
			return Objects.equals(sessionId, that.sessionId) && Objects.equals(modelId, that.modelId);
		}

		@Override
		public int hashCode() {
			return Objects.hash(sessionId, modelId);
		}

		@Override
		public String toString() {
			return "SetSessionModelRequest[sessionId=" + sessionId + ", modelId=" + modelId + "]";
		}
	}

	/**
	 * Set session model response (UNSTABLE)
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public static final class SetSessionModelResponse {
		public SetSessionModelResponse() {
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null || getClass() != o.getClass()) return false;
			SetSessionModelResponse that = (SetSessionModelResponse) o;
			return true;
		}

		@Override
		public int hashCode() {
			return 0;
		}

		@Override
		public String toString() {
			return "SetSessionModelResponse[]";
		}
	}

	/**
	 * Cancel notification - cancels ongoing operations
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public static final class CancelNotification {
		private final @JsonProperty("sessionId") String sessionId;

		public CancelNotification(@JsonProperty("sessionId") String sessionId) {
			this.sessionId = sessionId;
		}

		public String sessionId() { return sessionId; }

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null || getClass() != o.getClass()) return false;
			CancelNotification that = (CancelNotification) o;
			return Objects.equals(sessionId, that.sessionId);
		}

		@Override
		public int hashCode() {
			return Objects.hash(sessionId);
		}

		@Override
		public String toString() {
			return "CancelNotification[sessionId=" + sessionId + "]";
		}
	}

	// ---------------------------
	// Client Methods (Agent → Client)
	// ---------------------------

	/**
	 * Request permission from user
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public static final class RequestPermissionRequest {
		private final @JsonProperty("sessionId") String sessionId;
		private final @JsonProperty("toolCall") ToolCallUpdate toolCall;
		private final @JsonProperty("options") List<PermissionOption> options;

		public RequestPermissionRequest(@JsonProperty("sessionId") String sessionId, @JsonProperty("toolCall") ToolCallUpdate toolCall, @JsonProperty("options") List<PermissionOption> options) {
			this.sessionId = sessionId;
			this.toolCall = toolCall;
			this.options = options;
		}

		public String sessionId() { return sessionId; }
		public ToolCallUpdate toolCall() { return toolCall; }
		public List<PermissionOption> options() { return options; }

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null || getClass() != o.getClass()) return false;
			RequestPermissionRequest that = (RequestPermissionRequest) o;
			return Objects.equals(sessionId, that.sessionId) && Objects.equals(toolCall, that.toolCall) && Objects.equals(options, that.options);
		}

		@Override
		public int hashCode() {
			return Objects.hash(sessionId, toolCall, options);
		}

		@Override
		public String toString() {
			return "RequestPermissionRequest[sessionId=" + sessionId + ", toolCall=" + toolCall + ", options=" + options + "]";
		}
	}

	/**
	 * Permission response from user
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public static final class RequestPermissionResponse {
		private final @JsonProperty("outcome") RequestPermissionOutcome outcome;

		public RequestPermissionResponse(@JsonProperty("outcome") RequestPermissionOutcome outcome) {
			this.outcome = outcome;
		}

		public RequestPermissionOutcome outcome() { return outcome; }

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null || getClass() != o.getClass()) return false;
			RequestPermissionResponse that = (RequestPermissionResponse) o;
			return Objects.equals(outcome, that.outcome);
		}

		@Override
		public int hashCode() {
			return Objects.hash(outcome);
		}

		@Override
		public String toString() {
			return "RequestPermissionResponse[outcome=" + outcome + "]";
		}
	}

	/**
	 * Session update notification - real-time progress
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public static final class SessionNotification {
		private final @JsonProperty("sessionId") String sessionId;
		private final @JsonProperty("update") SessionUpdate update;
		private final @JsonProperty("_meta") Map<String, Object> meta;

		public SessionNotification(@JsonProperty("sessionId") String sessionId, @JsonProperty("update") SessionUpdate update, @JsonProperty("_meta") Map<String, Object> meta) {
			this.sessionId = sessionId;
			this.update = update;
			this.meta = meta;
		}

		public SessionNotification(String sessionId, SessionUpdate update) {
			this(sessionId, update, null);
		}

		public String sessionId() { return sessionId; }
		public SessionUpdate update() { return update; }
		public Map<String, Object> meta() { return meta; }

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null || getClass() != o.getClass()) return false;
			SessionNotification that = (SessionNotification) o;
			return Objects.equals(sessionId, that.sessionId) && Objects.equals(update, that.update) && Objects.equals(meta, that.meta);
		}

		@Override
		public int hashCode() {
			return Objects.hash(sessionId, update, meta);
		}

		@Override
		public String toString() {
			return "SessionNotification[sessionId=" + sessionId + ", update=" + update + ", meta=" + meta + "]";
		}
	}

	/**
	 * Read text file request
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public static final class ReadTextFileRequest {
		private final @JsonProperty("sessionId") String sessionId;
		private final @JsonProperty("path") String path;
		private final @JsonProperty("line") Integer line;
		private final @JsonProperty("limit") Integer limit;

		public ReadTextFileRequest(@JsonProperty("sessionId") String sessionId, @JsonProperty("path") String path, @JsonProperty("line") Integer line, @JsonProperty("limit") Integer limit) {
			this.sessionId = sessionId;
			this.path = path;
			this.line = line;
			this.limit = limit;
		}

		public String sessionId() { return sessionId; }
		public String path() { return path; }
		public Integer line() { return line; }
		public Integer limit() { return limit; }

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null || getClass() != o.getClass()) return false;
			ReadTextFileRequest that = (ReadTextFileRequest) o;
			return Objects.equals(sessionId, that.sessionId) && Objects.equals(path, that.path) && Objects.equals(line, that.line) && Objects.equals(limit, that.limit);
		}

		@Override
		public int hashCode() {
			return Objects.hash(sessionId, path, line, limit);
		}

		@Override
		public String toString() {
			return "ReadTextFileRequest[sessionId=" + sessionId + ", path=" + path + ", line=" + line + ", limit=" + limit + "]";
		}
	}

	/**
	 * Read text file response
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public static final class ReadTextFileResponse {
		private final @JsonProperty("content") String content;

		public ReadTextFileResponse(@JsonProperty("content") String content) {
			this.content = content;
		}

		public String content() { return content; }

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null || getClass() != o.getClass()) return false;
			ReadTextFileResponse that = (ReadTextFileResponse) o;
			return Objects.equals(content, that.content);
		}

		@Override
		public int hashCode() {
			return Objects.hash(content);
		}

		@Override
		public String toString() {
			return "ReadTextFileResponse[content=" + content + "]";
		}
	}

	/**
	 * Write text file request
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public static final class WriteTextFileRequest {
		private final @JsonProperty("sessionId") String sessionId;
		private final @JsonProperty("path") String path;
		private final @JsonProperty("content") String content;

		public WriteTextFileRequest(@JsonProperty("sessionId") String sessionId, @JsonProperty("path") String path, @JsonProperty("content") String content) {
			this.sessionId = sessionId;
			this.path = path;
			this.content = content;
		}

		public String sessionId() { return sessionId; }
		public String path() { return path; }
		public String content() { return content; }

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null || getClass() != o.getClass()) return false;
			WriteTextFileRequest that = (WriteTextFileRequest) o;
			return Objects.equals(sessionId, that.sessionId) && Objects.equals(path, that.path) && Objects.equals(content, that.content);
		}

		@Override
		public int hashCode() {
			return Objects.hash(sessionId, path, content);
		}

		@Override
		public String toString() {
			return "WriteTextFileRequest[sessionId=" + sessionId + ", path=" + path + ", content=" + content + "]";
		}
	}

	/**
	 * Write text file response
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public static final class WriteTextFileResponse {
		public WriteTextFileResponse() {
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null || getClass() != o.getClass()) return false;
			WriteTextFileResponse that = (WriteTextFileResponse) o;
			return true;
		}

		@Override
		public int hashCode() {
			return 0;
		}

		@Override
		public String toString() {
			return "WriteTextFileResponse[]";
		}
	}

	/**
	 * Create terminal request
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public static final class CreateTerminalRequest {
		private final @JsonProperty("sessionId") String sessionId;
		private final @JsonProperty("command") String command;
		private final @JsonProperty("args") List<String> args;
		private final @JsonProperty("cwd") String cwd;
		private final @JsonProperty("env") List<EnvVariable> env;
		private final @JsonProperty("outputByteLimit") Long outputByteLimit;

		public CreateTerminalRequest(@JsonProperty("sessionId") String sessionId, @JsonProperty("command") String command, @JsonProperty("args") List<String> args, @JsonProperty("cwd") String cwd, @JsonProperty("env") List<EnvVariable> env, @JsonProperty("outputByteLimit") Long outputByteLimit) {
			this.sessionId = sessionId;
			this.command = command;
			this.args = args;
			this.cwd = cwd;
			this.env = env;
			this.outputByteLimit = outputByteLimit;
		}

		public String sessionId() { return sessionId; }
		public String command() { return command; }
		public List<String> args() { return args; }
		public String cwd() { return cwd; }
		public List<EnvVariable> env() { return env; }
		public Long outputByteLimit() { return outputByteLimit; }

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null || getClass() != o.getClass()) return false;
			CreateTerminalRequest that = (CreateTerminalRequest) o;
			return Objects.equals(sessionId, that.sessionId) && Objects.equals(command, that.command) && Objects.equals(args, that.args) && Objects.equals(cwd, that.cwd) && Objects.equals(env, that.env) && Objects.equals(outputByteLimit, that.outputByteLimit);
		}

		@Override
		public int hashCode() {
			return Objects.hash(sessionId, command, args, cwd, env, outputByteLimit);
		}

		@Override
		public String toString() {
			return "CreateTerminalRequest[sessionId=" + sessionId + ", command=" + command + ", args=" + args + ", cwd=" + cwd + ", env=" + env + ", outputByteLimit=" + outputByteLimit + "]";
		}
	}

	/**
	 * Create terminal response
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public static final class CreateTerminalResponse {
		private final @JsonProperty("terminalId") String terminalId;

		public CreateTerminalResponse(@JsonProperty("terminalId") String terminalId) {
			this.terminalId = terminalId;
		}

		public String terminalId() { return terminalId; }

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null || getClass() != o.getClass()) return false;
			CreateTerminalResponse that = (CreateTerminalResponse) o;
			return Objects.equals(terminalId, that.terminalId);
		}

		@Override
		public int hashCode() {
			return Objects.hash(terminalId);
		}

		@Override
		public String toString() {
			return "CreateTerminalResponse[terminalId=" + terminalId + "]";
		}
	}

	/**
	 * Terminal output request
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public static final class TerminalOutputRequest {
		private final @JsonProperty("sessionId") String sessionId;
		private final @JsonProperty("terminalId") String terminalId;

		public TerminalOutputRequest(@JsonProperty("sessionId") String sessionId, @JsonProperty("terminalId") String terminalId) {
			this.sessionId = sessionId;
			this.terminalId = terminalId;
		}

		public String sessionId() { return sessionId; }
		public String terminalId() { return terminalId; }

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null || getClass() != o.getClass()) return false;
			TerminalOutputRequest that = (TerminalOutputRequest) o;
			return Objects.equals(sessionId, that.sessionId) && Objects.equals(terminalId, that.terminalId);
		}

		@Override
		public int hashCode() {
			return Objects.hash(sessionId, terminalId);
		}

		@Override
		public String toString() {
			return "TerminalOutputRequest[sessionId=" + sessionId + ", terminalId=" + terminalId + "]";
		}
	}

	/**
	 * Terminal output response
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public static final class TerminalOutputResponse {
		private final @JsonProperty("output") String output;
		private final @JsonProperty("truncated") boolean truncated;
		private final @JsonProperty("exitStatus") TerminalExitStatus exitStatus;

		public TerminalOutputResponse(@JsonProperty("output") String output, @JsonProperty("truncated") boolean truncated, @JsonProperty("exitStatus") TerminalExitStatus exitStatus) {
			this.output = output;
			this.truncated = truncated;
			this.exitStatus = exitStatus;
		}

		public String output() { return output; }
		public boolean truncated() { return truncated; }
		public TerminalExitStatus exitStatus() { return exitStatus; }

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null || getClass() != o.getClass()) return false;
			TerminalOutputResponse that = (TerminalOutputResponse) o;
			return Objects.equals(output, that.output) && truncated == that.truncated && Objects.equals(exitStatus, that.exitStatus);
		}

		@Override
		public int hashCode() {
			return Objects.hash(output, truncated, exitStatus);
		}

		@Override
		public String toString() {
			return "TerminalOutputResponse[output=" + output + ", truncated=" + truncated + ", exitStatus=" + exitStatus + "]";
		}
	}

	/**
	 * Release terminal request
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public static final class ReleaseTerminalRequest {
		private final @JsonProperty("sessionId") String sessionId;
		private final @JsonProperty("terminalId") String terminalId;

		public ReleaseTerminalRequest(@JsonProperty("sessionId") String sessionId, @JsonProperty("terminalId") String terminalId) {
			this.sessionId = sessionId;
			this.terminalId = terminalId;
		}

		public String sessionId() { return sessionId; }
		public String terminalId() { return terminalId; }

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null || getClass() != o.getClass()) return false;
			ReleaseTerminalRequest that = (ReleaseTerminalRequest) o;
			return Objects.equals(sessionId, that.sessionId) && Objects.equals(terminalId, that.terminalId);
		}

		@Override
		public int hashCode() {
			return Objects.hash(sessionId, terminalId);
		}

		@Override
		public String toString() {
			return "ReleaseTerminalRequest[sessionId=" + sessionId + ", terminalId=" + terminalId + "]";
		}
	}

	/**
	 * Release terminal response
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public static final class ReleaseTerminalResponse {
		public ReleaseTerminalResponse() {
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null || getClass() != o.getClass()) return false;
			ReleaseTerminalResponse that = (ReleaseTerminalResponse) o;
			return true;
		}

		@Override
		public int hashCode() {
			return 0;
		}

		@Override
		public String toString() {
			return "ReleaseTerminalResponse[]";
		}
	}

	/**
	 * Wait for terminal exit request
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public static final class WaitForTerminalExitRequest {
		private final @JsonProperty("sessionId") String sessionId;
		private final @JsonProperty("terminalId") String terminalId;

		public WaitForTerminalExitRequest(@JsonProperty("sessionId") String sessionId, @JsonProperty("terminalId") String terminalId) {
			this.sessionId = sessionId;
			this.terminalId = terminalId;
		}

		public String sessionId() { return sessionId; }
		public String terminalId() { return terminalId; }

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null || getClass() != o.getClass()) return false;
			WaitForTerminalExitRequest that = (WaitForTerminalExitRequest) o;
			return Objects.equals(sessionId, that.sessionId) && Objects.equals(terminalId, that.terminalId);
		}

		@Override
		public int hashCode() {
			return Objects.hash(sessionId, terminalId);
		}

		@Override
		public String toString() {
			return "WaitForTerminalExitRequest[sessionId=" + sessionId + ", terminalId=" + terminalId + "]";
		}
	}

	/**
	 * Wait for terminal exit response
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public static final class WaitForTerminalExitResponse {
		private final @JsonProperty("exitCode") Integer exitCode;
		private final @JsonProperty("signal") String signal;

		public WaitForTerminalExitResponse(@JsonProperty("exitCode") Integer exitCode, @JsonProperty("signal") String signal) {
			this.exitCode = exitCode;
			this.signal = signal;
		}

		public Integer exitCode() { return exitCode; }
		public String signal() { return signal; }

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null || getClass() != o.getClass()) return false;
			WaitForTerminalExitResponse that = (WaitForTerminalExitResponse) o;
			return Objects.equals(exitCode, that.exitCode) && Objects.equals(signal, that.signal);
		}

		@Override
		public int hashCode() {
			return Objects.hash(exitCode, signal);
		}

		@Override
		public String toString() {
			return "WaitForTerminalExitResponse[exitCode=" + exitCode + ", signal=" + signal + "]";
		}
	}

	/**
	 * Kill terminal request
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public static final class KillTerminalCommandRequest {
		private final @JsonProperty("sessionId") String sessionId;
		private final @JsonProperty("terminalId") String terminalId;

		public KillTerminalCommandRequest(@JsonProperty("sessionId") String sessionId, @JsonProperty("terminalId") String terminalId) {
			this.sessionId = sessionId;
			this.terminalId = terminalId;
		}

		public String sessionId() { return sessionId; }
		public String terminalId() { return terminalId; }

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null || getClass() != o.getClass()) return false;
			KillTerminalCommandRequest that = (KillTerminalCommandRequest) o;
			return Objects.equals(sessionId, that.sessionId) && Objects.equals(terminalId, that.terminalId);
		}

		@Override
		public int hashCode() {
			return Objects.hash(sessionId, terminalId);
		}

		@Override
		public String toString() {
			return "KillTerminalCommandRequest[sessionId=" + sessionId + ", terminalId=" + terminalId + "]";
		}
	}

	/**
	 * Kill terminal response
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public static final class KillTerminalCommandResponse {
		public KillTerminalCommandResponse() {
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null || getClass() != o.getClass()) return false;
			KillTerminalCommandResponse that = (KillTerminalCommandResponse) o;
			return true;
		}

		@Override
		public int hashCode() {
			return 0;
		}

		@Override
		public String toString() {
			return "KillTerminalCommandResponse[]";
		}
	}

	// ---------------------------
	// Capabilities
	// ---------------------------

	/**
	 * Client capabilities
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public static final class ClientCapabilities {
		private final @JsonProperty("fs") FileSystemCapability fs;
		private final @JsonProperty("terminal") Boolean terminal;
		private final @JsonProperty("_meta") Map<String, Object> meta;

		public ClientCapabilities(@JsonProperty("fs") FileSystemCapability fs, @JsonProperty("terminal") Boolean terminal, @JsonProperty("_meta") Map<String, Object> meta) {
			this.fs = fs;
			this.terminal = terminal;
			this.meta = meta;
		}

		public ClientCapabilities() {
			this(new FileSystemCapability(), false, null);
		}

		public ClientCapabilities(FileSystemCapability fs, Boolean terminal) {
			this(fs, terminal, null);
		}

		public FileSystemCapability fs() { return fs; }
		public Boolean terminal() { return terminal; }
		public Map<String, Object> meta() { return meta; }

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null || getClass() != o.getClass()) return false;
			ClientCapabilities that = (ClientCapabilities) o;
			return Objects.equals(fs, that.fs) && Objects.equals(terminal, that.terminal) && Objects.equals(meta, that.meta);
		}

		@Override
		public int hashCode() {
			return Objects.hash(fs, terminal, meta);
		}

		@Override
		public String toString() {
			return "ClientCapabilities[fs=" + fs + ", terminal=" + terminal + ", meta=" + meta + "]";
		}
	}

	/**
	 * File system capabilities
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public static final class FileSystemCapability {
		private final @JsonProperty("readTextFile") Boolean readTextFile;
		private final @JsonProperty("writeTextFile") Boolean writeTextFile;

		public FileSystemCapability(@JsonProperty("readTextFile") Boolean readTextFile, @JsonProperty("writeTextFile") Boolean writeTextFile) {
			this.readTextFile = readTextFile;
			this.writeTextFile = writeTextFile;
		}

		public FileSystemCapability() {
			this(false, false);
		}

		public Boolean readTextFile() { return readTextFile; }
		public Boolean writeTextFile() { return writeTextFile; }

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null || getClass() != o.getClass()) return false;
			FileSystemCapability that = (FileSystemCapability) o;
			return Objects.equals(readTextFile, that.readTextFile) && Objects.equals(writeTextFile, that.writeTextFile);
		}

		@Override
		public int hashCode() {
			return Objects.hash(readTextFile, writeTextFile);
		}

		@Override
		public String toString() {
			return "FileSystemCapability[readTextFile=" + readTextFile + ", writeTextFile=" + writeTextFile + "]";
		}
	}

	/**
	 * Agent capabilities
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public static final class AgentCapabilities {
		private final @JsonProperty("loadSession") Boolean loadSession;
		private final @JsonProperty("mcpCapabilities") McpCapabilities mcpCapabilities;
		private final @JsonProperty("promptCapabilities") PromptCapabilities promptCapabilities;
		private final @JsonProperty("_meta") Map<String, Object> meta;

		public AgentCapabilities(@JsonProperty("loadSession") Boolean loadSession, @JsonProperty("mcpCapabilities") McpCapabilities mcpCapabilities, @JsonProperty("promptCapabilities") PromptCapabilities promptCapabilities, @JsonProperty("_meta") Map<String, Object> meta) {
			this.loadSession = loadSession;
			this.mcpCapabilities = mcpCapabilities;
			this.promptCapabilities = promptCapabilities;
			this.meta = meta;
		}

		public AgentCapabilities() {
			this(false, new McpCapabilities(), new PromptCapabilities(), null);
		}

		public AgentCapabilities(Boolean loadSession, McpCapabilities mcpCapabilities,
				PromptCapabilities promptCapabilities) {
			this(loadSession, mcpCapabilities, promptCapabilities, null);
		}

		public Boolean loadSession() { return loadSession; }
		public McpCapabilities mcpCapabilities() { return mcpCapabilities; }
		public PromptCapabilities promptCapabilities() { return promptCapabilities; }
		public Map<String, Object> meta() { return meta; }

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null || getClass() != o.getClass()) return false;
			AgentCapabilities that = (AgentCapabilities) o;
			return Objects.equals(loadSession, that.loadSession) && Objects.equals(mcpCapabilities, that.mcpCapabilities) && Objects.equals(promptCapabilities, that.promptCapabilities) && Objects.equals(meta, that.meta);
		}

		@Override
		public int hashCode() {
			return Objects.hash(loadSession, mcpCapabilities, promptCapabilities, meta);
		}

		@Override
		public String toString() {
			return "AgentCapabilities[loadSession=" + loadSession + ", mcpCapabilities=" + mcpCapabilities + ", promptCapabilities=" + promptCapabilities + ", meta=" + meta + "]";
		}
	}

	/**
	 * MCP capabilities supported by agent
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public static final class McpCapabilities {
		private final @JsonProperty("http") Boolean http;
		private final @JsonProperty("sse") Boolean sse;

		public McpCapabilities(@JsonProperty("http") Boolean http, @JsonProperty("sse") Boolean sse) {
			this.http = http;
			this.sse = sse;
		}

		public McpCapabilities() {
			this(false, false);
		}

		public Boolean http() { return http; }
		public Boolean sse() { return sse; }

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null || getClass() != o.getClass()) return false;
			McpCapabilities that = (McpCapabilities) o;
			return Objects.equals(http, that.http) && Objects.equals(sse, that.sse);
		}

		@Override
		public int hashCode() {
			return Objects.hash(http, sse);
		}

		@Override
		public String toString() {
			return "McpCapabilities[http=" + http + ", sse=" + sse + "]";
		}
	}

	/**
	 * Prompt capabilities
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public static final class PromptCapabilities {
		private final @JsonProperty("audio") Boolean audio;
		private final @JsonProperty("embeddedContext") Boolean embeddedContext;
		private final @JsonProperty("image") Boolean image;

		public PromptCapabilities(@JsonProperty("audio") Boolean audio, @JsonProperty("embeddedContext") Boolean embeddedContext, @JsonProperty("image") Boolean image) {
			this.audio = audio;
			this.embeddedContext = embeddedContext;
			this.image = image;
		}

		public PromptCapabilities() {
			this(false, false, false);
		}

		public Boolean audio() { return audio; }
		public Boolean embeddedContext() { return embeddedContext; }
		public Boolean image() { return image; }

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null || getClass() != o.getClass()) return false;
			PromptCapabilities that = (PromptCapabilities) o;
			return Objects.equals(audio, that.audio) && Objects.equals(embeddedContext, that.embeddedContext) && Objects.equals(image, that.image);
		}

		@Override
		public int hashCode() {
			return Objects.hash(audio, embeddedContext, image);
		}

		@Override
		public String toString() {
			return "PromptCapabilities[audio=" + audio + ", embeddedContext=" + embeddedContext + ", image=" + image + "]";
		}
	}

	// ---------------------------
	// Session Types
	// ---------------------------

	/**
	 * Session mode state
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public static final class SessionModeState {
		private final @JsonProperty("currentModeId") String currentModeId;
		private final @JsonProperty("availableModes") List<SessionMode> availableModes;

		public SessionModeState(@JsonProperty("currentModeId") String currentModeId, @JsonProperty("availableModes") List<SessionMode> availableModes) {
			this.currentModeId = currentModeId;
			this.availableModes = availableModes;
		}

		public String currentModeId() { return currentModeId; }
		public List<SessionMode> availableModes() { return availableModes; }

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null || getClass() != o.getClass()) return false;
			SessionModeState that = (SessionModeState) o;
			return Objects.equals(currentModeId, that.currentModeId) && Objects.equals(availableModes, that.availableModes);
		}

		@Override
		public int hashCode() {
			return Objects.hash(currentModeId, availableModes);
		}

		@Override
		public String toString() {
			return "SessionModeState[currentModeId=" + currentModeId + ", availableModes=" + availableModes + "]";
		}
	}

	/**
	 * Session mode
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public static final class SessionMode {
		private final @JsonProperty("id") String id;
		private final @JsonProperty("name") String name;
		private final @JsonProperty("description") String description;

		public SessionMode(@JsonProperty("id") String id, @JsonProperty("name") String name, @JsonProperty("description") String description) {
			this.id = id;
			this.name = name;
			this.description = description;
		}

		public String id() { return id; }
		public String name() { return name; }
		public String description() { return description; }

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null || getClass() != o.getClass()) return false;
			SessionMode that = (SessionMode) o;
			return Objects.equals(id, that.id) && Objects.equals(name, that.name) && Objects.equals(description, that.description);
		}

		@Override
		public int hashCode() {
			return Objects.hash(id, name, description);
		}

		@Override
		public String toString() {
			return "SessionMode[id=" + id + ", name=" + name + ", description=" + description + "]";
		}
	}

	/**
	 * Session model state (UNSTABLE)
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public static final class SessionModelState {
		private final @JsonProperty("currentModelId") String currentModelId;
		private final @JsonProperty("availableModels") List<ModelInfo> availableModels;

		public SessionModelState(@JsonProperty("currentModelId") String currentModelId, @JsonProperty("availableModels") List<ModelInfo> availableModels) {
			this.currentModelId = currentModelId;
			this.availableModels = availableModels;
		}

		public String currentModelId() { return currentModelId; }
		public List<ModelInfo> availableModels() { return availableModels; }

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null || getClass() != o.getClass()) return false;
			SessionModelState that = (SessionModelState) o;
			return Objects.equals(currentModelId, that.currentModelId) && Objects.equals(availableModels, that.availableModels);
		}

		@Override
		public int hashCode() {
			return Objects.hash(currentModelId, availableModels);
		}

		@Override
		public String toString() {
			return "SessionModelState[currentModelId=" + currentModelId + ", availableModels=" + availableModels + "]";
		}
	}

	/**
	 * Model info (UNSTABLE)
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public static final class ModelInfo {
		private final @JsonProperty("modelId") String modelId;
		private final @JsonProperty("name") String name;
		private final @JsonProperty("description") String description;

		public ModelInfo(@JsonProperty("modelId") String modelId, @JsonProperty("name") String name, @JsonProperty("description") String description) {
			this.modelId = modelId;
			this.name = name;
			this.description = description;
		}

		public String modelId() { return modelId; }
		public String name() { return name; }
		public String description() { return description; }

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null || getClass() != o.getClass()) return false;
			ModelInfo that = (ModelInfo) o;
			return Objects.equals(modelId, that.modelId) && Objects.equals(name, that.name) && Objects.equals(description, that.description);
		}

		@Override
		public int hashCode() {
			return Objects.hash(modelId, name, description);
		}

		@Override
		public String toString() {
			return "ModelInfo[modelId=" + modelId + ", name=" + name + ", description=" + description + "]";
		}
	}

	// ---------------------------
	// Content Types
	// ---------------------------

	/**
	 * Content block - base type for all content
	 */
	@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
	@JsonSubTypes({ @JsonSubTypes.Type(value = TextContent.class, name = "text"),
			@JsonSubTypes.Type(value = ImageContent.class, name = "image"),
			@JsonSubTypes.Type(value = AudioContent.class, name = "audio"),
			@JsonSubTypes.Type(value = ResourceLink.class, name = "resource_link"),
			@JsonSubTypes.Type(value = Resource.class, name = "resource") })
	public interface ContentBlock {

	}

	/**
	 * Text content
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public static final class TextContent implements ContentBlock {
		private final @JsonProperty("type") String type;
		private final @JsonProperty("text") String text;
		private final @JsonProperty("annotations") Annotations annotations;
		private final @JsonProperty("_meta") Map<String, Object> meta;

		public TextContent(@JsonProperty("type") String type, @JsonProperty("text") String text, @JsonProperty("annotations") Annotations annotations, @JsonProperty("_meta") Map<String, Object> meta) {
			this.type = type;
			this.text = text;
			this.annotations = annotations;
			this.meta = meta;
		}

		public TextContent(String text) {
			this("text", text, null, null);
		}

		public String type() { return type; }
		public String text() { return text; }
		public Annotations annotations() { return annotations; }
		public Map<String, Object> meta() { return meta; }

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null || getClass() != o.getClass()) return false;
			TextContent that = (TextContent) o;
			return Objects.equals(type, that.type) && Objects.equals(text, that.text) && Objects.equals(annotations, that.annotations) && Objects.equals(meta, that.meta);
		}

		@Override
		public int hashCode() {
			return Objects.hash(type, text, annotations, meta);
		}

		@Override
		public String toString() {
			return "TextContent[type=" + type + ", text=" + text + ", annotations=" + annotations + ", meta=" + meta + "]";
		}
	}

	/**
	 * Image content
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public static final class ImageContent implements ContentBlock {
		private final @JsonProperty("type") String type;
		private final @JsonProperty("data") String data;
		private final @JsonProperty("mimeType") String mimeType;
		private final @JsonProperty("uri") String uri;
		private final @JsonProperty("annotations") Annotations annotations;
		private final @JsonProperty("_meta") Map<String, Object> meta;

		public ImageContent(@JsonProperty("type") String type, @JsonProperty("data") String data, @JsonProperty("mimeType") String mimeType, @JsonProperty("uri") String uri, @JsonProperty("annotations") Annotations annotations, @JsonProperty("_meta") Map<String, Object> meta) {
			this.type = type;
			this.data = data;
			this.mimeType = mimeType;
			this.uri = uri;
			this.annotations = annotations;
			this.meta = meta;
		}

		public String type() { return type; }
		public String data() { return data; }
		public String mimeType() { return mimeType; }
		public String uri() { return uri; }
		public Annotations annotations() { return annotations; }
		public Map<String, Object> meta() { return meta; }

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null || getClass() != o.getClass()) return false;
			ImageContent that = (ImageContent) o;
			return Objects.equals(type, that.type) && Objects.equals(data, that.data) && Objects.equals(mimeType, that.mimeType) && Objects.equals(uri, that.uri) && Objects.equals(annotations, that.annotations) && Objects.equals(meta, that.meta);
		}

		@Override
		public int hashCode() {
			return Objects.hash(type, data, mimeType, uri, annotations, meta);
		}

		@Override
		public String toString() {
			return "ImageContent[type=" + type + ", data=" + data + ", mimeType=" + mimeType + ", uri=" + uri + ", annotations=" + annotations + ", meta=" + meta + "]";
		}
	}

	/**
	 * Audio content
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public static final class AudioContent implements ContentBlock {
		private final @JsonProperty("type") String type;
		private final @JsonProperty("data") String data;
		private final @JsonProperty("mimeType") String mimeType;
		private final @JsonProperty("annotations") Annotations annotations;
		private final @JsonProperty("_meta") Map<String, Object> meta;

		public AudioContent(@JsonProperty("type") String type, @JsonProperty("data") String data, @JsonProperty("mimeType") String mimeType, @JsonProperty("annotations") Annotations annotations, @JsonProperty("_meta") Map<String, Object> meta) {
			this.type = type;
			this.data = data;
			this.mimeType = mimeType;
			this.annotations = annotations;
			this.meta = meta;
		}

		public String type() { return type; }
		public String data() { return data; }
		public String mimeType() { return mimeType; }
		public Annotations annotations() { return annotations; }
		public Map<String, Object> meta() { return meta; }

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null || getClass() != o.getClass()) return false;
			AudioContent that = (AudioContent) o;
			return Objects.equals(type, that.type) && Objects.equals(data, that.data) && Objects.equals(mimeType, that.mimeType) && Objects.equals(annotations, that.annotations) && Objects.equals(meta, that.meta);
		}

		@Override
		public int hashCode() {
			return Objects.hash(type, data, mimeType, annotations, meta);
		}

		@Override
		public String toString() {
			return "AudioContent[type=" + type + ", data=" + data + ", mimeType=" + mimeType + ", annotations=" + annotations + ", meta=" + meta + "]";
		}
	}

	/**
	 * Resource link
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public static final class ResourceLink implements ContentBlock {
		private final @JsonProperty("type") String type;
		private final @JsonProperty("name") String name;
		private final @JsonProperty("uri") String uri;
		private final @JsonProperty("title") String title;
		private final @JsonProperty("description") String description;
		private final @JsonProperty("mimeType") String mimeType;
		private final @JsonProperty("size") Long size;
		private final @JsonProperty("annotations") Annotations annotations;
		private final @JsonProperty("_meta") Map<String, Object> meta;

		public ResourceLink(@JsonProperty("type") String type, @JsonProperty("name") String name, @JsonProperty("uri") String uri, @JsonProperty("title") String title, @JsonProperty("description") String description, @JsonProperty("mimeType") String mimeType, @JsonProperty("size") Long size, @JsonProperty("annotations") Annotations annotations, @JsonProperty("_meta") Map<String, Object> meta) {
			this.type = type;
			this.name = name;
			this.uri = uri;
			this.title = title;
			this.description = description;
			this.mimeType = mimeType;
			this.size = size;
			this.annotations = annotations;
			this.meta = meta;
		}

		public String type() { return type; }
		public String name() { return name; }
		public String uri() { return uri; }
		public String title() { return title; }
		public String description() { return description; }
		public String mimeType() { return mimeType; }
		public Long size() { return size; }
		public Annotations annotations() { return annotations; }
		public Map<String, Object> meta() { return meta; }

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null || getClass() != o.getClass()) return false;
			ResourceLink that = (ResourceLink) o;
			return Objects.equals(type, that.type) && Objects.equals(name, that.name) && Objects.equals(uri, that.uri) && Objects.equals(title, that.title) && Objects.equals(description, that.description) && Objects.equals(mimeType, that.mimeType) && Objects.equals(size, that.size) && Objects.equals(annotations, that.annotations) && Objects.equals(meta, that.meta);
		}

		@Override
		public int hashCode() {
			return Objects.hash(type, name, uri, title, description, mimeType, size, annotations, meta);
		}

		@Override
		public String toString() {
			return "ResourceLink[type=" + type + ", name=" + name + ", uri=" + uri + ", title=" + title + ", description=" + description + ", mimeType=" + mimeType + ", size=" + size + ", annotations=" + annotations + ", meta=" + meta + "]";
		}
	}

	/**
	 * Embedded resource
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public static final class Resource implements ContentBlock {
		private final @JsonProperty("type") String type;
		private final @JsonProperty("resource") EmbeddedResourceResource resource;
		private final @JsonProperty("annotations") Annotations annotations;
		private final @JsonProperty("_meta") Map<String, Object> meta;

		public Resource(@JsonProperty("type") String type, @JsonProperty("resource") EmbeddedResourceResource resource, @JsonProperty("annotations") Annotations annotations, @JsonProperty("_meta") Map<String, Object> meta) {
			this.type = type;
			this.resource = resource;
			this.annotations = annotations;
			this.meta = meta;
		}

		public String type() { return type; }
		public EmbeddedResourceResource resource() { return resource; }
		public Annotations annotations() { return annotations; }
		public Map<String, Object> meta() { return meta; }

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null || getClass() != o.getClass()) return false;
			Resource that = (Resource) o;
			return Objects.equals(type, that.type) && Objects.equals(resource, that.resource) && Objects.equals(annotations, that.annotations) && Objects.equals(meta, that.meta);
		}

		@Override
		public int hashCode() {
			return Objects.hash(type, resource, annotations, meta);
		}

		@Override
		public String toString() {
			return "Resource[type=" + type + ", resource=" + resource + ", annotations=" + annotations + ", meta=" + meta + "]";
		}
	}

	/**
	 * Embedded resource content
	 */
	@JsonTypeInfo(use = JsonTypeInfo.Id.DEDUCTION)
	@JsonSubTypes({ @JsonSubTypes.Type(value = TextResourceContents.class),
			@JsonSubTypes.Type(value = BlobResourceContents.class) })
	public interface EmbeddedResourceResource {

	}

	/**
	 * Text resource contents
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public static final class TextResourceContents implements EmbeddedResourceResource {
		private final @JsonProperty("text") String text;
		private final @JsonProperty("uri") String uri;
		private final @JsonProperty("mimeType") String mimeType;

		public TextResourceContents(@JsonProperty("text") String text, @JsonProperty("uri") String uri, @JsonProperty("mimeType") String mimeType) {
			this.text = text;
			this.uri = uri;
			this.mimeType = mimeType;
		}

		public String text() { return text; }
		public String uri() { return uri; }
		public String mimeType() { return mimeType; }

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null || getClass() != o.getClass()) return false;
			TextResourceContents that = (TextResourceContents) o;
			return Objects.equals(text, that.text) && Objects.equals(uri, that.uri) && Objects.equals(mimeType, that.mimeType);
		}

		@Override
		public int hashCode() {
			return Objects.hash(text, uri, mimeType);
		}

		@Override
		public String toString() {
			return "TextResourceContents[text=" + text + ", uri=" + uri + ", mimeType=" + mimeType + "]";
		}
	}

	/**
	 * Blob resource contents
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public static final class BlobResourceContents implements EmbeddedResourceResource {
		private final @JsonProperty("blob") String blob;
		private final @JsonProperty("uri") String uri;
		private final @JsonProperty("mimeType") String mimeType;

		public BlobResourceContents(@JsonProperty("blob") String blob, @JsonProperty("uri") String uri, @JsonProperty("mimeType") String mimeType) {
			this.blob = blob;
			this.uri = uri;
			this.mimeType = mimeType;
		}

		public String blob() { return blob; }
		public String uri() { return uri; }
		public String mimeType() { return mimeType; }

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null || getClass() != o.getClass()) return false;
			BlobResourceContents that = (BlobResourceContents) o;
			return Objects.equals(blob, that.blob) && Objects.equals(uri, that.uri) && Objects.equals(mimeType, that.mimeType);
		}

		@Override
		public int hashCode() {
			return Objects.hash(blob, uri, mimeType);
		}

		@Override
		public String toString() {
			return "BlobResourceContents[blob=" + blob + ", uri=" + uri + ", mimeType=" + mimeType + "]";
		}
	}

	/**
	 * Annotations for content
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public static final class Annotations {
		private final @JsonProperty("audience") List<Role> audience;
		private final @JsonProperty("priority") Double priority;
		private final @JsonProperty("lastModified") String lastModified;

		public Annotations(@JsonProperty("audience") List<Role> audience, @JsonProperty("priority") Double priority, @JsonProperty("lastModified") String lastModified) {
			this.audience = audience;
			this.priority = priority;
			this.lastModified = lastModified;
		}

		public List<Role> audience() { return audience; }
		public Double priority() { return priority; }
		public String lastModified() { return lastModified; }

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null || getClass() != o.getClass()) return false;
			Annotations that = (Annotations) o;
			return Objects.equals(audience, that.audience) && Objects.equals(priority, that.priority) && Objects.equals(lastModified, that.lastModified);
		}

		@Override
		public int hashCode() {
			return Objects.hash(audience, priority, lastModified);
		}

		@Override
		public String toString() {
			return "Annotations[audience=" + audience + ", priority=" + priority + ", lastModified=" + lastModified + "]";
		}
	}

	// ---------------------------
	// Session Updates
	// ---------------------------

	/**
	 * Session update - different types of updates
	 */
	@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "sessionUpdate", visible = true)
	@JsonSubTypes({ @JsonSubTypes.Type(value = UserMessageChunk.class, name = "user_message_chunk"),
			@JsonSubTypes.Type(value = AgentMessageChunk.class, name = "agent_message_chunk"),
			@JsonSubTypes.Type(value = AgentThoughtChunk.class, name = "agent_thought_chunk"),
			@JsonSubTypes.Type(value = ToolCall.class, name = "tool_call"),
			@JsonSubTypes.Type(value = ToolCallUpdateNotification.class, name = "tool_call_update"),
			@JsonSubTypes.Type(value = Plan.class, name = "plan"),
			@JsonSubTypes.Type(value = AvailableCommandsUpdate.class, name = "available_commands_update"),
			@JsonSubTypes.Type(value = CurrentModeUpdate.class, name = "current_mode_update"),
			@JsonSubTypes.Type(value = UsageUpdate.class, name = "usage_update") })
	public interface SessionUpdate {

	}

	/**
	 * User message chunk
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public static final class UserMessageChunk implements SessionUpdate {
		private final @JsonProperty("sessionUpdate") String sessionUpdate;
		private final @JsonProperty("content") ContentBlock content;
		private final @JsonProperty("_meta") Map<String, Object> meta;

		public UserMessageChunk(@JsonProperty("sessionUpdate") String sessionUpdate, @JsonProperty("content") ContentBlock content, @JsonProperty("_meta") Map<String, Object> meta) {
			this.sessionUpdate = sessionUpdate;
			this.content = content;
			this.meta = meta;
		}

		public UserMessageChunk(String sessionUpdate, ContentBlock content) {
			this(sessionUpdate, content, null);
		}

		public String sessionUpdate() { return sessionUpdate; }
		public ContentBlock content() { return content; }
		public Map<String, Object> meta() { return meta; }

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null || getClass() != o.getClass()) return false;
			UserMessageChunk that = (UserMessageChunk) o;
			return Objects.equals(sessionUpdate, that.sessionUpdate) && Objects.equals(content, that.content) && Objects.equals(meta, that.meta);
		}

		@Override
		public int hashCode() {
			return Objects.hash(sessionUpdate, content, meta);
		}

		@Override
		public String toString() {
			return "UserMessageChunk[sessionUpdate=" + sessionUpdate + ", content=" + content + ", meta=" + meta + "]";
		}
	}

	/**
	 * Agent message chunk
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public static final class AgentMessageChunk implements SessionUpdate {
		private final @JsonProperty("sessionUpdate") String sessionUpdate;
		private final @JsonProperty("content") ContentBlock content;
		private final @JsonProperty("_meta") Map<String, Object> meta;

		public AgentMessageChunk(@JsonProperty("sessionUpdate") String sessionUpdate, @JsonProperty("content") ContentBlock content, @JsonProperty("_meta") Map<String, Object> meta) {
			this.sessionUpdate = sessionUpdate;
			this.content = content;
			this.meta = meta;
		}

		public AgentMessageChunk(String sessionUpdate, ContentBlock content) {
			this(sessionUpdate, content, null);
		}

		public String sessionUpdate() { return sessionUpdate; }
		public ContentBlock content() { return content; }
		public Map<String, Object> meta() { return meta; }

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null || getClass() != o.getClass()) return false;
			AgentMessageChunk that = (AgentMessageChunk) o;
			return Objects.equals(sessionUpdate, that.sessionUpdate) && Objects.equals(content, that.content) && Objects.equals(meta, that.meta);
		}

		@Override
		public int hashCode() {
			return Objects.hash(sessionUpdate, content, meta);
		}

		@Override
		public String toString() {
			return "AgentMessageChunk[sessionUpdate=" + sessionUpdate + ", content=" + content + ", meta=" + meta + "]";
		}
	}

	/**
	 * Agent thought chunk
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public static final class AgentThoughtChunk implements SessionUpdate {
		private final @JsonProperty("sessionUpdate") String sessionUpdate;
		private final @JsonProperty("content") ContentBlock content;
		private final @JsonProperty("_meta") Map<String, Object> meta;

		public AgentThoughtChunk(@JsonProperty("sessionUpdate") String sessionUpdate, @JsonProperty("content") ContentBlock content, @JsonProperty("_meta") Map<String, Object> meta) {
			this.sessionUpdate = sessionUpdate;
			this.content = content;
			this.meta = meta;
		}

		public AgentThoughtChunk(String sessionUpdate, ContentBlock content) {
			this(sessionUpdate, content, null);
		}

		public String sessionUpdate() { return sessionUpdate; }
		public ContentBlock content() { return content; }
		public Map<String, Object> meta() { return meta; }

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null || getClass() != o.getClass()) return false;
			AgentThoughtChunk that = (AgentThoughtChunk) o;
			return Objects.equals(sessionUpdate, that.sessionUpdate) && Objects.equals(content, that.content) && Objects.equals(meta, that.meta);
		}

		@Override
		public int hashCode() {
			return Objects.hash(sessionUpdate, content, meta);
		}

		@Override
		public String toString() {
			return "AgentThoughtChunk[sessionUpdate=" + sessionUpdate + ", content=" + content + ", meta=" + meta + "]";
		}
	}

	/**
	 * Tool call
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public static final class ToolCall implements SessionUpdate {
		private final @JsonProperty("sessionUpdate") String sessionUpdate;
		private final @JsonProperty("toolCallId") String toolCallId;
		private final @JsonProperty("title") String title;
		private final @JsonProperty("kind") ToolKind kind;
		private final @JsonProperty("status") ToolCallStatus status;
		private final @JsonProperty("content") List<ToolCallContent> content;
		private final @JsonProperty("locations") List<ToolCallLocation> locations;
		private final @JsonProperty("rawInput") Object rawInput;
		private final @JsonProperty("rawOutput") Object rawOutput;
		private final @JsonProperty("_meta") Map<String, Object> meta;

		public ToolCall(@JsonProperty("sessionUpdate") String sessionUpdate, @JsonProperty("toolCallId") String toolCallId, @JsonProperty("title") String title, @JsonProperty("kind") ToolKind kind, @JsonProperty("status") ToolCallStatus status, @JsonProperty("content") List<ToolCallContent> content, @JsonProperty("locations") List<ToolCallLocation> locations, @JsonProperty("rawInput") Object rawInput, @JsonProperty("rawOutput") Object rawOutput, @JsonProperty("_meta") Map<String, Object> meta) {
			this.sessionUpdate = sessionUpdate;
			this.toolCallId = toolCallId;
			this.title = title;
			this.kind = kind;
			this.status = status;
			this.content = content;
			this.locations = locations;
			this.rawInput = rawInput;
			this.rawOutput = rawOutput;
			this.meta = meta;
		}

		public String sessionUpdate() { return sessionUpdate; }
		public String toolCallId() { return toolCallId; }
		public String title() { return title; }
		public ToolKind kind() { return kind; }
		public ToolCallStatus status() { return status; }
		public List<ToolCallContent> content() { return content; }
		public List<ToolCallLocation> locations() { return locations; }
		public Object rawInput() { return rawInput; }
		public Object rawOutput() { return rawOutput; }
		public Map<String, Object> meta() { return meta; }

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null || getClass() != o.getClass()) return false;
			ToolCall that = (ToolCall) o;
			return Objects.equals(sessionUpdate, that.sessionUpdate) && Objects.equals(toolCallId, that.toolCallId) && Objects.equals(title, that.title) && Objects.equals(kind, that.kind) && Objects.equals(status, that.status) && Objects.equals(content, that.content) && Objects.equals(locations, that.locations) && Objects.equals(rawInput, that.rawInput) && Objects.equals(rawOutput, that.rawOutput) && Objects.equals(meta, that.meta);
		}

		@Override
		public int hashCode() {
			return Objects.hash(sessionUpdate, toolCallId, title, kind, status, content, locations, rawInput, rawOutput, meta);
		}

		@Override
		public String toString() {
			return "ToolCall[sessionUpdate=" + sessionUpdate + ", toolCallId=" + toolCallId + ", title=" + title + ", kind=" + kind + ", status=" + status + ", content=" + content + ", locations=" + locations + ", rawInput=" + rawInput + ", rawOutput=" + rawOutput + ", meta=" + meta + "]";
		}
	}

	/**
	 * Tool call update
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public static final class ToolCallUpdate {
		private final @JsonProperty("toolCallId") String toolCallId;
		private final @JsonProperty("title") String title;
		private final @JsonProperty("kind") ToolKind kind;
		private final @JsonProperty("status") ToolCallStatus status;
		private final @JsonProperty("content") List<ToolCallContent> content;
		private final @JsonProperty("locations") List<ToolCallLocation> locations;
		private final @JsonProperty("rawInput") Object rawInput;
		private final @JsonProperty("rawOutput") Object rawOutput;

		public ToolCallUpdate(@JsonProperty("toolCallId") String toolCallId, @JsonProperty("title") String title, @JsonProperty("kind") ToolKind kind, @JsonProperty("status") ToolCallStatus status, @JsonProperty("content") List<ToolCallContent> content, @JsonProperty("locations") List<ToolCallLocation> locations, @JsonProperty("rawInput") Object rawInput, @JsonProperty("rawOutput") Object rawOutput) {
			this.toolCallId = toolCallId;
			this.title = title;
			this.kind = kind;
			this.status = status;
			this.content = content;
			this.locations = locations;
			this.rawInput = rawInput;
			this.rawOutput = rawOutput;
		}

		public String toolCallId() { return toolCallId; }
		public String title() { return title; }
		public ToolKind kind() { return kind; }
		public ToolCallStatus status() { return status; }
		public List<ToolCallContent> content() { return content; }
		public List<ToolCallLocation> locations() { return locations; }
		public Object rawInput() { return rawInput; }
		public Object rawOutput() { return rawOutput; }

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null || getClass() != o.getClass()) return false;
			ToolCallUpdate that = (ToolCallUpdate) o;
			return Objects.equals(toolCallId, that.toolCallId) && Objects.equals(title, that.title) && Objects.equals(kind, that.kind) && Objects.equals(status, that.status) && Objects.equals(content, that.content) && Objects.equals(locations, that.locations) && Objects.equals(rawInput, that.rawInput) && Objects.equals(rawOutput, that.rawOutput);
		}

		@Override
		public int hashCode() {
			return Objects.hash(toolCallId, title, kind, status, content, locations, rawInput, rawOutput);
		}

		@Override
		public String toString() {
			return "ToolCallUpdate[toolCallId=" + toolCallId + ", title=" + title + ", kind=" + kind + ", status=" + status + ", content=" + content + ", locations=" + locations + ", rawInput=" + rawInput + ", rawOutput=" + rawOutput + "]";
		}
	}

	/**
	 * Tool call update notification
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public static final class ToolCallUpdateNotification implements SessionUpdate {
		private final @JsonProperty("sessionUpdate") String sessionUpdate;
		private final @JsonProperty("toolCallId") String toolCallId;
		private final @JsonProperty("title") String title;
		private final @JsonProperty("kind") ToolKind kind;
		private final @JsonProperty("status") ToolCallStatus status;
		private final @JsonProperty("content") List<ToolCallContent> content;
		private final @JsonProperty("locations") List<ToolCallLocation> locations;
		private final @JsonProperty("rawInput") Object rawInput;
		private final @JsonProperty("rawOutput") Object rawOutput;
		private final @JsonProperty("_meta") Map<String, Object> meta;

		public ToolCallUpdateNotification(@JsonProperty("sessionUpdate") String sessionUpdate, @JsonProperty("toolCallId") String toolCallId, @JsonProperty("title") String title, @JsonProperty("kind") ToolKind kind, @JsonProperty("status") ToolCallStatus status, @JsonProperty("content") List<ToolCallContent> content, @JsonProperty("locations") List<ToolCallLocation> locations, @JsonProperty("rawInput") Object rawInput, @JsonProperty("rawOutput") Object rawOutput, @JsonProperty("_meta") Map<String, Object> meta) {
			this.sessionUpdate = sessionUpdate;
			this.toolCallId = toolCallId;
			this.title = title;
			this.kind = kind;
			this.status = status;
			this.content = content;
			this.locations = locations;
			this.rawInput = rawInput;
			this.rawOutput = rawOutput;
			this.meta = meta;
		}

		public String sessionUpdate() { return sessionUpdate; }
		public String toolCallId() { return toolCallId; }
		public String title() { return title; }
		public ToolKind kind() { return kind; }
		public ToolCallStatus status() { return status; }
		public List<ToolCallContent> content() { return content; }
		public List<ToolCallLocation> locations() { return locations; }
		public Object rawInput() { return rawInput; }
		public Object rawOutput() { return rawOutput; }
		public Map<String, Object> meta() { return meta; }

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null || getClass() != o.getClass()) return false;
			ToolCallUpdateNotification that = (ToolCallUpdateNotification) o;
			return Objects.equals(sessionUpdate, that.sessionUpdate) && Objects.equals(toolCallId, that.toolCallId) && Objects.equals(title, that.title) && Objects.equals(kind, that.kind) && Objects.equals(status, that.status) && Objects.equals(content, that.content) && Objects.equals(locations, that.locations) && Objects.equals(rawInput, that.rawInput) && Objects.equals(rawOutput, that.rawOutput) && Objects.equals(meta, that.meta);
		}

		@Override
		public int hashCode() {
			return Objects.hash(sessionUpdate, toolCallId, title, kind, status, content, locations, rawInput, rawOutput, meta);
		}

		@Override
		public String toString() {
			return "ToolCallUpdateNotification[sessionUpdate=" + sessionUpdate + ", toolCallId=" + toolCallId + ", title=" + title + ", kind=" + kind + ", status=" + status + ", content=" + content + ", locations=" + locations + ", rawInput=" + rawInput + ", rawOutput=" + rawOutput + ", meta=" + meta + "]";
		}
	}

	/**
	 * Plan update
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public static final class Plan implements SessionUpdate {
		private final @JsonProperty("sessionUpdate") String sessionUpdate;
		private final @JsonProperty("entries") List<PlanEntry> entries;
		private final @JsonProperty("_meta") Map<String, Object> meta;

		public Plan(@JsonProperty("sessionUpdate") String sessionUpdate, @JsonProperty("entries") List<PlanEntry> entries, @JsonProperty("_meta") Map<String, Object> meta) {
			this.sessionUpdate = sessionUpdate;
			this.entries = entries;
			this.meta = meta;
		}

		public Plan(String sessionUpdate, List<PlanEntry> entries) {
			this(sessionUpdate, entries, null);
		}

		public String sessionUpdate() { return sessionUpdate; }
		public List<PlanEntry> entries() { return entries; }
		public Map<String, Object> meta() { return meta; }

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null || getClass() != o.getClass()) return false;
			Plan that = (Plan) o;
			return Objects.equals(sessionUpdate, that.sessionUpdate) && Objects.equals(entries, that.entries) && Objects.equals(meta, that.meta);
		}

		@Override
		public int hashCode() {
			return Objects.hash(sessionUpdate, entries, meta);
		}

		@Override
		public String toString() {
			return "Plan[sessionUpdate=" + sessionUpdate + ", entries=" + entries + ", meta=" + meta + "]";
		}
	}

	/**
	 * Available commands update
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public static final class AvailableCommandsUpdate implements SessionUpdate {
		private final @JsonProperty("sessionUpdate") String sessionUpdate;
		private final @JsonProperty("availableCommands") List<AvailableCommand> availableCommands;
		private final @JsonProperty("_meta") Map<String, Object> meta;

		public AvailableCommandsUpdate(@JsonProperty("sessionUpdate") String sessionUpdate, @JsonProperty("availableCommands") List<AvailableCommand> availableCommands, @JsonProperty("_meta") Map<String, Object> meta) {
			this.sessionUpdate = sessionUpdate;
			this.availableCommands = availableCommands;
			this.meta = meta;
		}

		public AvailableCommandsUpdate(String sessionUpdate, List<AvailableCommand> availableCommands) {
			this(sessionUpdate, availableCommands, null);
		}

		public String sessionUpdate() { return sessionUpdate; }
		public List<AvailableCommand> availableCommands() { return availableCommands; }
		public Map<String, Object> meta() { return meta; }

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null || getClass() != o.getClass()) return false;
			AvailableCommandsUpdate that = (AvailableCommandsUpdate) o;
			return Objects.equals(sessionUpdate, that.sessionUpdate) && Objects.equals(availableCommands, that.availableCommands) && Objects.equals(meta, that.meta);
		}

		@Override
		public int hashCode() {
			return Objects.hash(sessionUpdate, availableCommands, meta);
		}

		@Override
		public String toString() {
			return "AvailableCommandsUpdate[sessionUpdate=" + sessionUpdate + ", availableCommands=" + availableCommands + ", meta=" + meta + "]";
		}
	}

	/**
	 * Current mode update
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public static final class CurrentModeUpdate implements SessionUpdate {
		private final @JsonProperty("sessionUpdate") String sessionUpdate;
		private final @JsonProperty("currentModeId") String currentModeId;
		private final @JsonProperty("_meta") Map<String, Object> meta;

		public CurrentModeUpdate(@JsonProperty("sessionUpdate") String sessionUpdate, @JsonProperty("currentModeId") String currentModeId, @JsonProperty("_meta") Map<String, Object> meta) {
			this.sessionUpdate = sessionUpdate;
			this.currentModeId = currentModeId;
			this.meta = meta;
		}

		public CurrentModeUpdate(String sessionUpdate, String currentModeId) {
			this(sessionUpdate, currentModeId, null);
		}

		public String sessionUpdate() { return sessionUpdate; }
		public String currentModeId() { return currentModeId; }
		public Map<String, Object> meta() { return meta; }

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null || getClass() != o.getClass()) return false;
			CurrentModeUpdate that = (CurrentModeUpdate) o;
			return Objects.equals(sessionUpdate, that.sessionUpdate) && Objects.equals(currentModeId, that.currentModeId) && Objects.equals(meta, that.meta);
		}

		@Override
		public int hashCode() {
			return Objects.hash(sessionUpdate, currentModeId, meta);
		}

		@Override
		public String toString() {
			return "CurrentModeUpdate[sessionUpdate=" + sessionUpdate + ", currentModeId=" + currentModeId + ", meta=" + meta + "]";
		}
	}

	/**
	 * Usage update - context window and cost update for the session (UNSTABLE)
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public static final class UsageUpdate implements SessionUpdate {
		private final @JsonProperty("sessionUpdate") String sessionUpdate;
		private final @JsonProperty("used") Long used;
		private final @JsonProperty("size") Long size;
		private final @JsonProperty("cost") Cost cost;
		private final @JsonProperty("_meta") Map<String, Object> meta;

		public UsageUpdate(@JsonProperty("sessionUpdate") String sessionUpdate, @JsonProperty("used") Long used, @JsonProperty("size") Long size, @JsonProperty("cost") Cost cost, @JsonProperty("_meta") Map<String, Object> meta) {
			this.sessionUpdate = sessionUpdate;
			this.used = used;
			this.size = size;
			this.cost = cost;
			this.meta = meta;
		}

		public UsageUpdate(String sessionUpdate, Long used, Long size) {
			this(sessionUpdate, used, size, null, null);
		}

		public String sessionUpdate() { return sessionUpdate; }
		public Long used() { return used; }
		public Long size() { return size; }
		public Cost cost() { return cost; }
		public Map<String, Object> meta() { return meta; }

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null || getClass() != o.getClass()) return false;
			UsageUpdate that = (UsageUpdate) o;
			return Objects.equals(sessionUpdate, that.sessionUpdate) && Objects.equals(used, that.used) && Objects.equals(size, that.size) && Objects.equals(cost, that.cost) && Objects.equals(meta, that.meta);
		}

		@Override
		public int hashCode() {
			return Objects.hash(sessionUpdate, used, size, cost, meta);
		}

		@Override
		public String toString() {
			return "UsageUpdate[sessionUpdate=" + sessionUpdate + ", used=" + used + ", size=" + size + ", cost=" + cost + ", meta=" + meta + "]";
		}
	}

	/**
	 * Cost information for a session (UNSTABLE)
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public static final class Cost {
		private final @JsonProperty("amount") Double amount;
		private final @JsonProperty("currency") String currency;

		public Cost(@JsonProperty("amount") Double amount, @JsonProperty("currency") String currency) {
			this.amount = amount;
			this.currency = currency;
		}

		public Double amount() { return amount; }
		public String currency() { return currency; }

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null || getClass() != o.getClass()) return false;
			Cost that = (Cost) o;
			return Objects.equals(amount, that.amount) && Objects.equals(currency, that.currency);
		}

		@Override
		public int hashCode() {
			return Objects.hash(amount, currency);
		}

		@Override
		public String toString() {
			return "Cost[amount=" + amount + ", currency=" + currency + "]";
		}
	}

	// ---------------------------
	// Tool Call Types
	// ---------------------------

	/**
	 * Tool call content
	 */
	@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
	@JsonSubTypes({ @JsonSubTypes.Type(value = ToolCallContentBlock.class, name = "content"),
			@JsonSubTypes.Type(value = ToolCallDiff.class, name = "diff"),
			@JsonSubTypes.Type(value = ToolCallTerminal.class, name = "terminal") })
	public interface ToolCallContent {

	}

	/**
	 * Tool call content block
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public static final class ToolCallContentBlock implements ToolCallContent {
		private final @JsonProperty("type") String type;
		private final @JsonProperty("content") ContentBlock content;

		public ToolCallContentBlock(@JsonProperty("type") String type, @JsonProperty("content") ContentBlock content) {
			this.type = type;
			this.content = content;
		}

		public String type() { return type; }
		public ContentBlock content() { return content; }

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null || getClass() != o.getClass()) return false;
			ToolCallContentBlock that = (ToolCallContentBlock) o;
			return Objects.equals(type, that.type) && Objects.equals(content, that.content);
		}

		@Override
		public int hashCode() {
			return Objects.hash(type, content);
		}

		@Override
		public String toString() {
			return "ToolCallContentBlock[type=" + type + ", content=" + content + "]";
		}
	}

	/**
	 * Tool call diff
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public static final class ToolCallDiff implements ToolCallContent {
		private final @JsonProperty("type") String type;
		private final @JsonProperty("path") String path;
		private final @JsonProperty("oldText") String oldText;
		private final @JsonProperty("newText") String newText;

		public ToolCallDiff(@JsonProperty("type") String type, @JsonProperty("path") String path, @JsonProperty("oldText") String oldText, @JsonProperty("newText") String newText) {
			this.type = type;
			this.path = path;
			this.oldText = oldText;
			this.newText = newText;
		}

		public String type() { return type; }
		public String path() { return path; }
		public String oldText() { return oldText; }
		public String newText() { return newText; }

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null || getClass() != o.getClass()) return false;
			ToolCallDiff that = (ToolCallDiff) o;
			return Objects.equals(type, that.type) && Objects.equals(path, that.path) && Objects.equals(oldText, that.oldText) && Objects.equals(newText, that.newText);
		}

		@Override
		public int hashCode() {
			return Objects.hash(type, path, oldText, newText);
		}

		@Override
		public String toString() {
			return "ToolCallDiff[type=" + type + ", path=" + path + ", oldText=" + oldText + ", newText=" + newText + "]";
		}
	}

	/**
	 * Tool call terminal
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public static final class ToolCallTerminal implements ToolCallContent {
		private final @JsonProperty("type") String type;
		private final @JsonProperty("terminalId") String terminalId;

		public ToolCallTerminal(@JsonProperty("type") String type, @JsonProperty("terminalId") String terminalId) {
			this.type = type;
			this.terminalId = terminalId;
		}

		public String type() { return type; }
		public String terminalId() { return terminalId; }

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null || getClass() != o.getClass()) return false;
			ToolCallTerminal that = (ToolCallTerminal) o;
			return Objects.equals(type, that.type) && Objects.equals(terminalId, that.terminalId);
		}

		@Override
		public int hashCode() {
			return Objects.hash(type, terminalId);
		}

		@Override
		public String toString() {
			return "ToolCallTerminal[type=" + type + ", terminalId=" + terminalId + "]";
		}
	}

	/**
	 * Tool call location
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public static final class ToolCallLocation {
		private final @JsonProperty("path") String path;
		private final @JsonProperty("line") Integer line;

		public ToolCallLocation(@JsonProperty("path") String path, @JsonProperty("line") Integer line) {
			this.path = path;
			this.line = line;
		}

		public String path() { return path; }
		public Integer line() { return line; }

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null || getClass() != o.getClass()) return false;
			ToolCallLocation that = (ToolCallLocation) o;
			return Objects.equals(path, that.path) && Objects.equals(line, that.line);
		}

		@Override
		public int hashCode() {
			return Objects.hash(path, line);
		}

		@Override
		public String toString() {
			return "ToolCallLocation[path=" + path + ", line=" + line + "]";
		}
	}

	// ---------------------------
	// Enums
	// ---------------------------

	public enum StopReason {

		@JsonProperty("end_turn")
		END_TURN, @JsonProperty("max_tokens")
		MAX_TOKENS, @JsonProperty("max_turn_requests")
		MAX_TURN_REQUESTS, @JsonProperty("refusal")
		REFUSAL, @JsonProperty("cancelled")
		CANCELLED

	}

	public enum ToolCallStatus {

		@JsonProperty("pending")
		PENDING, @JsonProperty("in_progress")
		IN_PROGRESS, @JsonProperty("completed")
		COMPLETED, @JsonProperty("failed")
		FAILED

	}

	public enum ToolKind {

		@JsonProperty("read")
		READ, @JsonProperty("edit")
		EDIT, @JsonProperty("delete")
		DELETE, @JsonProperty("move")
		MOVE, @JsonProperty("search")
		SEARCH, @JsonProperty("execute")
		EXECUTE, @JsonProperty("think")
		THINK, @JsonProperty("fetch")
		FETCH, @JsonProperty("switch_mode")
		SWITCH_MODE, @JsonProperty("other")
		OTHER

	}

	public enum Role {

		@JsonProperty("assistant")
		ASSISTANT, @JsonProperty("user")
		USER

	}

	public enum PermissionOptionKind {

		@JsonProperty("allow_once")
		ALLOW_ONCE, @JsonProperty("allow_always")
		ALLOW_ALWAYS, @JsonProperty("reject_once")
		REJECT_ONCE, @JsonProperty("reject_always")
		REJECT_ALWAYS

	}

	public enum PlanEntryStatus {

		@JsonProperty("pending")
		PENDING, @JsonProperty("in_progress")
		IN_PROGRESS, @JsonProperty("completed")
		COMPLETED

	}

	public enum PlanEntryPriority {

		@JsonProperty("high")
		HIGH, @JsonProperty("medium")
		MEDIUM, @JsonProperty("low")
		LOW

	}

	// ---------------------------
	// Supporting Types
	// ---------------------------

	/**
	 * Metadata about an implementation (client or agent).
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public static final class Implementation {
		private final @JsonProperty("name") String name;
		private final @JsonProperty("version") String version;
		private final @JsonProperty("title") String title;

		public Implementation(@JsonProperty("name") String name, @JsonProperty("version") String version, @JsonProperty("title") String title) {
			this.name = name;
			this.version = version;
			this.title = title;
		}

		public Implementation(String name, String version) {
			this(name, version, null);
		}

		public String name() { return name; }
		public String version() { return version; }
		public String title() { return title; }

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null || getClass() != o.getClass()) return false;
			Implementation that = (Implementation) o;
			return Objects.equals(name, that.name) && Objects.equals(version, that.version) && Objects.equals(title, that.title);
		}

		@Override
		public int hashCode() {
			return Objects.hash(name, version, title);
		}

		@Override
		public String toString() {
			return "Implementation[name=" + name + ", version=" + version + ", title=" + title + "]";
		}
	}

	/**
	 * MCP server configuration.
	 * <p>
	 * Per the ACP spec:
	 * <ul>
	 * <li>Stdio transport: NO type field (default)</li>
	 * <li>HTTP transport: type="http"</li>
	 * <li>SSE transport: type="sse"</li>
	 * </ul>
	 * </p>
	 * <p>
	 * Uses {@code EXISTING_PROPERTY} so that:
	 * <ul>
	 * <li>McpServerStdio (no type method) serializes WITHOUT type field</li>
	 * <li>McpServerHttp/Sse (with type method) serialize WITH type field</li>
	 * </ul>
	 * </p>
	 */
	@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type", include = JsonTypeInfo.As.EXISTING_PROPERTY,
			defaultImpl = McpServerStdio.class)
	@JsonSubTypes({ @JsonSubTypes.Type(value = McpServerHttp.class, name = "http"),
			@JsonSubTypes.Type(value = McpServerSse.class, name = "sse") })
	public interface McpServer {

	}

	/**
	 * STDIO MCP server (default transport, no type field in JSON).
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public static final class McpServerStdio implements McpServer {
		private final @JsonProperty("name") String name;
		private final @JsonProperty("command") String command;
		private final @JsonProperty("args") List<String> args;
		private final @JsonProperty("env") List<EnvVariable> env;

		public McpServerStdio(@JsonProperty("name") String name, @JsonProperty("command") String command, @JsonProperty("args") List<String> args, @JsonProperty("env") List<EnvVariable> env) {
			this.name = name;
			this.command = command;
			this.args = args;
			this.env = env;
		}

		public String name() { return name; }
		public String command() { return command; }
		public List<String> args() { return args; }
		public List<EnvVariable> env() { return env; }

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null || getClass() != o.getClass()) return false;
			McpServerStdio that = (McpServerStdio) o;
			return Objects.equals(name, that.name) && Objects.equals(command, that.command) && Objects.equals(args, that.args) && Objects.equals(env, that.env);
		}

		@Override
		public int hashCode() {
			return Objects.hash(name, command, args, env);
		}

		@Override
		public String toString() {
			return "McpServerStdio[name=" + name + ", command=" + command + ", args=" + args + ", env=" + env + "]";
		}
	}

	/**
	 * HTTP MCP server.
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public static final class McpServerHttp implements McpServer {
		private final @JsonProperty("name") String name;
		private final @JsonProperty("url") String url;
		private final @JsonProperty("headers") List<HttpHeader> headers;

		public McpServerHttp(@JsonProperty("name") String name, @JsonProperty("url") String url, @JsonProperty("headers") List<HttpHeader> headers) {
			this.name = name;
			this.url = url;
			this.headers = headers;
		}


		/**
		 * Returns the transport type identifier.
		 */
		@JsonProperty("type")
		public String type() {
			return "http";
		}

		public String name() { return name; }
		public String url() { return url; }
		public List<HttpHeader> headers() { return headers; }

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null || getClass() != o.getClass()) return false;
			McpServerHttp that = (McpServerHttp) o;
			return Objects.equals(name, that.name) && Objects.equals(url, that.url) && Objects.equals(headers, that.headers);
		}

		@Override
		public int hashCode() {
			return Objects.hash(name, url, headers);
		}

		@Override
		public String toString() {
			return "McpServerHttp[name=" + name + ", url=" + url + ", headers=" + headers + "]";
		}
	}

	/**
	 * SSE MCP server.
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public static final class McpServerSse implements McpServer {
		private final @JsonProperty("name") String name;
		private final @JsonProperty("url") String url;
		private final @JsonProperty("headers") List<HttpHeader> headers;

		public McpServerSse(@JsonProperty("name") String name, @JsonProperty("url") String url, @JsonProperty("headers") List<HttpHeader> headers) {
			this.name = name;
			this.url = url;
			this.headers = headers;
		}


		/**
		 * Returns the transport type identifier.
		 */
		@JsonProperty("type")
		public String type() {
			return "sse";
		}

		public String name() { return name; }
		public String url() { return url; }
		public List<HttpHeader> headers() { return headers; }

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null || getClass() != o.getClass()) return false;
			McpServerSse that = (McpServerSse) o;
			return Objects.equals(name, that.name) && Objects.equals(url, that.url) && Objects.equals(headers, that.headers);
		}

		@Override
		public int hashCode() {
			return Objects.hash(name, url, headers);
		}

		@Override
		public String toString() {
			return "McpServerSse[name=" + name + ", url=" + url + ", headers=" + headers + "]";
		}
	}

	/**
	 * Environment variable
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public static final class EnvVariable {
		private final @JsonProperty("name") String name;
		private final @JsonProperty("value") String value;

		public EnvVariable(@JsonProperty("name") String name, @JsonProperty("value") String value) {
			this.name = name;
			this.value = value;
		}

		public String name() { return name; }
		public String value() { return value; }

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null || getClass() != o.getClass()) return false;
			EnvVariable that = (EnvVariable) o;
			return Objects.equals(name, that.name) && Objects.equals(value, that.value);
		}

		@Override
		public int hashCode() {
			return Objects.hash(name, value);
		}

		@Override
		public String toString() {
			return "EnvVariable[name=" + name + ", value=" + value + "]";
		}
	}

	/**
	 * HTTP header
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public static final class HttpHeader {
		private final @JsonProperty("name") String name;
		private final @JsonProperty("value") String value;

		public HttpHeader(@JsonProperty("name") String name, @JsonProperty("value") String value) {
			this.name = name;
			this.value = value;
		}

		public String name() { return name; }
		public String value() { return value; }

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null || getClass() != o.getClass()) return false;
			HttpHeader that = (HttpHeader) o;
			return Objects.equals(name, that.name) && Objects.equals(value, that.value);
		}

		@Override
		public int hashCode() {
			return Objects.hash(name, value);
		}

		@Override
		public String toString() {
			return "HttpHeader[name=" + name + ", value=" + value + "]";
		}
	}

	/**
	 * Terminal exit status
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public static final class TerminalExitStatus {
		private final @JsonProperty("exitCode") Integer exitCode;
		private final @JsonProperty("signal") String signal;

		public TerminalExitStatus(@JsonProperty("exitCode") Integer exitCode, @JsonProperty("signal") String signal) {
			this.exitCode = exitCode;
			this.signal = signal;
		}

		public Integer exitCode() { return exitCode; }
		public String signal() { return signal; }

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null || getClass() != o.getClass()) return false;
			TerminalExitStatus that = (TerminalExitStatus) o;
			return Objects.equals(exitCode, that.exitCode) && Objects.equals(signal, that.signal);
		}

		@Override
		public int hashCode() {
			return Objects.hash(exitCode, signal);
		}

		@Override
		public String toString() {
			return "TerminalExitStatus[exitCode=" + exitCode + ", signal=" + signal + "]";
		}
	}

	/**
	 * Authentication method
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public static final class AuthMethod {
		private final @JsonProperty("id") String id;
		private final @JsonProperty("name") String name;
		private final @JsonProperty("description") String description;

		public AuthMethod(@JsonProperty("id") String id, @JsonProperty("name") String name, @JsonProperty("description") String description) {
			this.id = id;
			this.name = name;
			this.description = description;
		}

		public String id() { return id; }
		public String name() { return name; }
		public String description() { return description; }

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null || getClass() != o.getClass()) return false;
			AuthMethod that = (AuthMethod) o;
			return Objects.equals(id, that.id) && Objects.equals(name, that.name) && Objects.equals(description, that.description);
		}

		@Override
		public int hashCode() {
			return Objects.hash(id, name, description);
		}

		@Override
		public String toString() {
			return "AuthMethod[id=" + id + ", name=" + name + ", description=" + description + "]";
		}
	}

	/**
	 * Permission option
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public static final class PermissionOption {
		private final @JsonProperty("optionId") String optionId;
		private final @JsonProperty("name") String name;
		private final @JsonProperty("kind") PermissionOptionKind kind;

		public PermissionOption(@JsonProperty("optionId") String optionId, @JsonProperty("name") String name, @JsonProperty("kind") PermissionOptionKind kind) {
			this.optionId = optionId;
			this.name = name;
			this.kind = kind;
		}

		public String optionId() { return optionId; }
		public String name() { return name; }
		public PermissionOptionKind kind() { return kind; }

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null || getClass() != o.getClass()) return false;
			PermissionOption that = (PermissionOption) o;
			return Objects.equals(optionId, that.optionId) && Objects.equals(name, that.name) && Objects.equals(kind, that.kind);
		}

		@Override
		public int hashCode() {
			return Objects.hash(optionId, name, kind);
		}

		@Override
		public String toString() {
			return "PermissionOption[optionId=" + optionId + ", name=" + name + ", kind=" + kind + "]";
		}
	}

	/**
	 * Request permission outcome
	 */
	@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "outcome")
	@JsonSubTypes({ @JsonSubTypes.Type(value = PermissionCancelled.class, name = "cancelled"),
			@JsonSubTypes.Type(value = PermissionSelected.class, name = "selected") })
	public interface RequestPermissionOutcome {

	}

	/**
	 * Permission cancelled
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public static final class PermissionCancelled implements RequestPermissionOutcome {
		private final @JsonProperty("outcome") String outcome;

		public PermissionCancelled(@JsonProperty("outcome") String outcome) {
			this.outcome = outcome;
		}

		public PermissionCancelled() {
			this("cancelled");
		}

		public String outcome() { return outcome; }

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null || getClass() != o.getClass()) return false;
			PermissionCancelled that = (PermissionCancelled) o;
			return Objects.equals(outcome, that.outcome);
		}

		@Override
		public int hashCode() {
			return Objects.hash(outcome);
		}

		@Override
		public String toString() {
			return "PermissionCancelled[outcome=" + outcome + "]";
		}
	}

	/**
	 * Permission selected
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public static final class PermissionSelected implements RequestPermissionOutcome {
		private final @JsonProperty("outcome") String outcome;
		private final @JsonProperty("optionId") String optionId;

		public PermissionSelected(@JsonProperty("outcome") String outcome, @JsonProperty("optionId") String optionId) {
			this.outcome = outcome;
			this.optionId = optionId;
		}

		public PermissionSelected(String optionId) {
			this("selected", optionId);
		}

		public String outcome() { return outcome; }
		public String optionId() { return optionId; }

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null || getClass() != o.getClass()) return false;
			PermissionSelected that = (PermissionSelected) o;
			return Objects.equals(outcome, that.outcome) && Objects.equals(optionId, that.optionId);
		}

		@Override
		public int hashCode() {
			return Objects.hash(outcome, optionId);
		}

		@Override
		public String toString() {
			return "PermissionSelected[outcome=" + outcome + ", optionId=" + optionId + "]";
		}
	}

	/**
	 * Plan entry
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public static final class PlanEntry {
		private final @JsonProperty("content") String content;
		private final @JsonProperty("priority") PlanEntryPriority priority;
		private final @JsonProperty("status") PlanEntryStatus status;

		public PlanEntry(@JsonProperty("content") String content, @JsonProperty("priority") PlanEntryPriority priority, @JsonProperty("status") PlanEntryStatus status) {
			this.content = content;
			this.priority = priority;
			this.status = status;
		}

		public String content() { return content; }
		public PlanEntryPriority priority() { return priority; }
		public PlanEntryStatus status() { return status; }

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null || getClass() != o.getClass()) return false;
			PlanEntry that = (PlanEntry) o;
			return Objects.equals(content, that.content) && Objects.equals(priority, that.priority) && Objects.equals(status, that.status);
		}

		@Override
		public int hashCode() {
			return Objects.hash(content, priority, status);
		}

		@Override
		public String toString() {
			return "PlanEntry[content=" + content + ", priority=" + priority + ", status=" + status + "]";
		}
	}

	/**
	 * Available command
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public static final class AvailableCommand {
		private final @JsonProperty("name") String name;
		private final @JsonProperty("description") String description;
		private final @JsonProperty("input") AvailableCommandInput input;

		public AvailableCommand(@JsonProperty("name") String name, @JsonProperty("description") String description, @JsonProperty("input") AvailableCommandInput input) {
			this.name = name;
			this.description = description;
			this.input = input;
		}

		public String name() { return name; }
		public String description() { return description; }
		public AvailableCommandInput input() { return input; }

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null || getClass() != o.getClass()) return false;
			AvailableCommand that = (AvailableCommand) o;
			return Objects.equals(name, that.name) && Objects.equals(description, that.description) && Objects.equals(input, that.input);
		}

		@Override
		public int hashCode() {
			return Objects.hash(name, description, input);
		}

		@Override
		public String toString() {
			return "AvailableCommand[name=" + name + ", description=" + description + ", input=" + input + "]";
		}
	}

	/**
	 * Available command input
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public static final class AvailableCommandInput {
		private final @JsonProperty("hint") String hint;

		public AvailableCommandInput(@JsonProperty("hint") String hint) {
			this.hint = hint;
		}

		public String hint() { return hint; }

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null || getClass() != o.getClass()) return false;
			AvailableCommandInput that = (AvailableCommandInput) o;
			return Objects.equals(hint, that.hint);
		}

		@Override
		public int hashCode() {
			return Objects.hash(hint);
		}

		@Override
		public String toString() {
			return "AvailableCommandInput[hint=" + hint + "]";
		}
	}

}
