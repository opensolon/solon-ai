/*
 * Copyright 2024-2024 the original author or authors.
 */

package io.modelcontextprotocol.spec;

import com.fasterxml.jackson.annotation.*;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.util.Assert;
import lombok.Builder;
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
 */
public final class McpSchema {

	private static final Logger logger = LoggerFactory.getLogger(McpSchema.class);

	private McpSchema() {
	}

	public static final String JSONRPC_VERSION = "2.0";

	public static final String FIRST_PAGE = null;

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
	// @JsonFormat(with = JsonFormat.Feature.ACCEPT_SINGLE_VALUE_AS_ARRAY)
	public static final class JSONRPCRequest implements JSONRPCMessage {
		@JsonProperty("jsonrpc")
		private final String jsonrpc;
		@JsonProperty("method")
		private final String method;
		@JsonProperty("id")
		private final Object id;
		@JsonProperty("params")
		private final Object params;

		public JSONRPCRequest(@JsonProperty("jsonrpc") String jsonrpc, @JsonProperty("method") String method, @JsonProperty("id") Object id, @JsonProperty("params") Object params) {
			this.jsonrpc = jsonrpc;
			this.method = method;
			this.id = id;
			this.params = params;
			Assert.notNull(id, "MCP requests MUST include an ID - null IDs are not allowed");
			Assert.isTrue(id instanceof String || id instanceof Integer || id instanceof Long,
			"MCP requests MUST have an ID that is either a string or integer");
		}

		public String jsonrpc() { return this.jsonrpc; }

		public String method() { return this.method; }

		public Object id() { return this.id; }

		public Object params() { return this.params; }

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (!(o instanceof JSONRPCRequest)) return false;
			JSONRPCRequest that = (JSONRPCRequest) o;
			return Objects.equals(jsonrpc, that.jsonrpc()) && Objects.equals(method, that.method()) && Objects.equals(id, that.id()) && Objects.equals(params, that.params());
		}

		@Override
		public int hashCode() { return Objects.hash(jsonrpc, method, id, params); }

		@Override
		public String toString() {
			return "JSONRPCRequest[jsonrpc=" + jsonrpc + ", method=" + method + ", id=" + id + ", params=" + params + "]";
		}

// @formatter:on

		/**
		 * Constructor that validates MCP-specific ID requirements. Unlike base JSON-RPC,
		 * MCP requires that: (1) Requests MUST include a string or integer ID; (2) The ID
		 * MUST NOT be null
		 */
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
	// TODO: batching support
	// @JsonFormat(with = JsonFormat.Feature.ACCEPT_SINGLE_VALUE_AS_ARRAY)
	public static final class JSONRPCNotification implements JSONRPCMessage {
		@JsonProperty("jsonrpc")
		private final String jsonrpc;
		@JsonProperty("method")
		private final String method;
		@JsonProperty("params")
		private final Object params;

		public JSONRPCNotification(@JsonProperty("jsonrpc") String jsonrpc, @JsonProperty("method") String method, @JsonProperty("params") Object params) {
			this.jsonrpc = jsonrpc;
			this.method = method;
			this.params = params;
		}

		public String jsonrpc() { return this.jsonrpc; }

		public String method() { return this.method; }

		public Object params() { return this.params; }

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (!(o instanceof JSONRPCNotification)) return false;
			JSONRPCNotification that = (JSONRPCNotification) o;
			return Objects.equals(jsonrpc, that.jsonrpc()) && Objects.equals(method, that.method()) && Objects.equals(params, that.params());
		}

		@Override
		public int hashCode() { return Objects.hash(jsonrpc, method, params); }

		@Override
		public String toString() {
			return "JSONRPCNotification[jsonrpc=" + jsonrpc + ", method=" + method + ", params=" + params + "]";
		}

// @formatter:on
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
	// TODO: batching support
	// @JsonFormat(with = JsonFormat.Feature.ACCEPT_SINGLE_VALUE_AS_ARRAY)
	public static final class JSONRPCResponse implements JSONRPCMessage {
		@JsonProperty("jsonrpc")
		private final String jsonrpc;
		@JsonProperty("id")
		private final Object id;
		@JsonProperty("result")
		private final Object result;
		@JsonProperty("error")
		private final JSONRPCError error;

		public JSONRPCResponse(@JsonProperty("jsonrpc") String jsonrpc, @JsonProperty("id") Object id, @JsonProperty("result") Object result, @JsonProperty("error") JSONRPCError error) {
			this.jsonrpc = jsonrpc;
			this.id = id;
			this.result = result;
			this.error = error;
		}

		public String jsonrpc() { return this.jsonrpc; }

		public Object id() { return this.id; }

		public Object result() { return this.result; }

		public JSONRPCError error() { return this.error; }

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (!(o instanceof JSONRPCResponse)) return false;
			JSONRPCResponse that = (JSONRPCResponse) o;
			return Objects.equals(jsonrpc, that.jsonrpc()) && Objects.equals(id, that.id()) && Objects.equals(result, that.result()) && Objects.equals(error, that.error());
		}

		@Override
		public int hashCode() { return Objects.hash(jsonrpc, id, result, error); }

		@Override
		public String toString() {
			return "JSONRPCResponse[jsonrpc=" + jsonrpc + ", id=" + id + ", result=" + result + ", error=" + error + "]";
		}

// @formatter:on

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

		public JSONRPCError(@JsonProperty("code") Integer code, @JsonProperty("message") String message, @JsonProperty("data") Object data) {
			this.code = code;
			this.message = message;
			this.data = data;
		}

		public Integer code() { return this.code; }

		public String message() { return this.message; }

		public Object data() { return this.data; }

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (!(o instanceof JSONRPCError)) return false;
			JSONRPCError that = (JSONRPCError) o;
			return Objects.equals(code, that.code()) && Objects.equals(message, that.message()) && Objects.equals(data, that.data());
		}

		@Override
		public int hashCode() { return Objects.hash(code, message, data); }

		@Override
		public String toString() {
			return "JSONRPCError[code=" + code + ", message=" + message + ", data=" + data + "]";
		}

// @formatter:on
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

		public InitializeRequest(@JsonProperty("protocolVersion") String protocolVersion, @JsonProperty("capabilities") ClientCapabilities capabilities, @JsonProperty("clientInfo") Implementation clientInfo, @JsonProperty("_meta") Map<String, Object> meta) {
			this.protocolVersion = protocolVersion;
			this.capabilities = capabilities;
			this.clientInfo = clientInfo;
			this.meta = meta;
		}

		public String protocolVersion() { return this.protocolVersion; }

		public ClientCapabilities capabilities() { return this.capabilities; }

		public Implementation clientInfo() { return this.clientInfo; }

		public Map<String, Object> meta() { return this.meta; }

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (!(o instanceof InitializeRequest)) return false;
			InitializeRequest that = (InitializeRequest) o;
			return Objects.equals(protocolVersion, that.protocolVersion()) && Objects.equals(capabilities, that.capabilities()) && Objects.equals(clientInfo, that.clientInfo()) && Objects.equals(meta, that.meta());
		}

		@Override
		public int hashCode() { return Objects.hash(protocolVersion, capabilities, clientInfo, meta); }

		@Override
		public String toString() {
			return "InitializeRequest[protocolVersion=" + protocolVersion + ", capabilities=" + capabilities + ", clientInfo=" + clientInfo + ", meta=" + meta + "]";
		}

// @formatter:on

		public InitializeRequest(String protocolVersion, ClientCapabilities capabilities, Implementation clientInfo) {
			this(protocolVersion, capabilities, clientInfo, null);
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

		public InitializeResult(@JsonProperty("protocolVersion") String protocolVersion, @JsonProperty("capabilities") ServerCapabilities capabilities, @JsonProperty("serverInfo") Implementation serverInfo, @JsonProperty("instructions") String instructions, @JsonProperty("_meta") Map<String, Object> meta) {
			this.protocolVersion = protocolVersion;
			this.capabilities = capabilities;
			this.serverInfo = serverInfo;
			this.instructions = instructions;
			this.meta = meta;
		}

		public String protocolVersion() { return this.protocolVersion; }

		public ServerCapabilities capabilities() { return this.capabilities; }

		public Implementation serverInfo() { return this.serverInfo; }

		public String instructions() { return this.instructions; }

		public Map<String, Object> meta() { return this.meta; }

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (!(o instanceof InitializeResult)) return false;
			InitializeResult that = (InitializeResult) o;
			return Objects.equals(protocolVersion, that.protocolVersion()) && Objects.equals(capabilities, that.capabilities()) && Objects.equals(serverInfo, that.serverInfo()) && Objects.equals(instructions, that.instructions()) && Objects.equals(meta, that.meta());
		}

		@Override
		public int hashCode() { return Objects.hash(protocolVersion, capabilities, serverInfo, instructions, meta); }

		@Override
		public String toString() {
			return "InitializeResult[protocolVersion=" + protocolVersion + ", capabilities=" + capabilities + ", serverInfo=" + serverInfo + ", instructions=" + instructions + ", meta=" + meta + "]";
		}

// @formatter:on

		public InitializeResult(String protocolVersion, ServerCapabilities capabilities, Implementation serverInfo,
				String instructions) {
			this(protocolVersion, capabilities, serverInfo, instructions, null);
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

		public ClientCapabilities(@JsonProperty("experimental") Map<String, Object> experimental, @JsonProperty("roots") RootCapabilities roots, @JsonProperty("sampling") Sampling sampling, @JsonProperty("elicitation") Elicitation elicitation) {
			this.experimental = experimental;
			this.roots = roots;
			this.sampling = sampling;
			this.elicitation = elicitation;
		}

		public Map<String, Object> experimental() { return this.experimental; }

		public RootCapabilities roots() { return this.roots; }

		public Sampling sampling() { return this.sampling; }

		public Elicitation elicitation() { return this.elicitation; }

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (!(o instanceof ClientCapabilities)) return false;
			ClientCapabilities that = (ClientCapabilities) o;
			return Objects.equals(experimental, that.experimental()) && Objects.equals(roots, that.roots()) && Objects.equals(sampling, that.sampling()) && Objects.equals(elicitation, that.elicitation());
		}

		@Override
		public int hashCode() { return Objects.hash(experimental, roots, sampling, elicitation); }

		@Override
		public String toString() {
			return "ClientCapabilities[experimental=" + experimental + ", roots=" + roots + ", sampling=" + sampling + ", elicitation=" + elicitation + "]";
		}

