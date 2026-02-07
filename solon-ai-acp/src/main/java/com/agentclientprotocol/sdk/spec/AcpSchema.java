/*
 * Copyright 2025-2025 the original author or authors.
 */

package com.agentclientprotocol.sdk.spec;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.TypeRef;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;
import lombok.var;
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
	public static JSONRPCMessage deserializeJsonRpcMessage(McpJsonMapper jsonMapper, String jsonText)
			throws IOException {

		logger.debug("Received JSON message: {}", jsonText);

		var map = jsonMapper.readValue(jsonText, MAP_TYPE_REF);

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
	 */
	@NoArgsConstructor
	@AllArgsConstructor
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public static class JSONRPCRequest implements JSONRPCMessage {
		@JsonProperty("jsonrpc") String jsonrpc;
		@JsonProperty("id") Object id;
		@JsonProperty("method") String method;
		@JsonProperty("params") Object params;

		public JSONRPCRequest(String method, Object id, Object params) {
			this(JSONRPC_VERSION, id, method, params);
		}

		public String jsonrpc() {
			return jsonrpc;
		}

		public Object id() {
			return id;
		}

		public String method() {
			return method;
		}

		public Object params() {
			return params;
		}
	}

	/**
	 * A JSON-RPC notification that does not expect a response.
	 */
	@NoArgsConstructor
	@AllArgsConstructor
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public static class JSONRPCNotification implements JSONRPCMessage {
		@JsonProperty("jsonrpc") String jsonrpc;
		@JsonProperty("method") String method;
		@JsonProperty("params") Object params;


		public JSONRPCNotification(String method, Object params) {
			this(JSONRPC_VERSION, method, params);
		}

		public String jsonrpc() {
			return jsonrpc;
		}

		public String method() {
			return method;
		}

		public Object params() {
			return params;
		}
	}

	/**
	 * A JSON-RPC response to a request.
	 */
	@NoArgsConstructor
	@AllArgsConstructor
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public static class JSONRPCResponse implements JSONRPCMessage {
		@JsonProperty("jsonrpc") String jsonrpc;
		@JsonProperty("id") Object id;
		@JsonProperty("result") Object result;
		@JsonProperty("error") JSONRPCError error;

		public String jsonrpc() {
			return jsonrpc;
		}

		public Object id() {
			return id;
		}

		public Object result() {
			return result;
		}

		public JSONRPCError error() {
			return error;
		}
	}

	@NoArgsConstructor
	@AllArgsConstructor
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public static class JSONRPCError {
		@JsonProperty("code") int code;
		@JsonProperty("message") String message;
		@JsonProperty("data") Object data;

		public int code() {
			return code;
		}

		public String message() {
			return message;
		}

		public Object data() {
			return data;
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
	@NoArgsConstructor
	@AllArgsConstructor
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public static class InitializeRequest {
		@JsonProperty("protocolVersion") Integer protocolVersion;
		@JsonProperty("clientCapabilities") ClientCapabilities clientCapabilities;
		@JsonProperty("_meta") Map<String, Object> meta;

		public InitializeRequest(Integer protocolVersion, ClientCapabilities clientCapabilities) {
			this(protocolVersion, clientCapabilities, null);
		}

		public Integer protocolVersion() {
			return protocolVersion;
		}

		public ClientCapabilities clientCapabilities() {
			return clientCapabilities;
		}

		public Map<String, Object> meta() {
			return meta;
		}
	}

	/**
	 * Initialize response - returns agent capabilities and auth methods
	 */
	@NoArgsConstructor
	@AllArgsConstructor
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public static class InitializeResponse {
		@JsonProperty("protocolVersion") Integer protocolVersion;
		@JsonProperty("agentCapabilities") AgentCapabilities agentCapabilities;
		@JsonProperty("authMethods") List<AuthMethod> authMethods;
		@JsonProperty("_meta") Map<String, Object> meta;

		public InitializeResponse(Integer protocolVersion, AgentCapabilities agentCapabilities,
				List<AuthMethod> authMethods) {
			this(protocolVersion, agentCapabilities, authMethods, null);
		}

		public Integer protocolVersion() {
			return protocolVersion;
		}

		public AgentCapabilities agentCapabilities() {
			return agentCapabilities;
		}

		public List<AuthMethod> authMethods() {
			return authMethods;
		}

		public Map<String, Object> meta() {
			return meta;
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
	@NoArgsConstructor
	@AllArgsConstructor
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public static class AuthenticateRequest {
		@JsonProperty("methodId") String methodId;

		public String methodId() {
			return methodId;
		}
	}

	/**
	 * Authenticate response
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public static class AuthenticateResponse {
	}

	/**
	 * Create new session request
	 */
	@NoArgsConstructor
	@AllArgsConstructor
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public static class NewSessionRequest {
		@JsonProperty("cwd") String cwd;
		@JsonProperty("mcpServers") List<McpServer> mcpServers;
		@JsonProperty("_meta") Map<String, Object> meta;

		public NewSessionRequest(String cwd, List<McpServer> mcpServers) {
			this(cwd, mcpServers, null);
		}

		public String cwd() {
			return cwd;
		}

		public List<McpServer> mcpServers() {
			return mcpServers;
		}

		public Map<String, Object> meta() {
			return meta;
		}
	}

	/**
	 * Create new session response
	 */
	@NoArgsConstructor
	@AllArgsConstructor
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public static class NewSessionResponse {
		@JsonProperty("sessionId") String sessionId;
		@JsonProperty("modes") SessionModeState modes;
		@JsonProperty("models") SessionModelState models;
		@JsonProperty("_meta") Map<String, Object> meta;

		public NewSessionResponse(String sessionId, SessionModeState modes, SessionModelState models) {
			this(sessionId, modes, models, null);
		}

		public String sessionId() {
			return sessionId;
		}

		public SessionModeState modes() {
			return modes;
		}

		public SessionModelState models() {
			return models;
		}

		public Map<String, Object> meta() {
			return meta;
		}
	}

	/**
	 * Load existing session request
	 */
	@NoArgsConstructor
	@AllArgsConstructor
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public static class LoadSessionRequest {
		@JsonProperty("sessionId") String sessionId;
		@JsonProperty("cwd") String cwd;
		@JsonProperty("mcpServers") List<McpServer> mcpServers;
		@JsonProperty("_meta") Map<String, Object> meta;

		public LoadSessionRequest(String sessionId, String cwd, List<McpServer> mcpServers) {
			this(sessionId, cwd, mcpServers, null);
		}

		public String sessionId() {
			return sessionId;
		}

		public String cwd() {
			return cwd;
		}

		public List<McpServer> mcpServers() {
			return mcpServers;
		}

		public Map<String, Object> meta() {
			return meta;
		}
	}

	/**
	 * Load session response
	 */
	@NoArgsConstructor
	@AllArgsConstructor
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public static class LoadSessionResponse {
		@JsonProperty("modes") SessionModeState modes;
		@JsonProperty("models") SessionModelState models;
		@JsonProperty("_meta") Map<String, Object> meta;

		public LoadSessionResponse(SessionModeState modes, SessionModelState models) {
			this(modes, models, null);
		}

		public SessionModeState modes() {
			return modes;
		}

		public SessionModelState models() {
			return models;
		}

		public Map<String, Object> meta() {
			return meta;
		}
	}

	/**
	 * Prompt request - sends user message to agent
	 */
	@NoArgsConstructor
	@AllArgsConstructor
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public static class PromptRequest {
		@JsonProperty("sessionId") String sessionId;
		@JsonProperty("prompt") List<ContentBlock> prompt;
		@JsonProperty("_meta") Map<String, Object> meta;

		public PromptRequest(String sessionId, List<ContentBlock> prompt) {
			this(sessionId, prompt, null);
		}

		public String sessionId() {
			return sessionId;
		}

		public List<ContentBlock> prompt() {
			return prompt;
		}

		public Map<String, Object> meta() {
			return meta;
		}
	}

	/**
	 * Prompt response - indicates why agent stopped
	 */
	@NoArgsConstructor
	@AllArgsConstructor
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public static class PromptResponse {
		@JsonProperty("stopReason") StopReason stopReason;
		@JsonProperty("_meta") Map<String, Object> meta;

		public PromptResponse(StopReason stopReason) {
			this(stopReason, null);
		}

		public StopReason stopReason() {
			return stopReason;
		}

		public Map<String, Object> meta() {
			return meta;
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
	@NoArgsConstructor
	@AllArgsConstructor
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public static class SetSessionModeRequest {
		@JsonProperty("sessionId") String sessionId;
		@JsonProperty("modeId") String modeId;

		public String sessionId() {
			return sessionId;
		}

		public String modeId() {
			return modeId;
		}
	}

	/**
	 * Set session mode response
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public static class SetSessionModeResponse {
	}

	/**
	 * Set session model request (UNSTABLE)
	 */
	@NoArgsConstructor
	@AllArgsConstructor
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public static class SetSessionModelRequest {
		@JsonProperty("sessionId") String sessionId;
		@JsonProperty("modelId") String modelId;

		public String sessionId() {
			return sessionId;
		}

		public String modelId() {
			return modelId;
		}
	}

	/**
	 * Set session model response (UNSTABLE)
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public static class SetSessionModelResponse {
	}

	/**
	 * Cancel notification - cancels ongoing operations
	 */
	@NoArgsConstructor
	@AllArgsConstructor
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public static class CancelNotification {
		@JsonProperty("sessionId") String sessionId;

		public String sessionId() {
			return sessionId;
		}
	}

	// ---------------------------
	// Client Methods (Agent → Client)
	// ---------------------------

	/**
	 * Request permission from user
	 */
	@NoArgsConstructor
	@AllArgsConstructor
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public static class RequestPermissionRequest {
		@JsonProperty("sessionId") String sessionId;
		@JsonProperty("toolCall") ToolCallUpdate toolCall;
		@JsonProperty("options") List<PermissionOption> options;

		public String sessionId() {
			return sessionId;
		}

		public ToolCallUpdate toolCall() {
			return toolCall;
		}

		public List<PermissionOption> options() {
			return options;
		}
	}

	/**
	 * Permission response from user
	 */
	@NoArgsConstructor
	@AllArgsConstructor
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public static class RequestPermissionResponse {
		@JsonProperty("outcome") RequestPermissionOutcome outcome;

		public RequestPermissionOutcome outcome() {
			return outcome;
		}
	}

	/**
	 * Session update notification - real-time progress
	 */
	@NoArgsConstructor
	@AllArgsConstructor
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public static class SessionNotification {
		@JsonProperty("sessionId") String sessionId;
		@JsonProperty("update") SessionUpdate update;
		@JsonProperty("_meta") Map<String, Object> meta;

		public SessionNotification(String sessionId, SessionUpdate update) {
			this(sessionId, update, null);
		}

		public String sessionId() {
			return sessionId;
		}

		public SessionUpdate update() {
			return update;
		}

		public Map<String, Object> meta() {
			return meta;
		}
	}

	/**
	 * Read text file request
	 */
	@NoArgsConstructor
	@AllArgsConstructor
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public static class ReadTextFileRequest {
		@JsonProperty("sessionId") String sessionId;
		@JsonProperty("path") String path;
		@JsonProperty("line") Integer line;
		@JsonProperty("limit") Integer limit;

		public String sessionId() {
			return sessionId;
		}

		public String path() {
			return path;
		}

		public Integer line() {
			return line;
		}

		public Integer limit() {
			return limit;
		}
	}

	/**
	 * Read text file response
	 */
	@NoArgsConstructor
	@AllArgsConstructor
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public static class ReadTextFileResponse {
		@JsonProperty("content") String content;

		public String content() {
			return content;
		}
	}

	/**
	 * Write text file request
	 */
	@NoArgsConstructor
	@AllArgsConstructor
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public static class WriteTextFileRequest {
		@JsonProperty("sessionId") String sessionId;
		@JsonProperty("path") String path;
		@JsonProperty("content") String content;

		public String sessionId() {
			return sessionId;
		}

		public String path() {
			return path;
		}

		public String content() {
			return content;
		}
	}

	/**
	 * Write text file response
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public static class WriteTextFileResponse {
	}

	/**
	 * Create terminal request
	 */
	@NoArgsConstructor
	@AllArgsConstructor
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public static class CreateTerminalRequest {
		@JsonProperty("sessionId") String sessionId;
		@JsonProperty("command") String command;
		@JsonProperty("args") List<String> args;
		@JsonProperty("cwd") String cwd;
		@JsonProperty("env") List<EnvVariable> env;
		@JsonProperty("outputByteLimit") Long outputByteLimit;

		public String sessionId() {
			return sessionId;
		}

		public String command() {
			return command;
		}

		public List<String> args() {
			return args;
		}

		public String cwd() {
			return cwd;
		}

		public List<EnvVariable> env() {
			return env;
		}

		public Long outputByteLimit() {
			return outputByteLimit;
		}
	}

	/**
	 * Create terminal response
	 */
	@NoArgsConstructor
	@AllArgsConstructor
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public static class CreateTerminalResponse {
		@JsonProperty("terminalId") String terminalId;

		public String terminalId() {
			return terminalId;
		}
	}

	/**
	 * Terminal output request
	 */
	@NoArgsConstructor
	@AllArgsConstructor
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public static class TerminalOutputRequest {
		@JsonProperty("sessionId") String sessionId;
		@JsonProperty("terminalId") String terminalId;

		public String sessionId() {
			return sessionId;
		}

		public String terminalId() {
			return terminalId;
		}
	}

	/**
	 * Terminal output response
	 */
	@NoArgsConstructor
	@AllArgsConstructor
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public static class TerminalOutputResponse {
		@JsonProperty("output") String output;
		@JsonProperty("truncated") boolean truncated;
		@JsonProperty("exitStatus") TerminalExitStatus exitStatus;

		public String output() {
			return output;
		}

		public boolean truncated() {
			return truncated;
		}

		public TerminalExitStatus exitStatus() {
			return exitStatus;
		}
	}

	/**
	 * Release terminal request
	 */
	@NoArgsConstructor
	@AllArgsConstructor
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public static class ReleaseTerminalRequest {
		@JsonProperty("sessionId") String sessionId;
		@JsonProperty("terminalId") String terminalId;

		public String sessionId() {
			return sessionId;
		}

		public String terminalId() {
			return terminalId;
		}
	}

	/**
	 * Release terminal response
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public static class ReleaseTerminalResponse {
	}

	/**
	 * Wait for terminal exit request
	 */
	@NoArgsConstructor
	@AllArgsConstructor
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public static class WaitForTerminalExitRequest {
		@JsonProperty("sessionId") String sessionId;
		@JsonProperty("terminalId") String terminalId;

		public String sessionId() {
			return sessionId;
		}

		public String terminalId() {
			return terminalId;
		}
	}

	/**
	 * Wait for terminal exit response
	 */
	@NoArgsConstructor
	@AllArgsConstructor
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public static class WaitForTerminalExitResponse {
		@JsonProperty("exitCode") Integer exitCode;
		@JsonProperty("signal") String signal;

		public Integer exitCode() {
			return exitCode;
		}

		public String signal() {
			return signal;
		}
	}

	/**
	 * Kill terminal request
	 */
	@NoArgsConstructor
	@AllArgsConstructor
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public static class KillTerminalCommandRequest {
		@JsonProperty("sessionId") String sessionId;
		@JsonProperty("terminalId") String terminalId;

		public  String sessionId() {
			return sessionId;
		}

		public String terminalId() {
			return terminalId;
		}
	}

	/**
	 * Kill terminal response
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public static class KillTerminalCommandResponse {
	}

	// ---------------------------
	// Capabilities
	// ---------------------------

	/**
	 * Client capabilities
	 */
	@AllArgsConstructor
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public static class ClientCapabilities {
		@JsonProperty("fs") FileSystemCapability fs;
		@JsonProperty("terminal") Boolean terminal;
		@JsonProperty("_meta") Map<String, Object> meta;

		public ClientCapabilities() {
			this(new FileSystemCapability(), false, null);
		}

		public ClientCapabilities(FileSystemCapability fs, Boolean terminal) {
			this(fs, terminal, null);
		}

		public FileSystemCapability fs() {
			return fs;
		}

		public Boolean terminal() {
			return terminal;
		}

		public Map<String, Object> meta() {
			return meta;
		}
	}

	/**
	 * File system capabilities
	 */
	@AllArgsConstructor
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public static class FileSystemCapability {
		@JsonProperty("readTextFile") Boolean readTextFile;
		@JsonProperty("writeTextFile") Boolean writeTextFile;

		public FileSystemCapability() {
			this(false, false);
		}

		public  Boolean readTextFile() {
			return readTextFile;
		}

		public Boolean writeTextFile() {
			return writeTextFile;
		}
	}

	/**
	 * Agent capabilities
	 */
	@AllArgsConstructor
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public static class AgentCapabilities {
		@JsonProperty("loadSession") Boolean loadSession;
		@JsonProperty("mcpCapabilities") McpCapabilities mcpCapabilities;
		@JsonProperty("promptCapabilities") PromptCapabilities promptCapabilities;
		@JsonProperty("_meta") Map<String, Object> meta;

		public AgentCapabilities() {
			this(false, new McpCapabilities(), new PromptCapabilities(), null);
		}

		public AgentCapabilities(Boolean loadSession, McpCapabilities mcpCapabilities,
				PromptCapabilities promptCapabilities) {
			this(loadSession, mcpCapabilities, promptCapabilities, null);
		}

		public Boolean loadSession() {
			return loadSession;
		}

		public McpCapabilities mcpCapabilities() {
			return mcpCapabilities;
		}

		public PromptCapabilities promptCapabilities() {
			return promptCapabilities;
		}

		public Map<String, Object> meta() {
			return meta;
		}
	}

	/**
	 * MCP capabilities supported by agent
	 */
	@AllArgsConstructor
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public static class McpCapabilities {
		@JsonProperty("http") Boolean http;
		@JsonProperty("sse") Boolean sse;
		public McpCapabilities() {
			this(false, false);
		}

		public Boolean http() {
			return http;
		}

		public Boolean sse() {
			return sse;
		}
	}

	/**
	 * Prompt capabilities
	 */
	@AllArgsConstructor
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public static class PromptCapabilities {
		@JsonProperty("audio") Boolean audio;
		@JsonProperty("embeddedContext") Boolean embeddedContext;
		@JsonProperty("image") Boolean image;

		public PromptCapabilities() {
			this(false, false, false);
		}

		public Boolean audio() {
			return audio;
		}

		public Boolean embeddedContext() {
			return embeddedContext;
		}

		public Boolean image() {
			return image;
		}
	}

	// ---------------------------
	// Session Types
	// ---------------------------

	/**
	 * Session mode state
	 */
	@NoArgsConstructor
	@AllArgsConstructor
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public static class SessionModeState {
		@JsonProperty("currentModeId") String currentModeId;
		@JsonProperty("availableModes") List<SessionMode> availableModes;

		public String currentModeId() {
			return currentModeId;
		}

		public List<SessionMode> availableModes() {
			return availableModes;
		}
	}

	/**
	 * Session mode
	 */
	@NoArgsConstructor
	@AllArgsConstructor
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public static class SessionMode {
		@JsonProperty("id") String id;
		@JsonProperty("name") String name;
		@JsonProperty("description") String description;

		public String id() {
			return id;
		}

		public String name() {
			return name;
		}

		public String description() {
			return description;
		}
	}

	/**
	 * Session model state (UNSTABLE)
	 */
	@NoArgsConstructor
	@AllArgsConstructor
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public static class SessionModelState {
		@JsonProperty("currentModelId") String currentModelId;
		@JsonProperty("availableModels") List<ModelInfo> availableModels;

		public String currentModelId() {
			return currentModelId;
		}

		public List<ModelInfo> availableModels() {
			return availableModels;
		}
	}

	/**
	 * Model info (UNSTABLE)
	 */
	@NoArgsConstructor
	@AllArgsConstructor
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public static class ModelInfo {
		@JsonProperty("modelId") String modelId;
		@JsonProperty("name") String name;
		@JsonProperty("description") String description;

		public String modelId() {
			return modelId;
		}

		public String name() {
			return name;
		}

		public String description() {
			return description;
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
	@NoArgsConstructor
	@AllArgsConstructor
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public static class TextContent implements ContentBlock {
		@JsonProperty("type") String type;
		@JsonProperty("text") String text;
		@JsonProperty("annotations") Annotations annotations;
		@JsonProperty("_meta") Map<String, Object> meta;

		public TextContent(String text) {
			this("text", text, null, null);
		}

		public String type() {
			return type;
		}

		public String text() {
			return text;
		}

		public Annotations annotations() {
			return annotations;
		}

		public Map<String, Object> meta() {
			return meta;
		}
	}

	/**
	 * Image content
	 */
	@NoArgsConstructor
	@AllArgsConstructor
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public static class ImageContent implements ContentBlock {
		@JsonProperty("type") String type;
		@JsonProperty("data") String data;
		@JsonProperty("mimeType") String mimeType;
		@JsonProperty("uri") String uri;
		@JsonProperty("annotations") Annotations annotations;
		@JsonProperty("_meta") Map<String, Object> meta;

		public String type() {
			return type;
		}

		public String data() {
			return data;
		}

		public String mimeType() {
			return mimeType;
		}

		public String uri() {
			return uri;
		}

		public Annotations annotations() {
			return annotations;
		}

		public Map<String, Object> meta() {
			return meta;
		}
	}

	/**
	 * Audio content
	 */
	@NoArgsConstructor
	@AllArgsConstructor
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public static class AudioContent implements ContentBlock {
		@JsonProperty("type") String type;
		@JsonProperty("data") String data;
		@JsonProperty("mimeType") String mimeType;
		@JsonProperty("annotations") Annotations annotations;
		@JsonProperty("_meta") Map<String, Object> meta;

		public String type() {
			return type;
		}

		public String data() {
			return data;
		}

		public String mimeType() {
			return mimeType;
		}

		public Annotations annotations() {
			return annotations;
		}

		public Map<String, Object> meta() {
			return meta;
		}
	}

	/**
	 * Resource link
	 */
	@NoArgsConstructor
	@AllArgsConstructor
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public static class ResourceLink implements ContentBlock {
		@JsonProperty("type") String type;
		@JsonProperty("name") String name;
		@JsonProperty("uri") String uri;
		@JsonProperty("title") String title;
		@JsonProperty("description") String description;
		@JsonProperty("mimeType") String mimeType;
		@JsonProperty("size") Long size;
		@JsonProperty("annotations") Annotations annotations;
		@JsonProperty("_meta") Map<String, Object> meta;

		public String type() {
			return type;
		}

		public String name() {
			return name;
		}

		public String uri() {
			return uri;
		}

		public String title() {
			return title;
		}

		public String description() {
			return description;
		}

		public String mimeType() {
			return mimeType;
		}

		public Long size() {
			return size;
		}

		public Annotations annotations() {
			return annotations;
		}

		public Map<String, Object> meta() {
			return meta;
		}
	}

	/**
	 * Embedded resource
	 */
	@NoArgsConstructor
	@AllArgsConstructor
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public static class Resource implements ContentBlock {
		@JsonProperty("type") String type;
		@JsonProperty("resource") EmbeddedResourceResource resource;
		@JsonProperty("annotations") Annotations annotations;
		@JsonProperty("_meta") Map<String, Object> meta;

		public String type() {
			return type;
		}

		public EmbeddedResourceResource resource() {
			return resource;
		}

		public Annotations annotations() {
			return annotations;
		}

		public Map<String, Object> meta() {
			return meta;
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
	@NoArgsConstructor
	@AllArgsConstructor
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public static class TextResourceContents implements EmbeddedResourceResource {
		@JsonProperty("text") String text;
		@JsonProperty("uri") String uri;
		@JsonProperty("mimeType") String mimeType;

		public String text() {
			return text;
		}

		public String uri() {
			return uri;
		}

		public String mimeType() {
			return mimeType;
		}
	}

	/**
	 * Blob resource contents
	 */
	@NoArgsConstructor
	@AllArgsConstructor
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public static class BlobResourceContents implements EmbeddedResourceResource {
		@JsonProperty("data") String data;
		@JsonProperty("uri") String uri;
		@JsonProperty("mimeType") String mimeType;

		public String data() {
			return data;
		}

		public String uri() {
			return uri;
		}

		public String mimeType() {
			return mimeType;
		}
	}

	/**
	 * Annotations for content
	 */
	@NoArgsConstructor
	@AllArgsConstructor
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public static class Annotations {
		@JsonProperty("audience") List<Role> audience;
		@JsonProperty("priority") Double priority;
		@JsonProperty("lastModified") String lastModified;

		public List<Role> audience() {
			return audience;
		}

		public Double priority() {
			return priority;
		}

		public String lastModified() {
			return lastModified;
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
			@JsonSubTypes.Type(value = CurrentModeUpdate.class, name = "current_mode_update") })
	public interface SessionUpdate {

	}

	/**
	 * User message chunk
	 */
	@NoArgsConstructor
	@AllArgsConstructor
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public static class UserMessageChunk implements SessionUpdate {
		@JsonProperty("sessionUpdate") String sessionUpdate;
		@JsonProperty("content") ContentBlock content;
		@JsonProperty("_meta") Map<String, Object> meta;

		public UserMessageChunk(String sessionUpdate, ContentBlock content) {
			this(sessionUpdate, content, null);
		}

		public String sessionUpdate(){
			return sessionUpdate;
		}

		public ContentBlock content(){
			return content;
		}

		public Map<String, Object> meta(){
			return meta;
		}
	}

	/**
	 * Agent message chunk
	 */
	@NoArgsConstructor
	@AllArgsConstructor
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public static class AgentMessageChunk implements SessionUpdate {
		@JsonProperty("sessionUpdate") String sessionUpdate;
		@JsonProperty("content") ContentBlock content;
		@JsonProperty("_meta") Map<String, Object> meta;

		public AgentMessageChunk(String sessionUpdate, ContentBlock content) {
			this(sessionUpdate, content, null);
		}

		public String sessionUpdate(){
			return sessionUpdate;
		}

		public ContentBlock content(){
			return content;
		}

		public Map<String, Object> meta(){
			return meta;
		}
	}

	/**
	 * Agent thought chunk
	 */
	@NoArgsConstructor
	@AllArgsConstructor
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public static class AgentThoughtChunk implements SessionUpdate {
		@JsonProperty("sessionUpdate") String sessionUpdate;
		@JsonProperty("content") ContentBlock content;
		@JsonProperty("_meta") Map<String, Object> meta;

		public AgentThoughtChunk(String sessionUpdate, ContentBlock content) {
			this(sessionUpdate, content, null);
		}

		public String sessionUpdate(){
			return sessionUpdate;
		}

		public ContentBlock content(){
			return content;
		}

		public Map<String, Object> meta(){
			return meta;
		}
	}

	/**
	 * Tool call
	 */
	@NoArgsConstructor
	@AllArgsConstructor
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public static class ToolCall implements SessionUpdate {
		@JsonProperty("sessionUpdate") String sessionUpdate;
		@JsonProperty("toolCallId") String toolCallId;
		@JsonProperty("title") String title;
		@JsonProperty("kind") ToolKind kind;
		@JsonProperty("status") ToolCallStatus status;
		@JsonProperty("content") List<ToolCallContent> content;
		@JsonProperty("locations") List<ToolCallLocation> locations;
		@JsonProperty("rawInput") Object rawInput;
		@JsonProperty("rawOutput") Object rawOutput;
		@JsonProperty("_meta") Map<String, Object> meta;

		public String sessionUpdate(){
			return sessionUpdate;
		}

		public String toolCallId(){
			return toolCallId;
		}

		public String title(){
			return title;
		}

		public ToolKind kind(){
			return kind;
		}

		public ToolCallStatus status(){
			return status;
		}

		public List<ToolCallContent> content(){
			return content;
		}

		public List<ToolCallLocation> locations(){
			return locations;
		}

		public Object rawInput(){
			return rawInput;
		}

		public Object rawOutput(){
			return rawOutput;
		}

		public Map<String, Object> meta(){
			return meta;
		}
	}

	/**
	 * Tool call update
	 */
	@NoArgsConstructor
	@AllArgsConstructor
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public static class ToolCallUpdate {
		@JsonProperty("toolCallId") String toolCallId;
		@JsonProperty("title") String title;
		@JsonProperty("kind") ToolKind kind;
		@JsonProperty("status") ToolCallStatus status;
		@JsonProperty("content") List<ToolCallContent> content;
		@JsonProperty("locations") List<ToolCallLocation> locations;
		@JsonProperty("rawInput") Object rawInput;
		@JsonProperty("rawOutput") Object rawOutput;

		public String toolCallId(){
			return toolCallId;
		}

		public String title(){
			return title;
		}

		public ToolKind kind(){
			return kind;
		}

		public ToolCallStatus status(){
			return status;
		}

		public List<ToolCallContent> content(){
			return content;
		}

		public List<ToolCallLocation> locations(){
			return locations;
		}

		public Object rawInput(){
			return rawInput;
		}

		public Object rawOutput(){
			return rawOutput;
		}
	}

	/**
	 * Tool call update notification
	 */
	@NoArgsConstructor
	@AllArgsConstructor
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public static class ToolCallUpdateNotification implements SessionUpdate {
		@JsonProperty("sessionUpdate") String sessionUpdate;
		@JsonProperty("toolCallId") String toolCallId;
		@JsonProperty("title") String title;
		@JsonProperty("kind") ToolKind kind;
		@JsonProperty("status") ToolCallStatus status;
		@JsonProperty("content") List<ToolCallContent> content;
		@JsonProperty("locations") List<ToolCallLocation> locations;
		@JsonProperty("rawInput") Object rawInput;
		@JsonProperty("rawOutput") Object rawOutput;
		@JsonProperty("_meta") Map<String, Object> meta;

		public String sessionUpdate(){
			return sessionUpdate;
		}

		public String toolCallId(){
			return toolCallId;
		}

		public String title(){
			return title;
		}

		public ToolKind kind(){
			return kind;
		}

		public ToolCallStatus status(){
			return status;
		}

		public List<ToolCallContent> content(){
			return content;
		}

		public List<ToolCallLocation> locations(){
			return locations;
		}

		public Object rawInput(){
			return rawInput;
		}

		public Object rawOutput(){
			return rawOutput;
		}

		public Map<String, Object> meta(){
			return meta;
		}
	}

	/**
	 * Plan update
	 */
	@NoArgsConstructor
	@AllArgsConstructor
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public static class Plan implements SessionUpdate {
		@JsonProperty("sessionUpdate") String sessionUpdate;
		@JsonProperty("entries") List<PlanEntry> entries;
		@JsonProperty("_meta") Map<String, Object> meta;

		public Plan(String sessionUpdate, List<PlanEntry> entries) {
			this(sessionUpdate, entries, null);
		}

		public String sessionUpdate(){
			return sessionUpdate;
		}

		public List<PlanEntry> entries(){
			return entries;
		}

		public Map<String, Object> meta(){
			return meta;
		}
	}

	/**
	 * Available commands update
	 */
	@NoArgsConstructor
	@AllArgsConstructor
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public static class AvailableCommandsUpdate implements SessionUpdate {
		@JsonProperty("sessionUpdate") String sessionUpdate;
		@JsonProperty("availableCommands") List<AvailableCommand> availableCommands;
		@JsonProperty("_meta") Map<String, Object> meta;

		public AvailableCommandsUpdate(String sessionUpdate, List<AvailableCommand> availableCommands) {
			this(sessionUpdate, availableCommands, null);
		}

		public String sessionUpdate(){
			return sessionUpdate;
		}

		public List<AvailableCommand> availableCommands(){
			return availableCommands;
		}

		public Map<String, Object> meta(){
			return meta;
		}
	}

	/**
	 * Current mode update
	 */
	@NoArgsConstructor
	@AllArgsConstructor
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public static class CurrentModeUpdate implements SessionUpdate {
		@JsonProperty("sessionUpdate") String sessionUpdate;
		@JsonProperty("currentModeId") String currentModeId;
		@JsonProperty("_meta") Map<String, Object> meta;

		public CurrentModeUpdate(String sessionUpdate, String currentModeId) {
			this(sessionUpdate, currentModeId, null);
		}

		public String sessionUpdate(){
			return sessionUpdate;
		}

		public String currentModeId(){
			return currentModeId;
		}

		public Map<String, Object> meta(){
			return meta;
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
	@NoArgsConstructor
	@AllArgsConstructor
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public static class ToolCallContentBlock implements ToolCallContent {
		@JsonProperty("type") String type;
		@JsonProperty("content") ContentBlock content;

		public String type(){
			return type;
		}

		public ContentBlock content(){
			return content;
		}
	}

	/**
	 * Tool call diff
	 */
	@NoArgsConstructor
	@AllArgsConstructor
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public static class ToolCallDiff implements ToolCallContent {
		@JsonProperty("type") String type;
		@JsonProperty("path") String path;
		@JsonProperty("oldText") String oldText;
		@JsonProperty("newText") String newText;

		public String type(){
			return type;
		}

		public String path(){
			return path;
		}

		public String oldText(){
			return oldText;
		}

		public String newText(){
			return newText;
		}
	}

	/**
	 * Tool call terminal
	 */
	@NoArgsConstructor
	@AllArgsConstructor
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public static class ToolCallTerminal implements ToolCallContent {
		@JsonProperty("type") String type;
		@JsonProperty("terminalId") String terminalId;

		public String type(){
			return type;
		}

		public String terminalId(){
			return terminalId;
		}
	}

	/**
	 * Tool call location
	 */
	@NoArgsConstructor
	@AllArgsConstructor
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public static class ToolCallLocation {
		@JsonProperty("path") String path;
		@JsonProperty("line") Integer line;

		public String path(){
			return path;
		}

		public Integer line(){
			return line;
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
	@NoArgsConstructor
	@AllArgsConstructor
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public static class McpServerStdio implements McpServer {
		@JsonProperty("name") String name;
		@JsonProperty("command") String command;
		@JsonProperty("args") List<String> args;
		@JsonProperty("env") List<EnvVariable> env;

		public String name() {
			return name;
		}

		public String command() {
			return command;
		}

		public List<String> args() {
			return args;
		}

		public List<EnvVariable> env() {
			return env;
		}
	}

	/**
	 * HTTP MCP server.
	 */
	@NoArgsConstructor
	@AllArgsConstructor
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public static class McpServerHttp implements McpServer {
		@JsonProperty("name") String name;
		@JsonProperty("url") String url;
		@JsonProperty("headers") List<HttpHeader> headers;

		/**
		 * Returns the transport type identifier.
		 */
		@JsonProperty("type")
		public String type() {
			return "http";
		}

		public String name() {
			return name;
		}

		public String url() {
			return url;
		}

		public List<HttpHeader> headers() {
			return headers;
		}
	}

	/**
	 * SSE MCP server.
	 */
	@NoArgsConstructor
	@AllArgsConstructor
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public static class McpServerSse implements McpServer {
		@JsonProperty("name") String name;
		@JsonProperty("url") String url;
		@JsonProperty("headers") List<HttpHeader> headers;

		/**
		 * Returns the transport type identifier.
		 */
		@JsonProperty("type")
		public String type() {
			return "sse";
		}

		public String name() {
			return name;
		}

		public String url() {
			return url;
		}

		public List<HttpHeader> headers() {
			return headers;
		}
	}

	/**
	 * Environment variable
	 */
	@NoArgsConstructor
	@AllArgsConstructor
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public static class EnvVariable {
		@JsonProperty("name") String name;
		@JsonProperty("value") String value;

		public String name() {
			return name;
		}

		public String value() {
			return value;
		}
	}

	/**
	 * HTTP header
	 */
	@NoArgsConstructor
	@AllArgsConstructor
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public static class HttpHeader {
		@JsonProperty("name") String name;
		@JsonProperty("value") String value;

		public String name() {
			return name;
		}

		public String value() {
			return value;
		}
	}

	/**
	 * Terminal exit status
	 */
	@NoArgsConstructor
	@AllArgsConstructor
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public static class TerminalExitStatus {
		@JsonProperty("exitCode") Integer exitCode;
		@JsonProperty("signal") String signal;

		public Integer exitCode() {
			return exitCode;
		}

		public String signal() {
			return signal;
		}
	}

	/**
	 * Authentication method
	 */
	@NoArgsConstructor
	@AllArgsConstructor
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public static class AuthMethod {
		@JsonProperty("id") String id;
		@JsonProperty("name") String name;
		@JsonProperty("description") String description;

		public String id() {
			return id;
		}

		public String name() {
			return name;
		}

		public String description() {
			return description;
		}
	}

	/**
	 * Permission option
	 */
	@NoArgsConstructor
	@AllArgsConstructor
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public static class PermissionOption {
		@JsonProperty("optionId") String optionId;
		@JsonProperty("name") String name;
		@JsonProperty("kind") PermissionOptionKind kind;

		public String optionId() {
			return optionId;
		}

		public String name() {
			return name;
		}

		public PermissionOptionKind kind() {
			return kind;
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
	@AllArgsConstructor
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public static class PermissionCancelled implements RequestPermissionOutcome {
		@JsonProperty("outcome") String outcome;

		public PermissionCancelled() {
			this("cancelled");
		}

		public String outcome(){
			return outcome;
		}
	}

	/**
	 * Permission selected
	 */
	@NoArgsConstructor
	@AllArgsConstructor
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public static class PermissionSelected implements RequestPermissionOutcome {
		@JsonProperty("outcome") String outcome;
		@JsonProperty("optionId") String optionId;

		public PermissionSelected(String optionId) {
			this("selected", optionId);
		}

		public String outcome(){
			return outcome;
		}

		public String optionId(){
			return optionId;
		}
	}

	/**
	 * Plan entry
	 */
	@NoArgsConstructor
	@AllArgsConstructor
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public static class PlanEntry {
		@JsonProperty("content") String content;
		@JsonProperty("priority") PlanEntryPriority priority;
		@JsonProperty("status") PlanEntryStatus status;

		public String content(){
			return content;
		}

		public PlanEntryPriority priority(){
			return priority;
		}

		public PlanEntryStatus status(){
			return status;
		}
	}

	/**
	 * Available command
	 */
	@NoArgsConstructor
	@AllArgsConstructor
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public static class AvailableCommand {
		@JsonProperty("name") String name;
		@JsonProperty("description") String description;
		@JsonProperty("input") AvailableCommandInput input;

		public String name(){
			return name;
		}

		public String description(){
			return description;
		}

		public AvailableCommandInput input(){
			return input;
		}
	}

	/**
	 * Available command input
	 */
	@NoArgsConstructor
	@AllArgsConstructor
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public static class AvailableCommandInput {
		@JsonProperty("hint") String hint;

		public String hint(){
			return hint;
		}
	}
}
