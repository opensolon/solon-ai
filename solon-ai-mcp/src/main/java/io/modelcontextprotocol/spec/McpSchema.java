/*
 * Copyright 2024-2024 the original author or authors.
 */

package io.modelcontextprotocol.spec;

import com.fasterxml.jackson.annotation.*;
import com.fasterxml.jackson.annotation.JsonTypeInfo.As;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.util.Assert;
import io.modelcontextprotocol.util.Utils;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.var;
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

	@Deprecated
	public static final String LATEST_PROTOCOL_VERSION = ProtocolVersions.MCP_2025_03_26;

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

	private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

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

	}

	public interface Request {
		Map<String, Object> getMeta();

		default String progressToken() {
			if (getMeta() != null && getMeta().containsKey("progressToken")) {
				return getMeta().get("progressToken").toString();
			}
			return null;
		}
	}

	public interface Result {
		Map<String, Object> getMeta();
	}

	public interface Notification {
		Map<String, Object> getMeta();
	}

	private static final TypeReference<HashMap<String, Object>> MAP_TYPE_REF = new TypeReference<HashMap<String, Object>>() {
	};

	/**
	 * Deserializes a JSON string into a JSONRPCMessage object.
	 * @param objectMapper The ObjectMapper instance to use for deserialization
	 * @param jsonText The JSON string to deserialize
	 * @return A JSONRPCMessage instance using either the {@link JSONRPCRequest},
	 * {@link JSONRPCNotification}, or {@link JSONRPCResponse} classes.
	 * @throws IOException If there's an error during deserialization
	 * @throws IllegalArgumentException If the JSON structure doesn't match any known
	 * message type
	 */
	public static JSONRPCMessage deserializeJsonRpcMessage(ObjectMapper objectMapper, String jsonText)
			throws IOException {

		logger.debug("Received JSON message: {}", jsonText);

		var map = objectMapper.readValue(jsonText, MAP_TYPE_REF);

		// Determine message type based on specific JSON structure
		if (map.containsKey("method") && map.containsKey("id")) {
			return objectMapper.convertValue(map, JSONRPCRequest.class);
		}
		else if (map.containsKey("method") && !map.containsKey("id")) {
			return objectMapper.convertValue(map, JSONRPCNotification.class);
		}
		else if (map.containsKey("result") || map.containsKey("error")) {
			return objectMapper.convertValue(map, JSONRPCResponse.class);
		}

		throw new IllegalArgumentException("Cannot deserialize JSONRPCMessage: " + jsonText);
	}

	// ---------------------------
	// JSON-RPC Message Types
	// ---------------------------
	public interface JSONRPCMessage {
		String getJsonrpc();
	}

	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	@Data
	@AllArgsConstructor
	@NoArgsConstructor
	public static class JSONRPCRequest implements JSONRPCMessage {
		@JsonProperty("jsonrpc") String jsonrpc;
		@JsonProperty("method") String method;
		@JsonProperty("id") Object id;
		@JsonProperty("params") Object params;
	} // @formatter:on

	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	@Data
	@AllArgsConstructor
	@NoArgsConstructor
	public static class JSONRPCNotification implements JSONRPCMessage {
		@JsonProperty("jsonrpc") String jsonrpc;
		@JsonProperty("method") String method;
		@JsonProperty("params") Object params;
	} // @formatter:on

	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	@Data
	@AllArgsConstructor
	@NoArgsConstructor
	public static class JSONRPCResponse implements JSONRPCMessage {
		@JsonProperty("jsonrpc") String jsonrpc;
		@JsonProperty("id") Object id;
		@JsonProperty("result") Object result;
		@JsonProperty("error") JSONRPCError error;

		@JsonInclude(JsonInclude.Include.NON_ABSENT)
		@JsonIgnoreProperties(ignoreUnknown = true)
		@Data
		@AllArgsConstructor
		@NoArgsConstructor
		public static class JSONRPCError {
			@JsonProperty("code") int code;
			@JsonProperty("message") String message;
			@JsonProperty("data") Object data;
		}
	}// @formatter:on

	// ---------------------------
	// Initialization
	// ---------------------------
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	@Data
	@AllArgsConstructor
	@NoArgsConstructor
	public static class InitializeRequest implements Request {
		@JsonProperty("protocolVersion") String protocolVersion;
		@JsonProperty("capabilities") ClientCapabilities capabilities;
		@JsonProperty("clientInfo") Implementation clientInfo;
		@JsonProperty("_meta") Map<String, Object> meta;

		public InitializeRequest(String protocolVersion, ClientCapabilities capabilities, Implementation clientInfo) {
			this(protocolVersion, capabilities, clientInfo, null);
		}
	} // @formatter:on

	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	@Data
	@AllArgsConstructor
	@NoArgsConstructor
	public static class InitializeResult implements Result{
		@JsonProperty("protocolVersion") String protocolVersion;
		@JsonProperty("capabilities") ServerCapabilities capabilities;
		@JsonProperty("serverInfo") Implementation serverInfo;
		@JsonProperty("instructions") String instructions;
		@JsonProperty("_meta") Map<String, Object> meta;

		public InitializeResult(String protocolVersion, ServerCapabilities capabilities, Implementation serverInfo,
								String instructions) {
			this(protocolVersion, capabilities, serverInfo, instructions, null);
		}
	} // @formatter:on

	/**
	 * Clients can implement additional features to enrich connected MCP servers with
	 * additional capabilities. These capabilities can be used to extend the functionality
	 * of the server, or to provide additional information to the server about the
	 * client's capabilities.
	 *
	 *
	 */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	@Data
	@AllArgsConstructor
	@NoArgsConstructor
	public static class ClientCapabilities {
		@JsonProperty("experimental") Map<String, Object> experimental;
		@JsonProperty("roots") RootCapabilities roots;
		@JsonProperty("sampling") Sampling sampling;
		@JsonProperty("elicitation") Elicitation elicitation;

		/**
		 * Roots define the boundaries of where servers can operate within the filesystem,
		 * allowing them to understand which directories and files they have access to.
		 * Servers can request the list of roots from supporting clients and
		 * receive notifications when that list changes.
		 *
		 */
		@JsonInclude(JsonInclude.Include.NON_ABSENT)
		@JsonIgnoreProperties(ignoreUnknown = true)
		@Data
		@AllArgsConstructor
		@NoArgsConstructor
		public static class RootCapabilities {
			@JsonProperty("listChanged") Boolean listChanged;
		}

		/**
		 * Provides a standardized way for servers to request LLM
	 	 * sampling ("completions" or "generations") from language
		 * models via clients. This flow allows clients to maintain
		 * control over model access, selection, and permissions
		 * while enabling servers to leverage AI capabilities—with
		 * no server API keys necessary. Servers can request text or
		 * image-based interactions and optionally include context
		 * from MCP servers in their prompts.
		 */
		@JsonInclude(JsonInclude.Include.NON_ABSENT)
		public static class Sampling {
		}

		@JsonInclude(JsonInclude.Include.NON_ABSENT)
		public static class  Elicitation{

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

			public Builder elicitation() {
				this.elicitation = new Elicitation();
				return this;
			}

			public ClientCapabilities build() {
				return new ClientCapabilities(experimental, roots, sampling, elicitation);
			}
		}
	}// @formatter:on

	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	@Data
	@AllArgsConstructor
	@NoArgsConstructor
	public static class ServerCapabilities {
		@JsonProperty("completions") CompletionCapabilities completions;
		@JsonProperty("experimental") Map<String, Object> experimental;
		@JsonProperty("logging") LoggingCapabilities logging;
		@JsonProperty("prompts") PromptCapabilities prompts;
		@JsonProperty("resources") ResourceCapabilities resources;
		@JsonProperty("tools") ToolCapabilities tools;

		/**
		 * Present if the server supports argument autocompletion suggestions.
		 */
		@JsonInclude(JsonInclude.Include.NON_ABSENT)
		public static class CompletionCapabilities {
		}

		/**
		 * Present if the server supports sending log messages to the client.
		 */
		@JsonInclude(JsonInclude.Include.NON_ABSENT)
		public static class LoggingCapabilities {
		}

		/**
		 * Present if the server offers any prompt templates.
		 * the prompt list
		 */
		@JsonInclude(JsonInclude.Include.NON_ABSENT)
        @JsonIgnoreProperties(ignoreUnknown = true)
		@Data
		@AllArgsConstructor
		@NoArgsConstructor
		public static class PromptCapabilities {
			/**
			 * Whether this server supports notifications for changes to
			 * */
			@JsonProperty("listChanged") Boolean listChanged;
		}

		@JsonInclude(JsonInclude.Include.NON_ABSENT)
        @JsonIgnoreProperties(ignoreUnknown = true)
		@Data
		@AllArgsConstructor
		@NoArgsConstructor
		public static class ResourceCapabilities {
			@JsonProperty("subscribe") Boolean subscribe;
			@JsonProperty("listChanged") Boolean listChanged;
		}

		@JsonInclude(JsonInclude.Include.NON_ABSENT)
        @JsonIgnoreProperties(ignoreUnknown = true)
		@Data
		@AllArgsConstructor
		@NoArgsConstructor
		public static class ToolCapabilities {
			@JsonProperty("listChanged") Boolean listChanged;
		}

        /**
         * Create a mutated copy of this object with the specified changes.
         * @return A new Builder instance with the same values as this object.
         */
        public Builder mutate() {
            var builder = new Builder();
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
			private LoggingCapabilities logging = new LoggingCapabilities();
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
	} // @formatter:on

	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	@Data
	@AllArgsConstructor
	@NoArgsConstructor
	public static class Implementation implements BaseMetadata{
		@JsonProperty("name") String name;
		@JsonProperty("title") String title;
		@JsonProperty("version") String version;

		public Implementation(String name, String version) {
			this(name, null, version);
		}
	} // @formatter:on

	// Existing Enums and Base Types (from previous implementation)
	public enum Role {// @formatter:off

		@JsonProperty("user") USER,
		@JsonProperty("assistant") ASSISTANT
	}// @formatter:on

	// ---------------------------
	// Resource Interfaces
	// ---------------------------
	/**
	 * Base for objects that include optional annotations for the client. The client can
	 * use annotations to inform how objects are used or displayed
	 */
	public interface Annotated {

		Annotations getAnnotations();

	}

	/**
	 * Optional annotations for the client. The client can use annotations to inform how
	 * objects are used or displayed.
	 */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	@Data
	@AllArgsConstructor
	@NoArgsConstructor
	public static class Annotations {
		@JsonProperty("audience") List<Role> audience;
		@JsonProperty("priority") Double priority;
	} // @formatter:on


	/**
	 * A common interface for resource content, which includes metadata about the resource
	 * such as its URI, name, description, MIME type, size, and annotations. This
	 * interface is implemented by both {@link Resource} and {@link ResourceLink} to
	 * provide a consistent way to access resource metadata.
	 */
	public interface ResourceContent extends BaseMetadata {
		String getUri();

		String getDescription();

		String getMimeType();

		Long getSize();

		Annotations getAnnotations();

	}

	/**
	 * Base interface for metadata with name (identifier) and title (display name)
	 * properties.
	 */
	public interface BaseMetadata {

		/**
		 * Intended for programmatic or logical use, but used as a display name in past
		 * specs or fallback (if title isn't present).
		 */
		String getName();

		/**
		 * Intended for UI and end-user contexts — optimized to be human-readable and
		 * easily understood, even by those unfamiliar with domain-specific terminology.
		 *
		 * If not provided, the name should be used for display.
		 */
		String getTitle();

	}

	/**
	 * A known resource that the server is capable of reading.
	 */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	@Data
	@AllArgsConstructor
	@NoArgsConstructor
	public static class Resource implements Annotated, ResourceContent {
		@JsonProperty("uri")
		String uri;
		@JsonProperty("name")
		String name;
		@JsonProperty("title")
		String title;
		@JsonProperty("description")
		String description;
		@JsonProperty("mimeType")
		String mimeType;
		@JsonProperty("size")
		Long size;
		@JsonProperty("annotations")
		Annotations annotations;
		@JsonProperty("_meta")
		Map<String, Object> meta;

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
	} // @formatter:on

	/**
	 * Resource templates allow servers to expose parameterized resources using URI
	 * templates.
     * @see <a href="https://datatracker.ietf.org/doc/html/rfc6570">RFC 6570</a>
     */
		@JsonInclude(JsonInclude.Include.NON_ABSENT)
		@JsonIgnoreProperties(ignoreUnknown = true)
		@Data
		@AllArgsConstructor
		@NoArgsConstructor
		public static class ResourceTemplate implements Annotated, BaseMetadata {
			@JsonProperty("uriTemplate")
			String uriTemplate;
			@JsonProperty("name")
			String name;
			@JsonProperty("title")
			String title;
			@JsonProperty("description")
			String description;
			@JsonProperty("mimeType")
			String mimeType;
			@JsonProperty("annotations")
			Annotations annotations;
			@JsonProperty("_meta")
			Map<String, Object> meta;

			public ResourceTemplate(String uriTemplate, String name, String title, String description, String mimeType,
									Annotations annotations) {
				this(uriTemplate, name, title, description, mimeType, annotations, null);
			}

			public ResourceTemplate(String uriTemplate, String name, String description, String mimeType,
									Annotations annotations) {
				this(uriTemplate, name, null, description, mimeType, annotations);
			}
		} // @formatter:on

		@JsonInclude(JsonInclude.Include.NON_ABSENT)
		@JsonIgnoreProperties(ignoreUnknown = true)
		@Data
		@AllArgsConstructor
		@NoArgsConstructor
		public static class ListResourcesResult implements Result {
			@JsonProperty("resources")
			List<Resource> resources;
			@JsonProperty("nextCursor")
			String nextCursor;
			@JsonProperty("_meta")
			Map<String, Object> meta;

			public ListResourcesResult(List<Resource> resources, String nextCursor) {
				this(resources, nextCursor, null);
			}
		} // @formatter:on

		@JsonInclude(JsonInclude.Include.NON_ABSENT)
		@JsonIgnoreProperties(ignoreUnknown = true)
		@Data
		@AllArgsConstructor
		@NoArgsConstructor
		public static class ListResourceTemplatesResult implements Result {
			@JsonProperty("resourceTemplates")
			List<ResourceTemplate> resourceTemplates;
			@JsonProperty("nextCursor")
			String nextCursor;
			@JsonProperty("_meta")
			Map<String, Object> meta;

			public ListResourceTemplatesResult(List<ResourceTemplate> resourceTemplates, String nextCursor) {
				this(resourceTemplates, nextCursor, null);
			}
		} // @formatter:on

		@JsonInclude(JsonInclude.Include.NON_ABSENT)
		@JsonIgnoreProperties(ignoreUnknown = true)
		@Data
		@AllArgsConstructor
		@NoArgsConstructor
		public static class ReadResourceRequest implements Request {
			@JsonProperty("uri")
			String uri;
			@JsonProperty("_meta")
			Map<String, Object> meta;

			public ReadResourceRequest(String uri) {
				this(uri, null);
			}
		} // @formatter:on

		@JsonInclude(JsonInclude.Include.NON_ABSENT)
		@JsonIgnoreProperties(ignoreUnknown = true)
		@Data
		@AllArgsConstructor
		@NoArgsConstructor
		public static class ReadResourceResult implements Result {
			@JsonProperty("contents")
			List<ResourceContents> contents;
			@JsonProperty("_meta")
			Map<String, Object> meta;

			public ReadResourceResult(List<ResourceContents> contents) {
				this(contents, null);
			}
		} // @formatter:on

		/**
     * Sent from the client to request resources/updated notifications from the server
     * whenever a particular resource changes.
     */
		@JsonInclude(JsonInclude.Include.NON_ABSENT)
		@JsonIgnoreProperties(ignoreUnknown = true)
		@Data
		@AllArgsConstructor
		@NoArgsConstructor
		public static class SubscribeRequest implements Request {
			@JsonProperty("uri")
			String uri;
			@JsonProperty("_meta")
			Map<String, Object> meta;

			public SubscribeRequest(String uri) {
				this(uri, null);
			}
		} // @formatter:on

		@JsonInclude(JsonInclude.Include.NON_ABSENT)
		@JsonIgnoreProperties(ignoreUnknown = true)
		@Data
		@AllArgsConstructor
		@NoArgsConstructor
		public static class UnsubscribeRequest implements Request {
			@JsonProperty("uri")
			String uri;
			@JsonProperty("_meta")
			Map<String, Object> meta;

			public UnsubscribeRequest(String uri) {
				this(uri, null);
			}
		} // @formatter:on

		/**
     * The contents of a specific resource or sub-resource.
     */
		@JsonTypeInfo(use = JsonTypeInfo.Id.DEDUCTION, include = As.PROPERTY)
		@JsonSubTypes({@JsonSubTypes.Type(value = TextResourceContents.class, name = "text"),
				@JsonSubTypes.Type(value = BlobResourceContents.class, name = "blob")})
		public interface ResourceContents {

			/**
         * The URI of this resource.
         *
         * @return the URI of this resource.
         */
			String getUri();

			/**
         * The MIME type of this resource.
         *
         * @return the MIME type of this resource.
         */
			String getMimeType();

			/**
         * @return additional metadata related to this resource.
         * @see <a href=
         * "https://modelcontextprotocol.io/specification/2025-06-18/basic/index#meta">Specification</a>
         * for notes on _meta usage
         */
			Map<String, Object> getMeta();
		}

		/**
     * Text contents of a resource.
     */
		@JsonInclude(JsonInclude.Include.NON_ABSENT)
		@JsonIgnoreProperties(ignoreUnknown = true)
		@Data
		@AllArgsConstructor
		@NoArgsConstructor
		public static class TextResourceContents implements ResourceContents {
			@JsonProperty("uri")
			String uri;
			@JsonProperty("mimeType")
			String mimeType;
			@JsonProperty("text")
			String text;
			@JsonProperty("_meta")
			Map<String, Object> meta;

			public TextResourceContents(String uri, String mimeType, String text) {
				this(uri, mimeType, text, null);
			}
		} // @formatter:on

		/**
		 * Binary contents of a resource.
		 */
		@JsonInclude(JsonInclude.Include.NON_ABSENT)
		@JsonIgnoreProperties(ignoreUnknown = true)
		@Data
		@AllArgsConstructor
		@NoArgsConstructor
		public static class BlobResourceContents implements ResourceContents {
			@JsonProperty("uri")
			String uri;
			@JsonProperty("mimeType")
			String mimeType;
			@JsonProperty("blob")
			String blob;
			@JsonProperty("_meta")
			Map<String, Object> meta;

			public BlobResourceContents(String uri, String mimeType, String blob) {
				this(uri, mimeType, blob, null);
			}
		} // @formatter:on

		// ---------------------------
		// Prompt Interfaces
		// ---------------------------

		/**
     * A prompt or prompt template that the server offers.
     */
		@JsonInclude(JsonInclude.Include.NON_ABSENT)
		@JsonIgnoreProperties(ignoreUnknown = true)
		@Data
		@AllArgsConstructor
		@NoArgsConstructor
		public static class Prompt implements BaseMetadata {
			@JsonProperty("name")
			String name;
			@JsonProperty("title")
			String title;
			@JsonProperty("description")
			String description;
			@JsonProperty("arguments")
			List<PromptArgument> arguments;
			@JsonProperty("_meta")
			Map<String, Object> meta;

			public Prompt(String name, String description, List<PromptArgument> arguments) {
				this(name, null, description, arguments != null ? arguments : new ArrayList<>());
			}

			public Prompt(String name, String title, String description, List<PromptArgument> arguments) {
				this(name, title, description, arguments != null ? arguments : new ArrayList<>(), null);
			}
		} // @formatter:on

		/**
		 * Describes an argument that a prompt can accept.
		 */
		@JsonInclude(JsonInclude.Include.NON_ABSENT)
		@JsonIgnoreProperties(ignoreUnknown = true)
		@Data
		@AllArgsConstructor
		@NoArgsConstructor
		public static class PromptArgument implements BaseMetadata {
			@JsonProperty("name")
			String name;
			@JsonProperty("title")
			String title;
			@JsonProperty("description")
			String description;
			@JsonProperty("required")
			Boolean required;

			public PromptArgument(String name, String description, Boolean required) {
				this(name, null, description, required);
			}
		}// @formatter:on

		/**
     * Describes a message returned as part of a prompt.
     * <p>
     * This is similar to `SamplingMessage`, but also supports the embedding of resources
     * from the MCP server.
     */
		@JsonInclude(JsonInclude.Include.NON_ABSENT)
		@JsonIgnoreProperties(ignoreUnknown = true)
		@Data
		@AllArgsConstructor
		@NoArgsConstructor
		public static class PromptMessage {
			@JsonProperty("role")
			Role role;
			@JsonProperty("content")
			Content content;
		} // @formatter:on

		/**
		 * The server's response to a prompts/list request from the client.
		 */
		@JsonInclude(JsonInclude.Include.NON_ABSENT)
		@JsonIgnoreProperties(ignoreUnknown = true)
		@Data
		@AllArgsConstructor
		@NoArgsConstructor
		public static class ListPromptsResult implements Result {
			@JsonProperty("prompts")
			List<Prompt> prompts;
			@JsonProperty("nextCursor")
			String nextCursor;
			@JsonProperty("_meta")
			Map<String, Object> meta;

			public ListPromptsResult(List<Prompt> prompts, String nextCursor) {
				this(prompts, nextCursor, null);
			}
		}// @formatter:on

		/**
     * Used by the client to get a prompt provided by the server.
     */
		@JsonInclude(JsonInclude.Include.NON_ABSENT)
		@JsonIgnoreProperties(ignoreUnknown = true)
		@Data
		@AllArgsConstructor
		@NoArgsConstructor
		public static class GetPromptRequest implements Request {
			@JsonProperty("name")
			String name;
			@JsonProperty("arguments")
			Map<String, Object> arguments;
			@JsonProperty("_meta")
			Map<String, Object> meta;

			public GetPromptRequest(String name, Map<String, Object> arguments) {
				this(name, arguments, null);
			}
		}// @formatter:off

	/**
	 * The server's response to a prompts/get request from the client.
	 */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	@Data
	@AllArgsConstructor
	@NoArgsConstructor
	public static class GetPromptResult implements Result {
		@JsonProperty("description") String description;
		@JsonProperty("messages") List<PromptMessage> messages;
		@JsonProperty("_meta") Map<String, Object> meta;

		public GetPromptResult(String description, List<PromptMessage> messages) {
			this(description, messages, null);
		}
	} // @formatter:on

		// ---------------------------
		// Tool Interfaces
		// ---------------------------

		/**
		 * The server's response to a tools/list request from the client.
		 */
		@JsonInclude(JsonInclude.Include.NON_ABSENT)
		@JsonIgnoreProperties(ignoreUnknown = true)
		@Data
		@AllArgsConstructor
		@NoArgsConstructor
		public static class ListToolsResult implements Result {
			@JsonProperty("tools")
			List<Tool> tools;
			@JsonProperty("nextCursor")
			String nextCursor;
			@JsonProperty("_meta")
			Map<String, Object> meta;

			public ListToolsResult(List<Tool> tools, String nextCursor) {
				this(tools, nextCursor, null);
			}
		}// @formatter:on

		@JsonInclude(JsonInclude.Include.NON_ABSENT)
		@JsonIgnoreProperties(ignoreUnknown = true)
		@Data
		@AllArgsConstructor
		@NoArgsConstructor
		public static class JsonSchema {
			@JsonProperty("type")
			String type;
			@JsonProperty("properties")
			Map<String, Object> properties;
			@JsonProperty("required")
			List<String> required;
			@JsonProperty("additionalProperties")
			Boolean additionalProperties;
			@JsonProperty("$defs")
			Map<String, Object> defs;
			@JsonProperty("definitions")
			Map<String, Object> definitions;
		} // @formatter:on

		/**
		 * Additional properties describing a Tool to clients.
		 * <p>
		 * NOTE: all properties in ToolAnnotations are **hints**. They are not guaranteed to
		 * provide a faithful description of tool behavior (including descriptive properties
		 * like `title`).
		 * <p>
		 * Clients should never make tool use decisions based on ToolAnnotations received from
		 * untrusted servers.
		 */
		@JsonInclude(JsonInclude.Include.NON_ABSENT)
		@JsonIgnoreProperties(ignoreUnknown = true)
		@Data
		public static class ToolAnnotations { // @formatter:on
			@JsonProperty("title")
			String title;
			@JsonProperty("readOnlyHint")
			Boolean readOnlyHint;
			@JsonProperty("destructiveHint")
			Boolean destructiveHint;
			@JsonProperty("idempotentHint")
			Boolean idempotentHint;
			@JsonProperty("openWorldHint")
			Boolean openWorldHint;
			@JsonProperty("returnDirect")
			Boolean returnDirect;
		}

		/**
		 * Represents a tool that the server provides. Tools enable servers to expose
		 * executable functionality to the system. Through these tools, you can interact with
		 * external systems, perform computations, and take actions in the real world.
		 */
		@JsonInclude(JsonInclude.Include.NON_ABSENT)
		@JsonIgnoreProperties(ignoreUnknown = true)
		@Data
		@AllArgsConstructor
		@NoArgsConstructor
		public static class Tool {
			@JsonProperty("name")
			String name;
			@JsonProperty("title")
			String title;
			@JsonProperty("description")
			String description;
			@JsonProperty("inputSchema")
			JsonSchema inputSchema;
			@JsonProperty("outputSchema")
			Map<String, Object> outputSchema;
			@JsonProperty("annotations")
			ToolAnnotations annotations;
			@JsonProperty("_meta")
			Map<String, Object> meta;

			/**
			 * @deprecated Only exists for backwards-compatibility purposes. Use
			 * {@link Tool#builder()} instead.
			 */
			@Deprecated
			public Tool(String name, String description, JsonSchema inputSchema, ToolAnnotations annotations) {
				this(name, null, description, inputSchema, null, annotations, null);
			}

			/**
			 * @deprecated Only exists for backwards-compatibility purposes. Use
			 * {@link Tool#builder()} instead.
			 */
			@Deprecated
			public Tool(String name, String description, String inputSchema) {
				this(name, null, description, parseSchema(inputSchema), null, null, null);
			}

			/**
			 * @deprecated Only exists for backwards-compatibility purposes. Use
			 * {@link Tool#builder()} instead.
			 */
			@Deprecated
			public Tool(String name, String description, String schema, ToolAnnotations annotations) {
				this(name, null, description, parseSchema(schema), null, annotations, null);
			}

			/**
			 * @deprecated Only exists for backwards-compatibility purposes. Use
			 * {@link Tool#builder()} instead.
			 */
			@Deprecated
			public Tool(String name, String description, String inputSchema, String outputSchema,
						ToolAnnotations annotations) {
				this(name, null, description, parseSchema(inputSchema), schemaToMap(outputSchema), annotations, null);
			}

			/**
			 * @deprecated Only exists for backwards-compatibility purposes. Use
			 * {@link Tool#builder()} instead.
			 */
			@Deprecated
			public Tool(String name, String title, String description, String inputSchema, String outputSchema,
						ToolAnnotations annotations) {
				this(name, title, description, parseSchema(inputSchema), schemaToMap(outputSchema), annotations, null);
			}

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

				public Builder inputSchema(String inputSchema) {
					this.inputSchema = parseSchema(inputSchema);
					return this;
				}

				public Builder outputSchema(Map<String, Object> outputSchema) {
					this.outputSchema = outputSchema;
					return this;
				}

				public Builder outputSchema(String outputSchema) {
					this.outputSchema = schemaToMap(outputSchema);
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

		} // @formatter:on

		private static Map<String, Object> schemaToMap(String schema) {
			try {
				return OBJECT_MAPPER.readValue(schema, MAP_TYPE_REF);
			} catch (IOException e) {
				throw new IllegalArgumentException("Invalid schema: " + schema, e);
			}
		}

		private static JsonSchema parseSchema(String schema) {
			try {
				return OBJECT_MAPPER.readValue(schema, JsonSchema.class);
			} catch (IOException e) {
				throw new IllegalArgumentException("Invalid schema: " + schema, e);
			}
		}

		/**
		 * Used by the client to call a tool provided by the server.
		 */
		@JsonInclude(JsonInclude.Include.NON_ABSENT)
		@JsonIgnoreProperties(ignoreUnknown = true)
		@Data
		@AllArgsConstructor
		@NoArgsConstructor
		public static class CallToolRequest implements Request {
			@JsonProperty("name")
			String name;
			@JsonProperty("arguments")
			Map<String, Object> arguments;
			@JsonProperty("_meta")
			Map<String, Object> meta;

			public CallToolRequest(String name, String jsonArguments) {
				this(name, parseJsonArguments(jsonArguments), null);
			}

			public CallToolRequest(String name, Map<String, Object> arguments) {
				this(name, arguments, null);
			}

			private static Map<String, Object> parseJsonArguments(String jsonArguments) {
				try {
					return OBJECT_MAPPER.readValue(jsonArguments, MAP_TYPE_REF);
				} catch (IOException e) {
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

				public Builder arguments(String jsonArguments) {
					this.arguments = parseJsonArguments(jsonArguments);
					return this;
				}

				public Builder meta(Map<String, Object> meta) {
					this.meta = meta;
					return this;
				}

				public Builder progressToken(String progressToken) {
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
		}// @formatter:off

	/**
	 * The server's response to a tools/call request from the client.
	 */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	@Data
	@AllArgsConstructor
	@NoArgsConstructor
	public static class CallToolResult implements Result{
		@JsonProperty("content") List<Content> content;
		@JsonProperty("isError") Boolean isError;
		@JsonProperty("structuredContent") Map<String, Object> structuredContent;
		@JsonProperty("_meta") Map<String, Object> meta;

		// backwards compatibility constructor
		public CallToolResult(List<Content> content, Boolean isError) {
			this(content, isError, null, null);
		}

		// backwards compatibility constructor
		public CallToolResult(List<Content> content, Boolean isError, Map<String, Object> structuredContent) {
			this(content, isError, structuredContent, null);
		}

		/**
		 * Creates a new instance of {@link CallToolResult} with a string containing the
		 * tool result.
		 * @param content The content of the tool result. This will be mapped to a
		 * one-sized list with a {@link TextContent} element.
		 * @param isError If true, indicates that the tool execution failed and the
		 * content contains error information. If false or absent, indicates successful
		 * execution.
		 */
		public CallToolResult(String content, Boolean isError) {
			this(Utils.asList(new TextContent(content)), isError, null);
		}

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

			private Map<String, Object> structuredContent;

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

			public Builder structuredContent(Map<String, Object> structuredContent) {
				Assert.notNull(structuredContent, "structuredContent must not be null");
				this.structuredContent = structuredContent;
				return this;
			}

			public Builder structuredContent(String structuredContent) {
				Assert.hasText(structuredContent, "structuredContent must not be empty");
				try {
					this.structuredContent = OBJECT_MAPPER.readValue(structuredContent, MAP_TYPE_REF);
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
	} // @formatter:on

		// ---------------------------
		// Sampling Interfaces
		// ---------------------------
		@JsonInclude(JsonInclude.Include.NON_ABSENT)
		@JsonIgnoreProperties(ignoreUnknown = true)
		@Data
		@AllArgsConstructor
		@NoArgsConstructor
		public static class ModelPreferences {
			@JsonProperty("hints")
			List<ModelHint> hints;
			@JsonProperty("costPriority")
			Double costPriority;
			@JsonProperty("speedPriority")
			Double speedPriority;
			@JsonProperty("intelligencePriority")
			Double intelligencePriority;

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
		} // @formatter:on

		@JsonInclude(JsonInclude.Include.NON_ABSENT)
		@JsonIgnoreProperties(ignoreUnknown = true)
		@Data
		@AllArgsConstructor
		@NoArgsConstructor
		public static class ModelHint {
			@JsonProperty("name")
			String name;

			public static ModelHint of(String name) {
				return new ModelHint(name);
			}
		}

		@JsonInclude(JsonInclude.Include.NON_ABSENT)
		@JsonIgnoreProperties(ignoreUnknown = true)
		@Data
		@AllArgsConstructor
		@NoArgsConstructor
		public static class SamplingMessage {
			@JsonProperty("role")
			Role role;
			@JsonProperty("content")
			Content content;
		} // @formatter:on

		// Sampling and Message Creation
		@JsonInclude(JsonInclude.Include.NON_ABSENT)
		@JsonIgnoreProperties(ignoreUnknown = true)
		@Data
		@AllArgsConstructor
		@NoArgsConstructor
		public static class CreateMessageRequest implements Request {
			@JsonProperty("messages")
			List<SamplingMessage> messages;
			@JsonProperty("modelPreferences")
			ModelPreferences modelPreferences;
			@JsonProperty("systemPrompt")
			String systemPrompt;
			@JsonProperty("includeContext")
			ContextInclusionStrategy includeContext;
			@JsonProperty("temperature")
			Double temperature;
			@JsonProperty("maxTokens")
			int maxTokens;
			@JsonProperty("stopSequences")
			List<String> stopSequences;
			@JsonProperty("metadata")
			Map<String, Object> metadata;
			@JsonProperty("_meta")
			Map<String, Object> meta;

			// backwards compatibility constructor
			public CreateMessageRequest(List<SamplingMessage> messages, ModelPreferences modelPreferences,
										String systemPrompt, ContextInclusionStrategy includeContext, Double temperature, int maxTokens,
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

				private int maxTokens;

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

				public Builder progressToken(String progressToken) {
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
		}// @formatter:on

		@JsonInclude(JsonInclude.Include.NON_ABSENT)
		@JsonIgnoreProperties(ignoreUnknown = true)
		@Data
		@AllArgsConstructor
		@NoArgsConstructor
		public static class CreateMessageResult implements Result {
			@JsonProperty("role")
			Role role;
			@JsonProperty("content")
			Content content;
			@JsonProperty("model")
			String model;
			@JsonProperty("stopReason")
			StopReason stopReason;
			@JsonProperty("_meta")
			Map<String, Object> meta;

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
		}// @formatter:on


		// Elicitation

		/**
		 * A request from the server to elicit additional information from the user via the
		 * client.
		 */
		@JsonInclude(JsonInclude.Include.NON_ABSENT)
		@JsonIgnoreProperties(ignoreUnknown = true)
		@Data
		@AllArgsConstructor
		@NoArgsConstructor
		public static class ElicitRequest implements Request { // @formatter:on
			@JsonProperty("message")
			String message;
			@JsonProperty("requestedSchema")
			Map<String, Object> requestedSchema;
			@JsonProperty("_meta")
			Map<String, Object> meta;

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

				public Builder progressToken(String progressToken) {
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
		 */
		@JsonInclude(JsonInclude.Include.NON_ABSENT)
		@JsonIgnoreProperties(ignoreUnknown = true)
		@Data
		@AllArgsConstructor
		@NoArgsConstructor
		public static class ElicitResult implements Result { // @formatter:on
			@JsonProperty("action")
			Action action;
			@JsonProperty("content")
			Map<String, Object> content;
			@JsonProperty("_meta")
			Map<String, Object> meta;

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
		@JsonInclude(JsonInclude.Include.NON_ABSENT)
		@JsonIgnoreProperties(ignoreUnknown = true)
		@Data
		@AllArgsConstructor
		public static class PaginatedRequest implements Request {
			@JsonProperty("cursor")
			String cursor;
			@JsonProperty("_meta")
			Map<String, Object> meta;

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

		@JsonInclude(JsonInclude.Include.NON_ABSENT)
		@JsonIgnoreProperties(ignoreUnknown = true)
		@Data
		@AllArgsConstructor
		@NoArgsConstructor
		public static class PaginatedResult implements Result {
			@JsonProperty("nextCursor")
			String nextCursor;
			@JsonProperty("_meta")
			Map<String, Object> meta;

			public PaginatedResult(String nextCursor) {
				this(nextCursor, null);
			}
		}

		// ---------------------------
		// Progress and Logging
	// ---------------------------
	@JsonIgnoreProperties(ignoreUnknown = true)
	@Data
	@AllArgsConstructor
	@NoArgsConstructor
	public static class ProgressNotification implements Notification{
		@JsonProperty("progressToken") String progressToken;
		@JsonProperty("progress") double progress;
		@JsonProperty("total") Double total;
		@JsonProperty("message") String message;
		@JsonProperty("_meta") Map<String, Object> meta;

		public ProgressNotification(String progressToken, double progress, Double total, String message) {
			this(progressToken, progress, total, message, null);
		}
	}// @formatter:on


		/**
		 * The Model Context Protocol (MCP) provides a standardized way for servers to send
		 * resources update message to clients.
		 */
		@JsonIgnoreProperties(ignoreUnknown = true)
		@Data
		@AllArgsConstructor
		@NoArgsConstructor
		public static class ResourcesUpdatedNotification implements Notification { // @formatter:on
			@JsonProperty("uri") String uri;
			@JsonProperty("_meta") Map<String, Object> meta;

			public ResourcesUpdatedNotification(String uri) {
				this(uri, null);
			}
		}


	/**
         * The Model Context Protocol (MCP) provides a standardized way for servers to send
         * structured log messages to clients. Clients can control logging verbosity by
         * setting minimum log levels, with servers sending notifications containing severity
         * levels, optional logger names, and arbitrary JSON-serializable data.
         */
	@JsonIgnoreProperties(ignoreUnknown = true)
	@Data
	@AllArgsConstructor
	@NoArgsConstructor
	public static class LoggingMessageNotification implements Notification{
		@JsonProperty("level") LoggingLevel level;
		@JsonProperty("logger") String logger;
		@JsonProperty("data") String data;
		@JsonProperty("_meta") Map<String, Object> meta;

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
	}// @formatter:on

	public enum LoggingLevel {// @formatter:off
		@JsonProperty("debug") DEBUG(0),
		@JsonProperty("info") INFO(1),
		@JsonProperty("notice") NOTICE(2),
		@JsonProperty("warning") WARNING(3),
		@JsonProperty("error") ERROR(4),
		@JsonProperty("critical") CRITICAL(5),
		@JsonProperty("alert") ALERT(6),
		@JsonProperty("emergency") EMERGENCY(7);

		private final int level;

		LoggingLevel(int level) {
			this.level = level;
		}

		public int level() {
			return level;
		}

	} // @formatter:on


	/**
	 * A request from the client to the server, to enable or adjust logging.
	 */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	@Data
	@AllArgsConstructor
	@NoArgsConstructor
	public static class SetLevelRequest {
		@JsonProperty("level") LoggingLevel level;
	}

	// ---------------------------
	// Autocomplete
	// ---------------------------
	public  interface CompleteReference {
		String getType();
		String getIdentifier();
	}

	/**
	 * Identifies a prompt for completion requests.
	 */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	@Data
	@AllArgsConstructor
	@NoArgsConstructor
	public static class PromptReference implements McpSchema.CompleteReference, BaseMetadata { // @formatter:on
		@JsonProperty("type") String type;
		@JsonProperty("name") String name;
		@JsonProperty("title") String title;

		public PromptReference(String type, String name) {
			this(type, name, null);
		}

		public PromptReference(String name) {
			this("ref/prompt", name, null);
		}

		@Override
		public String getIdentifier() {
			return getName();
		}

        @Override
        public boolean equals(Object obj) {
            if (this == obj)
                return true;
            if (obj == null || getClass() != obj.getClass())
                return false;
            PromptReference that = (PromptReference) obj;
            return java.util.Objects.equals(getIdentifier(), that.getIdentifier())
                    && java.util.Objects.equals(getType(), that.getType());
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(getIdentifier(), getType());
        }
	}

	/**
	 * A reference to a resource or resource template definition for completion requests.
	 */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	@Data
	@AllArgsConstructor
	@NoArgsConstructor
	public static class ResourceReference  implements McpSchema.CompleteReference { // @formatter:on
		@JsonProperty("type") String type;
		@JsonProperty("uri") String uri;

		public ResourceReference(String uri) {
			this("ref/resource", uri);
		}

		@Override
		public String getIdentifier() {
			return getUri();
		}
	}


	@Data
	@AllArgsConstructor
	@NoArgsConstructor
	public static class CompleteRequest implements Request {
		@JsonProperty("ref") McpSchema.CompleteReference ref;
		@JsonProperty("argument") CompleteArgument argument;
		@JsonProperty("_meta") Map<String, Object> meta;
		@JsonProperty("context") CompleteContext context;

		public CompleteRequest(McpSchema.CompleteReference ref, CompleteArgument argument, Map<String, Object> meta) {
			this(ref, argument, meta, null);
		}

		public CompleteRequest(McpSchema.CompleteReference ref, CompleteArgument argument, CompleteContext context) {
			this(ref, argument, null, context);
		}

		public CompleteRequest(McpSchema.CompleteReference ref, CompleteArgument argument) {
			this(ref, argument, null, null);
		}

		@Data
		@AllArgsConstructor
		@NoArgsConstructor
		public static class CompleteArgument {
			@JsonProperty("name") String name;
			@JsonProperty("value") String value;
		}// @formatter:on

		@Data
		@AllArgsConstructor
		@NoArgsConstructor
		public static class CompleteContext {
			@JsonProperty("arguments") Map<String, String> arguments;
		}// @formatter:on
	}

	@Data
	@AllArgsConstructor
	@NoArgsConstructor
	public static class CompleteResult implements Result {
		@JsonProperty("completion") CompleteCompletion completion;
		@JsonProperty("_meta") Map<String, Object> meta;

		// backwards compatibility constructor
		public CompleteResult(CompleteCompletion completion) {
			this(completion, null);
		}

		@Data
		@AllArgsConstructor
		@NoArgsConstructor
		public static class CompleteCompletion {
			@JsonProperty("values") List<String> values;
			@JsonProperty("total") Integer total;
			@JsonProperty("hasMore") Boolean hasMore;
		}// @formatter:on
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
	public interface Content {
		Map<String, Object> getMeta();

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

	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	@Data
	@AllArgsConstructor
	@NoArgsConstructor
	public static class TextContent implements Annotated, Content { // @formatter:on
		@JsonProperty("annotations") Annotations annotations;
		@JsonProperty("text") String text;
		@JsonProperty("_meta") Map<String, Object> meta;

		public TextContent(Annotations annotations, String text) {
			this(annotations, text, null);
		}

		public TextContent(String content) {
			this(null, content, null);
		}

		/**
		 * @deprecated Only exists for backwards-compatibility purposes. Use
		 * {@link TextContent#TextContent(Annotations, String)} instead.
		 */
		@Deprecated
		public TextContent(List<Role> audience, Double priority, String content) {
			this(audience != null || priority != null ? new Annotations(audience, priority) : null, content, null);
		}

		/**
		 * @deprecated Only exists for backwards-compatibility purposes. Use
		 * {@link TextContent#getAnnotations()} instead.
		 */
		@Deprecated
		public List<Role> getAudience() {
			return annotations == null ? null : annotations.getAudience();
		}

		/**
		 * @deprecated Only exists for backwards-compatibility purposes. Use
		 * {@link TextContent#getAnnotations()} instead.
		 */
		@Deprecated
		public Double getPriority() {
			return annotations == null ? null : annotations.getPriority();
		}
	}

	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	@Data
	@AllArgsConstructor
	@NoArgsConstructor
	public static class ImageContent implements Annotated, Content  { // @formatter:on
		@JsonProperty("annotations") Annotations annotations;
		@JsonProperty("data") String data;
		@JsonProperty("mimeType") String mimeType;
		@JsonProperty("_meta") Map<String, Object> meta;

		public ImageContent(Annotations annotations, String data, String mimeType) {
			this(annotations, data, mimeType, null);
		}

		/**
		 * @deprecated Only exists for backwards-compatibility purposes. Use
		 * {@link ImageContent#ImageContent(Annotations, String, String)} instead.
		 */
		@Deprecated
		public ImageContent(List<Role> audience, Double priority, String data, String mimeType) {
			this(audience != null || priority != null ? new Annotations(audience, priority) : null, data, mimeType,
					null);
		}

		/**
		 * @deprecated Only exists for backwards-compatibility purposes. Use
		 * {@link ImageContent#getAnnotations()} instead.
		 */
		@Deprecated
		public List<Role> getAudience() {
			return annotations == null ? null : annotations.getAudience();
		}

		/**
		 * @deprecated Only exists for backwards-compatibility purposes. Use
		 * {@link ImageContent#getAnnotations()} instead.
		 */
		@Deprecated
		public Double getPriority() {
			return annotations == null ? null : annotations.getPriority();
		}
	}

	/**
	 * Audio provided to or from an LLM.
	 */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	@Data
	@AllArgsConstructor
	@NoArgsConstructor
	public static class AudioContent implements Annotated, Content { // @formatter:on
		@JsonProperty("annotations") Annotations annotations;
		@JsonProperty("data") String data;
		@JsonProperty("mimeType") String mimeType;
		@JsonProperty("_meta") Map<String, Object> meta;

		// backwards compatibility constructor
		public AudioContent(Annotations annotations, String data, String mimeType) {
			this(annotations, data, mimeType, null);
		}
	}

	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	@Data
	@AllArgsConstructor
	@NoArgsConstructor
	public static class EmbeddedResource implements Annotated, Content { // @formatter:on
		@JsonProperty("annotations") Annotations annotations;
		@JsonProperty("resource") ResourceContents resource;
		@JsonProperty("_meta") Map<String, Object> meta;

		// backwards compatibility constructor
		public EmbeddedResource(Annotations annotations, ResourceContents resource) {
			this(annotations, resource, null);
		}

		/**
		 * @deprecated Only exists for backwards-compatibility purposes. Use
		 * {@link EmbeddedResource#EmbeddedResource(Annotations, ResourceContents)}
		 * instead.
		 */
		@Deprecated
		public EmbeddedResource(List<Role> audience, Double priority, ResourceContents resource) {
			this(audience != null || priority != null ? new Annotations(audience, priority) : null, resource, null);
		}

		/**
		 * @deprecated Only exists for backwards-compatibility purposes. Use
		 * {@link EmbeddedResource#getAnnotations()} instead.
		 */
		@Deprecated
		public List<Role> getAudience() {
			return annotations == null ? null : annotations.getAudience();
		}

		/**
		 * @deprecated Only exists for backwards-compatibility purposes. Use
		 * {@link EmbeddedResource#getAnnotations()} instead.
		 */
		@Deprecated
		public Double getPriority() {
			return annotations == null ? null : annotations.getPriority();
		}
	}

	/**
	 * A known resource that the server is capable of reading.
	 */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	@Data
	@AllArgsConstructor
	@NoArgsConstructor
	public static class ResourceLink implements Annotated, Content, ResourceContent { // @formatter:on
		@JsonProperty("name") String name;
		@JsonProperty("title") String title;
		@JsonProperty("uri") String uri;
		@JsonProperty("description") String description;
		@JsonProperty("mimeType") String mimeType;
		@JsonProperty("size") Long size;
		@JsonProperty("annotations") Annotations annotations;
		@JsonProperty("_meta") Map<String, Object> meta;

		/**
		 * @deprecated Only exists for backwards-compatibility purposes. Use
		 * {@link ResourceLink#ResourceLink(String, String, String, String, String, Long, Annotations)}
		 * instead.
		 */
		@Deprecated
		public ResourceLink(String name, String title, String uri, String description, String mimeType, Long size,
				Annotations annotations) {
			this(name, title, uri, description, mimeType, size, annotations, null);
		}

		/**
		 * @deprecated Only exists for backwards-compatibility purposes. Use
		 * {@link ResourceLink#ResourceLink(String, String, String, String, String, Long, Annotations)}
		 * instead.
		 */
		@Deprecated
		public ResourceLink(String name, String uri, String description, String mimeType, Long size,
				Annotations annotations) {
			this(name, null, uri, description, mimeType, size, annotations);
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
	}

	// ---------------------------
	// Roots
	// ---------------------------
	/**
	 * Represents a root directory or file that the server can operate on.
	 */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	@Data
	@AllArgsConstructor
	@NoArgsConstructor
	public static class Root {
		@JsonProperty("uri") String uri;
		@JsonProperty("name") String name;
		@JsonProperty("_meta") Map<String, Object> meta;

		public Root(String uri, String name) {
			this(uri, name, null);
		}
	} // @formatter:on

	/**
	 * The client's response to a roots/list request from the server. This result contains
	 * an array of Root objects, each representing a root directory or file that the
	 * server can operate on.
	 */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	@Data
	@AllArgsConstructor
	@NoArgsConstructor
	public static class ListRootsResult implements Result{
		@JsonProperty("roots") List<Root> roots;
		@JsonProperty("nextCursor") String nextCursor;
		@JsonProperty("_meta") Map<String, Object> meta;

		public ListRootsResult(List<Root> roots) {
			this(roots, null);
		}

		public ListRootsResult(List<Root> roots, String nextCursor) {
			this(roots, nextCursor, null);
		}
	} // @formatter:on
}