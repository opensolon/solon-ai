/*
 * Copyright 2024-2026 the original author or authors.
 */

package io.modelcontextprotocol.spec;

import com.fasterxml.jackson.annotation.*;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.util.Assert;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.*;

/**
 * Based on the <a href="http://www.jsonrpc.org/specification">JSON-RPC 2.0
 * specification</a> and the <a href=
 * "https://github.com/modelcontextprotocol/specification/blob/main/schema/2024-11-05/schema.ts">Model
 * Context Protocol Schema</a>.
 *
 * @author Christian Tzolov
 * @author Luca Chang
 * @author Surbhi Bansal
 * @author Anurag Pant
 * @author Dariusz Jędrzejczyk
 */
public final class McpSchema {

	private static final Logger logger = LoggerFactory.getLogger(McpSchema.class);

	private McpSchema() {
	}

	public static final String JSONRPC_VERSION = "2.0";

	public static final String FIRST_PAGE = null;

	/**
	 * The JSON Schema 2020-12 meta-schema URI (SEP-1613). This is the default dialect for
	 * all schema objects in MCP when no explicit {@code $schema} field is present.
	 */
	public static final String JSON_SCHEMA_DIALECT_2020_12 = "https://json-schema.org/draft/2020-12/schema";

	// ---------------------------
	// Method Names
	// ---------------------------

	// Lifecycle Methods
	public static final String METHOD_INITIALIZE = "initialize";

	public static final String METHOD_NOTIFICATION_INITIALIZED = "notifications/initialized";

	public static final String METHOD_PING = "ping";

	public static final String METHOD_NOTIFICATION_PROGRESS = "notifications/progress";

	// Tool Methods
	public static final String METHOD_TOOLS_LIST = "tools/list";

	public static final String METHOD_TOOLS_CALL = "tools/call";

	public static final String METHOD_NOTIFICATION_TOOLS_LIST_CHANGED = "notifications/tools/list_changed";

	// Resources Methods
	public static final String METHOD_RESOURCES_LIST = "resources/list";

	public static final String METHOD_RESOURCES_READ = "resources/read";

	public static final String METHOD_NOTIFICATION_RESOURCES_LIST_CHANGED = "notifications/resources/list_changed";

	public static final String METHOD_NOTIFICATION_RESOURCES_UPDATED = "notifications/resources/updated";

	public static final String METHOD_RESOURCES_TEMPLATES_LIST = "resources/templates/list";

	public static final String METHOD_RESOURCES_SUBSCRIBE = "resources/subscribe";

	public static final String METHOD_RESOURCES_UNSUBSCRIBE = "resources/unsubscribe";

	// Prompt Methods
	public static final String METHOD_PROMPT_LIST = "prompts/list";

	public static final String METHOD_PROMPT_GET = "prompts/get";

	public static final String METHOD_NOTIFICATION_PROMPTS_LIST_CHANGED = "notifications/prompts/list_changed";

	public static final String METHOD_COMPLETION_COMPLETE = "completion/complete";

	// Logging Methods
	public static final String METHOD_LOGGING_SET_LEVEL = "logging/setLevel";

	public static final String METHOD_NOTIFICATION_MESSAGE = "notifications/message";

	// Roots Methods
	public static final String METHOD_ROOTS_LIST = "roots/list";

	public static final String METHOD_NOTIFICATION_ROOTS_LIST_CHANGED = "notifications/roots/list_changed";

	// Sampling Methods
	public static final String METHOD_SAMPLING_CREATE_MESSAGE = "sampling/createMessage";

	// Elicitation Methods
	public static final String METHOD_ELICITATION_CREATE = "elicitation/create";

	public static final String METHOD_NOTIFICATION_ELICITATION_COMPLETE = "notifications/elicitation/complete";

	// ---------------------------
	// JSON-RPC Error Codes
	// ---------------------------
	/**
	 * Standard error codes used in MCP JSON-RPC responses.
	 */
	public static final class ErrorCodes {

		/**
		 * Invalid JSON was received by the server.
		 */
		public static final int PARSE_ERROR = -32700;

		/**
		 * The JSON sent is not a valid Request object.
		 */
		public static final int INVALID_REQUEST = -32600;

		/**
		 * The method does not exist / is not available.
		 */
		public static final int METHOD_NOT_FOUND = -32601;

		/**
		 * Invalid method parameter(s).
		 */
		public static final int INVALID_PARAMS = -32602;

		/**
		 * Internal JSON-RPC error.
		 */
		public static final int INTERNAL_ERROR = -32603;

		/**
		 * Resource not found.
		 */
		public static final int RESOURCE_NOT_FOUND = -32002;

		/**
		 * URL elicitation is required before the request can proceed.
		 */
		public static final int URL_ELICITATION_REQUIRED = -32042;

	}

	/**
	 * Base interface for MCP objects that include optional metadata in the `_meta` field.
	 */
	public interface Meta {

		/**
		 * @see <a href=
		 * "https://modelcontextprotocol.io/specification/2025-06-18/basic/index#meta">Specification</a>
		 * for notes on _meta usage
		 * @return additional metadata related to this resource.
		 */
		Map<String, Object> meta();

	}

	public interface Request extends Meta {

		default Object progressToken() {
			if (meta() != null && meta().containsKey("progressToken")) {
				return meta().get("progressToken");
			}
			return null;
		}

	}

	public interface Result extends Meta {

	}

	public interface Notification extends Meta {

	}

	private static final TypeRef<HashMap<String, Object>> MAP_TYPE_REF = new TypeRef<HashMap<String, Object>>() {
	};

	/**
	 * Deserializes a JSON string into a JSONRPCMessage object.
	 * @param jsonMapper The JsonMapper instance to use for deserialization
	 * @param jsonText The JSON string to deserialize
	 * @return A JSONRPCMessage instance using either the {@link JSONRPCRequest},
	 * {@link JSONRPCNotification}, or {@link JSONRPCResponse} classes.
	 * @throws IOException If there's an error during deserialization
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
	// JSON-RPC Message Types
	// ---------------------------
	public interface JSONRPCMessage {

		String jsonrpc();

	}

	/**
	 * A request that expects a response.
	 *
	 * @param jsonrpc The JSON-RPC version (must be "2.0")
	 * @param method The name of the method to be invoked
	 * @param id A unique identifier for the request
	 * @param params Parameters for the method call
	 */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static final class JSONRPCRequest implements JSONRPCMessage {

		@JsonProperty("jsonrpc")
		private final String jsonrpc;

		@JsonProperty("method")
		private final String method;

		@JsonProperty("id")
		private final Object id;

		@JsonProperty("params")
		private final Object params;

		/**
		 * Constructor that validates MCP-specific ID requirements. Unlike base JSON-RPC,
		 * MCP requires that: (1) Requests MUST include a string or integer ID; (2) The ID
		 * MUST NOT be null
		 */
		@JsonCreator
		public JSONRPCRequest( // @formatter:off
				@JsonProperty("jsonrpc") String jsonrpc,
				@JsonProperty("method") String method,
				@JsonProperty("id") Object id,
				@JsonProperty("params") Object params) { // @formatter:on
			Assert.hasText(jsonrpc, "jsonrpc must not be empty");
			Assert.notNull(id, "MCP requests MUST include an ID - null IDs are not allowed");
			Assert.isTrue(id instanceof String || id instanceof Integer || id instanceof Long,
					"MCP requests MUST have an ID that is either a string or integer");
			Assert.notNull(method, "MCP request method must not be null");
			this.jsonrpc = jsonrpc;
			this.method = method;
			this.id = id;
			this.params = params;
		}

		public JSONRPCRequest(String method, Object id, Object params) {
			this(JSONRPC_VERSION, method, id, params);
		}

		public JSONRPCRequest(String method, Object id) {
			this(JSONRPC_VERSION, method, id, null);
		}

		public String jsonrpc() {
			return this.jsonrpc;
		}

		public String method() {
			return this.method;
		}

		public Object id() {
			return this.id;
		}

		public Object params() {
			return this.params;
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) {
				return true;
			}
			if (!(o instanceof JSONRPCRequest)) {
				return false;
			}
			JSONRPCRequest that = (JSONRPCRequest) o;
			return Objects.equals(this.jsonrpc, that.jsonrpc) && Objects.equals(this.method, that.method)
					&& Objects.equals(this.id, that.id) && Objects.equals(this.params, that.params);
		}

		@Override
		public int hashCode() {
			return Objects.hash(this.jsonrpc, this.method, this.id, this.params);
		}