// @formatter:on

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

		public RootCapabilities(@JsonProperty("listChanged") Boolean listChanged) {
			this.listChanged = listChanged;
		}

		public Boolean listChanged() { return this.listChanged; }

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (!(o instanceof RootCapabilities)) return false;
			RootCapabilities that = (RootCapabilities) o;
			return Objects.equals(listChanged, that.listChanged());
		}

		@Override
		public int hashCode() { return Objects.hash(listChanged); }

		@Override
		public String toString() {
			return "RootCapabilities[listChanged=" + listChanged + "]";
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
	public static final class Sampling {

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (!(o instanceof Sampling)) return false;
			return true;
		}

		@Override
		public int hashCode() { return 1; }

		@Override
		public String toString() { return "Sampling[]"; }
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
	public static final class Elicitation {
		@JsonProperty("form")
		private final Form form;
		@JsonProperty("url")
		private final Url url;

		public Elicitation(@JsonProperty("form") Form form, @JsonProperty("url") Url url) {
			this.form = form;
			this.url = url;
		}

		public Form form() { return this.form; }

		public Url url() { return this.url; }

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (!(o instanceof Elicitation)) return false;
			Elicitation that = (Elicitation) o;
			return Objects.equals(form, that.form()) && Objects.equals(url, that.url());
		}

		@Override
		public int hashCode() { return Objects.hash(form, url); }

		@Override
		public String toString() {
			return "Elicitation[form=" + form + ", url=" + url + "]";
		}

/**
			 * Marker record indicating support for form-based elicitation mode.
			 */
			@JsonInclude(JsonInclude.Include.NON_ABSENT)
	public static final class Form {

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (!(o instanceof Form)) return false;
			return true;
		}

		@Override
		public int hashCode() { return 1; }

		@Override
		public String toString() { return "Form[]"; }
	}

			/**
			 * Marker record indicating support for URL-based elicitation mode.
			 */
			@JsonInclude(JsonInclude.Include.NON_ABSENT)
	public static final class Url {

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (!(o instanceof Url)) return false;
			return true;
		}

		@Override
		public int hashCode() { return 1; }

		@Override
		public String toString() { return "Url[]"; }
	}

			/**
			 * Creates an Elicitation with default settings (backward compatible, produces
			 * empty JSON object).
			 */
			public Elicitation() {
				this(null, null);
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
				this.elicitation = new Elicitation();
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

			public ClientCapabilities build() {
				return new ClientCapabilities(experimental, roots, sampling, elicitation);
			}

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

		public ServerCapabilities(@JsonProperty("completions") CompletionCapabilities completions, @JsonProperty("experimental") Map<String, Object> experimental, @JsonProperty("logging") LoggingCapabilities logging, @JsonProperty("prompts") PromptCapabilities prompts, @JsonProperty("resources") ResourceCapabilities resources, @JsonProperty("tools") ToolCapabilities tools) {
			this.completions = completions;
			this.experimental = experimental;
			this.logging = logging;
			this.prompts = prompts;
			this.resources = resources;
			this.tools = tools;
		}

		public CompletionCapabilities completions() { return this.completions; }

		public Map<String, Object> experimental() { return this.experimental; }

		public LoggingCapabilities logging() { return this.logging; }

		public PromptCapabilities prompts() { return this.prompts; }

		public ResourceCapabilities resources() { return this.resources; }

		public ToolCapabilities tools() { return this.tools; }

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (!(o instanceof ServerCapabilities)) return false;
			ServerCapabilities that = (ServerCapabilities) o;
			return Objects.equals(completions, that.completions()) && Objects.equals(experimental, that.experimental()) && Objects.equals(logging, that.logging()) && Objects.equals(prompts, that.prompts()) && Objects.equals(resources, that.resources()) && Objects.equals(tools, that.tools());
		}

		@Override
		public int hashCode() { return Objects.hash(completions, experimental, logging, prompts, resources, tools); }

		@Override
		public String toString() {
			return "ServerCapabilities[completions=" + completions + ", experimental=" + experimental + ", logging=" + logging + ", prompts=" + prompts + ", resources=" + resources + ", tools=" + tools + "]";
		}

// @formatter:on

	/**
	 * Present if the server supports argument autocompletion suggestions.
	 */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static final class CompletionCapabilities {

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (!(o instanceof CompletionCapabilities)) return false;
			return true;
		}

		@Override
		public int hashCode() { return 1; }

		@Override
		public String toString() { return "CompletionCapabilities[]"; }
	}

	/**
	 * Present if the server supports sending log messages to the client.
	 */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static final class LoggingCapabilities {

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (!(o instanceof LoggingCapabilities)) return false;
			return true;
		}

		@Override
		public int hashCode() { return 1; }

		@Override
		public String toString() { return "LoggingCapabilities[]"; }
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

		public PromptCapabilities(@JsonProperty("listChanged") Boolean listChanged) {
			this.listChanged = listChanged;
		}

		public Boolean listChanged() { return this.listChanged; }

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (!(o instanceof PromptCapabilities)) return false;
			PromptCapabilities that = (PromptCapabilities) o;
			return Objects.equals(listChanged, that.listChanged());
		}

		@Override
		public int hashCode() { return Objects.hash(listChanged); }

		@Override
		public String toString() {
			return "PromptCapabilities[listChanged=" + listChanged + "]";
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

		public ResourceCapabilities(@JsonProperty("subscribe") Boolean subscribe, @JsonProperty("listChanged") Boolean listChanged) {
			this.subscribe = subscribe;
			this.listChanged = listChanged;
		}

		public Boolean subscribe() { return this.subscribe; }

		public Boolean listChanged() { return this.listChanged; }

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (!(o instanceof ResourceCapabilities)) return false;
			ResourceCapabilities that = (ResourceCapabilities) o;
			return Objects.equals(subscribe, that.subscribe()) && Objects.equals(listChanged, that.listChanged());
		}

		@Override
		public int hashCode() { return Objects.hash(subscribe, listChanged); }

		@Override
		public String toString() {
			return "ResourceCapabilities[subscribe=" + subscribe + ", listChanged=" + listChanged + "]";
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

		public ToolCapabilities(@JsonProperty("listChanged") Boolean listChanged) {
			this.listChanged = listChanged;
		}

		public Boolean listChanged() { return this.listChanged; }

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (!(o instanceof ToolCapabilities)) return false;
			ToolCapabilities that = (ToolCapabilities) o;
			return Objects.equals(listChanged, that.listChanged());
		}

		@Override
		public int hashCode() { return Objects.hash(listChanged); }

		@Override
		public String toString() {
			return "ToolCapabilities[listChanged=" + listChanged + "]";
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

			public ServerCapabilities build() {
				return new ServerCapabilities(completions, experimental, logging, prompts, resources, tools);
			}

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

		public Implementation(@JsonProperty("name") String name, @JsonProperty("title") String title, @JsonProperty("version") String version) {
			this.name = name;
			this.title = title;
			this.version = version;
		}

		public String name() { return this.name; }

		public String title() { return this.title; }

		public String version() { return this.version; }

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (!(o instanceof Implementation)) return false;
			Implementation that = (Implementation) o;
			return Objects.equals(name, that.name()) && Objects.equals(title, that.title()) && Objects.equals(version, that.version());
		}

		@Override
		public int hashCode() { return Objects.hash(name, title, version); }

		@Override
		public String toString() {
			return "Implementation[name=" + name + ", title=" + title + ", version=" + version + "]";
		}

// @formatter:on			

		public Implementation(String name, String version) {
			this(name, null, version);
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

		public Annotations(@JsonProperty("audience") List<Role> audience, @JsonProperty("priority") Double priority, @JsonProperty("lastModified") String lastModified) {
			this.audience = audience;
			this.priority = priority;
			this.lastModified = lastModified;
		}

		public List<Role> audience() { return this.audience; }

		public Double priority() { return this.priority; }

		public String lastModified() { return this.lastModified; }

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (!(o instanceof Annotations)) return false;
			Annotations that = (Annotations) o;
			return Objects.equals(audience, that.audience()) && Objects.equals(priority, that.priority()) && Objects.equals(lastModified, that.lastModified());
		}

		@Override
		public int hashCode() { return Objects.hash(audience, priority, lastModified); }

		@Override
		public String toString() {
			return "Annotations[audience=" + audience + ", priority=" + priority + ", lastModified=" + lastModified + "]";
		}

// @formatter:on

		public Annotations(List<Role> audience, Double priority) {
			this(audience, priority, null);
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

		public Resource(@JsonProperty("uri") String uri, @JsonProperty("name") String name, @JsonProperty("title") String title, @JsonProperty("description") String description, @JsonProperty("mimeType") String mimeType, @JsonProperty("size") Long size, @JsonProperty("annotations") Annotations annotations, @JsonProperty("_meta") Map<String, Object> meta) {
			this.uri = uri;
			this.name = name;
			this.title = title;
			this.description = description;
			this.mimeType = mimeType;
			this.size = size;
			this.annotations = annotations;
			this.meta = meta;
		}

		public String uri() { return this.uri; }

		public String name() { return this.name; }

		public String title() { return this.title; }

		public String description() { return this.description; }

		public String mimeType() { return this.mimeType; }

		public Long size() { return this.size; }

		public Annotations annotations() { return this.annotations; }

		public Map<String, Object> meta() { return this.meta; }

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (!(o instanceof Resource)) return false;
			Resource that = (Resource) o;
			return Objects.equals(uri, that.uri()) && Objects.equals(name, that.name()) && Objects.equals(title, that.title()) && Objects.equals(description, that.description()) && Objects.equals(mimeType, that.mimeType()) && Objects.equals(size, that.size()) && Objects.equals(annotations, that.annotations()) && Objects.equals(meta, that.meta());
		}

		@Override
		public int hashCode() { return Objects.hash(uri, name, title, description, mimeType, size, annotations, meta); }

		@Override
		public String toString() {
			return "Resource[uri=" + uri + ", name=" + name + ", title=" + title + ", description=" + description + ", mimeType=" + mimeType + ", size=" + size + ", annotations=" + annotations + ", meta=" + meta + "]";
		}

