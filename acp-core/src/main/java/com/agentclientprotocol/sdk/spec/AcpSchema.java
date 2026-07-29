/*
 * Copyright 2025-2025 the original author or authors.
 */

package com.agentclientprotocol.sdk.spec;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.agentclientprotocol.sdk.annotation.UnstableAcpApi;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.agentclientprotocol.sdk.json.AcpJsonMapper;
import com.agentclientprotocol.sdk.json.TypeRef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
	public static JSONRPCMessage deserializeJsonRpcMessage(AcpJsonMapper jsonMapper, String jsonText)
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

	public static final String METHOD_LOGOUT = "logout";

	public static final String METHOD_SESSION_NEW = "session/new";

	public static final String METHOD_SESSION_LOAD = "session/load";

	public static final String METHOD_SESSION_PROMPT = "session/prompt";

	public static final String METHOD_SESSION_SET_MODE = "session/set_mode";

	/**
	 * @deprecated The {@code session/set_model} method was removed from the ACP spec
	 * (June 2026, v0.13.5). Expose model selection through {@code session/set_config_option}
	 * with a config option whose {@code category} is {@code "model"} instead. Slated for
	 * removal in a future release.
	 */
	@Deprecated
	@UnstableAcpApi
	public static final String METHOD_SESSION_SET_MODEL = "session/set_model";

	public static final String METHOD_SESSION_CANCEL = "session/cancel";

	public static final String METHOD_SESSION_LIST = "session/list";

	public static final String METHOD_SESSION_CLOSE = "session/close";

	public static final String METHOD_SESSION_DELETE = "session/delete";

	public static final String METHOD_SESSION_RESUME = "session/resume";

	public static final String METHOD_SESSION_FORK = "session/fork";

	public static final String METHOD_SESSION_SET_CONFIG_OPTION = "session/set_config_option";

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

	public static final String METHOD_ELICITATION_CREATE = "elicitation/create";

	public static final String METHOD_ELICITATION_COMPLETE = "elicitation/complete";

	// Provider configuration (UNSTABLE)
	public static final String METHOD_PROVIDERS_LIST = "providers/list";

	public static final String METHOD_PROVIDERS_SET = "providers/set";

	public static final String METHOD_PROVIDERS_DISABLE = "providers/disable";

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

		public String jsonrpc() { return jsonrpc; }
		public Object id() { return id; }
		public String method() { return method; }
		public Object params() { return params; }

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null || getClass() != o.getClass()) return false;
			JSONRPCRequest that = (JSONRPCRequest) o;
			return Objects.equals(jsonrpc, that.jsonrpc)
							&& Objects.equals(id, that.id)
							&& Objects.equals(method, that.method)
							&& Objects.equals(params, that.params);
		}

		@Override
		public int hashCode() {
			return Objects.hash(jsonrpc, id, method, params);
		}

		@Override
		public String toString() {
			return "JSONRPCRequest[" + "jsonrpc=" + jsonrpc + ", id=" + id + ", method=" + method + ", params=" + params + "]";
		}

		public JSONRPCRequest(String method, Object id, Object params) {
			this(JSONRPC_VERSION, id, method, params);
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

		public String jsonrpc() { return jsonrpc; }
		public String method() { return method; }
		public Object params() { return params; }

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null || getClass() != o.getClass()) return false;
			JSONRPCNotification that = (JSONRPCNotification) o;
			return Objects.equals(jsonrpc, that.jsonrpc)
							&& Objects.equals(method, that.method)
							&& Objects.equals(params, that.params);
		}

		@Override
		public int hashCode() {
			return Objects.hash(jsonrpc, method, params);
		}

		@Override
		public String toString() {
			return "JSONRPCNotification[" + "jsonrpc=" + jsonrpc + ", method=" + method + ", params=" + params + "]";
		}

		public JSONRPCNotification(String method, Object params) {
			this(JSONRPC_VERSION, method, params);
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
			return Objects.equals(jsonrpc, that.jsonrpc)
							&& Objects.equals(id, that.id)
							&& Objects.equals(result, that.result)
							&& Objects.equals(error, that.error);
		}

		@Override
		public int hashCode() {
			return Objects.hash(jsonrpc, id, result, error);
		}

		@Override
		public String toString() {
			return "JSONRPCResponse[" + "jsonrpc=" + jsonrpc + ", id=" + id + ", result=" + result + ", error=" + error + "]";
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
			return code == that.code
							&& Objects.equals(message, that.message)
							&& Objects.equals(data, that.data);
		}

		@Override
		public int hashCode() {
			return Objects.hash(code, message, data);
		}

		@Override
		public String toString() {
			return "JSONRPCError[" + "code=" + code + ", message=" + message + ", data=" + data + "]";
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

		public Integer protocolVersion() { return protocolVersion; }
		public ClientCapabilities clientCapabilities() { return clientCapabilities; }
		public Implementation clientInfo() { return clientInfo; }
		public Map<String, Object> meta() { return meta; }

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null || getClass() != o.getClass()) return false;
			InitializeRequest that = (InitializeRequest) o;
			return Objects.equals(protocolVersion, that.protocolVersion)
							&& Objects.equals(clientCapabilities, that.clientCapabilities)
							&& Objects.equals(clientInfo, that.clientInfo)
							&& Objects.equals(meta, that.meta);
		}

		@Override
		public int hashCode() {
			return Objects.hash(protocolVersion, clientCapabilities, clientInfo, meta);
		}

		@Override
		public String toString() {
			return "InitializeRequest[" + "protocolVersion=" + protocolVersion + ", clientCapabilities=" + clientCapabilities + ", clientInfo=" + clientInfo + ", meta=" + meta + "]";
		}

		public InitializeRequest(Integer protocolVersion, ClientCapabilities clientCapabilities) {
			this(protocolVersion, clientCapabilities, null, null);
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
			return Objects.equals(protocolVersion, that.protocolVersion)
							&& Objects.equals(agentCapabilities, that.agentCapabilities)
							&& Objects.equals(authMethods, that.authMethods)
							&& Objects.equals(agentInfo, that.agentInfo)
							&& Objects.equals(meta, that.meta);
		}

		@Override
		public int hashCode() {
			return Objects.hash(protocolVersion, agentCapabilities, authMethods, agentInfo, meta);
		}

		@Override
		public String toString() {
			return "InitializeResponse[" + "protocolVersion=" + protocolVersion + ", agentCapabilities=" + agentCapabilities + ", authMethods=" + authMethods + ", agentInfo=" + agentInfo + ", meta=" + meta + "]";
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
			return "AuthenticateRequest[" + "methodId=" + methodId + "]";
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
	 * Logout request - clears stored credentials, terminating the current
	 * authenticated session.
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public static final class LogoutRequest {
		private final @JsonProperty("_meta") Map<String, Object> meta;

		public LogoutRequest(@JsonProperty("_meta") Map<String, Object> meta) {
			this.meta = meta;
		}

		public Map<String, Object> meta() { return meta; }

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null || getClass() != o.getClass()) return false;
			LogoutRequest that = (LogoutRequest) o;
			return Objects.equals(meta, that.meta);
		}

		@Override
		public int hashCode() {
			return Objects.hash(meta);
		}

		@Override
		public String toString() {
			return "LogoutRequest[" + "meta=" + meta + "]";
		}

		public LogoutRequest() {
			this(null);
		}
	}

	/**
	 * Logout response
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public static final class LogoutResponse {
		private final @JsonProperty("_meta") Map<String, Object> meta;

		public LogoutResponse(@JsonProperty("_meta") Map<String, Object> meta) {
			this.meta = meta;
		}

		public Map<String, Object> meta() { return meta; }

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null || getClass() != o.getClass()) return false;
			LogoutResponse that = (LogoutResponse) o;
			return Objects.equals(meta, that.meta);
		}

		@Override
		public int hashCode() {
			return Objects.hash(meta);
		}

		@Override
		public String toString() {
			return "LogoutResponse[" + "meta=" + meta + "]";
		}

		public LogoutResponse() {
			this(null);
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
		private final @JsonProperty("additionalDirectories") List<String> additionalDirectories;
		private final @JsonProperty("_meta") Map<String, Object> meta;

		public NewSessionRequest(@JsonProperty("cwd") String cwd, @JsonProperty("mcpServers") List<McpServer> mcpServers, @JsonProperty("additionalDirectories") List<String> additionalDirectories, @JsonProperty("_meta") Map<String, Object> meta) {
			this.cwd = cwd;
			this.mcpServers = mcpServers;
			this.additionalDirectories = additionalDirectories;
			this.meta = meta;
		}

		public String cwd() { return cwd; }
		public List<McpServer> mcpServers() { return mcpServers; }
		public List<String> additionalDirectories() { return additionalDirectories; }
		public Map<String, Object> meta() { return meta; }

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null || getClass() != o.getClass()) return false;
			NewSessionRequest that = (NewSessionRequest) o;
			return Objects.equals(cwd, that.cwd)
							&& Objects.equals(mcpServers, that.mcpServers)
							&& Objects.equals(additionalDirectories, that.additionalDirectories)
							&& Objects.equals(meta, that.meta);
		}

		@Override
		public int hashCode() {
			return Objects.hash(cwd, mcpServers, additionalDirectories, meta);
		}

		@Override
		public String toString() {
			return "NewSessionRequest[" + "cwd=" + cwd + ", mcpServers=" + mcpServers + ", additionalDirectories=" + additionalDirectories + ", meta=" + meta + "]";
		}

		public NewSessionRequest(String cwd, List<McpServer> mcpServers) {
			this(cwd, mcpServers, null, null);
		}

		public NewSessionRequest(String cwd, List<McpServer> mcpServers, List<String> additionalDirectories) {
			this(cwd, mcpServers, additionalDirectories, null);
		}
	}

	/**
	 * Create new session response
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	@SuppressWarnings("removal") // 'models' references the deprecated-for-removal SessionModelState
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

		public String sessionId() { return sessionId; }
		public SessionModeState modes() { return modes; }
		public SessionModelState models() { return models; }
		public Map<String, Object> meta() { return meta; }

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null || getClass() != o.getClass()) return false;
			NewSessionResponse that = (NewSessionResponse) o;
			return Objects.equals(sessionId, that.sessionId)
							&& Objects.equals(modes, that.modes)
							&& Objects.equals(models, that.models)
							&& Objects.equals(meta, that.meta);
		}

		@Override
		public int hashCode() {
			return Objects.hash(sessionId, modes, models, meta);
		}

		@Override
		public String toString() {
			return "NewSessionResponse[" + "sessionId=" + sessionId + ", modes=" + modes + ", models=" + models + ", meta=" + meta + "]";
		}

		public NewSessionResponse(String sessionId, SessionModeState modes, SessionModelState models) {
			this(sessionId, modes, models, null);
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
		private final @JsonProperty("additionalDirectories") List<String> additionalDirectories;
		private final @JsonProperty("_meta") Map<String, Object> meta;

		public LoadSessionRequest(@JsonProperty("sessionId") String sessionId, @JsonProperty("cwd") String cwd, @JsonProperty("mcpServers") List<McpServer> mcpServers, @JsonProperty("additionalDirectories") List<String> additionalDirectories, @JsonProperty("_meta") Map<String, Object> meta) {
			this.sessionId = sessionId;
			this.cwd = cwd;
			this.mcpServers = mcpServers;
			this.additionalDirectories = additionalDirectories;
			this.meta = meta;
		}

		public String sessionId() { return sessionId; }
		public String cwd() { return cwd; }
		public List<McpServer> mcpServers() { return mcpServers; }
		public List<String> additionalDirectories() { return additionalDirectories; }
		public Map<String, Object> meta() { return meta; }

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null || getClass() != o.getClass()) return false;
			LoadSessionRequest that = (LoadSessionRequest) o;
			return Objects.equals(sessionId, that.sessionId)
							&& Objects.equals(cwd, that.cwd)
							&& Objects.equals(mcpServers, that.mcpServers)
							&& Objects.equals(additionalDirectories, that.additionalDirectories)
							&& Objects.equals(meta, that.meta);
		}

		@Override
		public int hashCode() {
			return Objects.hash(sessionId, cwd, mcpServers, additionalDirectories, meta);
		}

		@Override
		public String toString() {
			return "LoadSessionRequest[" + "sessionId=" + sessionId + ", cwd=" + cwd + ", mcpServers=" + mcpServers + ", additionalDirectories=" + additionalDirectories + ", meta=" + meta + "]";
		}

		public LoadSessionRequest(String sessionId, String cwd, List<McpServer> mcpServers) {
			this(sessionId, cwd, mcpServers, null, null);
		}

		public LoadSessionRequest(String sessionId, String cwd, List<McpServer> mcpServers,
				List<String> additionalDirectories) {
			this(sessionId, cwd, mcpServers, additionalDirectories, null);
		}
	}

	/**
	 * Load session response
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	@SuppressWarnings("removal") // 'models' references the deprecated-for-removal SessionModelState
	public static final class LoadSessionResponse {
		private final @JsonProperty("modes") SessionModeState modes;
		private final @JsonProperty("models") SessionModelState models;
		private final @JsonProperty("_meta") Map<String, Object> meta;

		public LoadSessionResponse(@JsonProperty("modes") SessionModeState modes, @JsonProperty("models") SessionModelState models, @JsonProperty("_meta") Map<String, Object> meta) {
			this.modes = modes;
			this.models = models;
			this.meta = meta;
		}

		public SessionModeState modes() { return modes; }
		public SessionModelState models() { return models; }
		public Map<String, Object> meta() { return meta; }

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null || getClass() != o.getClass()) return false;
			LoadSessionResponse that = (LoadSessionResponse) o;
			return Objects.equals(modes, that.modes)
							&& Objects.equals(models, that.models)
							&& Objects.equals(meta, that.meta);
		}

		@Override
		public int hashCode() {
			return Objects.hash(modes, models, meta);
		}

		@Override
		public String toString() {
			return "LoadSessionResponse[" + "modes=" + modes + ", models=" + models + ", meta=" + meta + "]";
		}

		public LoadSessionResponse(SessionModeState modes, SessionModelState models) {
			this(modes, models, null);
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

		public String sessionId() { return sessionId; }
		public List<ContentBlock> prompt() { return prompt; }
		public Map<String, Object> meta() { return meta; }

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null || getClass() != o.getClass()) return false;
			PromptRequest that = (PromptRequest) o;
			return Objects.equals(sessionId, that.sessionId)
							&& Objects.equals(prompt, that.prompt)
							&& Objects.equals(meta, that.meta);
		}

		@Override
		public int hashCode() {
			return Objects.hash(sessionId, prompt, meta);
		}

		@Override
		public String toString() {
			return "PromptRequest[" + "sessionId=" + sessionId + ", prompt=" + prompt + ", meta=" + meta + "]";
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

		public StopReason stopReason() { return stopReason; }
		public Map<String, Object> meta() { return meta; }

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null || getClass() != o.getClass()) return false;
			PromptResponse that = (PromptResponse) o;
			return Objects.equals(stopReason, that.stopReason)
							&& Objects.equals(meta, that.meta);
		}

		@Override
		public int hashCode() {
			return Objects.hash(stopReason, meta);
		}

		@Override
		public String toString() {
			return "PromptResponse[" + "stopReason=" + stopReason + ", meta=" + meta + "]";
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
			return Objects.equals(sessionId, that.sessionId)
							&& Objects.equals(modeId, that.modeId);
		}

		@Override
		public int hashCode() {
			return Objects.hash(sessionId, modeId);
		}

		@Override
		public String toString() {
			return "SetSessionModeRequest[" + "sessionId=" + sessionId + ", modeId=" + modeId + "]";
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
	 * Set session model request.
	 *
	 * @deprecated The {@code session/set_model} method was removed from the ACP spec
	 * (June 2026, v0.13.5). Use {@code session/set_config_option} with a {@code "model"}
	 * category config option instead. Slated for removal in a future release.
	 */
	@Deprecated
	@UnstableAcpApi
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
			return Objects.equals(sessionId, that.sessionId)
							&& Objects.equals(modelId, that.modelId);
		}

		@Override
		public int hashCode() {
			return Objects.hash(sessionId, modelId);
		}

		@Override
		public String toString() {
			return "SetSessionModelRequest[" + "sessionId=" + sessionId + ", modelId=" + modelId + "]";
		}
	}

	/**
	 * Set session model response.
	 *
	 * @deprecated See {@link SetSessionModelRequest}. Slated for removal.
	 */
	@Deprecated
	@UnstableAcpApi
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public static final class SetSessionModelResponse {

		public SetSessionModelResponse() {
		}


		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null || getClass() != o.getClass()) return false;
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
			return "CancelNotification[" + "sessionId=" + sessionId + "]";
		}
	}

	/**
	 * List sessions request - lists all sessions, optionally filtered by working directory
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public static final class ListSessionsRequest {
		private final @JsonProperty("cwd") String cwd;
		private final @JsonProperty("cursor") String cursor;
		private final @JsonProperty("_meta") Map<String, Object> meta;

		public ListSessionsRequest(@JsonProperty("cwd") String cwd, @JsonProperty("cursor") String cursor, @JsonProperty("_meta") Map<String, Object> meta) {
			this.cwd = cwd;
			this.cursor = cursor;
			this.meta = meta;
		}

		public String cwd() { return cwd; }
		public String cursor() { return cursor; }
		public Map<String, Object> meta() { return meta; }

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null || getClass() != o.getClass()) return false;
			ListSessionsRequest that = (ListSessionsRequest) o;
			return Objects.equals(cwd, that.cwd)
							&& Objects.equals(cursor, that.cursor)
							&& Objects.equals(meta, that.meta);
		}

		@Override
		public int hashCode() {
			return Objects.hash(cwd, cursor, meta);
		}

		@Override
		public String toString() {
			return "ListSessionsRequest[" + "cwd=" + cwd + ", cursor=" + cursor + ", meta=" + meta + "]";
		}

		public ListSessionsRequest(String cwd) {
			this(cwd, null, null);
		}
	}

	/**
	 * List sessions response
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public static final class ListSessionsResponse {
		private final @JsonProperty("sessions") List<SessionInfo> sessions;
		private final @JsonProperty("nextCursor") String nextCursor;
		private final @JsonProperty("_meta") Map<String, Object> meta;

		public ListSessionsResponse(@JsonProperty("sessions") List<SessionInfo> sessions, @JsonProperty("nextCursor") String nextCursor, @JsonProperty("_meta") Map<String, Object> meta) {
			this.sessions = sessions;
			this.nextCursor = nextCursor;
			this.meta = meta;
		}

		public List<SessionInfo> sessions() { return sessions; }
		public String nextCursor() { return nextCursor; }
		public Map<String, Object> meta() { return meta; }

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null || getClass() != o.getClass()) return false;
			ListSessionsResponse that = (ListSessionsResponse) o;
			return Objects.equals(sessions, that.sessions)
							&& Objects.equals(nextCursor, that.nextCursor)
							&& Objects.equals(meta, that.meta);
		}

		@Override
		public int hashCode() {
			return Objects.hash(sessions, nextCursor, meta);
		}

		@Override
		public String toString() {
			return "ListSessionsResponse[" + "sessions=" + sessions + ", nextCursor=" + nextCursor + ", meta=" + meta + "]";
		}

		public ListSessionsResponse(List<SessionInfo> sessions) {
			this(sessions, null, null);
		}
	}

	/**
	 * Close session request - closes a session and cancels in-flight work
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public static final class CloseSessionRequest {
		private final @JsonProperty("sessionId") String sessionId;
		private final @JsonProperty("_meta") Map<String, Object> meta;

		public CloseSessionRequest(@JsonProperty("sessionId") String sessionId, @JsonProperty("_meta") Map<String, Object> meta) {
			this.sessionId = sessionId;
			this.meta = meta;
		}

		public String sessionId() { return sessionId; }
		public Map<String, Object> meta() { return meta; }

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null || getClass() != o.getClass()) return false;
			CloseSessionRequest that = (CloseSessionRequest) o;
			return Objects.equals(sessionId, that.sessionId)
							&& Objects.equals(meta, that.meta);
		}

		@Override
		public int hashCode() {
			return Objects.hash(sessionId, meta);
		}

		@Override
		public String toString() {
			return "CloseSessionRequest[" + "sessionId=" + sessionId + ", meta=" + meta + "]";
		}

		public CloseSessionRequest(String sessionId) {
			this(sessionId, null);
		}
	}

	/**
	 * Close session response
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public static final class CloseSessionResponse {
		private final @JsonProperty("_meta") Map<String, Object> meta;

		public CloseSessionResponse(@JsonProperty("_meta") Map<String, Object> meta) {
			this.meta = meta;
		}

		public Map<String, Object> meta() { return meta; }

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null || getClass() != o.getClass()) return false;
			CloseSessionResponse that = (CloseSessionResponse) o;
			return Objects.equals(meta, that.meta);
		}

		@Override
		public int hashCode() {
			return Objects.hash(meta);
		}

		@Override
		public String toString() {
			return "CloseSessionResponse[" + "meta=" + meta + "]";
		}

		public CloseSessionResponse() {
			this(null);
		}
	}

	/**
	 * Delete session request - permanently deletes a stored session.
	 *
	 * <p>Only available if the agent advertises the {@code sessionCapabilities.delete}
	 * capability.
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public static final class DeleteSessionRequest {
		private final @JsonProperty("sessionId") String sessionId;
		private final @JsonProperty("_meta") Map<String, Object> meta;

		public DeleteSessionRequest(@JsonProperty("sessionId") String sessionId, @JsonProperty("_meta") Map<String, Object> meta) {
			this.sessionId = sessionId;
			this.meta = meta;
		}

		public String sessionId() { return sessionId; }
		public Map<String, Object> meta() { return meta; }

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null || getClass() != o.getClass()) return false;
			DeleteSessionRequest that = (DeleteSessionRequest) o;
			return Objects.equals(sessionId, that.sessionId)
							&& Objects.equals(meta, that.meta);
		}

		@Override
		public int hashCode() {
			return Objects.hash(sessionId, meta);
		}

		@Override
		public String toString() {
			return "DeleteSessionRequest[" + "sessionId=" + sessionId + ", meta=" + meta + "]";
		}

		public DeleteSessionRequest(String sessionId) {
			this(sessionId, null);
		}
	}

	/**
	 * Delete session response
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public static final class DeleteSessionResponse {
		private final @JsonProperty("_meta") Map<String, Object> meta;

		public DeleteSessionResponse(@JsonProperty("_meta") Map<String, Object> meta) {
			this.meta = meta;
		}

		public Map<String, Object> meta() { return meta; }

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null || getClass() != o.getClass()) return false;
			DeleteSessionResponse that = (DeleteSessionResponse) o;
			return Objects.equals(meta, that.meta);
		}

		@Override
		public int hashCode() {
			return Objects.hash(meta);
		}

		@Override
		public String toString() {
			return "DeleteSessionResponse[" + "meta=" + meta + "]";
		}

		public DeleteSessionResponse() {
			this(null);
		}
	}

	/**
	 * Resume session request - reconnects to existing session without replaying history
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public static final class ResumeSessionRequest {
		private final @JsonProperty("sessionId") String sessionId;
		private final @JsonProperty("cwd") String cwd;
		private final @JsonProperty("mcpServers") List<McpServer> mcpServers;
		private final @JsonProperty("additionalDirectories") List<String> additionalDirectories;
		private final @JsonProperty("_meta") Map<String, Object> meta;

		public ResumeSessionRequest(@JsonProperty("sessionId") String sessionId, @JsonProperty("cwd") String cwd, @JsonProperty("mcpServers") List<McpServer> mcpServers, @JsonProperty("additionalDirectories") List<String> additionalDirectories, @JsonProperty("_meta") Map<String, Object> meta) {
			this.sessionId = sessionId;
			this.cwd = cwd;
			this.mcpServers = mcpServers;
			this.additionalDirectories = additionalDirectories;
			this.meta = meta;
		}

		public String sessionId() { return sessionId; }
		public String cwd() { return cwd; }
		public List<McpServer> mcpServers() { return mcpServers; }
		public List<String> additionalDirectories() { return additionalDirectories; }
		public Map<String, Object> meta() { return meta; }

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null || getClass() != o.getClass()) return false;
			ResumeSessionRequest that = (ResumeSessionRequest) o;
			return Objects.equals(sessionId, that.sessionId)
							&& Objects.equals(cwd, that.cwd)
							&& Objects.equals(mcpServers, that.mcpServers)
							&& Objects.equals(additionalDirectories, that.additionalDirectories)
							&& Objects.equals(meta, that.meta);
		}

		@Override
		public int hashCode() {
			return Objects.hash(sessionId, cwd, mcpServers, additionalDirectories, meta);
		}

		@Override
		public String toString() {
			return "ResumeSessionRequest[" + "sessionId=" + sessionId + ", cwd=" + cwd + ", mcpServers=" + mcpServers + ", additionalDirectories=" + additionalDirectories + ", meta=" + meta + "]";
		}

		public ResumeSessionRequest(String sessionId, String cwd, List<McpServer> mcpServers) {
			this(sessionId, cwd, mcpServers, null, null);
		}

		public ResumeSessionRequest(String sessionId, String cwd, List<McpServer> mcpServers,
				List<String> additionalDirectories) {
			this(sessionId, cwd, mcpServers, additionalDirectories, null);
		}
	}

	/**
	 * Resume session response
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	@SuppressWarnings("removal") // 'models' references the deprecated-for-removal SessionModelState
	public static final class ResumeSessionResponse {
		private final @JsonProperty("modes") SessionModeState modes;
		private final @JsonProperty("models") SessionModelState models;
		private final @JsonProperty("_meta") Map<String, Object> meta;

		public ResumeSessionResponse(@JsonProperty("modes") SessionModeState modes, @JsonProperty("models") SessionModelState models, @JsonProperty("_meta") Map<String, Object> meta) {
			this.modes = modes;
			this.models = models;
			this.meta = meta;
		}

		public SessionModeState modes() { return modes; }
		public SessionModelState models() { return models; }
		public Map<String, Object> meta() { return meta; }

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null || getClass() != o.getClass()) return false;
			ResumeSessionResponse that = (ResumeSessionResponse) o;
			return Objects.equals(modes, that.modes)
							&& Objects.equals(models, that.models)
							&& Objects.equals(meta, that.meta);
		}

		@Override
		public int hashCode() {
			return Objects.hash(modes, models, meta);
		}

		@Override
		public String toString() {
			return "ResumeSessionResponse[" + "modes=" + modes + ", models=" + models + ", meta=" + meta + "]";
		}

		public ResumeSessionResponse(SessionModeState modes, SessionModelState models) {
			this(modes, models, null);
		}
	}

	/**
	 * Fork session request - creates a new session branched from an existing one
	 */
	@UnstableAcpApi
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public static final class ForkSessionRequest {
		private final @JsonProperty("sessionId") String sessionId;
		private final @JsonProperty("cwd") String cwd;
		private final @JsonProperty("mcpServers") List<McpServer> mcpServers;
		private final @JsonProperty("additionalDirectories") List<String> additionalDirectories;
		private final @JsonProperty("_meta") Map<String, Object> meta;

		public ForkSessionRequest(@JsonProperty("sessionId") String sessionId, @JsonProperty("cwd") String cwd, @JsonProperty("mcpServers") List<McpServer> mcpServers, @JsonProperty("additionalDirectories") List<String> additionalDirectories, @JsonProperty("_meta") Map<String, Object> meta) {
			this.sessionId = sessionId;
			this.cwd = cwd;
			this.mcpServers = mcpServers;
			this.additionalDirectories = additionalDirectories;
			this.meta = meta;
		}

		public String sessionId() { return sessionId; }
		public String cwd() { return cwd; }
		public List<McpServer> mcpServers() { return mcpServers; }
		public List<String> additionalDirectories() { return additionalDirectories; }
		public Map<String, Object> meta() { return meta; }

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null || getClass() != o.getClass()) return false;
			ForkSessionRequest that = (ForkSessionRequest) o;
			return Objects.equals(sessionId, that.sessionId)
							&& Objects.equals(cwd, that.cwd)
							&& Objects.equals(mcpServers, that.mcpServers)
							&& Objects.equals(additionalDirectories, that.additionalDirectories)
							&& Objects.equals(meta, that.meta);
		}

		@Override
		public int hashCode() {
			return Objects.hash(sessionId, cwd, mcpServers, additionalDirectories, meta);
		}

		@Override
		public String toString() {
			return "ForkSessionRequest[" + "sessionId=" + sessionId + ", cwd=" + cwd + ", mcpServers=" + mcpServers + ", additionalDirectories=" + additionalDirectories + ", meta=" + meta + "]";
		}

		public ForkSessionRequest(String sessionId, String cwd, List<McpServer> mcpServers) {
			this(sessionId, cwd, mcpServers, null, null);
		}

		public ForkSessionRequest(String sessionId, String cwd, List<McpServer> mcpServers,
				List<String> additionalDirectories) {
			this(sessionId, cwd, mcpServers, additionalDirectories, null);
		}
	}

	/**
	 * Fork session response - returns the new forked session ID
	 */
	@UnstableAcpApi
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	@SuppressWarnings("removal") // 'models' references the deprecated-for-removal SessionModelState
	public static final class ForkSessionResponse {
		private final @JsonProperty("sessionId") String sessionId;
		private final @JsonProperty("modes") SessionModeState modes;
		private final @JsonProperty("models") SessionModelState models;
		private final @JsonProperty("configOptions") List<SessionConfigOption> configOptions;
		private final @JsonProperty("_meta") Map<String, Object> meta;

		public ForkSessionResponse(@JsonProperty("sessionId") String sessionId, @JsonProperty("modes") SessionModeState modes, @JsonProperty("models") SessionModelState models, @JsonProperty("configOptions") List<SessionConfigOption> configOptions, @JsonProperty("_meta") Map<String, Object> meta) {
			this.sessionId = sessionId;
			this.modes = modes;
			this.models = models;
			this.configOptions = configOptions;
			this.meta = meta;
		}

		public String sessionId() { return sessionId; }
		public SessionModeState modes() { return modes; }
		public SessionModelState models() { return models; }
		public List<SessionConfigOption> configOptions() { return configOptions; }
		public Map<String, Object> meta() { return meta; }

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null || getClass() != o.getClass()) return false;
			ForkSessionResponse that = (ForkSessionResponse) o;
			return Objects.equals(sessionId, that.sessionId)
							&& Objects.equals(modes, that.modes)
							&& Objects.equals(models, that.models)
							&& Objects.equals(configOptions, that.configOptions)
							&& Objects.equals(meta, that.meta);
		}

		@Override
		public int hashCode() {
			return Objects.hash(sessionId, modes, models, configOptions, meta);
		}

		@Override
		public String toString() {
			return "ForkSessionResponse[" + "sessionId=" + sessionId + ", modes=" + modes + ", models=" + models + ", configOptions=" + configOptions + ", meta=" + meta + "]";
		}

		public ForkSessionResponse(String sessionId, SessionModeState modes, SessionModelState models) {
			this(sessionId, modes, models, null, null);
		}
	}

	/**
	 * Set session config option request - changes a configuration value
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public static final class SetSessionConfigOptionRequest {
		private final @JsonProperty("sessionId") String sessionId;
		private final @JsonProperty("configId") String configId;
		private final @JsonProperty("value") Object value;
		private final @JsonProperty("type") String type;
		private final @JsonProperty("_meta") Map<String, Object> meta;

		public SetSessionConfigOptionRequest(@JsonProperty("sessionId") String sessionId, @JsonProperty("configId") String configId, @JsonProperty("value") Object value, @JsonProperty("type") String type, @JsonProperty("_meta") Map<String, Object> meta) {
			this.sessionId = sessionId;
			this.configId = configId;
			this.value = value;
			this.type = type;
			this.meta = meta;
		}

		public String sessionId() { return sessionId; }
		public String configId() { return configId; }
		public Object value() { return value; }
		public String type() { return type; }
		public Map<String, Object> meta() { return meta; }

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null || getClass() != o.getClass()) return false;
			SetSessionConfigOptionRequest that = (SetSessionConfigOptionRequest) o;
			return Objects.equals(sessionId, that.sessionId)
							&& Objects.equals(configId, that.configId)
							&& Objects.equals(value, that.value)
							&& Objects.equals(type, that.type)
							&& Objects.equals(meta, that.meta);
		}

		@Override
		public int hashCode() {
			return Objects.hash(sessionId, configId, value, type, meta);
		}

		@Override
		public String toString() {
			return "SetSessionConfigOptionRequest[" + "sessionId=" + sessionId + ", configId=" + configId + ", value=" + value + ", type=" + type + ", meta=" + meta + "]";
		}


		/**
		 * Creates a request to set a select-type config option.
		 */
		public static SetSessionConfigOptionRequest select(String sessionId, String configId, String value) {
			return new SetSessionConfigOptionRequest(sessionId, configId, value, null, null);
		}

		/**
		 * Creates a request to set a boolean-type config option.
		 */
		public static SetSessionConfigOptionRequest bool(String sessionId, String configId, boolean value) {
			return new SetSessionConfigOptionRequest(sessionId, configId, value, "boolean", null);
		}
	}

	/**
	 * Set session config option response - returns full config state
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public static final class SetSessionConfigOptionResponse {
		private final @JsonProperty("configOptions") List<SessionConfigOption> configOptions;
		private final @JsonProperty("_meta") Map<String, Object> meta;

		public SetSessionConfigOptionResponse(@JsonProperty("configOptions") List<SessionConfigOption> configOptions, @JsonProperty("_meta") Map<String, Object> meta) {
			this.configOptions = configOptions;
			this.meta = meta;
		}

		public List<SessionConfigOption> configOptions() { return configOptions; }
		public Map<String, Object> meta() { return meta; }

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null || getClass() != o.getClass()) return false;
			SetSessionConfigOptionResponse that = (SetSessionConfigOptionResponse) o;
			return Objects.equals(configOptions, that.configOptions)
							&& Objects.equals(meta, that.meta);
		}

		@Override
		public int hashCode() {
			return Objects.hash(configOptions, meta);
		}

		@Override
		public String toString() {
			return "SetSessionConfigOptionResponse[" + "configOptions=" + configOptions + ", meta=" + meta + "]";
		}

		public SetSessionConfigOptionResponse(List<SessionConfigOption> configOptions) {
			this(configOptions, null);
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
			return Objects.equals(sessionId, that.sessionId)
							&& Objects.equals(toolCall, that.toolCall)
							&& Objects.equals(options, that.options);
		}

		@Override
		public int hashCode() {
			return Objects.hash(sessionId, toolCall, options);
		}

		@Override
		public String toString() {
			return "RequestPermissionRequest[" + "sessionId=" + sessionId + ", toolCall=" + toolCall + ", options=" + options + "]";
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
			return "RequestPermissionResponse[" + "outcome=" + outcome + "]";
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

		public String sessionId() { return sessionId; }
		public SessionUpdate update() { return update; }
		public Map<String, Object> meta() { return meta; }

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null || getClass() != o.getClass()) return false;
			SessionNotification that = (SessionNotification) o;
			return Objects.equals(sessionId, that.sessionId)
							&& Objects.equals(update, that.update)
							&& Objects.equals(meta, that.meta);
		}

		@Override
		public int hashCode() {
			return Objects.hash(sessionId, update, meta);
		}

		@Override
		public String toString() {
			return "SessionNotification[" + "sessionId=" + sessionId + ", update=" + update + ", meta=" + meta + "]";
		}

		public SessionNotification(String sessionId, SessionUpdate update) {
			this(sessionId, update, null);
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
			return Objects.equals(sessionId, that.sessionId)
							&& Objects.equals(path, that.path)
							&& Objects.equals(line, that.line)
							&& Objects.equals(limit, that.limit);
		}

		@Override
		public int hashCode() {
			return Objects.hash(sessionId, path, line, limit);
		}

		@Override
		public String toString() {
			return "ReadTextFileRequest[" + "sessionId=" + sessionId + ", path=" + path + ", line=" + line + ", limit=" + limit + "]";
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
			return "ReadTextFileResponse[" + "content=" + content + "]";
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
			return Objects.equals(sessionId, that.sessionId)
							&& Objects.equals(path, that.path)
							&& Objects.equals(content, that.content);
		}

		@Override
		public int hashCode() {
			return Objects.hash(sessionId, path, content);
		}

		@Override
		public String toString() {
			return "WriteTextFileRequest[" + "sessionId=" + sessionId + ", path=" + path + ", content=" + content + "]";
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
			return Objects.equals(sessionId, that.sessionId)
							&& Objects.equals(command, that.command)
							&& Objects.equals(args, that.args)
							&& Objects.equals(cwd, that.cwd)
							&& Objects.equals(env, that.env)
							&& Objects.equals(outputByteLimit, that.outputByteLimit);
		}

		@Override
		public int hashCode() {
			return Objects.hash(sessionId, command, args, cwd, env, outputByteLimit);
		}

		@Override
		public String toString() {
			return "CreateTerminalRequest[" + "sessionId=" + sessionId + ", command=" + command + ", args=" + args + ", cwd=" + cwd + ", env=" + env + ", outputByteLimit=" + outputByteLimit + "]";
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
			return "CreateTerminalResponse[" + "terminalId=" + terminalId + "]";
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
			return Objects.equals(sessionId, that.sessionId)
							&& Objects.equals(terminalId, that.terminalId);
		}

		@Override
		public int hashCode() {
			return Objects.hash(sessionId, terminalId);
		}

		@Override
		public String toString() {
			return "TerminalOutputRequest[" + "sessionId=" + sessionId + ", terminalId=" + terminalId + "]";
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
			return Objects.equals(output, that.output)
							&& truncated == that.truncated
							&& Objects.equals(exitStatus, that.exitStatus);
		}

		@Override
		public int hashCode() {
			return Objects.hash(output, truncated, exitStatus);
		}

		@Override
		public String toString() {
			return "TerminalOutputResponse[" + "output=" + output + ", truncated=" + truncated + ", exitStatus=" + exitStatus + "]";
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
			return Objects.equals(sessionId, that.sessionId)
							&& Objects.equals(terminalId, that.terminalId);
		}

		@Override
		public int hashCode() {
			return Objects.hash(sessionId, terminalId);
		}

		@Override
		public String toString() {
			return "ReleaseTerminalRequest[" + "sessionId=" + sessionId + ", terminalId=" + terminalId + "]";
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
			return Objects.equals(sessionId, that.sessionId)
							&& Objects.equals(terminalId, that.terminalId);
		}

		@Override
		public int hashCode() {
			return Objects.hash(sessionId, terminalId);
		}

		@Override
		public String toString() {
			return "WaitForTerminalExitRequest[" + "sessionId=" + sessionId + ", terminalId=" + terminalId + "]";
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
			return Objects.equals(exitCode, that.exitCode)
							&& Objects.equals(signal, that.signal);
		}

		@Override
		public int hashCode() {
			return Objects.hash(exitCode, signal);
		}

		@Override
		public String toString() {
			return "WaitForTerminalExitResponse[" + "exitCode=" + exitCode + ", signal=" + signal + "]";
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
			return Objects.equals(sessionId, that.sessionId)
							&& Objects.equals(terminalId, that.terminalId);
		}

		@Override
		public int hashCode() {
			return Objects.hash(sessionId, terminalId);
		}

		@Override
		public String toString() {
			return "KillTerminalCommandRequest[" + "sessionId=" + sessionId + ", terminalId=" + terminalId + "]";
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
	// Elicitation (UNSTABLE)
	// ---------------------------

	/**
	 * Create elicitation request - agent asks client for structured user input.
	 * Supports form mode (JSON Schema) and URL mode (out-of-band).
	 * Scope is either session (sessionId) or request (requestId).
	 */
	@UnstableAcpApi
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public static final class CreateElicitationRequest {
		private final @JsonProperty("sessionId") String sessionId;
		private final @JsonProperty("toolCallId") String toolCallId;
		private final @JsonProperty("requestId") Object requestId;
		private final @JsonProperty("message") String message;
		private final @JsonProperty("mode") String mode;
		private final @JsonProperty("requestedSchema") ElicitationSchema requestedSchema;
		private final @JsonProperty("elicitationId") String elicitationId;
		private final @JsonProperty("url") String url;
		private final @JsonProperty("_meta") Map<String, Object> meta;

		public CreateElicitationRequest(@JsonProperty("sessionId") String sessionId, @JsonProperty("toolCallId") String toolCallId, @JsonProperty("requestId") Object requestId, @JsonProperty("message") String message, @JsonProperty("mode") String mode, @JsonProperty("requestedSchema") ElicitationSchema requestedSchema, @JsonProperty("elicitationId") String elicitationId, @JsonProperty("url") String url, @JsonProperty("_meta") Map<String, Object> meta) {
			this.sessionId = sessionId;
			this.toolCallId = toolCallId;
			this.requestId = requestId;
			this.message = message;
			this.mode = mode;
			this.requestedSchema = requestedSchema;
			this.elicitationId = elicitationId;
			this.url = url;
			this.meta = meta;
		}

		public String sessionId() { return sessionId; }
		public String toolCallId() { return toolCallId; }
		public Object requestId() { return requestId; }
		public String message() { return message; }
		public String mode() { return mode; }
		public ElicitationSchema requestedSchema() { return requestedSchema; }
		public String elicitationId() { return elicitationId; }
		public String url() { return url; }
		public Map<String, Object> meta() { return meta; }

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null || getClass() != o.getClass()) return false;
			CreateElicitationRequest that = (CreateElicitationRequest) o;
			return Objects.equals(sessionId, that.sessionId)
							&& Objects.equals(toolCallId, that.toolCallId)
							&& Objects.equals(requestId, that.requestId)
							&& Objects.equals(message, that.message)
							&& Objects.equals(mode, that.mode)
							&& Objects.equals(requestedSchema, that.requestedSchema)
							&& Objects.equals(elicitationId, that.elicitationId)
							&& Objects.equals(url, that.url)
							&& Objects.equals(meta, that.meta);
		}

		@Override
		public int hashCode() {
			return Objects.hash(sessionId, toolCallId, requestId, message, mode, requestedSchema, elicitationId, url, meta);
		}

		@Override
		public String toString() {
			return "CreateElicitationRequest[" + "sessionId=" + sessionId + ", toolCallId=" + toolCallId + ", requestId=" + requestId + ", message=" + message + ", mode=" + mode + ", requestedSchema=" + requestedSchema + ", elicitationId=" + elicitationId + ", url=" + url + ", meta=" + meta + "]";
		}


		/**
		 * Creates a form-mode elicitation request scoped to a session.
		 */
		public static CreateElicitationRequest form(String sessionId, String message,
				ElicitationSchema schema) {
			return new CreateElicitationRequest(sessionId, null, null, message, "form", schema, null,
					null, null);
		}

		/**
		 * Creates a URL-mode elicitation request scoped to a session.
		 */
		public static CreateElicitationRequest url(String sessionId, String message,
				String elicitationId, String url) {
			return new CreateElicitationRequest(sessionId, null, null, message, "url", null,
					elicitationId, url, null);
		}
	}

	/**
	 * Create elicitation response - client returns user's input.
	 * Action is "accept" (with content), "decline", or "cancel".
	 */
	@UnstableAcpApi
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public static final class CreateElicitationResponse {
		private final @JsonProperty("action") ElicitationAction action;
		private final @JsonProperty("content") Map<String, Object> content;
		private final @JsonProperty("_meta") Map<String, Object> meta;

		public CreateElicitationResponse(@JsonProperty("action") ElicitationAction action, @JsonProperty("content") Map<String, Object> content, @JsonProperty("_meta") Map<String, Object> meta) {
			this.action = action;
			this.content = content;
			this.meta = meta;
		}

		public ElicitationAction action() { return action; }
		public Map<String, Object> content() { return content; }
		public Map<String, Object> meta() { return meta; }

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null || getClass() != o.getClass()) return false;
			CreateElicitationResponse that = (CreateElicitationResponse) o;
			return Objects.equals(action, that.action)
							&& Objects.equals(content, that.content)
							&& Objects.equals(meta, that.meta);
		}

		@Override
		public int hashCode() {
			return Objects.hash(action, content, meta);
		}

		@Override
		public String toString() {
			return "CreateElicitationResponse[" + "action=" + action + ", content=" + content + ", meta=" + meta + "]";
		}


		public static CreateElicitationResponse accept(Map<String, Object> content) {
			return new CreateElicitationResponse(ElicitationAction.ACCEPT, content, null);
		}

		public static CreateElicitationResponse decline() {
			return new CreateElicitationResponse(ElicitationAction.DECLINE, null, null);
		}

		public static CreateElicitationResponse cancel() {
			return new CreateElicitationResponse(ElicitationAction.CANCEL, null, null);
		}
	}

	/**
	 * Elicitation action - user's response to an elicitation.
	 */
	@UnstableAcpApi
	public enum ElicitationAction {

		@JsonProperty("accept")
		ACCEPT, @JsonProperty("decline")
		DECLINE, @JsonProperty("cancel")
		CANCEL

	}

	/**
	 * Complete elicitation notification - signals URL-mode elicitation is done.
	 */
	@UnstableAcpApi
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public static final class CompleteElicitationNotification {
		private final @JsonProperty("elicitationId") String elicitationId;
		private final @JsonProperty("_meta") Map<String, Object> meta;

		public CompleteElicitationNotification(@JsonProperty("elicitationId") String elicitationId, @JsonProperty("_meta") Map<String, Object> meta) {
			this.elicitationId = elicitationId;
			this.meta = meta;
		}

		public String elicitationId() { return elicitationId; }
		public Map<String, Object> meta() { return meta; }

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null || getClass() != o.getClass()) return false;
			CompleteElicitationNotification that = (CompleteElicitationNotification) o;
			return Objects.equals(elicitationId, that.elicitationId)
							&& Objects.equals(meta, that.meta);
		}

		@Override
		public int hashCode() {
			return Objects.hash(elicitationId, meta);
		}

		@Override
		public String toString() {
			return "CompleteElicitationNotification[" + "elicitationId=" + elicitationId + ", meta=" + meta + "]";
		}

		public CompleteElicitationNotification(String elicitationId) {
			this(elicitationId, null);
		}
	}

	/**
	 * Elicitation schema - JSON Schema describing form fields for user input.
	 */
	@UnstableAcpApi
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public static final class ElicitationSchema {
		private final @JsonProperty("type") String type;
		private final @JsonProperty("properties") Map<String, ElicitationPropertySchema> properties;
		private final @JsonProperty("required") List<String> required;
		private final @JsonProperty("title") String title;
		private final @JsonProperty("description") String description;

		public ElicitationSchema(@JsonProperty("type") String type, @JsonProperty("properties") Map<String, ElicitationPropertySchema> properties, @JsonProperty("required") List<String> required, @JsonProperty("title") String title, @JsonProperty("description") String description) {
			this.type = type;
			this.properties = properties;
			this.required = required;
			this.title = title;
			this.description = description;
		}

		public String type() { return type; }
		public Map<String, ElicitationPropertySchema> properties() { return properties; }
		public List<String> required() { return required; }
		public String title() { return title; }
		public String description() { return description; }

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null || getClass() != o.getClass()) return false;
			ElicitationSchema that = (ElicitationSchema) o;
			return Objects.equals(type, that.type)
							&& Objects.equals(properties, that.properties)
							&& Objects.equals(required, that.required)
							&& Objects.equals(title, that.title)
							&& Objects.equals(description, that.description);
		}

		@Override
		public int hashCode() {
			return Objects.hash(type, properties, required, title, description);
		}

		@Override
		public String toString() {
			return "ElicitationSchema[" + "type=" + type + ", properties=" + properties + ", required=" + required + ", title=" + title + ", description=" + description + "]";
		}

		public ElicitationSchema(Map<String, ElicitationPropertySchema> properties, List<String> required) {
			this("object", properties, required, null, null);
		}
	}

	/**
	 * Elicitation property schema - defines a single form field.
	 */
	@UnstableAcpApi
	@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
	@JsonSubTypes({ @JsonSubTypes.Type(value = StringPropertySchema.class, name = "string"),
			@JsonSubTypes.Type(value = NumberPropertySchema.class, name = "number"),
			@JsonSubTypes.Type(value = IntegerPropertySchema.class, name = "integer"),
			@JsonSubTypes.Type(value = BooleanPropertySchema.class, name = "boolean"),
			@JsonSubTypes.Type(value = MultiSelectPropertySchema.class, name = "array") })
	public interface ElicitationPropertySchema {

	}

	/**
	 * String property schema - text input or single-select enum.
	 */
	@UnstableAcpApi
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public static final class StringPropertySchema implements ElicitationPropertySchema {
		private final @JsonProperty("type") String type;
		private final @JsonProperty("title") String title;
		private final @JsonProperty("description") String description;
		private final @JsonProperty("default") String defaultValue;
		private final @JsonProperty("minLength") Integer minLength;
		private final @JsonProperty("maxLength") Integer maxLength;
		private final @JsonProperty("pattern") String pattern;
		private final @JsonProperty("format") String format;
		private final @JsonProperty("enum") List<String> enumValues;
		private final @JsonProperty("oneOf") List<EnumOption> oneOf;

		public StringPropertySchema(@JsonProperty("type") String type, @JsonProperty("title") String title, @JsonProperty("description") String description, @JsonProperty("default") String defaultValue, @JsonProperty("minLength") Integer minLength, @JsonProperty("maxLength") Integer maxLength, @JsonProperty("pattern") String pattern, @JsonProperty("format") String format, @JsonProperty("enum") List<String> enumValues, @JsonProperty("oneOf") List<EnumOption> oneOf) {
			this.type = type;
			this.title = title;
			this.description = description;
			this.defaultValue = defaultValue;
			this.minLength = minLength;
			this.maxLength = maxLength;
			this.pattern = pattern;
			this.format = format;
			this.enumValues = enumValues;
			this.oneOf = oneOf;
		}

		public String type() { return type; }
		public String title() { return title; }
		public String description() { return description; }
		public String defaultValue() { return defaultValue; }
		public Integer minLength() { return minLength; }
		public Integer maxLength() { return maxLength; }
		public String pattern() { return pattern; }
		public String format() { return format; }
		public List<String> enumValues() { return enumValues; }
		public List<EnumOption> oneOf() { return oneOf; }

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null || getClass() != o.getClass()) return false;
			StringPropertySchema that = (StringPropertySchema) o;
			return Objects.equals(type, that.type)
							&& Objects.equals(title, that.title)
							&& Objects.equals(description, that.description)
							&& Objects.equals(defaultValue, that.defaultValue)
							&& Objects.equals(minLength, that.minLength)
							&& Objects.equals(maxLength, that.maxLength)
							&& Objects.equals(pattern, that.pattern)
							&& Objects.equals(format, that.format)
							&& Objects.equals(enumValues, that.enumValues)
							&& Objects.equals(oneOf, that.oneOf);
		}

		@Override
		public int hashCode() {
			return Objects.hash(type, title, description, defaultValue, minLength, maxLength, pattern, format, enumValues, oneOf);
		}

		@Override
		public String toString() {
			return "StringPropertySchema[" + "type=" + type + ", title=" + title + ", description=" + description + ", defaultValue=" + defaultValue + ", minLength=" + minLength + ", maxLength=" + maxLength + ", pattern=" + pattern + ", format=" + format + ", enumValues=" + enumValues + ", oneOf=" + oneOf + "]";
		}


		public static StringPropertySchema text(String title) {
			return new StringPropertySchema("string", title, null, null, null, null, null, null, null, null);
		}

		public static StringPropertySchema singleSelect(String title, List<EnumOption> options) {
			return new StringPropertySchema("string", title, null, null, null, null, null, null, null, options);
		}
	}

	/**
	 * Number property schema - floating-point input.
	 */
	@UnstableAcpApi
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public static final class NumberPropertySchema implements ElicitationPropertySchema {
		private final @JsonProperty("type") String type;
		private final @JsonProperty("title") String title;
		private final @JsonProperty("description") String description;
		private final @JsonProperty("default") Double defaultValue;
		private final @JsonProperty("minimum") Double minimum;
		private final @JsonProperty("maximum") Double maximum;

		public NumberPropertySchema(@JsonProperty("type") String type, @JsonProperty("title") String title, @JsonProperty("description") String description, @JsonProperty("default") Double defaultValue, @JsonProperty("minimum") Double minimum, @JsonProperty("maximum") Double maximum) {
			this.type = type;
			this.title = title;
			this.description = description;
			this.defaultValue = defaultValue;
			this.minimum = minimum;
			this.maximum = maximum;
		}

		public String type() { return type; }
		public String title() { return title; }
		public String description() { return description; }
		public Double defaultValue() { return defaultValue; }
		public Double minimum() { return minimum; }
		public Double maximum() { return maximum; }

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null || getClass() != o.getClass()) return false;
			NumberPropertySchema that = (NumberPropertySchema) o;
			return Objects.equals(type, that.type)
							&& Objects.equals(title, that.title)
							&& Objects.equals(description, that.description)
							&& Objects.equals(defaultValue, that.defaultValue)
							&& Objects.equals(minimum, that.minimum)
							&& Objects.equals(maximum, that.maximum);
		}

		@Override
		public int hashCode() {
			return Objects.hash(type, title, description, defaultValue, minimum, maximum);
		}

		@Override
		public String toString() {
			return "NumberPropertySchema[" + "type=" + type + ", title=" + title + ", description=" + description + ", defaultValue=" + defaultValue + ", minimum=" + minimum + ", maximum=" + maximum + "]";
		}
	}

	/**
	 * Integer property schema - whole number input.
	 */
	@UnstableAcpApi
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public static final class IntegerPropertySchema implements ElicitationPropertySchema {
		private final @JsonProperty("type") String type;
		private final @JsonProperty("title") String title;
		private final @JsonProperty("description") String description;
		private final @JsonProperty("default") Long defaultValue;
		private final @JsonProperty("minimum") Long minimum;
		private final @JsonProperty("maximum") Long maximum;

		public IntegerPropertySchema(@JsonProperty("type") String type, @JsonProperty("title") String title, @JsonProperty("description") String description, @JsonProperty("default") Long defaultValue, @JsonProperty("minimum") Long minimum, @JsonProperty("maximum") Long maximum) {
			this.type = type;
			this.title = title;
			this.description = description;
			this.defaultValue = defaultValue;
			this.minimum = minimum;
			this.maximum = maximum;
		}

		public String type() { return type; }
		public String title() { return title; }
		public String description() { return description; }
		public Long defaultValue() { return defaultValue; }
		public Long minimum() { return minimum; }
		public Long maximum() { return maximum; }

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null || getClass() != o.getClass()) return false;
			IntegerPropertySchema that = (IntegerPropertySchema) o;
			return Objects.equals(type, that.type)
							&& Objects.equals(title, that.title)
							&& Objects.equals(description, that.description)
							&& Objects.equals(defaultValue, that.defaultValue)
							&& Objects.equals(minimum, that.minimum)
							&& Objects.equals(maximum, that.maximum);
		}

		@Override
		public int hashCode() {
			return Objects.hash(type, title, description, defaultValue, minimum, maximum);
		}

		@Override
		public String toString() {
			return "IntegerPropertySchema[" + "type=" + type + ", title=" + title + ", description=" + description + ", defaultValue=" + defaultValue + ", minimum=" + minimum + ", maximum=" + maximum + "]";
		}
	}

	/**
	 * Boolean property schema - checkbox/toggle input.
	 */
	@UnstableAcpApi
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public static final class BooleanPropertySchema implements ElicitationPropertySchema {
		private final @JsonProperty("type") String type;
		private final @JsonProperty("title") String title;
		private final @JsonProperty("description") String description;
		private final @JsonProperty("default") Boolean defaultValue;

		public BooleanPropertySchema(@JsonProperty("type") String type, @JsonProperty("title") String title, @JsonProperty("description") String description, @JsonProperty("default") Boolean defaultValue) {
			this.type = type;
			this.title = title;
			this.description = description;
			this.defaultValue = defaultValue;
		}

		public String type() { return type; }
		public String title() { return title; }
		public String description() { return description; }
		public Boolean defaultValue() { return defaultValue; }

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null || getClass() != o.getClass()) return false;
			BooleanPropertySchema that = (BooleanPropertySchema) o;
			return Objects.equals(type, that.type)
							&& Objects.equals(title, that.title)
							&& Objects.equals(description, that.description)
							&& Objects.equals(defaultValue, that.defaultValue);
		}

		@Override
		public int hashCode() {
			return Objects.hash(type, title, description, defaultValue);
		}

		@Override
		public String toString() {
			return "BooleanPropertySchema[" + "type=" + type + ", title=" + title + ", description=" + description + ", defaultValue=" + defaultValue + "]";
		}
	}

	/**
	 * Multi-select property schema - array of selected values.
	 */
	@UnstableAcpApi
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public static final class MultiSelectPropertySchema implements ElicitationPropertySchema {
		private final @JsonProperty("type") String type;
		private final @JsonProperty("title") String title;
		private final @JsonProperty("description") String description;
		private final @JsonProperty("default") List<String> defaultValues;
		private final @JsonProperty("items") MultiSelectItems items;
		private final @JsonProperty("minItems") Long minItems;
		private final @JsonProperty("maxItems") Long maxItems;

		public MultiSelectPropertySchema(@JsonProperty("type") String type, @JsonProperty("title") String title, @JsonProperty("description") String description, @JsonProperty("default") List<String> defaultValues, @JsonProperty("items") MultiSelectItems items, @JsonProperty("minItems") Long minItems, @JsonProperty("maxItems") Long maxItems) {
			this.type = type;
			this.title = title;
			this.description = description;
			this.defaultValues = defaultValues;
			this.items = items;
			this.minItems = minItems;
			this.maxItems = maxItems;
		}

		public String type() { return type; }
		public String title() { return title; }
		public String description() { return description; }
		public List<String> defaultValues() { return defaultValues; }
		public MultiSelectItems items() { return items; }
		public Long minItems() { return minItems; }
		public Long maxItems() { return maxItems; }

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null || getClass() != o.getClass()) return false;
			MultiSelectPropertySchema that = (MultiSelectPropertySchema) o;
			return Objects.equals(type, that.type)
							&& Objects.equals(title, that.title)
							&& Objects.equals(description, that.description)
							&& Objects.equals(defaultValues, that.defaultValues)
							&& Objects.equals(items, that.items)
							&& Objects.equals(minItems, that.minItems)
							&& Objects.equals(maxItems, that.maxItems);
		}

		@Override
		public int hashCode() {
			return Objects.hash(type, title, description, defaultValues, items, minItems, maxItems);
		}

		@Override
		public String toString() {
			return "MultiSelectPropertySchema[" + "type=" + type + ", title=" + title + ", description=" + description + ", defaultValues=" + defaultValues + ", items=" + items + ", minItems=" + minItems + ", maxItems=" + maxItems + "]";
		}
	}

	/**
	 * Multi-select items - defines allowed values for multi-select.
	 */
	@UnstableAcpApi
	@JsonTypeInfo(use = JsonTypeInfo.Id.DEDUCTION)
	@JsonSubTypes({ @JsonSubTypes.Type(value = UntitledMultiSelectItems.class),
			@JsonSubTypes.Type(value = TitledMultiSelectItems.class) })
	public interface MultiSelectItems {

	}

	/**
	 * Untitled multi-select items - plain string enum values.
	 */
	@UnstableAcpApi
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public static final class UntitledMultiSelectItems implements MultiSelectItems {
		private final @JsonProperty("type") String type;
		private final @JsonProperty("enum") List<String> enumValues;

		public UntitledMultiSelectItems(@JsonProperty("type") String type, @JsonProperty("enum") List<String> enumValues) {
			this.type = type;
			this.enumValues = enumValues;
		}

		public String type() { return type; }
		public List<String> enumValues() { return enumValues; }

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null || getClass() != o.getClass()) return false;
			UntitledMultiSelectItems that = (UntitledMultiSelectItems) o;
			return Objects.equals(type, that.type)
							&& Objects.equals(enumValues, that.enumValues);
		}

		@Override
		public int hashCode() {
			return Objects.hash(type, enumValues);
		}

		@Override
		public String toString() {
			return "UntitledMultiSelectItems[" + "type=" + type + ", enumValues=" + enumValues + "]";
		}
	}

	/**
	 * Titled multi-select items - options with const/title pairs.
	 */
	@UnstableAcpApi
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public static final class TitledMultiSelectItems implements MultiSelectItems {
		private final @JsonProperty("anyOf") List<EnumOption> anyOf;

		public TitledMultiSelectItems(@JsonProperty("anyOf") List<EnumOption> anyOf) {
			this.anyOf = anyOf;
		}

		public List<EnumOption> anyOf() { return anyOf; }

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null || getClass() != o.getClass()) return false;
			TitledMultiSelectItems that = (TitledMultiSelectItems) o;
			return Objects.equals(anyOf, that.anyOf);
		}

		@Override
		public int hashCode() {
			return Objects.hash(anyOf);
		}

		@Override
		public String toString() {
			return "TitledMultiSelectItems[" + "anyOf=" + anyOf + "]";
		}
	}

	/**
	 * Enum option - a named value for single-select or multi-select.
	 */
	@UnstableAcpApi
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public static final class EnumOption {
		private final @JsonProperty("const") String constValue;
		private final @JsonProperty("title") String title;

		public EnumOption(@JsonProperty("const") String constValue, @JsonProperty("title") String title) {
			this.constValue = constValue;
			this.title = title;
		}

		public String constValue() { return constValue; }
		public String title() { return title; }

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null || getClass() != o.getClass()) return false;
			EnumOption that = (EnumOption) o;
			return Objects.equals(constValue, that.constValue)
							&& Objects.equals(title, that.title);
		}

		@Override
		public int hashCode() {
			return Objects.hash(constValue, title);
		}

		@Override
		public String toString() {
			return "EnumOption[" + "constValue=" + constValue + ", title=" + title + "]";
		}
	}

	/**
	 * Elicitation capabilities - advertised by client during initialize.
	 */
	@UnstableAcpApi
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public static final class ElicitationCapabilities {
		private final @JsonProperty("form") Object form;
		private final @JsonProperty("url") Object url;
		private final @JsonProperty("_meta") Map<String, Object> meta;

		public ElicitationCapabilities(@JsonProperty("form") Object form, @JsonProperty("url") Object url, @JsonProperty("_meta") Map<String, Object> meta) {
			this.form = form;
			this.url = url;
			this.meta = meta;
		}

		public Object form() { return form; }
		public Object url() { return url; }
		public Map<String, Object> meta() { return meta; }

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null || getClass() != o.getClass()) return false;
			ElicitationCapabilities that = (ElicitationCapabilities) o;
			return Objects.equals(form, that.form)
							&& Objects.equals(url, that.url)
							&& Objects.equals(meta, that.meta);
		}

		@Override
		public int hashCode() {
			return Objects.hash(form, url, meta);
		}

		@Override
		public String toString() {
			return "ElicitationCapabilities[" + "form=" + form + ", url=" + url + ", meta=" + meta + "]";
		}

		/**
		 * Creates capabilities indicating form-mode support (the default).
		 */
		public ElicitationCapabilities() {
			this(java.util.Collections.emptyMap(), null, null);
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
		private final @UnstableAcpApi @JsonProperty("elicitation") ElicitationCapabilities elicitation;
		private final @JsonProperty("_meta") Map<String, Object> meta;

		public ClientCapabilities(@JsonProperty("fs") FileSystemCapability fs, @JsonProperty("terminal") Boolean terminal, @UnstableAcpApi @JsonProperty("elicitation") ElicitationCapabilities elicitation, @JsonProperty("_meta") Map<String, Object> meta) {
			this.fs = fs;
			this.terminal = terminal;
			this.elicitation = elicitation;
			this.meta = meta;
		}

		public FileSystemCapability fs() { return fs; }
		public Boolean terminal() { return terminal; }
		public ElicitationCapabilities elicitation() { return elicitation; }
		public Map<String, Object> meta() { return meta; }

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null || getClass() != o.getClass()) return false;
			ClientCapabilities that = (ClientCapabilities) o;
			return Objects.equals(fs, that.fs)
							&& Objects.equals(terminal, that.terminal)
							&& Objects.equals(elicitation, that.elicitation)
							&& Objects.equals(meta, that.meta);
		}

		@Override
		public int hashCode() {
			return Objects.hash(fs, terminal, elicitation, meta);
		}

		@Override
		public String toString() {
			return "ClientCapabilities[" + "fs=" + fs + ", terminal=" + terminal + ", elicitation=" + elicitation + ", meta=" + meta + "]";
		}

		public ClientCapabilities() {
			this(new FileSystemCapability(), false, null, null);
		}

		public ClientCapabilities(FileSystemCapability fs, Boolean terminal) {
			this(fs, terminal, null, null);
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

		public Boolean readTextFile() { return readTextFile; }
		public Boolean writeTextFile() { return writeTextFile; }

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null || getClass() != o.getClass()) return false;
			FileSystemCapability that = (FileSystemCapability) o;
			return Objects.equals(readTextFile, that.readTextFile)
							&& Objects.equals(writeTextFile, that.writeTextFile);
		}

		@Override
		public int hashCode() {
			return Objects.hash(readTextFile, writeTextFile);
		}

		@Override
		public String toString() {
			return "FileSystemCapability[" + "readTextFile=" + readTextFile + ", writeTextFile=" + writeTextFile + "]";
		}

		public FileSystemCapability() {
			this(false, false);
		}
	}

	/**
	 * Agent capabilities
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public static final class AgentCapabilities {
		private final @JsonProperty("loadSession") Boolean loadSession;
		private final @JsonProperty("sessionCapabilities") SessionCapabilities sessionCapabilities;
		private final @JsonProperty("mcpCapabilities") McpCapabilities mcpCapabilities;
		private final @JsonProperty("promptCapabilities") PromptCapabilities promptCapabilities;
		private final @UnstableAcpApi @JsonProperty("providers") ProvidersCapabilities providers;
		private final @JsonProperty("_meta") Map<String, Object> meta;

		public AgentCapabilities(@JsonProperty("loadSession") Boolean loadSession, @JsonProperty("sessionCapabilities") SessionCapabilities sessionCapabilities, @JsonProperty("mcpCapabilities") McpCapabilities mcpCapabilities, @JsonProperty("promptCapabilities") PromptCapabilities promptCapabilities, @UnstableAcpApi @JsonProperty("providers") ProvidersCapabilities providers, @JsonProperty("_meta") Map<String, Object> meta) {
			this.loadSession = loadSession;
			this.sessionCapabilities = sessionCapabilities;
			this.mcpCapabilities = mcpCapabilities;
			this.promptCapabilities = promptCapabilities;
			this.providers = providers;
			this.meta = meta;
		}

		public Boolean loadSession() { return loadSession; }
		public SessionCapabilities sessionCapabilities() { return sessionCapabilities; }
		public McpCapabilities mcpCapabilities() { return mcpCapabilities; }
		public PromptCapabilities promptCapabilities() { return promptCapabilities; }
		public ProvidersCapabilities providers() { return providers; }
		public Map<String, Object> meta() { return meta; }

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null || getClass() != o.getClass()) return false;
			AgentCapabilities that = (AgentCapabilities) o;
			return Objects.equals(loadSession, that.loadSession)
							&& Objects.equals(sessionCapabilities, that.sessionCapabilities)
							&& Objects.equals(mcpCapabilities, that.mcpCapabilities)
							&& Objects.equals(promptCapabilities, that.promptCapabilities)
							&& Objects.equals(providers, that.providers)
							&& Objects.equals(meta, that.meta);
		}

		@Override
		public int hashCode() {
			return Objects.hash(loadSession, sessionCapabilities, mcpCapabilities, promptCapabilities, providers, meta);
		}

		@Override
		public String toString() {
			return "AgentCapabilities[" + "loadSession=" + loadSession + ", sessionCapabilities=" + sessionCapabilities + ", mcpCapabilities=" + mcpCapabilities + ", promptCapabilities=" + promptCapabilities + ", providers=" + providers + ", meta=" + meta + "]";
		}

		public AgentCapabilities() {
			this(false, null, new McpCapabilities(), new PromptCapabilities(), null, null);
		}

		public AgentCapabilities(Boolean loadSession, McpCapabilities mcpCapabilities,
				PromptCapabilities promptCapabilities) {
			this(loadSession, null, mcpCapabilities, promptCapabilities, null, null);
		}

		public AgentCapabilities(Boolean loadSession, SessionCapabilities sessionCapabilities,
				McpCapabilities mcpCapabilities, PromptCapabilities promptCapabilities, Map<String, Object> meta) {
			this(loadSession, sessionCapabilities, mcpCapabilities, promptCapabilities, null, meta);
		}
	}

	/**
	 * Session capabilities advertised by the agent. Presence of a non-null field
	 * signals support for that session method.
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public static final class SessionCapabilities {
		private final @JsonProperty("list") Object list;
		private final @JsonProperty("close") Object close;
		private final @JsonProperty("resume") Object resume;
		private final @JsonProperty("delete") Object delete;
		private final @JsonProperty("additionalDirectories") Object additionalDirectories;
		private final @UnstableAcpApi @JsonProperty("fork") Object fork;

		public SessionCapabilities(@JsonProperty("list") Object list, @JsonProperty("close") Object close, @JsonProperty("resume") Object resume, @JsonProperty("delete") Object delete, @JsonProperty("additionalDirectories") Object additionalDirectories, @UnstableAcpApi @JsonProperty("fork") Object fork) {
			this.list = list;
			this.close = close;
			this.resume = resume;
			this.delete = delete;
			this.additionalDirectories = additionalDirectories;
			this.fork = fork;
		}

		public Object list() { return list; }
		public Object close() { return close; }
		public Object resume() { return resume; }
		public Object delete() { return delete; }
		public Object additionalDirectories() { return additionalDirectories; }
		public Object fork() { return fork; }

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null || getClass() != o.getClass()) return false;
			SessionCapabilities that = (SessionCapabilities) o;
			return Objects.equals(list, that.list)
							&& Objects.equals(close, that.close)
							&& Objects.equals(resume, that.resume)
							&& Objects.equals(delete, that.delete)
							&& Objects.equals(additionalDirectories, that.additionalDirectories)
							&& Objects.equals(fork, that.fork);
		}

		@Override
		public int hashCode() {
			return Objects.hash(list, close, resume, delete, additionalDirectories, fork);
		}

		@Override
		public String toString() {
			return "SessionCapabilities[" + "list=" + list + ", close=" + close + ", resume=" + resume + ", delete=" + delete + ", additionalDirectories=" + additionalDirectories + ", fork=" + fork + "]";
		}

		public SessionCapabilities(Object list, Object close, Object resume) {
			this(list, close, resume, null, null, null);
		}

		public SessionCapabilities(Object list, Object close, Object resume, Object fork) {
			this(list, close, resume, null, null, fork);
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

		public Boolean http() { return http; }
		public Boolean sse() { return sse; }

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null || getClass() != o.getClass()) return false;
			McpCapabilities that = (McpCapabilities) o;
			return Objects.equals(http, that.http)
							&& Objects.equals(sse, that.sse);
		}

		@Override
		public int hashCode() {
			return Objects.hash(http, sse);
		}

		@Override
		public String toString() {
			return "McpCapabilities[" + "http=" + http + ", sse=" + sse + "]";
		}

		public McpCapabilities() {
			this(false, false);
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

		public Boolean audio() { return audio; }
		public Boolean embeddedContext() { return embeddedContext; }
		public Boolean image() { return image; }

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null || getClass() != o.getClass()) return false;
			PromptCapabilities that = (PromptCapabilities) o;
			return Objects.equals(audio, that.audio)
							&& Objects.equals(embeddedContext, that.embeddedContext)
							&& Objects.equals(image, that.image);
		}

		@Override
		public int hashCode() {
			return Objects.hash(audio, embeddedContext, image);
		}

		@Override
		public String toString() {
			return "PromptCapabilities[" + "audio=" + audio + ", embeddedContext=" + embeddedContext + ", image=" + image + "]";
		}

		public PromptCapabilities() {
			this(false, false, false);
		}
	}

	// ---------------------------
	// Session Types
	// ---------------------------

	/**
	 * Session information returned by session/list
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public static final class SessionInfo {
		private final @JsonProperty("sessionId") String sessionId;
		private final @JsonProperty("cwd") String cwd;
		private final @JsonProperty("title") String title;
		private final @JsonProperty("updatedAt") String updatedAt;
		private final @JsonProperty("additionalDirectories") List<String> additionalDirectories;
		private final @JsonProperty("_meta") Map<String, Object> meta;

		public SessionInfo(@JsonProperty("sessionId") String sessionId, @JsonProperty("cwd") String cwd, @JsonProperty("title") String title, @JsonProperty("updatedAt") String updatedAt, @JsonProperty("additionalDirectories") List<String> additionalDirectories, @JsonProperty("_meta") Map<String, Object> meta) {
			this.sessionId = sessionId;
			this.cwd = cwd;
			this.title = title;
			this.updatedAt = updatedAt;
			this.additionalDirectories = additionalDirectories;
			this.meta = meta;
		}

		public String sessionId() { return sessionId; }
		public String cwd() { return cwd; }
		public String title() { return title; }
		public String updatedAt() { return updatedAt; }
		public List<String> additionalDirectories() { return additionalDirectories; }
		public Map<String, Object> meta() { return meta; }

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null || getClass() != o.getClass()) return false;
			SessionInfo that = (SessionInfo) o;
			return Objects.equals(sessionId, that.sessionId)
							&& Objects.equals(cwd, that.cwd)
							&& Objects.equals(title, that.title)
							&& Objects.equals(updatedAt, that.updatedAt)
							&& Objects.equals(additionalDirectories, that.additionalDirectories)
							&& Objects.equals(meta, that.meta);
		}

		@Override
		public int hashCode() {
			return Objects.hash(sessionId, cwd, title, updatedAt, additionalDirectories, meta);
		}

		@Override
		public String toString() {
			return "SessionInfo[" + "sessionId=" + sessionId + ", cwd=" + cwd + ", title=" + title + ", updatedAt=" + updatedAt + ", additionalDirectories=" + additionalDirectories + ", meta=" + meta + "]";
		}

		public SessionInfo(String sessionId, String cwd) {
			this(sessionId, cwd, null, null, null, null);
		}
	}

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
			return Objects.equals(currentModeId, that.currentModeId)
							&& Objects.equals(availableModes, that.availableModes);
		}

		@Override
		public int hashCode() {
			return Objects.hash(currentModeId, availableModes);
		}

		@Override
		public String toString() {
			return "SessionModeState[" + "currentModeId=" + currentModeId + ", availableModes=" + availableModes + "]";
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
			return Objects.equals(id, that.id)
							&& Objects.equals(name, that.name)
							&& Objects.equals(description, that.description);
		}

		@Override
		public int hashCode() {
			return Objects.hash(id, name, description);
		}

		@Override
		public String toString() {
			return "SessionMode[" + "id=" + id + ", name=" + name + ", description=" + description + "]";
		}
	}

	/**
	 * Session model state.
	 *
	 * @deprecated The session-model API (including the {@code models} field on session
	 * responses) was removed from the ACP spec (June 2026, v0.13.5). Model selection is now
	 * carried by {@code session/set_config_option} with a {@code "model"} category config
	 * option. Slated for removal in a future release.
	 */
	@Deprecated
	@UnstableAcpApi
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
			return Objects.equals(currentModelId, that.currentModelId)
							&& Objects.equals(availableModels, that.availableModels);
		}

		@Override
		public int hashCode() {
			return Objects.hash(currentModelId, availableModels);
		}

		@Override
		public String toString() {
			return "SessionModelState[" + "currentModelId=" + currentModelId + ", availableModels=" + availableModels + "]";
		}
	}

	/**
	 * Model info.
	 *
	 * @deprecated See {@link SessionModelState}. Slated for removal.
	 */
	@Deprecated
	@UnstableAcpApi
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
			return Objects.equals(modelId, that.modelId)
							&& Objects.equals(name, that.name)
							&& Objects.equals(description, that.description);
		}

		@Override
		public int hashCode() {
			return Objects.hash(modelId, name, description);
		}

		@Override
		public String toString() {
			return "ModelInfo[" + "modelId=" + modelId + ", name=" + name + ", description=" + description + "]";
		}
	}

	// ---------------------------
	// Session Config Types
	// (session/set_config_option is stable; the "boolean" variant remains an unstable extension)
	// ---------------------------

	/**
	 * Session config option - a configurable setting exposed by the agent.
	 * Discriminated by type: "select" (stable) or "boolean" (unstable extension).
	 */
	@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
	@JsonSubTypes({ @JsonSubTypes.Type(value = SessionConfigSelect.class, name = "select"),
			@JsonSubTypes.Type(value = SessionConfigBoolean.class, name = "boolean") })
	public interface SessionConfigOption {

	}

	/**
	 * Select-type config option - a dropdown with named values.
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public static final class SessionConfigSelect implements SessionConfigOption {
		private final @JsonProperty("type") String type;
		private final @JsonProperty("id") String id;
		private final @JsonProperty("name") String name;
		private final @JsonProperty("description") String description;
		private final @JsonProperty("category") String category;
		private final @JsonProperty("currentValue") String currentValue;
		private final @JsonProperty("options") List<SessionConfigSelectOption> options;
		private final @JsonProperty("_meta") Map<String, Object> meta;

		public SessionConfigSelect(@JsonProperty("type") String type, @JsonProperty("id") String id, @JsonProperty("name") String name, @JsonProperty("description") String description, @JsonProperty("category") String category, @JsonProperty("currentValue") String currentValue, @JsonProperty("options") List<SessionConfigSelectOption> options, @JsonProperty("_meta") Map<String, Object> meta) {
			this.type = type;
			this.id = id;
			this.name = name;
			this.description = description;
			this.category = category;
			this.currentValue = currentValue;
			this.options = options;
			this.meta = meta;
		}

		public String type() { return type; }
		public String id() { return id; }
		public String name() { return name; }
		public String description() { return description; }
		public String category() { return category; }
		public String currentValue() { return currentValue; }
		public List<SessionConfigSelectOption> options() { return options; }
		public Map<String, Object> meta() { return meta; }

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null || getClass() != o.getClass()) return false;
			SessionConfigSelect that = (SessionConfigSelect) o;
			return Objects.equals(type, that.type)
							&& Objects.equals(id, that.id)
							&& Objects.equals(name, that.name)
							&& Objects.equals(description, that.description)
							&& Objects.equals(category, that.category)
							&& Objects.equals(currentValue, that.currentValue)
							&& Objects.equals(options, that.options)
							&& Objects.equals(meta, that.meta);
		}

		@Override
		public int hashCode() {
			return Objects.hash(type, id, name, description, category, currentValue, options, meta);
		}

		@Override
		public String toString() {
			return "SessionConfigSelect[" + "type=" + type + ", id=" + id + ", name=" + name + ", description=" + description + ", category=" + category + ", currentValue=" + currentValue + ", options=" + options + ", meta=" + meta + "]";
		}

		public SessionConfigSelect(String id, String name, String currentValue,
				List<SessionConfigSelectOption> options) {
			this("select", id, name, null, null, currentValue, options, null);
		}
	}

	/**
	 * Boolean-type config option - a toggle.
	 *
	 * <p>Unstable: the stable schema only defines the {@code select} config option variant;
	 * {@code boolean} is an SDK extension that may change.
	 */
	@UnstableAcpApi
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public static final class SessionConfigBoolean implements SessionConfigOption {
		private final @JsonProperty("type") String type;
		private final @JsonProperty("id") String id;
		private final @JsonProperty("name") String name;
		private final @JsonProperty("description") String description;
		private final @JsonProperty("category") String category;
		private final @JsonProperty("currentValue") Boolean currentValue;
		private final @JsonProperty("_meta") Map<String, Object> meta;

		public SessionConfigBoolean(@JsonProperty("type") String type, @JsonProperty("id") String id, @JsonProperty("name") String name, @JsonProperty("description") String description, @JsonProperty("category") String category, @JsonProperty("currentValue") Boolean currentValue, @JsonProperty("_meta") Map<String, Object> meta) {
			this.type = type;
			this.id = id;
			this.name = name;
			this.description = description;
			this.category = category;
			this.currentValue = currentValue;
			this.meta = meta;
		}

		public String type() { return type; }
		public String id() { return id; }
		public String name() { return name; }
		public String description() { return description; }
		public String category() { return category; }
		public Boolean currentValue() { return currentValue; }
		public Map<String, Object> meta() { return meta; }

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null || getClass() != o.getClass()) return false;
			SessionConfigBoolean that = (SessionConfigBoolean) o;
			return Objects.equals(type, that.type)
							&& Objects.equals(id, that.id)
							&& Objects.equals(name, that.name)
							&& Objects.equals(description, that.description)
							&& Objects.equals(category, that.category)
							&& Objects.equals(currentValue, that.currentValue)
							&& Objects.equals(meta, that.meta);
		}

		@Override
		public int hashCode() {
			return Objects.hash(type, id, name, description, category, currentValue, meta);
		}

		@Override
		public String toString() {
			return "SessionConfigBoolean[" + "type=" + type + ", id=" + id + ", name=" + name + ", description=" + description + ", category=" + category + ", currentValue=" + currentValue + ", meta=" + meta + "]";
		}

		public SessionConfigBoolean(String id, String name, Boolean currentValue) {
			this("boolean", id, name, null, null, currentValue, null);
		}
	}

	/**
	 * A selectable option within a select-type config option.
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public static final class SessionConfigSelectOption {
		private final @JsonProperty("value") String value;
		private final @JsonProperty("name") String name;
		private final @JsonProperty("description") String description;
		private final @JsonProperty("_meta") Map<String, Object> meta;

		public SessionConfigSelectOption(@JsonProperty("value") String value, @JsonProperty("name") String name, @JsonProperty("description") String description, @JsonProperty("_meta") Map<String, Object> meta) {
			this.value = value;
			this.name = name;
			this.description = description;
			this.meta = meta;
		}

		public String value() { return value; }
		public String name() { return name; }
		public String description() { return description; }
		public Map<String, Object> meta() { return meta; }

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null || getClass() != o.getClass()) return false;
			SessionConfigSelectOption that = (SessionConfigSelectOption) o;
			return Objects.equals(value, that.value)
							&& Objects.equals(name, that.name)
							&& Objects.equals(description, that.description)
							&& Objects.equals(meta, that.meta);
		}

		@Override
		public int hashCode() {
			return Objects.hash(value, name, description, meta);
		}

		@Override
		public String toString() {
			return "SessionConfigSelectOption[" + "value=" + value + ", name=" + name + ", description=" + description + ", meta=" + meta + "]";
		}

		public SessionConfigSelectOption(String value, String name) {
			this(value, name, null, null);
		}
	}

	/**
	 * Config option update - pushed by agent via session/update notification.
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public static final class ConfigOptionUpdate implements SessionUpdate {
		private final @JsonProperty("sessionUpdate") String sessionUpdate;
		private final @JsonProperty("configOptions") List<SessionConfigOption> configOptions;
		private final @JsonProperty("_meta") Map<String, Object> meta;

		public ConfigOptionUpdate(@JsonProperty("sessionUpdate") String sessionUpdate, @JsonProperty("configOptions") List<SessionConfigOption> configOptions, @JsonProperty("_meta") Map<String, Object> meta) {
			this.sessionUpdate = sessionUpdate;
			this.configOptions = configOptions;
			this.meta = meta;
		}

		public String sessionUpdate() { return sessionUpdate; }
		public List<SessionConfigOption> configOptions() { return configOptions; }
		public Map<String, Object> meta() { return meta; }

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null || getClass() != o.getClass()) return false;
			ConfigOptionUpdate that = (ConfigOptionUpdate) o;
			return Objects.equals(sessionUpdate, that.sessionUpdate)
							&& Objects.equals(configOptions, that.configOptions)
							&& Objects.equals(meta, that.meta);
		}

		@Override
		public int hashCode() {
			return Objects.hash(sessionUpdate, configOptions, meta);
		}

		@Override
		public String toString() {
			return "ConfigOptionUpdate[" + "sessionUpdate=" + sessionUpdate + ", configOptions=" + configOptions + ", meta=" + meta + "]";
		}

		public ConfigOptionUpdate(String sessionUpdate, List<SessionConfigOption> configOptions) {
			this(sessionUpdate, configOptions, null);
		}
	}

	// ---------------------------
	// Provider Types (UNSTABLE)
	// ---------------------------

	/**
	 * Provider configuration capabilities advertised by the agent. Presence (a non-null
	 * value, including {@code {}}) signals that the agent supports the {@code providers/*}
	 * methods.
	 */
	@UnstableAcpApi
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public static final class ProvidersCapabilities {
		private final @JsonProperty("_meta") Map<String, Object> meta;

		public ProvidersCapabilities(@JsonProperty("_meta") Map<String, Object> meta) {
			this.meta = meta;
		}

		public Map<String, Object> meta() { return meta; }

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null || getClass() != o.getClass()) return false;
			ProvidersCapabilities that = (ProvidersCapabilities) o;
			return Objects.equals(meta, that.meta);
		}

		@Override
		public int hashCode() {
			return Objects.hash(meta);
		}

		@Override
		public String toString() {
			return "ProvidersCapabilities[" + "meta=" + meta + "]";
		}

		public ProvidersCapabilities() {
			this((Map<String, Object>) null);
		}
	}

	/**
	 * The current effective (non-secret) routing config for a provider.
	 *
	 * <p>{@code apiType} is a well-known {@code LlmProtocol} identifier (for example
	 * {@code "anthropic"}, {@code "openai"}, {@code "azure"}, {@code "vertex"},
	 * {@code "bedrock"}) or a custom string.
	 */
	@UnstableAcpApi
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public static final class ProviderCurrentConfig {
		private final @JsonProperty("apiType") String apiType;
		private final @JsonProperty("baseUrl") String baseUrl;

		public ProviderCurrentConfig(@JsonProperty("apiType") String apiType, @JsonProperty("baseUrl") String baseUrl) {
			this.apiType = apiType;
			this.baseUrl = baseUrl;
		}

		public String apiType() { return apiType; }
		public String baseUrl() { return baseUrl; }

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null || getClass() != o.getClass()) return false;
			ProviderCurrentConfig that = (ProviderCurrentConfig) o;
			return Objects.equals(apiType, that.apiType)
							&& Objects.equals(baseUrl, that.baseUrl);
		}

		@Override
		public int hashCode() {
			return Objects.hash(apiType, baseUrl);
		}

		@Override
		public String toString() {
			return "ProviderCurrentConfig[" + "apiType=" + apiType + ", baseUrl=" + baseUrl + "]";
		}
	}

	/**
	 * Describes a configurable provider returned by {@code providers/list}.
	 *
	 * @param id provider identifier, for example {@code "main"} or {@code "openai"}
	 * @param supported supported {@code LlmProtocol} identifiers for this provider
	 * @param required whether this provider is mandatory and cannot be disabled
	 * @param current current effective non-secret routing config, or {@code null}
	 * @param meta reserved metadata
	 */
	@UnstableAcpApi
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public static final class ProviderInfo {
		private final @JsonProperty("id") String id;
		private final @JsonProperty("supported") List<String> supported;
		private final @JsonProperty("required") Boolean required;
		private final @JsonProperty("current") ProviderCurrentConfig current;
		private final @JsonProperty("_meta") Map<String, Object> meta;

		public ProviderInfo(@JsonProperty("id") String id, @JsonProperty("supported") List<String> supported, @JsonProperty("required") Boolean required, @JsonProperty("current") ProviderCurrentConfig current, @JsonProperty("_meta") Map<String, Object> meta) {
			this.id = id;
			this.supported = supported;
			this.required = required;
			this.current = current;
			this.meta = meta;
		}

		public String id() { return id; }
		public List<String> supported() { return supported; }
		public Boolean required() { return required; }
		public ProviderCurrentConfig current() { return current; }
		public Map<String, Object> meta() { return meta; }

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null || getClass() != o.getClass()) return false;
			ProviderInfo that = (ProviderInfo) o;
			return Objects.equals(id, that.id)
							&& Objects.equals(supported, that.supported)
							&& Objects.equals(required, that.required)
							&& Objects.equals(current, that.current)
							&& Objects.equals(meta, that.meta);
		}

		@Override
		public int hashCode() {
			return Objects.hash(id, supported, required, current, meta);
		}

		@Override
		public String toString() {
			return "ProviderInfo[" + "id=" + id + ", supported=" + supported + ", required=" + required + ", current=" + current + ", meta=" + meta + "]";
		}

		public ProviderInfo(String id, List<String> supported, Boolean required, ProviderCurrentConfig current) {
			this(id, supported, required, current, null);
		}
	}

	/**
	 * Request for {@code providers/list} - lists configurable providers.
	 */
	@UnstableAcpApi
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public static final class ListProvidersRequest {
		private final @JsonProperty("_meta") Map<String, Object> meta;

		public ListProvidersRequest(@JsonProperty("_meta") Map<String, Object> meta) {
			this.meta = meta;
		}

		public Map<String, Object> meta() { return meta; }

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null || getClass() != o.getClass()) return false;
			ListProvidersRequest that = (ListProvidersRequest) o;
			return Objects.equals(meta, that.meta);
		}

		@Override
		public int hashCode() {
			return Objects.hash(meta);
		}

		@Override
		public String toString() {
			return "ListProvidersRequest[" + "meta=" + meta + "]";
		}

		public ListProvidersRequest() {
			this((Map<String, Object>) null);
		}
	}

	/**
	 * Response to {@code providers/list}.
	 */
	@UnstableAcpApi
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public static final class ListProvidersResponse {
		private final @JsonProperty("providers") List<ProviderInfo> providers;
		private final @JsonProperty("_meta") Map<String, Object> meta;

		public ListProvidersResponse(@JsonProperty("providers") List<ProviderInfo> providers, @JsonProperty("_meta") Map<String, Object> meta) {
			this.providers = providers;
			this.meta = meta;
		}

		public List<ProviderInfo> providers() { return providers; }
		public Map<String, Object> meta() { return meta; }

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null || getClass() != o.getClass()) return false;
			ListProvidersResponse that = (ListProvidersResponse) o;
			return Objects.equals(providers, that.providers)
							&& Objects.equals(meta, that.meta);
		}

		@Override
		public int hashCode() {
			return Objects.hash(providers, meta);
		}

		@Override
		public String toString() {
			return "ListProvidersResponse[" + "providers=" + providers + ", meta=" + meta + "]";
		}

		public ListProvidersResponse(List<ProviderInfo> providers) {
			this(providers, null);
		}
	}

	/**
	 * Request for {@code providers/set} - configures a provider.
	 *
	 * @param id provider id to configure
	 * @param apiType protocol type for this provider (an {@code LlmProtocol} identifier)
	 * @param baseUrl base URL for requests sent through this provider
	 * @param headers full headers map for this provider (may include authorization), or {@code null}
	 * @param meta reserved metadata
	 */
	@UnstableAcpApi
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public static final class SetProviderRequest {
		private final @JsonProperty("id") String id;
		private final @JsonProperty("apiType") String apiType;
		private final @JsonProperty("baseUrl") String baseUrl;
		private final @JsonProperty("headers") Map<String, String> headers;
		private final @JsonProperty("_meta") Map<String, Object> meta;

		public SetProviderRequest(@JsonProperty("id") String id, @JsonProperty("apiType") String apiType, @JsonProperty("baseUrl") String baseUrl, @JsonProperty("headers") Map<String, String> headers, @JsonProperty("_meta") Map<String, Object> meta) {
			this.id = id;
			this.apiType = apiType;
			this.baseUrl = baseUrl;
			this.headers = headers;
			this.meta = meta;
		}

		public String id() { return id; }
		public String apiType() { return apiType; }
		public String baseUrl() { return baseUrl; }
		public Map<String, String> headers() { return headers; }
		public Map<String, Object> meta() { return meta; }

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null || getClass() != o.getClass()) return false;
			SetProviderRequest that = (SetProviderRequest) o;
			return Objects.equals(id, that.id)
							&& Objects.equals(apiType, that.apiType)
							&& Objects.equals(baseUrl, that.baseUrl)
							&& Objects.equals(headers, that.headers)
							&& Objects.equals(meta, that.meta);
		}

		@Override
		public int hashCode() {
			return Objects.hash(id, apiType, baseUrl, headers, meta);
		}

		@Override
		public String toString() {
			return "SetProviderRequest[" + "id=" + id + ", apiType=" + apiType + ", baseUrl=" + baseUrl + ", headers=" + headers + ", meta=" + meta + "]";
		}

		public SetProviderRequest(String id, String apiType, String baseUrl) {
			this(id, apiType, baseUrl, null, null);
		}

		public SetProviderRequest(String id, String apiType, String baseUrl, Map<String, String> headers) {
			this(id, apiType, baseUrl, headers, null);
		}
	}

	/**
	 * Response to {@code providers/set}.
	 */
	@UnstableAcpApi
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public static final class SetProviderResponse {
		private final @JsonProperty("_meta") Map<String, Object> meta;

		public SetProviderResponse(@JsonProperty("_meta") Map<String, Object> meta) {
			this.meta = meta;
		}

		public Map<String, Object> meta() { return meta; }

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null || getClass() != o.getClass()) return false;
			SetProviderResponse that = (SetProviderResponse) o;
			return Objects.equals(meta, that.meta);
		}

		@Override
		public int hashCode() {
			return Objects.hash(meta);
		}

		@Override
		public String toString() {
			return "SetProviderResponse[" + "meta=" + meta + "]";
		}

		public SetProviderResponse() {
			this((Map<String, Object>) null);
		}
	}

	/**
	 * Request for {@code providers/disable} - disables a provider by id.
	 */
	@UnstableAcpApi
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public static final class DisableProviderRequest {
		private final @JsonProperty("id") String id;
		private final @JsonProperty("_meta") Map<String, Object> meta;

		public DisableProviderRequest(@JsonProperty("id") String id, @JsonProperty("_meta") Map<String, Object> meta) {
			this.id = id;
			this.meta = meta;
		}

		public String id() { return id; }
		public Map<String, Object> meta() { return meta; }

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null || getClass() != o.getClass()) return false;
			DisableProviderRequest that = (DisableProviderRequest) o;
			return Objects.equals(id, that.id)
							&& Objects.equals(meta, that.meta);
		}

		@Override
		public int hashCode() {
			return Objects.hash(id, meta);
		}

		@Override
		public String toString() {
			return "DisableProviderRequest[" + "id=" + id + ", meta=" + meta + "]";
		}

		public DisableProviderRequest(String id) {
			this(id, null);
		}
	}

	/**
	 * Response to {@code providers/disable}.
	 */
	@UnstableAcpApi
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public static final class DisableProviderResponse {
		private final @JsonProperty("_meta") Map<String, Object> meta;

		public DisableProviderResponse(@JsonProperty("_meta") Map<String, Object> meta) {
			this.meta = meta;
		}

		public Map<String, Object> meta() { return meta; }

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null || getClass() != o.getClass()) return false;
			DisableProviderResponse that = (DisableProviderResponse) o;
			return Objects.equals(meta, that.meta);
		}

		@Override
		public int hashCode() {
			return Objects.hash(meta);
		}

		@Override
		public String toString() {
			return "DisableProviderResponse[" + "meta=" + meta + "]";
		}

		public DisableProviderResponse() {
			this((Map<String, Object>) null);
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

		public String type() { return type; }
		public String text() { return text; }
		public Annotations annotations() { return annotations; }
		public Map<String, Object> meta() { return meta; }

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null || getClass() != o.getClass()) return false;
			TextContent that = (TextContent) o;
			return Objects.equals(type, that.type)
							&& Objects.equals(text, that.text)
							&& Objects.equals(annotations, that.annotations)
							&& Objects.equals(meta, that.meta);
		}

		@Override
		public int hashCode() {
			return Objects.hash(type, text, annotations, meta);
		}

		@Override
		public String toString() {
			return "TextContent[" + "type=" + type + ", text=" + text + ", annotations=" + annotations + ", meta=" + meta + "]";
		}

		public TextContent(String text) {
			this("text", text, null, null);
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
			return Objects.equals(type, that.type)
							&& Objects.equals(data, that.data)
							&& Objects.equals(mimeType, that.mimeType)
							&& Objects.equals(uri, that.uri)
							&& Objects.equals(annotations, that.annotations)
							&& Objects.equals(meta, that.meta);
		}

		@Override
		public int hashCode() {
			return Objects.hash(type, data, mimeType, uri, annotations, meta);
		}

		@Override
		public String toString() {
			return "ImageContent[" + "type=" + type + ", data=" + data + ", mimeType=" + mimeType + ", uri=" + uri + ", annotations=" + annotations + ", meta=" + meta + "]";
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
			return Objects.equals(type, that.type)
							&& Objects.equals(data, that.data)
							&& Objects.equals(mimeType, that.mimeType)
							&& Objects.equals(annotations, that.annotations)
							&& Objects.equals(meta, that.meta);
		}

		@Override
		public int hashCode() {
			return Objects.hash(type, data, mimeType, annotations, meta);
		}

		@Override
		public String toString() {
			return "AudioContent[" + "type=" + type + ", data=" + data + ", mimeType=" + mimeType + ", annotations=" + annotations + ", meta=" + meta + "]";
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
			return Objects.equals(type, that.type)
							&& Objects.equals(name, that.name)
							&& Objects.equals(uri, that.uri)
							&& Objects.equals(title, that.title)
							&& Objects.equals(description, that.description)
							&& Objects.equals(mimeType, that.mimeType)
							&& Objects.equals(size, that.size)
							&& Objects.equals(annotations, that.annotations)
							&& Objects.equals(meta, that.meta);
		}

		@Override
		public int hashCode() {
			return Objects.hash(type, name, uri, title, description, mimeType, size, annotations, meta);
		}

		@Override
		public String toString() {
			return "ResourceLink[" + "type=" + type + ", name=" + name + ", uri=" + uri + ", title=" + title + ", description=" + description + ", mimeType=" + mimeType + ", size=" + size + ", annotations=" + annotations + ", meta=" + meta + "]";
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
			return Objects.equals(type, that.type)
							&& Objects.equals(resource, that.resource)
							&& Objects.equals(annotations, that.annotations)
							&& Objects.equals(meta, that.meta);
		}

		@Override
		public int hashCode() {
			return Objects.hash(type, resource, annotations, meta);
		}

		@Override
		public String toString() {
			return "Resource[" + "type=" + type + ", resource=" + resource + ", annotations=" + annotations + ", meta=" + meta + "]";
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
			return Objects.equals(text, that.text)
							&& Objects.equals(uri, that.uri)
							&& Objects.equals(mimeType, that.mimeType);
		}

		@Override
		public int hashCode() {
			return Objects.hash(text, uri, mimeType);
		}

		@Override
		public String toString() {
			return "TextResourceContents[" + "text=" + text + ", uri=" + uri + ", mimeType=" + mimeType + "]";
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
			return Objects.equals(blob, that.blob)
							&& Objects.equals(uri, that.uri)
							&& Objects.equals(mimeType, that.mimeType);
		}

		@Override
		public int hashCode() {
			return Objects.hash(blob, uri, mimeType);
		}

		@Override
		public String toString() {
			return "BlobResourceContents[" + "blob=" + blob + ", uri=" + uri + ", mimeType=" + mimeType + "]";
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
			return Objects.equals(audience, that.audience)
							&& Objects.equals(priority, that.priority)
							&& Objects.equals(lastModified, that.lastModified);
		}

		@Override
		public int hashCode() {
			return Objects.hash(audience, priority, lastModified);
		}

		@Override
		public String toString() {
			return "Annotations[" + "audience=" + audience + ", priority=" + priority + ", lastModified=" + lastModified + "]";
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
			@JsonSubTypes.Type(value = UsageUpdate.class, name = "usage_update"),
			@JsonSubTypes.Type(value = ConfigOptionUpdate.class, name = "config_option_update") })
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
		private final @JsonProperty("messageId") String messageId;
		private final @JsonProperty("_meta") Map<String, Object> meta;

		public UserMessageChunk(@JsonProperty("sessionUpdate") String sessionUpdate, @JsonProperty("content") ContentBlock content, @JsonProperty("messageId") String messageId, @JsonProperty("_meta") Map<String, Object> meta) {
			this.sessionUpdate = sessionUpdate;
			this.content = content;
			this.messageId = messageId;
			this.meta = meta;
		}

		public String sessionUpdate() { return sessionUpdate; }
		public ContentBlock content() { return content; }
		public String messageId() { return messageId; }
		public Map<String, Object> meta() { return meta; }

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null || getClass() != o.getClass()) return false;
			UserMessageChunk that = (UserMessageChunk) o;
			return Objects.equals(sessionUpdate, that.sessionUpdate)
							&& Objects.equals(content, that.content)
							&& Objects.equals(messageId, that.messageId)
							&& Objects.equals(meta, that.meta);
		}

		@Override
		public int hashCode() {
			return Objects.hash(sessionUpdate, content, messageId, meta);
		}

		@Override
		public String toString() {
			return "UserMessageChunk[" + "sessionUpdate=" + sessionUpdate + ", content=" + content + ", messageId=" + messageId + ", meta=" + meta + "]";
		}

		public UserMessageChunk(String sessionUpdate, ContentBlock content) {
			this(sessionUpdate, content, null, null);
		}

		public UserMessageChunk(String sessionUpdate, ContentBlock content, String messageId) {
			this(sessionUpdate, content, messageId, null);
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
		private final @JsonProperty("messageId") String messageId;
		private final @JsonProperty("_meta") Map<String, Object> meta;

		public AgentMessageChunk(@JsonProperty("sessionUpdate") String sessionUpdate, @JsonProperty("content") ContentBlock content, @JsonProperty("messageId") String messageId, @JsonProperty("_meta") Map<String, Object> meta) {
			this.sessionUpdate = sessionUpdate;
			this.content = content;
			this.messageId = messageId;
			this.meta = meta;
		}

		public String sessionUpdate() { return sessionUpdate; }
		public ContentBlock content() { return content; }
		public String messageId() { return messageId; }
		public Map<String, Object> meta() { return meta; }

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null || getClass() != o.getClass()) return false;
			AgentMessageChunk that = (AgentMessageChunk) o;
			return Objects.equals(sessionUpdate, that.sessionUpdate)
							&& Objects.equals(content, that.content)
							&& Objects.equals(messageId, that.messageId)
							&& Objects.equals(meta, that.meta);
		}

		@Override
		public int hashCode() {
			return Objects.hash(sessionUpdate, content, messageId, meta);
		}

		@Override
		public String toString() {
			return "AgentMessageChunk[" + "sessionUpdate=" + sessionUpdate + ", content=" + content + ", messageId=" + messageId + ", meta=" + meta + "]";
		}

		public AgentMessageChunk(String sessionUpdate, ContentBlock content) {
			this(sessionUpdate, content, null, null);
		}

		public AgentMessageChunk(String sessionUpdate, ContentBlock content, String messageId) {
			this(sessionUpdate, content, messageId, null);
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
		private final @JsonProperty("messageId") String messageId;
		private final @JsonProperty("_meta") Map<String, Object> meta;

		public AgentThoughtChunk(@JsonProperty("sessionUpdate") String sessionUpdate, @JsonProperty("content") ContentBlock content, @JsonProperty("messageId") String messageId, @JsonProperty("_meta") Map<String, Object> meta) {
			this.sessionUpdate = sessionUpdate;
			this.content = content;
			this.messageId = messageId;
			this.meta = meta;
		}

		public String sessionUpdate() { return sessionUpdate; }
		public ContentBlock content() { return content; }
		public String messageId() { return messageId; }
		public Map<String, Object> meta() { return meta; }

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null || getClass() != o.getClass()) return false;
			AgentThoughtChunk that = (AgentThoughtChunk) o;
			return Objects.equals(sessionUpdate, that.sessionUpdate)
							&& Objects.equals(content, that.content)
							&& Objects.equals(messageId, that.messageId)
							&& Objects.equals(meta, that.meta);
		}

		@Override
		public int hashCode() {
			return Objects.hash(sessionUpdate, content, messageId, meta);
		}

		@Override
		public String toString() {
			return "AgentThoughtChunk[" + "sessionUpdate=" + sessionUpdate + ", content=" + content + ", messageId=" + messageId + ", meta=" + meta + "]";
		}

		public AgentThoughtChunk(String sessionUpdate, ContentBlock content) {
			this(sessionUpdate, content, null, null);
		}

		public AgentThoughtChunk(String sessionUpdate, ContentBlock content, String messageId) {
			this(sessionUpdate, content, messageId, null);
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
			return Objects.equals(sessionUpdate, that.sessionUpdate)
							&& Objects.equals(toolCallId, that.toolCallId)
							&& Objects.equals(title, that.title)
							&& Objects.equals(kind, that.kind)
							&& Objects.equals(status, that.status)
							&& Objects.equals(content, that.content)
							&& Objects.equals(locations, that.locations)
							&& Objects.equals(rawInput, that.rawInput)
							&& Objects.equals(rawOutput, that.rawOutput)
							&& Objects.equals(meta, that.meta);
		}

		@Override
		public int hashCode() {
			return Objects.hash(sessionUpdate, toolCallId, title, kind, status, content, locations, rawInput, rawOutput, meta);
		}

		@Override
		public String toString() {
			return "ToolCall[" + "sessionUpdate=" + sessionUpdate + ", toolCallId=" + toolCallId + ", title=" + title + ", kind=" + kind + ", status=" + status + ", content=" + content + ", locations=" + locations + ", rawInput=" + rawInput + ", rawOutput=" + rawOutput + ", meta=" + meta + "]";
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
			return Objects.equals(toolCallId, that.toolCallId)
							&& Objects.equals(title, that.title)
							&& Objects.equals(kind, that.kind)
							&& Objects.equals(status, that.status)
							&& Objects.equals(content, that.content)
							&& Objects.equals(locations, that.locations)
							&& Objects.equals(rawInput, that.rawInput)
							&& Objects.equals(rawOutput, that.rawOutput);
		}

		@Override
		public int hashCode() {
			return Objects.hash(toolCallId, title, kind, status, content, locations, rawInput, rawOutput);
		}

		@Override
		public String toString() {
			return "ToolCallUpdate[" + "toolCallId=" + toolCallId + ", title=" + title + ", kind=" + kind + ", status=" + status + ", content=" + content + ", locations=" + locations + ", rawInput=" + rawInput + ", rawOutput=" + rawOutput + "]";
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
			return Objects.equals(sessionUpdate, that.sessionUpdate)
							&& Objects.equals(toolCallId, that.toolCallId)
							&& Objects.equals(title, that.title)
							&& Objects.equals(kind, that.kind)
							&& Objects.equals(status, that.status)
							&& Objects.equals(content, that.content)
							&& Objects.equals(locations, that.locations)
							&& Objects.equals(rawInput, that.rawInput)
							&& Objects.equals(rawOutput, that.rawOutput)
							&& Objects.equals(meta, that.meta);
		}

		@Override
		public int hashCode() {
			return Objects.hash(sessionUpdate, toolCallId, title, kind, status, content, locations, rawInput, rawOutput, meta);
		}

		@Override
		public String toString() {
			return "ToolCallUpdateNotification[" + "sessionUpdate=" + sessionUpdate + ", toolCallId=" + toolCallId + ", title=" + title + ", kind=" + kind + ", status=" + status + ", content=" + content + ", locations=" + locations + ", rawInput=" + rawInput + ", rawOutput=" + rawOutput + ", meta=" + meta + "]";
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

		public String sessionUpdate() { return sessionUpdate; }
		public List<PlanEntry> entries() { return entries; }
		public Map<String, Object> meta() { return meta; }

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null || getClass() != o.getClass()) return false;
			Plan that = (Plan) o;
			return Objects.equals(sessionUpdate, that.sessionUpdate)
							&& Objects.equals(entries, that.entries)
							&& Objects.equals(meta, that.meta);
		}

		@Override
		public int hashCode() {
			return Objects.hash(sessionUpdate, entries, meta);
		}

		@Override
		public String toString() {
			return "Plan[" + "sessionUpdate=" + sessionUpdate + ", entries=" + entries + ", meta=" + meta + "]";
		}

		public Plan(String sessionUpdate, List<PlanEntry> entries) {
			this(sessionUpdate, entries, null);
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

		public String sessionUpdate() { return sessionUpdate; }
		public List<AvailableCommand> availableCommands() { return availableCommands; }
		public Map<String, Object> meta() { return meta; }

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null || getClass() != o.getClass()) return false;
			AvailableCommandsUpdate that = (AvailableCommandsUpdate) o;
			return Objects.equals(sessionUpdate, that.sessionUpdate)
							&& Objects.equals(availableCommands, that.availableCommands)
							&& Objects.equals(meta, that.meta);
		}

		@Override
		public int hashCode() {
			return Objects.hash(sessionUpdate, availableCommands, meta);
		}

		@Override
		public String toString() {
			return "AvailableCommandsUpdate[" + "sessionUpdate=" + sessionUpdate + ", availableCommands=" + availableCommands + ", meta=" + meta + "]";
		}

		public AvailableCommandsUpdate(String sessionUpdate, List<AvailableCommand> availableCommands) {
			this(sessionUpdate, availableCommands, null);
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

		public String sessionUpdate() { return sessionUpdate; }
		public String currentModeId() { return currentModeId; }
		public Map<String, Object> meta() { return meta; }

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null || getClass() != o.getClass()) return false;
			CurrentModeUpdate that = (CurrentModeUpdate) o;
			return Objects.equals(sessionUpdate, that.sessionUpdate)
							&& Objects.equals(currentModeId, that.currentModeId)
							&& Objects.equals(meta, that.meta);
		}

		@Override
		public int hashCode() {
			return Objects.hash(sessionUpdate, currentModeId, meta);
		}

		@Override
		public String toString() {
			return "CurrentModeUpdate[" + "sessionUpdate=" + sessionUpdate + ", currentModeId=" + currentModeId + ", meta=" + meta + "]";
		}

		public CurrentModeUpdate(String sessionUpdate, String currentModeId) {
			this(sessionUpdate, currentModeId, null);
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
			return Objects.equals(sessionUpdate, that.sessionUpdate)
							&& Objects.equals(used, that.used)
							&& Objects.equals(size, that.size)
							&& Objects.equals(cost, that.cost)
							&& Objects.equals(meta, that.meta);
		}

		@Override
		public int hashCode() {
			return Objects.hash(sessionUpdate, used, size, cost, meta);
		}

		@Override
		public String toString() {
			return "UsageUpdate[" + "sessionUpdate=" + sessionUpdate + ", used=" + used + ", size=" + size + ", cost=" + cost + ", meta=" + meta + "]";
		}

		public UsageUpdate(String sessionUpdate, Long used, Long size) {
			this(sessionUpdate, used, size, null, null);
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
			return Objects.equals(amount, that.amount)
							&& Objects.equals(currency, that.currency);
		}

		@Override
		public int hashCode() {
			return Objects.hash(amount, currency);
		}

		@Override
		public String toString() {
			return "Cost[" + "amount=" + amount + ", currency=" + currency + "]";
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
			return Objects.equals(type, that.type)
							&& Objects.equals(content, that.content);
		}

		@Override
		public int hashCode() {
			return Objects.hash(type, content);
		}

		@Override
		public String toString() {
			return "ToolCallContentBlock[" + "type=" + type + ", content=" + content + "]";
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
			return Objects.equals(type, that.type)
							&& Objects.equals(path, that.path)
							&& Objects.equals(oldText, that.oldText)
							&& Objects.equals(newText, that.newText);
		}

		@Override
		public int hashCode() {
			return Objects.hash(type, path, oldText, newText);
		}

		@Override
		public String toString() {
			return "ToolCallDiff[" + "type=" + type + ", path=" + path + ", oldText=" + oldText + ", newText=" + newText + "]";
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
			return Objects.equals(type, that.type)
							&& Objects.equals(terminalId, that.terminalId);
		}

		@Override
		public int hashCode() {
			return Objects.hash(type, terminalId);
		}

		@Override
		public String toString() {
			return "ToolCallTerminal[" + "type=" + type + ", terminalId=" + terminalId + "]";
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
			return Objects.equals(path, that.path)
							&& Objects.equals(line, that.line);
		}

		@Override
		public int hashCode() {
			return Objects.hash(path, line);
		}

		@Override
		public String toString() {
			return "ToolCallLocation[" + "path=" + path + ", line=" + line + "]";
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

		public String name() { return name; }
		public String version() { return version; }
		public String title() { return title; }

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null || getClass() != o.getClass()) return false;
			Implementation that = (Implementation) o;
			return Objects.equals(name, that.name)
							&& Objects.equals(version, that.version)
							&& Objects.equals(title, that.title);
		}

		@Override
		public int hashCode() {
			return Objects.hash(name, version, title);
		}

		@Override
		public String toString() {
			return "Implementation[" + "name=" + name + ", version=" + version + ", title=" + title + "]";
		}

		public Implementation(String name, String version) {
			this(name, version, null);
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
			return Objects.equals(name, that.name)
							&& Objects.equals(command, that.command)
							&& Objects.equals(args, that.args)
							&& Objects.equals(env, that.env);
		}

		@Override
		public int hashCode() {
			return Objects.hash(name, command, args, env);
		}

		@Override
		public String toString() {
			return "McpServerStdio[" + "name=" + name + ", command=" + command + ", args=" + args + ", env=" + env + "]";
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

		public String name() { return name; }
		public String url() { return url; }
		public List<HttpHeader> headers() { return headers; }

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null || getClass() != o.getClass()) return false;
			McpServerHttp that = (McpServerHttp) o;
			return Objects.equals(name, that.name)
							&& Objects.equals(url, that.url)
							&& Objects.equals(headers, that.headers);
		}

		@Override
		public int hashCode() {
			return Objects.hash(name, url, headers);
		}

		@Override
		public String toString() {
			return "McpServerHttp[" + "name=" + name + ", url=" + url + ", headers=" + headers + "]";
		}


		/**
		 * Returns the transport type identifier.
		 */
		@JsonProperty("type")
		public String type() {
			return "http";
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

		public String name() { return name; }
		public String url() { return url; }
		public List<HttpHeader> headers() { return headers; }

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null || getClass() != o.getClass()) return false;
			McpServerSse that = (McpServerSse) o;
			return Objects.equals(name, that.name)
							&& Objects.equals(url, that.url)
							&& Objects.equals(headers, that.headers);
		}

		@Override
		public int hashCode() {
			return Objects.hash(name, url, headers);
		}

		@Override
		public String toString() {
			return "McpServerSse[" + "name=" + name + ", url=" + url + ", headers=" + headers + "]";
		}


		/**
		 * Returns the transport type identifier.
		 */
		@JsonProperty("type")
		public String type() {
			return "sse";
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
			return Objects.equals(name, that.name)
							&& Objects.equals(value, that.value);
		}

		@Override
		public int hashCode() {
			return Objects.hash(name, value);
		}

		@Override
		public String toString() {
			return "EnvVariable[" + "name=" + name + ", value=" + value + "]";
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
			return Objects.equals(name, that.name)
							&& Objects.equals(value, that.value);
		}

		@Override
		public int hashCode() {
			return Objects.hash(name, value);
		}

		@Override
		public String toString() {
			return "HttpHeader[" + "name=" + name + ", value=" + value + "]";
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
			return Objects.equals(exitCode, that.exitCode)
							&& Objects.equals(signal, that.signal);
		}

		@Override
		public int hashCode() {
			return Objects.hash(exitCode, signal);
		}

		@Override
		public String toString() {
			return "TerminalExitStatus[" + "exitCode=" + exitCode + ", signal=" + signal + "]";
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
			return Objects.equals(id, that.id)
							&& Objects.equals(name, that.name)
							&& Objects.equals(description, that.description);
		}

		@Override
		public int hashCode() {
			return Objects.hash(id, name, description);
		}

		@Override
		public String toString() {
			return "AuthMethod[" + "id=" + id + ", name=" + name + ", description=" + description + "]";
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
			return Objects.equals(optionId, that.optionId)
							&& Objects.equals(name, that.name)
							&& Objects.equals(kind, that.kind);
		}

		@Override
		public int hashCode() {
			return Objects.hash(optionId, name, kind);
		}

		@Override
		public String toString() {
			return "PermissionOption[" + "optionId=" + optionId + ", name=" + name + ", kind=" + kind + "]";
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
			return "PermissionCancelled[" + "outcome=" + outcome + "]";
		}

		public PermissionCancelled() {
			this("cancelled");
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

		public String outcome() { return outcome; }
		public String optionId() { return optionId; }

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null || getClass() != o.getClass()) return false;
			PermissionSelected that = (PermissionSelected) o;
			return Objects.equals(outcome, that.outcome)
							&& Objects.equals(optionId, that.optionId);
		}

		@Override
		public int hashCode() {
			return Objects.hash(outcome, optionId);
		}

		@Override
		public String toString() {
			return "PermissionSelected[" + "outcome=" + outcome + ", optionId=" + optionId + "]";
		}

		public PermissionSelected(String optionId) {
			this("selected", optionId);
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
			return Objects.equals(content, that.content)
							&& Objects.equals(priority, that.priority)
							&& Objects.equals(status, that.status);
		}

		@Override
		public int hashCode() {
			return Objects.hash(content, priority, status);
		}

		@Override
		public String toString() {
			return "PlanEntry[" + "content=" + content + ", priority=" + priority + ", status=" + status + "]";
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
			return Objects.equals(name, that.name)
							&& Objects.equals(description, that.description)
							&& Objects.equals(input, that.input);
		}

		@Override
		public int hashCode() {
			return Objects.hash(name, description, input);
		}

		@Override
		public String toString() {
			return "AvailableCommand[" + "name=" + name + ", description=" + description + ", input=" + input + "]";
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
			return "AvailableCommandInput[" + "hint=" + hint + "]";
		}
	}

}