		@Override
		public String toString() {
			return "JSONRPCRequest[jsonrpc=" + this.jsonrpc + ", method=" + this.method + ", id=" + this.id + ", params="
					+ this.params + "]";
		}

	}

	/**
	 * A notification which does not expect a response.
	 *
	 * @param jsonrpc The JSON-RPC version (must be "2.0")
	 * @param method The name of the method being notified
	 * @param params Parameters for the notification
	 */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static final class JSONRPCNotification implements JSONRPCMessage {

		@JsonProperty("jsonrpc")
		private final String jsonrpc;

		@JsonProperty("method")
		private final String method;

		@JsonProperty("params")
		private final Object params;

		@JsonCreator
		public JSONRPCNotification( // @formatter:off
				@JsonProperty("jsonrpc") String jsonrpc,
				@JsonProperty("method") String method,
				@JsonProperty("params") Object params) { // @formatter:on
			Assert.hasText(jsonrpc, "jsonrpc must not be empty");
			Assert.notNull(method, "MCP notification method must not be null");
			this.jsonrpc = jsonrpc;
			this.method = method;
			this.params = params;
		}

		public JSONRPCNotification(String method, Object params) {
			this(JSONRPC_VERSION, method, params);
		}

		public JSONRPCNotification(String method) {
			this(JSONRPC_VERSION, method, null);
		}

		public String jsonrpc() {
			return this.jsonrpc;
		}

		public String method() {
			return this.method;
		}

		public Object params() {
			return this.params;
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) {
				return true;
			}
			if (!(o instanceof JSONRPCNotification)) {
				return false;
			}
			JSONRPCNotification that = (JSONRPCNotification) o;
			return Objects.equals(this.jsonrpc, that.jsonrpc) && Objects.equals(this.method, that.method)
					&& Objects.equals(this.params, that.params);
		}

		@Override
		public int hashCode() {
			return Objects.hash(this.jsonrpc, this.method, this.params);
		}

		@Override
		public String toString() {
			return "JSONRPCNotification[jsonrpc=" + this.jsonrpc + ", method=" + this.method + ", params=" + this.params
					+ "]";
		}

	}

	/**
	 * A response to a request (successful, or error).
	 *
	 * @param jsonrpc The JSON-RPC version (must be "2.0")
	 * @param id The request identifier that this response corresponds to
	 * @param result The result of the successful request; null if error
	 * @param error Error information if the request failed; null if has result
	 */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static final class JSONRPCResponse implements JSONRPCMessage {

		@JsonProperty("jsonrpc")
		private final String jsonrpc;

		@JsonProperty("id")
		private final Object id;

		@JsonProperty("result")
		private final Object result;

		@JsonProperty("error")
		private final JSONRPCError error;

		@JsonCreator
		public JSONRPCResponse( // @formatter:off
				@JsonProperty("jsonrpc") String jsonrpc,
				@JsonProperty("id") Object id,
				@JsonProperty("result") Object result,
				@JsonProperty("error") JSONRPCError error) { // @formatter:on
			Assert.hasText(jsonrpc, "jsonrpc must not be empty");
			Assert.notNull(id, "MCP responses MUST include an ID - null IDs are not allowed");
			Assert.isTrue(id instanceof String || id instanceof Integer || id instanceof Long,
					"MCP responses MUST have an ID that is either a string or integer");
			Assert.isTrue((result != null) ^ (error != null), "MCP responses MUST either have a result or error");
			this.jsonrpc = jsonrpc;
			this.id = id;
			this.result = result;
			this.error = error;
		}

		public static JSONRPCResponse result(Object id, Object result) {
			return new JSONRPCResponse(JSONRPC_VERSION, id, result, null);
		}

		public static JSONRPCResponse error(Object id, JSONRPCError error) {
			return new JSONRPCResponse(JSONRPC_VERSION, id, null, error);
		}

		public String jsonrpc() {
			return this.jsonrpc;
		}

		public Object id() {
			return this.id;
		}

		public Object result() {
			return this.result;
		}

		public JSONRPCError error() {
			return this.error;
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) {
				return true;
			}
			if (!(o instanceof JSONRPCResponse)) {
				return false;
			}
			JSONRPCResponse that = (JSONRPCResponse) o;
			return Objects.equals(this.jsonrpc, that.jsonrpc) && Objects.equals(this.id, that.id)
					&& Objects.equals(this.result, that.result) && Objects.equals(this.error, that.error);
		}

		@Override
		public int hashCode() {
			return Objects.hash(this.jsonrpc, this.id, this.result, this.error);
		}

		@Override
		public String toString() {
			return "JSONRPCResponse[jsonrpc=" + this.jsonrpc + ", id=" + this.id + ", result=" + this.result + ", error="
					+ this.error + "]";
		}

		/**
		 * A response to a request that indicates an error occurred.
		 *
		 * @param code The error type that occurred
		 * @param message A short description of the error. The message SHOULD be limited
		 * to a concise single sentence
		 * @param data Additional information about the error. The value of this member is
		 * defined by the sender (e.g. detailed error information, nested errors etc.)
		 */
		@JsonInclude(JsonInclude.Include.NON_ABSENT)
		@JsonIgnoreProperties(ignoreUnknown = true)
		public static final class JSONRPCError {

			@JsonProperty("code")
			private final Integer code;

			@JsonProperty("message")
			private final String message;

			@JsonProperty("data")
			private final Object data;

			@JsonCreator
			public JSONRPCError( // @formatter:off
					@JsonProperty("code") Integer code,
					@JsonProperty("message") String message,
					@JsonProperty("data") Object data) { // @formatter:on
				Assert.notNull(code, "code must not be null");
				Assert.notNull(message, "message must not be null");
				this.code = code;
				this.message = message;
				this.data = data;
			}

			public JSONRPCError(Integer code, String message) {
				this(code, message, null);
			}

			public Integer code() {
				return this.code;
			}

			public String message() {
				return this.message;
			}

			public Object data() {
				return this.data;
			}

			@Override
			public boolean equals(Object o) {
				if (this == o) {
					return true;
				}
				if (!(o instanceof JSONRPCError)) {
					return false;
				}
				JSONRPCError that = (JSONRPCError) o;
				return Objects.equals(this.code, that.code) && Objects.equals(this.message, that.message)
						&& Objects.equals(this.data, that.data);
			}

			@Override
			public int hashCode() {
				return Objects.hash(this.code, this.message, this.data);
			}

			@Override
			public String toString() {
				return "JSONRPCError[code=" + this.code + ", message=" + this.message + ", data=" + this.data + "]";
			}

		}
	}

	// ---------------------------
	// Initialization
	// ---------------------------
	/**
	 * This request is sent from the client to the server when it first connects, asking
	 * it to begin initialization.
	 *
	 * @param protocolVersion The latest version of the Model Context Protocol that the
	 * client supports. The client MAY decide to support older versions as well
	 * @param capabilities The capabilities that the client supports
	 * @param clientInfo Information about the client implementation
	 * @param meta See specification for notes on _meta usage
	 */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static final class InitializeRequest implements Request {

		@JsonProperty("protocolVersion")
		private final String protocolVersion;

		@JsonProperty("capabilities")
		private final ClientCapabilities capabilities;

		@JsonProperty("clientInfo")
		private final Implementation clientInfo;

		@JsonProperty("_meta")
		private final Map<String, Object> meta;

		public InitializeRequest(String protocolVersion, ClientCapabilities capabilities, Implementation clientInfo,
				Map<String, Object> meta) {
			Assert.notNull(protocolVersion, "protocolVersion must not be null");
			Assert.notNull(capabilities, "capabilities must not be null");
			Assert.notNull(clientInfo, "clientInfo must not be null");
			this.protocolVersion = protocolVersion;
			this.capabilities = capabilities;
			this.clientInfo = clientInfo;
			this.meta = meta;
		}

		@JsonCreator
		static InitializeRequest fromJson(@JsonProperty("protocolVersion") String protocolVersion,
				@JsonProperty("capabilities") ClientCapabilities capabilities,
				@JsonProperty("clientInfo") Implementation clientInfo,
				@JsonProperty("_meta") Map<String, Object> meta) {
			if (protocolVersion == null || capabilities == null || clientInfo == null) {
				List<String> missing = new ArrayList<>();
				if (protocolVersion == null) {
					missing.add("protocolVersion -> ''");
					protocolVersion = "";
				}
				if (capabilities == null) {
					missing.add("capabilities -> {}");
					capabilities = new ClientCapabilities(null, null, null, null);
				}
				if (clientInfo == null) {
					missing.add("clientInfo -> {name='', version=''}");
					clientInfo = new Implementation("", "");
				}
				logger.warn("InitializeRequest: missing required fields during deserialization: {}",
						String.join(", ", missing));
			}
			return new InitializeRequest(protocolVersion, capabilities, clientInfo, meta);
		}

		/**
		 * @deprecated Use {@link #builder(String, ClientCapabilities, Implementation)}
		 * instead.
		 */
		@Deprecated
		public InitializeRequest(String protocolVersion, ClientCapabilities capabilities, Implementation clientInfo) {
			this(protocolVersion, capabilities, clientInfo, null);
		}

		public static Builder builder(String protocolVersion, ClientCapabilities capabilities,
				Implementation clientInfo) {
			return new Builder(protocolVersion, capabilities, clientInfo);
		}

		public static class Builder {

			private final String protocolVersion;

			private final ClientCapabilities capabilities;

			private final Implementation clientInfo;

			private Map<String, Object> meta;

			private Builder(String protocolVersion, ClientCapabilities capabilities, Implementation clientInfo) {
				Assert.hasText(protocolVersion, "protocolVersion must not be empty");
				Assert.notNull(capabilities, "capabilities must not be null");
				Assert.notNull(clientInfo, "clientInfo must not be null");
				this.protocolVersion = protocolVersion;
				this.capabilities = capabilities;
				this.clientInfo = clientInfo;
			}

		public Builder meta(Map<String, Object> meta) {
				this.meta = meta;
				return this;
			}

			public InitializeRequest build() {
				return new InitializeRequest(protocolVersion, capabilities, clientInfo, meta);
			}

		}

		public String protocolVersion() {
			return this.protocolVersion;
		}

		public ClientCapabilities capabilities() {
			return this.capabilities;
		}

		public Implementation clientInfo() {
			return this.clientInfo;
		}

		public Map<String, Object> meta() {
			return this.meta;
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) {
				return true;
			}
			if (!(o instanceof InitializeRequest)) {
				return false;
			}
			InitializeRequest that = (InitializeRequest) o;
			return Objects.equals(this.protocolVersion, that.protocolVersion)
					&& Objects.equals(this.capabilities, that.capabilities)
					&& Objects.equals(this.clientInfo, that.clientInfo) && Objects.equals(this.meta, that.meta);
		}

		@Override
		public int hashCode() {
			return Objects.hash(this.protocolVersion, this.capabilities, this.clientInfo, this.meta);
		}

		@Override
		public String toString() {
			return "InitializeRequest[protocolVersion=" + this.protocolVersion + ", capabilities=" + this.capabilities
					+ ", clientInfo=" + this.clientInfo + ", meta=" + this.meta + "]";
		}
	}

	/**
	 * After receiving an initialize request from the client, the server sends this
	 * response.
	 *
	 * @param protocolVersion The version of the Model Context Protocol that the server
	 * wants to use. This may not match the version that the client requested. If the
	 * client cannot support this version, it MUST disconnect
	 * @param capabilities The capabilities that the server supports
	 * @param serverInfo Information about the server implementation
	 * @param instructions Instructions describing how to use the server and its features.
	 * This can be used by clients to improve the LLM's understanding of available tools,
	 * resources, etc. It can be thought of like a "hint" to the model. For example, this
	 * information MAY be added to the system prompt
	 * @param meta See specification for notes on _meta usage
	 */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static final class InitializeResult implements Result {

		@JsonProperty("protocolVersion")
		private final String protocolVersion;

		@JsonProperty("capabilities")
		private final ServerCapabilities capabilities;

		@JsonProperty("serverInfo")
		private final Implementation serverInfo;

		@JsonProperty("instructions")
		private final String instructions;

		@JsonProperty("_meta")
		private final Map<String, Object> meta;

		public InitializeResult(String protocolVersion, ServerCapabilities capabilities, Implementation serverInfo,
				String instructions, Map<String, Object> meta) {
			Assert.notNull(protocolVersion, "protocolVersion must not be null");
			Assert.notNull(capabilities, "capabilities must not be null");
			Assert.notNull(serverInfo, "serverInfo must not be null");
			this.protocolVersion = protocolVersion;
			this.capabilities = capabilities;
			this.serverInfo = serverInfo;
			this.instructions = instructions;
			this.meta = meta;
		}

		@JsonCreator
		static InitializeResult fromJson(@JsonProperty("protocolVersion") String protocolVersion,
				@JsonProperty("capabilities") ServerCapabilities capabilities,
				@JsonProperty("serverInfo") Implementation serverInfo,
				@JsonProperty("instructions") String instructions, @JsonProperty("_meta") Map<String, Object> meta) {
			if (protocolVersion == null || capabilities == null || serverInfo == null) {
				List<String> missing = new ArrayList<>();
				if (protocolVersion == null) {
					missing.add("protocolVersion -> ''");
					protocolVersion = "";
				}
				if (capabilities == null) {
					missing.add("capabilities -> {}");
					capabilities = new ServerCapabilities(null, null, null, null, null, null);
				}
				if (serverInfo == null) {
					missing.add("serverInfo -> {name='', version=''}");
					serverInfo = new Implementation("", "");
				}
				logger.warn("InitializeResult: missing required fields during deserialization: {}",
						String.join(", ", missing));
			}
			return new InitializeResult(protocolVersion, capabilities, serverInfo, instructions, meta);
		}

		/**
		 * @deprecated Use {@link #builder(String, ServerCapabilities, Implementation)}
		 * instead.
		 */
		@Deprecated
		public InitializeResult(String protocolVersion, ServerCapabilities capabilities, Implementation serverInfo,
				String instructions) {
			this(protocolVersion, capabilities, serverInfo, instructions, null);
		}

		public static Builder builder(String protocolVersion, ServerCapabilities capabilities,
				Implementation serverInfo) {
			return new Builder(protocolVersion, capabilities, serverInfo);
		}

		public static class Builder {

			private final String protocolVersion;

			private final ServerCapabilities capabilities;

			private final Implementation serverInfo;

			private String instructions;

			private Map<String, Object> meta;

			private Builder(String protocolVersion, ServerCapabilities capabilities, Implementation serverInfo) {
				Assert.hasText(protocolVersion, "protocolVersion must not be empty");
				Assert.notNull(capabilities, "capabilities must not be null");
				Assert.notNull(serverInfo, "serverInfo must not be null");
				this.protocolVersion = protocolVersion;
				this.capabilities = capabilities;
				this.serverInfo = serverInfo;
			}

			public Builder instructions(String instructions) {
				this.instructions = instructions;
				return this;
			}

			public Builder meta(Map<String, Object> meta) {
				this.meta = meta;
				return this;
			}

			public InitializeResult build() {
				return new InitializeResult(protocolVersion, capabilities, serverInfo, instructions, meta);
			}

		}

		public String protocolVersion() {
			return this.protocolVersion;
		}

		public ServerCapabilities capabilities() {
			return this.capabilities;
		}

		public Implementation serverInfo() {
			return this.serverInfo;
		}

		public String instructions() {
			return this.instructions;
		}

		public Map<String, Object> meta() {
			return this.meta;
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) {
				return true;
			}
			if (!(o instanceof InitializeResult)) {
				return false;
			}
			InitializeResult that = (InitializeResult) o;
			return Objects.equals(this.protocolVersion, that.protocolVersion)
					&& Objects.equals(this.capabilities, that.capabilities)
					&& Objects.equals(this.serverInfo, that.serverInfo)
					&& Objects.equals(this.instructions, that.instructions) && Objects.equals(this.meta, that.meta);
		}

		@Override
		public int hashCode() {
			return Objects.hash(this.protocolVersion, this.capabilities, this.serverInfo, this.instructions, this.meta);
		}

		@Override
		public String toString() {
			return "InitializeResult[protocolVersion=" + this.protocolVersion + ", capabilities=" + this.capabilities
					+ ", serverInfo=" + this.serverInfo + ", instructions=" + this.instructions + ", meta=" + this.meta + "]";
		}
	}

	/**
	 * Capabilities a client may support. Known capabilities are defined here, in this
	 * schema, but this is not a closed set: any client can define its own, additional
	 * capabilities.
	 *
	 * @param experimental Experimental, non-standard capabilities that the client
	 * supports
	 * @param roots Present if the client supports listing roots
	 * @param sampling Present if the client supports sampling from an LLM
	 * @param elicitation Present if the client supports elicitation from the server
	 * @param extensions Present if the client supports MCP extensions, keyed by extension
	 * identifier
	 */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static final class ClientCapabilities {

		@JsonProperty("experimental")
		private final Map<String, Object> experimental;

		@JsonProperty("roots")
		private final RootCapabilities roots;

		@JsonProperty("sampling")
		private final Sampling sampling;

		@JsonProperty("elicitation")
		private final Elicitation elicitation;

		@JsonProperty("extensions")
		private final Map<String, Map<String, Object>> extensions;

		@JsonCreator
		public ClientCapabilities( // @formatter:off
				@JsonProperty("experimental") Map<String, Object> experimental,
				@JsonProperty("roots") RootCapabilities roots,
				@JsonProperty("sampling") Sampling sampling,
				@JsonProperty("elicitation") Elicitation elicitation,
				@JsonProperty("extensions") Map<String, Map<String, Object>> extensions) { // @formatter:on
			this.experimental = experimental;
			this.roots = roots;
			this.sampling = sampling;
			this.elicitation = elicitation;
			this.extensions = extensions;
		}

		// Keep the old constructor so existing callers still compile
		public ClientCapabilities(Map<String, Object> experimental, RootCapabilities roots, Sampling sampling,
				Elicitation elicitation) {
			this(experimental, roots, sampling, elicitation, null);
		}

		/**
		 * Present if the client supports listing roots.
		 *
		 * @param listChanged Whether the client supports notifications for changes to the
		 * roots list
		 */
		@JsonInclude(JsonInclude.Include.NON_ABSENT)
		@JsonIgnoreProperties(ignoreUnknown = true)
		public static final class RootCapabilities {

			@JsonProperty("listChanged")
			private final Boolean listChanged;

			@JsonCreator
			public RootCapabilities(@JsonProperty("listChanged") Boolean listChanged) {
				this.listChanged = listChanged;
			}

			public Boolean listChanged() {
				return this.listChanged;
			}

			public static Builder builder() {
				return new Builder();
			}

			public static class Builder {

				private Boolean listChanged;

				public Builder listChanged(Boolean listChanged) {
					this.listChanged = listChanged;
					return this;
				}

				public RootCapabilities build() {
					return new RootCapabilities(listChanged);
				}

			}

			@Override
			public boolean equals(Object o) {
				if (this == o) {
					return true;
				}
				if (!(o instanceof RootCapabilities)) {
					return false;
				}
				RootCapabilities that = (RootCapabilities) o;
				return Objects.equals(this.listChanged, that.listChanged);
			}

			@Override
			public int hashCode() {
				return Objects.hash(this.listChanged);
			}

			@Override
			public String toString() {
				return "RootCapabilities[listChanged=" + this.listChanged + "]";
			}
		}

		/**
		 * Provides a standardized way for servers to request LLM sampling ("completions"
		 * or "generations") from language models via clients. This flow allows clients to
		 * maintain control over model access, selection, and permissions while enabling
		 * servers to leverage AI capabilities—with no server API keys necessary. Servers
		 * can request text or image-based interactions and optionally include context
		 * from MCP servers in their prompts.
		 */
		@JsonInclude(JsonInclude.Include.NON_ABSENT)
		@JsonIgnoreProperties(ignoreUnknown = true)
		public static final class Sampling {

			public Sampling() {
			}

			@Override
			public boolean equals(Object o) {
				return this == o || o instanceof Sampling;
			}

			@Override
			public int hashCode() {
				return Objects.hash();
			}

			@Override
			public String toString() {
				return "Sampling[]";
			}
		}

		/**
		 * Provides a standardized way for servers to request additional information from
		 * users through the client during interactions. This flow allows clients to
		 * maintain control over user interactions and data sharing while enabling servers
		 * to gather necessary information dynamically. Servers can request structured
		 * data from users with optional JSON schemas to validate responses.
		 *
		 * <p>
		 * Per the 2025-11-25 spec, clients can declare support for specific elicitation
		 * modes:
		 * <ul>
		 * <li>{@code form} - In-band structured data collection with optional schema
		 * validation</li>
		 * <li>{@code url} - Out-of-band interaction via URL navigation</li>
		 * </ul>
		 *
		 * <p>
		 * For backward compatibility, an empty elicitation object {@code {}} is
		 * equivalent to declaring support for form mode only.
		 *
		 * @param form support for in-band form-based elicitation
		 * @param url support for out-of-band URL-based elicitation
		 */
		@JsonInclude(JsonInclude.Include.NON_ABSENT)
		@JsonIgnoreProperties(ignoreUnknown = true)
		public static final class Elicitation {

			@JsonProperty("form")
			private final Form form;

			@JsonProperty("url")
			private final Url url;

			@JsonCreator
			public Elicitation( // @formatter:off
					@JsonProperty("form") Form form,
					@JsonProperty("url") Url url) { // @formatter:on
				this.form = form;
				this.url = url;
			}

			/**
			 * Marker class indicating support for form-based elicitation mode.
			 */
			@JsonInclude(JsonInclude.Include.NON_ABSENT)
			@JsonIgnoreProperties(ignoreUnknown = true)
			public static final class Form {

				public Form() {
				}

				@Override
				public boolean equals(Object o) {
					return this == o || o instanceof Form;
				}

				@Override
				public int hashCode() {
					return Objects.hash();
				}

				@Override
				public String toString() {
					return "Form[]";
				}
			}

			/**
			 * Marker class indicating support for URL-based elicitation mode.
			 */
			@JsonInclude(JsonInclude.Include.NON_ABSENT)
			@JsonIgnoreProperties(ignoreUnknown = true)
			public static final class Url {

				public Url() {
				}

				@Override
				public boolean equals(Object o) {
					return this == o || o instanceof Url;
				}

				@Override
				public int hashCode() {
					return Objects.hash();
				}

				@Override
				public String toString() {
					return "Url[]";
				}
			}

			/**
			 * Creates an Elicitation with default settings (backward compatible, produces
			 * empty JSON object).
			 * @deprecated Use {@link #builder()} instead.
			 */
			@Deprecated
			public Elicitation() {
				this(null, null);
			}

			public static Builder builder() {
				return new Builder();
			}

			public static class Builder {

				private Form form;

				private Url url;

				public Builder form(Form form) {
					this.form = form;
					return this;
				}

				public Builder url(Url url) {
					this.url = url;
					return this;
				}

				public Elicitation build() {
					return new Elicitation(form, url);
				}

			}

			public Form form() {
				return this.form;
			}

			public Url url() {
				return this.url;
			}

			@Override
			public boolean equals(Object o) {
				if (this == o) {
					return true;
				}
				if (!(o instanceof Elicitation)) {
					return false;
				}
				Elicitation that = (Elicitation) o;
				return Objects.equals(this.form, that.form) && Objects.equals(this.url, that.url);
			}

			@Override
			public int hashCode() {
				return Objects.hash(this.form, this.url);
			}

			@Override
			public String toString() {
				return "Elicitation[form=" + this.form + ", url=" + this.url + "]";
			}
		}

		public static Builder builder() {
			return new Builder();
		}

		public static class Builder {

			private Map<String, Object> experimental;

			private RootCapabilities roots;

			private Sampling sampling;

			private Elicitation elicitation;

			private Map<String, Map<String, Object>> extensions;

			public Builder experimental(Map<String, Object> experimental) {
				this.experimental = experimental;
				return this;
			}

			public Builder roots(Boolean listChanged) {
				this.roots = new RootCapabilities(listChanged);
				return this;
			}

			public Builder sampling() {
				this.sampling = new Sampling();
				return this;
			}

			/**
			 * Enables elicitation capability with default settings (backward compatible,
			 * produces empty JSON object).
			 * @return this builder
			 */
			public Builder elicitation() {
				this.elicitation = Elicitation.builder().build();
				return this;
			}

			/**
			 * Enables elicitation capability with explicit form and/or url mode support.
			 * @param form whether to support form-based elicitation
			 * @param url whether to support URL-based elicitation
			 * @return this builder
			 */
			public Builder elicitation(boolean form, boolean url) {
				this.elicitation = new Elicitation(form ? new Elicitation.Form() : null,
						url ? new Elicitation.Url() : null);
				return this;
			}

			public Builder elicitation(Elicitation elicitation) {
				this.elicitation = elicitation;
				return this;
			}

			public Builder extensions(Map<String, Map<String, Object>> extensions) {
				this.extensions = extensions;
				return this;
			}

			public ClientCapabilities build() {
				return new ClientCapabilities(experimental, roots, sampling, elicitation, extensions);
			}

		}

		public Map<String, Object> experimental() {
			return this.experimental;
		}

		public RootCapabilities roots() {
			return this.roots;
		}

		public Sampling sampling() {
			return this.sampling;
		}

		public Elicitation elicitation() {
			return this.elicitation;
		}

		public Map<String, Map<String, Object>> extensions() {
			return this.extensions;
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) {
				return true;
			}
			if (!(o instanceof ClientCapabilities)) {
				return false;
			}
			ClientCapabilities that = (ClientCapabilities) o;
			return Objects.equals(this.experimental, that.experimental) && Objects.equals(this.roots, that.roots)
					&& Objects.equals(this.sampling, that.sampling) && Objects.equals(this.elicitation, that.elicitation)
					&& Objects.equals(this.extensions, that.extensions);
		}

		@Override
		public int hashCode() {
			return Objects.hash(this.experimental, this.roots, this.sampling, this.elicitation, this.extensions);
		}

		@Override
		public String toString() {
			return "ClientCapabilities[experimental=" + this.experimental + ", roots=" + this.roots + ", sampling="
					+ this.sampling + ", elicitation=" + this.elicitation + ", extensions=" + this.extensions + "]";
		}
	}

	/**
	 * Capabilities that a server may support. Known capabilities are defined here, in
	 * this schema, but this is not a closed set: any server can define its own,
	 * additional capabilities.
	 *
	 * @param completions Present if the server supports argument autocompletion
	 * suggestions
	 * @param experimental Experimental, non-standard capabilities that the server
	 * supports
	 * @param logging Present if the server supports sending log messages to the client
	 * @param prompts Present if the server offers any prompt templates
	 * @param resources Present if the server offers any resources to read
	 * @param tools Present if the server offers any tools to call
	 * @param extensions Present if the server supports MCP extensions, keyed by extension
	 * identifier
	 */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static final class ServerCapabilities {

		@JsonProperty("completions")
		private final CompletionCapabilities completions;

		@JsonProperty("experimental")
		private final Map<String, Object> experimental;

		@JsonProperty("logging")
		private final LoggingCapabilities logging;

		@JsonProperty("prompts")
		private final PromptCapabilities prompts;

		@JsonProperty("resources")
		private final ResourceCapabilities resources;

		@JsonProperty("tools")
		private final ToolCapabilities tools;

		@JsonProperty("extensions")
		private final Map<String, Map<String, Object>> extensions;

		@JsonCreator
		public ServerCapabilities( // @formatter:off
				@JsonProperty("completions") CompletionCapabilities completions,
				@JsonProperty("experimental") Map<String, Object> experimental,
				@JsonProperty("logging") LoggingCapabilities logging,
				@JsonProperty("prompts") PromptCapabilities prompts,
				@JsonProperty("resources") ResourceCapabilities resources,
				@JsonProperty("tools") ToolCapabilities tools,
				@JsonProperty("extensions") Map<String, Map<String, Object>> extensions) { // @formatter:on
			this.completions = completions;
			this.experimental = experimental;
			this.logging = logging;
			this.prompts = prompts;
			this.resources = resources;
			this.tools = tools;
			this.extensions = extensions;
		}

		// Keep the old constructor so existing callers still compile
		public ServerCapabilities(CompletionCapabilities completions, Map<String, Object> experimental,
				LoggingCapabilities logging, PromptCapabilities prompts, ResourceCapabilities resources,
				ToolCapabilities tools) {
			this(completions, experimental, logging, prompts, resources, tools, null);
		}

		/**
		 * Present if the server supports argument autocompletion suggestions.
		 */
		@JsonInclude(JsonInclude.Include.NON_ABSENT)
		@JsonIgnoreProperties(ignoreUnknown = true)
		public static final class CompletionCapabilities {

			public CompletionCapabilities() {
			}

			@Override
			public boolean equals(Object o) {
				return this == o || o instanceof CompletionCapabilities;
			}

			@Override
			public int hashCode() {
				return Objects.hash();
			}

			@Override
			public String toString() {
				return "CompletionCapabilities[]";
			}
		}

		/**
		 * Present if the server supports sending log messages to the client.
		 */
		@JsonInclude(JsonInclude.Include.NON_ABSENT)
		@JsonIgnoreProperties(ignoreUnknown = true)
		public static final class LoggingCapabilities {

			public LoggingCapabilities() {
			}

			@Override
			public boolean equals(Object o) {
				return this == o || o instanceof LoggingCapabilities;
			}

			@Override
			public int hashCode() {
				return Objects.hash();
			}

			@Override
			public String toString() {
				return "LoggingCapabilities[]";
			}
		}

		/**
		 * Present if the server offers any prompt templates.
		 *
		 * @param listChanged Whether this server supports notifications for changes to
		 * the prompt list
		 */
		@JsonInclude(JsonInclude.Include.NON_ABSENT)
		@JsonIgnoreProperties(ignoreUnknown = true)
		public static final class PromptCapabilities {

			@JsonProperty("listChanged")
			private final Boolean listChanged;

			@JsonCreator
			public PromptCapabilities(@JsonProperty("listChanged") Boolean listChanged) {
				this.listChanged = listChanged;
			}

			public Boolean listChanged() {
				return this.listChanged;
			}

			public static Builder builder() {
				return new Builder();
			}

			public static class Builder {

				private Boolean listChanged;

				public Builder listChanged(Boolean listChanged) {
					this.listChanged = listChanged;
					return this;
				}

				public PromptCapabilities build() {
					return new PromptCapabilities(listChanged);
				}

			}

			@Override
			public boolean equals(Object o) {
				if (this == o) {
					return true;
				}
				if (!(o instanceof PromptCapabilities)) {
					return false;
				}
				PromptCapabilities that = (PromptCapabilities) o;
				return Objects.equals(this.listChanged, that.listChanged);
			}

			@Override
			public int hashCode() {
				return Objects.hash(this.listChanged);
			}

			@Override
			public String toString() {
				return "PromptCapabilities[listChanged=" + this.listChanged + "]";
			}
		}

		/**
		 * Present if the server offers any resources to read.
		 *
		 * @param subscribe Whether this server supports subscribing to resource updates
		 * @param listChanged Whether this server supports notifications for changes to
		 * the resource list
		 */
		@JsonInclude(JsonInclude.Include.NON_ABSENT)
		@JsonIgnoreProperties(ignoreUnknown = true)
		public static final class ResourceCapabilities {

			@JsonProperty("subscribe")
			private final Boolean subscribe;

			@JsonProperty("listChanged")
			private final Boolean listChanged;

			@JsonCreator
			public ResourceCapabilities( // @formatter:off
					@JsonProperty("subscribe") Boolean subscribe,
					@JsonProperty("listChanged") Boolean listChanged) { // @formatter:on
				this.subscribe = subscribe;
				this.listChanged = listChanged;
			}

			public Boolean subscribe() {
				return this.subscribe;
			}

			public Boolean listChanged() {
				return this.listChanged;
			}

			public static Builder builder() {
				return new Builder();
			}

			public static class Builder {

				private Boolean subscribe;

				private Boolean listChanged;

				public Builder subscribe(Boolean subscribe) {
					this.subscribe = subscribe;
					return this;
				}

				public Builder listChanged(Boolean listChanged) {
					this.listChanged = listChanged;
				return this;
				}

				public ResourceCapabilities build() {
					return new ResourceCapabilities(subscribe, listChanged);
				}

			}

			@Override
			public boolean equals(Object o) {
				if (this == o) {
					return true;
				}
				if (!(o instanceof ResourceCapabilities)) {
					return false;
				}
				ResourceCapabilities that = (ResourceCapabilities) o;
				return Objects.equals(this.subscribe, that.subscribe)
						&& Objects.equals(this.listChanged, that.listChanged);
			}

			@Override
			public int hashCode() {
				return Objects.hash(this.subscribe, this.listChanged);
			}

			@Override
			public String toString() {
				return "ResourceCapabilities[subscribe=" + this.subscribe + ", listChanged=" + this.listChanged + "]";
			}
		}

		/**
		 * Present if the server offers any tools to call.
		 *
		 * @param listChanged Whether this server supports notifications for changes to
		 * the tool list
		 */
		@JsonInclude(JsonInclude.Include.NON_ABSENT)
		@JsonIgnoreProperties(ignoreUnknown = true)
		public static final class ToolCapabilities {

			@JsonProperty("listChanged")
			private final Boolean listChanged;

			@JsonCreator
			public ToolCapabilities(@JsonProperty("listChanged") Boolean listChanged) {
				this.listChanged = listChanged;
			}

			public Boolean listChanged() {
				return this.listChanged;
			}

			public static Builder builder() {
				return new Builder();
			}

			public static class Builder {

				private Boolean listChanged;

				public Builder listChanged(Boolean listChanged) {
					this.listChanged = listChanged;
					return this;
				}

				public ToolCapabilities build() {
					return new ToolCapabilities(listChanged);
				}

			}

			@Override
			public boolean equals(Object o) {
				if (this == o) {
					return true;
				}
				if (!(o instanceof ToolCapabilities)) {
					return false;
				}
				ToolCapabilities that = (ToolCapabilities) o;
				return Objects.equals(this.listChanged, that.listChanged);
			}

			@Override
			public int hashCode() {
				return Objects.hash(this.listChanged);
			}

			@Override
			public String toString() {
				return "ToolCapabilities[listChanged=" + this.listChanged + "]";
			}
		}

		/**
		 * Create a mutated copy of this object with the specified changes.
		 * @return A new Builder instance with the same values as this object.
		 */
		public Builder mutate() {
			Builder builder = new Builder();
			builder.completions = this.completions;
			builder.experimental = this.experimental;
			builder.logging = this.logging;
			builder.prompts = this.prompts;
			builder.resources = this.resources;
			builder.tools = this.tools;
			builder.extensions = this.extensions;
			return builder;
		}

		public static Builder builder() {
			return new Builder();
		}

		public static class Builder {

			private CompletionCapabilities completions;

			private Map<String, Object> experimental;

			private LoggingCapabilities logging;

			private PromptCapabilities prompts;

			private ResourceCapabilities resources;

			private ToolCapabilities tools;

			private Map<String, Map<String, Object>> extensions;

			public Builder completions() {
				this.completions = new CompletionCapabilities();
				return this;
			}

			public Builder experimental(Map<String, Object> experimental) {
				this.experimental = experimental;
				return this;
			}

			public Builder logging() {
				this.logging = new LoggingCapabilities();
				return this;
			}

			public Builder prompts(Boolean listChanged) {
				this.prompts = new PromptCapabilities(listChanged);
				return this;
			}

			public Builder resources(Boolean subscribe, Boolean listChanged) {
				this.resources = new ResourceCapabilities(subscribe, listChanged);
				return this;
			}

			public Builder tools(Boolean listChanged) {
				this.tools = new ToolCapabilities(listChanged);
				return this;
			}

			public Builder extensions(Map<String, Map<String, Object>> extensions) {
				this.extensions = extensions;
				return this;
			}

			public ServerCapabilities build() {
				return new ServerCapabilities(completions, experimental, logging, prompts, resources, tools,
					extensions);
			}

		}

		public CompletionCapabilities completions() {
			return this.completions;
		}

		public Map<String, Object> experimental() {
			return this.experimental;
		}

		public LoggingCapabilities logging() {
			return this.logging;
		}

		public PromptCapabilities prompts() {
			return this.prompts;
		}

		public ResourceCapabilities resources() {
			return this.resources;
		}

		public ToolCapabilities tools() {
			return this.tools;
		}

		public Map<String, Map<String, Object>> extensions() {
			return this.extensions;
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) {
				return true;
			}
			if (!(o instanceof ServerCapabilities)) {
				return false;
			}
			ServerCapabilities that = (ServerCapabilities) o;
			return Objects.equals(this.completions, that.completions)
					&& Objects.equals(this.experimental, that.experimental)
					&& Objects.equals(this.logging, that.logging) && Objects.equals(this.prompts, that.prompts)
					&& Objects.equals(this.resources, that.resources) && Objects.equals(this.tools, that.tools)
					&& Objects.equals(this.extensions, that.extensions);
		}

		@Override
		public int hashCode() {
			return Objects.hash(this.completions, this.experimental, this.logging, this.prompts, this.resources,
					this.tools, this.extensions);
		}

		@Override
		public String toString() {
			return "ServerCapabilities[completions=" + this.completions + ", experimental=" + this.experimental
					+ ", logging=" + this.logging + ", prompts=" + this.prompts + ", resources=" + this.resources
					+ ", tools=" + this.tools + ", extensions=" + this.extensions + "]";
		}
	}

	/**
	 * Describes the name and version of an MCP implementation, with an optional title for
	 * UI representation.
	 *
	 * @param name Intended for programmatic or logical use, but used as a display name in
	 * past specs or fallback (if title isn't present).
	 * @param title Intended for UI and end-user contexts
	 * @param version The version of the implementation.
	 * @param description An optional human-readable description of this implementation.
	 * @param icons An optional list of icons for this implementation.
	 * @param websiteUrl An optional URL of the website for this implementation.
	 */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static final class Implementation implements Identifier {

		@JsonProperty("name")
		private final String name;

		@JsonProperty("title")
		private final String title;

		@JsonProperty("version")
		private final String version;

		@JsonProperty("description")
		private final String description;

		@JsonProperty("icons")
		private final List<Icon> icons;

		@JsonProperty("websiteUrl")
		private final String websiteUrl;

		public Implementation(String name, String title, String version, String description, List<Icon> icons,
				String websiteUrl) {
			Assert.notNull(name, "name must not be null");
			Assert.notNull(version, "version must not be null");
			this.name = name;
			this.title = title;
			this.version = version;
			this.description = description;
			this.icons = icons;
			this.websiteUrl = websiteUrl;
		}

		@JsonCreator
		static Implementation fromJson(@JsonProperty("name") String name, @JsonProperty("title") String title,
				@JsonProperty("version") String version, @JsonProperty("description") String description,
				@JsonProperty("icons") List<Icon> icons, @JsonProperty("websiteUrl") String websiteUrl) {
			if (name == null || version == null) {
				List<String> missing = new ArrayList<>();
				if (name == null) {
					missing.add("name -> ''");
					name = "";
				}
				if (version == null) {
					missing.add("version -> ''");
					version = "";
				}
				logger.warn("Implementation: missing required fields during deserialization: {}",
						String.join(", ", missing));
			}
			return new Implementation(name, title, version, description, icons, websiteUrl);
		}

		/**
		 * @deprecated Use {@link #builder(String, String)}
		 */
		@Deprecated
		public Implementation(String name, String version) {
			this(name, null, version, null, null, null);
		}

		/**
		 * @deprecated Use {@link #builder(String, String)}
		 */
		@Deprecated
		public Implementation(String name, String title, String version) {
			this(name, title, version, null, null, null);
		}

		public static Builder builder(String name, String version) {
			return new Builder(name, version);
		}

		public static class Builder {

			private final String name;

			private String title;

			private final String version;

			private String description;

			private List<Icon> icons;

			private String websiteUrl;

			private Builder(String name, String version) {
				Assert.hasText(name, "name must not be empty");
				Assert.hasText(version, "version must not be empty");
				this.name = name;
				this.version = version;
			}

			public Builder title(String title) {
				this.title = title;
				return this;
			}

			public Builder description(String description) {
				this.description = description;
				return this;
			}

			public Builder icons(List<Icon> icons) {
				this.icons = icons;
				return this;
			}

			public Builder websiteUrl(String websiteUrl) {
				this.websiteUrl = websiteUrl;
				return this;
			}

			public Implementation build() {
				return new Implementation(name, title, version, description, icons, websiteUrl);
			}

		}

		public String name() {
			return this.name;
		}

		public String title() {
			return this.title;
		}

		public String version() {
			return this.version;
		}

		public String description() {
			return this.description;
		}

		public List<Icon> icons() {
			return this.icons;
		}

		public String websiteUrl() {
			return this.websiteUrl;
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) {
				return true;
			}
			if (!(o instanceof Implementation)) {
				return false;
			}
			Implementation that = (Implementation) o;
			return Objects.equals(this.name, that.name) && Objects.equals(this.title, that.title)
					&& Objects.equals(this.version, that.version) && Objects.equals(this.description, that.description)
					&& Objects.equals(this.icons, that.icons) && Objects.equals(this.websiteUrl, that.websiteUrl);
		}

		@Override
		public int hashCode() {
			return Objects.hash(this.name, this.title, this.version, this.description, this.icons, this.websiteUrl);
		}

		@Override
		public String toString() {
			return "Implementation[name=" + this.name + ", title=" + this.title + ", version=" + this.version
					+ ", description=" + this.description + ", icons=" + this.icons + ", websiteUrl=" + this.websiteUrl
					+ "]";
		}
	}

	/**
	 * Represents an icon that can be displayed in a user interface.
	 *
	 * @param src A URI pointing to an icon resource or a base64-encoded data URI.
	 * @param mimeType Optional MIME type override if the server's MIME type is missing or
	 * generic.
	 * @param sizes Optional array of strings specifying sizes at which the icon can be
	 * used. Each string should be in WxH format (e.g., "48x48", "96x96") or "any" for
	 * scalable formats like SVG.
	 * @param theme Optional specifier for the theme this icon is designed for. "light"
	 * indicates the icon is designed for a light background, "dark" indicates the icon is
	 * designed for a dark background. If not provided, the client should assume the icon
	 * can be used with any theme.
	 * @see <a href=
	 * "https://github.com/modelcontextprotocol/modelcontextprotocol/issues/973">SEP-973</a>
	 */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static final class Icon {

		@JsonProperty("src")
		private final String src;

		@JsonProperty("mimeType")
		private final String mimeType;

		@JsonProperty("sizes")
		private final List<String> sizes;

		@JsonProperty("theme")
		private final String theme;

		public Icon(String src, String mimeType, List<String> sizes, String theme) {
			Assert.notNull(src, "Icon src must not be null");
			this.src = src;
			this.mimeType = mimeType;
			this.sizes = sizes;
			this.theme = theme;
		}

		@JsonCreator
		static Icon fromJson(@JsonProperty("src") String src, @JsonProperty("mimeType") String mimeType,
				@JsonProperty("sizes") List<String> sizes, @JsonProperty("theme") String theme) {
			if (src == null) {
				logger.warn("Icon: missing required field 'src' during deserialization, using default ''");
				src = "";
			}
			return new Icon(src, mimeType, sizes, theme);
		}

		public static Builder builder(String src) {
			return new Builder(src);
		}

		public static class Builder {

			private final String src;

			private String mimeType;

			private List<String> sizes;

			private String theme;

			private Builder(String src) {
				Assert.hasText(src, "src must not be empty");
				this.src = src;
			}

			public Builder mimeType(String mimeType) {
				this.mimeType = mimeType;
				return this;
			}

			public Builder sizes(List<String> sizes) {
				this.sizes = sizes;
				return this;
			}

			public Builder theme(String theme) {
				this.theme = theme;
				return this;
			}

			public Icon build() {
				return new Icon(src, mimeType, sizes, theme);
			}

		}

		public String src() {
			return this.src;
		}

		public String mimeType() {
			return this.mimeType;
		}

		public List<String> sizes() {
			return this.sizes;
		}

		public String theme() {
			return this.theme;
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) {
				return true;
			}
			if (!(o instanceof Icon)) {
				return false;
			}
			Icon that = (Icon) o;
			return Objects.equals(this.src, that.src) && Objects.equals(this.mimeType, that.mimeType)
					&& Objects.equals(this.sizes, that.sizes) && Objects.equals(this.theme, that.theme);
		}

		@Override
		public int hashCode() {
			return Objects.hash(this.src, this.mimeType, this.sizes, this.theme);
		}

		@Override
		public String toString() {
			return "Icon[src=" + this.src + ", mimeType=" + this.mimeType + ", sizes=" + this.sizes + ", theme="
					+ this.theme + "]";
		}
	}

	// Existing Enums and Base Types (from previous implementation)
	public enum Role {

	// @formatter:off
		@JsonProperty("user") USER,
		@JsonProperty("assistant") ASSISTANT
	} // @formatter:on

	// ---------------------------
	// Resource Interfaces
	// ---------------------------
	/**
	 * Base for objects that include optional annotations for the client. The client can
	 * use annotations to inform how objects are used or displayed
	 */
	public interface Annotated {

		Annotations annotations();

	}

	/**
	 * Optional annotations for the client. The client can use annotations to inform how
	 * objects are used or displayed.
	 *
	 * @param audience Describes who the intended customer of this object or data is. It
	 * can include multiple entries to indicate content useful for multiple audiences
	 * (e.g., `["user", "assistant"]`).
	 * @param priority Describes how important this data is for operating the server. A
	 * value of 1 means "most important," and indicates that the data is effectively
	 * required, while 0 means "least important," and indicates that the data is entirely
	 * optional. It is a number between 0 and 1.
	 */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static final class Annotations {

		@JsonProperty("audience")
		private final List<Role> audience;

		@JsonProperty("priority")
		private final Double priority;

		@JsonProperty("lastModified")
		private final String lastModified;

		@JsonCreator
		public Annotations( // @formatter:off
				@JsonProperty("audience") List<Role> audience,
				@JsonProperty("priority") Double priority,
				@JsonProperty("lastModified") String lastModified) { // @formatter:on
			this.audience = audience;
			this.priority = priority;
			this.lastModified = lastModified;
		}

		/**
		 * @deprecated Use {@link #builder()} instead.
		 */
		@Deprecated
		public Annotations(List<Role> audience, Double priority) {
			this(audience, priority, null);
		}

		public static Builder builder() {
			return new Builder();
		}

		public static class Builder {

			private List<Role> audience;

			private Double priority;

			private String lastModified;

			public Builder audience(List<Role> audience) {
				this.audience = audience;
				return this;
			}

			public Builder priority(Double priority) {
				this.priority = priority;
				return this;
			}

			public Builder lastModified(String lastModified) {
				this.lastModified = lastModified;
				return this;
			}

			public Annotations build() {
				return new Annotations(audience, priority, lastModified);
			}

		}

		public List<Role> audience() {
			return this.audience;
		}

		public Double priority() {
			return this.priority;
		}

		public String lastModified() {
			return this.lastModified;
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) {
				return true;
			}
			if (!(o instanceof Annotations)) {
				return false;
			}
			Annotations that = (Annotations) o;
			return Objects.equals(this.audience, that.audience) && Objects.equals(this.priority, that.priority)
					&& Objects.equals(this.lastModified, that.lastModified);
		}

		@Override
		public int hashCode() {
			return Objects.hash(this.audience, this.priority, this.lastModified);
		}

		@Override
		public String toString() {
			return "Annotations[audience=" + this.audience + ", priority=" + this.priority + ", lastModified="
					+ this.lastModified + "]";
		}
	}

	/**
	 * A common interface for resource content, which includes metadata about the resource
	 * such as its URI, name, description, MIME type, size, and annotations. This
	 * interface is implemented by both {@link Resource} and {@link ResourceLink} to
	 * provide a consistent way to access resource metadata.
	 */
	public interface ResourceContent extends Identifier, Annotated, Meta {

		// name & title from Identifier

		String uri();

		String description();

		String mimeType();

		Long size();

		// annotations from Annotated
		// meta from Meta

	}

	/**
	 * Base interface with name (identifier) and title (display name) properties.
	 */
	public interface Identifier {

		/**
		 * Intended for programmatic or logical use, but used as a display name in past
		 * specs or fallback (if title isn't present).
		 */
		String name();

		/**
		 * Intended for UI and end-user contexts — optimized to be human-readable and
		 * easily understood, even by those unfamiliar with domain-specific terminology.
		 *
		 * If not provided, the name should be used for display.
		 */
		String title();

	}

	/**
	 * A known resource that the server is capable of reading.
	 *
	 * @param uri the URI of the resource.
	 * @param name A human-readable name for this resource. This can be used by clients to
	 * populate UI elements.
	 * @param title An optional title for this resource.
	 * @param description A description of what this resource represents. This can be used
	 * by clients to improve the LLM's understanding of available resources. It can be
	 * thought of like a "hint" to the model.
	 * @param mimeType The MIME type of this resource, if known.
	 * @param size The size of the raw resource content, in bytes (i.e., before base64
	 * encoding or any tokenization), if known. This can be used by Hosts to display file
	 * sizes and estimate context window usage.
	 * @param annotations Optional annotations for the client. The client can use
	 * annotations to inform how objects are used or displayed.
	 * @param icons Optional list of icons for this resource.
	 * @param meta See specification for notes on _meta usage
	 */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static final class Resource implements ResourceContent {

		@JsonProperty("uri")
		private final String uri;

		@JsonProperty("name")
		private final String name;

		@JsonProperty("title")
		private final String title;

		@JsonProperty("description")
		private final String description;

		@JsonProperty("mimeType")
		private final String mimeType;

		@JsonProperty("size")
		private final Long size;

		@JsonProperty("annotations")
		private final Annotations annotations;

		@JsonProperty("_meta")
		private final Map<String, Object> meta;

		@JsonProperty("icons")
		private final List<Icon> icons;

		@JsonCreator
		public Resource( // @formatter:off
				@JsonProperty("uri") String uri,
				@JsonProperty("name") String name,
				@JsonProperty("title") String title,
				@JsonProperty("description") String description,
				@JsonProperty("mimeType") String mimeType,
				@JsonProperty("size") Long size,
				@JsonProperty("annotations") Annotations annotations,
				@JsonProperty("_meta") Map<String, Object> meta,
				@JsonProperty("icons") List<Icon> icons) { // @formatter:on
			Assert.hasText(uri, "uri must not be empty");
			Assert.hasText(name, "name must not be empty");
			this.uri = uri;
			this.name = name;
			this.title = title;
			this.description = description;
			this.mimeType = mimeType;
			this.size = size;
			this.annotations = annotations;
			this.meta = meta;
			this.icons = icons;
		}

		/**
		 * @deprecated Use {@link #builder(String, String)}
		 */
		@Deprecated
		public Resource(String uri, String name, String title, String description, String mimeType, Long size,
				Annotations annotations, Map<String, Object> meta) {
			this(uri, name, title, description, mimeType, size, annotations, meta, null);
		}

		public static Builder builder(String uri, String name) {
			return new Builder(uri, name);
		}

		@Deprecated
		public static Builder builder() {
			return new Builder();
		}

		public static class Builder {

			private /* final */ String uri;

			private /* final */ String name;

			private String title;

			private String description;

			private String mimeType;

			private Long size;

			private Annotations annotations;

			private List<Icon> icons;

			private Map<String, Object> meta;

			@Deprecated
			public Builder() {
			}

			@Deprecated
			public Builder uri(String uri) {
				Assert.hasText(uri, "uri must not be empty");
				this.uri = uri;
				return this;
			}

			@Deprecated
			public Builder name(String name) {
				this.name = name;
				Assert.hasText(name, "name must not be empty");
				return this;
			}

			private Builder(String uri, String name) {
				Assert.hasText(uri, "uri must not be empty");
				Assert.hasText(name, "name must not be empty");
				this.uri = uri;
				this.name = name;
			}

			public Builder title(String title) {
				this.title = title;
				return this;
			}

			public Builder description(String description) {
				this.description = description;
				return this;
			}

			public Builder mimeType(String mimeType) {
				this.mimeType = mimeType;
				return this;
			}

			public Builder size(Long size) {
				this.size = size;
				return this;
			}

			public Builder annotations(Annotations annotations) {
				this.annotations = annotations;
				return this;
			}

			public Builder icons(List<Icon> icons) {
				this.icons = icons;
				return this;
			}

			public Builder meta(Map<String, Object> meta) {
				this.meta = meta;
				return this;
			}

			public Resource build() {
				return new Resource(uri, name, title, description, mimeType, size, annotations, meta, icons);
			}

		}

		public String uri() {
			return this.uri;
		}

		public String name() {
			return this.name;
		}

		public String title() {
			return this.title;
		}

		public String description() {
			return this.description;
		}

		public String mimeType() {
			return this.mimeType;
		}

		public Long size() {
			return this.size;
		}

		public Annotations annotations() {
			return this.annotations;
		}

		public Map<String, Object> meta() {
			return this.meta;
		}

		public List<Icon> icons() {
			return this.icons;
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) {
				return true;
			}
			if (!(o instanceof Resource)) {
				return false;
			}
			Resource that = (Resource) o;
			return Objects.equals(this.uri, that.uri) && Objects.equals(this.name, that.name)
					&& Objects.equals(this.title, that.title) && Objects.equals(this.description, that.description)
					&& Objects.equals(this.mimeType, that.mimeType) && Objects.equals(this.size, that.size)
					&& Objects.equals(this.annotations, that.annotations) && Objects.equals(this.meta, that.meta)
					&& Objects.equals(this.icons, that.icons);
		}

		@Override
		public int hashCode() {
			return Objects.hash(this.uri, this.name, this.title, this.description, this.mimeType, this.size,
					this.annotations, this.meta, this.icons);
		}

		@Override
		public String toString() {
			return "Resource[uri=" + this.uri + ", name=" + this.name + ", title=" + this.title + ", description="
					+ this.description + ", mimeType=" + this.mimeType + ", size=" + this.size + ", annotations="
					+ this.annotations + ", meta=" + this.meta + ", icons=" + this.icons + "]";
		}
	}

	/**
	 * Resource templates allow servers to expose parameterized resources using URI
	 *
	 * @param uriTemplate A URI template that can be used to generate URIs for this
	 * resource.
	 * @param name A human-readable name for this resource. This can be used by clients to
	 * populate UI elements.
	 * @param title An optional title for this resource.
	 * @param description A description of what this resource represents. This can be used
	 * by clients to improve the LLM's understanding of available resources. It can be
	 * thought of like a "hint" to the model.
	 * @param mimeType The MIME type of this resource, if known.
	 * @param annotations Optional annotations for the client. The client can use
	 * annotations to inform how objects are used or displayed.
	 * @param icons Optional list of icons for this resource template.
	 * @see <a href="https://datatracker.ietf.org/doc/html/rfc6570">RFC 6570</a>
	 * @param meta See specification for notes on _meta usage
	 *
	 */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static final class ResourceTemplate implements Annotated, Identifier, Meta {

		@JsonProperty("uriTemplate")
		private final String uriTemplate;

		@JsonProperty("name")
		private final String name;

		@JsonProperty("title")
		private final String title;

		@JsonProperty("description")
		private final String description;

		@JsonProperty("mimeType")
		private final String mimeType;

		@JsonProperty("annotations")
		private final Annotations annotations;

		@JsonProperty("_meta")
		private final Map<String, Object> meta;

		@JsonProperty("icons")
		private final List<Icon> icons;

		@JsonCreator
		public ResourceTemplate( // @formatter:off
				@JsonProperty("uriTemplate") String uriTemplate,
				@JsonProperty("name") String name,
				@JsonProperty("title") String title,
				@JsonProperty("description") String description,
				@JsonProperty("mimeType") String mimeType,
				@JsonProperty("annotations") Annotations annotations,
				@JsonProperty("_meta") Map<String, Object> meta,
				@JsonProperty("icons") List<Icon> icons) { // @formatter:on
			Assert.hasText(uriTemplate, "uriTemplate must not be empty");
			Assert.hasText(name, "name must not be empty");
			this.uriTemplate = uriTemplate;
			this.name = name;
			this.title = title;
			this.description = description;
			this.mimeType = mimeType;
			this.annotations = annotations;
			this.meta = meta;
			this.icons = icons;
		}

		/**
		 * @deprecated Use {@link #builder(String, String)}.
		 */
		@Deprecated
		public ResourceTemplate(String uriTemplate, String name, String title, String description, String mimeType,
				Annotations annotations, Map<String, Object> meta) {
			this(uriTemplate, name, title, description, mimeType, annotations, meta, null);
		}

		/**
		 * @deprecated Use {@link #builder(String, String)}.
		 */
		@Deprecated
		public ResourceTemplate(String uriTemplate, String name, String title, String description, String mimeType,
				Annotations annotations) {
			this(uriTemplate, name, title, description, mimeType, annotations, null, null);
		}

		/**
		 * @deprecated Use {@link #builder(String, String)}.
		 */
		@Deprecated
		public ResourceTemplate(String uriTemplate, String name, String description, String mimeType,
				Annotations annotations) {
			this(uriTemplate, name, null, description, mimeType, annotations, null, null);
		}

		public static Builder builder(String uriTemplate, String name) {
			return new Builder(uriTemplate, name);
		}

		@Deprecated
		public static Builder builder() {
			return new Builder();
		}

		public static class Builder {

			private /* final */ String uriTemplate;

			private /* final */ String name;

			private String title;

			private String description;

			private String mimeType;

			private Annotations annotations;

			private List<Icon> icons;

			private Map<String, Object> meta;

			@Deprecated
			private Builder() {

			}

			private Builder(String uriTemplate, String name) {
				Assert.hasText(uriTemplate, "uriTemplate must not be empty");
				Assert.hasText(name, "name must not be empty");
				this.uriTemplate = uriTemplate;
				this.name = name;
			}

			@Deprecated
			public Builder uriTemplate(String uriTemplate) {
				Assert.hasText(uriTemplate, "uriTemplate must not be empty");
				this.uriTemplate = uriTemplate;
				return this;
			}

			@Deprecated
			public Builder name(String name) {
				Assert.hasText(name, "name must not be empty");
				this.name = name;
				return this;
			}

			public Builder title(String title) {
				this.title = title;
				return this;
			}

			public Builder description(String description) {
				this.description = description;
				return this;
			}

			public Builder mimeType(String mimeType) {
				this.mimeType = mimeType;
				return this;
			}

			public Builder annotations(Annotations annotations) {
				this.annotations = annotations;
				return this;
			}

			public Builder icons(List<Icon> icons) {
				this.icons = icons;
				return this;
			}

			public Builder meta(Map<String, Object> meta) {
				this.meta = meta;
				return this;
			}

			public ResourceTemplate build() {
				return new ResourceTemplate(uriTemplate, name, title, description, mimeType, annotations, meta, icons);
			}

		}

		public String uriTemplate() {
			return this.uriTemplate;
		}

		public String name() {
			return this.name;
		}

		public String title() {
			return this.title;
		}

		public String description() {
			return this.description;
		}

		public String mimeType() {
			return this.mimeType;
		}

		public Annotations annotations() {
			return this.annotations;
		}

		public Map<String, Object> meta() {
			return this.meta;
		}

		public List<Icon> icons() {
			return this.icons;
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) {
				return true;
			}
			if (!(o instanceof ResourceTemplate)) {
				return false;
			}
			ResourceTemplate that = (ResourceTemplate) o;
			return Objects.equals(this.uriTemplate, that.uriTemplate) && Objects.equals(this.name, that.name)
					&& Objects.equals(this.title, that.title) && Objects.equals(this.description, that.description)
					&& Objects.equals(this.mimeType, that.mimeType)
					&& Objects.equals(this.annotations, that.annotations) && Objects.equals(this.meta, that.meta)
					&& Objects.equals(this.icons, that.icons);
		}

		@Override
		public int hashCode() {
			return Objects.hash(this.uriTemplate, this.name, this.title, this.description, this.mimeType,
					this.annotations, this.meta, this.icons);
		}

		@Override
		public String toString() {
			return "ResourceTemplate[uriTemplate=" + this.uriTemplate + ", name=" + this.name + ", title=" + this.title
					+ ", description=" + this.description + ", mimeType=" + this.mimeType + ", annotations="
					+ this.annotations + ", meta=" + this.meta + ", icons=" + this.icons + "]";
		}
	}

	/**
	 * The server's response to a resources/list request from the client.
	 *
	 * @param resources A list of resources that the server provides
	 * @param nextCursor An opaque token representing the pagination position after the
	 * last returned result. If present, there may be more results available
	 * @param meta See specification for notes on _meta usage
	 */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static final class ListResourcesResult implements Result {

		@JsonProperty("resources")
		private final List<Resource> resources;

		@JsonProperty("nextCursor")
		private final String nextCursor;

		@JsonProperty("_meta")
		private final Map<String, Object> meta;

		public ListResourcesResult(List<Resource> resources, String nextCursor, Map<String, Object> meta) {
			Assert.notNull(resources, "resources must not be null");
			this.resources = resources;
			this.nextCursor = nextCursor;
			this.meta = meta;
		}

		@JsonCreator
		static ListResourcesResult fromJson(@JsonProperty("resources") List<Resource> resources,
				@JsonProperty("nextCursor") String nextCursor, @JsonProperty("_meta") Map<String, Object> meta) {
			if (resources == null) {
				logger.warn(
						"ListResourcesResult: missing required field 'resources' during deserialization, using default []");
				resources = Collections.emptyList();
			}
			return new ListResourcesResult(resources, nextCursor, meta);
		}

		@Deprecated
		public ListResourcesResult(List<Resource> resources, String nextCursor) {
			this(resources, nextCursor, null);
		}

		public static Builder builder(List<Resource> resources) {
			return new Builder(resources);
		}

		public static class Builder {

			private final List<Resource> resources;

			private String nextCursor;

			private Map<String, Object> meta;

			private Builder(List<Resource> resources) {
				Assert.notNull(resources, "resources must not be null");
				this.resources = resources;
			}

			public Builder nextCursor(String nextCursor) {
				this.nextCursor = nextCursor;
				return this;
			}

			public Builder meta(Map<String, Object> meta) {
				this.meta = meta;
				return this;
			}

			public ListResourcesResult build() {
				return new ListResourcesResult(resources, nextCursor, meta);
			}

		}

		public List<Resource> resources() {
			return this.resources;
		}

		public String nextCursor() {
			return this.nextCursor;
		}

		public Map<String, Object> meta() {
			return this.meta;
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) {
				return true;
			}
			if (!(o instanceof ListResourcesResult)) {
				return false;
			}
			ListResourcesResult that = (ListResourcesResult) o;
			return Objects.equals(this.resources, that.resources) && Objects.equals(this.nextCursor, that.nextCursor)
					&& Objects.equals(this.meta, that.meta);
		}

		@Override
		public int hashCode() {
			return Objects.hash(this.resources, this.nextCursor, this.meta);
		}

		@Override
		public String toString() {
			return "ListResourcesResult[resources=" + this.resources + ", nextCursor=" + this.nextCursor + ", meta="
					+ this.meta + "]";
		}
	}

	/**
	 * The server's response to a resources/templates/list request from the client.
	 *
	 * @param resourceTemplates A list of resource templates that the server provides
	 * @param nextCursor An opaque token representing the pagination position after the
	 * last returned result. If present, there may be more results available
	 * @param meta See specification for notes on _meta usage
	 */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static final class ListResourceTemplatesResult implements Result {

		@JsonProperty("resourceTemplates")
		private final List<ResourceTemplate> resourceTemplates;

		@JsonProperty("nextCursor")
		private final String nextCursor;

		@JsonProperty("_meta")
		private final Map<String, Object> meta;

		public ListResourceTemplatesResult(List<ResourceTemplate> resourceTemplates, String nextCursor,
				Map<String, Object> meta) {
			Assert.notNull(resourceTemplates, "resourceTemplates must not be null");
			this.resourceTemplates = resourceTemplates;
			this.nextCursor = nextCursor;
			this.meta = meta;
		}

		@JsonCreator
		static ListResourceTemplatesResult fromJson(
				@JsonProperty("resourceTemplates") List<ResourceTemplate> resourceTemplates,
				@JsonProperty("nextCursor") String nextCursor, @JsonProperty("_meta") Map<String, Object> meta) {
			if (resourceTemplates == null) {
				logger.warn(
						"ListResourceTemplatesResult: missing required field 'resourceTemplates' during deserialization, using default []");
				resourceTemplates = Collections.emptyList();
			}
			return new ListResourceTemplatesResult(resourceTemplates, nextCursor, meta);
		}

		@Deprecated
		public ListResourceTemplatesResult(List<ResourceTemplate> resourceTemplates, String nextCursor) {
			this(resourceTemplates, nextCursor, null);
		}

		public static Builder builder(List<ResourceTemplate> resourceTemplates) {
			return new Builder(resourceTemplates);
		}

		public static class Builder {

			private final List<ResourceTemplate> resourceTemplates;

			private String nextCursor;

			private Map<String, Object> meta;

			private Builder(List<ResourceTemplate> resourceTemplates) {
				Assert.notNull(resourceTemplates, "resourceTemplates must not be null");
				this.resourceTemplates = resourceTemplates;
			}

			public Builder nextCursor(String nextCursor) {
				this.nextCursor = nextCursor;
				return this;
			}

			public Builder meta(Map<String, Object> meta) {
				this.meta = meta;
				return this;
			}

			public ListResourceTemplatesResult build() {
				return new ListResourceTemplatesResult(resourceTemplates, nextCursor, meta);
			}

		}

		public List<ResourceTemplate> resourceTemplates() {
			return this.resourceTemplates;
		}

		public String nextCursor() {
			return this.nextCursor;
		}

		public Map<String, Object> meta() {
			return this.meta;
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) {
				return true;
			}
			if (!(o instanceof ListResourceTemplatesResult)) {
				return false;
			}
			ListResourceTemplatesResult that = (ListResourceTemplatesResult) o;
			return Objects.equals(this.resourceTemplates, that.resourceTemplates)
					&& Objects.equals(this.nextCursor, that.nextCursor) && Objects.equals(this.meta, that.meta);
		}

		@Override
		public int hashCode() {
			return Objects.hash(this.resourceTemplates, this.nextCursor, this.meta);
		}

		@Override
		public String toString() {
			return "ListResourceTemplatesResult[resourceTemplates=" + this.resourceTemplates + ", nextCursor="
					+ this.nextCursor + ", meta=" + this.meta + "]";
		}
	}

	/**
	 * Sent from the client to the server, to read a specific resource URI.
	 *
	 * @param uri The URI of the resource to read. The URI can use any protocol; it is up
	 * to the server how to interpret it
	 * @param meta See specification for notes on _meta usage
	 */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static final class ReadResourceRequest implements Request {

		@JsonProperty("uri")
		private final String uri;

		@JsonProperty("_meta")
		private final Map<String, Object> meta;

		public ReadResourceRequest(String uri, Map<String, Object> meta) {
			Assert.notNull(uri, "uri must not be null");
			this.uri = uri;
			this.meta = meta;
		}

		@JsonCreator
		static ReadResourceRequest fromJson(@JsonProperty("uri") String uri,
				@JsonProperty("_meta") Map<String, Object> meta) {
			if (uri == null) {
				logger
					.warn("ReadResourceRequest: missing required field 'uri' during deserialization, using default ''");
				uri = "";
			}
			return new ReadResourceRequest(uri, meta);
		}

		@Deprecated
		public ReadResourceRequest(String uri) {
			this(uri, null);
		}

		public static Builder builder(String uri) {
			return new Builder(uri);
		}

		public static class Builder {

			private final String uri;

			private Map<String, Object> meta;

			private Builder(String uri) {
				Assert.hasText(uri, "uri must not be empty");
				this.uri = uri;
			}

			public Builder meta(Map<String, Object> meta) {
				this.meta = meta;
				return this;
			}

			public ReadResourceRequest build() {
				return new ReadResourceRequest(uri, meta);
			}

		}

		public String uri() {
			return this.uri;
		}

		public Map<String, Object> meta() {
			return this.meta;
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) {
				return true;
			}
			if (!(o instanceof ReadResourceRequest)) {
				return false;
			}
			ReadResourceRequest that = (ReadResourceRequest) o;
			return Objects.equals(this.uri, that.uri) && Objects.equals(this.meta, that.meta);
		}

		@Override
		public int hashCode() {
			return Objects.hash(this.uri, this.meta);
		}

		@Override
		public String toString() {
			return "ReadResourceRequest[uri=" + this.uri + ", meta=" + this.meta + "]";
		}
	}

	/**
	 * The server's response to a resources/read request from the client.
	 *
	 * @param contents The contents of the resource
	 * @param meta See specification for notes on _meta usage
	 */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static final class ReadResourceResult implements Result {

		@JsonProperty("contents")
		private final List<ResourceContents> contents;

		@JsonProperty("_meta")
		private final Map<String, Object> meta;

		public ReadResourceResult(List<ResourceContents> contents, Map<String, Object> meta) {
			Assert.notNull(contents, "contents must not be null");
			this.contents = contents;
			this.meta = meta;
		}

		@JsonCreator
		static ReadResourceResult fromJson(@JsonProperty("contents") List<ResourceContents> contents,
				@JsonProperty("_meta") Map<String, Object> meta) {
			if (contents == null) {
				logger.warn(
						"ReadResourceResult: missing required field 'contents' during deserialization, using default []");
				contents = Collections.emptyList();
			}
			return new ReadResourceResult(contents, meta);
		}

		@Deprecated
		public ReadResourceResult(List<ResourceContents> contents) {
			this(contents, null);
		}

		public static Builder builder(List<ResourceContents> contents) {
			return new Builder(contents);
		}

		public static class Builder {

			private final List<ResourceContents> contents;

			private Map<String, Object> meta;

			private Builder(List<ResourceContents> contents) {
				Assert.notNull(contents, "contents must not be null");
				this.contents = contents;
			}

			public Builder meta(Map<String, Object> meta) {
				this.meta = meta;
				return this;
			}

			public ReadResourceResult build() {
				return new ReadResourceResult(contents, meta);
			}

		}

		public List<ResourceContents> contents() {
			return this.contents;
		}

		public Map<String, Object> meta() {
			return this.meta;
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) {
				return true;
			}
			if (!(o instanceof ReadResourceResult)) {
				return false;
			}
			ReadResourceResult that = (ReadResourceResult) o;
			return Objects.equals(this.contents, that.contents) && Objects.equals(this.meta, that.meta);
		}

		@Override
		public int hashCode() {
			return Objects.hash(this.contents, this.meta);
		}

		@Override
		public String toString() {
			return "ReadResourceResult[contents=" + this.contents + ", meta=" + this.meta + "]";
		}
	}

	/**
	 * Sent from the client to request resources/updated notifications from the server
	 * whenever a particular resource changes.
	 *
	 * @param uri the URI of the resource to subscribe to. The URI can use any protocol;
	 * it is up to the server how to interpret it.
	 * @param meta See specification for notes on _meta usage
	 */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static final class SubscribeRequest implements Request {

		@JsonProperty("uri")
		private final String uri;

		@JsonProperty("_meta")
		private final Map<String, Object> meta;

		public SubscribeRequest(String uri, Map<String, Object> meta) {
			Assert.notNull(uri, "uri must not be null");
			this.uri = uri;
			this.meta = meta;
		}

		@JsonCreator
		static SubscribeRequest fromJson(@JsonProperty("uri") String uri,
				@JsonProperty("_meta") Map<String, Object> meta) {
			if (uri == null) {
				logger.warn("SubscribeRequest: missing required field 'uri' during deserialization, using default ''");
				uri = "";
			}
			return new SubscribeRequest(uri, meta);
		}

		@Deprecated
		public SubscribeRequest(String uri) {
			this(uri, null);
		}

		public static Builder builder(String uri) {
			return new Builder(uri);
		}

		public static class Builder {

			private final String uri;

			private Map<String, Object> meta;

			private Builder(String uri) {
				Assert.hasText(uri, "uri must not be empty");
				this.uri = uri;
			}

			public Builder meta(Map<String, Object> meta) {
				this.meta = meta;
				return this;
			}

			public SubscribeRequest build() {
				return new SubscribeRequest(uri, meta);
			}

		}

		public String uri() {
			return this.uri;
		}

		public Map<String, Object> meta() {
			return this.meta;
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) {
				return true;
			}
			if (!(o instanceof SubscribeRequest)) {
				return false;
			}
			SubscribeRequest that = (SubscribeRequest) o;
			return Objects.equals(this.uri, that.uri) && Objects.equals(this.meta, that.meta);
		}

		@Override
		public int hashCode() {
			return Objects.hash(this.uri, this.meta);
		}

		@Override
		public String toString() {
			return "SubscribeRequest[uri=" + this.uri + ", meta=" + this.meta + "]";
		}
	}

	/**
	 * Sent from the client to request cancellation of resources/updated notifications
	 * from the server. This should follow a previous resources/subscribe request.
	 *
	 * @param uri The URI of the resource to unsubscribe from
	 * @param meta See specification for notes on _meta usage
	 */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static final class UnsubscribeRequest implements Request {

		@JsonProperty("uri")
		private final String uri;

		@JsonProperty("_meta")
		private final Map<String, Object> meta;

		public UnsubscribeRequest(String uri, Map<String, Object> meta) {
			Assert.notNull(uri, "uri must not be null");
			this.uri = uri;
			this.meta = meta;
		}

		@JsonCreator
		static UnsubscribeRequest fromJson(@JsonProperty("uri") String uri,
				@JsonProperty("_meta") Map<String, Object> meta) {
			if (uri == null) {
				logger
					.warn("UnsubscribeRequest: missing required field 'uri' during deserialization, using default ''");
				uri = "";
			}
			return new UnsubscribeRequest(uri, meta);
		}

		@Deprecated
		public UnsubscribeRequest(String uri) {
			this(uri, null);
		}

		public static Builder builder(String uri) {
			return new Builder(uri);
		}

		public static class Builder {

			private final String uri;

			private Map<String, Object> meta;

			private Builder(String uri) {
				Assert.hasText(uri, "uri must not be empty");
				this.uri = uri;
			}

			public Builder meta(Map<String, Object> meta) {
				this.meta = meta;
				return this;
			}

			public UnsubscribeRequest build() {
				return new UnsubscribeRequest(uri, meta);
			}

		}

		public String uri() {
			return this.uri;
		}

		public Map<String, Object> meta() {
			return this.meta;
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) {
				return true;
			}
			if (!(o instanceof UnsubscribeRequest)) {
				return false;
			}
			UnsubscribeRequest that = (UnsubscribeRequest) o;
			return Objects.equals(this.uri, that.uri) && Objects.equals(this.meta, that.meta);
		}

		@Override
		public int hashCode() {
			return Objects.hash(this.uri, this.meta);
		}

		@Override
		public String toString() {
			return "UnsubscribeRequest[uri=" + this.uri + ", meta=" + this.meta + "]";
		}
	}

	/**
	 * The contents of a specific resource or sub-resource.
	 */
	@JsonTypeInfo(use = JsonTypeInfo.Id.DEDUCTION)
	@JsonSubTypes({ @JsonSubTypes.Type(value = TextResourceContents.class),
			@JsonSubTypes.Type(value = BlobResourceContents.class) })
	public interface ResourceContents extends Meta {

		/**
		 * The URI of this resource.
		 * @return the URI of this resource.
		 */
		String uri();

		/**
		 * The MIME type of this resource.
		 * @return the MIME type of this resource.
		 */
		String mimeType();

	}

	/**
	 * Text contents of a resource.
	 *
	 * @param uri the URI of this resource.
	 * @param mimeType the MIME type of this resource.
	 * @param text the text of the resource. This must only be set if the resource can
	 * actually be represented as text (not binary data).
	 * @param meta See specification for notes on _meta usage
	 */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static final class TextResourceContents implements ResourceContents {

		@JsonProperty("uri")
		private final String uri;

		@JsonProperty("mimeType")
		private final String mimeType;

		@JsonProperty("text")
		private final String text;

		@JsonProperty("_meta")
		private final Map<String, Object> meta;

		public TextResourceContents(String uri, String mimeType, String text, Map<String, Object> meta) {
			Assert.notNull(uri, "uri must not be null");
			Assert.notNull(text, "text must not be null");
			this.uri = uri;
			this.mimeType = mimeType;
			this.text = text;
			this.meta = meta;
		}

		@JsonCreator
		static TextResourceContents fromJson(@JsonProperty("uri") String uri, @JsonProperty("mimeType") String mimeType,
				@JsonProperty("text") String text, @JsonProperty("_meta") Map<String, Object> meta) {
			if (uri == null || text == null) {
				List<String> missing = new ArrayList<>();
				if (uri == null) {
					missing.add("uri -> ''");
					uri = "";
				}
				if (text == null) {
					missing.add("text -> ''");
					text = "";
				}
				logger.warn("TextResourceContents: missing required fields during deserialization: {}",
						String.join(", ", missing));
			}
			return new TextResourceContents(uri, mimeType, text, meta);
		}

		@Deprecated
		public TextResourceContents(String uri, String mimeType, String text) {
			this(uri, mimeType, text, null);
		}

		public static Builder builder(String uri, String text) {
			return new Builder(uri, text);
		}

		public static class Builder {

			private final String uri;

			private String mimeType;

			private final String text;

			private Map<String, Object> meta;

			private Builder(String uri, String text) {
				Assert.hasText(uri, "uri must not be empty");
				Assert.notNull(text, "text must not be null");
				this.uri = uri;
				this.text = text;
			}

			public Builder mimeType(String mimeType) {
				this.mimeType = mimeType;
				return this;
			}

			public Builder meta(Map<String, Object> meta) {
				this.meta = meta;
				return this;
			}

			public TextResourceContents build() {
				return new TextResourceContents(uri, mimeType, text, meta);
			}

		}

		public String uri() {
			return this.uri;
		}

		public String mimeType() {
			return this.mimeType;
		}

		public String text() {
			return this.text;
		}

		public Map<String, Object> meta() {
			return this.meta;
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) {
				return true;
			}
			if (!(o instanceof TextResourceContents)) {
				return false;
			}
			TextResourceContents that = (TextResourceContents) o;
			return Objects.equals(this.uri, that.uri) && Objects.equals(this.mimeType, that.mimeType)
					&& Objects.equals(this.text, that.text) && Objects.equals(this.meta, that.meta);
		}

		@Override
		public int hashCode() {
			return Objects.hash(this.uri, this.mimeType, this.text, this.meta);
		}

		@Override
		public String toString() {
			return "TextResourceContents[uri=" + this.uri + ", mimeType=" + this.mimeType + ", text=" + this.text
					+ ", meta=" + this.meta + "]";
		}
	}

	/**
	 * Binary contents of a resource.
	 *
	 * @param uri the URI of this resource.
	 * @param mimeType the MIME type of this resource.
	 * @param blob a base64-encoded string representing the binary data of the resource.
	 * This must only be set if the resource can actually be represented as binary data
	 * (not text).
	 * @param meta See specification for notes on _meta usage
	 */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static final class BlobResourceContents implements ResourceContents {

		@JsonProperty("uri")
		private final String uri;

		@JsonProperty("mimeType")
		private final String mimeType;

		@JsonProperty("blob")
		private final String blob;

		@JsonProperty("_meta")
		private final Map<String, Object> meta;

		public BlobResourceContents(String uri, String mimeType, String blob, Map<String, Object> meta) {
			Assert.notNull(uri, "uri must not be null");
			Assert.notNull(blob, "blob must not be null");
			this.uri = uri;
			this.mimeType = mimeType;
			this.blob = blob;
			this.meta = meta;
		}

		@JsonCreator
		static BlobResourceContents fromJson(@JsonProperty("uri") String uri, @JsonProperty("mimeType") String mimeType,
				@JsonProperty("blob") String blob, @JsonProperty("_meta") Map<String, Object> meta) {
			if (uri == null || blob == null) {
				List<String> missing = new ArrayList<>();
				if (uri == null) {
					missing.add("uri -> ''");
					uri = "";
				}
				if (blob == null) {
					missing.add("blob -> ''");
					blob = "";
				}
				logger.warn("BlobResourceContents: missing required fields during deserialization: {}",
						String.join(", ", missing));
			}
			return new BlobResourceContents(uri, mimeType, blob, meta);
		}

		@Deprecated
		public BlobResourceContents(String uri, String mimeType, String blob) {
			this(uri, mimeType, blob, null);
		}

		public static Builder builder(String uri, String blob) {
			return new Builder(uri, blob);
		}

		public static class Builder {

			private final String uri;

			private String mimeType;

			private final String blob;

			private Map<String, Object> meta;

			private Builder(String uri, String blob) {
				Assert.hasText(uri, "uri must not be empty");
				Assert.notNull(blob, "blob must not be null");
				this.uri = uri;
				this.blob = blob;
			}

			public Builder mimeType(String mimeType) {
				this.mimeType = mimeType;
				return this;
			}

			public Builder meta(Map<String, Object> meta) {
				this.meta = meta;
				return this;
			}

			public BlobResourceContents build() {
				return new BlobResourceContents(uri, mimeType, blob, meta);
			}

		}

		public String uri() {
			return this.uri;
		}

		public String mimeType() {
			return this.mimeType;
		}

		public String blob() {
			return this.blob;
		}

		public Map<String, Object> meta() {
			return this.meta;
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) {
				return true;
			}
			if (!(o instanceof BlobResourceContents)) {
				return false;
			}
			BlobResourceContents that = (BlobResourceContents) o;
			return Objects.equals(this.uri, that.uri) && Objects.equals(this.mimeType, that.mimeType)
					&& Objects.equals(this.blob, that.blob) && Objects.equals(this.meta, that.meta);
		}

		@Override
		public int hashCode() {
			return Objects.hash(this.uri, this.mimeType, this.blob, this.meta);
		}

		@Override
		public String toString() {
			return "BlobResourceContents[uri=" + this.uri + ", mimeType=" + this.mimeType + ", blob=" + this.blob
					+ ", meta=" + this.meta + "]";
		}
	}

	// ---------------------------
	// Prompt Interfaces
	// ---------------------------
	/**
	 * A prompt or prompt template that the server offers.
	 *
	 * @param name The name of the prompt or prompt template.
	 * @param title An optional title for the prompt.
	 * @param description An optional description of what this prompt provides.
	 * @param arguments A list of arguments to use for templating the prompt.
	 * @param icons Optional list of icons for this prompt.
	 * @param meta See specification for notes on _meta usage
	 */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static final class Prompt implements Identifier {

		@JsonProperty("name")
		private final String name;

		@JsonProperty("title")
		private final String title;

		@JsonProperty("description")
		private final String description;

		@JsonProperty("arguments")
		private final List<PromptArgument> arguments;

		@JsonProperty("_meta")
		private final Map<String, Object> meta;

		@JsonProperty("icons")
		private final List<Icon> icons;

		public Prompt(String name, String title, String description, List<PromptArgument> arguments,
				Map<String, Object> meta, List<Icon> icons) {
			Assert.notNull(name, "name must not be null");
			this.name = name;
			this.title = title;
			this.description = description;
			this.arguments = arguments;
			this.meta = meta;
			this.icons = icons;
		}

		@JsonCreator
		static Prompt fromJson(@JsonProperty("name") String name, @JsonProperty("title") String title,
				@JsonProperty("description") String description,
				@JsonProperty("arguments") List<PromptArgument> arguments,
				@JsonProperty("_meta") Map<String, Object> meta, @JsonProperty("icons") List<Icon> icons) {
			if (name == null) {
				logger.warn("Prompt: missing required field 'name' during deserialization, using default ''");
				name = "";
			}
			return new Prompt(name, title, description, arguments, meta, icons);
		}

		@Deprecated
		public Prompt(String name, String description, List<PromptArgument> arguments) {
			this(name, null, description, arguments, null, null);
		}

		@Deprecated
		public Prompt(String name, String title, String description, List<PromptArgument> arguments) {
			this(name, title, description, arguments, null, null);
		}

		@Deprecated
		public Prompt(String name, String title, String description, List<PromptArgument> arguments,
				Map<String, Object> meta) {
			this(name, title, description, arguments, meta, null);
		}

		public static Builder builder(String name) {
			return new Builder(name);
		}

		public static class Builder {

			private final String name;

			private String title;

			private String description;

			private List<PromptArgument> arguments;

			private List<Icon> icons;

			private Map<String, Object> meta;

			private Builder(String name) {
				Assert.hasText(name, "name must not be empty");
				this.name = name;
			}

			public Builder title(String title) {
				this.title = title;
				return this;
			}

			public Builder description(String description) {
				this.description = description;
				return this;
			}

			public Builder arguments(List<PromptArgument> arguments) {
				this.arguments = arguments;
				return this;
			}

			public Builder icons(List<Icon> icons) {
				this.icons = icons;
				return this;
			}

			public Builder meta(Map<String, Object> meta) {
				this.meta = meta;
				return this;
			}

			public Prompt build() {
				return new Prompt(name, title, description, arguments, meta, icons);
			}

		}

		public String name() {
			return this.name;
		}

		public String title() {
			return this.title;
		}

		public String description() {
			return this.description;
		}

		public List<PromptArgument> arguments() {
			return this.arguments;
		}

		public Map<String, Object> meta() {
			return this.meta;
		}

		public List<Icon> icons() {
			return this.icons;
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) {
				return true;
			}
			if (!(o instanceof Prompt)) {
				return false;
			}
			Prompt that = (Prompt) o;
			return Objects.equals(this.name, that.name) && Objects.equals(this.title, that.title)
					&& Objects.equals(this.description, that.description)
					&& Objects.equals(this.arguments, that.arguments) && Objects.equals(this.meta, that.meta)
					&& Objects.equals(this.icons, that.icons);
		}

		@Override
		public int hashCode() {
			return Objects.hash(this.name, this.title, this.description, this.arguments, this.meta, this.icons);
		}

		@Override
		public String toString() {
			return "Prompt[name=" + this.name + ", title=" + this.title + ", description=" + this.description
					+ ", arguments=" + this.arguments + ", meta=" + this.meta + ", icons=" + this.icons + "]";
		}
	}

	/**
	 * Describes an argument that a prompt can accept.

	/**
	 * Describes an argument that a prompt can accept.
	 *
	 * @param name The name of the argument.
	 * @param title An optional title for the argument, which can be used in UI
	 * @param description A human-readable description of the argument.
	 * @param required Whether this argument must be provided.
	 */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static final class PromptArgument implements Identifier {

		@JsonProperty("name")
		private final String name;

		@JsonProperty("title")
		private final String title;

		@JsonProperty("description")
		private final String description;

		@JsonProperty("required")
		private final Boolean required;

		@JsonCreator
		public PromptArgument( // @formatter:off
				@JsonProperty("name") String name,
				@JsonProperty("title") String title,
				@JsonProperty("description") String description,
				@JsonProperty("required") Boolean required) { // @formatter:on
			Assert.hasText(name, "name must not be empty");
			this.name = name;
			this.title = title;
			this.description = description;
			this.required = required;
		}

		@Deprecated
		public PromptArgument(String name, String description, Boolean required) {
			this(name, null, description, required);
		}

		public static Builder builder(String name) {
			return new Builder(name);
		}

		public static class Builder {

			private final String name;

			private String title;

			private String description;

			private Boolean required;

			private Builder(String name) {
				Assert.hasText(name, "name must not be empty");
				this.name = name;
			}

			public Builder title(String title) {
				this.title = title;
				return this;
			}

			public Builder description(String description) {
				this.description = description;
				return this;
			}

			public Builder required(Boolean required) {
				this.required = required;
				return this;
			}

			public PromptArgument build() {
				return new PromptArgument(name, title, description, required);
			}

		}

		public String name() {
			return this.name;
		}

		public String title() {
			return this.title;
		}

		public String description() {
			return this.description;
		}

		public Boolean required() {
			return this.required;
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) {
				return true;
			}
			if (!(o instanceof PromptArgument)) {
				return false;
			}
			PromptArgument that = (PromptArgument) o;
			return Objects.equals(this.name, that.name) && Objects.equals(this.title, that.title)
					&& Objects.equals(this.description, that.description)
					&& Objects.equals(this.required, that.required);
		}

		@Override
		public int hashCode() {
			return Objects.hash(this.name, this.title, this.description, this.required);
		}

		@Override
		public String toString() {
			return "PromptArgument[name=" + this.name + ", title=" + this.title + ", description=" + this.description
					+ ", required=" + this.required + "]";
		}
	}

	/**
	 * Describes a message returned as part of a prompt.
	 *
	 * This is similar to `SamplingMessage`, but also supports the embedding of resources
	 * from the MCP server.
	 *
	 * @param role The sender or recipient of messages and data in a conversation.
	 * @param content The content of the message of type {@link Content}.
	 */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static final class PromptMessage {

		@JsonProperty("role")
		private final Role role;

		@JsonProperty("content")
		private final Content content;

		public PromptMessage(Role role, Content content) {
			Assert.notNull(role, "role must not be null");
			Assert.notNull(content, "content must not be null");
			this.role = role;
			this.content = content;
		}

		@JsonCreator
		static PromptMessage fromJson(@JsonProperty("role") Role role, @JsonProperty("content") Content content) {
			if (role == null || content == null) {
				List<String> missing = new ArrayList<>();
				if (role == null) {
					missing.add("role -> 'user'");
					role = Role.USER;
				}
				if (content == null) {
					missing.add("content -> ''");
					content = TextContent.builder("").build();
				}
				logger.warn("PromptMessage: missing required fields during deserialization: {}",
						String.join(", ", missing));
			}
			return new PromptMessage(role, content);
		}

		public static Builder builder(Role role, Content content) {
			return new Builder(role, content);
		}

		public static class Builder {

			private final Role role;

			private final Content content;

			private Builder(Role role, Content content) {
				Assert.notNull(role, "role must not be null");
				Assert.notNull(content, "content must not be null");
				this.role = role;
				this.content = content;
			}

			public PromptMessage build() {
				return new PromptMessage(role, content);
			}

		}

		public Role role() {
			return this.role;
		}

		public Content content() {
			return this.content;
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) {
				return true;
			}
			if (!(o instanceof PromptMessage)) {
				return false;
			}
			PromptMessage that = (PromptMessage) o;
			return Objects.equals(this.role, that.role) && Objects.equals(this.content, that.content);
		}

		@Override
		public int hashCode() {
			return Objects.hash(this.role, this.content);
		}

		@Override
		public String toString() {
			return "PromptMessage[role=" + this.role + ", content=" + this.content + "]";
		}
	}

	/**
	 * The server's response to a prompts/list request from the client.
	 *
	 * @param prompts A list of prompts that the server provides.
	 * @param nextCursor An optional cursor for pagination. If present, indicates there
	 * are more prompts available.
	 * @param meta See specification for notes on _meta usage
	 */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static final class ListPromptsResult implements Result {

		@JsonProperty("prompts")
		private final List<Prompt> prompts;

		@JsonProperty("nextCursor")
		private final String nextCursor;

		@JsonProperty("_meta")
		private final Map<String, Object> meta;

		public ListPromptsResult(List<Prompt> prompts, String nextCursor, Map<String, Object> meta) {
			Assert.notNull(prompts, "prompts must not be null");
			this.prompts = prompts;
			this.nextCursor = nextCursor;
			this.meta = meta;
		}

		@JsonCreator
		static ListPromptsResult fromJson(@JsonProperty("prompts") List<Prompt> prompts,
				@JsonProperty("nextCursor") String nextCursor, @JsonProperty("_meta") Map<String, Object> meta) {
			if (prompts == null) {
				logger.warn(
						"ListPromptsResult: missing required field 'prompts' during deserialization, using default []");
				prompts = Collections.emptyList();
			}
			return new ListPromptsResult(prompts, nextCursor, meta);
		}

		@Deprecated
		public ListPromptsResult(List<Prompt> prompts, String nextCursor) {
			this(prompts, nextCursor, null);
		}

		public static Builder builder(List<Prompt> prompts) {
			return new Builder(prompts);
		}

		public static class Builder {

			private final List<Prompt> prompts;

			private String nextCursor;

			private Map<String, Object> meta;

			private Builder(List<Prompt> prompts) {
				Assert.notNull(prompts, "prompts must not be null");
				this.prompts = prompts;
			}

			public Builder nextCursor(String nextCursor) {
				this.nextCursor = nextCursor;
				return this;
			}

			public Builder meta(Map<String, Object> meta) {
				this.meta = meta;
				return this;
			}

			public ListPromptsResult build() {
				return new ListPromptsResult(prompts, nextCursor, meta);
			}

		}

		public List<Prompt> prompts() {
			return this.prompts;
		}

		public String nextCursor() {
			return this.nextCursor;
		}

		public Map<String, Object> meta() {
			return this.meta;
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) {
				return true;
			}
			if (!(o instanceof ListPromptsResult)) {
				return false;
			}
			ListPromptsResult that = (ListPromptsResult) o;
			return Objects.equals(this.prompts, that.prompts) && Objects.equals(this.nextCursor, that.nextCursor)
					&& Objects.equals(this.meta, that.meta);
		}

		@Override
		public int hashCode() {
			return Objects.hash(this.prompts, this.nextCursor, this.meta);
		}

		@Override
		public String toString() {
			return "ListPromptsResult[prompts=" + this.prompts + ", nextCursor=" + this.nextCursor + ", meta=" + this.meta
					+ "]";
		}
	}

	/**
	 * Used by the client to get a prompt provided by the server.
	 *
	 * @param name The name of the prompt or prompt template.
	 * @param arguments Arguments to use for templating the prompt.
	 * @param meta See specification for notes on _meta usage
	 */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static final class GetPromptRequest implements Request {

		@JsonProperty("name")
		private final String name;

		@JsonProperty("arguments")
		private final Map<String, Object> arguments;

		@JsonProperty("_meta")
		private final Map<String, Object> meta;

		public GetPromptRequest(String name, Map<String, Object> arguments, Map<String, Object> meta) {
			Assert.notNull(name, "name must not be null");
			this.name = name;
			this.arguments = arguments;
			this.meta = meta;
		}

		@JsonCreator
		static GetPromptRequest fromJson(@JsonProperty("name") String name,
				@JsonProperty("arguments") Map<String, Object> arguments,
				@JsonProperty("_meta") Map<String, Object> meta) {
			if (name == null) {
				logger.warn("GetPromptRequest: missing required field 'name' during deserialization, using default ''");
				name = "";
			}
			return new GetPromptRequest(name, arguments, meta);
		}

		@Deprecated
		public GetPromptRequest(String name, Map<String, Object> arguments) {
			this(name, arguments, null);
		}

		public static Builder builder(String name) {
			return new Builder(name);
		}

		public static class Builder {

			private final String name;

			private Map<String, Object> arguments;

			private Map<String, Object> meta;

			private Builder(String name) {
				Assert.hasText(name, "name must not be empty");
				this.name = name;
			}

			public Builder arguments(Map<String, Object> arguments) {
				this.arguments = arguments;
				return this;
			}

			public Builder meta(Map<String, Object> meta) {
				this.meta = meta;
				return this;
			}

			public GetPromptRequest build() {
				return new GetPromptRequest(name, arguments, meta);
			}

		}

		public String name() {
			return this.name;
		}

		public Map<String, Object> arguments() {
			return this.arguments;
		}

		public Map<String, Object> meta() {
			return this.meta;
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) {
				return true;
			}
			if (!(o instanceof GetPromptRequest)) {
				return false;
			}
			GetPromptRequest that = (GetPromptRequest) o;
			return Objects.equals(this.name, that.name) && Objects.equals(this.arguments, that.arguments)
					&& Objects.equals(this.meta, that.meta);
		}

		@Override
		public int hashCode() {
			return Objects.hash(this.name, this.arguments, this.meta);
		}

		@Override
		public String toString() {
			return "GetPromptRequest[name=" + this.name + ", arguments=" + this.arguments + ", meta=" + this.meta + "]";
		}
	}

	/**
	 * The server's response to a prompts/get request from the client.
	 *
	 * @param description An optional description for the prompt.
	 * @param messages A list of messages to display as part of the prompt.
	 * @param meta See specification for notes on _meta usage
	 */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static final class GetPromptResult implements Result {

		@JsonProperty("description")
		private final String description;

		@JsonProperty("messages")
		private final List<PromptMessage> messages;

		@JsonProperty("_meta")
		private final Map<String, Object> meta;

		public GetPromptResult(String description, List<PromptMessage> messages, Map<String, Object> meta) {
			Assert.notNull(messages, "messages must not be null");
			this.description = description;
			this.messages = messages;
			this.meta = meta;
		}

		@JsonCreator
		static GetPromptResult fromJson(@JsonProperty("description") String description,
				@JsonProperty("messages") List<PromptMessage> messages,
				@JsonProperty("_meta") Map<String, Object> meta) {
			if (messages == null) {
				logger.warn(
						"GetPromptResult: missing required field 'messages' during deserialization, using default []");
				messages = Collections.emptyList();
			}
			return new GetPromptResult(description, messages, meta);
		}

		@Deprecated
		public GetPromptResult(String description, List<PromptMessage> messages) {
			this(description, messages, null);
		}

		public static Builder builder(List<PromptMessage> messages) {
			return new Builder(messages);
		}

		public static class Builder {

			private String description;

			private final List<PromptMessage> messages;

			private Map<String, Object> meta;

			private Builder(List<PromptMessage> messages) {
				Assert.notNull(messages, "messages must not be null");
				this.messages = messages;
			}

			public Builder description(String description) {
				this.description = description;
				return this;
			}

			public Builder meta(Map<String, Object> meta) {
				this.meta = meta;
				return this;
			}

			public GetPromptResult build() {
				return new GetPromptResult(description, messages, meta);
			}

		}

		public String description() {
			return this.description;
		}

		public List<PromptMessage> messages() {
			return this.messages;
		}

		public Map<String, Object> meta() {
			return this.meta;
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) {
				return true;
			}
			if (!(o instanceof GetPromptResult)) {
				return false;
			}
			GetPromptResult that = (GetPromptResult) o;
			return Objects.equals(this.description, that.description)
					&& Objects.equals(this.messages, that.messages) && Objects.equals(this.meta, that.meta);
		}

		@Override
		public int hashCode() {
			return Objects.hash(this.description, this.messages, this.meta);
		}

		@Override
		public String toString() {
			return "GetPromptResult[description=" + this.description + ", messages=" + this.messages + ", meta=" + this.meta
					+ "]";
		}
	}

	// ---------------------------
	// Tool Interfaces
	// ---------------------------
	/**
	 * The server's response to a tools/list request from the client.
	 *
	 * @param tools A list of tools that the server provides.
	 * @param nextCursor An optional cursor for pagination. If present, indicates there
	 * are more tools available.
	 * @param meta See specification for notes on _meta usage
	 */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static final class ListToolsResult implements Result {

		@JsonProperty("tools")
		private final List<Tool> tools;

		@JsonProperty("nextCursor")
		private final String nextCursor;

		@JsonProperty("_meta")
		private final Map<String, Object> meta;

		public ListToolsResult(List<Tool> tools, String nextCursor, Map<String, Object> meta) {
			Assert.notNull(tools, "tools must not be null");
			this.tools = tools;
			this.nextCursor = nextCursor;
			this.meta = meta;
		}

		@JsonCreator
		static ListToolsResult fromJson(@JsonProperty("tools") List<Tool> tools,
				@JsonProperty("nextCursor") String nextCursor, @JsonProperty("_meta") Map<String, Object> meta) {
			if (tools == null) {
				logger.warn("ListToolsResult: missing required field 'tools' during deserialization, using default []");
				tools = Collections.emptyList();
			}
			return new ListToolsResult(tools, nextCursor, meta);
		}

		@Deprecated
		public ListToolsResult(List<Tool> tools, String nextCursor) {
			this(tools, nextCursor, null);
		}

		public static Builder builder(List<Tool> tools) {
			return new Builder(tools);
		}

		public static class Builder {

			private final List<Tool> tools;

			private String nextCursor;

			private Map<String, Object> meta;

			private Builder(List<Tool> tools) {
				Assert.notNull(tools, "tools must not be null");
				this.tools = tools;
			}

			public Builder nextCursor(String nextCursor) {
				this.nextCursor = nextCursor;
				return this;
			}

			public Builder meta(Map<String, Object> meta) {
				this.meta = meta;
				return this;
			}

			public ListToolsResult build() {
				return new ListToolsResult(tools, nextCursor, meta);
			}

		}

		public List<Tool> tools() {
			return this.tools;
		}

		public String nextCursor() {
			return this.nextCursor;
		}

		public Map<String, Object> meta() {
			return this.meta;
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) {
				return true;
			}
			if (!(o instanceof ListToolsResult)) {
				return false;
			}
			ListToolsResult that = (ListToolsResult) o;
			return Objects.equals(this.tools, that.tools) && Objects.equals(this.nextCursor, that.nextCursor)
					&& Objects.equals(this.meta, that.meta);
		}

		@Override
		public int hashCode() {
			return Objects.hash(this.tools, this.nextCursor, this.meta);
		}

		@Override
		public String toString() {
			return "ListToolsResult[tools=" + this.tools + ", nextCursor=" + this.nextCursor + ", meta=" + this.meta + "]";
		}
	}

	/**
	 * A JSON Schema object that describes the expected structure of arguments or output.
	 *
	 * @param type The type of the schema (e.g., "object")
	 * @param properties The properties of the schema object
	 * @param required List of required property names
	 * @param additionalProperties Whether additional properties are allowed
	 * @param defs Schema definitions using the newer $defs keyword
	 * @param definitions Schema definitions using the legacy definitions keyword
	 * @deprecated use {@link Map} instead.
	 */
	@Deprecated
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static final class JsonSchema {

		@JsonProperty("type")
		private final String type;

		@JsonProperty("properties")
		private final Map<String, Object> properties;

		@JsonProperty("required")
		private final List<String> required;

		@JsonProperty("additionalProperties")
		private final Boolean additionalProperties;

		@JsonProperty("$defs")
		private final Map<String, Object> defs;

		@JsonProperty("definitions")
		private final Map<String, Object> definitions;

		@JsonCreator
		public JsonSchema( // @formatter:off
				@JsonProperty("type") String type,
				@JsonProperty("properties") Map<String, Object> properties,
				@JsonProperty("required") List<String> required,
				@JsonProperty("additionalProperties") Boolean additionalProperties,
				@JsonProperty("$defs") Map<String, Object> defs,
				@JsonProperty("definitions") Map<String, Object> definitions) { // @formatter:on
			this.type = type;
			this.properties = properties;
			this.required = required;
			this.additionalProperties = additionalProperties;
			this.defs = defs;
			this.definitions = definitions;
		}

		public static Builder builder() {
			return new Builder();
		}

		public static class Builder {

			private String type;

			private Map<String, Object> properties;

			private List<String> required;

			private Boolean additionalProperties;

			private Map<String, Object> defs;

			private Map<String, Object> definitions;

			public Builder type(String type) {
				this.type = type;
				return this;
			}

			public Builder properties(Map<String, Object> properties) {
				this.properties = properties;
				return this;
			}

			public Builder required(List<String> required) {
				this.required = required;
				return this;
			}

			public Builder additionalProperties(Boolean additionalProperties) {
				this.additionalProperties = additionalProperties;
				return this;
			}

			public Builder defs(Map<String, Object> defs) {
				this.defs = defs;
				return this;
			}

			public Builder definitions(Map<String, Object> definitions) {
				this.definitions = definitions;
				return this;
			}

			public JsonSchema build() {
				return new JsonSchema(type, properties, required, additionalProperties, defs, definitions);
			}

		}

		public String type() {
			return this.type;
		}

		public Map<String, Object> properties() {
			return this.properties;
		}

		public List<String> required() {
			return this.required;
		}

		public Boolean additionalProperties() {
			return this.additionalProperties;
		}

		public Map<String, Object> defs() {
			return this.defs;
		}

		public Map<String, Object> definitions() {
			return this.definitions;
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) {
				return true;
			}
			if (!(o instanceof JsonSchema)) {
				return false;
			}
			JsonSchema that = (JsonSchema) o;
			return Objects.equals(this.type, that.type) && Objects.equals(this.properties, that.properties)
					&& Objects.equals(this.required, that.required)
					&& Objects.equals(this.additionalProperties, that.additionalProperties)
					&& Objects.equals(this.defs, that.defs) && Objects.equals(this.definitions, that.definitions);
		}

		@Override
		public int hashCode() {
			return Objects.hash(this.type, this.properties, this.required, this.additionalProperties, this.defs,
					this.definitions);
		}

		@Override
		public String toString() {
			return "JsonSchema[type=" + this.type + ", properties=" + this.properties + ", required=" + this.required
					+ ", additionalProperties=" + this.additionalProperties + ", defs=" + this.defs + ", definitions="
					+ this.definitions + "]";
		}

	}

	/**
	 * Additional properties describing a Tool to clients.
	 *
	 * NOTE: all properties in ToolAnnotations are **hints**. They are not guaranteed to
	 * provide a faithful description of tool behavior (including descriptive properties
	 * like `title`).
	 *
	 * Clients should never make tool use decisions based on ToolAnnotations received from
	 * untrusted servers.
	 */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static final class ToolAnnotations {

		@JsonProperty("title")
		private final String title;

		@JsonProperty("readOnlyHint")
		private final Boolean readOnlyHint;

		@JsonProperty("destructiveHint")
		private final Boolean destructiveHint;

		@JsonProperty("idempotentHint")
		private final Boolean idempotentHint;

		@JsonProperty("openWorldHint")
		private final Boolean openWorldHint;

		@JsonProperty("returnDirect")
		private final Boolean returnDirect;

		@JsonCreator
		public ToolAnnotations( // @formatter:off
				@JsonProperty("title") String title,
				@JsonProperty("readOnlyHint") Boolean readOnlyHint,
				@JsonProperty("destructiveHint") Boolean destructiveHint,
				@JsonProperty("idempotentHint") Boolean idempotentHint,
				@JsonProperty("openWorldHint") Boolean openWorldHint,
				@JsonProperty("returnDirect") Boolean returnDirect) { // @formatter:on
			this.title = title;
			this.readOnlyHint = readOnlyHint;
			this.destructiveHint = destructiveHint;
			this.idempotentHint = idempotentHint;
			this.openWorldHint = openWorldHint;
			this.returnDirect = returnDirect;
		}

		public static Builder builder() {
			return new Builder();
		}

		public static class Builder {

			private String title;

			private Boolean readOnlyHint;

			private Boolean destructiveHint;

			private Boolean idempotentHint;

			private Boolean openWorldHint;

			private Boolean returnDirect;

			public Builder title(String title) {
				this.title = title;
				return this;
			}

			public Builder readOnlyHint(Boolean readOnlyHint) {
				this.readOnlyHint = readOnlyHint;
				return this;
			}

			public Builder destructiveHint(Boolean destructiveHint) {
				this.destructiveHint = destructiveHint;
				return this;
			}

			public Builder idempotentHint(Boolean idempotentHint) {
				this.idempotentHint = idempotentHint;
				return this;
			}

			public Builder openWorldHint(Boolean openWorldHint) {
				this.openWorldHint = openWorldHint;
				return this;
			}

			public Builder returnDirect(Boolean returnDirect) {
				this.returnDirect = returnDirect;
				return this;
			}

			public ToolAnnotations build() {
				return new ToolAnnotations(title, readOnlyHint, destructiveHint, idempotentHint, openWorldHint,
						returnDirect);
			}

		}

		public String title() {
			return this.title;
		}

		public Boolean readOnlyHint() {
			return this.readOnlyHint;
		}

		public Boolean destructiveHint() {
			return this.destructiveHint;
		}

		public Boolean idempotentHint() {
			return this.idempotentHint;
		}

		public Boolean openWorldHint() {
			return this.openWorldHint;
		}

		public Boolean returnDirect() {
			return this.returnDirect;
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) {
				return true;
			}
			if (!(o instanceof ToolAnnotations)) {
				return false;
			}
			ToolAnnotations that = (ToolAnnotations) o;
			return Objects.equals(this.title, that.title) && Objects.equals(this.readOnlyHint, that.readOnlyHint)
					&& Objects.equals(this.destructiveHint, that.destructiveHint)
					&& Objects.equals(this.idempotentHint, that.idempotentHint)
					&& Objects.equals(this.openWorldHint, that.openWorldHint)
					&& Objects.equals(this.returnDirect, that.returnDirect);
		}

		@Override
		public int hashCode() {
			return Objects.hash(this.title, this.readOnlyHint, this.destructiveHint, this.idempotentHint,
					this.openWorldHint, this.returnDirect);
		}

		@Override
		public String toString() {
			return "ToolAnnotations[title=" + this.title + ", readOnlyHint=" + this.readOnlyHint + ", destructiveHint="
					+ this.destructiveHint + ", idempotentHint=" + this.idempotentHint + ", openWorldHint="
					+ this.openWorldHint + ", returnDirect=" + this.returnDirect + "]";
		}
	}

	/**
	 * Represents a tool that the server provides. Tools enable servers to expose
	 * executable functionality to the system. Through these tools, you can interact with
	 * external systems, perform computations, and take actions in the real world.
	 *
	 * @param name A unique identifier for the tool. This name is used when calling the
	 * tool.
	 * @param title A human-readable title for the tool.
	 * @param description A human-readable description of what the tool does. This can be
	 * used by clients to improve the LLM's understanding of available tools.
	 * @param inputSchema A JSON Schema object that describes the expected structure of
	 * the arguments when calling this tool. Per SEP-1613, the dialect defaults to JSON
	 * Schema 2020-12 ({@link #JSON_SCHEMA_DIALECT_2020_12}) when no explicit
	 * {@code $schema} entry is present. To declare a different dialect, include a
	 * {@code "$schema"} key in the map. For tools with no parameters the spec recommends
	 * {@code {"type":"object","additionalProperties":false}}.
	 * @param outputSchema An optional JSON Schema object defining the structure of the
	 * tool's output returned in the structuredContent field of a CallToolResult. Same
	 * dialect rules as {@code inputSchema}.
	 * @param annotations Optional additional tool information.
	 * @param icons Optional list of icons for this tool.
	 * @param meta See specification for notes on _meta usage
	 */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static final class Tool {

		@JsonProperty("name")
		private final String name;

		@JsonProperty("title")
		private final String title;

		@JsonProperty("description")
		private final String description;

		@JsonProperty("inputSchema")
		private final Map<String, Object> inputSchema;

		@JsonProperty("outputSchema")
		private final Map<String, Object> outputSchema;

		@JsonProperty("annotations")
		private final ToolAnnotations annotations;

		@JsonProperty("_meta")
		private final Map<String, Object> meta;

		@JsonProperty("icons")
		private final List<Icon> icons;

		public Tool(String name, String title, String description, Map<String, Object> inputSchema,
				Map<String, Object> outputSchema, ToolAnnotations annotations, Map<String, Object> meta,
				List<Icon> icons) {
			Assert.notNull(name, "name must not be null");
			Assert.notNull(inputSchema, "inputSchema must not be null");
			this.name = name;
			this.title = title;
			this.description = description;
			this.inputSchema = inputSchema;
			this.outputSchema = outputSchema;
			this.annotations = annotations;
			this.meta = meta;
			this.icons = icons;
		}

		/**
		 * @deprecated Use {@link #builder(String, Map)}
		 */
		@Deprecated
		public Tool(String name, String title, String description, Map<String, Object> inputSchema,
				Map<String, Object> outputSchema, ToolAnnotations annotations, Map<String, Object> meta) {
			this(name, title, description, inputSchema, outputSchema, annotations, meta, null);
		}

		@JsonCreator
		static Tool fromJson(@JsonProperty("name") String name, @JsonProperty("title") String title,
				@JsonProperty("description") String description,
				@JsonProperty("inputSchema") Map<String, Object> inputSchema,
				@JsonProperty("outputSchema") Map<String, Object> outputSchema,
				@JsonProperty("annotations") ToolAnnotations annotations,
				@JsonProperty("_meta") Map<String, Object> meta, @JsonProperty("icons") List<Icon> icons) {
			if (name == null || inputSchema == null) {
				List<String> missing = new ArrayList<>();
				if (name == null) {
					missing.add("name -> ''");
					name = "";
				}
			if (inputSchema == null) {
					missing.add("inputSchema -> {}");
					inputSchema = Collections.emptyMap();
			}
				logger.warn("Tool: missing required fields during deserialization: {}", String.join(", ", missing));
			}
			return new Tool(name, title, description, inputSchema, outputSchema, annotations, meta, icons);
		}

		/**
		 * @deprecated Use {@link #builder(String, Map)} instead.
		 */
		@Deprecated
		public static Builder builder() {
			return new Builder();
		}

		/**
		 * Uses empty input schema.
		 * @param name
		 * @return
		 */
		@Deprecated
		public static Builder builder(String name) {
			return new Builder(name);
		}

		public static Builder builder(String name, Map<String, Object> inputSchema) {
			return new Builder(name, inputSchema);
		}

		public static Builder builder(String name, McpJsonMapper jsonMapper, String inputSchema) {
			return new Builder(name, schemaToMap(jsonMapper, inputSchema));
		}

		public static class Builder {

			private String name;

			private String title;

			private String description;

			private Map<String, Object> inputSchema;

			private Map<String, Object> outputSchema;

			private ToolAnnotations annotations;

			private List<Icon> icons;

			private Map<String, Object> meta;

			/**
			 * @deprecated Use {@link Tool#builder(String, Map)} instead.
			 */
			@Deprecated
			public Builder() {
			}

			/**
			 * @deprecated Use {@link Tool#builder(String, Map)} instead.
			 */
			@Deprecated
			private Builder(String name) {
				Assert.hasText(name, "name must not be empty");
				this.name = name;
			}

			private Builder(String name, Map<String, Object> inputSchema) {
				Assert.hasText(name, "name must not be empty");
				Assert.notNull(inputSchema, "inputSchema must not be null");
				this.name = name;
				this.inputSchema = inputSchema;
			}

			public Builder name(String name) {
				this.name = name;
				return this;
			}

			public Builder title(String title) {
				this.title = title;
				return this;
			}

			public Builder description(String description) {
				this.description = description;
				return this;
			}

			/**
			 * @deprecated use {@link #inputSchema(Map)} instead.
			 */
			@Deprecated
			public Builder inputSchema(JsonSchema inputSchema) {
				Map<String, Object> schema = new HashMap<>();
				if (inputSchema.type() != null)
					schema.put("type", inputSchema.type());
				if (inputSchema.properties() != null)
					schema.put("properties", inputSchema.properties());
				if (inputSchema.required() != null)
					schema.put("required", inputSchema.required());
				if (inputSchema.additionalProperties() != null)
					schema.put("additionalProperties", inputSchema.additionalProperties());
				if (inputSchema.defs() != null)
					schema.put("$defs", inputSchema.defs());
				if (inputSchema.definitions() != null)
					schema.put("definitions", inputSchema.definitions());
				return inputSchema(schema);
			}

			public Builder inputSchema(Map<String, Object> inputSchema) {
				this.inputSchema = inputSchema;
				return this;
			}

			public Builder inputSchema(McpJsonMapper jsonMapper, String inputSchema) {
				this.inputSchema = schemaToMap(jsonMapper, inputSchema);
				return this;
			}

			public Builder outputSchema(Map<String, Object> outputSchema) {
				this.outputSchema = outputSchema;
				return this;
			}

			public Builder outputSchema(McpJsonMapper jsonMapper, String outputSchema) {
				this.outputSchema = schemaToMap(jsonMapper, outputSchema);
				return this;
			}

			public Builder annotations(ToolAnnotations annotations) {
				this.annotations = annotations;
				return this;
			}

			public Builder icons(List<Icon> icons) {
				this.icons = icons;
				return this;
			}

			public Builder meta(Map<String, Object> meta) {
				this.meta = meta;
				return this;
			}

			public Tool build() {
				Assert.hasText(name, "name must not be empty");
				if (inputSchema == null) {
					logger.warn("Input schema was not set, falling back to empty schema");
					inputSchema = Collections.singletonMap("type", "object");
				}
				return new Tool(name, title, description, inputSchema, outputSchema, annotations, meta, icons);
			}

		}

		public String name() {
			return this.name;
		}

		public String title() {
			return this.title;
		}

		public String description() {
			return this.description;
		}

		public Map<String, Object> inputSchema() {
			return this.inputSchema;
		}

		public Map<String, Object> outputSchema() {
			return this.outputSchema;
		}

		public ToolAnnotations annotations() {
			return this.annotations;
		}

		public Map<String, Object> meta() {
			return this.meta;
		}

		public List<Icon> icons() {
			return this.icons;
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) {
				return true;
			}
			if (!(o instanceof Tool)) {
				return false;
			}
			Tool that = (Tool) o;
			return Objects.equals(this.name, that.name) && Objects.equals(this.title, that.title)
					&& Objects.equals(this.description, that.description)
					&& Objects.equals(this.inputSchema, that.inputSchema)
					&& Objects.equals(this.outputSchema, that.outputSchema)
					&& Objects.equals(this.annotations, that.annotations) && Objects.equals(this.meta, that.meta)
					&& Objects.equals(this.icons, that.icons);
		}

		@Override
		public int hashCode() {
			return Objects.hash(this.name, this.title, this.description, this.inputSchema, this.outputSchema,
					this.annotations, this.meta, this.icons);
		}

		@Override
		public String toString() {
			return "Tool[name=" + this.name + ", title=" + this.title + ", description=" + this.description
					+ ", inputSchema=" + this.inputSchema + ", outputSchema=" + this.outputSchema + ", annotations="
					+ this.annotations + ", meta=" + this.meta + ", icons=" + this.icons + "]";
		}
	}

	private static Map<String, Object> schemaToMap(McpJsonMapper jsonMapper, String schema) {
		try {
			return jsonMapper.readValue(schema, MAP_TYPE_REF);
		}
		catch (IOException e) {
			throw new IllegalArgumentException("Invalid schema: " + schema, e);
		}
	}

	/**
	 * Used by the client to call a tool provided by the server.
	 *
	 * @param name The name of the tool to call. This must match a tool name from
	 * tools/list.
	 * @param arguments Arguments to pass to the tool. These must conform to the tool's
	 * input schema.
	 * @param meta Optional metadata about the request. This can include additional
	 * information like `progressToken`
	 */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static final class CallToolRequest implements Request {

		@JsonProperty("name")
		private final String name;

		@JsonProperty("arguments")
		private final Map<String, Object> arguments;

		@JsonProperty("_meta")
		private final Map<String, Object> meta;

		public CallToolRequest(String name, Map<String, Object> arguments, Map<String, Object> meta) {
			Assert.notNull(name, "name must not be null");
			this.name = name;
			this.arguments = arguments;
			this.meta = meta;
		}

		@JsonCreator
		static CallToolRequest fromJson(@JsonProperty("name") String name,
				@JsonProperty("arguments") Map<String, Object> arguments,
				@JsonProperty("_meta") Map<String, Object> meta) {
			if (name == null) {
				logger.warn("CallToolRequest: missing required field 'name' during deserialization, using default ''");
				name = "";
			}
			return new CallToolRequest(name, arguments, meta);
		}

		@Deprecated
		public CallToolRequest(McpJsonMapper jsonMapper, String name, String jsonArguments) {
			this(name, parseJsonArguments(jsonMapper, jsonArguments), null);
		}

		@Deprecated
		public CallToolRequest(String name, Map<String, Object> arguments) {
			this(name, arguments, null);
		}

		private static Map<String, Object> parseJsonArguments(McpJsonMapper jsonMapper, String jsonArguments) {
			try {
				return jsonMapper.readValue(jsonArguments, MAP_TYPE_REF);
			}
			catch (IOException e) {
				throw new IllegalArgumentException("Invalid arguments: " + jsonArguments, e);
			}
		}

		/**
		 * @deprecated Use {@link #builder(String)} instead.
		 */
		@Deprecated
		public static Builder builder() {
			return new Builder();
		}

		public static Builder builder(String name) {
			return new Builder(name);
		}

		public static class Builder {

			private String name;

			private Map<String, Object> arguments;

			private Map<String, Object> meta;

			/**
			 * @deprecated Use {@link CallToolRequest#builder(String)} instead.
			 */
			@Deprecated
			public Builder() {
			}

			private Builder(String name) {
				Assert.hasText(name, "name must not be empty");
				this.name = name;
			}

			public Builder name(String name) {
				this.name = name;
				return this;
			}

			public Builder arguments(Map<String, Object> arguments) {
				this.arguments = arguments;
				return this;
			}

			public Builder arguments(McpJsonMapper jsonMapper, String jsonArguments) {
				this.arguments = parseJsonArguments(jsonMapper, jsonArguments);
				return this;
			}

			public Builder meta(Map<String, Object> meta) {
				this.meta = meta;
				return this;
			}

			public Builder progressToken(Object progressToken) {
				if (this.meta == null) {
					this.meta = new HashMap<>();
				}
				this.meta.put("progressToken", progressToken);
				return this;
			}

			public CallToolRequest build() {
				Assert.hasText(name, "name must not be empty");
				return new CallToolRequest(name, arguments, meta);
			}

		}

		public String name() {
			return this.name;
		}

		public Map<String, Object> arguments() {
			return this.arguments;
		}

		public Map<String, Object> meta() {
			return this.meta;
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) {
				return true;
			}
			if (!(o instanceof CallToolRequest)) {
				return false;
			}
			CallToolRequest that = (CallToolRequest) o;
			return Objects.equals(this.name, that.name) && Objects.equals(this.arguments, that.arguments)
					&& Objects.equals(this.meta, that.meta);
		}

		@Override
		public int hashCode() {
			return Objects.hash(this.name, this.arguments, this.meta);
		}

		@Override
		public String toString() {
			return "CallToolRequest[name=" + this.name + ", arguments=" + this.arguments + ", meta=" + this.meta + "]";
		}
	}

	/**
	 * The server's response to a tools/call request from the client.
	 *
	 * @param content A list of content items representing the tool's output. Each item
	 * can be text, an image, or an embedded resource.
	 * @param isError If true, indicates that the tool execution failed and the content
	 * contains error information. If false or absent, indicates successful execution.
	 * @param structuredContent An optional JSON object that represents the structured
	 * result of the tool call.
	 * @param meta See specification for notes on _meta usage
	 * <p>
	 * Note: {@code content} is required by the MCP specification. Deserialization accepts
	 * a missing value and substitutes an empty list to avoid breaking existing
	 * integrations that may omit the field.
	 */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static final class CallToolResult implements Result {

		@JsonProperty("content")
		private final List<Content> content;

		@JsonProperty("isError")
		private final Boolean isError;

		@JsonProperty("structuredContent")
		private final Object structuredContent;

		@JsonProperty("_meta")
		private final Map<String, Object> meta;

		public CallToolResult(List<Content> content, Boolean isError, Object structuredContent,
				Map<String, Object> meta) {
			Assert.notNull(content, "content must not be null");
			this.content = content;
			this.isError = isError;
			this.structuredContent = structuredContent;
			this.meta = meta;
		}

		@JsonCreator
		static CallToolResult fromJson(@JsonProperty("content") List<Content> content,
				@JsonProperty("isError") Boolean isError, @JsonProperty("structuredContent") Object structuredContent,
				@JsonProperty("_meta") Map<String, Object> meta) {
			if (content == null) {
				logger.warn("CallToolResult: missing required fields during deserialization: content -> []");
				content = Collections.emptyList();
			}
			return new CallToolResult(content, isError, structuredContent, meta);
		}

		/**
		 * Creates a builder for {@link CallToolResult} with the required content list.
		 * @param content the content list
		 * @return a new builder instance
		 */
		public static Builder builder(List<Content> content) {
			return new Builder(content);
		}

		/**
		 * Creates a builder for {@link CallToolResult}.
		 * @return a new builder instance
		 */
		public static Builder builder() {
			return new Builder(new ArrayList<>());
		}

		/**
		 * Builder for {@link CallToolResult}.
		 */
		public static class Builder {

			private List<Content> content = new ArrayList<>();

			private Boolean isError = false;

			/**
			 * @deprecated Use {@link CallToolResult#builder()} factory method instead of
			 * instantiating the builder directly.
			 */
			@Deprecated
			public Builder() {
			}

			private Builder(List<Content> content) {
				this.content.addAll(content);
			}

			private Object structuredContent;

			private Map<String, Object> meta;

			/**
			 * Sets the content list for the tool result.
			 * @param content the content list
			 * @return this builder
			 */
			public Builder content(List<Content> content) {
				Assert.notNull(content, "content must not be null");
				this.content = new ArrayList<>(content);
				return this;
			}

			public Builder structuredContent(Object structuredContent) {
				Assert.notNull(structuredContent, "structuredContent must not be null");
				this.structuredContent = structuredContent;
				return this;
			}

			public Builder structuredContent(McpJsonMapper jsonMapper, String structuredContent) {
				Assert.hasText(structuredContent, "structuredContent must not be empty");
				try {
					this.structuredContent = jsonMapper.readValue(structuredContent, MAP_TYPE_REF);
				}
				catch (IOException e) {
					throw new IllegalArgumentException("Invalid structured content: " + structuredContent, e);
				}
				return this;
			}

			/**
			 * Sets the text content for the tool result.
			 * @param textContent the text content
			 * @return this builder
			 */
			public Builder textContent(List<String> textContent) {
				Assert.notNull(textContent, "textContent must not be null");
				textContent.stream().map(t -> TextContent.builder(t).build()).forEach(this.content::add);
				return this;
			}

			/**
			 * Adds a content item to the tool result.
			 * @param contentItem the content item to add
			 * @return this builder
			 */
			public Builder addContent(Content contentItem) {
				Assert.notNull(contentItem, "contentItem must not be null");
				this.content.add(contentItem);
				return this;
			}

			/**
			 * Adds a text content item to the tool result.
			 * @param text the text content
			 * @return this builder
			 */
			public Builder addTextContent(String text) {
				Assert.notNull(text, "text must not be null");
				return addContent(TextContent.builder(text).build());
			}

			/**
			 * Sets whether the tool execution resulted in an error.
			 * @param isError true if the tool execution failed, false otherwise
			 * @return this builder
			 */
			public Builder isError(Boolean isError) {
				Assert.notNull(isError, "isError must not be null");
				this.isError = isError;
				return this;
			}

			/**
			 * Sets the metadata for the tool result.
			 * @param meta metadata
			 * @return this builder
			 */
			public Builder meta(Map<String, Object> meta) {
				this.meta = meta;
				return this;
			}

			/**
			 * Builds a new {@link CallToolResult} instance.
			 * @return a new CallToolResult instance
			 */
			public CallToolResult build() {
				Assert.notNull(content, "content must not be null");
				return new CallToolResult(content, isError, structuredContent, meta);
			}

		}

		public List<Content> content() {
			return this.content;
		}

		public Boolean isError() {
			return this.isError;
		}

		public Object structuredContent() {
			return this.structuredContent;
		}

		public Map<String, Object> meta() {
			return this.meta;
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) {
				return true;
			}
			if (!(o instanceof CallToolResult)) {
				return false;
			}
			CallToolResult that = (CallToolResult) o;
			return Objects.equals(this.content, that.content) && Objects.equals(this.isError, that.isError)
					&& Objects.equals(this.structuredContent, that.structuredContent)
					&& Objects.equals(this.meta, that.meta);
		}

		@Override
		public int hashCode() {
			return Objects.hash(this.content, this.isError, this.structuredContent, this.meta);
		}

		@Override
		public String toString() {
			return "CallToolResult[content=" + this.content + ", isError=" + this.isError + ", structuredContent="
					+ this.structuredContent + ", meta=" + this.meta + "]";
		}
	}

	// ---------------------------
	// Sampling Interfaces
	// ---------------------------
	/**
	 * The server's preferences for model selection, requested of the client during
	 * sampling.
	 *
	 * @param hints Optional hints to use for model selection. If multiple hints are
	 * specified, the client MUST evaluate them in order (such that the first match is
	 * taken). The client SHOULD prioritize these hints over the numeric priorities, but
	 * MAY still use the priorities to select from ambiguous matches
	 * @param costPriority How much to prioritize cost when selecting a model. A value of
	 * 0 means cost is not important, while a value of 1 means cost is the most important
	 * factor
	 * @param speedPriority How much to prioritize sampling speed (latency) when selecting
	 * a model. A value of 0 means speed is not important, while a value of 1 means speed
	 * is the most important factor
	 * @param intelligencePriority How much to prioritize intelligence and capabilities
	 * when selecting a model. A value of 0 means intelligence is not important, while a
	 * value of 1 means intelligence is the most important factor
	 */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static final class ModelPreferences {

		@JsonProperty("hints")
		private final List<ModelHint> hints;

		@JsonProperty("costPriority")
		private final Double costPriority;

		@JsonProperty("speedPriority")
		private final Double speedPriority;

		@JsonProperty("intelligencePriority")
		private final Double intelligencePriority;

		public ModelPreferences(List<ModelHint> hints, Double costPriority, Double speedPriority,
				Double intelligencePriority) {
			this.hints = hints;
			this.costPriority = costPriority;
			this.speedPriority = speedPriority;
			this.intelligencePriority = intelligencePriority;
		}

		public static Builder builder() {
			return new Builder();
		}

		public static class Builder {

			private List<ModelHint> hints;

			private Double costPriority;

			private Double speedPriority;

			private Double intelligencePriority;

			public Builder hints(List<ModelHint> hints) {
				this.hints = hints;
				return this;
			}

			public Builder addHint(String name) {
				if (this.hints == null) {
					this.hints = new ArrayList<>();
				}
				this.hints.add(new ModelHint(name));
				return this;
			}

			public Builder costPriority(Double costPriority) {
				this.costPriority = costPriority;
				return this;
			}

			public Builder speedPriority(Double speedPriority) {
				this.speedPriority = speedPriority;
				return this;
			}

			public Builder intelligencePriority(Double intelligencePriority) {
				this.intelligencePriority = intelligencePriority;
				return this;
			}

			public ModelPreferences build() {
				return new ModelPreferences(hints, costPriority, speedPriority, intelligencePriority);
			}

		}

		public List<ModelHint> hints() {
			return this.hints;
		}

		public Double costPriority() {
			return this.costPriority;
		}

		public Double speedPriority() {
			return this.speedPriority;
		}

		public Double intelligencePriority() {
			return this.intelligencePriority;
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) {
				return true;
			}
			if (!(o instanceof ModelPreferences)) {
				return false;
			}
			ModelPreferences that = (ModelPreferences) o;
			return Objects.equals(this.hints, that.hints) && Objects.equals(this.costPriority, that.costPriority)
					&& Objects.equals(this.speedPriority, that.speedPriority)
					&& Objects.equals(this.intelligencePriority, that.intelligencePriority);
		}

		@Override
		public int hashCode() {
			return Objects.hash(this.hints, this.costPriority, this.speedPriority, this.intelligencePriority);
		}

		@Override
		public String toString() {
			return "ModelPreferences[hints=" + this.hints + ", costPriority=" + this.costPriority + ", speedPriority="
					+ this.speedPriority + ", intelligencePriority=" + this.intelligencePriority + "]";
		}
	}

	/**
	 * Hints to use for model selection.
	 *
	 * @param name A hint for a model name. The client SHOULD treat this as a substring of
	 * a model name; for example: `claude-3-5-sonnet` should match
	 * `claude-3-5-sonnet-20241022`, `sonnet` should match `claude-3-5-sonnet-20241022`,
	 * `claude-3-sonnet-20240229`, etc., `claude` should match any Claude model. The
	 * client MAY also map the string to a different provider's model name or a different
	 * model family, as long as it fills a similar niche
	 */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static final class ModelHint {

		@JsonProperty("name")
		private final String name;

		@JsonCreator
		public ModelHint(@JsonProperty("name") String name) {
			this.name = name;
		}

		public String name() {
			return this.name;
		}

		/**
		 * @deprecated Use {@link #ModelHint(String)}
		 */
		@Deprecated
		public static ModelHint of(String name) {
			return new ModelHint(name);
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) {
				return true;
			}
			if (!(o instanceof ModelHint)) {
				return false;
			}
			ModelHint that = (ModelHint) o;
			return Objects.equals(this.name, that.name);
		}

		@Override
		public int hashCode() {
			return Objects.hash(this.name);
		}

		@Override
		public String toString() {
			return "ModelHint[name=" + this.name + "]";
		}
	}

	/**
	 * Describes a message issued to or received from an LLM API.
	 *
	 * @param role The sender or recipient of messages and data in a conversation
	 * @param content The content of the message
	 * <p>
	 * Note: {@code role} and {@code content} are required by the MCP specification.
	 * Deserialization accepts missing values and substitutes defaults to avoid breaking
	 * existing integrations that may omit these fields.
	 */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static final class SamplingMessage {

		@JsonProperty("role")
		private final Role role;

		@JsonProperty("content")
		private final Content content;

		public SamplingMessage(Role role, Content content) {
			Assert.notNull(role, "role must not be null");
			Assert.notNull(content, "content must not be null");
			this.role = role;
			this.content = content;
		}

		@JsonCreator
		static SamplingMessage fromJson(@JsonProperty("role") Role role, @JsonProperty("content") Content content) {
			if (role == null || content == null) {
				List<String> missing = new ArrayList<>();
				if (role == null) {
					missing.add("role -> 'user'");
					role = Role.USER;
				}
				if (content == null) {
					missing.add("content -> ''");
					content = TextContent.builder("").build();
				}
				logger.warn("SamplingMessage: missing required fields during deserialization: {}",
						String.join(", ", missing));
			}
			return new SamplingMessage(role, content);
		}

		public static Builder builder(Role role, Content content) {
			return new Builder(role, content);
		}

		public static class Builder {

			private final Role role;

			private final Content content;

			private Builder(Role role, Content content) {
				Assert.notNull(role, "role must not be null");
				Assert.notNull(content, "content must not be null");
				this.role = role;
				this.content = content;
			}

			public SamplingMessage build() {
				return new SamplingMessage(role, content);
			}

		}

		public Role role() {
			return this.role;
		}

		public Content content() {
			return this.content;
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) {
				return true;
			}
			if (!(o instanceof SamplingMessage)) {
				return false;
			}
			SamplingMessage that = (SamplingMessage) o;
			return Objects.equals(this.role, that.role) && Objects.equals(this.content, that.content);
		}

		@Override
		public int hashCode() {
			return Objects.hash(this.role, this.content);
		}

		@Override
		public String toString() {
			return "SamplingMessage[role=" + this.role + ", content=" + this.content + "]";
		}
	}

	/**
	 * A request from the server to sample an LLM via the client. The client has full
	 * discretion over which model to select. The client should also inform the user
	 * before beginning sampling, to allow them to inspect the request (human in the loop)
	 * and decide whether to approve it.
	 *
	 * @param messages The conversation messages to send to the LLM
	 * @param modelPreferences The server's preferences for which model to select. The
	 * client MAY ignore these preferences
	 * @param systemPrompt An optional system prompt the server wants to use for sampling.
	 * The client MAY modify or omit this prompt
	 * @param includeContext A request to include context from one or more MCP servers
	 * (including the caller), to be attached to the prompt. The client MAY ignore this
	 * request
	 * @param temperature Optional temperature parameter for sampling
	 * @param maxTokens The maximum number of tokens to sample, as requested by the
	 * server. The client MAY choose to sample fewer tokens than requested
	 * @param stopSequences Optional stop sequences for sampling
	 * @param metadata Optional metadata to pass through to the LLM provider. The format
	 * of this metadata is provider-specific
	 * @param meta See specification for notes on _meta usage
	 * <p>
	 * Note: {@code messages} and {@code maxTokens} are required by the MCP specification.
	 * Deserialization accepts missing values and substitutes defaults to avoid breaking
	 * existing integrations that may omit these fields.
	 */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static final class CreateMessageRequest implements Request {

		@JsonProperty("messages")
		private final List<SamplingMessage> messages;

		@JsonProperty("modelPreferences")
		private final ModelPreferences modelPreferences;

		@JsonProperty("systemPrompt")
		private final String systemPrompt;

		@JsonProperty("includeContext")
		private final ContextInclusionStrategy includeContext;

		@JsonProperty("temperature")
		private final Double temperature;

		@JsonProperty("maxTokens")
		private final Integer maxTokens;

		@JsonProperty("stopSequences")
		private final List<String> stopSequences;

		@JsonProperty("metadata")
		private final Map<String, Object> metadata;

		@JsonProperty("_meta")
		private final Map<String, Object> meta;

		public CreateMessageRequest(List<SamplingMessage> messages, ModelPreferences modelPreferences,
				String systemPrompt, ContextInclusionStrategy includeContext, Double temperature, Integer maxTokens,
				List<String> stopSequences, Map<String, Object> metadata, Map<String, Object> meta) {
			Assert.notNull(messages, "messages must not be null");
			Assert.notNull(maxTokens, "maxTokens must not be null");
			this.messages = messages;
			this.modelPreferences = modelPreferences;
			this.systemPrompt = systemPrompt;
			this.includeContext = includeContext;
			this.temperature = temperature;
			this.maxTokens = maxTokens;
			this.stopSequences = stopSequences;
			this.metadata = metadata;
			this.meta = meta;
		}

		@JsonCreator
		static CreateMessageRequest fromJson(@JsonProperty("messages") List<SamplingMessage> messages,
				@JsonProperty("modelPreferences") ModelPreferences modelPreferences,
				@JsonProperty("systemPrompt") String systemPrompt,
				@JsonProperty("includeContext") ContextInclusionStrategy includeContext,
				@JsonProperty("temperature") Double temperature, @JsonProperty("maxTokens") Integer maxTokens,
				@JsonProperty("stopSequences") List<String> stopSequences,
				@JsonProperty("metadata") Map<String, Object> metadata,
				@JsonProperty("_meta") Map<String, Object> meta) {
			if (messages == null || maxTokens == null) {
				List<String> missing = new ArrayList<>();
				if (messages == null) {
					missing.add("messages -> []");
					messages = Collections.emptyList();
				}
				if (maxTokens == null) {
					missing.add("maxTokens -> 0");
					maxTokens = 0;
				}
				logger.warn("CreateMessageRequest: missing required fields during deserialization: {}",
						String.join(", ", missing));
			}
			return new CreateMessageRequest(messages, modelPreferences, systemPrompt, includeContext, temperature,
					maxTokens, stopSequences, metadata, meta);
		}

		// backwards compatibility constructor
		public CreateMessageRequest(List<SamplingMessage> messages, ModelPreferences modelPreferences,
				String systemPrompt, ContextInclusionStrategy includeContext, Double temperature, Integer maxTokens,
				List<String> stopSequences, Map<String, Object> metadata) {
			this(messages, modelPreferences, systemPrompt, includeContext, temperature, maxTokens, stopSequences,
					metadata, null);
		}

		public enum ContextInclusionStrategy {

		// @formatter:off
			@JsonProperty("none") NONE,
			@JsonProperty("thisServer") THIS_SERVER,
			@JsonProperty("allServers") ALL_SERVERS
		} // @formatter:on

		/**
		 * @deprecated Use {@link #builder(List, int)} instead.
		 */
		@Deprecated
		public static Builder builder() {
			return new Builder();
		}

		public static Builder builder(List<SamplingMessage> messages, int maxTokens) {
			return new Builder(messages, maxTokens);
		}

		public static class Builder {

			private List<SamplingMessage> messages;

			private ModelPreferences modelPreferences;

			private String systemPrompt;

			private ContextInclusionStrategy includeContext;

			private Double temperature;

			private Integer maxTokens;

			private List<String> stopSequences;

			private Map<String, Object> metadata;

			private Map<String, Object> meta;

			/**
			 * @deprecated Use {@link CreateMessageRequest#builder(List, int)} factory
			 * method instead.
			 */
			@Deprecated
			public Builder() {
			}

			private Builder(List<SamplingMessage> messages, int maxTokens) {
				Assert.notNull(messages, "messages must not be null");
				this.messages = messages;
				this.maxTokens = maxTokens;
			}

			public Builder messages(List<SamplingMessage> messages) {
				Assert.notNull(messages, "messages must not be null");
				this.messages = messages;
				return this;
			}

			public Builder modelPreferences(ModelPreferences modelPreferences) {
				this.modelPreferences = modelPreferences;
				return this;
			}

			public Builder systemPrompt(String systemPrompt) {
				this.systemPrompt = systemPrompt;
				return this;
			}

			public Builder includeContext(ContextInclusionStrategy includeContext) {
				this.includeContext = includeContext;
				return this;
			}

			public Builder temperature(Double temperature) {
				this.temperature = temperature;
				return this;
			}

			public Builder maxTokens(int maxTokens) {
				this.maxTokens = maxTokens;
				return this;
			}

			public Builder stopSequences(List<String> stopSequences) {
				this.stopSequences = stopSequences;
				return this;
			}

			public Builder metadata(Map<String, Object> metadata) {
				this.metadata = metadata;
				return this;
			}

			public Builder meta(Map<String, Object> meta) {
				this.meta = meta;
				return this;
			}

			public Builder progressToken(Object progressToken) {
				if (this.meta == null) {
					this.meta = new HashMap<>();
				}
				this.meta.put("progressToken", progressToken);
				return this;
			}

			public CreateMessageRequest build() {
				Assert.notNull(messages, "messages must not be null");
				Assert.notNull(maxTokens, "maxTokens must not be null");
				return new CreateMessageRequest(messages, modelPreferences, systemPrompt, includeContext, temperature,
						maxTokens, stopSequences, metadata, meta);
			}

		}

		public List<SamplingMessage> messages() {
			return this.messages;
		}

		public ModelPreferences modelPreferences() {
			return this.modelPreferences;
		}

		public String systemPrompt() {
			return this.systemPrompt;
		}

		public ContextInclusionStrategy includeContext() {
			return this.includeContext;
		}

		public Double temperature() {
			return this.temperature;
		}

		public Integer maxTokens() {
			return this.maxTokens;
		}

		public List<String> stopSequences() {
			return this.stopSequences;
		}

		public Map<String, Object> metadata() {
			return this.metadata;
		}

		public Map<String, Object> meta() {
			return this.meta;
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) {
				return true;
			}
			if (!(o instanceof CreateMessageRequest)) {
				return false;
			}
			CreateMessageRequest that = (CreateMessageRequest) o;
			return Objects.equals(this.messages, that.messages)
					&& Objects.equals(this.modelPreferences, that.modelPreferences)
					&& Objects.equals(this.systemPrompt, that.systemPrompt)
					&& Objects.equals(this.includeContext, that.includeContext)
					&& Objects.equals(this.temperature, that.temperature)
					&& Objects.equals(this.maxTokens, that.maxTokens)
					&& Objects.equals(this.stopSequences, that.stopSequences)
					&& Objects.equals(this.metadata, that.metadata) && Objects.equals(this.meta, that.meta);
		}

		@Override
		public int hashCode() {
			return Objects.hash(this.messages, this.modelPreferences, this.systemPrompt, this.includeContext,
					this.temperature, this.maxTokens, this.stopSequences, this.metadata, this.meta);
		}

		@Override
		public String toString() {
			return "CreateMessageRequest[messages=" + this.messages + ", modelPreferences=" + this.modelPreferences
					+ ", systemPrompt=" + this.systemPrompt + ", includeContext=" + this.includeContext + ", temperature="
					+ this.temperature + ", maxTokens=" + this.maxTokens + ", stopSequences=" + this.stopSequences
					+ ", metadata=" + this.metadata + ", meta=" + this.meta + "]";
		}
	}

	// TODO: role, content and model are required
	/**
	 * The client's response to a sampling/create_message request from the server. The
	 * client should inform the user before returning the sampled message, to allow them
	 * to inspect the response (human in the loop) and decide whether to allow the server
	 * to see it.
	 *
	 * @param role The role of the message sender (typically assistant)
	 * @param content The content of the sampled message
	 * @param model The name of the model that generated the message
	 * @param stopReason The reason why sampling stopped, if known
	 * @param meta See specification for notes on _meta usage
	 */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static final class CreateMessageResult implements Result {

		@JsonProperty("role")
		private final Role role;

		@JsonProperty("content")
		private final Content content;

		@JsonProperty("model")
		private final String model;

		@JsonProperty("stopReason")
		private final StopReason stopReason;

		@JsonProperty("_meta")
		private final Map<String, Object> meta;

		public CreateMessageResult(Role role, Content content, String model, StopReason stopReason,
				Map<String, Object> meta) {
			Assert.notNull(role, "role must not be null");
			Assert.notNull(content, "content must not be null");
			Assert.notNull(model, "model must not be null");
			this.role = role;
			this.content = content;
			this.model = model;
			this.stopReason = stopReason;
			this.meta = meta;
		}

		@JsonCreator
		static CreateMessageResult fromJson(@JsonProperty("role") Role role, @JsonProperty("content") Content content,
				@JsonProperty("model") String model, @JsonProperty("stopReason") StopReason stopReason,
				@JsonProperty("_meta") Map<String, Object> meta) {
			if (role == null || content == null || model == null) {
				List<String> missing = new ArrayList<>();
				if (role == null) {
					missing.add("role -> 'assistant'");
					role = Role.ASSISTANT;
				}
				if (content == null) {
					missing.add("content -> ''");
					content = TextContent.builder("").build();
				}
				if (model == null) {
					missing.add("model -> ''");
					model = "";
				}
				logger.warn("CreateMessageResult: missing required fields during deserialization: {}",
						String.join(", ", missing));
			}
			return new CreateMessageResult(role, content, model, stopReason, meta);
		}

		public enum StopReason {

		// @formatter:off

			@JsonProperty("endTurn") END_TURN("endTurn"),
			@JsonProperty("stopSequence") STOP_SEQUENCE("stopSequence"),
			@JsonProperty("maxTokens") MAX_TOKENS("maxTokens"),
			@JsonProperty("unknown") UNKNOWN("unknown"); // @formatter:on

			private final String value;

			private static final Map<String, StopReason> BY_VALUE;

			static {
				Map<String, StopReason> m = new HashMap<>();
				for (StopReason r : values()) {
					m.put(r.value, r);
				}
				BY_VALUE = Collections.unmodifiableMap(m);
			}

			StopReason(String value) {
				this.value = value;
			}

			@JsonCreator
			public static StopReason of(String value) {
				return BY_VALUE.getOrDefault(value, UNKNOWN);
			}

		}

		// backwards compatibility constructor
		public CreateMessageResult(Role role, Content content, String model, StopReason stopReason) {
			this(role, content, model, stopReason, null);
		}

		@Deprecated
		public static Builder builder() {
			return new Builder(Role.ASSISTANT);
		}

		public static Builder builder(Role role, String textContent, String model) {
			return builder(role, TextContent.builder(textContent).build(), model);
		}

		public static Builder builder(Role role, Content content, String model) {
			return new Builder(role, content, model);
		}

		public static class Builder {

			private Role role;

			private Content content;

			private String model;

			private StopReason stopReason = StopReason.END_TURN;

			private Map<String, Object> meta;

			// temporary to keep deprecated use
			private Builder(Role role) {
				Assert.notNull(role, "role must not be null");
				this.role = role;
			}

			Builder(Role role, Content content, String model) {
				Assert.notNull(role, "role must not be null");
				Assert.notNull(content, "content must not be null");
				Assert.notNull(model, "model must not be null");
				this.role = role;
				this.content = content;
				this.model = model;
			}

			@Deprecated
			public Builder role(Role role) {
				this.role = role;
				return this;
			}

			@Deprecated
			public Builder content(Content content) {
				this.content = content;
				return this;
			}

			@Deprecated
			public Builder model(String model) {
				this.model = model;
				return this;
			}

			public Builder stopReason(StopReason stopReason) {
				this.stopReason = stopReason;
				return this;
			}

			@Deprecated
			public Builder message(String message) {
				this.content = TextContent.builder(message).build();
				return this;
			}

			public Builder meta(Map<String, Object> meta) {
				this.meta = meta;
				return this;
			}

			public CreateMessageResult build() {
				return new CreateMessageResult(role, content, model, stopReason, meta);
			}

		}

		public Role role() {
			return this.role;
		}

		public Content content() {
			return this.content;
		}

		public String model() {
			return this.model;
		}

		public StopReason stopReason() {
			return this.stopReason;
		}

		public Map<String, Object> meta() {
			return this.meta;
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) {
				return true;
			}
			if (!(o instanceof CreateMessageResult)) {
				return false;
			}
			CreateMessageResult that = (CreateMessageResult) o;
			return Objects.equals(this.role, that.role) && Objects.equals(this.content, that.content)
					&& Objects.equals(this.model, that.model) && Objects.equals(this.stopReason, that.stopReason)
					&& Objects.equals(this.meta, that.meta);
		}

		@Override
		public int hashCode() {
			return Objects.hash(this.role, this.content, this.model, this.stopReason, this.meta);
		}

		@Override
		public String toString() {
			return "CreateMessageResult[role=" + this.role + ", content=" + this.content + ", model=" + this.model
					+ ", stopReason=" + this.stopReason + ", meta=" + this.meta + "]";
		}
	}

	// Elicitation

	/**
	 * An option in a titled enum schema, with a machine-readable value and a
	 * human-readable display label.
	 *
	 * @param constValue The machine-readable value of the option
	 * @param title The human-readable display label
	 */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static final class EnumSchemaOption {

		@JsonProperty("const")
		private final String constValue;

		@JsonProperty("title")
		private final String title;

		public EnumSchemaOption(String constValue, String title) {
			Assert.notNull(constValue, "constValue must not be null");
			Assert.notNull(title, "title must not be null");
			this.constValue = constValue;
			this.title = title;
		}

		@JsonCreator
		static EnumSchemaOption fromJson(@JsonProperty("const") String constValue,
				@JsonProperty("title") String title) {
			if (constValue == null || title == null) {
				List<String> missing = new ArrayList<>();
				if (constValue == null) {
					missing.add("constValue -> ''");
					constValue = "";
				}
				if (title == null) {
					missing.add("title -> ''");
					title = "";
				}
				logger.warn("EnumSchemaOption: missing required fields during deserialization: {}",
						String.join(", ", missing));
			}
			return new EnumSchemaOption(constValue, title);
		}

		public String constValue() {
			return this.constValue;
		}

		public String title() {
			return this.title;
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) {
				return true;
			}
			if (!(o instanceof EnumSchemaOption)) {
				return false;
			}
			EnumSchemaOption that = (EnumSchemaOption) o;
			return Objects.equals(this.constValue, that.constValue) && Objects.equals(this.title, that.title);
		}

		@Override
		public int hashCode() {
			return Objects.hash(this.constValue, this.title);
		}

		@Override
		public String toString() {
			return "EnumSchemaOption[constValue=" + this.constValue + ", title=" + this.title + "]";
		}

	}

	/**
	 * Legacy enum schema with optional display names via the non-standard
	 * {@code enumNames} property. Use {@link TitledSingleSelectEnumSchema} instead.
	 *
	 * @param title Optional title for the enum field
	 * @param description Optional description for the enum field
	 * @param enumValues Array of enum values to choose from
	 * @param enumNames Optional display names for enum values (non-standard per JSON
	 * Schema 2020-12)
	 * @param defaultValue Optional default value
	 * @deprecated Use {@link TitledSingleSelectEnumSchema} instead
	 */
	@Deprecated
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static final class LegacyTitledEnumSchema {

		@JsonProperty("title")
		private final String title;

		@JsonProperty("description")
		private final String description;

		@JsonProperty("enum")
		private final List<String> enumValues;

		@JsonProperty("enumNames")
		private final List<String> enumNames;

		@JsonProperty("default")
		private final String defaultValue;

		public LegacyTitledEnumSchema(String title, String description, List<String> enumValues,
				List<String> enumNames, String defaultValue) {
			Assert.notNull(enumValues, "enumValues must not be null");
			this.title = title;
			this.description = description;
			this.enumValues = enumValues;
			this.enumNames = enumNames;
			this.defaultValue = defaultValue;
		}

		@JsonProperty("type")
		public String type() {
			return "string";
		}

		public static Builder builder() {
			return new Builder();
		}

		public static class Builder {

			private String title;

			private String description;

			private List<String> enumValues;

			private List<String> enumNames;

			private String defaultValue;

			private Builder() {
			}

			public Builder title(String title) {
				this.title = title;
				return this;
			}

			public Builder description(String description) {
				this.description = description;
				return this;
			}

			public Builder enumValues(List<String> enumValues) {
				Assert.notNull(enumValues, "enumValues must not be null");
				this.enumValues = new ArrayList<>(enumValues);
				return this;
			}

			public Builder enumValues(String... enumValues) {
				Assert.notNull(enumValues, "enumValues must not be null");
				this.enumValues = Arrays.asList(enumValues);
				return this;
			}

			public Builder enumNames(List<String> enumNames) {
				Assert.notNull(enumNames, "enumNames must not be null");
				this.enumNames = new ArrayList<>(enumNames);
				return this;
			}

			public Builder enumNames(String... enumNames) {
				Assert.notNull(enumNames, "enumNames must not be null");
				this.enumNames = Arrays.asList(enumNames);
				return this;
			}

			public Builder defaultValue(String defaultValue) {
				this.defaultValue = defaultValue;
				return this;
			}

			public LegacyTitledEnumSchema build() {
				Assert.notEmpty(enumValues, "enumValues must not be empty");
				return new LegacyTitledEnumSchema(title, description, enumValues, enumNames, defaultValue);
			}

		}

		public String title() {
			return this.title;
		}

		public String description() {
			return this.description;
		}

		public List<String> enumValues() {
			return this.enumValues;
		}

		public List<String> enumNames() {
			return this.enumNames;
		}

		public String defaultValue() {
			return this.defaultValue;
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) {
				return true;
			}
			if (!(o instanceof LegacyTitledEnumSchema)) {
				return false;
			}
			LegacyTitledEnumSchema that = (LegacyTitledEnumSchema) o;
			return Objects.equals(this.title, that.title) && Objects.equals(this.description, that.description)
					&& Objects.equals(this.enumValues, that.enumValues)
					&& Objects.equals(this.enumNames, that.enumNames)
					&& Objects.equals(this.defaultValue, that.defaultValue);
		}

		@Override
		public int hashCode() {
			return Objects.hash(this.title, this.description, this.enumValues, this.enumNames, this.defaultValue);
		}

		@Override
		public String toString() {
			return "LegacyTitledEnumSchema[title=" + this.title + ", description=" + this.description + ", enumValues="
					+ this.enumValues + ", enumNames=" + this.enumNames + ", defaultValue=" + this.defaultValue + "]";
		}
	}

	/**
	 * Schema for single-selection enumeration without display titles for options.
	 *
	 * @param title Optional title for the enum field
	 * @param description Optional description for the enum field
	 * @param enumValues Array of enum values to choose from
	 * @param defaultValue Optional default value
	 */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static final class UntitledSingleSelectEnumSchema {

		@JsonProperty("title")
		private final String title;

		@JsonProperty("description")
		private final String description;

		@JsonProperty("enum")
		private final List<String> enumValues;

		@JsonProperty("default")
		private final String defaultValue;

		public UntitledSingleSelectEnumSchema(String title, String description, List<String> enumValues,
				String defaultValue) {
			Assert.notNull(enumValues, "enumValues must not be null");
			this.title = title;
			this.description = description;
			this.enumValues = enumValues;
			this.defaultValue = defaultValue;
		}

		@JsonProperty("type")
		public String type() {
			return "string";
		}

		public static Builder builder() {
			return new Builder();
		}

		public static class Builder {

			private String title;

			private String description;

			private List<String> enumValues;

			private String defaultValue;

			private Builder() {
			}

			public Builder title(String title) {
				this.title = title;
				return this;
			}

			public Builder description(String description) {
				this.description = description;
				return this;
			}

			public Builder enumValues(List<String> enumValues) {
				Assert.notNull(enumValues, "enumValues must not be null");
				this.enumValues = new ArrayList<>(enumValues);
				return this;
			}

			public Builder enumValues(String... enumValues) {
				Assert.notNull(enumValues, "enumValues must not be null");
				this.enumValues = Arrays.asList(enumValues);
				return this;
			}

			public Builder defaultValue(String defaultValue) {
				this.defaultValue = defaultValue;
				return this;
			}

			public UntitledSingleSelectEnumSchema build() {
				Assert.notEmpty(enumValues, "enumValues must not be empty");
				return new UntitledSingleSelectEnumSchema(title, description, enumValues, defaultValue);
			}

		}

		public String title() {
			return this.title;
		}

		public String description() {
			return this.description;
		}

		public List<String> enumValues() {
			return this.enumValues;
		}

		public String defaultValue() {
			return this.defaultValue;
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) {
				return true;
			}
			if (!(o instanceof UntitledSingleSelectEnumSchema)) {
				return false;
			}
			UntitledSingleSelectEnumSchema that = (UntitledSingleSelectEnumSchema) o;
			return Objects.equals(this.title, that.title) && Objects.equals(this.description, that.description)
					&& Objects.equals(this.enumValues, that.enumValues)
					&& Objects.equals(this.defaultValue, that.defaultValue);
		}

		@Override
		public int hashCode() {
			return Objects.hash(this.title, this.description, this.enumValues, this.defaultValue);
		}

		@Override
		public String toString() {
			return "UntitledSingleSelectEnumSchema[title=" + this.title + ", description=" + this.description
					+ ", enumValues=" + this.enumValues + ", defaultValue=" + this.defaultValue + "]";
		}
	}

	/**
	 * Schema for single-selection enumeration with display titles for each option.
	 *
	 * @param title Optional title for the enum field
	 * @param description Optional description for the enum field
	 * @param oneOf Array of enum options, each with a machine-readable value and a
	 * human-readable display label
	 * @param defaultValue Optional default value
	 */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static final class TitledSingleSelectEnumSchema {

		@JsonProperty("title")
		private final String title;

		@JsonProperty("description")
		private final String description;

		@JsonProperty("oneOf")
		private final List<EnumSchemaOption> oneOf;

		@JsonProperty("default")
		private final String defaultValue;

		public TitledSingleSelectEnumSchema(String title, String description, List<EnumSchemaOption> oneOf,
				String defaultValue) {
			Assert.notEmpty(oneOf, "oneOf must not be empty");
			this.title = title;
			this.description = description;
			this.oneOf = oneOf;
			this.defaultValue = defaultValue;
		}

		@JsonProperty("type")
		public String type() {
			return "string";
		}

		public static Builder builder() {
			return new Builder();
		}

		public static class Builder {

			private String title;

			private String description;

			private List<EnumSchemaOption> oneOf;

			private String defaultValue;

			private Builder() {
			}

			public Builder title(String title) {
				this.title = title;
				return this;
			}

			public Builder description(String description) {
				this.description = description;
				return this;
			}

			public Builder oneOf(List<EnumSchemaOption> oneOf) {
				Assert.notNull(oneOf, "oneOf must not be null");
				this.oneOf = new ArrayList<>(oneOf);
				return this;
			}

			public Builder oneOf(EnumSchemaOption... oneOf) {
				Assert.notNull(oneOf, "oneOf must not be null");
				this.oneOf = Arrays.asList(oneOf);
				return this;
			}

			public Builder defaultValue(String defaultValue) {
				this.defaultValue = defaultValue;
				return this;
			}

			public TitledSingleSelectEnumSchema build() {
				Assert.notEmpty(oneOf, "oneOf must not be empty");
				return new TitledSingleSelectEnumSchema(title, description, oneOf, defaultValue);
			}

		}

		public String title() {
			return this.title;
		}

		public String description() {
			return this.description;
		}

		public List<EnumSchemaOption> oneOf() {
			return this.oneOf;
		}

		public String defaultValue() {
			return this.defaultValue;
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) {
				return true;
			}
			if (!(o instanceof TitledSingleSelectEnumSchema)) {
				return false;
			}
			TitledSingleSelectEnumSchema that = (TitledSingleSelectEnumSchema) o;
			return Objects.equals(this.title, that.title) && Objects.equals(this.description, that.description)
					&& Objects.equals(this.oneOf, that.oneOf)
					&& Objects.equals(this.defaultValue, that.defaultValue);
		}

		@Override
		public int hashCode() {
			return Objects.hash(this.title, this.description, this.oneOf, this.defaultValue);
		}

		@Override
		public String toString() {
			return "TitledSingleSelectEnumSchema[title=" + this.title + ", description=" + this.description + ", oneOf="
					+ this.oneOf + ", defaultValue=" + this.defaultValue + "]";
		}
	}

	/**
	 * The items schema for {@link UntitledMultiSelectEnumSchema}, describing the allowed
	 * enum values.
	 *
	 * @param enumValues Array of enum values to choose from
	 */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static final class UntitledMultiSelectItems {

		@JsonProperty("enum")
		private final List<String> enumValues;

		public UntitledMultiSelectItems(List<String> enumValues) {
			Assert.notNull(enumValues, "enumValues must not be null");
			this.enumValues = enumValues;
		}

		@JsonProperty("type")
		public String type() {
			return "string";
		}

		public static Builder builder() {
			return new Builder();
		}

		public static class Builder {

			private List<String> enumValues;

			private Builder() {
			}

			public Builder enumValues(List<String> enumValues) {
				Assert.notNull(enumValues, "enumValues must not be null");
				this.enumValues = new ArrayList<>(enumValues);
				return this;
			}

			public Builder enumValues(String... enumValues) {
				Assert.notNull(enumValues, "enumValues must not be null");
				this.enumValues = Arrays.asList(enumValues);
				return this;
			}

			public UntitledMultiSelectItems build() {
				Assert.notEmpty(enumValues, "enumValues must not be empty");
				return new UntitledMultiSelectItems(enumValues);
			}

		}

		public List<String> enumValues() {
			return this.enumValues;
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) {
				return true;
			}
			if (!(o instanceof UntitledMultiSelectItems)) {
				return false;
			}
			UntitledMultiSelectItems that = (UntitledMultiSelectItems) o;
			return Objects.equals(this.enumValues, that.enumValues);
		}

		@Override
		public int hashCode() {
			return Objects.hash(this.enumValues);
		}

		@Override
		public String toString() {
			return "UntitledMultiSelectItems[enumValues=" + this.enumValues + "]";
		}
	}

	/**
	 * Schema for multiple-selection enumeration without display titles for options.
	 *
	 * @param title Optional title for the enum field
	 * @param description Optional description for the enum field
	 * @param items Schema for the array items, containing the list of enum values
	 * @param minItems Optional minimum number of items to select
	 * @param maxItems Optional maximum number of items to select
	 * @param defaultValue Optional default selected values
	 */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static final class UntitledMultiSelectEnumSchema {

		@JsonProperty("title")
		private final String title;

		@JsonProperty("description")
		private final String description;

		@JsonProperty("items")
		private final UntitledMultiSelectItems items;

		@JsonProperty("minItems")
		private final Integer minItems;

		@JsonProperty("maxItems")
		private final Integer maxItems;

		@JsonProperty("default")
		private final List<String> defaultValue;

		public UntitledMultiSelectEnumSchema(String title, String description, UntitledMultiSelectItems items,
				Integer minItems, Integer maxItems, List<String> defaultValue) {
			Assert.notNull(items, "items must not be null");
			this.title = title;
			this.description = description;
			this.items = items;
			this.minItems = minItems;
			this.maxItems = maxItems;
			this.defaultValue = defaultValue;
		}

		@JsonProperty("type")
		public String type() {
			return "array";
		}

		public static Builder builder(UntitledMultiSelectItems items) {
			return new Builder(items);
		}

		public static class Builder {

			private String title;

			private String description;

			private UntitledMultiSelectItems items;

			private Integer minItems;

			private Integer maxItems;

			private List<String> defaultValue;

			private Builder(UntitledMultiSelectItems items) {
				Assert.notNull(items, "items must not be null");
				this.items = items;
			}

			public Builder title(String title) {
				this.title = title;
				return this;
			}

			public Builder description(String description) {
				this.description = description;
				return this;
			}

			public Builder items(UntitledMultiSelectItems items) {
				Assert.notNull(items, "items must not be null");
				this.items = items;
				return this;
			}

			public Builder minItems(Integer minItems) {
				this.minItems = minItems;
				return this;
			}

			public Builder maxItems(Integer maxItems) {
				this.maxItems = maxItems;
				return this;
			}

			public Builder defaults(String... defaultValue) {
				Assert.notNull(defaultValue, "defaultValue must not be null");
				this.defaultValue = Arrays.asList(defaultValue);
				return this;
			}

			public Builder defaults(List<String> defaultValue) {
				Assert.notNull(defaultValue, "defaultValue must not be null");
				this.defaultValue = new ArrayList<>(defaultValue);
				return this;
			}

			public UntitledMultiSelectEnumSchema build() {
				return new UntitledMultiSelectEnumSchema(title, description, items, minItems, maxItems, defaultValue);
			}

		}

		public String title() {
			return this.title;
		}

		public String description() {
			return this.description;
		}

		public UntitledMultiSelectItems items() {
			return this.items;
		}

		public Integer minItems() {
			return this.minItems;
		}

		public Integer maxItems() {
			return this.maxItems;
		}

		public List<String> defaultValue() {
			return this.defaultValue;
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) {
				return true;
			}
			if (!(o instanceof UntitledMultiSelectEnumSchema)) {
				return false;
			}
			UntitledMultiSelectEnumSchema that = (UntitledMultiSelectEnumSchema) o;
			return Objects.equals(this.title, that.title) && Objects.equals(this.description, that.description)
					&& Objects.equals(this.items, that.items) && Objects.equals(this.minItems, that.minItems)
					&& Objects.equals(this.maxItems, that.maxItems)
					&& Objects.equals(this.defaultValue, that.defaultValue);
		}

		@Override
		public int hashCode() {
			return Objects.hash(this.title, this.description, this.items, this.minItems, this.maxItems,
					this.defaultValue);
		}

		@Override
		public String toString() {
			return "UntitledMultiSelectEnumSchema[title=" + this.title + ", description=" + this.description
					+ ", items=" + this.items + ", minItems=" + this.minItems + ", maxItems=" + this.maxItems
					+ ", defaultValue=" + this.defaultValue + "]";
		}
	}

	/**
	 * The items schema for {@link TitledMultiSelectEnumSchema}, describing the allowed
	 * enum options with display labels.
	 *
	 * @param anyOf Array of enum options, each with a machine-readable value and a
	 * human-readable display label
	 */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static final class TitledMultiSelectItems {

		@JsonProperty("anyOf")
		private final List<EnumSchemaOption> anyOf;

		public TitledMultiSelectItems(List<EnumSchemaOption> anyOf) {
			Assert.notNull(anyOf, "anyOf must not be null");
			this.anyOf = anyOf;
		}

		public static Builder builder() {
			return new Builder();
		}

		public static class Builder {

			private List<EnumSchemaOption> anyOf;

			private Builder() {
			}

			public Builder anyOf(List<EnumSchemaOption> anyOf) {
				Assert.notNull(anyOf, "anyOf must not be null");
				this.anyOf = new ArrayList<>(anyOf);
				return this;
			}

			public Builder anyOf(EnumSchemaOption... anyOf) {
				Assert.notNull(anyOf, "anyOf must not be null");
				this.anyOf = Arrays.asList(anyOf);
				return this;
			}

			public TitledMultiSelectItems build() {
				Assert.notEmpty(anyOf, "anyOf must not be empty");
				return new TitledMultiSelectItems(anyOf);
			}

		}

		public List<EnumSchemaOption> anyOf() {
			return this.anyOf;
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) {
				return true;
			}
			if (!(o instanceof TitledMultiSelectItems)) {
				return false;
			}
			TitledMultiSelectItems that = (TitledMultiSelectItems) o;
			return Objects.equals(this.anyOf, that.anyOf);
		}

		@Override
		public int hashCode() {
			return Objects.hash(this.anyOf);
		}

		@Override
		public String toString() {
			return "TitledMultiSelectItems[anyOf=" + this.anyOf + "]";
		}
	}

	/**
	 * Schema for multiple-selection enumeration with display titles for each option.
	 *
	 * @param title Optional title for the enum field
	 * @param description Optional description for the enum field
	 * @param items Schema for the array items, containing the list of titled enum options
	 * @param minItems Optional minimum number of items to select
	 * @param maxItems Optional maximum number of items to select
	 * @param defaultValue Optional default selected values
	 */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static final class TitledMultiSelectEnumSchema {

		@JsonProperty("title")
		private final String title;

		@JsonProperty("description")
		private final String description;

		@JsonProperty("items")
		private final TitledMultiSelectItems items;

		@JsonProperty("minItems")
		private final Integer minItems;

		@JsonProperty("maxItems")
		private final Integer maxItems;

		@JsonProperty("default")
		private final List<String> defaultValue;

		public TitledMultiSelectEnumSchema(String title, String description, TitledMultiSelectItems items,
				Integer minItems, Integer maxItems, List<String> defaultValue) {
			Assert.notNull(items, "items must not be null");
			this.title = title;
			this.description = description;
			this.items = items;
			this.minItems = minItems;
			this.maxItems = maxItems;
			this.defaultValue = defaultValue;
		}

		@JsonProperty("type")
		public String type() {
			return "array";
		}

		public static Builder builder(TitledMultiSelectItems items) {
			return new Builder(items);
		}

		public static class Builder {

			private String title;

			private String description;

			private TitledMultiSelectItems items;

			private Integer minItems;

			private Integer maxItems;

			private List<String> defaultValue;

			private Builder(TitledMultiSelectItems items) {
				Assert.notNull(items, "items must not be null");
				this.items = items;
			}

			public Builder title(String title) {
				this.title = title;
				return this;
			}

			public Builder description(String description) {
				this.description = description;
				return this;
			}

			public Builder items(TitledMultiSelectItems items) {
				Assert.notNull(items, "items must not be null");
				this.items = items;
				return this;
			}

			public Builder minItems(Integer minItems) {
				this.minItems = minItems;
				return this;
			}

			public Builder maxItems(Integer maxItems) {
				this.maxItems = maxItems;
				return this;
			}

			public Builder defaults(List<String> defaultValue) {
				Assert.notNull(defaultValue, "defaultValue must not be null");
				this.defaultValue = new ArrayList<>(defaultValue);
				return this;
			}

			public Builder defaults(String... defaultValue) {
				Assert.notNull(defaultValue, "defaultValue must not be null");
				this.defaultValue = Arrays.asList(defaultValue);
				return this;
			}

			public TitledMultiSelectEnumSchema build() {
				return new TitledMultiSelectEnumSchema(title, description, items, minItems, maxItems, defaultValue);
			}

		}

		public String title() {
			return this.title;
		}

		public String description() {
			return this.description;
		}

		public TitledMultiSelectItems items() {
			return this.items;
		}

		public Integer minItems() {
			return this.minItems;
		}

		public Integer maxItems() {
			return this.maxItems;
		}

		public List<String> defaultValue() {
			return this.defaultValue;
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) {
				return true;
			}
			if (!(o instanceof TitledMultiSelectEnumSchema)) {
				return false;
			}
			TitledMultiSelectEnumSchema that = (TitledMultiSelectEnumSchema) o;
			return Objects.equals(this.title, that.title) && Objects.equals(this.description, that.description)
					&& Objects.equals(this.items, that.items) && Objects.equals(this.minItems, that.minItems)
					&& Objects.equals(this.maxItems, that.maxItems)
					&& Objects.equals(this.defaultValue, that.defaultValue);
		}

		@Override
		public int hashCode() {
			return Objects.hash(this.title, this.description, this.items, this.minItems, this.maxItems,
					this.defaultValue);
		}

		@Override
		public String toString() {
			return "TitledMultiSelectEnumSchema[title=" + this.title + ", description=" + this.description
					+ ", items=" + this.items + ", minItems=" + this.minItems + ", maxItems=" + this.maxItems
					+ ", defaultValue=" + this.defaultValue + "]";
		}
	}

	/**
	 * Schema for a boolean field in a form-based elicitation request.
	 *
	 * @param title Optional title for the boolean field
	 * @param description Optional description for the boolean field
	 * @param defaultValue Optional default value
	 */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static final class BooleanSchema {

		@JsonProperty("title")
		private final String title;

		@JsonProperty("description")
		private final String description;

		@JsonProperty("default")
		private final Boolean defaultValue;

		public BooleanSchema(String title, String description, Boolean defaultValue) {
			this.title = title;
			this.description = description;
			this.defaultValue = defaultValue;
		}

		@JsonProperty("type")
		public String type() {
			return "boolean";
		}

		public static Builder builder() {
			return new Builder();
		}

		public static class Builder {

			private String title;

			private String description;

			private Boolean defaultValue;

			private Builder() {
			}

			public Builder title(String title) {
				this.title = title;
				return this;
			}

			public Builder description(String description) {
				this.description = description;
				return this;
			}

			public Builder defaultValue(Boolean defaultValue) {
				this.defaultValue = defaultValue;
				return this;
			}

			public BooleanSchema build() {
				return new BooleanSchema(title, description, defaultValue);
			}

		}

		public String title() {
			return this.title;
		}

		public String description() {
			return this.description;
		}

		public Boolean defaultValue() {
			return this.defaultValue;
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) {
				return true;
			}
			if (!(o instanceof BooleanSchema)) {
				return false;
			}
			BooleanSchema that = (BooleanSchema) o;
			return Objects.equals(this.title, that.title) && Objects.equals(this.description, that.description)
					&& Objects.equals(this.defaultValue, that.defaultValue);
		}

		@Override
		public int hashCode() {
			return Objects.hash(this.title, this.description, this.defaultValue);
		}

		@Override
		public String toString() {
			return "BooleanSchema[title=" + this.title + ", description=" + this.description + ", defaultValue="
					+ this.defaultValue + "]";
		}
	}

	/**
	 * Schema for a numeric field in a form-based elicitation request, supporting both
	 * {@code "number"} (floating-point) and {@code "integer"} types.
	 *
	 * @param title Optional title for the numeric field
	 * @param description Optional description for the numeric field
	 * @param type The JSON Schema type, either {@code "number"} or {@code "integer"};
	 * defaults to {@code "number"} in the builder
	 * @param minimum Optional minimum value (inclusive)
	 * @param maximum Optional maximum value (inclusive)
	 * @param defaultValue Optional default value
	 */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static final class NumberSchema {

		@JsonProperty("title")
		private final String title;

		@JsonProperty("description")
		private final String description;

		@JsonProperty("type")
		private final String type;

		@JsonProperty("minimum")
		private final Number minimum;

		@JsonProperty("maximum")
		private final Number maximum;

		@JsonProperty("default")
		private final Number defaultValue;

		public NumberSchema(String title, String description, String type, Number minimum, Number maximum,
				Number defaultValue) {
			Assert.notNull(type, "type must not be null");
			this.title = title;
			this.description = description;
			this.type = type;
			this.minimum = minimum;
			this.maximum = maximum;
			this.defaultValue = defaultValue;
		}

		public static Builder builder() {
			return new Builder();
		}

		public static class Builder {

			private String title;

			private String description;

			private String type = "number";

			private Number minimum;

			private Number maximum;

			private Number defaultValue;

			private Builder() {
			}

			public Builder title(String title) {
				this.title = title;
				return this;
			}

			public Builder description(String description) {
				this.description = description;
				return this;
			}

			public Builder integer() {
				this.type = "integer";
				return this;
			}

			public Builder minimum(Number minimum) {
				this.minimum = minimum;
				return this;
			}

			public Builder maximum(Number maximum) {
				this.maximum = maximum;
				return this;
			}

			public Builder defaultValue(Number defaultValue) {
				this.defaultValue = defaultValue;
				return this;
			}

			public NumberSchema build() {
				return new NumberSchema(title, description, type, minimum, maximum, defaultValue);
			}

		}

		public String title() {
			return this.title;
		}

		public String description() {
			return this.description;
		}

		public String type() {
			return this.type;
		}

		public Number minimum() {
			return this.minimum;
		}

		public Number maximum() {
			return this.maximum;
		}

		public Number defaultValue() {
			return this.defaultValue;
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) {
				return true;
			}
			if (!(o instanceof NumberSchema)) {
				return false;
			}
			NumberSchema that = (NumberSchema) o;
			return Objects.equals(this.title, that.title) && Objects.equals(this.description, that.description)
					&& Objects.equals(this.type, that.type) && Objects.equals(this.minimum, that.minimum)
					&& Objects.equals(this.maximum, that.maximum)
					&& Objects.equals(this.defaultValue, that.defaultValue);
		}

		@Override
		public int hashCode() {
			return Objects.hash(this.title, this.description, this.type, this.minimum, this.maximum, this.defaultValue);
		}

		@Override
		public String toString() {
			return "NumberSchema[title=" + this.title + ", description=" + this.description + ", type=" + this.type
					+ ", minimum=" + this.minimum + ", maximum=" + this.maximum + ", defaultValue=" + this.defaultValue
					+ "]";
		}
	}

	/**
	 * Schema for a text input field in a form-based elicitation request.
	 *
	 * @param title Optional title for the text field
	 * @param description Optional description for the text field
	 * @param minLength Optional minimum string length
	 * @param maxLength Optional maximum string length
	 * @param format Optional format hint (e.g. {@code "email"}, {@code "uri"})
	 * @param defaultValue Optional default value
	 */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static final class StringSchema {

		@JsonProperty("title")
		private final String title;

		@JsonProperty("description")
		private final String description;

		@JsonProperty("minLength")
		private final Integer minLength;

		@JsonProperty("maxLength")
		private final Integer maxLength;

		@JsonProperty("format")
		private final String format;

		@JsonProperty("default")
		private final String defaultValue;

		public StringSchema(String title, String description, Integer minLength, Integer maxLength, String format,
				String defaultValue) {
			this.title = title;
			this.description = description;
			this.minLength = minLength;
			this.maxLength = maxLength;
			this.format = format;
			this.defaultValue = defaultValue;
		}

		@JsonProperty("type")
		public String type() {
			return "string";
		}

		public static Builder builder() {
			return new Builder();
		}

		public static class Builder {

			private String title;

			private String description;

			private Integer minLength;

			private Integer maxLength;

			private String format;

			private String defaultValue;

			private Builder() {
			}

			public Builder title(String title) {
				this.title = title;
				return this;
			}

			public Builder description(String description) {
				this.description = description;
				return this;
			}

			public Builder minLength(Integer minLength) {
				this.minLength = minLength;
				return this;
			}

			public Builder maxLength(Integer maxLength) {
				this.maxLength = maxLength;
				return this;
			}

			public Builder format(String format) {
				this.format = format;
				return this;
			}

			public Builder defaultValue(String defaultValue) {
				this.defaultValue = defaultValue;
				return this;
			}

			public StringSchema build() {
				Assert.isTrue(
						format == null || format.equals("uri") || format.equals("email") || format.equals("date")
								|| format.equals("date-time"),
						"format must be one of: null, \"uri\", \"email\", \"date\", \"date-time\"");
				return new StringSchema(title, description, minLength, maxLength, format, defaultValue);
			}

		}

		public String title() {
			return this.title;
		}

		public String description() {
			return this.description;
		}

		public Integer minLength() {
			return this.minLength;
		}

		public Integer maxLength() {
			return this.maxLength;
		}

		public String format() {
			return this.format;
		}

		public String defaultValue() {
			return this.defaultValue;
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) {
				return true;
			}
			if (!(o instanceof StringSchema)) {
				return false;
			}
			StringSchema that = (StringSchema) o;
			return Objects.equals(this.title, that.title) && Objects.equals(this.description, that.description)
					&& Objects.equals(this.minLength, that.minLength)
					&& Objects.equals(this.maxLength, that.maxLength) && Objects.equals(this.format, that.format)
					&& Objects.equals(this.defaultValue, that.defaultValue);
		}

		@Override
		public int hashCode() {
			return Objects.hash(this.title, this.description, this.minLength, this.maxLength, this.format,
					this.defaultValue);
		}

		@Override
		public String toString() {
			return "StringSchema[title=" + this.title + ", description=" + this.description + ", minLength="
					+ this.minLength + ", maxLength=" + this.maxLength + ", format=" + this.format + ", defaultValue="
					+ this.defaultValue + "]";
		}
	}

	/**
	 * A request from the server to elicit additional information from the user, either
	 * through the client or out-of-band.
	 *
	 * @see ElicitFormRequest
	 * @see ElicitUrlRequest
	 */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "mode",
			defaultImpl = ElicitFormRequest.class)
	@JsonSubTypes({ @JsonSubTypes.Type(value = ElicitFormRequest.class, name = ElicitFormRequest.MODE),
			@JsonSubTypes.Type(value = ElicitUrlRequest.class, name = ElicitUrlRequest.MODE) })
	public interface ElicitRequest extends Request {

		String message();

		Map<String, Object> meta();

		String mode();

		/**
		 * @deprecated Use {@link ElicitFormRequest#builder(String, Map)} instead.
		 */
		@Deprecated
		static ElicitFormRequest.Builder builder() {
			return new ElicitFormRequest.Builder();
		}

		/**
		 * @deprecated Use {@link ElicitFormRequest#builder(String, Map)} instead.
		 */
		@Deprecated
		static ElicitFormRequest.Builder builder(String message, Map<String, Object> requestedSchema) {
			return new ElicitFormRequest.Builder(message, requestedSchema);
		}

	}

	/**
	 * A request from the server to elicit additional information from the user via the
	 * client, using {@code form} mode.
	 * <p>
	 * The requested schema is flexible, but for standard schemas, consider using one the
	 * following types:
	 * <ul>
	 * <li>{@link BooleanSchema}
	 * <li>{@link NumberSchema}
	 * <li>{@link StringSchema}
	 * <li>{@link LegacyTitledEnumSchema}
	 * <li>{@link TitledSingleSelectEnumSchema}
	 * <li>{@link TitledMultiSelectEnumSchema}
	 * <li>{@link UntitledSingleSelectEnumSchema}
	 * <li>{@link UntitledMultiSelectEnumSchema}
	 * </ul>
	 *
	 * These can be used with a JSON mapper:
	 *
	 * <pre>
	 * McpJsonMapper mapper = McpJsonDefaults.getMapper();
	 * TypeRef&lt;Map&lt;String, Object&gt;&gt; mapType = new TypeRef&lt;Map&lt;String, Object&gt;&gt;() { };
	 * UntitledSingleSelectEnumSchema first = UntitledSingleSelectEnumSchema.builder()
	 *           .enumValues("option1", "option2", "option3")
	 *           .build();
	 * BooleanSchema second = BooleanSchema
	 *           .builder()
	 *           .title("Say yes")
	 *           .description("By selecting this, you say yes to the thing")
	 *           .build();
	 * Map&lt;String, Object&gt; properties = new LinkedHashMap&lt;&gt;();
	 * properties.put("first-thing", mapper.convertValue(first, mapType));
	 * properties.put("second-thing", mapper.convertValue(second, mapType));
	 * Map&lt;String, Object&gt; requestedSchema = new LinkedHashMap&lt;&gt;();
	 * requestedSchema.put("type", "object");
	 * requestedSchema.put("properties", properties);
	 * requestedSchema.put("required", Arrays.asList("first-thing", "second-thing"));
	 * </pre>
	 *
	 * @param message The message to present to the user
	 * @param requestedSchema A restricted subset of JSON Schema. Only top-level
	 * properties are allowed, without nesting. Per SEP-1613, the dialect defaults to JSON
	 * Schema 2020-12 ({@link #JSON_SCHEMA_DIALECT_2020_12}) when no explicit
	 * {@code $schema} entry is present. To declare a different dialect, include a
	 * {@code "$schema"} key in the map. For type-safety in the schemas, use one of the
	 * supported schema types.
	 * @param meta See specification for notes on _meta usage
	 * <p>
	 * Note: {@code message} and {@code requestedSchema} are required by the MCP
	 * specification. Deserialization accepts missing values and substitutes defaults to
	 * avoid breaking existing integrations that may omit these fields.
	 */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static final class ElicitFormRequest implements ElicitRequest {

		@JsonProperty("message")
		private final String message;

		@JsonProperty("requestedSchema")
		private final Map<String, Object> requestedSchema;

		@JsonProperty("_meta")
		private final Map<String, Object> meta;

		public static final String MODE = "form";

		public ElicitFormRequest(String message, Map<String, Object> requestedSchema, Map<String, Object> meta) {
			Assert.notNull(message, "message must not be null");
			Assert.notNull(requestedSchema, "requestedSchema must not be null");
			this.message = message;
			this.requestedSchema = requestedSchema;
			this.meta = meta;
		}

		@Override
		@JsonProperty("mode")
		public String mode() {
			return MODE;
		}

		@JsonCreator
		static ElicitFormRequest fromJson(@JsonProperty("message") String message,
				@JsonProperty("requestedSchema") Map<String, Object> requestedSchema,
				@JsonProperty("_meta") Map<String, Object> meta) {
			if (message == null || requestedSchema == null) {
				List<String> missing = new ArrayList<>();
				if (message == null) {
					missing.add("message -> ''");
					message = "";
				}
				if (requestedSchema == null) {
					missing.add("requestedSchema -> {}");
					requestedSchema = Collections.emptyMap();
				}
				logger.warn("ElicitFormRequest: missing required fields during deserialization: {}",
						String.join(", ", missing));
			}
			return new ElicitFormRequest(message, requestedSchema, meta);
		}

		public static Builder builder(String message, Map<String, Object> requestedSchema) {
			return new Builder(message, requestedSchema);
		}

		public static class Builder {

			private String message;

			private Map<String, Object> requestedSchema;

			private Map<String, Object> meta;

			/**
			 * @deprecated Use {@link ElicitFormRequest#builder(String, Map)} factory
			 * method instead.
			 */
			@Deprecated
			private Builder() {
			}

			private Builder(String message, Map<String, Object> requestedSchema) {
				Assert.notNull(message, "message must not be null");
				Assert.notNull(requestedSchema, "requestedSchema must not be null");
				this.message = message;
				this.requestedSchema = requestedSchema;
			}

			/**
			 * @deprecated Use {@link ElicitFormRequest#builder(String, Map)} factory
			 * method instead.
			 */
			@Deprecated
			public Builder message(String message) {
				Assert.notNull(message, "message must not be null");
				this.message = message;
				return this;
			}

			/**
			 * @deprecated Use {@link ElicitFormRequest#builder(String, Map)} factory
			 * method instead.
			 */
			@Deprecated
			public Builder requestedSchema(Map<String, Object> requestedSchema) {
				Assert.notNull(requestedSchema, "requestedSchema must not be null");
				this.requestedSchema = requestedSchema;
				return this;
			}

			public Builder meta(Map<String, Object> meta) {
				this.meta = meta;
				return this;
			}

			public Builder progressToken(Object progressToken) {
				if (this.meta == null) {
					this.meta = new HashMap<>();
				}
				this.meta.put("progressToken", progressToken);
				return this;
			}

			public ElicitFormRequest build() {
				Assert.notNull(message, "message must not be null");
				Assert.notNull(requestedSchema, "requestedSchema must not be null");
				return new ElicitFormRequest(message, requestedSchema, meta);
			}

		}

		public String message() {
			return this.message;
		}

		public Map<String, Object> requestedSchema() {
			return this.requestedSchema;
		}

		public Map<String, Object> meta() {
			return this.meta;
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) {
				return true;
			}
			if (!(o instanceof ElicitFormRequest)) {
				return false;
			}
			ElicitFormRequest that = (ElicitFormRequest) o;
			return Objects.equals(this.message, that.message)
					&& Objects.equals(this.requestedSchema, that.requestedSchema)
					&& Objects.equals(this.meta, that.meta);
		}

		@Override
		public int hashCode() {
			return Objects.hash(this.message, this.requestedSchema, this.meta);
		}

		@Override
		public String toString() {
			return "ElicitFormRequest[message=" + this.message + ", requestedSchema=" + this.requestedSchema
					+ ", meta=" + this.meta + "]";
		}
	}

	/**
	 * A request from the server to elicit additional information from the user out of
	 * band, using {@code url} mode.
	 *
	 * @param message The message to present to the user
	 * @param url The URL the user must navigate to.
	 * @param elicitationId The elicitation ID of the elicitations reques.t
	 * @param meta See specification for notes on _meta usage
	 * <p>
	 * Note: {@code message}, {@code url} and {@code elicitationId} are required by the
	 * MCP specification. Deserialization accepts missing values and substitutes defaults
	 * to avoid breaking existing integrations that may omit these fields.
	 */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static final class ElicitUrlRequest implements ElicitRequest {

		@JsonProperty("message")
		private final String message;

		@JsonProperty("url")
		private final String url;

		@JsonProperty("elicitationId")
		private final String elicitationId;

		@JsonProperty("_meta")
		private final Map<String, Object> meta;

		public static final String MODE = "url";

		public ElicitUrlRequest(String message, String url, String elicitationId, Map<String, Object> meta) {
			Assert.notNull(message, "message must not be null");
			Assert.notNull(url, "url must not be null");
			Assert.notNull(elicitationId, "elicitationId must not be null");
			this.message = message;
			this.url = url;
			this.elicitationId = elicitationId;
			this.meta = meta;
		}

		@Override
		@JsonProperty("mode")
		public String mode() {
			return MODE;
		}

		@JsonCreator
		static ElicitUrlRequest fromJson(@JsonProperty("message") String message, @JsonProperty("url") String url,
				@JsonProperty("elicitationId") String elicitationId, @JsonProperty("_meta") Map<String, Object> meta) {
			if (message == null || url == null || elicitationId == null) {
				List<String> missing = new ArrayList<>();
				if (message == null) {
					missing.add("message -> ''");
					message = "";
				}
				if (url == null) {
					missing.add("url -> ''");
					url = "";
				}
				if (elicitationId == null) {
					missing.add("elicitationId -> ''");
					elicitationId = "";
				}
				logger.warn("ElicitUrlRequest: missing required fields during deserialization: {}",
						String.join(", ", missing));
			}
			return new ElicitUrlRequest(message, url, elicitationId, meta);
		}

		public static Builder builder(String message, String url, String elicitationId) {
			return new Builder(message, url, elicitationId);
		}

		public static class Builder {

			private final String message;

			private final String url;

			private final String elicitationId;

			private Map<String, Object> meta;

			private Builder(String message, String url, String elicitationId) {
				Assert.notNull(message, "message must not be null");
				Assert.notNull(url, "url must not be null");
				Assert.notNull(elicitationId, "elicitationId must not be null");
				this.message = message;
				this.url = url;
				this.elicitationId = elicitationId;
			}

			public Builder meta(Map<String, Object> meta) {
				this.meta = meta;
				return this;
			}

			public Builder progressToken(Object progressToken) {
				if (this.meta == null) {
					this.meta = new HashMap<>();
				}
				this.meta.put("progressToken", progressToken);
				return this;
			}

			public ElicitUrlRequest build() {
				Assert.notNull(message, "message must not be null");
				Assert.notNull(url, "url must not be null");
				Assert.notNull(elicitationId, "elicitationId must not be null");
				return new ElicitUrlRequest(message, url, elicitationId, meta);
			}

		}

		public String message() {
			return this.message;
		}

		public String url() {
			return this.url;
		}

		public String elicitationId() {
			return this.elicitationId;
		}

		public Map<String, Object> meta() {
			return this.meta;
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) {
				return true;
			}
			if (!(o instanceof ElicitUrlRequest)) {
				return false;
			}
			ElicitUrlRequest that = (ElicitUrlRequest) o;
			return Objects.equals(this.message, that.message) && Objects.equals(this.url, that.url)
					&& Objects.equals(this.elicitationId, that.elicitationId)
					&& Objects.equals(this.meta, that.meta);
		}

		@Override
		public int hashCode() {
			return Objects.hash(this.message, this.url, this.elicitationId, this.meta);
		}

		@Override
		public String toString() {
			return "ElicitUrlRequest[message=" + this.message + ", url=" + this.url + ", elicitationId="
					+ this.elicitationId + ", meta=" + this.meta + "]";
		}
	}

	/**
	 * The client's response to an elicitation request.
	 *
	 * @param action The user action in response to the elicitation. "accept": User
	 * submitted the form/confirmed the action, "decline": User explicitly declined the
	 * action, "cancel": User dismissed without making an explicit choice
	 * @param content The submitted form data, only present when action is "accept".
	 * Contains values matching the requested schema
	 * @param meta See specification for notes on _meta usage
	 */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static final class ElicitResult implements Result {

		@JsonProperty("action")
		private final Action action;

		@JsonProperty("content")
		private final Map<String, Object> content;

		@JsonProperty("_meta")
		private final Map<String, Object> meta;

		public ElicitResult(Action action, Map<String, Object> content, Map<String, Object> meta) {
			Assert.notNull(action, "action must not be null");
			this.action = action;
			this.content = content;
			this.meta = meta;
		}

		@JsonCreator
		static ElicitResult fromJson(@JsonProperty("action") Action action,
				@JsonProperty("content") Map<String, Object> content, @JsonProperty("_meta") Map<String, Object> meta) {
			if (action == null) {
				logger.warn(
						"ElicitResult: missing required field 'action' during deserialization, using default 'cancel'");
				action = Action.CANCEL;
			}
			return new ElicitResult(action, content, meta);
		}

		public enum Action {

		// @formatter:off

			@JsonProperty("accept") ACCEPT,
			@JsonProperty("decline") DECLINE,
			@JsonProperty("cancel") CANCEL

		} // @formatter:on

		// backwards compatibility constructor
		public ElicitResult(Action action, Map<String, Object> content) {
			this(action, content, null);
		}

		@Deprecated
		public static Builder builder() {
			return new Builder();
		}

		public static Builder builder(Action action) {
			return new Builder(action);
		}

		public static class Builder {

			private Action action;

			private Map<String, Object> content;

			private Map<String, Object> meta;

			// tepmorary to support deprecated builder
			private Builder() {

			}

			private Builder(Action action) {
				Assert.notNull(action, "action must not be null");
				this.action = action;
			}

			@Deprecated
			public Builder message(Action action) {
				this.action = action;
				return this;
			}

			public Builder content(Map<String, Object> content) {
				this.content = content;
				return this;
			}

			public Builder meta(Map<String, Object> meta) {
				this.meta = meta;
				return this;
			}

			public ElicitResult build() {
				Assert.notNull(action, "action must not be null");
				return new ElicitResult(action, content, meta);
			}

		}

		public Action action() {
			return this.action;
		}

		public Map<String, Object> content() {
			return this.content;
		}

		public Map<String, Object> meta() {
			return this.meta;
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) {
				return true;
			}
			if (!(o instanceof ElicitResult)) {
				return false;
			}
			ElicitResult that = (ElicitResult) o;
			return Objects.equals(this.action, that.action) && Objects.equals(this.content, that.content)
					&& Objects.equals(this.meta, that.meta);
		}

		@Override
		public int hashCode() {
			return Objects.hash(this.action, this.content, this.meta);
		}

		@Override
		public String toString() {
			return "ElicitResult[action=" + this.action + ", content=" + this.content + ", meta=" + this.meta + "]";
		}
	}

	/**
	 * A notification from the server to the client indicating that an out-of-band URL
	 * elicitation interaction has completed.
	 *
	 * @param elicitationId The unique identifier of the completed elicitation
	 * @param meta See specification for notes on _meta usage
	 */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static final class ElicitationCompleteNotification implements Notification {

		@JsonProperty("elicitationId")
		private final String elicitationId;

		@JsonProperty("_meta")
		private final Map<String, Object> meta;

		public ElicitationCompleteNotification(String elicitationId, Map<String, Object> meta) {
			Assert.notNull(elicitationId, "elicitationId must not be null");
			this.elicitationId = elicitationId;
			this.meta = meta;
		}

		@JsonCreator
		static ElicitationCompleteNotification fromJson(@JsonProperty("elicitationId") String elicitationId,
				@JsonProperty("_meta") Map<String, Object> meta) {
			if (elicitationId == null || elicitationId.trim().isEmpty()) {
				logger.warn(
						"ElicitationCompleteNotification: missing required field 'elicitationId' during deserialization, using default ''");
				elicitationId = "";
			}
			return new ElicitationCompleteNotification(elicitationId, meta);
		}

		public ElicitationCompleteNotification(String elicitationId) {
			this(elicitationId, null);
		}

		public String elicitationId() {
			return this.elicitationId;
		}

		public Map<String, Object> meta() {
			return this.meta;
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) {
				return true;
			}
			if (!(o instanceof ElicitationCompleteNotification)) {
				return false;
			}
			ElicitationCompleteNotification that = (ElicitationCompleteNotification) o;
			return Objects.equals(this.elicitationId, that.elicitationId) && Objects.equals(this.meta, that.meta);
		}

		@Override
		public int hashCode() {
			return Objects.hash(this.elicitationId, this.meta);
		}

		@Override
		public String toString() {
			return "ElicitationCompleteNotification[elicitationId=" + this.elicitationId + ", meta=" + this.meta + "]";
		}
	}

	// ---------------------------
	// Pagination Interfaces
	// ---------------------------
	/**
	 * A request that supports pagination using cursors.
	 *
	 * @param cursor An opaque token representing the current pagination position. If
	 * provided, the server should return results starting after this cursor
	 * @param meta See specification for notes on _meta usage
	 */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static final class PaginatedRequest implements Request {

		@JsonProperty("cursor")
		private final String cursor;

		@JsonProperty("_meta")
		private final Map<String, Object> meta;

		public PaginatedRequest(String cursor, Map<String, Object> meta) {
			this.cursor = cursor;
			this.meta = meta;
		}

		public PaginatedRequest(String cursor) {
			this(cursor, null);
		}

		/**
		 * Creates a new paginated request with an empty cursor.
		 */
		public PaginatedRequest() {
			this(null);
		}

		public String cursor() {
			return this.cursor;
		}

		public Map<String, Object> meta() {
			return this.meta;
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) {
				return true;
			}
			if (!(o instanceof PaginatedRequest)) {
				return false;
			}
			PaginatedRequest that = (PaginatedRequest) o;
			return Objects.equals(this.cursor, that.cursor) && Objects.equals(this.meta, that.meta);
		}

		@Override
		public int hashCode() {
			return Objects.hash(this.cursor, this.meta);
		}

		@Override
		public String toString() {
			return "PaginatedRequest[cursor=" + this.cursor + ", meta=" + this.meta + "]";
		}
	}

	/**
	 * An opaque token representing the pagination position after the last returned
	 * result. If present, there may be more results available.
	 *
	 * @param nextCursor An opaque token representing the pagination position after the
	 * last returned result. If present, there may be more results available
	 */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static final class PaginatedResult {

		@JsonProperty("nextCursor")
		private final String nextCursor;

		public PaginatedResult(String nextCursor) {
			this.nextCursor = nextCursor;
		}

		public String nextCursor() {
			return this.nextCursor;
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) {
				return true;
			}
			if (!(o instanceof PaginatedResult)) {
				return false;
			}
			PaginatedResult that = (PaginatedResult) o;
			return Objects.equals(this.nextCursor, that.nextCursor);
		}

		@Override
		public int hashCode() {
			return Objects.hash(this.nextCursor);
		}

		@Override
		public String toString() {
			return "PaginatedResult[nextCursor=" + this.nextCursor + "]";
		}
	}

	// ---------------------------
	// Progress and Logging
	// ---------------------------
	/**
	 * The Model Context Protocol (MCP) supports optional progress tracking for
	 * long-running operations through notification messages. Either side can send
	 * progress notifications to provide updates about operation status.
	 *
	 * @param progressToken A unique token to identify the progress notification. MUST be
	 * unique across all active requests.
	 * @param progress A value indicating the current progress.
	 * @param total An optional total amount of work to be done, if known.
	 * @param message An optional message providing additional context about the progress.
	 * @param meta See specification for notes on _meta usage
	 * <p>
	 * Note: {@code progressToken} and {@code progress} are required by the MCP
	 * specification. Deserialization accepts missing values and substitutes defaults to
	 * avoid breaking existing integrations that may omit these fields.
	 */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static final class ProgressNotification implements Notification {

		@JsonProperty("progressToken")
		private final Object progressToken;

		@JsonProperty("progress")
		private final Double progress;

		@JsonProperty("total")
		private final Double total;

		@JsonProperty("message")
		private final String message;

		@JsonProperty("_meta")
		private final Map<String, Object> meta;

		public ProgressNotification(Object progressToken, Double progress, Double total, String message,
				Map<String, Object> meta) {
			Assert.notNull(progressToken, "progressToken must not be null");
			Assert.notNull(progress, "progress must not be null");
			this.progressToken = progressToken;
			this.progress = progress;
			this.total = total;
			this.message = message;
			this.meta = meta;
		}

		@JsonCreator
		static ProgressNotification fromJson(@JsonProperty("progressToken") Object progressToken,
				@JsonProperty("progress") Double progress, @JsonProperty("total") Double total,
				@JsonProperty("message") String message, @JsonProperty("_meta") Map<String, Object> meta) {
			if (progressToken == null || progress == null) {
				List<String> missing = new ArrayList<>();
				if (progressToken == null) {
					missing.add("progressToken -> ''");
					progressToken = "";
				}
				if (progress == null) {
					missing.add("progress -> 0.0");
					progress = 0.0;
				}
				logger.warn("ProgressNotification: missing required fields during deserialization: {}",
						String.join(", ", missing));
			}
			return new ProgressNotification(progressToken, progress, total, message, meta);
		}

		@Deprecated
		public ProgressNotification(Object progressToken, double progress, Double total, String message) {
			this(progressToken, progress, total, message, null);
		}

		public static Builder builder(Object progressToken, double progress) {
			return new Builder(progressToken, progress);
		}

		public static class Builder {

			private final Object progressToken;

			private final Double progress;

			private Double total;

			private String message;

			private Map<String, Object> meta;

			private Builder(Object progressToken, double progress) {
				Assert.notNull(progressToken, "progressToken must not be null");
				this.progressToken = progressToken;
				this.progress = progress;
			}

			public Builder total(Double total) {
				this.total = total;
				return this;
			}

			public Builder message(String message) {
				this.message = message;
				return this;
			}

			public Builder meta(Map<String, Object> meta) {
				this.meta = meta;
				return this;
			}

			public ProgressNotification build() {
				return new ProgressNotification(progressToken, progress, total, message, meta);
			}

		}

		public Object progressToken() {
			return this.progressToken;
		}

		public Double progress() {
			return this.progress;
		}

		public Double total() {
			return this.total;
		}

		public String message() {
			return this.message;
		}

		public Map<String, Object> meta() {
			return this.meta;
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) {
				return true;
			}
			if (!(o instanceof ProgressNotification)) {
				return false;
			}
			ProgressNotification that = (ProgressNotification) o;
			return Objects.equals(this.progressToken, that.progressToken)
					&& Objects.equals(this.progress, that.progress) && Objects.equals(this.total, that.total)
					&& Objects.equals(this.message, that.message) && Objects.equals(this.meta, that.meta);
		}

		@Override
		public int hashCode() {
			return Objects.hash(this.progressToken, this.progress, this.total, this.message, this.meta);
		}

		@Override
		public String toString() {
			return "ProgressNotification[progressToken=" + this.progressToken + ", progress=" + this.progress
					+ ", total=" + this.total + ", message=" + this.message + ", meta=" + this.meta + "]";
		}

	}

	/**
	 * The Model Context Protocol (MCP) provides a standardized way for servers to send
	 * resources update message to clients.
	 *
	 * @param uri The updated resource uri.
	 * @param meta See specification for notes on _meta usage
	 */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static final class ResourcesUpdatedNotification implements Notification {

		@JsonProperty("uri")
		private final String uri;

		@JsonProperty("_meta")
		private final Map<String, Object> meta;

		public ResourcesUpdatedNotification(String uri, Map<String, Object> meta) {
			Assert.notNull(uri, "uri must not be null");
			this.uri = uri;
			this.meta = meta;
		}

		public ResourcesUpdatedNotification(String uri) {
			this(uri, null);
		}

		@JsonCreator
		static ResourcesUpdatedNotification fromJson(@JsonProperty("uri") String uri,
				@JsonProperty("_meta") Map<String, Object> meta) {
			if (uri == null) {
				logger.warn(
						"ResourcesUpdatedNotification: missing required field 'uri' during deserialization, using default ''");
				uri = "";
			}
			return new ResourcesUpdatedNotification(uri, meta);
		}

		public String uri() {
			return this.uri;
		}

		public Map<String, Object> meta() {
			return this.meta;
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) {
				return true;
			}
			if (!(o instanceof ResourcesUpdatedNotification)) {
				return false;
			}
			ResourcesUpdatedNotification that = (ResourcesUpdatedNotification) o;
			return Objects.equals(this.uri, that.uri) && Objects.equals(this.meta, that.meta);
		}

		@Override
		public int hashCode() {
			return Objects.hash(this.uri, this.meta);
		}

		@Override
		public String toString() {
			return "ResourcesUpdatedNotification[uri=" + this.uri + ", meta=" + this.meta + "]";
		}
	}

	/**
	 * The Model Context Protocol (MCP) provides a standardized way for servers to send
	 * structured log messages to clients. Clients can control logging verbosity by
	 * setting minimum log levels, with servers sending notifications containing severity
	 * levels, optional logger names, and arbitrary JSON-serializable data.
	 *
	 * @param level The severity levels. The minimum log level is set by the client.
	 * @param logger The logger that generated the message.
	 * @param data JSON-serializable logging data.
	 * @param meta See specification for notes on _meta usage
	 * <p>
	 * Note: {@code level} and {@code data} are required by the MCP specification.
	 * Deserialization accepts missing values and substitutes defaults to avoid breaking
	 * existing integrations that may omit these fields.
	 */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static final class LoggingMessageNotification implements Notification {

		@JsonProperty("level")
		private final LoggingLevel level;

		@JsonProperty("logger")
		private final String logger;

		@JsonProperty("data")
		private final String data;

		@JsonProperty("_meta")
		private final Map<String, Object> meta;

		public LoggingMessageNotification(LoggingLevel level, String logger, String data,
				Map<String, Object> meta) {
			Assert.notNull(level, "level must not be null");
			Assert.notNull(data, "data must not be null");
			this.level = level;
			this.logger = logger;
			this.data = data;
			this.meta = meta;
		}

		@JsonCreator
		static LoggingMessageNotification fromJson(@JsonProperty("level") LoggingLevel level,
				@JsonProperty("logger") String loggerName, @JsonProperty("data") String data,
				@JsonProperty("_meta") Map<String, Object> meta) {
			if (level == null || data == null) {
				List<String> missing = new ArrayList<>();
				if (level == null) {
					missing.add("level -> INFO");
					level = LoggingLevel.INFO;
				}
				if (data == null) {
					missing.add("data -> ''");
					data = "";
				}
				McpSchema.logger.warn("LoggingMessageNotification: missing required fields during deserialization: {}",
						String.join(", ", missing));
			}
			return new LoggingMessageNotification(level, loggerName, data, meta);
		}

		// backwards compatibility constructor
		public LoggingMessageNotification(LoggingLevel level, String logger, String data) {
			this(level, logger, data, null);
		}

		/**
		 * @deprecated Use {@link #builder(LoggingLevel, String)} instead.
		 */
		@Deprecated
		public static Builder builder() {
			return new Builder().level(LoggingLevel.INFO);
		}

		public static Builder builder(LoggingLevel level, String data) {
			return new Builder(level, data);
		}

		public static class Builder {

			private LoggingLevel level;

			private String logger = "server";

			private String data;

			private Map<String, Object> meta;

			/**
			 * @deprecated Use
			 * {@link LoggingMessageNotification#builder(LoggingLevel, String)} factory
			 * method instead.
			 */
			@Deprecated
			public Builder() {
			}

			private Builder(LoggingLevel level, String data) {
				Assert.notNull(level, "level must not be null");
				Assert.notNull(data, "data must not be null");
				this.level = level;
				this.data = data;
			}

			@Deprecated
			public Builder level(LoggingLevel level) {
				Assert.notNull(level, "level must not be null");
				this.level = level;
				return this;
			}

			public Builder logger(String logger) {
				this.logger = logger;
				return this;
			}

			@Deprecated
			public Builder data(String data) {
				Assert.notNull(data, "data must not be null");
				this.data = data;
				return this;
			}

			public Builder meta(Map<String, Object> meta) {
				this.meta = meta;
				return this;
			}

			public LoggingMessageNotification build() {
				Assert.notNull(level, "level must not be null");
				Assert.notNull(data, "data must not be null");
				return new LoggingMessageNotification(level, logger, data, meta);
			}

		}

		public LoggingLevel level() {
			return this.level;
		}

		public String logger() {
			return this.logger;
		}

		public String data() {
			return this.data;
		}

		public Map<String, Object> meta() {
			return this.meta;
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) {
				return true;
			}
			if (!(o instanceof LoggingMessageNotification)) {
				return false;
			}
			LoggingMessageNotification that = (LoggingMessageNotification) o;
			return Objects.equals(this.level, that.level) && Objects.equals(this.logger, that.logger)
					&& Objects.equals(this.data, that.data) && Objects.equals(this.meta, that.meta);
		}

		@Override
		public int hashCode() {
			return Objects.hash(this.level, this.logger, this.data, this.meta);
		}

		@Override
		public String toString() {
			return "LoggingMessageNotification[level=" + this.level + ", logger=" + this.logger + ", data=" + this.data
					+ ", meta=" + this.meta + "]";
		}
	}

	/**
	 * Severity levels for MCP log messages, ordered from least to most severe. The
	 * numeric {@link #level()} can be used to compare severities. Deserialization is
	 * case-insensitive and returns {@code null} for unrecognized values.
	 */
	public enum LoggingLevel {

	// @formatter:off

		@JsonProperty("debug") DEBUG(0),
		@JsonProperty("info") INFO(1),
		@JsonProperty("notice") NOTICE(2),
		@JsonProperty("warning") WARNING(3),
		@JsonProperty("error") ERROR(4),
		@JsonProperty("critical") CRITICAL(5),
		@JsonProperty("alert") ALERT(6),
		@JsonProperty("emergency") EMERGENCY(7);
		// @formatter:on

		private final int level;

		private static final Map<String, LoggingLevel> BY_NAME;

		static {
			Map<String, LoggingLevel> m = new HashMap<>();
			for (LoggingLevel l : values()) {
				m.put(l.name().toLowerCase(), l);
			}
				BY_NAME = Collections.unmodifiableMap(m);
		}

		LoggingLevel(int level) {
			this.level = level;
		}

		public int level() {
			return level;
		}

		@JsonCreator
		public static LoggingLevel fromValue(String value) {
			return value == null ? null : BY_NAME.get(value.toLowerCase());
		}

	}

	/**
	 * A request from the client to the server, to enable or adjust logging.
	 *
	 * @param level The level of logging that the client wants to receive from the server.
	 * The server should send all logs at this level and higher (i.e., more severe) to the
	 * client as notifications/message
	 */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static final class SetLevelRequest {

		@JsonProperty("level")
		private final LoggingLevel level;

		public SetLevelRequest(LoggingLevel level) {
			Assert.notNull(level, "level must not be null");
			this.level = level;
		}

		@JsonCreator
		static SetLevelRequest fromJson(@JsonProperty("level") LoggingLevel level) {
			if (level == null) {
				logger.warn(
						"SetLevelRequest: missing required field 'level' during deserialization, using default 'info'");
				level = LoggingLevel.INFO;
			}
			return new SetLevelRequest(level);
		}

		public LoggingLevel level() {
			return this.level;
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) {
				return true;
			}
			if (!(o instanceof SetLevelRequest)) {
				return false;
			}
			SetLevelRequest that = (SetLevelRequest) o;
			return Objects.equals(this.level, that.level);
		}

		@Override
		public int hashCode() {
			return Objects.hash(this.level);
		}

		@Override
		public String toString() {
			return "SetLevelRequest[level=" + this.level + "]";
		}
	}

	// ---------------------------
	// Autocomplete
	// ---------------------------

	/**
	 * A reference to a prompt or resource that can be used as input for completion
	 * requests. Implementations are identified by a {@code "type"} discriminator field
	 * whose value maps to a concrete subtype via {@code @JsonSubTypes}.
	 */
	@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.EXISTING_PROPERTY, property = "type",
			visible = true)
	@JsonSubTypes({ @JsonSubTypes.Type(value = PromptReference.class, name = PromptReference.TYPE),
			@JsonSubTypes.Type(value = ResourceReference.class, name = ResourceReference.TYPE) })
	public interface CompleteReference {

		default String type() {
			if (this instanceof PromptReference) {
				return PromptReference.TYPE;
			}
			else if (this instanceof ResourceReference) {
				return ResourceReference.TYPE;
			}
			throw new IllegalArgumentException("Unknown CompleteReference type: " + this);
		}

		@Deprecated
		default String identifier() {
			return null;
		}

	}

	/**
	 * Identifies a prompt for completion requests.
	 *
	 * @param type Always {@value #TYPE}; present as the polymorphic discriminator. Any
	 * non-null value other than {@value #TYPE} is replaced with {@value #TYPE} and a WARN
	 * is logged.
	 * @param name The name of the prompt
	 * @param title An optional title for the prompt
	 */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static final class PromptReference implements CompleteReference, Identifier {

		@JsonProperty("type")
		private final String type;

		@JsonProperty("name")
		private final String name;

		@JsonProperty("title")
		private final String title;

		public static final String TYPE = "ref/prompt";

		public PromptReference(String type, String name, String title) {
			Assert.hasText(name, "name must not be null or empty");
			if (type != null && !TYPE.equals(type)) {
				logger.warn("PromptReference: 'type' argument '{}' is ignored, type is always '{}'", type, TYPE);
			}
			this.type = TYPE;
			this.name = name;
			this.title = title;
		}

		@JsonCreator
		static PromptReference fromJson(@JsonProperty("type") String type, @JsonProperty("name") String name,
				@JsonProperty("title") String title) {
			return new PromptReference(type, name, title);
		}

		/**
		 * @deprecated The {@code type} argument is ignored — the type discriminator is
		 * always {@value #TYPE}. Use {@link #PromptReference(String)} or the
		 * {@link #builder(String)} instead.
		 */
		@Deprecated
		public PromptReference(String type, String name) {
			this(type, name, null);
		}

		public PromptReference(String name) {
			this(TYPE, name, null);
		}

		@Override
		public String identifier() {
			return name();
		}

		public String type() {
			return this.type;
		}

		public String name() {
			return this.name;
		}

		public String title() {
			return this.title;
		}

		@Override
		public boolean equals(Object obj) {
			if (this == obj)
				return true;
			if (obj == null || getClass() != obj.getClass())
				return false;
			PromptReference that = (PromptReference) obj;
			return Objects.equals(name, that.name);
		}

		@Override
		public int hashCode() {
			return Objects.hash(name);
		}

		@Override
		public String toString() {
			return "PromptReference[type=" + this.type + ", name=" + this.name + ", title=" + this.title + "]";
		}

		public static Builder builder(String name) {
			return new Builder(name);
		}

		public static final class Builder {

			private final String name;

			private String title;

			private Builder(String name) {
				this.name = name;
			}

			public Builder title(String title) {
				this.title = title;
				return this;
			}

			public PromptReference build() {
				return new PromptReference(TYPE, name, title);
			}

		}

	}

	// TODO: this should actually be a ResourceTemplateReference
	/**
	 * A reference to a resource or resource template definition for completion requests.
	 *
	 * @param uri The URI or URI template of the resource
	 */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static final class ResourceReference implements CompleteReference {

		@JsonProperty("uri")
		private final String uri;

		public static final String TYPE = "ref/resource";

		public ResourceReference(String uri) {
			Assert.notNull(uri, "uri must not be null");
			this.uri = uri;
		}

		@JsonProperty("type")
		@Override
		public String type() {
			return CompleteReference.super.type();
		}

		@JsonCreator
		static ResourceReference fromJson(@JsonProperty("uri") String uri, @JsonProperty("type") String type) {
			return new ResourceReference(uri);
		}

		@Deprecated
		public ResourceReference(String type, String uri) {
			this(uri);
			logger.warn("ResourceReference: type argument '{}' is ignored, type is always '{}'", type, TYPE);
		}

		@Override
		public String identifier() {
			return uri();
		}

		public String uri() {
			return this.uri;
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) {
				return true;
			}
			if (!(o instanceof ResourceReference)) {
				return false;
			}
			ResourceReference that = (ResourceReference) o;
			return Objects.equals(this.uri, that.uri);
		}

		@Override
		public int hashCode() {
			return Objects.hash(this.uri);
		}

		@Override
		public String toString() {
			return "ResourceReference[uri=" + this.uri + "]";
		}
	}

	/**
	 * A request from the client to the server, to ask for completion options.
	 *
	 * @param ref A reference to a prompt or resource template definition
	 * @param argument The argument's information for completion requests
	 * @param meta See specification for notes on _meta usage
	 * @param context Additional, optional context for completions
	 */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static final class CompleteRequest implements Request {

		@JsonProperty("ref")
		private final CompleteReference ref;

		@JsonProperty("argument")
		private final CompleteArgument argument;

		@JsonProperty("_meta")
		private final Map<String, Object> meta;

		@JsonProperty("context")
		private final CompleteContext context;

		public CompleteRequest(CompleteReference ref, CompleteArgument argument, Map<String, Object> meta,
                               CompleteContext context) {
			Assert.notNull(ref, "ref must not be null");
			Assert.notNull(argument, "argument must not be null");
			this.ref = ref;
			this.argument = argument;
			this.meta = meta;
			this.context = context;
		}

		@JsonCreator
		static CompleteRequest fromJson(@JsonProperty("ref") CompleteReference ref,
				@JsonProperty("argument") CompleteArgument argument, @JsonProperty("_meta") Map<String, Object> meta,
				@JsonProperty("context") CompleteContext context) {
			return new CompleteRequest(ref, argument, meta, context);
		}

		@Deprecated
		public CompleteRequest(CompleteReference ref, CompleteArgument argument, Map<String, Object> meta) {
			this(ref, argument, meta, null);
		}

		@Deprecated
		public CompleteRequest(CompleteReference ref, CompleteArgument argument, CompleteContext context) {
			this(ref, argument, null, context);
		}

		@Deprecated
		public CompleteRequest(CompleteReference ref, CompleteArgument argument) {
			this(ref, argument, null, null);
		}

		public static Builder builder(CompleteReference ref, CompleteArgument argument) {
			return new Builder(ref, argument);
		}

		public static class Builder {

			private final CompleteReference ref;

			private final CompleteArgument argument;

			private Map<String, Object> meta;

			private CompleteContext context;

			private Builder(CompleteReference ref, CompleteArgument argument) {
				Assert.notNull(ref, "ref must not be null");
				Assert.notNull(argument, "argument must not be null");
				this.ref = ref;
				this.argument = argument;
			}

			public Builder meta(Map<String, Object> meta) {
				this.meta = meta;
				return this;
			}

			public Builder context(CompleteContext context) {
				this.context = context;
				return this;
			}

			public CompleteRequest build() {
				return new CompleteRequest(ref, argument, meta, context);
			}

		}

		public CompleteReference ref() {
			return this.ref;
		}

		public CompleteArgument argument() {
			return this.argument;
		}

		public Map<String, Object> meta() {
			return this.meta;
		}

		public CompleteContext context() {
			return this.context;
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) {
				return true;
			}
			if (!(o instanceof CompleteRequest)) {
				return false;
			}
			CompleteRequest that = (CompleteRequest) o;
			return Objects.equals(this.ref, that.ref) && Objects.equals(this.argument, that.argument)
					&& Objects.equals(this.meta, that.meta) && Objects.equals(this.context, that.context);
		}

		@Override
		public int hashCode() {
			return Objects.hash(this.ref, this.argument, this.meta, this.context);
		}

		@Override
		public String toString() {
			return "CompleteRequest[ref=" + this.ref + ", argument=" + this.argument + ", meta=" + this.meta
					+ ", context=" + this.context + "]";
		}

		/**
		 * The argument's information for completion requests.
		 *
		 * @param name The name of the argument
		 * @param value The value of the argument to use for completion matching
		 */
		@JsonInclude(JsonInclude.Include.NON_ABSENT)
		@JsonIgnoreProperties(ignoreUnknown = true)
		public static final class CompleteArgument {

			@JsonProperty("name")
			private final String name;

			@JsonProperty("value")
			private final String value;

			public CompleteArgument(@JsonProperty("name") String name, @JsonProperty("value") String value) {
				Assert.hasText(name, "name must not be empty");
				Assert.notNull(value, "value must not be null");
				this.name = name;
				this.value = value;
			}

			public String name() {
				return this.name;
			}

			public String value() {
				return this.value;
			}

			@Override
			public boolean equals(Object o) {
				if (this == o) {
					return true;
				}
				if (!(o instanceof CompleteArgument)) {
					return false;
				}
				CompleteArgument that = (CompleteArgument) o;
				return Objects.equals(this.name, that.name) && Objects.equals(this.value, that.value);
			}

			@Override
			public int hashCode() {
				return Objects.hash(this.name, this.value);
			}

			@Override
			public String toString() {
				return "CompleteArgument[name=" + this.name + ", value=" + this.value + "]";
			}
		}

		/**
		 * Additional, optional context for completions.
		 *
		 * @param arguments Previously-resolved variables in a URI template or prompt
		 */
		@JsonInclude(JsonInclude.Include.NON_ABSENT)
		@JsonIgnoreProperties(ignoreUnknown = true)
		public static final class CompleteContext {

			@JsonProperty("arguments")
			private final Map<String, String> arguments;

			public CompleteContext(@JsonProperty("arguments") Map<String, String> arguments) {
				this.arguments = arguments;
			}

			public static Builder builder() {
				return new Builder();
			}

			public static class Builder {

				private Map<String, String> arguments;

				public Builder arguments(Map<String, String> arguments) {
					this.arguments = arguments;
					return this;
				}

				public CompleteContext build() {
					return new CompleteContext(arguments);
				}

			}

			public Map<String, String> arguments() {
				return this.arguments;
			}

			@Override
			public boolean equals(Object o) {
				if (this == o) {
					return true;
				}
				if (!(o instanceof CompleteContext)) {
					return false;
				}
				CompleteContext that = (CompleteContext) o;
				return Objects.equals(this.arguments, that.arguments);
			}

			@Override
			public int hashCode() {
				return Objects.hash(this.arguments);
			}

			@Override
			public String toString() {
				return "CompleteContext[arguments=" + this.arguments + "]";
			}
		}
	}

	/**
	 * The server's response to a completion/complete request.
	 *
	 * @param completion The completion information containing values and metadata
	 * @param meta See specification for notes on _meta usage
	 */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static final class CompleteResult implements Result {

		@JsonProperty("completion")
		private final CompleteCompletion completion;

		@JsonProperty("_meta")
		private final Map<String, Object> meta;

		public CompleteResult(CompleteCompletion completion, Map<String, Object> meta) {
			Assert.notNull(completion, "completion must not be null");
			this.completion = completion;
			this.meta = meta;
		}

		@JsonCreator
		static CompleteResult fromJson(@JsonProperty("completion") CompleteCompletion completion,
				@JsonProperty("_meta") Map<String, Object> meta) {
			if (completion == null) {
				logger.warn(
						"CompleteResult: missing required field 'completion' during deserialization, using default {values=[]}");
				completion = new CompleteCompletion(Collections.emptyList(), null, null);
			}
			return new CompleteResult(completion, meta);
		}

		public CompleteResult(CompleteCompletion completion) {
			this(completion, null);
		}

		public CompleteCompletion completion() {
			return this.completion;
		}

		public Map<String, Object> meta() {
			return this.meta;
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) {
				return true;
			}
			if (!(o instanceof CompleteResult)) {
				return false;
			}
			CompleteResult that = (CompleteResult) o;
			return Objects.equals(this.completion, that.completion) && Objects.equals(this.meta, that.meta);
		}

		@Override
		public int hashCode() {
			return Objects.hash(this.completion, this.meta);
		}

		@Override
		public String toString() {
			return "CompleteResult[completion=" + this.completion + ", meta=" + this.meta + "]";
		}

		/**
		 * The server's response to a completion/complete request
		 *
		 * @param values An array of completion values. Must not exceed 100 items
		 * @param total The total number of completion options available. This can exceed
		 * the number of values actually sent in the response
		 * @param hasMore Indicates whether there are additional completion options beyond
		 * those provided in the current response, even if the exact total is unknown
		 */
		@JsonInclude(JsonInclude.Include.NON_ABSENT)
		@JsonIgnoreProperties(ignoreUnknown = true)
		public static final class CompleteCompletion {

			@JsonProperty("values")
			private final List<String> values;

			@JsonProperty("total")
			private final Integer total;

			@JsonProperty("hasMore")
			private final Boolean hasMore;

			public CompleteCompletion(List<String> values, Integer total, Boolean hasMore) {
				Assert.notNull(values, "values must not be null");
				this.values = values;
				this.total = total;
				this.hasMore = hasMore;
			}

			public CompleteCompletion(List<String> values) {
				this(values, null, null);
			}

			public List<String> values() {
				return this.values;
			}

			public Integer total() {
				return this.total;
			}

			public Boolean hasMore() {
				return this.hasMore;
			}

			@Override
			public boolean equals(Object o) {
				if (this == o) {
					return true;
				}
				if (!(o instanceof CompleteCompletion)) {
					return false;
				}
				CompleteCompletion that = (CompleteCompletion) o;
				return Objects.equals(this.values, that.values) && Objects.equals(this.total, that.total)
						&& Objects.equals(this.hasMore, that.hasMore);
			}

			@Override
			public int hashCode() {
				return Objects.hash(this.values, this.total, this.hasMore);
			}

			@Override
			public String toString() {
				return "CompleteCompletion[values=" + this.values + ", total=" + this.total + ", hasMore=" + this.hasMore
						+ "]";
			}
		}
	}

	// ---------------------------
	// Content Types
	// ---------------------------

	/**
	 * A polymorphic content value that can appear in messages and tool results. The
	 * concrete type is determined by the {@code "type"} JSON property.
	 */
	@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
	@JsonSubTypes({ @JsonSubTypes.Type(value = TextContent.class, name = "text"),
			@JsonSubTypes.Type(value = ImageContent.class, name = "image"),
			@JsonSubTypes.Type(value = AudioContent.class, name = "audio"),
			@JsonSubTypes.Type(value = EmbeddedResource.class, name = "resource"),
			@JsonSubTypes.Type(value = ResourceLink.class, name = "resource_link") })
	public interface Content extends Meta {

		@JsonIgnore
		default String type() {
			if (this instanceof TextContent) {
				return "text";
			}
			else if (this instanceof ImageContent) {
				return "image";
			}
			else if (this instanceof AudioContent) {
				return "audio";
			}
			else if (this instanceof EmbeddedResource) {
				return "resource";
			}
			else if (this instanceof ResourceLink) {
				return "resource_link";
			}
			throw new IllegalArgumentException("Unknown content type: " + this);
		}

	}

	/**
	 * Text provided to or from an LLM.
	 *
	 * @param annotations Optional annotations for the client
	 * @param text The text content of the message
	 * @param meta See specification for notes on _meta usage
	 */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static final class TextContent implements Annotated, Content {

		@JsonProperty("annotations")
		private final Annotations annotations;

		@JsonProperty("text")
		private final String text;

		@JsonProperty("_meta")
		private final Map<String, Object> meta;

		public TextContent(Annotations annotations, String text, Map<String, Object> meta) {
			Assert.notNull(text, "text must not be null");
			this.annotations = annotations;
			this.text = text;
			this.meta = meta;
		}

		@JsonCreator
		static TextContent fromJson(@JsonProperty("annotations") Annotations annotations,
				@JsonProperty("text") String text, @JsonProperty("_meta") Map<String, Object> meta) {
			if (text == null) {
				logger.warn("TextContent: missing required field 'text' during deserialization, using default ''");
				text = "";
			}
			return new TextContent(annotations, text, meta);
		}

		@Deprecated
		public TextContent(Annotations annotations, String text) {
			this(annotations, text, null);
		}

		@Deprecated
		public TextContent(String content) {
			this(null, content, null);
		}

		public static Builder builder(String text) {
			return new Builder(text);
		}

		public static class Builder {

			private Annotations annotations;

			private final String text;

			private Map<String, Object> meta;

			private Builder(String text) {
				Assert.notNull(text, "text must not be null");
				this.text = text;
			}

			public Builder annotations(Annotations annotations) {
				this.annotations = annotations;
				return this;
			}

			public Builder meta(Map<String, Object> meta) {
				this.meta = meta;
				return this;
			}

			public TextContent build() {
				return new TextContent(annotations, text, meta);
			}

		}

		public Annotations annotations() {
			return this.annotations;
		}

		public String text() {
			return this.text;
		}

		public Map<String, Object> meta() {
			return this.meta;
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) {
				return true;
			}
			if (!(o instanceof TextContent)) {
				return false;
			}
			TextContent that = (TextContent) o;
			return Objects.equals(this.annotations, that.annotations) && Objects.equals(this.text, that.text)
					&& Objects.equals(this.meta, that.meta);
		}

		@Override
		public int hashCode() {
			return Objects.hash(this.annotations, this.text, this.meta);
		}

		@Override
		public String toString() {
			return "TextContent[annotations=" + this.annotations + ", text=" + this.text + ", meta=" + this.meta + "]";
		}
	}

	/**
	 * An image provided to or from an LLM.
	 *
	 * @param annotations Optional annotations for the client
	 * @param data The base64-encoded image data
	 * @param mimeType The MIME type of the image. Different providers may support
	 * different image types
	 * @param meta See specification for notes on _meta usage
	 */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static final class ImageContent implements Annotated, Content {

		@JsonProperty("annotations")
		private final Annotations annotations;

		@JsonProperty("data")
		private final String data;

		@JsonProperty("mimeType")
		private final String mimeType;

		@JsonProperty("_meta")
		private final Map<String, Object> meta;

		public ImageContent(Annotations annotations, String data, String mimeType, Map<String, Object> meta) {
			Assert.notNull(data, "data must not be null");
			Assert.notNull(mimeType, "mimeType must not be null");
			this.annotations = annotations;
			this.data = data;
			this.mimeType = mimeType;
			this.meta = meta;
		}

		@JsonCreator
		static ImageContent fromJson(@JsonProperty("annotations") Annotations annotations,
				@JsonProperty("data") String data, @JsonProperty("mimeType") String mimeType,
				@JsonProperty("_meta") Map<String, Object> meta) {
			if (data == null || mimeType == null) {
				List<String> missing = new ArrayList<>();
				if (data == null) {
					missing.add("data -> ''");
					data = "";
				}
				if (mimeType == null) {
					missing.add("mimeType -> ''");
					mimeType = "";
				}
				logger.warn("ImageContent: missing required fields during deserialization: {}",
						String.join(", ", missing));
			}
			return new ImageContent(annotations, data, mimeType, meta);
		}

		@Deprecated
		public ImageContent(Annotations annotations, String data, String mimeType) {
			this(annotations, data, mimeType, null);
		}

		public static Builder builder(String data, String mimeType) {
			return new Builder(data, mimeType);
		}

		public static class Builder {

			private Annotations annotations;

			private final String data;

			private final String mimeType;

			private Map<String, Object> meta;

			private Builder(String data, String mimeType) {
				Assert.notNull(data, "data must not be null");
				Assert.notNull(mimeType, "mimeType must not be null");
				this.data = data;
				this.mimeType = mimeType;
			}

			public Builder annotations(Annotations annotations) {
				this.annotations = annotations;
				return this;
			}

			public Builder meta(Map<String, Object> meta) {
				this.meta = meta;
				return this;
			}

			public ImageContent build() {
				return new ImageContent(annotations, data, mimeType, meta);
			}

		}

		public Annotations annotations() {
			return this.annotations;
		}

		public String data() {
			return this.data;
		}

		public String mimeType() {
			return this.mimeType;
		}

		public Map<String, Object> meta() {
			return this.meta;
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) {
				return true;
			}
			if (!(o instanceof ImageContent)) {
				return false;
			}
			ImageContent that = (ImageContent) o;
			return Objects.equals(this.annotations, that.annotations) && Objects.equals(this.data, that.data)
					&& Objects.equals(this.mimeType, that.mimeType) && Objects.equals(this.meta, that.meta);
		}

		@Override
		public int hashCode() {
			return Objects.hash(this.annotations, this.data, this.mimeType, this.meta);
		}

		@Override
		public String toString() {
			return "ImageContent[annotations=" + this.annotations + ", data=" + this.data + ", mimeType="
					+ this.mimeType + ", meta=" + this.meta + "]";
		}
	}

	/**
	 * Audio provided to or from an LLM.
	 *
	 * @param annotations Optional annotations for the client
	 * @param data The base64-encoded audio data
	 * @param mimeType The MIME type of the audio. Different providers may support
	 * different audio types
	 * @param meta See specification for notes on _meta usage
	 */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static final class AudioContent implements Annotated, Content {

		@JsonProperty("annotations")
		private final Annotations annotations;

		@JsonProperty("data")
		private final String data;

		@JsonProperty("mimeType")
		private final String mimeType;

		@JsonProperty("_meta")
		private final Map<String, Object> meta;

		public AudioContent(Annotations annotations, String data, String mimeType, Map<String, Object> meta) {
			Assert.notNull(data, "data must not be null");
			Assert.notNull(mimeType, "mimeType must not be null");
			this.annotations = annotations;
			this.data = data;
			this.mimeType = mimeType;
			this.meta = meta;
		}

		@JsonCreator
		static AudioContent fromJson(@JsonProperty("annotations") Annotations annotations,
				@JsonProperty("data") String data, @JsonProperty("mimeType") String mimeType,
				@JsonProperty("_meta") Map<String, Object> meta) {
			if (data == null || mimeType == null) {
				List<String> missing = new ArrayList<>();
				if (data == null) {
					missing.add("data -> ''");
					data = "";
				}
				if (mimeType == null) {
					missing.add("mimeType -> ''");
					mimeType = "";
				}
				logger.warn("AudioContent: missing required fields during deserialization: {}",
						String.join(", ", missing));
			}
			return new AudioContent(annotations, data, mimeType, meta);
		}

		// backwards compatibility constructor
		@Deprecated
		public AudioContent(Annotations annotations, String data, String mimeType) {
			this(annotations, data, mimeType, null);
		}

		public static Builder builder(String data, String mimeType) {
			return new Builder(data, mimeType);
		}

		public static class Builder {

			private Annotations annotations;

			private final String data;

			private final String mimeType;

			private Map<String, Object> meta;

			private Builder(String data, String mimeType) {
				Assert.notNull(data, "data must not be null");
				Assert.notNull(mimeType, "mimeType must not be null");
				this.data = data;
				this.mimeType = mimeType;
			}

			public Builder annotations(Annotations annotations) {
				this.annotations = annotations;
				return this;
			}

			public Builder meta(Map<String, Object> meta) {
				this.meta = meta;
				return this;
			}

			public AudioContent build() {
				return new AudioContent(annotations, data, mimeType, meta);
			}

		}

		public Annotations annotations() {
			return this.annotations;
		}

		public String data() {
			return this.data;
		}

		public String mimeType() {
			return this.mimeType;
		}

		public Map<String, Object> meta() {
			return this.meta;
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) {
				return true;
			}
			if (!(o instanceof AudioContent)) {
				return false;
			}
			AudioContent that = (AudioContent) o;
			return Objects.equals(this.annotations, that.annotations) && Objects.equals(this.data, that.data)
					&& Objects.equals(this.mimeType, that.mimeType) && Objects.equals(this.meta, that.meta);
		}

		@Override
		public int hashCode() {
			return Objects.hash(this.annotations, this.data, this.mimeType, this.meta);
		}

		@Override
		public String toString() {
			return "AudioContent[annotations=" + this.annotations + ", data=" + this.data + ", mimeType="
					+ this.mimeType + ", meta=" + this.meta + "]";
		}
	}

	/**
	 * The contents of a resource, embedded into a prompt or tool call result.
	 *
	 * It is up to the client how best to render embedded resources for the benefit of the
	 * LLM and/or the user.
	 *
	 * @param annotations Optional annotations for the client
	 * @param resource The resource contents that are embedded
	 * @param meta See specification for notes on _meta usage
	 */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static final class EmbeddedResource implements Annotated, Content {

		@JsonProperty("annotations")
		private final Annotations annotations;

		@JsonProperty("resource")
		private final ResourceContents resource;

		@JsonProperty("_meta")
		private final Map<String, Object> meta;

		public EmbeddedResource(Annotations annotations, ResourceContents resource, Map<String, Object> meta) {
			Assert.notNull(resource, "resource must not be null");
			this.annotations = annotations;
			this.resource = resource;
			this.meta = meta;
		}

		@JsonCreator
		static EmbeddedResource fromJson(@JsonProperty("annotations") Annotations annotations,
				@JsonProperty("resource") ResourceContents resource, @JsonProperty("_meta") Map<String, Object> meta) {
			if (resource == null) {
				logger.warn(
						"EmbeddedResource: missing required field 'resource' during deserialization, using empty text resource");
				resource = new TextResourceContents("", null, "", null);
			}
			return new EmbeddedResource(annotations, resource, meta);
		}

		// backwards compatibility constructor
		@Deprecated
		public EmbeddedResource(Annotations annotations, ResourceContents resource) {
			this(annotations, resource, null);
		}

		public static Builder builder(ResourceContents resource) {
			return new Builder(resource);
		}

		public static class Builder {

			private Annotations annotations;

			private final ResourceContents resource;

			private Map<String, Object> meta;

			private Builder(ResourceContents resource) {
				Assert.notNull(resource, "resource must not be null");
				this.resource = resource;
			}

			public Builder annotations(Annotations annotations) {
				this.annotations = annotations;
				return this;
			}

			public Builder meta(Map<String, Object> meta) {
				this.meta = meta;
				return this;
			}

			public EmbeddedResource build() {
				return new EmbeddedResource(annotations, resource, meta);
			}

		}

		public Annotations annotations() {
			return this.annotations;
		}

		public ResourceContents resource() {
			return this.resource;
		}

		public Map<String, Object> meta() {
			return this.meta;
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) {
				return true;
			}
			if (!(o instanceof EmbeddedResource)) {
				return false;
			}
			EmbeddedResource that = (EmbeddedResource) o;
			return Objects.equals(this.annotations, that.annotations) && Objects.equals(this.resource, that.resource)
					&& Objects.equals(this.meta, that.meta);
		}

		@Override
		public int hashCode() {
			return Objects.hash(this.annotations, this.resource, this.meta);
		}

		@Override
		public String toString() {
			return "EmbeddedResource[annotations=" + this.annotations + ", resource=" + this.resource + ", meta="
					+ this.meta + "]";
		}
	}

	/**
	 * A known resource that the server is capable of reading.
	 *
	 * @param uri the URI of the resource.
	 * @param name A human-readable name for this resource. This can be used by clients to
	 * populate UI elements.
	 * @param title A human-readable title for this resource.
	 * @param description A description of what this resource represents. This can be used
	 * by clients to improve the LLM's understanding of available resources. It can be
	 * thought of like a "hint" to the model.
	 * @param mimeType The MIME type of this resource, if known.
	 * @param size The size of the raw resource content, in bytes (i.e., before base64
	 * encoding or any tokenization), if known. This can be used by Hosts to display file
	 * sizes and estimate context window usage.
	 * @param annotations Optional annotations for the client. The client can use
	 * annotations to inform how objects are used or displayed.
	 * @param meta See specification for notes on _meta usage
	 */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static final class ResourceLink implements Content, ResourceContent {

		@JsonProperty("name")
		private final String name;

		@JsonProperty("title")
		private final String title;

		@JsonProperty("uri")
		private final String uri;

		@JsonProperty("description")
		private final String description;

		@JsonProperty("mimeType")
		private final String mimeType;

		@JsonProperty("size")
		private final Long size;

		@JsonProperty("annotations")
		private final Annotations annotations;

		@JsonProperty("_meta")
		private final Map<String, Object> meta;

		public ResourceLink(String name, String title, String uri, String description, String mimeType, Long size,
				Annotations annotations, Map<String, Object> meta) {
			this.name = name;
			this.title = title;
			this.uri = uri;
			this.description = description;
			this.mimeType = mimeType;
			this.size = size;
			this.annotations = annotations;
			this.meta = meta;
		}

		public static Builder builder() {
			return new Builder();
		}

		public static class Builder {

			private String name;

			private String title;

			private String uri;

			private String description;

			private String mimeType;

			private Annotations annotations;

			private Long size;

			private Map<String, Object> meta;

			public Builder name(String name) {
				this.name = name;
				return this;
			}

			public Builder title(String title) {
				this.title = title;
				return this;
			}

			public Builder uri(String uri) {
				this.uri = uri;
				return this;
			}

			public Builder description(String description) {
				this.description = description;
				return this;
			}

			public Builder mimeType(String mimeType) {
				this.mimeType = mimeType;
				return this;
			}

			public Builder annotations(Annotations annotations) {
				this.annotations = annotations;
				return this;
			}

			public Builder size(Long size) {
				this.size = size;
				return this;
			}

			public Builder meta(Map<String, Object> meta) {
				this.meta = meta;
				return this;
			}

			public ResourceLink build() {
				Assert.hasText(uri, "uri must not be empty");
				Assert.hasText(name, "name must not be empty");

				return new ResourceLink(name, title, uri, description, mimeType, size, annotations, meta);
			}

		}

		public String name() {
			return this.name;
		}

		public String title() {
			return this.title;
		}

		public String uri() {
			return this.uri;
		}

		public String description() {
			return this.description;
		}

		public String mimeType() {
			return this.mimeType;
		}

		public Long size() {
			return this.size;
		}

		public Annotations annotations() {
			return this.annotations;
		}

		public Map<String, Object> meta() {
			return this.meta;
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) {
				return true;
			}
			if (!(o instanceof ResourceLink)) {
				return false;
			}
			ResourceLink that = (ResourceLink) o;
			return Objects.equals(this.name, that.name) && Objects.equals(this.title, that.title)
					&& Objects.equals(this.uri, that.uri) && Objects.equals(this.description, that.description)
					&& Objects.equals(this.mimeType, that.mimeType) && Objects.equals(this.size, that.size)
					&& Objects.equals(this.annotations, that.annotations) && Objects.equals(this.meta, that.meta);
		}

		@Override
		public int hashCode() {
			return Objects.hash(this.name, this.title, this.uri, this.description, this.mimeType, this.size,
					this.annotations, this.meta);
		}

		@Override
		public String toString() {
			return "ResourceLink[name=" + this.name + ", title=" + this.title + ", uri=" + this.uri
					+ ", description=" + this.description + ", mimeType=" + this.mimeType + ", size=" + this.size
					+ ", annotations=" + this.annotations + ", meta=" + this.meta + "]";
		}
	}

	// ---------------------------
	// Roots
	// ---------------------------
	/**
	 * Represents a root directory or file that the server can operate on.
	 *
	 * @param uri The URI identifying the root. This *must* start with file:// for now.
	 * This restriction may be relaxed in future versions of the protocol to allow other
	 * URI schemes.
	 * @param name An optional name for the root. This can be used to provide a
	 * human-readable identifier for the root, which may be useful for display purposes or
	 * for referencing the root in other parts of the application.
	 * @param meta See specification for notes on _meta usage
	 */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static final class Root {

		@JsonProperty("uri")
		private final String uri;

		@JsonProperty("name")
		private final String name;

		@JsonProperty("_meta")
		private final Map<String, Object> meta;

		public Root(String uri, String name, Map<String, Object> meta) {
			Assert.notNull(uri, "uri must not be null");
			this.uri = uri;
			this.name = name;
			this.meta = meta;
		}

		@JsonCreator
		static Root fromJson(@JsonProperty("uri") String uri, @JsonProperty("name") String name,
				@JsonProperty("_meta") Map<String, Object> meta) {
			if (uri == null) {
				logger.warn("Root: missing required field 'uri' during deserialization, using default ''");
				uri = "";
			}
			return new Root(uri, name, meta);
		}

		public Root(String uri, String name) {
			this(uri, name, null);
		}

		public static Builder builder(String uri) {
			return new Builder(uri);
		}

		public static class Builder {

			private final String uri;

			private String name;

			private Map<String, Object> meta;

			private Builder(String uri) {
				Assert.hasText(uri, "uri must not be empty");
				this.uri = uri;
			}

			public Builder name(String name) {
				this.name = name;
				return this;
			}

			public Builder meta(Map<String, Object> meta) {
				this.meta = meta;
				return this;
			}

			public Root build() {
				return new Root(uri, name, meta);
			}

		}

		public String uri() {
			return this.uri;
		}

		public String name() {
			return this.name;
		}

		public Map<String, Object> meta() {
			return this.meta;
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) {
				return true;
			}
			if (!(o instanceof Root)) {
				return false;
			}
			Root that = (Root) o;
			return Objects.equals(this.uri, that.uri) && Objects.equals(this.name, that.name)
					&& Objects.equals(this.meta, that.meta);
		}

		@Override
		public int hashCode() {
			return Objects.hash(this.uri, this.name, this.meta);
		}

		@Override
		public String toString() {
			return "Root[uri=" + this.uri + ", name=" + this.name + ", meta=" + this.meta + "]";
		}
	}

	/**
	 * The client's response to a roots/list request from the server. This result contains
	 * an array of Root objects, each representing a root directory or file that the
	 * server can operate on.
	 *
	 * @param roots An array of Root objects, each representing a root directory or file
	 * that the server can operate on.
	 * @param nextCursor An optional cursor for pagination. If present, indicates there
	 * are more roots available. The client can use this cursor to request the next page
	 * of results by sending a roots/list request with the cursor parameter set to this
	 * @param meta See specification for notes on _meta usage
	 */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static final class ListRootsResult implements Result {

		@JsonProperty("roots")
		private final List<Root> roots;

		@JsonProperty("nextCursor")
		private final String nextCursor;

		@JsonProperty("_meta")
		private final Map<String, Object> meta;

		public ListRootsResult(List<Root> roots, String nextCursor, Map<String, Object> meta) {
			Assert.notNull(roots, "roots must not be null");
			this.roots = roots;
			this.nextCursor = nextCursor;
			this.meta = meta;
		}

		@JsonCreator
		static ListRootsResult fromJson(@JsonProperty("roots") List<Root> roots,
				@JsonProperty("nextCursor") String nextCursor, @JsonProperty("_meta") Map<String, Object> meta) {
			if (roots == null) {
				logger.warn("ListRootsResult: missing required field 'roots' during deserialization, using default []");
				roots = Collections.emptyList();
			}
			return new ListRootsResult(roots, nextCursor, meta);
		}

		@Deprecated
		public ListRootsResult(List<Root> roots) {
			this(roots, null, null);
		}

		@Deprecated
		public ListRootsResult(List<Root> roots, String nextCursor) {
			this(roots, nextCursor, null);
		}

		public static Builder builder(List<Root> roots) {
			return new Builder(roots);
		}

		public static class Builder {

			private final List<Root> roots;

			private String nextCursor;

			private Map<String, Object> meta;

			private Builder(List<Root> roots) {
				Assert.notNull(roots, "roots must not be null");
				this.roots = roots;
			}

			public Builder nextCursor(String nextCursor) {
				this.nextCursor = nextCursor;
				return this;
			}

			public Builder meta(Map<String, Object> meta) {
				this.meta = meta;
				return this;
			}

			public ListRootsResult build() {
				return new ListRootsResult(roots, nextCursor, meta);
			}

		}

		public List<Root> roots() {
			return this.roots;
		}

		public String nextCursor() {
			return this.nextCursor;
		}

		public Map<String, Object> meta() {
			return this.meta;
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) {
				return true;
			}
			if (!(o instanceof ListRootsResult)) {
				return false;
			}
			ListRootsResult that = (ListRootsResult) o;
			return Objects.equals(this.roots, that.roots) && Objects.equals(this.nextCursor, that.nextCursor)
					&& Objects.equals(this.meta, that.meta);
		}

		@Override
		public int hashCode() {
			return Objects.hash(this.roots, this.nextCursor, this.meta);
		}

		@Override
		public String toString() {
			return "ListRootsResult[roots=" + this.roots + ", nextCursor=" + this.nextCursor + ", meta=" + this.meta
					+ "]";
		}
	}

}