// @formatter:on

		public static Builder builder() {
			return new Builder();
		}

		public static class Builder {

			private String uri;

			private String name;

			private String title;

			private String description;

			private String mimeType;

			private Long size;

			private Annotations annotations;

			private Map<String, Object> meta;

			public Builder uri(String uri) {
				this.uri = uri;
				return this;
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

			public Builder meta(Map<String, Object> meta) {
				this.meta = meta;
				return this;
			}

			public Resource build() {
				Assert.hasText(uri, "uri must not be empty");
				Assert.hasText(name, "name must not be empty");

				return new Resource(uri, name, title, description, mimeType, size, annotations, meta);
			}

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
	 * @see <a href="https://datatracker.ietf.org/doc/html/rfc6570">RFC 6570</a>
	 * @param meta See specification for notes on _meta usage
	 *
	 */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static final class ResourceTemplate implements Annotated {
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

		public ResourceTemplate(@JsonProperty("uriTemplate") String uriTemplate, @JsonProperty("name") String name, @JsonProperty("title") String title, @JsonProperty("description") String description, @JsonProperty("mimeType") String mimeType, @JsonProperty("annotations") Annotations annotations, @JsonProperty("_meta") Map<String, Object> meta) {
			this.uriTemplate = uriTemplate;
			this.name = name;
			this.title = title;
			this.description = description;
			this.mimeType = mimeType;
			this.annotations = annotations;
			this.meta = meta;
		}

		public String uriTemplate() { return this.uriTemplate; }

		public String name() { return this.name; }

		public String title() { return this.title; }

		public String description() { return this.description; }

		public String mimeType() { return this.mimeType; }

		public Annotations annotations() { return this.annotations; }

		public Map<String, Object> meta() { return this.meta; }

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (!(o instanceof ResourceTemplate)) return false;
			ResourceTemplate that = (ResourceTemplate) o;
			return Objects.equals(uriTemplate, that.uriTemplate()) && Objects.equals(name, that.name()) && Objects.equals(title, that.title()) && Objects.equals(description, that.description()) && Objects.equals(mimeType, that.mimeType()) && Objects.equals(annotations, that.annotations()) && Objects.equals(meta, that.meta());
		}

		@Override
		public int hashCode() { return Objects.hash(uriTemplate, name, title, description, mimeType, annotations, meta); }

		@Override
		public String toString() {
			return "ResourceTemplate[uriTemplate=" + uriTemplate + ", name=" + name + ", title=" + title + ", description=" + description + ", mimeType=" + mimeType + ", annotations=" + annotations + ", meta=" + meta + "]";
		}

// @formatter:on

		public ResourceTemplate(String uriTemplate, String name, String title, String description, String mimeType,
				Annotations annotations) {
			this(uriTemplate, name, title, description, mimeType, annotations, null);
		}

		public ResourceTemplate(String uriTemplate, String name, String description, String mimeType,
				Annotations annotations) {
			this(uriTemplate, name, null, description, mimeType, annotations);
		}

		public static Builder builder() {
			return new Builder();
		}

		public static class Builder {

			private String uriTemplate;

			private String name;

			private String title;

			private String description;

			private String mimeType;

			private Annotations annotations;

			private Map<String, Object> meta;

			public Builder uriTemplate(String uri) {
				this.uriTemplate = uri;
				return this;
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

			public Builder mimeType(String mimeType) {
				this.mimeType = mimeType;
				return this;
			}

			public Builder annotations(Annotations annotations) {
				this.annotations = annotations;
				return this;
			}

			public Builder meta(Map<String, Object> meta) {
				this.meta = meta;
				return this;
			}

			public ResourceTemplate build() {
				Assert.hasText(uriTemplate, "uri must not be empty");
				Assert.hasText(name, "name must not be empty");

				return new ResourceTemplate(uriTemplate, name, title, description, mimeType, annotations, meta);
			}

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

		public ListResourcesResult(@JsonProperty("resources") List<Resource> resources, @JsonProperty("nextCursor") String nextCursor, @JsonProperty("_meta") Map<String, Object> meta) {
			this.resources = resources;
			this.nextCursor = nextCursor;
			this.meta = meta;
		}

		public List<Resource> resources() { return this.resources; }

		public String nextCursor() { return this.nextCursor; }

		public Map<String, Object> meta() { return this.meta; }

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (!(o instanceof ListResourcesResult)) return false;
			ListResourcesResult that = (ListResourcesResult) o;
			return Objects.equals(resources, that.resources()) && Objects.equals(nextCursor, that.nextCursor()) && Objects.equals(meta, that.meta());
		}

		@Override
		public int hashCode() { return Objects.hash(resources, nextCursor, meta); }

		@Override
		public String toString() {
			return "ListResourcesResult[resources=" + resources + ", nextCursor=" + nextCursor + ", meta=" + meta + "]";
		}

// @formatter:on

		public ListResourcesResult(List<Resource> resources, String nextCursor) {
			this(resources, nextCursor, null);
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

		public ListResourceTemplatesResult(@JsonProperty("resourceTemplates") List<ResourceTemplate> resourceTemplates, @JsonProperty("nextCursor") String nextCursor, @JsonProperty("_meta") Map<String, Object> meta) {
			this.resourceTemplates = resourceTemplates;
			this.nextCursor = nextCursor;
			this.meta = meta;
		}

		public List<ResourceTemplate> resourceTemplates() { return this.resourceTemplates; }

		public String nextCursor() { return this.nextCursor; }

		public Map<String, Object> meta() { return this.meta; }

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (!(o instanceof ListResourceTemplatesResult)) return false;
			ListResourceTemplatesResult that = (ListResourceTemplatesResult) o;
			return Objects.equals(resourceTemplates, that.resourceTemplates()) && Objects.equals(nextCursor, that.nextCursor()) && Objects.equals(meta, that.meta());
		}

		@Override
		public int hashCode() { return Objects.hash(resourceTemplates, nextCursor, meta); }

		@Override
		public String toString() {
			return "ListResourceTemplatesResult[resourceTemplates=" + resourceTemplates + ", nextCursor=" + nextCursor + ", meta=" + meta + "]";
		}

// @formatter:on

		public ListResourceTemplatesResult(List<ResourceTemplate> resourceTemplates, String nextCursor) {
			this(resourceTemplates, nextCursor, null);
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

		public ReadResourceRequest(@JsonProperty("uri") String uri, @JsonProperty("_meta") Map<String, Object> meta) {
			this.uri = uri;
			this.meta = meta;
		}

		public String uri() { return this.uri; }

		public Map<String, Object> meta() { return this.meta; }

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (!(o instanceof ReadResourceRequest)) return false;
			ReadResourceRequest that = (ReadResourceRequest) o;
			return Objects.equals(uri, that.uri()) && Objects.equals(meta, that.meta());
		}

		@Override
		public int hashCode() { return Objects.hash(uri, meta); }

		@Override
		public String toString() {
			return "ReadResourceRequest[uri=" + uri + ", meta=" + meta + "]";
		}

// @formatter:on

		public ReadResourceRequest(String uri) {
			this(uri, null);
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

		public ReadResourceResult(@JsonProperty("contents") List<ResourceContents> contents, @JsonProperty("_meta") Map<String, Object> meta) {
			this.contents = contents;
			this.meta = meta;
		}

		public List<ResourceContents> contents() { return this.contents; }

		public Map<String, Object> meta() { return this.meta; }

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (!(o instanceof ReadResourceResult)) return false;
			ReadResourceResult that = (ReadResourceResult) o;
			return Objects.equals(contents, that.contents()) && Objects.equals(meta, that.meta());
		}

		@Override
		public int hashCode() { return Objects.hash(contents, meta); }

		@Override
		public String toString() {
			return "ReadResourceResult[contents=" + contents + ", meta=" + meta + "]";
		}

// @formatter:on

		public ReadResourceResult(List<ResourceContents> contents) {
			this(contents, null);
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

		public SubscribeRequest(@JsonProperty("uri") String uri, @JsonProperty("_meta") Map<String, Object> meta) {
			this.uri = uri;
			this.meta = meta;
		}

		public String uri() { return this.uri; }

		public Map<String, Object> meta() { return this.meta; }

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (!(o instanceof SubscribeRequest)) return false;
			SubscribeRequest that = (SubscribeRequest) o;
			return Objects.equals(uri, that.uri()) && Objects.equals(meta, that.meta());
		}

		@Override
		public int hashCode() { return Objects.hash(uri, meta); }

		@Override
		public String toString() {
			return "SubscribeRequest[uri=" + uri + ", meta=" + meta + "]";
		}

// @formatter:on

		public SubscribeRequest(String uri) {
			this(uri, null);
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

		public UnsubscribeRequest(@JsonProperty("uri") String uri, @JsonProperty("_meta") Map<String, Object> meta) {
			this.uri = uri;
			this.meta = meta;
		}

		public String uri() { return this.uri; }

		public Map<String, Object> meta() { return this.meta; }

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (!(o instanceof UnsubscribeRequest)) return false;
			UnsubscribeRequest that = (UnsubscribeRequest) o;
			return Objects.equals(uri, that.uri()) && Objects.equals(meta, that.meta());
		}

		@Override
		public int hashCode() { return Objects.hash(uri, meta); }

		@Override
		public String toString() {
			return "UnsubscribeRequest[uri=" + uri + ", meta=" + meta + "]";
		}

// @formatter:on

		public UnsubscribeRequest(String uri) {
			this(uri, null);
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

		public TextResourceContents(@JsonProperty("uri") String uri, @JsonProperty("mimeType") String mimeType, @JsonProperty("text") String text, @JsonProperty("_meta") Map<String, Object> meta) {
			this.uri = uri;
			this.mimeType = mimeType;
			this.text = text;
			this.meta = meta;
		}

		public String uri() { return this.uri; }

		public String mimeType() { return this.mimeType; }

		public String text() { return this.text; }

		public Map<String, Object> meta() { return this.meta; }

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (!(o instanceof TextResourceContents)) return false;
			TextResourceContents that = (TextResourceContents) o;
			return Objects.equals(uri, that.uri()) && Objects.equals(mimeType, that.mimeType()) && Objects.equals(text, that.text()) && Objects.equals(meta, that.meta());
		}

		@Override
		public int hashCode() { return Objects.hash(uri, mimeType, text, meta); }

		@Override
		public String toString() {
			return "TextResourceContents[uri=" + uri + ", mimeType=" + mimeType + ", text=" + text + ", meta=" + meta + "]";
		}

// @formatter:on

		public TextResourceContents(String uri, String mimeType, String text) {
			this(uri, mimeType, text, null);
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

		public BlobResourceContents(@JsonProperty("uri") String uri, @JsonProperty("mimeType") String mimeType, @JsonProperty("blob") String blob, @JsonProperty("_meta") Map<String, Object> meta) {
			this.uri = uri;
			this.mimeType = mimeType;
			this.blob = blob;
			this.meta = meta;
		}

		public String uri() { return this.uri; }

		public String mimeType() { return this.mimeType; }

		public String blob() { return this.blob; }

		public Map<String, Object> meta() { return this.meta; }

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (!(o instanceof BlobResourceContents)) return false;
			BlobResourceContents that = (BlobResourceContents) o;
			return Objects.equals(uri, that.uri()) && Objects.equals(mimeType, that.mimeType()) && Objects.equals(blob, that.blob()) && Objects.equals(meta, that.meta());
		}

		@Override
		public int hashCode() { return Objects.hash(uri, mimeType, blob, meta); }

		@Override
		public String toString() {
			return "BlobResourceContents[uri=" + uri + ", mimeType=" + mimeType + ", blob=" + blob + ", meta=" + meta + "]";
		}

// @formatter:on

		public BlobResourceContents(String uri, String mimeType, String blob) {
			this(uri, mimeType, blob, null);
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
	 * @param meta See specification for notes on _meta usage
	 */
	@Builder
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

		public Prompt(@JsonProperty("name") String name, @JsonProperty("title") String title, @JsonProperty("description") String description, @JsonProperty("arguments") List<PromptArgument> arguments, @JsonProperty("_meta") Map<String, Object> meta) {
			this.name = name;
			this.title = title;
			this.description = description;
			this.arguments = arguments;
			this.meta = meta;
		}

		public String name() { return this.name; }

		public String title() { return this.title; }

		public String description() { return this.description; }

		public List<PromptArgument> arguments() { return this.arguments; }

		public Map<String, Object> meta() { return this.meta; }

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (!(o instanceof Prompt)) return false;
			Prompt that = (Prompt) o;
			return Objects.equals(name, that.name()) && Objects.equals(title, that.title()) && Objects.equals(description, that.description()) && Objects.equals(arguments, that.arguments()) && Objects.equals(meta, that.meta());
		}

		@Override
		public int hashCode() { return Objects.hash(name, title, description, arguments, meta); }

		@Override
		public String toString() {
			return "Prompt[name=" + name + ", title=" + title + ", description=" + description + ", arguments=" + arguments + ", meta=" + meta + "]";
		}

// @formatter:on

		public Prompt(String name, String description, List<PromptArgument> arguments) {
			this(name, null, description, arguments != null ? arguments : new ArrayList<>());
		}

		public Prompt(String name, String title, String description, List<PromptArgument> arguments) {
			this(name, title, description, arguments != null ? arguments : new ArrayList<>(), null);
		}
	}

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

		public PromptArgument(@JsonProperty("name") String name, @JsonProperty("title") String title, @JsonProperty("description") String description, @JsonProperty("required") Boolean required) {
			this.name = name;
			this.title = title;
			this.description = description;
			this.required = required;
		}

		public String name() { return this.name; }

		public String title() { return this.title; }

		public String description() { return this.description; }

		public Boolean required() { return this.required; }

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (!(o instanceof PromptArgument)) return false;
			PromptArgument that = (PromptArgument) o;
			return Objects.equals(name, that.name()) && Objects.equals(title, that.title()) && Objects.equals(description, that.description()) && Objects.equals(required, that.required());
		}

		@Override
		public int hashCode() { return Objects.hash(name, title, description, required); }

		@Override
		public String toString() {
			return "PromptArgument[name=" + name + ", title=" + title + ", description=" + description + ", required=" + required + "]";
		}

// @formatter:on

		public PromptArgument(String name, String description, Boolean required) {
			this(name, null, description, required);
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

		public PromptMessage(@JsonProperty("role") Role role, @JsonProperty("content") Content content) {
			this.role = role;
			this.content = content;
		}

		public Role role() { return this.role; }

		public Content content() { return this.content; }

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (!(o instanceof PromptMessage)) return false;
			PromptMessage that = (PromptMessage) o;
			return Objects.equals(role, that.role()) && Objects.equals(content, that.content());
		}

		@Override
		public int hashCode() { return Objects.hash(role, content); }

		@Override
		public String toString() {
			return "PromptMessage[role=" + role + ", content=" + content + "]";
		}

// @formatter:on
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

		public ListPromptsResult(@JsonProperty("prompts") List<Prompt> prompts, @JsonProperty("nextCursor") String nextCursor, @JsonProperty("_meta") Map<String, Object> meta) {
			this.prompts = prompts;
			this.nextCursor = nextCursor;
			this.meta = meta;
		}

		public List<Prompt> prompts() { return this.prompts; }

		public String nextCursor() { return this.nextCursor; }

		public Map<String, Object> meta() { return this.meta; }

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (!(o instanceof ListPromptsResult)) return false;
			ListPromptsResult that = (ListPromptsResult) o;
			return Objects.equals(prompts, that.prompts()) && Objects.equals(nextCursor, that.nextCursor()) && Objects.equals(meta, that.meta());
		}

		@Override
		public int hashCode() { return Objects.hash(prompts, nextCursor, meta); }

		@Override
		public String toString() {
			return "ListPromptsResult[prompts=" + prompts + ", nextCursor=" + nextCursor + ", meta=" + meta + "]";
		}

