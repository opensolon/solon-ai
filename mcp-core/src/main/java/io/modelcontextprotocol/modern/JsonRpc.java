/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.modern;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.util.Assert;
import io.modelcontextprotocol.util.SelfValidating;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Map;
import java.util.Objects;

/**
 * The <a href="https://www.jsonrpc.org/specification">JSON-RPC 2.0</a> envelope that
 * carries {@link McpSchema} payloads, with MCP's restrictions on request ids.
 *
 * @author Dariusz Jędrzejczyk
 */
public final class JsonRpc {

	private static final Logger logger = LoggerFactory.getLogger(JsonRpc.class);

	public static final String JSONRPC_VERSION = "2.0";

	private JsonRpc() {
	}

	/**
	 * Parses a JSON-RPC message, picking the concrete type from the fields present.
	 * @throws IOException if {@code jsonText} is not valid JSON
	 * @throws InvalidMessageException if it is not a valid request, notification or
	 * response
	 */
	public static JSONRPCMessage deserializeMessage(McpJsonMapper jsonMapper, String jsonText) throws IOException {
		logger.debug("Received JSON message: {}", jsonText);

		// Read untyped first: valid JSON that is not an object (null, an array, a
		// scalar) is an Invalid Request, not a Parse error.
		Object parsed = jsonMapper.readValue(jsonText, Object.class);
		if (!(parsed instanceof Map)) {
			throw new InvalidMessageException("A JSON-RPC message must be a JSON object", null, null);
		}
		Map<?, ?> map = (Map<?, ?>) parsed;

		Class<? extends JSONRPCMessage> type;
		if (map.containsKey("method") && map.containsKey("id")) {
			type = JSONRPCRequest.class;
		}
		else if (map.containsKey("method") && !map.containsKey("id")) {
			type = JSONRPCNotification.class;
		}
		else if (map.containsKey("result") || map.containsKey("error")) {
			type = JSONRPCResponse.class;
		}
		else {
			throw new InvalidMessageException("Cannot deserialize JSONRPCMessage: " + jsonText, null, null);
		}
		try {
			JSONRPCMessage decoded = jsonMapper.convertValue(map, type);
			// Mappers that bypass constructors (e.g. Gson instantiates via Unsafe) skip the
			// canonical constructor's validation, so replay it here.
			if (decoded instanceof SelfValidating) {
				((SelfValidating) decoded).validate();
			}
			return decoded;
		}
		catch (RuntimeException ex) {
			// Mappers report envelope violations (e.g. a null id) with their own
			// exception types; normalize so callers can answer with Invalid Request.
			// Only a request's id is kept: the error answers that request.
			Object id = type == JSONRPCRequest.class ? map.get("id") : null;
			throw new InvalidMessageException("Invalid " + type.getSimpleName() + ": " + ex.getMessage(),
					isValidId(id) ? id : null, ex);
		}
	}

	private static boolean isValidId(Object id) {
		return id instanceof String || id instanceof Integer || id instanceof Long;
	}

	/**
	 * Thrown when JSON is not a valid JSON-RPC message.
	 */
	public static final class InvalidMessageException extends IllegalArgumentException {

		private final transient Object id;

		InvalidMessageException(String message, Object id, Throwable cause) {
			super(message, cause);
			this.id = id;
		}

		/**
		 * The id of the invalid request, or {@code null} if it has none or it is not a
		 * valid id.
		 */
		public Object id() {
			return this.id;
		}

	}

	public interface JSONRPCMessage {

		String jsonrpc();

	}

	/**
	 * A request that expects a response. MCP requires a non-null string or integer
	 * {@code id}.
	 */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static final class JSONRPCRequest implements JSONRPCMessage, SelfValidating {

		@JsonProperty("jsonrpc")
		private final String jsonrpc;

		@JsonProperty("method")
		private final String method;

		@JsonProperty("id")
		private final Object id;

		@JsonProperty("params")
		private final Object params;

		@JsonCreator
		public JSONRPCRequest( // @formatter:off
				@JsonProperty("jsonrpc") String jsonrpc,
				@JsonProperty("method") String method,
				@JsonProperty("id") Object id,
				@JsonProperty("params") Object params) { // @formatter:on
			Assert.isTrue(JSONRPC_VERSION.equals(jsonrpc), "jsonrpc must be \"" + JSONRPC_VERSION + "\"");
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

		@Override
		public void validate() {
			Assert.isTrue(JSONRPC_VERSION.equals(this.jsonrpc), "jsonrpc must be \"" + JSONRPC_VERSION + "\"");
			Assert.notNull(this.id, "MCP requests MUST include an ID - null IDs are not allowed");
			Assert.isTrue(this.id instanceof String || this.id instanceof Integer || this.id instanceof Long,
					"MCP requests MUST have an ID that is either a string or integer");
			Assert.notNull(this.method, "MCP request method must not be null");
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

	/** A notification, which never receives a response. */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static final class JSONRPCNotification implements JSONRPCMessage, SelfValidating {

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
			Assert.isTrue(JSONRPC_VERSION.equals(jsonrpc), "jsonrpc must be \"" + JSONRPC_VERSION + "\"");
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

		@Override
		public void validate() {
			Assert.isTrue(JSONRPC_VERSION.equals(this.jsonrpc), "jsonrpc must be \"" + JSONRPC_VERSION + "\"");
			Assert.notNull(this.method, "MCP notification method must not be null");
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
	 * A response to a request, carrying exactly one of {@code result} or {@code error}.
	 * An error response has no {@code id} when the request's id could not be determined.
	 */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static final class JSONRPCResponse implements JSONRPCMessage, SelfValidating {

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
			Assert.isTrue(JSONRPC_VERSION.equals(jsonrpc), "jsonrpc must be \"" + JSONRPC_VERSION + "\"");
			Assert.isTrue((result != null) ^ (error != null), "MCP responses MUST either have a result or error");
			Assert.isTrue(id != null || error != null, "MCP result responses MUST include an ID");
			Assert.isTrue(id == null || id instanceof String || id instanceof Integer || id instanceof Long,
					"MCP responses MUST have an ID that is either a string or integer");
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

		@Override
		public void validate() {
			Assert.isTrue(JSONRPC_VERSION.equals(this.jsonrpc), "jsonrpc must be \"" + JSONRPC_VERSION + "\"");
			Assert.isTrue((this.result != null) ^ (this.error != null), "MCP responses MUST either have a result or error");
			Assert.isTrue(this.id != null || this.error != null, "MCP result responses MUST include an ID");
			Assert.isTrue(this.id == null || this.id instanceof String || this.id instanceof Integer
					|| this.id instanceof Long, "MCP responses MUST have an ID that is either a string or integer");
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

}