// @formatter:on

		public ListPromptsResult(List<Prompt> prompts, String nextCursor) {
			this(prompts, nextCursor, null);
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

		public GetPromptRequest(@JsonProperty("name") String name, @JsonProperty("arguments") Map<String, Object> arguments, @JsonProperty("_meta") Map<String, Object> meta) {
			this.name = name;
			this.arguments = arguments;
			this.meta = meta;
		}

		public String name() { return this.name; }

		public Map<String, Object> arguments() { return this.arguments; }

		public Map<String, Object> meta() { return this.meta; }

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (!(o instanceof GetPromptRequest)) return false;
			GetPromptRequest that = (GetPromptRequest) o;
			return Objects.equals(name, that.name()) && Objects.equals(arguments, that.arguments()) && Objects.equals(meta, that.meta());
		}

		@Override
		public int hashCode() { return Objects.hash(name, arguments, meta); }

		@Override
		public String toString() {
			return "GetPromptRequest[name=" + name + ", arguments=" + arguments + ", meta=" + meta + "]";
		}

// @formatter:on

		public GetPromptRequest(String name, Map<String, Object> arguments) {
			this(name, arguments, null);
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

		public GetPromptResult(@JsonProperty("description") String description, @JsonProperty("messages") List<PromptMessage> messages, @JsonProperty("_meta") Map<String, Object> meta) {
			this.description = description;
			this.messages = messages;
			this.meta = meta;
		}

		public String description() { return this.description; }

		public List<PromptMessage> messages() { return this.messages; }

		public Map<String, Object> meta() { return this.meta; }

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (!(o instanceof GetPromptResult)) return false;
			GetPromptResult that = (GetPromptResult) o;
			return Objects.equals(description, that.description()) && Objects.equals(messages, that.messages()) && Objects.equals(meta, that.meta());
		}

		@Override
		public int hashCode() { return Objects.hash(description, messages, meta); }

		@Override
		public String toString() {
			return "GetPromptResult[description=" + description + ", messages=" + messages + ", meta=" + meta + "]";
		}

// @formatter:on

		public GetPromptResult(String description, List<PromptMessage> messages) {
			this(description, messages, null);
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

		public ListToolsResult(@JsonProperty("tools") List<Tool> tools, @JsonProperty("nextCursor") String nextCursor, @JsonProperty("_meta") Map<String, Object> meta) {
			this.tools = tools;
			this.nextCursor = nextCursor;
			this.meta = meta;
		}

		public List<Tool> tools() { return this.tools; }

		public String nextCursor() { return this.nextCursor; }

		public Map<String, Object> meta() { return this.meta; }

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (!(o instanceof ListToolsResult)) return false;
			ListToolsResult that = (ListToolsResult) o;
			return Objects.equals(tools, that.tools()) && Objects.equals(nextCursor, that.nextCursor()) && Objects.equals(meta, that.meta());
		}

		@Override
		public int hashCode() { return Objects.hash(tools, nextCursor, meta); }

		@Override
		public String toString() {
			return "ListToolsResult[tools=" + tools + ", nextCursor=" + nextCursor + ", meta=" + meta + "]";
		}

// @formatter:on

		public ListToolsResult(List<Tool> tools, String nextCursor) {
			this(tools, nextCursor, null);
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
	 */
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

		public JsonSchema(@JsonProperty("type") String type, @JsonProperty("properties") Map<String, Object> properties, @JsonProperty("required") List<String> required, @JsonProperty("additionalProperties") Boolean additionalProperties, @JsonProperty("$defs") Map<String, Object> defs, @JsonProperty("definitions") Map<String, Object> definitions) {
			this.type = type;
			this.properties = properties;
			this.required = required;
			this.additionalProperties = additionalProperties;
			this.defs = defs;
			this.definitions = definitions;
		}

		public String type() { return this.type; }

		public Map<String, Object> properties() { return this.properties; }

		public List<String> required() { return this.required; }

		public Boolean additionalProperties() { return this.additionalProperties; }

		public Map<String, Object> defs() { return this.defs; }

		public Map<String, Object> definitions() { return this.definitions; }

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (!(o instanceof JsonSchema)) return false;
			JsonSchema that = (JsonSchema) o;
			return Objects.equals(type, that.type()) && Objects.equals(properties, that.properties()) && Objects.equals(required, that.required()) && Objects.equals(additionalProperties, that.additionalProperties()) && Objects.equals(defs, that.defs()) && Objects.equals(definitions, that.definitions());
		}

		@Override
		public int hashCode() { return Objects.hash(type, properties, required, additionalProperties, defs, definitions); }

		@Override
		public String toString() {
			return "JsonSchema[type=" + type + ", properties=" + properties + ", required=" + required + ", additionalProperties=" + additionalProperties + ", defs=" + defs + ", definitions=" + definitions + "]";
		}

// @formatter:on
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
	@Builder
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

		public ToolAnnotations(@JsonProperty("title") String title, @JsonProperty("readOnlyHint") Boolean readOnlyHint, @JsonProperty("destructiveHint") Boolean destructiveHint, @JsonProperty("idempotentHint") Boolean idempotentHint, @JsonProperty("openWorldHint") Boolean openWorldHint, @JsonProperty("returnDirect") Boolean returnDirect) {
			this.title = title;
			this.readOnlyHint = readOnlyHint;
			this.destructiveHint = destructiveHint;
			this.idempotentHint = idempotentHint;
			this.openWorldHint = openWorldHint;
			this.returnDirect = returnDirect;
		}

		public String title() { return this.title; }

		public Boolean readOnlyHint() { return this.readOnlyHint; }

		public Boolean destructiveHint() { return this.destructiveHint; }

		public Boolean idempotentHint() { return this.idempotentHint; }

		public Boolean openWorldHint() { return this.openWorldHint; }

		public Boolean returnDirect() { return this.returnDirect; }

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (!(o instanceof ToolAnnotations)) return false;
			ToolAnnotations that = (ToolAnnotations) o;
			return Objects.equals(title, that.title()) && Objects.equals(readOnlyHint, that.readOnlyHint()) && Objects.equals(destructiveHint, that.destructiveHint()) && Objects.equals(idempotentHint, that.idempotentHint()) && Objects.equals(openWorldHint, that.openWorldHint()) && Objects.equals(returnDirect, that.returnDirect());
		}

		@Override
		public int hashCode() { return Objects.hash(title, readOnlyHint, destructiveHint, idempotentHint, openWorldHint, returnDirect); }

		@Override
		public String toString() {
			return "ToolAnnotations[title=" + title + ", readOnlyHint=" + readOnlyHint + ", destructiveHint=" + destructiveHint + ", idempotentHint=" + idempotentHint + ", openWorldHint=" + openWorldHint + ", returnDirect=" + returnDirect + "]";
		}

// @formatter:on
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
	 * the arguments when calling this tool. This allows clients to validate tool
	 * @param outputSchema An optional JSON Schema object defining the structure of the
	 * tool's output returned in the structuredContent field of a CallToolResult.
	 * @param annotations Optional additional tool information.
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
		private final JsonSchema inputSchema;
		@JsonProperty("outputSchema")
		private final Map<String, Object> outputSchema;
		@JsonProperty("annotations")
		private final ToolAnnotations annotations;
		@JsonProperty("_meta")
		private final Map<String, Object> meta;

		public Tool(@JsonProperty("name") String name, @JsonProperty("title") String title, @JsonProperty("description") String description, @JsonProperty("inputSchema") JsonSchema inputSchema, @JsonProperty("outputSchema") Map<String, Object> outputSchema, @JsonProperty("annotations") ToolAnnotations annotations, @JsonProperty("_meta") Map<String, Object> meta) {
			this.name = name;
			this.title = title;
			this.description = description;
			this.inputSchema = inputSchema;
			this.outputSchema = outputSchema;
			this.annotations = annotations;
			this.meta = meta;
		}

		public String name() { return this.name; }

		public String title() { return this.title; }

		public String description() { return this.description; }

		public JsonSchema inputSchema() { return this.inputSchema; }

		public Map<String, Object> outputSchema() { return this.outputSchema; }

		public ToolAnnotations annotations() { return this.annotations; }

		public Map<String, Object> meta() { return this.meta; }

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (!(o instanceof Tool)) return false;
			Tool that = (Tool) o;
			return Objects.equals(name, that.name()) && Objects.equals(title, that.title()) && Objects.equals(description, that.description()) && Objects.equals(inputSchema, that.inputSchema()) && Objects.equals(outputSchema, that.outputSchema()) && Objects.equals(annotations, that.annotations()) && Objects.equals(meta, that.meta());
		}

		@Override
		public int hashCode() { return Objects.hash(name, title, description, inputSchema, outputSchema, annotations, meta); }

		@Override
		public String toString() {
			return "Tool[name=" + name + ", title=" + title + ", description=" + description + ", inputSchema=" + inputSchema + ", outputSchema=" + outputSchema + ", annotations=" + annotations + ", meta=" + meta + "]";
		}

// @formatter:on

		public static Builder builder() {
			return new Builder();
		}

		public static class Builder {

			private String name;

			private String title;

			private String description;

			private JsonSchema inputSchema;

			private Map<String, Object> outputSchema;

			private ToolAnnotations annotations;

			private Map<String, Object> meta;

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

			public Builder inputSchema(JsonSchema inputSchema) {
				this.inputSchema = inputSchema;
				return this;
			}

			public Builder inputSchema(McpJsonMapper jsonMapper, String inputSchema) {
				this.inputSchema = parseSchema(jsonMapper, inputSchema);
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

			public Builder meta(Map<String, Object> meta) {
				this.meta = meta;
				return this;
			}

			public Tool build() {
				Assert.hasText(name, "name must not be empty");
				return new Tool(name, title, description, inputSchema, outputSchema, annotations, meta);
			}

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

	private static JsonSchema parseSchema(McpJsonMapper jsonMapper, String schema) {
		try {
			return jsonMapper.readValue(schema, JsonSchema.class);
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

		public CallToolRequest(@JsonProperty("name") String name, @JsonProperty("arguments") Map<String, Object> arguments, @JsonProperty("_meta") Map<String, Object> meta) {
			this.name = name;
			this.arguments = arguments;
			this.meta = meta;
		}

		public String name() { return this.name; }

		public Map<String, Object> arguments() { return this.arguments; }

		public Map<String, Object> meta() { return this.meta; }

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (!(o instanceof CallToolRequest)) return false;
			CallToolRequest that = (CallToolRequest) o;
			return Objects.equals(name, that.name()) && Objects.equals(arguments, that.arguments()) && Objects.equals(meta, that.meta());
		}

		@Override
		public int hashCode() { return Objects.hash(name, arguments, meta); }

		@Override
		public String toString() {
			return "CallToolRequest[name=" + name + ", arguments=" + arguments + ", meta=" + meta + "]";
		}

// @formatter:on

		public CallToolRequest(McpJsonMapper jsonMapper, String name, String jsonArguments) {
			this(name, parseJsonArguments(jsonMapper, jsonArguments), null);
		}

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

		public static Builder builder() {
			return new Builder();
		}

		public static class Builder {

			private String name;

			private Map<String, Object> arguments;

			private Map<String, Object> meta;

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
	 */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static final class CallToolResult implements Result {
		@JsonProperty("content")
		private final List<Content> content;
		private final Boolean isError;
		@JsonProperty("structuredContent")
		private final Object structuredContent;
		@JsonProperty("_meta")
		private final Map<String, Object> meta;

		public CallToolResult(@JsonProperty("content") List<Content> content, @JsonProperty("isError") Boolean isError, @JsonProperty("structuredContent") Object structuredContent, @JsonProperty("_meta") Map<String, Object> meta) {
			this.content = content;
			this.isError = isError;
			this.structuredContent = structuredContent;
			this.meta = meta;
		}

		public List<Content> content() { return this.content; }

		@JsonProperty("isError")
		public Boolean isError() { return this.isError; }

		public Object structuredContent() { return this.structuredContent; }

		public Map<String, Object> meta() { return this.meta; }

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (!(o instanceof CallToolResult)) return false;
			CallToolResult that = (CallToolResult) o;
			return Objects.equals(content, that.content()) && Objects.equals(isError, that.isError()) && Objects.equals(structuredContent, that.structuredContent()) && Objects.equals(meta, that.meta());
		}

		@Override
		public int hashCode() { return Objects.hash(content, isError, structuredContent, meta); }

		@Override
		public String toString() {
			return "CallToolResult[content=" + content + ", isError=" + isError + ", structuredContent=" + structuredContent + ", meta=" + meta + "]";
		}

// @formatter:on

		/**
		 * Creates a builder for {@link CallToolResult}.
		 * @return a new builder instance
		 */
		public static Builder builder() {
			return new Builder();
		}

		/**
		 * Builder for {@link CallToolResult}.
		 */
		public static class Builder {

			private List<Content> content = new ArrayList<>();

			private Boolean isError = false;

			private Object structuredContent;

			private Map<String, Object> meta;

			/**
			 * Sets the content list for the tool result.
			 * @param content the content list
			 * @return this builder
			 */
			public Builder content(List<Content> content) {
				Assert.notNull(content, "content must not be null");
				this.content = content;
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
				textContent.stream().map(TextContent::new).forEach(this.content::add);
				return this;
			}

			/**
			 * Adds a content item to the tool result.
			 * @param contentItem the content item to add
			 * @return this builder
			 */
			public Builder addContent(Content contentItem) {
				Assert.notNull(contentItem, "contentItem must not be null");
				if (this.content == null) {
					this.content = new ArrayList<>();
				}
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
				return addContent(new TextContent(text));
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
				return new CallToolResult(content, isError, structuredContent, meta);
			}

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

		public ModelPreferences(@JsonProperty("hints") List<ModelHint> hints, @JsonProperty("costPriority") Double costPriority, @JsonProperty("speedPriority") Double speedPriority, @JsonProperty("intelligencePriority") Double intelligencePriority) {
			this.hints = hints;
			this.costPriority = costPriority;
			this.speedPriority = speedPriority;
			this.intelligencePriority = intelligencePriority;
		}

		public List<ModelHint> hints() { return this.hints; }

		public Double costPriority() { return this.costPriority; }

		public Double speedPriority() { return this.speedPriority; }

		public Double intelligencePriority() { return this.intelligencePriority; }

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (!(o instanceof ModelPreferences)) return false;
			ModelPreferences that = (ModelPreferences) o;
			return Objects.equals(hints, that.hints()) && Objects.equals(costPriority, that.costPriority()) && Objects.equals(speedPriority, that.speedPriority()) && Objects.equals(intelligencePriority, that.intelligencePriority());
		}

		@Override
		public int hashCode() { return Objects.hash(hints, costPriority, speedPriority, intelligencePriority); }

		@Override
		public String toString() {
			return "ModelPreferences[hints=" + hints + ", costPriority=" + costPriority + ", speedPriority=" + speedPriority + ", intelligencePriority=" + intelligencePriority + "]";
		}

// @formatter:on

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

		public ModelHint(@JsonProperty("name") String name) {
			this.name = name;
		}

		public String name() { return this.name; }

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (!(o instanceof ModelHint)) return false;
			ModelHint that = (ModelHint) o;
			return Objects.equals(name, that.name());
		}

		@Override
		public int hashCode() { return Objects.hash(name); }

		@Override
		public String toString() {
			return "ModelHint[name=" + name + "]";
		}

public static ModelHint of(String name) {
			return new ModelHint(name);
		}
	}

	/**
	 * Describes a message issued to or received from an LLM API.
	 *
	 * @param role The sender or recipient of messages and data in a conversation
	 * @param content The content of the message
	 */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static final class SamplingMessage {
		@JsonProperty("role")
		private final Role role;
		@JsonProperty("content")
		private final Content content;

		public SamplingMessage(@JsonProperty("role") Role role, @JsonProperty("content") Content content) {
			this.role = role;
			this.content = content;
		}

		public Role role() { return this.role; }

		public Content content() { return this.content; }

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (!(o instanceof SamplingMessage)) return false;
			SamplingMessage that = (SamplingMessage) o;
			return Objects.equals(role, that.role()) && Objects.equals(content, that.content());
		}

		@Override
		public int hashCode() { return Objects.hash(role, content); }

		@Override
		public String toString() {
			return "SamplingMessage[role=" + role + ", content=" + content + "]";
		}

// @formatter:on
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

		public CreateMessageRequest(@JsonProperty("messages") List<SamplingMessage> messages, @JsonProperty("modelPreferences") ModelPreferences modelPreferences, @JsonProperty("systemPrompt") String systemPrompt, @JsonProperty("includeContext") ContextInclusionStrategy includeContext, @JsonProperty("temperature") Double temperature, @JsonProperty("maxTokens") Integer maxTokens, @JsonProperty("stopSequences") List<String> stopSequences, @JsonProperty("metadata") Map<String, Object> metadata, @JsonProperty("_meta") Map<String, Object> meta) {
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

		public List<SamplingMessage> messages() { return this.messages; }

		public ModelPreferences modelPreferences() { return this.modelPreferences; }

		public String systemPrompt() { return this.systemPrompt; }

		public ContextInclusionStrategy includeContext() { return this.includeContext; }

		public Double temperature() { return this.temperature; }

		public Integer maxTokens() { return this.maxTokens; }

		public List<String> stopSequences() { return this.stopSequences; }

		public Map<String, Object> metadata() { return this.metadata; }

		public Map<String, Object> meta() { return this.meta; }

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (!(o instanceof CreateMessageRequest)) return false;
			CreateMessageRequest that = (CreateMessageRequest) o;
			return Objects.equals(messages, that.messages()) && Objects.equals(modelPreferences, that.modelPreferences()) && Objects.equals(systemPrompt, that.systemPrompt()) && Objects.equals(includeContext, that.includeContext()) && Objects.equals(temperature, that.temperature()) && Objects.equals(maxTokens, that.maxTokens()) && Objects.equals(stopSequences, that.stopSequences()) && Objects.equals(metadata, that.metadata()) && Objects.equals(meta, that.meta());
		}

		@Override
		public int hashCode() { return Objects.hash(messages, modelPreferences, systemPrompt, includeContext, temperature, maxTokens, stopSequences, metadata, meta); }

		@Override
		public String toString() {
			return "CreateMessageRequest[messages=" + messages + ", modelPreferences=" + modelPreferences + ", systemPrompt=" + systemPrompt + ", includeContext=" + includeContext + ", temperature=" + temperature + ", maxTokens=" + maxTokens + ", stopSequences=" + stopSequences + ", metadata=" + metadata + ", meta=" + meta + "]";
		}

// @formatter:on

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
			@JsonProperty("allServers")ALL_SERVERS
		} // @formatter:on

		public static Builder builder() {
			return new Builder();
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

			public Builder messages(List<SamplingMessage> messages) {
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
				return new CreateMessageRequest(messages, modelPreferences, systemPrompt, includeContext, temperature,
						maxTokens, stopSequences, metadata, meta);
			}

		}
	}

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

		public CreateMessageResult(@JsonProperty("role") Role role, @JsonProperty("content") Content content, @JsonProperty("model") String model, @JsonProperty("stopReason") StopReason stopReason, @JsonProperty("_meta") Map<String, Object> meta) {
			this.role = role;
			this.content = content;
			this.model = model;
			this.stopReason = stopReason;
			this.meta = meta;
		}

		public Role role() { return this.role; }

		public Content content() { return this.content; }

		public String model() { return this.model; }

		public StopReason stopReason() { return this.stopReason; }

		public Map<String, Object> meta() { return this.meta; }

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (!(o instanceof CreateMessageResult)) return false;
			CreateMessageResult that = (CreateMessageResult) o;
			return Objects.equals(role, that.role()) && Objects.equals(content, that.content()) && Objects.equals(model, that.model()) && Objects.equals(stopReason, that.stopReason()) && Objects.equals(meta, that.meta());
		}

		@Override
		public int hashCode() { return Objects.hash(role, content, model, stopReason, meta); }

		@Override
		public String toString() {
			return "CreateMessageResult[role=" + role + ", content=" + content + ", model=" + model + ", stopReason=" + stopReason + ", meta=" + meta + "]";
		}

// @formatter:on

		public enum StopReason {

		// @formatter:off
			@JsonProperty("endTurn") END_TURN("endTurn"),
			@JsonProperty("stopSequence") STOP_SEQUENCE("stopSequence"),
			@JsonProperty("maxTokens") MAX_TOKENS("maxTokens"),
			@JsonProperty("unknown") UNKNOWN("unknown");
			// @formatter:on

			private final String value;

			StopReason(String value) {
				this.value = value;
			}

			@JsonCreator
			private static StopReason of(String value) {
				return Arrays.stream(StopReason.values())
					.filter(stopReason -> stopReason.value.equals(value))
					.findFirst()
					.orElse(StopReason.UNKNOWN);
			}

		}

		public CreateMessageResult(Role role, Content content, String model, StopReason stopReason) {
			this(role, content, model, stopReason, null);
		}

		public static Builder builder() {
			return new Builder();
		}

		public static class Builder {

			private Role role = Role.ASSISTANT;

			private Content content;

			private String model;

			private StopReason stopReason = StopReason.END_TURN;

			private Map<String, Object> meta;

			public Builder role(Role role) {
				this.role = role;
				return this;
			}

			public Builder content(Content content) {
				this.content = content;
				return this;
			}

			public Builder model(String model) {
				this.model = model;
				return this;
			}

			public Builder stopReason(StopReason stopReason) {
				this.stopReason = stopReason;
				return this;
			}

			public Builder message(String message) {
				this.content = new TextContent(message);
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
	}

	// Elicitation
	/**
	 * A request from the server to elicit additional information from the user via the
	 * client.
	 *
	 * @param message The message to present to the user
	 * @param requestedSchema A restricted subset of JSON Schema. Only top-level
	 * properties are allowed, without nesting
	 * @param meta See specification for notes on _meta usage
	 */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static final class ElicitRequest implements Request {
		@JsonProperty("message")
		private final String message;
		@JsonProperty("requestedSchema")
		private final Map<String, Object> requestedSchema;
		@JsonProperty("_meta")
		private final Map<String, Object> meta;

		public ElicitRequest(@JsonProperty("message") String message, @JsonProperty("requestedSchema") Map<String, Object> requestedSchema, @JsonProperty("_meta") Map<String, Object> meta) {
			this.message = message;
			this.requestedSchema = requestedSchema;
			this.meta = meta;
		}

		public String message() { return this.message; }

		public Map<String, Object> requestedSchema() { return this.requestedSchema; }

		public Map<String, Object> meta() { return this.meta; }

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (!(o instanceof ElicitRequest)) return false;
			ElicitRequest that = (ElicitRequest) o;
			return Objects.equals(message, that.message()) && Objects.equals(requestedSchema, that.requestedSchema()) && Objects.equals(meta, that.meta());
		}

		@Override
		public int hashCode() { return Objects.hash(message, requestedSchema, meta); }

		@Override
		public String toString() {
			return "ElicitRequest[message=" + message + ", requestedSchema=" + requestedSchema + ", meta=" + meta + "]";
		}

// @formatter:on

		// backwards compatibility constructor
		public ElicitRequest(String message, Map<String, Object> requestedSchema) {
			this(message, requestedSchema, null);
		}

		public static Builder builder() {
			return new Builder();
		}

		public static class Builder {

			private String message;

			private Map<String, Object> requestedSchema;

			private Map<String, Object> meta;

			public Builder message(String message) {
				this.message = message;
				return this;
			}

			public Builder requestedSchema(Map<String, Object> requestedSchema) {
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

			public ElicitRequest build() {
				return new ElicitRequest(message, requestedSchema, meta);
			}

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

		public ElicitResult(@JsonProperty("action") Action action, @JsonProperty("content") Map<String, Object> content, @JsonProperty("_meta") Map<String, Object> meta) {
			this.action = action;
			this.content = content;
			this.meta = meta;
		}

		public Action action() { return this.action; }

		public Map<String, Object> content() { return this.content; }

		public Map<String, Object> meta() { return this.meta; }

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (!(o instanceof ElicitResult)) return false;
			ElicitResult that = (ElicitResult) o;
			return Objects.equals(action, that.action()) && Objects.equals(content, that.content()) && Objects.equals(meta, that.meta());
		}

		@Override
		public int hashCode() { return Objects.hash(action, content, meta); }

		@Override
		public String toString() {
			return "ElicitResult[action=" + action + ", content=" + content + ", meta=" + meta + "]";
		}

// @formatter:on

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

		public static Builder builder() {
			return new Builder();
		}

		public static class Builder {

			private Action action;

			private Map<String, Object> content;

			private Map<String, Object> meta;

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
				return new ElicitResult(action, content, meta);
			}

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

		public PaginatedRequest(@JsonProperty("cursor") String cursor, @JsonProperty("_meta") Map<String, Object> meta) {
			this.cursor = cursor;
			this.meta = meta;
		}

		public String cursor() { return this.cursor; }

		public Map<String, Object> meta() { return this.meta; }

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (!(o instanceof PaginatedRequest)) return false;
			PaginatedRequest that = (PaginatedRequest) o;
			return Objects.equals(cursor, that.cursor()) && Objects.equals(meta, that.meta());
		}

		@Override
		public int hashCode() { return Objects.hash(cursor, meta); }

		@Override
		public String toString() {
			return "PaginatedRequest[cursor=" + cursor + ", meta=" + meta + "]";
		}

// @formatter:on

		public PaginatedRequest(String cursor) {
			this(cursor, null);
		}

		/**
		 * Creates a new paginated request with an empty cursor.
		 */
		public PaginatedRequest() {
			this(null);
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

		public PaginatedResult(@JsonProperty("nextCursor") String nextCursor) {
			this.nextCursor = nextCursor;
		}

		public String nextCursor() { return this.nextCursor; }

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (!(o instanceof PaginatedResult)) return false;
			PaginatedResult that = (PaginatedResult) o;
			return Objects.equals(nextCursor, that.nextCursor());
		}

		@Override
		public int hashCode() { return Objects.hash(nextCursor); }

		@Override
		public String toString() {
			return "PaginatedResult[nextCursor=" + nextCursor + "]";
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

		public ProgressNotification(@JsonProperty("progressToken") Object progressToken, @JsonProperty("progress") Double progress, @JsonProperty("total") Double total, @JsonProperty("message") String message, @JsonProperty("_meta") Map<String, Object> meta) {
			this.progressToken = progressToken;
			this.progress = progress;
			this.total = total;
			this.message = message;
			this.meta = meta;
		}

		public Object progressToken() { return this.progressToken; }

		public Double progress() { return this.progress; }

		public Double total() { return this.total; }

		public String message() { return this.message; }

		public Map<String, Object> meta() { return this.meta; }

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (!(o instanceof ProgressNotification)) return false;
			ProgressNotification that = (ProgressNotification) o;
			return Objects.equals(progressToken, that.progressToken()) && Objects.equals(progress, that.progress()) && Objects.equals(total, that.total()) && Objects.equals(message, that.message()) && Objects.equals(meta, that.meta());
		}

		@Override
		public int hashCode() { return Objects.hash(progressToken, progress, total, message, meta); }

		@Override
		public String toString() {
			return "ProgressNotification[progressToken=" + progressToken + ", progress=" + progress + ", total=" + total + ", message=" + message + ", meta=" + meta + "]";
		}

// @formatter:on

		public ProgressNotification(Object progressToken, double progress, Double total, String message) {
			this(progressToken, progress, total, message, null);
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

		public ResourcesUpdatedNotification(@JsonProperty("uri") String uri, @JsonProperty("_meta") Map<String, Object> meta) {
			this.uri = uri;
			this.meta = meta;
		}

		public String uri() { return this.uri; }

		public Map<String, Object> meta() { return this.meta; }

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (!(o instanceof ResourcesUpdatedNotification)) return false;
			ResourcesUpdatedNotification that = (ResourcesUpdatedNotification) o;
			return Objects.equals(uri, that.uri()) && Objects.equals(meta, that.meta());
		}

		@Override
		public int hashCode() { return Objects.hash(uri, meta); }

		@Override
		public String toString() {
			return "ResourcesUpdatedNotification[uri=" + uri + ", meta=" + meta + "]";
		}

// @formatter:on

		public ResourcesUpdatedNotification(String uri) {
			this(uri, null);
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

		public LoggingMessageNotification(@JsonProperty("level") LoggingLevel level, @JsonProperty("logger") String logger, @JsonProperty("data") String data, @JsonProperty("_meta") Map<String, Object> meta) {
			this.level = level;
			this.logger = logger;
			this.data = data;
			this.meta = meta;
		}

		public LoggingLevel level() { return this.level; }

		public String logger() { return this.logger; }

		public String data() { return this.data; }

		public Map<String, Object> meta() { return this.meta; }

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (!(o instanceof LoggingMessageNotification)) return false;
			LoggingMessageNotification that = (LoggingMessageNotification) o;
			return Objects.equals(level, that.level()) && Objects.equals(logger, that.logger()) && Objects.equals(data, that.data()) && Objects.equals(meta, that.meta());
		}

		@Override
		public int hashCode() { return Objects.hash(level, logger, data, meta); }

		@Override
		public String toString() {
			return "LoggingMessageNotification[level=" + level + ", logger=" + logger + ", data=" + data + ", meta=" + meta + "]";
		}

// @formatter:on

		// backwards compatibility constructor
		public LoggingMessageNotification(LoggingLevel level, String logger, String data) {
			this(level, logger, data, null);
		}

		public static Builder builder() {
			return new Builder();
		}

		public static class Builder {

			private LoggingLevel level = LoggingLevel.INFO;

			private String logger = "server";

			private String data;

			private Map<String, Object> meta;

			public Builder level(LoggingLevel level) {
				this.level = level;
				return this;
			}

			public Builder logger(String logger) {
				this.logger = logger;
				return this;
			}

			public Builder data(String data) {
				this.data = data;
				return this;
			}

			public Builder meta(Map<String, Object> meta) {
				this.meta = meta;
				return this;
			}

			public LoggingMessageNotification build() {
				return new LoggingMessageNotification(level, logger, data, meta);
			}

		}
	}

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

		LoggingLevel(int level) {
			this.level = level;
		}

		public int level() {
			return level;
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

		public SetLevelRequest(@JsonProperty("level") LoggingLevel level) {
			this.level = level;
		}

		public LoggingLevel level() { return this.level; }

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (!(o instanceof SetLevelRequest)) return false;
			SetLevelRequest that = (SetLevelRequest) o;
			return Objects.equals(level, that.level());
		}

		@Override
		public int hashCode() { return Objects.hash(level); }

		@Override
		public String toString() {
			return "SetLevelRequest[level=" + level + "]";
		}
	}

	// ---------------------------
	// Autocomplete
	// ---------------------------
	public interface CompleteReference {

		String type();

		String identifier();

	}

	/**
	 * Identifies a prompt for completion requests.
	 *
	 * @param type The reference type identifier (typically "ref/prompt")
	 * @param name The name of the prompt
	 * @param title An optional title for the prompt
	 */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static final class PromptReference implements CompleteReference {
		@JsonProperty("type")
		private final String type;
		@JsonProperty("name")
		private final String name;
		@JsonProperty("title")
		private final String title;

		public PromptReference(@JsonProperty("type") String type, @JsonProperty("name") String name, @JsonProperty("title") String title) {
			this.type = type;
			this.name = name;
			this.title = title;
		}

		public String type() { return this.type; }

		public String name() { return this.name; }

		public String title() { return this.title; }

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (!(o instanceof PromptReference)) return false;
			PromptReference that = (PromptReference) o;
			return Objects.equals(type, that.type()) && Objects.equals(name, that.name());
		}

		@Override
		public int hashCode() { return Objects.hash(type, name); }

		@Override
		public String toString() {
			return "PromptReference[type=" + type + ", name=" + name + ", title=" + title + "]";
		}

// @formatter:on

		public static final String TYPE = "ref/prompt";

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


	}

	/**
	 * A reference to a resource or resource template definition for completion requests.
	 *
	 * @param type The reference type identifier (typically "ref/resource")
	 * @param uri The URI or URI template of the resource
	 */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static final class ResourceReference implements CompleteReference {
		@JsonProperty("type")
		private final String type;
		@JsonProperty("uri")
		private final String uri;

		public ResourceReference(@JsonProperty("type") String type, @JsonProperty("uri") String uri) {
			this.type = type;
			this.uri = uri;
		}

		public String type() { return this.type; }

		public String uri() { return this.uri; }

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (!(o instanceof ResourceReference)) return false;
			ResourceReference that = (ResourceReference) o;
			return Objects.equals(type, that.type()) && Objects.equals(uri, that.uri());
		}

		@Override
		public int hashCode() { return Objects.hash(type, uri); }

		@Override
		public String toString() {
			return "ResourceReference[type=" + type + ", uri=" + uri + "]";
		}

// @formatter:on

		public static final String TYPE = "ref/resource";

		public ResourceReference(String uri) {
			this(TYPE, uri);
		}

		@Override
		public String identifier() {
			return uri();
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

		public CompleteRequest(@JsonProperty("ref") CompleteReference ref, @JsonProperty("argument") CompleteArgument argument, @JsonProperty("_meta") Map<String, Object> meta, @JsonProperty("context") CompleteContext context) {
			this.ref = ref;
			this.argument = argument;
			this.meta = meta;
			this.context = context;
		}

		public CompleteReference ref() { return this.ref; }

		public CompleteArgument argument() { return this.argument; }

		public Map<String, Object> meta() { return this.meta; }

		public CompleteContext context() { return this.context; }

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (!(o instanceof CompleteRequest)) return false;
			CompleteRequest that = (CompleteRequest) o;
			return Objects.equals(ref, that.ref()) && Objects.equals(argument, that.argument()) && Objects.equals(meta, that.meta()) && Objects.equals(context, that.context());
		}

		@Override
		public int hashCode() { return Objects.hash(ref, argument, meta, context); }

		@Override
		public String toString() {
			return "CompleteRequest[ref=" + ref + ", argument=" + argument + ", meta=" + meta + ", context=" + context + "]";
		}

// @formatter:on

		public CompleteRequest(CompleteReference ref, CompleteArgument argument, Map<String, Object> meta) {
			this(ref, argument, meta, null);
		}

		public CompleteRequest(CompleteReference ref, CompleteArgument argument, CompleteContext context) {
			this(ref, argument, null, context);
		}

		public CompleteRequest(CompleteReference ref, CompleteArgument argument) {
			this(ref, argument, null, null);
		}

		/**
		 * The argument's information for completion requests.
		 *
		 * @param name The name of the argument
		 * @param value The value of the argument to use for completion matching
		 */public static final class CompleteArgument {
		@JsonProperty("name")
		private final String name;
		@JsonProperty("value")
		private final String value;

		public CompleteArgument(@JsonProperty("name") String name, @JsonProperty("value") String value) {
			this.name = name;
			this.value = value;
		}

		public String name() { return this.name; }

		public String value() { return this.value; }

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (!(o instanceof CompleteArgument)) return false;
			CompleteArgument that = (CompleteArgument) o;
			return Objects.equals(name, that.name()) && Objects.equals(value, that.value());
		}

		@Override
		public int hashCode() { return Objects.hash(name, value); }

		@Override
		public String toString() {
			return "CompleteArgument[name=" + name + ", value=" + value + "]";
		}
	}

		/**
		 * Additional, optional context for completions.
		 *
		 * @param arguments Previously-resolved variables in a URI template or prompt
		 */public static final class CompleteContext {
		@JsonProperty("arguments")
		private final Map<String, String> arguments;

		public CompleteContext(@JsonProperty("arguments") Map<String, String> arguments) {
			this.arguments = arguments;
		}

		public Map<String, String> arguments() { return this.arguments; }

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (!(o instanceof CompleteContext)) return false;
			CompleteContext that = (CompleteContext) o;
			return Objects.equals(arguments, that.arguments());
		}

		@Override
		public int hashCode() { return Objects.hash(arguments); }

		@Override
		public String toString() {
			return "CompleteContext[arguments=" + arguments + "]";
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

		public CompleteResult(@JsonProperty("completion") CompleteCompletion completion, @JsonProperty("_meta") Map<String, Object> meta) {
			this.completion = completion;
			this.meta = meta;
		}

		public CompleteCompletion completion() { return this.completion; }

		public Map<String, Object> meta() { return this.meta; }

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (!(o instanceof CompleteResult)) return false;
			CompleteResult that = (CompleteResult) o;
			return Objects.equals(completion, that.completion()) && Objects.equals(meta, that.meta());
		}

		@Override
		public int hashCode() { return Objects.hash(completion, meta); }

		@Override
		public String toString() {
			return "CompleteResult[completion=" + completion + ", meta=" + meta + "]";
		}

// @formatter:on

		// backwards compatibility constructor
		public CompleteResult(CompleteCompletion completion) {
			this(completion, null);
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
		@JsonInclude(JsonInclude.Include.ALWAYS)
		public static final class CompleteCompletion {
		@JsonProperty("values")
		private final List<String> values;
		@JsonProperty("total")
		private final Integer total;
		@JsonProperty("hasMore")
		private final Boolean hasMore;

		public CompleteCompletion(@JsonProperty("values") List<String> values, @JsonProperty("total") Integer total, @JsonProperty("hasMore") Boolean hasMore) {
			this.values = values;
			this.total = total;
			this.hasMore = hasMore;
		}

		public List<String> values() { return this.values; }

		public Integer total() { return this.total; }

		public Boolean hasMore() { return this.hasMore; }

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (!(o instanceof CompleteCompletion)) return false;
			CompleteCompletion that = (CompleteCompletion) o;
			return Objects.equals(values, that.values()) && Objects.equals(total, that.total()) && Objects.equals(hasMore, that.hasMore());
		}

		@Override
		public int hashCode() { return Objects.hash(values, total, hasMore); }

		@Override
		public String toString() {
			return "CompleteCompletion[values=" + values + ", total=" + total + ", hasMore=" + hasMore + "]";
		}

// @formatter:on
	}
	}

	// ---------------------------
	// Content Types
	// ---------------------------
	@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
	@JsonSubTypes({ @JsonSubTypes.Type(value = TextContent.class, name = "text"),
			@JsonSubTypes.Type(value = ImageContent.class, name = "image"),
			@JsonSubTypes.Type(value = AudioContent.class, name = "audio"),
			@JsonSubTypes.Type(value = EmbeddedResource.class, name = "resource"),
			@JsonSubTypes.Type(value = ResourceLink.class, name = "resource_link") })
	public interface Content extends Meta {

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

		public TextContent(@JsonProperty("annotations") Annotations annotations, @JsonProperty("text") String text, @JsonProperty("_meta") Map<String, Object> meta) {
			this.annotations = annotations;
			this.text = text;
			this.meta = meta;
		}

		public Annotations annotations() { return this.annotations; }

		public String text() { return this.text; }

		public Map<String, Object> meta() { return this.meta; }

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (!(o instanceof TextContent)) return false;
			TextContent that = (TextContent) o;
			return Objects.equals(annotations, that.annotations()) && Objects.equals(text, that.text()) && Objects.equals(meta, that.meta());
		}

		@Override
		public int hashCode() { return Objects.hash(annotations, text, meta); }

		@Override
		public String toString() {
			return "TextContent[annotations=" + annotations + ", text=" + text + ", meta=" + meta + "]";
		}

// @formatter:on

		public TextContent(Annotations annotations, String text) {
			this(annotations, text, null);
		}

		public TextContent(String content) {
			this(null, content, null);
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

		public ImageContent(@JsonProperty("annotations") Annotations annotations, @JsonProperty("data") String data, @JsonProperty("mimeType") String mimeType, @JsonProperty("_meta") Map<String, Object> meta) {
			this.annotations = annotations;
			this.data = data;
			this.mimeType = mimeType;
			this.meta = meta;
		}

		public Annotations annotations() { return this.annotations; }

		public String data() { return this.data; }

		public String mimeType() { return this.mimeType; }

		public Map<String, Object> meta() { return this.meta; }

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (!(o instanceof ImageContent)) return false;
			ImageContent that = (ImageContent) o;
			return Objects.equals(annotations, that.annotations()) && Objects.equals(data, that.data()) && Objects.equals(mimeType, that.mimeType()) && Objects.equals(meta, that.meta());
		}

		@Override
		public int hashCode() { return Objects.hash(annotations, data, mimeType, meta); }

		@Override
		public String toString() {
			return "ImageContent[annotations=" + annotations + ", data=" + data + ", mimeType=" + mimeType + ", meta=" + meta + "]";
		}

// @formatter:on

		public ImageContent(Annotations annotations, String data, String mimeType) {
			this(annotations, data, mimeType, null);
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

		public AudioContent(@JsonProperty("annotations") Annotations annotations, @JsonProperty("data") String data, @JsonProperty("mimeType") String mimeType, @JsonProperty("_meta") Map<String, Object> meta) {
			this.annotations = annotations;
			this.data = data;
			this.mimeType = mimeType;
			this.meta = meta;
		}

		public Annotations annotations() { return this.annotations; }

		public String data() { return this.data; }

		public String mimeType() { return this.mimeType; }

		public Map<String, Object> meta() { return this.meta; }

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (!(o instanceof AudioContent)) return false;
			AudioContent that = (AudioContent) o;
			return Objects.equals(annotations, that.annotations()) && Objects.equals(data, that.data()) && Objects.equals(mimeType, that.mimeType()) && Objects.equals(meta, that.meta());
		}

		@Override
		public int hashCode() { return Objects.hash(annotations, data, mimeType, meta); }

		@Override
		public String toString() {
			return "AudioContent[annotations=" + annotations + ", data=" + data + ", mimeType=" + mimeType + ", meta=" + meta + "]";
		}

// @formatter:on

		// backwards compatibility constructor
		public AudioContent(Annotations annotations, String data, String mimeType) {
			this(annotations, data, mimeType, null);
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

		public EmbeddedResource(@JsonProperty("annotations") Annotations annotations, @JsonProperty("resource") ResourceContents resource, @JsonProperty("_meta") Map<String, Object> meta) {
			this.annotations = annotations;
			this.resource = resource;
			this.meta = meta;
		}

		public Annotations annotations() { return this.annotations; }

		public ResourceContents resource() { return this.resource; }

		public Map<String, Object> meta() { return this.meta; }

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (!(o instanceof EmbeddedResource)) return false;
			EmbeddedResource that = (EmbeddedResource) o;
			return Objects.equals(annotations, that.annotations()) && Objects.equals(resource, that.resource()) && Objects.equals(meta, that.meta());
		}

		@Override
		public int hashCode() { return Objects.hash(annotations, resource, meta); }

		@Override
		public String toString() {
			return "EmbeddedResource[annotations=" + annotations + ", resource=" + resource + ", meta=" + meta + "]";
		}

// @formatter:on

		// backwards compatibility constructor
		public EmbeddedResource(Annotations annotations, ResourceContents resource) {
			this(annotations, resource, null);
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
	public static final class ResourceLink implements Content {
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

		public ResourceLink(@JsonProperty("name") String name, @JsonProperty("title") String title, @JsonProperty("uri") String uri, @JsonProperty("description") String description, @JsonProperty("mimeType") String mimeType, @JsonProperty("size") Long size, @JsonProperty("annotations") Annotations annotations, @JsonProperty("_meta") Map<String, Object> meta) {
			this.name = name;
			this.title = title;
			this.uri = uri;
			this.description = description;
			this.mimeType = mimeType;
			this.size = size;
			this.annotations = annotations;
			this.meta = meta;
		}

		public String name() { return this.name; }

		public String title() { return this.title; }

		public String uri() { return this.uri; }

		public String description() { return this.description; }

		public String mimeType() { return this.mimeType; }

		public Long size() { return this.size; }

		public Annotations annotations() { return this.annotations; }

		public Map<String, Object> meta() { return this.meta; }

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (!(o instanceof ResourceLink)) return false;
			ResourceLink that = (ResourceLink) o;
			return Objects.equals(name, that.name()) && Objects.equals(title, that.title()) && Objects.equals(uri, that.uri()) && Objects.equals(description, that.description()) && Objects.equals(mimeType, that.mimeType()) && Objects.equals(size, that.size()) && Objects.equals(annotations, that.annotations()) && Objects.equals(meta, that.meta());
		}

		@Override
		public int hashCode() { return Objects.hash(name, title, uri, description, mimeType, size, annotations, meta); }

		@Override
		public String toString() {
			return "ResourceLink[name=" + name + ", title=" + title + ", uri=" + uri + ", description=" + description + ", mimeType=" + mimeType + ", size=" + size + ", annotations=" + annotations + ", meta=" + meta + "]";
		}

// @formatter:on

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

		public Root(@JsonProperty("uri") String uri, @JsonProperty("name") String name, @JsonProperty("_meta") Map<String, Object> meta) {
			this.uri = uri;
			this.name = name;
			this.meta = meta;
		}

		public String uri() { return this.uri; }

		public String name() { return this.name; }

		public Map<String, Object> meta() { return this.meta; }

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (!(o instanceof Root)) return false;
			Root that = (Root) o;
			return Objects.equals(uri, that.uri()) && Objects.equals(name, that.name()) && Objects.equals(meta, that.meta());
		}

		@Override
		public int hashCode() { return Objects.hash(uri, name, meta); }

		@Override
		public String toString() {
			return "Root[uri=" + uri + ", name=" + name + ", meta=" + meta + "]";
		}

// @formatter:on

		public Root(String uri, String name) {
			this(uri, name, null);
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

		public ListRootsResult(@JsonProperty("roots") List<Root> roots, @JsonProperty("nextCursor") String nextCursor, @JsonProperty("_meta") Map<String, Object> meta) {
			this.roots = roots;
			this.nextCursor = nextCursor;
			this.meta = meta;
		}

		public List<Root> roots() { return this.roots; }

		public String nextCursor() { return this.nextCursor; }

		public Map<String, Object> meta() { return this.meta; }

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (!(o instanceof ListRootsResult)) return false;
			ListRootsResult that = (ListRootsResult) o;
			return Objects.equals(roots, that.roots()) && Objects.equals(nextCursor, that.nextCursor()) && Objects.equals(meta, that.meta());
		}

		@Override
		public int hashCode() { return Objects.hash(roots, nextCursor, meta); }

		@Override
		public String toString() {
			return "ListRootsResult[roots=" + roots + ", nextCursor=" + nextCursor + ", meta=" + meta + "]";
		}

// @formatter:on

		public ListRootsResult(List<Root> roots) {
			this(roots, null);
		}

		public ListRootsResult(List<Root> roots, String nextCursor) {
			this(roots, nextCursor, null);
		}
	}

}
