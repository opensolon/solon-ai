/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.modern;

import com.fasterxml.jackson.annotation.*;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.util.Assert;
import io.modelcontextprotocol.util.SelfValidating;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Wire types for the MCP {@code 2026-07-28} ("modern") revision of the protocol: no
 * handshake, no session, per-request {@code _meta}, and a required {@code resultType} on
 * every result. The JSON-RPC envelope lives in {@link JsonRpc}.
 *
 * @author Dariusz Jędrzejczyk
 */
public final class McpSchema {

	private static final Logger logger = LoggerFactory.getLogger(McpSchema.class);

	private static final TypeRef<HashMap<String, Object>> MAP_TYPE_REF = new TypeRef<HashMap<String, Object>>() {
	};

	private McpSchema() {
	}

	// ---------------------------
	// Protocol constants
	// ---------------------------

	/** The protocol revision implemented by this package. */
	public static final String LATEST_PROTOCOL_VERSION = "2026-07-28";

	public static final String METHOD_SERVER_DISCOVER = "server/discover";

	public static final String METHOD_TOOLS_LIST = "tools/list";

	public static final String METHOD_TOOLS_CALL = "tools/call";

	public static final String METHOD_RESOURCES_LIST = "resources/list";

	public static final String METHOD_RESOURCES_TEMPLATES_LIST = "resources/templates/list";

	public static final String METHOD_RESOURCES_READ = "resources/read";

	public static final String METHOD_PROMPTS_LIST = "prompts/list";

	public static final String METHOD_PROMPTS_GET = "prompts/get";

	public static final String METHOD_COMPLETION_COMPLETE = "completion/complete";

	public static final String METHOD_SUBSCRIPTIONS_LISTEN = "subscriptions/listen";

	public static final String METHOD_NOTIFICATION_CANCELLED = "notifications/cancelled";

	public static final String METHOD_NOTIFICATION_PROGRESS = "notifications/progress";

	public static final String METHOD_NOTIFICATION_MESSAGE = "notifications/message";

	public static final String METHOD_NOTIFICATION_SUBSCRIPTIONS_ACKNOWLEDGED = "notifications/subscriptions/acknowledged";

	public static final String METHOD_NOTIFICATION_RESOURCES_UPDATED = "notifications/resources/updated";

	public static final String METHOD_NOTIFICATION_TOOLS_LIST_CHANGED = "notifications/tools/list_changed";

	public static final String METHOD_NOTIFICATION_PROMPTS_LIST_CHANGED = "notifications/prompts/list_changed";

	public static final String METHOD_NOTIFICATION_RESOURCES_LIST_CHANGED = "notifications/resources/list_changed";

	/** MRTR input-request payload methods; these never carry a JSON-RPC id. */
	public static final String METHOD_ELICITATION_CREATE = "elicitation/create";

	public static final String METHOD_SAMPLING_CREATE_MESSAGE = "sampling/createMessage";

	public static final String METHOD_ROOTS_LIST = "roots/list";

	/**
	 * The reserved {@code _meta} key names used on the modern wire.
	 */
	public static final class MetaKeys {

		public static final String PROTOCOL_VERSION = "io.modelcontextprotocol/protocolVersion";

		public static final String CLIENT_CAPABILITIES = "io.modelcontextprotocol/clientCapabilities";

		public static final String CLIENT_INFO = "io.modelcontextprotocol/clientInfo";

		public static final String LOG_LEVEL = "io.modelcontextprotocol/logLevel";

		public static final String SERVER_INFO = "io.modelcontextprotocol/serverInfo";

		public static final String SUBSCRIPTION_ID = "io.modelcontextprotocol/subscriptionId";

		public static final String PROGRESS_TOKEN = "progressToken";

		private MetaKeys() {
		}

	}

	/**
	 * JSON-RPC and MCP-specific error codes for the modern revision.
	 * <p>
	 * {@code -32002} ({@code RESOURCE_NOT_FOUND}) and {@code -32042}
	 * ({@code URL_ELICITATION_REQUIRED}) from the legacy revision must never be emitted
	 * on the modern path; a missing resource is reported as {@link #INVALID_PARAMS}.
	 */
	public static final class ErrorCodes {

		public static final int PARSE_ERROR = -32700;

		public static final int INVALID_REQUEST = -32600;

		public static final int METHOD_NOT_FOUND = -32601;

		public static final int INVALID_PARAMS = -32602;

		public static final int INTERNAL_ERROR = -32603;

		public static final int HEADER_MISMATCH = -32020;

		public static final int MISSING_REQUIRED_CLIENT_CAPABILITY = -32021;

		public static final int UNSUPPORTED_PROTOCOL_VERSION = -32022;

		private ErrorCodes() {
		}

	}

	// ---------------------------
	// Result base types
	// ---------------------------

	/**
	 * Base type for every modern result; {@code resultType} is spec-required (see
	 * {@link ResultType}).
	 */
	public interface Result {

		String resultType();

		Map<String, Object> meta();

	}

	/**
	 * The two {@code resultType} values defined by this revision.
	 */
	public static final class ResultType {

		public static final String COMPLETE = "complete";

		public static final String INPUT_REQUIRED = "input_required";

		private ResultType() {
		}

	}

	/**
	 * A result that carries caching hints. Required on {@code resultType: "complete"}
	 * results from the list methods, {@code resources/read} and {@code server/discover};
	 * never present on an {@code "input_required"} result.
	 */
	public interface CacheableResult extends Result {

		Long ttlMs();

		CacheScope cacheScope();

	}

	/**
	 * A result asking the client for more input before the request can complete. An
	 * extension method supporting multi round-trip requests returns its own result
	 * type implementing this interface and the method's outcome type.
	 */
	public interface InputRequired extends Result {

		Map<String, InputRequest> inputRequests();

		String requestState();

	}

	/**
	 * What a {@code tools/call} handler returns: a {@link CallToolResult} or input
	 * required.
	 */
	public interface CallToolOutcome extends Result {

	}

	/**
	 * What a {@code prompts/get} handler returns: a {@link GetPromptResult} or input
	 * required.
	 */
	public interface GetPromptOutcome extends Result {

	}

	/**
	 * What a {@code resources/read} handler returns: a {@link ReadResourceResult} or
	 * input required.
	 */
	public interface ReadResourceOutcome extends Result {

	}

	/**
	 * Who may reuse a cached response. {@code PUBLIC} asserts the response contains no
	 * user-specific data and may be served to any client across access tokens; it must
	 * never be the default. {@code PRIVATE} permits reuse only within the same
	 * authorization context.
	 */
	public enum CacheScope {

		@JsonProperty("public")
		PUBLIC, @JsonProperty("private")
		PRIVATE

	}

	// ---------------------------
	// Capabilities
	// ---------------------------

	/**
	 * Capabilities a client declares on every request via {@code _meta}.
	 */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static final class ClientCapabilities {

		@JsonProperty("experimental")
		private final Map<String, Object> experimental;

		@JsonProperty("roots")
		private final Roots roots;

		@JsonProperty("sampling")
		private final Sampling sampling;

		@JsonProperty("elicitation")
		private final Elicitation elicitation;

		@JsonProperty("extensions")
		private final Map<String, Map<String, Object>> extensions;

		@JsonCreator
		public ClientCapabilities( // @formatter:off
				@JsonProperty("experimental") Map<String, Object> experimental,
				@JsonProperty("roots") Roots roots,
				@JsonProperty("sampling") Sampling sampling,
				@JsonProperty("elicitation") Elicitation elicitation,
				@JsonProperty("extensions") Map<String, Map<String, Object>> extensions) { // @formatter:on
			this.experimental = experimental;
			this.roots = roots;
			this.sampling = sampling;
			this.elicitation = elicitation;
			this.extensions = extensions;
		}

		public static final ClientCapabilities NONE = new ClientCapabilities(null, null, null, null, null);

		public Map<String, Object> experimental() {
			return this.experimental;
		}

		public Roots roots() {
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

		public boolean supportsRoots() {
			return this.roots != null;
		}

		public boolean supportsSampling() {
			return this.sampling != null;
		}

		public boolean supportsElicitationForm() {
			return this.elicitation != null && (this.elicitation.form() != null || this.elicitation.url() == null);
		}

		public boolean supportsElicitationUrl() {
			return this.elicitation != null && this.elicitation.url() != null;
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

		public static Builder builder() {
			return new Builder();
		}

		/** Marker for roots support; deprecated by the spec. */
		@JsonInclude(JsonInclude.Include.NON_ABSENT)
		@JsonIgnoreProperties(ignoreUnknown = true)
		public static final class Roots {

			public Roots() {
			}

			@Override
			public boolean equals(Object o) {
				return this == o || o instanceof Roots;
			}

			@Override
			public int hashCode() {
				return Objects.hash();
			}

			@Override
			public String toString() {
				return "Roots[]";
			}

		}

		/** Marker for sampling support; deprecated by the spec. */
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
		 * Elicitation support. An empty object is equivalent to {@code form} only.
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

		}

		public static final class Builder {

			private Map<String, Object> experimental;

			private Roots roots;

			private Sampling sampling;

			private Elicitation elicitation;

			private Map<String, Map<String, Object>> extensions;

			public Builder experimental(Map<String, Object> experimental) {
				this.experimental = experimental;
				return this;
			}

			public Builder roots() {
				this.roots = new Roots();
				return this;
			}

			public Builder sampling() {
				this.sampling = new Sampling();
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
	}

	/**
	 * Capabilities a server advertises via {@code server/discover}. Populated by
	 * aggregating every registered {@code McpFeature}.
	 */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static final class ServerCapabilities {

		@JsonProperty("experimental")
		private final Map<String, Object> experimental;

		@JsonProperty("logging")
		private final Logging logging;

		@JsonProperty("completions")
		private final Completions completions;

		@JsonProperty("prompts")
		private final Prompts prompts;

		@JsonProperty("resources")
		private final Resources resources;

		@JsonProperty("tools")
		private final Tools tools;

		@JsonProperty("extensions")
		private final Map<String, Map<String, Object>> extensions;

		@JsonCreator
		public ServerCapabilities( // @formatter:off
				@JsonProperty("experimental") Map<String, Object> experimental,
				@JsonProperty("logging") Logging logging,
				@JsonProperty("completions") Completions completions,
				@JsonProperty("prompts") Prompts prompts,
				@JsonProperty("resources") Resources resources,
				@JsonProperty("tools") Tools tools,
				@JsonProperty("extensions") Map<String, Map<String, Object>> extensions) { // @formatter:on
			this.experimental = experimental;
			this.logging = logging;
			this.completions = completions;
			this.prompts = prompts;
			this.resources = resources;
			this.tools = tools;
			this.extensions = extensions;
		}

		public Map<String, Object> experimental() {
			return this.experimental;
		}

		public Logging logging() {
			return this.logging;
		}

		public Completions completions() {
			return this.completions;
		}

		public Prompts prompts() {
			return this.prompts;
		}

		public Resources resources() {
			return this.resources;
		}

		public Tools tools() {
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
			return Objects.equals(this.experimental, that.experimental) && Objects.equals(this.logging, that.logging)
					&& Objects.equals(this.completions, that.completions) && Objects.equals(this.prompts, that.prompts)
					&& Objects.equals(this.resources, that.resources) && Objects.equals(this.tools, that.tools)
					&& Objects.equals(this.extensions, that.extensions);
		}

		@Override
		public int hashCode() {
			return Objects.hash(this.experimental, this.logging, this.completions, this.prompts, this.resources,
					this.tools, this.extensions);
		}

		@Override
		public String toString() {
			return "ServerCapabilities[experimental=" + this.experimental + ", logging=" + this.logging
					+ ", completions=" + this.completions + ", prompts=" + this.prompts + ", resources=" + this.resources
					+ ", tools=" + this.tools + ", extensions=" + this.extensions + "]";
		}

		public static Builder builder() {
			return new Builder();
		}

		/** Present if the server accepts a per-request {@code logLevel}. Deprecated. */
		@JsonInclude(JsonInclude.Include.NON_ABSENT)
		@JsonIgnoreProperties(ignoreUnknown = true)
		public static final class Logging {

			public Logging() {
			}

			@Override
			public boolean equals(Object o) {
				return this == o || o instanceof Logging;
			}

			@Override
			public int hashCode() {
				return Objects.hash();
			}

			@Override
			public String toString() {
				return "Logging[]";
			}

		}

		@JsonInclude(JsonInclude.Include.NON_ABSENT)
		@JsonIgnoreProperties(ignoreUnknown = true)
		public static final class Completions {

			public Completions() {
			}

			@Override
			public boolean equals(Object o) {
				return this == o || o instanceof Completions;
			}

			@Override
			public int hashCode() {
				return Objects.hash();
			}

			@Override
			public String toString() {
				return "Completions[]";
			}

		}

		@JsonInclude(JsonInclude.Include.NON_ABSENT)
		@JsonIgnoreProperties(ignoreUnknown = true)
		public static final class Prompts {

			@JsonProperty("listChanged")
			private final Boolean listChanged;

			@JsonCreator
			public Prompts( // @formatter:off
					@JsonProperty("listChanged") Boolean listChanged) { // @formatter:on
				this.listChanged = listChanged;
			}

			public Boolean listChanged() {
				return this.listChanged;
			}

			@Override
			public boolean equals(Object o) {
				if (this == o) {
					return true;
			}
				if (!(o instanceof Prompts)) {
					return false;
			}
				Prompts that = (Prompts) o;
				return Objects.equals(this.listChanged, that.listChanged);
			}

			@Override
			public int hashCode() {
				return Objects.hash(this.listChanged);
			}

			@Override
			public String toString() {
				return "Prompts[listChanged=" + this.listChanged + "]";
			}

		}

		@JsonInclude(JsonInclude.Include.NON_ABSENT)
		@JsonIgnoreProperties(ignoreUnknown = true)
		public static final class Resources {

			@JsonProperty("subscribe")
			private final Boolean subscribe;

			@JsonProperty("listChanged")
			private final Boolean listChanged;

			@JsonCreator
			public Resources( // @formatter:off
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

			@Override
			public boolean equals(Object o) {
				if (this == o) {
					return true;
			}
				if (!(o instanceof Resources)) {
					return false;
			}
				Resources that = (Resources) o;
				return Objects.equals(this.subscribe, that.subscribe)
						&& Objects.equals(this.listChanged, that.listChanged);
			}

			@Override
			public int hashCode() {
				return Objects.hash(this.subscribe, this.listChanged);
			}

			@Override
			public String toString() {
				return "Resources[subscribe=" + this.subscribe + ", listChanged=" + this.listChanged + "]";
			}

		}

		@JsonInclude(JsonInclude.Include.NON_ABSENT)
		@JsonIgnoreProperties(ignoreUnknown = true)
		public static final class Tools {

			@JsonProperty("listChanged")
			private final Boolean listChanged;

			@JsonCreator
			public Tools( // @formatter:off
					@JsonProperty("listChanged") Boolean listChanged) { // @formatter:on
				this.listChanged = listChanged;
			}

			public Boolean listChanged() {
				return this.listChanged;
			}

			@Override
			public boolean equals(Object o) {
				if (this == o) {
					return true;
			}
				if (!(o instanceof Tools)) {
					return false;
			}
				Tools that = (Tools) o;
				return Objects.equals(this.listChanged, that.listChanged);
			}

			@Override
			public int hashCode() {
				return Objects.hash(this.listChanged);
			}

			@Override
			public String toString() {
				return "Tools[listChanged=" + this.listChanged + "]";
			}

		}

		/**
		 * Collects the capabilities contributed by each registered feature.
		 */
		public static final class Builder {

			// Prefix: dot-separated labels then '/'; name: alphanumeric at both ends,
			// '-', '_', '.' allowed inside, or empty.
			private static final Pattern EXTENSION_ID = Pattern
				.compile("[A-Za-z](?:[A-Za-z0-9-]*[A-Za-z0-9])?(?:\\.[A-Za-z](?:[A-Za-z0-9-]*[A-Za-z0-9])?)*/"
						+ "(?:[A-Za-z0-9](?:[A-Za-z0-9._-]*[A-Za-z0-9])?)?");

			private Map<String, Object> experimental;

			private Completions completions;

			private Prompts prompts;

			private Resources resources;

			private Tools tools;

			private Map<String, Map<String, Object>> extensions = new HashMap<>();

			public Builder experimental(Map<String, Object> experimental) {
				this.experimental = experimental;
				return this;
			}

			public Builder completions() {
				this.completions = new Completions();
				return this;
			}

			public Builder prompts(Boolean listChanged) {
				this.prompts = new Prompts(listChanged);
				return this;
			}

			public Builder resources(Boolean subscribe, Boolean listChanged) {
				this.resources = new Resources(subscribe, listChanged);
				return this;
			}

			public Builder tools(Boolean listChanged) {
				this.tools = new Tools(listChanged);
				return this;
			}

			/**
			 * Advertises an extension. {@code id} must be a {@code _meta}-style key with
			 * a prefix, e.g. {@code com.example/my-extension}.
			 */
			public Builder extension(String id, Map<String, Object> settings) {
				Assert.isTrue(id != null && EXTENSION_ID.matcher(id).matches(),
						"extension id must be a prefixed _meta key, e.g. com.example/name: " + id);
			this.extensions.put(id, settings == null ? Collections.emptyMap() : settings);
				return this;
			}

			public boolean hasTools() {
				return this.tools != null;
			}

			public boolean hasPrompts() {
				return this.prompts != null;
			}

			public boolean hasResources() {
				return this.resources != null;
			}

			public boolean hasResourcesSubscribe() {
				return this.resources != null && Boolean.TRUE.equals(this.resources.subscribe());
			}

			/** Sets {@code tools.listChanged}; a no-op unless tools are advertised. */
			public Builder toolsListChanged(boolean listChanged) {
				if (this.tools != null) {
					this.tools = new Tools(listChanged);
				}
				return this;
			}

			/**
			 * Sets {@code prompts.listChanged}; a no-op unless prompts are advertised.
			 */
			public Builder promptsListChanged(boolean listChanged) {
				if (this.prompts != null) {
					this.prompts = new Prompts(listChanged);
				}
				return this;
			}

			/**
			 * Sets {@code resources.subscribe} and {@code resources.listChanged}; a no-op
			 * unless resources are advertised.
			 */
			public Builder resourcesSubscribe(boolean subscribe, boolean listChanged) {
				if (this.resources != null) {
					this.resources = new Resources(subscribe, listChanged);
				}
				return this;
			}

			public ServerCapabilities build() {
				// Logging is deprecated and not offered by modern servers.
				return new ServerCapabilities(experimental, null, completions, prompts, resources, tools,
				extensions.isEmpty() ? null : Collections.unmodifiableMap(new LinkedHashMap<>(extensions)));
			}

		}
	}

	// ---------------------------
	// server/discover
	// ---------------------------

	/**
	 * The response to {@code server/discover}. Cacheable; the client uses it to determine
	 * supported protocol versions and capabilities before issuing any other request.
	 */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static final class DiscoverResult implements CacheableResult {

		@JsonProperty("supportedVersions")
		private final List<String> supportedVersions;

		@JsonProperty("capabilities")
		private final ServerCapabilities capabilities;

		@JsonProperty("instructions")
		private final String instructions;

		@JsonProperty("ttlMs")
		private final Long ttlMs;

		@JsonProperty("cacheScope")
		private final CacheScope cacheScope;

		@JsonProperty("resultType")
		private final String resultType;

		@JsonProperty("_meta")
		private final Map<String, Object> meta;

		public DiscoverResult(List<String> supportedVersions, ServerCapabilities capabilities, String instructions,
				Long ttlMs, CacheScope cacheScope, String resultType, Map<String, Object> meta) {
			Assert.notNull(supportedVersions, "supportedVersions must not be null");
			Assert.notNull(capabilities, "capabilities must not be null");
			Assert.notNull(ttlMs, "ttlMs must not be null");
			Assert.notNull(cacheScope, "cacheScope must not be null");
			this.resultType = resultType == null ? ResultType.COMPLETE : resultType;
			this.supportedVersions = supportedVersions;
			this.capabilities = capabilities;
			this.instructions = instructions;
			this.ttlMs = ttlMs;
			this.cacheScope = cacheScope;
			this.meta = meta;
		}

		@JsonCreator
		static DiscoverResult fromJson(@JsonProperty("supportedVersions") List<String> supportedVersions,
				@JsonProperty("capabilities") ServerCapabilities capabilities,
				@JsonProperty("instructions") String instructions, @JsonProperty("ttlMs") Long ttlMs,
				@JsonProperty("cacheScope") CacheScope cacheScope, @JsonProperty("resultType") String resultType,
				@JsonProperty("_meta") Map<String, Object> meta) {
			if (supportedVersions == null || capabilities == null || ttlMs == null || cacheScope == null) {
				logger.warn("DiscoverResult: missing required fields during deserialization; substituting defaults");
				supportedVersions = supportedVersions == null ? Collections.emptyList() : supportedVersions;
				capabilities = capabilities == null ? ServerCapabilities.builder().build() : capabilities;
				ttlMs = ttlMs == null ? 0L : ttlMs;
				cacheScope = cacheScope == null ? CacheScope.PRIVATE : cacheScope;
			}
			return new DiscoverResult(supportedVersions, capabilities, instructions, ttlMs, cacheScope, resultType,
					meta);
		}

		public List<String> supportedVersions() {
			return this.supportedVersions;
		}

		public ServerCapabilities capabilities() {
			return this.capabilities;
		}

		public String instructions() {
			return this.instructions;
		}

		@Override
		public Long ttlMs() {
			return this.ttlMs;
		}

		@Override
		public CacheScope cacheScope() {
			return this.cacheScope;
		}

		@Override
		public String resultType() {
			return this.resultType;
		}

		@Override
		public Map<String, Object> meta() {
			return this.meta;
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) {
				return true;
			}
			if (!(o instanceof DiscoverResult)) {
				return false;
			}
			DiscoverResult that = (DiscoverResult) o;
			return Objects.equals(this.supportedVersions, that.supportedVersions)
					&& Objects.equals(this.capabilities, that.capabilities)
					&& Objects.equals(this.instructions, that.instructions) && Objects.equals(this.ttlMs, that.ttlMs)
					&& Objects.equals(this.cacheScope, that.cacheScope)
					&& Objects.equals(this.resultType, that.resultType) && Objects.equals(this.meta, that.meta);
		}

		@Override
		public int hashCode() {
			return Objects.hash(this.supportedVersions, this.capabilities, this.instructions, this.ttlMs,
					this.cacheScope, this.resultType, this.meta);
		}

		@Override
		public String toString() {
			return "DiscoverResult[supportedVersions=" + this.supportedVersions + ", capabilities=" + this.capabilities
					+ ", instructions=" + this.instructions + ", ttlMs=" + this.ttlMs + ", cacheScope=" + this.cacheScope
					+ ", resultType=" + this.resultType + ", meta=" + this.meta + "]";
		}

		public static Builder builder(List<String> supportedVersions, ServerCapabilities capabilities) {
			return new Builder(supportedVersions, capabilities);
		}

		public static final class Builder {

			private final List<String> supportedVersions;

			private final ServerCapabilities capabilities;

			private String instructions;

			private Long ttlMs = 0L;

			private CacheScope cacheScope = CacheScope.PRIVATE;

			private Map<String, Object> meta;

			private Builder(List<String> supportedVersions, ServerCapabilities capabilities) {
				Assert.notNull(supportedVersions, "supportedVersions must not be null");
				Assert.notNull(capabilities, "capabilities must not be null");
				this.supportedVersions = supportedVersions;
				this.capabilities = capabilities;
			}

			public Builder instructions(String instructions) {
				this.instructions = instructions;
				return this;
			}

			public Builder ttlMs(long ttlMs) {
				Assert.isTrue(ttlMs >= 0, "ttlMs must not be negative");
				this.ttlMs = ttlMs;
				return this;
			}

			public Builder cacheScope(CacheScope cacheScope) {
				Assert.notNull(cacheScope, "cacheScope must not be null");
				this.cacheScope = cacheScope;
				return this;
			}

			public Builder meta(Map<String, Object> meta) {
				this.meta = meta;
				return this;
			}

			public DiscoverResult build() {
				return new DiscoverResult(supportedVersions, capabilities, instructions, ttlMs, cacheScope,
						ResultType.COMPLETE, meta);
			}

		}
	}

	// ---------------------------
	// Requests that may carry MRTR retry data
	// ---------------------------

	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static final class CallToolRequest implements SelfValidating {

		@JsonProperty("name")
		private final String name;

		@JsonProperty("arguments")
		private final Map<String, Object> arguments;

		@JsonProperty("inputResponses")
		private final Map<String, Object> inputResponses;

		@JsonProperty("requestState")
		private final String requestState;

		@JsonProperty("_meta")
		private final Map<String, Object> meta;

		@JsonCreator
		public CallToolRequest( // @formatter:off
				@JsonProperty("name") String name,
				@JsonProperty("arguments") Map<String, Object> arguments,
				@JsonProperty("inputResponses") Map<String, Object> inputResponses,
				@JsonProperty("requestState") String requestState,
		@JsonProperty("_meta") Map<String, Object> meta) { // @formatter:on
			Assert.notNull(name, "name must not be null");
			this.name = name;
			this.arguments = arguments;
			this.inputResponses = inputResponses;
			this.requestState = requestState;
			this.meta = meta;
		}

		@Override
		public void validate() {
			Assert.notNull(this.name, "name must not be null");
		}

		public String name() {
			return this.name;
		}

		public Map<String, Object> arguments() {
			return this.arguments;
		}

		public Map<String, Object> inputResponses() {
			return this.inputResponses;
		}

		public String requestState() {
			return this.requestState;
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
					&& Objects.equals(this.inputResponses, that.inputResponses)
					&& Objects.equals(this.requestState, that.requestState) && Objects.equals(this.meta, that.meta);
		}

		@Override
		public int hashCode() {
			return Objects.hash(this.name, this.arguments, this.inputResponses, this.requestState, this.meta);
		}

		@Override
		public String toString() {
			return "CallToolRequest[name=" + this.name + ", arguments=" + this.arguments + ", inputResponses="
					+ this.inputResponses + ", requestState=" + this.requestState + ", meta=" + this.meta + "]";
		}

		public static Builder builder(String name) {
			return new Builder(name);
		}

		public static final class Builder {

			private final String name;

			private Map<String, Object> arguments;

			private Map<String, Object> inputResponses;

			private String requestState;

			private Map<String, Object> meta;

			private Builder(String name) {
				Assert.hasText(name, "name must not be empty");
				this.name = name;
			}

			public Builder arguments(Map<String, Object> arguments) {
				this.arguments = arguments;
				return this;
			}

			public Builder inputResponses(Map<String, Object> inputResponses) {
				this.inputResponses = inputResponses;
				return this;
			}

			public Builder requestState(String requestState) {
				this.requestState = requestState;
				return this;
			}

			public Builder meta(Map<String, Object> meta) {
				this.meta = meta;
				return this;
			}

			public CallToolRequest build() {
				return new CallToolRequest(name, arguments, inputResponses, requestState, meta);
			}

		}
	}

	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static final class ReadResourceRequest implements SelfValidating {

		@JsonProperty("uri")
		private final String uri;

		@JsonProperty("inputResponses")
		private final Map<String, Object> inputResponses;

		@JsonProperty("requestState")
		private final String requestState;

		@JsonProperty("_meta")
		private final Map<String, Object> meta;

		@JsonCreator
		public ReadResourceRequest( // @formatter:off
				@JsonProperty("uri") String uri,
				@JsonProperty("inputResponses") Map<String, Object> inputResponses,
				@JsonProperty("requestState") String requestState,
				@JsonProperty("_meta") Map<String, Object> meta) { // @formatter:on
			Assert.notNull(uri, "uri must not be null");
			this.uri = uri;
			this.inputResponses = inputResponses;
			this.requestState = requestState;
			this.meta = meta;
		}

		@Override
		public void validate() {
			Assert.notNull(this.uri, "uri must not be null");
		}

		public String uri() {
			return this.uri;
		}

		public Map<String, Object> inputResponses() {
			return this.inputResponses;
		}

		public String requestState() {
			return this.requestState;
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
			return Objects.equals(this.uri, that.uri) && Objects.equals(this.inputResponses, that.inputResponses)
					&& Objects.equals(this.requestState, that.requestState) && Objects.equals(this.meta, that.meta);
		}

		@Override
		public int hashCode() {
			return Objects.hash(this.uri, this.inputResponses, this.requestState, this.meta);
		}

		@Override
		public String toString() {
			return "ReadResourceRequest[uri=" + this.uri + ", inputResponses=" + this.inputResponses
					+ ", requestState=" + this.requestState + ", meta=" + this.meta + "]";
		}

		public static Builder builder(String uri) {
			return new Builder(uri);
		}

		public static final class Builder {

			private final String uri;

			private Map<String, Object> inputResponses;

			private String requestState;

			private Map<String, Object> meta;

			private Builder(String uri) {
				Assert.hasText(uri, "uri must not be empty");
				this.uri = uri;
			}

			public Builder inputResponses(Map<String, Object> inputResponses) {
				this.inputResponses = inputResponses;
				return this;
			}

			public Builder requestState(String requestState) {
				this.requestState = requestState;
				return this;
			}

			public Builder meta(Map<String, Object> meta) {
				this.meta = meta;
				return this;
			}

			public ReadResourceRequest build() {
				return new ReadResourceRequest(uri, inputResponses, requestState, meta);
			}

		}
	}

	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static final class GetPromptRequest implements SelfValidating {

		@JsonProperty("name")
		private final String name;

		@JsonProperty("arguments")
		private final Map<String, String> arguments;

		@JsonProperty("inputResponses")
		private final Map<String, Object> inputResponses;

		@JsonProperty("requestState")
		private final String requestState;

		@JsonProperty("_meta")
		private final Map<String, Object> meta;

		@JsonCreator
		public GetPromptRequest( // @formatter:off
				@JsonProperty("name") String name,
				@JsonProperty("arguments") Map<String, String> arguments,
				@JsonProperty("inputResponses") Map<String, Object> inputResponses,
				@JsonProperty("requestState") String requestState,
				@JsonProperty("_meta") Map<String, Object> meta) { // @formatter:on
			Assert.notNull(name, "name must not be null");
			this.name = name;
			this.arguments = arguments;
			this.inputResponses = inputResponses;
			this.requestState = requestState;
			this.meta = meta;
		}

		@Override
		public void validate() {
			Assert.notNull(this.name, "name must not be null");
		}

		public String name() {
			return this.name;
		}

		public Map<String, String> arguments() {
			return this.arguments;
		}

		public Map<String, Object> inputResponses() {
			return this.inputResponses;
		}

		public String requestState() {
			return this.requestState;
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
					&& Objects.equals(this.inputResponses, that.inputResponses)
					&& Objects.equals(this.requestState, that.requestState) && Objects.equals(this.meta, that.meta);
		}

		@Override
		public int hashCode() {
			return Objects.hash(this.name, this.arguments, this.inputResponses, this.requestState, this.meta);
		}

		@Override
		public String toString() {
			return "GetPromptRequest[name=" + this.name + ", arguments=" + this.arguments + ", inputResponses="
					+ this.inputResponses + ", requestState=" + this.requestState + ", meta=" + this.meta + "]";
		}

		public static Builder builder(String name) {
			return new Builder(name);
		}

		public static final class Builder {

			private final String name;

			private Map<String, String> arguments;

			private Map<String, Object> inputResponses;

			private String requestState;

			private Map<String, Object> meta;

			private Builder(String name) {
				Assert.hasText(name, "name must not be empty");
				this.name = name;
			}

			public Builder arguments(Map<String, String> arguments) {
				this.arguments = arguments;
				return this;
			}

			public Builder inputResponses(Map<String, Object> inputResponses) {
				this.inputResponses = inputResponses;
				return this;
			}

			public Builder requestState(String requestState) {
				this.requestState = requestState;
				return this;
			}

			public Builder meta(Map<String, Object> meta) {
				this.meta = meta;
				return this;
			}

			public GetPromptRequest build() {
				return new GetPromptRequest(name, arguments, inputResponses, requestState, meta);
			}

		}
	}

	/** {@code cursor}/{@code _meta} shared by every paginated list request. */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static final class PaginatedRequest {

		@JsonProperty("cursor")
		private final String cursor;

		@JsonProperty("_meta")
		private final Map<String, Object> meta;

		@JsonCreator
		public PaginatedRequest(@JsonProperty("cursor") String cursor,
				@JsonProperty("_meta") Map<String, Object> meta) {
			this.cursor = cursor;
			this.meta = meta;
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

	// ---------------------------
	// List / read / call / get results
	// ---------------------------

	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static final class ListToolsResult implements CacheableResult {

		@JsonProperty("tools")
		private final List<Tool> tools;

		@JsonProperty("nextCursor")
		private final String nextCursor;

		@JsonProperty("ttlMs")
		private final Long ttlMs;

		@JsonProperty("cacheScope")
		private final CacheScope cacheScope;

		@JsonProperty("resultType")
		private final String resultType;

		@JsonProperty("_meta")
		private final Map<String, Object> meta;

		public ListToolsResult(List<Tool> tools, String nextCursor, Long ttlMs, CacheScope cacheScope,
				String resultType, Map<String, Object> meta) {
			Assert.notNull(tools, "tools must not be null");
			Assert.notNull(ttlMs, "ttlMs must not be null");
			Assert.notNull(cacheScope, "cacheScope must not be null");
			this.resultType = resultType == null ? ResultType.COMPLETE : resultType;
			this.tools = tools;
			this.nextCursor = nextCursor;
			this.ttlMs = ttlMs;
			this.cacheScope = cacheScope;
			this.meta = meta;
		}

		@JsonCreator
		static ListToolsResult fromJson(@JsonProperty("tools") List<Tool> tools,
				@JsonProperty("nextCursor") String nextCursor, @JsonProperty("ttlMs") Long ttlMs,
				@JsonProperty("cacheScope") CacheScope cacheScope, @JsonProperty("resultType") String resultType,
				@JsonProperty("_meta") Map<String, Object> meta) {
			if (tools == null || ttlMs == null || cacheScope == null) {
				logger.warn("ListToolsResult: missing required fields during deserialization; substituting defaults");
			}
			tools = tools == null ? Collections.emptyList() : tools;
			ttlMs = ttlMs == null ? 0L : ttlMs;
			cacheScope = cacheScope == null ? CacheScope.PRIVATE : cacheScope;
			return new ListToolsResult(tools, nextCursor, ttlMs, cacheScope, resultType, meta);
		}

		public List<Tool> tools() {
			return this.tools;
		}

		public String nextCursor() {
			return this.nextCursor;
		}

		@Override
		public Long ttlMs() {
			return this.ttlMs;
		}

		@Override
		public CacheScope cacheScope() {
			return this.cacheScope;
		}

		@Override
		public String resultType() {
			return this.resultType;
		}

		@Override
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
					&& Objects.equals(this.ttlMs, that.ttlMs) && Objects.equals(this.cacheScope, that.cacheScope)
					&& Objects.equals(this.resultType, that.resultType) && Objects.equals(this.meta, that.meta);
		}

		@Override
		public int hashCode() {
			return Objects.hash(this.tools, this.nextCursor, this.ttlMs, this.cacheScope, this.resultType, this.meta);
		}

		@Override
		public String toString() {
			return "ListToolsResult[tools=" + this.tools + ", nextCursor=" + this.nextCursor + ", ttlMs=" + this.ttlMs
					+ ", cacheScope=" + this.cacheScope + ", resultType=" + this.resultType + ", meta=" + this.meta + "]";
		}

		public static Builder builder(List<Tool> tools) {
			return new Builder(tools);
		}

		public static final class Builder {

			private final List<Tool> tools;

			private String nextCursor;

			private Long ttlMs = 0L;

			private CacheScope cacheScope = CacheScope.PRIVATE;

			private Map<String, Object> meta;

			private Builder(List<Tool> tools) {
				Assert.notNull(tools, "tools must not be null");
				this.tools = tools;
			}

			public Builder nextCursor(String nextCursor) {
				this.nextCursor = nextCursor;
				return this;
			}

			public Builder ttlMs(long ttlMs) {
				Assert.isTrue(ttlMs >= 0, "ttlMs must not be negative");
				this.ttlMs = ttlMs;
				return this;
			}

			public Builder cacheScope(CacheScope cacheScope) {
				Assert.notNull(cacheScope, "cacheScope must not be null");
				this.cacheScope = cacheScope;
				return this;
			}

			public Builder meta(Map<String, Object> meta) {
				this.meta = meta;
				return this;
			}

			public ListToolsResult build() {
				return new ListToolsResult(tools, nextCursor, ttlMs, cacheScope, ResultType.COMPLETE, meta);
			}

		}
	}

	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static final class ListResourcesResult implements CacheableResult {

		@JsonProperty("resources")
		private final List<Resource> resources;

		@JsonProperty("nextCursor")
		private final String nextCursor;

		@JsonProperty("ttlMs")
		private final Long ttlMs;

		@JsonProperty("cacheScope")
		private final CacheScope cacheScope;

		@JsonProperty("resultType")
		private final String resultType;

		@JsonProperty("_meta")
		private final Map<String, Object> meta;

		public ListResourcesResult(List<Resource> resources, String nextCursor, Long ttlMs, CacheScope cacheScope,
				String resultType, Map<String, Object> meta) {
			Assert.notNull(resources, "resources must not be null");
			Assert.notNull(ttlMs, "ttlMs must not be null");
			Assert.notNull(cacheScope, "cacheScope must not be null");
			this.resultType = resultType == null ? ResultType.COMPLETE : resultType;
			this.resources = resources;
			this.nextCursor = nextCursor;
			this.ttlMs = ttlMs;
			this.cacheScope = cacheScope;
			this.meta = meta;
		}

		@JsonCreator
		static ListResourcesResult fromJson(@JsonProperty("resources") List<Resource> resources,
				@JsonProperty("nextCursor") String nextCursor, @JsonProperty("ttlMs") Long ttlMs,
				@JsonProperty("cacheScope") CacheScope cacheScope, @JsonProperty("resultType") String resultType,
				@JsonProperty("_meta") Map<String, Object> meta) {
			resources = resources == null ? Collections.emptyList() : resources;
			ttlMs = ttlMs == null ? 0L : ttlMs;
			cacheScope = cacheScope == null ? CacheScope.PRIVATE : cacheScope;
			return new ListResourcesResult(resources, nextCursor, ttlMs, cacheScope, resultType, meta);
		}

		public List<Resource> resources() {
			return this.resources;
		}

		public String nextCursor() {
			return this.nextCursor;
		}

		@Override
		public Long ttlMs() {
			return this.ttlMs;
		}

		@Override
		public CacheScope cacheScope() {
			return this.cacheScope;
		}

		@Override
		public String resultType() {
			return this.resultType;
		}

		@Override
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
					&& Objects.equals(this.ttlMs, that.ttlMs) && Objects.equals(this.cacheScope, that.cacheScope)
					&& Objects.equals(this.resultType, that.resultType) && Objects.equals(this.meta, that.meta);
		}

		@Override
		public int hashCode() {
			return Objects.hash(this.resources, this.nextCursor, this.ttlMs, this.cacheScope, this.resultType,
					this.meta);
		}

		@Override
		public String toString() {
			return "ListResourcesResult[resources=" + this.resources + ", nextCursor=" + this.nextCursor + ", ttlMs="
					+ this.ttlMs + ", cacheScope=" + this.cacheScope + ", resultType=" + this.resultType + ", meta="
					+ this.meta + "]";
		}

		public static Builder builder(List<Resource> resources) {
			return new Builder(resources);
		}

		public static final class Builder {

			private final List<Resource> resources;

			private String nextCursor;

			private Long ttlMs = 0L;

			private CacheScope cacheScope = CacheScope.PRIVATE;

			private Map<String, Object> meta;

			private Builder(List<Resource> resources) {
				Assert.notNull(resources, "resources must not be null");
				this.resources = resources;
			}

			public Builder nextCursor(String nextCursor) {
				this.nextCursor = nextCursor;
				return this;
			}

			public Builder ttlMs(long ttlMs) {
				Assert.isTrue(ttlMs >= 0, "ttlMs must not be negative");
				this.ttlMs = ttlMs;
				return this;
			}

			public Builder cacheScope(CacheScope cacheScope) {
				this.cacheScope = cacheScope;
				return this;
			}

			public Builder meta(Map<String, Object> meta) {
				this.meta = meta;
				return this;
			}

			public ListResourcesResult build() {
				return new ListResourcesResult(resources, nextCursor, ttlMs, cacheScope, ResultType.COMPLETE, meta);
			}

		}
	}

	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static final class ListResourceTemplatesResult implements CacheableResult {

		@JsonProperty("resourceTemplates")
		private final List<ResourceTemplate> resourceTemplates;

		@JsonProperty("nextCursor")
		private final String nextCursor;

		@JsonProperty("ttlMs")
		private final Long ttlMs;

		@JsonProperty("cacheScope")
		private final CacheScope cacheScope;

		@JsonProperty("resultType")
		private final String resultType;

		@JsonProperty("_meta")
		private final Map<String, Object> meta;

		public ListResourceTemplatesResult(List<ResourceTemplate> resourceTemplates, String nextCursor, Long ttlMs,
				CacheScope cacheScope, String resultType, Map<String, Object> meta) {
			Assert.notNull(resourceTemplates, "resourceTemplates must not be null");
			Assert.notNull(ttlMs, "ttlMs must not be null");
			Assert.notNull(cacheScope, "cacheScope must not be null");
			this.resultType = resultType == null ? ResultType.COMPLETE : resultType;
			this.resourceTemplates = resourceTemplates;
			this.nextCursor = nextCursor;
			this.ttlMs = ttlMs;
			this.cacheScope = cacheScope;
			this.meta = meta;
		}

		@JsonCreator
		static ListResourceTemplatesResult fromJson(
				@JsonProperty("resourceTemplates") List<ResourceTemplate> resourceTemplates,
				@JsonProperty("nextCursor") String nextCursor, @JsonProperty("ttlMs") Long ttlMs,
				@JsonProperty("cacheScope") CacheScope cacheScope, @JsonProperty("resultType") String resultType,
				@JsonProperty("_meta") Map<String, Object> meta) {
			resourceTemplates = resourceTemplates == null ? Collections.emptyList() : resourceTemplates;
			ttlMs = ttlMs == null ? 0L : ttlMs;
			cacheScope = cacheScope == null ? CacheScope.PRIVATE : cacheScope;
			return new ListResourceTemplatesResult(resourceTemplates, nextCursor, ttlMs, cacheScope, resultType, meta);
		}

		public List<ResourceTemplate> resourceTemplates() {
			return this.resourceTemplates;
		}

		public String nextCursor() {
			return this.nextCursor;
		}

		@Override
		public Long ttlMs() {
			return this.ttlMs;
		}

		@Override
		public CacheScope cacheScope() {
			return this.cacheScope;
		}

		@Override
		public String resultType() {
			return this.resultType;
		}

		@Override
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
					&& Objects.equals(this.nextCursor, that.nextCursor) && Objects.equals(this.ttlMs, that.ttlMs)
					&& Objects.equals(this.cacheScope, that.cacheScope)
					&& Objects.equals(this.resultType, that.resultType) && Objects.equals(this.meta, that.meta);
		}

		@Override
		public int hashCode() {
			return Objects.hash(this.resourceTemplates, this.nextCursor, this.ttlMs, this.cacheScope, this.resultType,
					this.meta);
		}

		@Override
		public String toString() {
			return "ListResourceTemplatesResult[resourceTemplates=" + this.resourceTemplates + ", nextCursor="
					+ this.nextCursor + ", ttlMs=" + this.ttlMs + ", cacheScope=" + this.cacheScope + ", resultType="
					+ this.resultType + ", meta=" + this.meta + "]";
		}

		public static Builder builder(List<ResourceTemplate> resourceTemplates) {
			return new Builder(resourceTemplates);
		}

		public static final class Builder {

			private final List<ResourceTemplate> resourceTemplates;

			private String nextCursor;

			private Long ttlMs = 0L;

			private CacheScope cacheScope = CacheScope.PRIVATE;

			private Map<String, Object> meta;

			private Builder(List<ResourceTemplate> resourceTemplates) {
				Assert.notNull(resourceTemplates, "resourceTemplates must not be null");
				this.resourceTemplates = resourceTemplates;
			}

			public Builder nextCursor(String nextCursor) {
				this.nextCursor = nextCursor;
				return this;
			}

			public Builder ttlMs(long ttlMs) {
				Assert.isTrue(ttlMs >= 0, "ttlMs must not be negative");
				this.ttlMs = ttlMs;
				return this;
			}

			public Builder cacheScope(CacheScope cacheScope) {
				this.cacheScope = cacheScope;
				return this;
			}

			public Builder meta(Map<String, Object> meta) {
				this.meta = meta;
				return this;
			}

			public ListResourceTemplatesResult build() {
				return new ListResourceTemplatesResult(resourceTemplates, nextCursor, ttlMs, cacheScope,
						ResultType.COMPLETE, meta);
			}

		}
	}

	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static final class ListPromptsResult implements CacheableResult {

		@JsonProperty("prompts")
		private final List<Prompt> prompts;

		@JsonProperty("nextCursor")
		private final String nextCursor;

		@JsonProperty("ttlMs")
		private final Long ttlMs;

		@JsonProperty("cacheScope")
		private final CacheScope cacheScope;

		@JsonProperty("resultType")
		private final String resultType;

		@JsonProperty("_meta")
		private final Map<String, Object> meta;

		public ListPromptsResult(List<Prompt> prompts, String nextCursor, Long ttlMs, CacheScope cacheScope,
				String resultType, Map<String, Object> meta) {
			Assert.notNull(prompts, "prompts must not be null");
			Assert.notNull(ttlMs, "ttlMs must not be null");
			Assert.notNull(cacheScope, "cacheScope must not be null");
			this.resultType = resultType == null ? ResultType.COMPLETE : resultType;
			this.prompts = prompts;
			this.nextCursor = nextCursor;
			this.ttlMs = ttlMs;
			this.cacheScope = cacheScope;
			this.meta = meta;
		}

		@JsonCreator
		static ListPromptsResult fromJson(@JsonProperty("prompts") List<Prompt> prompts,
				@JsonProperty("nextCursor") String nextCursor, @JsonProperty("ttlMs") Long ttlMs,
				@JsonProperty("cacheScope") CacheScope cacheScope, @JsonProperty("resultType") String resultType,
				@JsonProperty("_meta") Map<String, Object> meta) {
			prompts = prompts == null ? Collections.emptyList() : prompts;
			ttlMs = ttlMs == null ? 0L : ttlMs;
			cacheScope = cacheScope == null ? CacheScope.PRIVATE : cacheScope;
			return new ListPromptsResult(prompts, nextCursor, ttlMs, cacheScope, resultType, meta);
		}

		public List<Prompt> prompts() {
			return this.prompts;
		}

		public String nextCursor() {
			return this.nextCursor;
		}

		@Override
		public Long ttlMs() {
			return this.ttlMs;
		}

		@Override
		public CacheScope cacheScope() {
			return this.cacheScope;
		}

		@Override
		public String resultType() {
			return this.resultType;
		}

		@Override
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
					&& Objects.equals(this.ttlMs, that.ttlMs) && Objects.equals(this.cacheScope, that.cacheScope)
					&& Objects.equals(this.resultType, that.resultType) && Objects.equals(this.meta, that.meta);
		}

		@Override
		public int hashCode() {
			return Objects.hash(this.prompts, this.nextCursor, this.ttlMs, this.cacheScope, this.resultType, this.meta);
		}

		@Override
		public String toString() {
			return "ListPromptsResult[prompts=" + this.prompts + ", nextCursor=" + this.nextCursor + ", ttlMs="
					+ this.ttlMs + ", cacheScope=" + this.cacheScope + ", resultType=" + this.resultType + ", meta="
					+ this.meta + "]";
		}

		public static Builder builder(List<Prompt> prompts) {
			return new Builder(prompts);
		}

		public static final class Builder {

			private final List<Prompt> prompts;

			private String nextCursor;

			private Long ttlMs = 0L;

			private CacheScope cacheScope = CacheScope.PRIVATE;

			private Map<String, Object> meta;

			private Builder(List<Prompt> prompts) {
				Assert.notNull(prompts, "prompts must not be null");
				this.prompts = prompts;
			}

			public Builder nextCursor(String nextCursor) {
				this.nextCursor = nextCursor;
				return this;
			}

			public Builder ttlMs(long ttlMs) {
				Assert.isTrue(ttlMs >= 0, "ttlMs must not be negative");
				this.ttlMs = ttlMs;
				return this;
			}

			public Builder cacheScope(CacheScope cacheScope) {
				this.cacheScope = cacheScope;
				return this;
			}

			public Builder meta(Map<String, Object> meta) {
				this.meta = meta;
				return this;
			}

			public ListPromptsResult build() {
				return new ListPromptsResult(prompts, nextCursor, ttlMs, cacheScope, ResultType.COMPLETE, meta);
			}

		}
	}

	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static final class ReadResourceResult implements CacheableResult, ReadResourceOutcome {

		@JsonProperty("contents")
		private final List<ResourceContents> contents;

		@JsonProperty("ttlMs")
		private final Long ttlMs;

		@JsonProperty("cacheScope")
		private final CacheScope cacheScope;

		@JsonProperty("resultType")
		private final String resultType;

		@JsonProperty("_meta")
		private final Map<String, Object> meta;

		public ReadResourceResult(List<ResourceContents> contents, Long ttlMs, CacheScope cacheScope,
				String resultType, Map<String, Object> meta) {
			Assert.notNull(contents, "contents must not be null");
			Assert.notNull(ttlMs, "ttlMs must not be null");
			Assert.notNull(cacheScope, "cacheScope must not be null");
			this.resultType = resultType == null ? ResultType.COMPLETE : resultType;
			this.contents = contents;
			this.ttlMs = ttlMs;
			this.cacheScope = cacheScope;
			this.meta = meta;
		}

		@JsonCreator
		static ReadResourceResult fromJson(@JsonProperty("contents") List<ResourceContents> contents,
				@JsonProperty("ttlMs") Long ttlMs, @JsonProperty("cacheScope") CacheScope cacheScope,
				@JsonProperty("resultType") String resultType, @JsonProperty("_meta") Map<String, Object> meta) {
			contents = contents == null ? Collections.emptyList() : contents;
			ttlMs = ttlMs == null ? 0L : ttlMs;
			cacheScope = cacheScope == null ? CacheScope.PRIVATE : cacheScope;
			return new ReadResourceResult(contents, ttlMs, cacheScope, resultType, meta);
		}

		public List<ResourceContents> contents() {
			return this.contents;
		}

		@Override
		public Long ttlMs() {
			return this.ttlMs;
		}

		@Override
		public CacheScope cacheScope() {
			return this.cacheScope;
		}

		@Override
		public String resultType() {
			return this.resultType;
		}

		@Override
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
			return Objects.equals(this.contents, that.contents) && Objects.equals(this.ttlMs, that.ttlMs)
					&& Objects.equals(this.cacheScope, that.cacheScope)
					&& Objects.equals(this.resultType, that.resultType) && Objects.equals(this.meta, that.meta);
		}

		@Override
		public int hashCode() {
			return Objects.hash(this.contents, this.ttlMs, this.cacheScope, this.resultType, this.meta);
		}

		@Override
		public String toString() {
			return "ReadResourceResult[contents=" + this.contents + ", ttlMs=" + this.ttlMs + ", cacheScope="
					+ this.cacheScope + ", resultType=" + this.resultType + ", meta=" + this.meta + "]";
		}

		public static Builder builder(List<ResourceContents> contents) {
			return new Builder(contents);
		}

		public static final class Builder {

			private final List<ResourceContents> contents;

			private Long ttlMs = 0L;

			private CacheScope cacheScope = CacheScope.PRIVATE;

			private Map<String, Object> meta;

			private Builder(List<ResourceContents> contents) {
				Assert.notNull(contents, "contents must not be null");
				this.contents = contents;
			}

			public Builder ttlMs(long ttlMs) {
				Assert.isTrue(ttlMs >= 0, "ttlMs must not be negative");
				this.ttlMs = ttlMs;
				return this;
			}

			public Builder cacheScope(CacheScope cacheScope) {
				this.cacheScope = cacheScope;
				return this;
			}

			public Builder meta(Map<String, Object> meta) {
				this.meta = meta;
				return this;
			}

			public ReadResourceResult build() {
				return new ReadResourceResult(contents, ttlMs, cacheScope, ResultType.COMPLETE, meta);
			}

		}
	}

	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static final class CallToolResult implements CallToolOutcome {

		@JsonProperty("content")
		private final List<Content> content;

		@JsonProperty("structuredContent")
		private final Object structuredContent;

		@JsonProperty("isError")
		private final Boolean isError;

		@JsonProperty("resultType")
		private final String resultType;

		@JsonProperty("_meta")
		private final Map<String, Object> meta;

		public CallToolResult(List<Content> content, Object structuredContent, Boolean isError, String resultType,
				Map<String, Object> meta) {
			Assert.notNull(content, "content must not be null");
			this.resultType = resultType == null ? ResultType.COMPLETE : resultType;
			this.content = content;
			this.structuredContent = structuredContent;
			this.isError = isError;
			this.meta = meta;
		}

		@JsonCreator
		static CallToolResult fromJson(@JsonProperty("content") List<Content> content,
				@JsonProperty("structuredContent") Object structuredContent, @JsonProperty("isError") Boolean isError,
				@JsonProperty("resultType") String resultType, @JsonProperty("_meta") Map<String, Object> meta) {
			content = content == null ? Collections.emptyList() : content;
			return new CallToolResult(content, structuredContent, isError, resultType, meta);
		}

		public List<Content> content() {
			return this.content;
		}

		public Object structuredContent() {
			return this.structuredContent;
		}

		public Boolean isError() {
			return this.isError;
		}

		@Override
		public String resultType() {
			return this.resultType;
		}

		@Override
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
			return Objects.equals(this.content, that.content)
					&& Objects.equals(this.structuredContent, that.structuredContent)
					&& Objects.equals(this.isError, that.isError) && Objects.equals(this.resultType, that.resultType)
					&& Objects.equals(this.meta, that.meta);
		}

		@Override
		public int hashCode() {
			return Objects.hash(this.content, this.structuredContent, this.isError, this.resultType, this.meta);
		}

		@Override
		public String toString() {
			return "CallToolResult[content=" + this.content + ", structuredContent=" + this.structuredContent
					+ ", isError=" + this.isError + ", resultType=" + this.resultType + ", meta=" + this.meta + "]";
		}

		public static Builder builder() {
			return new Builder();
		}

		public static final class Builder {

			private List<Content> content = new ArrayList<>();

			private Object structuredContent;

			private Boolean isError;

			private Map<String, Object> meta;

			public Builder content(List<Content> content) {
				Assert.notNull(content, "content must not be null");
				this.content = new ArrayList<>(content);
				return this;
			}

			public Builder addContent(Content contentItem) {
				Assert.notNull(contentItem, "contentItem must not be null");
				this.content.add(contentItem);
				return this;
			}

			public Builder structuredContent(Object structuredContent) {
				this.structuredContent = structuredContent;
				return this;
			}

			public Builder isError(boolean isError) {
				this.isError = isError;
				return this;
			}

			public Builder meta(Map<String, Object> meta) {
				this.meta = meta;
				return this;
			}

			public CallToolResult build() {
				return new CallToolResult(content, structuredContent, isError, ResultType.COMPLETE, meta);
			}

		}
	}

	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static final class GetPromptResult implements GetPromptOutcome {

		@JsonProperty("description")
		private final String description;

		@JsonProperty("messages")
		private final List<PromptMessage> messages;

		@JsonProperty("resultType")
		private final String resultType;

		@JsonProperty("_meta")
		private final Map<String, Object> meta;

		public GetPromptResult(String description, List<PromptMessage> messages, String resultType,
				Map<String, Object> meta) {
			Assert.notNull(messages, "messages must not be null");
			this.resultType = resultType == null ? ResultType.COMPLETE : resultType;
			this.description = description;
			this.messages = messages;
			this.meta = meta;
		}

		@JsonCreator
		static GetPromptResult fromJson(@JsonProperty("description") String description,
				@JsonProperty("messages") List<PromptMessage> messages, @JsonProperty("resultType") String resultType,
				@JsonProperty("_meta") Map<String, Object> meta) {
			messages = messages == null ? Collections.emptyList() : messages;
			return new GetPromptResult(description, messages, resultType, meta);
		}

		public String description() {
			return this.description;
		}

		public List<PromptMessage> messages() {
			return this.messages;
		}

		@Override
		public String resultType() {
			return this.resultType;
		}

		@Override
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
			return Objects.equals(this.description, that.description) && Objects.equals(this.messages, that.messages)
					&& Objects.equals(this.resultType, that.resultType) && Objects.equals(this.meta, that.meta);
		}

		@Override
		public int hashCode() {
			return Objects.hash(this.description, this.messages, this.resultType, this.meta);
		}

		@Override
		public String toString() {
			return "GetPromptResult[description=" + this.description + ", messages=" + this.messages
					+ ", resultType=" + this.resultType + ", meta=" + this.meta + "]";
		}

		public static Builder builder(List<PromptMessage> messages) {
			return new Builder(messages);
		}

		public static final class Builder {

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
				return new GetPromptResult(description, messages, ResultType.COMPLETE, meta);
			}

		}
	}

	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static final class CompleteResult implements Result {

		@JsonProperty("completion")
		private final Completion completion;

		@JsonProperty("resultType")
		private final String resultType;

		@JsonProperty("_meta")
		private final Map<String, Object> meta;

		public CompleteResult(Completion completion, String resultType, Map<String, Object> meta) {
			Assert.notNull(completion, "completion must not be null");
			this.resultType = resultType == null ? ResultType.COMPLETE : resultType;
			this.completion = completion;
			this.meta = meta;
		}

		@JsonCreator
		static CompleteResult fromJson(@JsonProperty("completion") Completion completion,
				@JsonProperty("resultType") String resultType, @JsonProperty("_meta") Map<String, Object> meta) {
			completion = completion == null ? new Completion(Collections.emptyList(), null, null) : completion;
			return new CompleteResult(completion, resultType, meta);
		}

		public Completion completion() {
			return this.completion;
		}

		@Override
		public String resultType() {
			return this.resultType;
		}

		@Override
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
			return Objects.equals(this.completion, that.completion) && Objects.equals(this.resultType, that.resultType)
					&& Objects.equals(this.meta, that.meta);
		}

		@Override
		public int hashCode() {
			return Objects.hash(this.completion, this.resultType, this.meta);
		}

		@Override
		public String toString() {
			return "CompleteResult[completion=" + this.completion + ", resultType=" + this.resultType + ", meta="
					+ this.meta + "]";
		}

		public static CompleteResult of(Completion completion) {
			return new CompleteResult(completion, ResultType.COMPLETE, null);
		}

		@JsonInclude(JsonInclude.Include.NON_ABSENT)
		@JsonIgnoreProperties(ignoreUnknown = true)
		public static final class Completion {

			@JsonProperty("values")
			private final List<String> values;

			@JsonProperty("total")
			private final Integer total;

			@JsonProperty("hasMore")
			private final Boolean hasMore;

			@JsonCreator
			public Completion( // @formatter:off
					@JsonProperty("values") List<String> values,
					@JsonProperty("total") Integer total,
					@JsonProperty("hasMore") Boolean hasMore) { // @formatter:on
				Assert.notNull(values, "values must not be null");
				this.values = values;
				this.total = total;
				this.hasMore = hasMore;
			}

			public Completion(List<String> values) {
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
				if (!(o instanceof Completion)) {
					return false;
				}
				Completion that = (Completion) o;
				return Objects.equals(this.values, that.values) && Objects.equals(this.total, that.total)
						&& Objects.equals(this.hasMore, that.hasMore);
			}

			@Override
			public int hashCode() {
				return Objects.hash(this.values, this.total, this.hasMore);
			}

			@Override
			public String toString() {
				return "Completion[values=" + this.values + ", total=" + this.total + ", hasMore=" + this.hasMore + "]";
			}
		}
	}

	// ---------------------------
	// MRTR (multi round-trip requests) - input-required results and payloads
	// ---------------------------

	/**
	 * One server-requested input, keyed by a server-assigned name inside
	 * {@link InputRequiredResult#inputRequests()}. {@code params} is one of
	 * {@link ElicitFormRequest}, {@link ElicitUrlRequest}, {@link CreateMessageRequest}
	 * or a bare "list roots" marker (an empty object).
	 */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static final class InputRequest {

		@JsonProperty("method")
		private final String method;

		@JsonProperty("params")
		private final Object params;

		@JsonCreator
		public InputRequest(@JsonProperty("method") String method, @JsonProperty("params") Object params) {
			Assert.hasText(method, "method must not be empty");
			this.method = method;
			this.params = params;
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
			if (!(o instanceof InputRequest)) {
				return false;
			}
			InputRequest that = (InputRequest) o;
			return Objects.equals(this.method, that.method) && Objects.equals(this.params, that.params);
		}

		@Override
		public int hashCode() {
			return Objects.hash(this.method, this.params);
		}

		@Override
		public String toString() {
			return "InputRequest[method=" + this.method + ", params=" + this.params + "]";
		}

		public static InputRequest elicit(ElicitFormRequest request) {
			return new InputRequest(McpSchema.METHOD_ELICITATION_CREATE, request);
		}

		public static InputRequest elicitUrl(String message, String url) {
			return new InputRequest(McpSchema.METHOD_ELICITATION_CREATE, new ElicitUrlRequest(message, url, null));
		}

		public static InputRequest createMessage(CreateMessageRequest request) {
			return new InputRequest(McpSchema.METHOD_SAMPLING_CREATE_MESSAGE, request);
		}

		public static InputRequest listRoots() {
			return new InputRequest(McpSchema.METHOD_ROOTS_LIST, Collections.emptyMap());
		}
	}

	/**
	 * A URL-mode elicitation request. Unlike the legacy revision's
	 * {@code ElicitUrlRequest}, this one carries no {@code elicitationId}; the retry
	 * correlates through the {@link InputRequiredResult}'s server-assigned key instead.
	 */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static final class ElicitUrlRequest {

		@JsonProperty("message")
		private final String message;

		@JsonProperty("url")
		private final String url;

		@JsonProperty("_meta")
		private final Map<String, Object> meta;

		public static final String MODE = "url";

		@JsonCreator
		public ElicitUrlRequest( // @formatter:off
				@JsonProperty("message") String message,
				@JsonProperty("url") String url,
				@JsonProperty("_meta") Map<String, Object> meta) { // @formatter:on
			Assert.notNull(message, "message must not be null");
			Assert.notNull(url, "url must not be null");
			this.message = message;
			this.url = url;
			this.meta = meta;
		}

		public String message() {
			return this.message;
		}

		public String url() {
			return this.url;
		}

		public Map<String, Object> meta() {
			return this.meta;
		}

		@JsonProperty("mode")
		public String mode() {
			return MODE;
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
					&& Objects.equals(this.meta, that.meta);
		}

		@Override
		public int hashCode() {
			return Objects.hash(this.message, this.url, this.meta);
		}

		@Override
		public String toString() {
			return "ElicitUrlRequest[message=" + this.message + ", url=" + this.url + ", meta=" + this.meta + "]";
		}
	}

	/**
	 * A result signalling that the server needs more input before it can complete the
	 * original request. Only {@code tools/call}, {@code resources/read} and
	 * {@code prompts/get} may return it.
	 */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static final class InputRequiredResult
			implements InputRequired, CallToolOutcome, GetPromptOutcome, ReadResourceOutcome {

		@JsonProperty("inputRequests")
		private final Map<String, InputRequest> inputRequests;

		@JsonProperty("requestState")
		private final String requestState;

		@JsonProperty("resultType")
		private final String resultType;

		@JsonProperty("_meta")
		private final Map<String, Object> meta;

		public InputRequiredResult(Map<String, InputRequest> inputRequests, String requestState, String resultType,
				Map<String, Object> meta) {
			Assert.isTrue(inputRequests != null || requestState != null,
					"at least one of inputRequests or requestState must be present");
			this.resultType = ResultType.INPUT_REQUIRED;
			this.inputRequests = inputRequests;
			this.requestState = requestState;
			this.meta = meta;
		}

		@Override
		public Map<String, InputRequest> inputRequests() {
			return this.inputRequests;
		}

		@Override
		public String requestState() {
			return this.requestState;
		}

		@Override
		public String resultType() {
			return this.resultType;
		}

		@Override
		public Map<String, Object> meta() {
			return this.meta;
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) {
				return true;
			}
			if (!(o instanceof InputRequiredResult)) {
				return false;
			}
			InputRequiredResult that = (InputRequiredResult) o;
			return Objects.equals(this.inputRequests, that.inputRequests)
					&& Objects.equals(this.requestState, that.requestState)
					&& Objects.equals(this.resultType, that.resultType) && Objects.equals(this.meta, that.meta);
		}

		@Override
		public int hashCode() {
			return Objects.hash(this.inputRequests, this.requestState, this.resultType, this.meta);
		}

		@Override
		public String toString() {
			return "InputRequiredResult[inputRequests=" + this.inputRequests + ", requestState=" + this.requestState
					+ ", resultType=" + this.resultType + ", meta=" + this.meta + "]";
		}

		public static Builder builder() {
			return new Builder();
		}

		public static final class Builder {

			private final Map<String, InputRequest> inputRequests = new HashMap<>();

			private String requestState;

			private Map<String, Object> meta;

			public Builder elicit(String key, ElicitFormRequest request) {
				this.inputRequests.put(key, InputRequest.elicit(request));
				return this;
			}

			public Builder elicitUrl(String key, String message, String url) {
				this.inputRequests.put(key, InputRequest.elicitUrl(message, url));
				return this;
			}

			public Builder createMessage(String key, CreateMessageRequest request) {
				this.inputRequests.put(key, InputRequest.createMessage(request));
				return this;
			}

			public Builder listRoots(String key) {
				this.inputRequests.put(key, InputRequest.listRoots());
				return this;
			}

			public Builder requestState(String requestState) {
				this.requestState = requestState;
				return this;
			}

			public Builder meta(Map<String, Object> meta) {
				this.meta = meta;
				return this;
			}

			public InputRequiredResult build() {
				return new InputRequiredResult(
						inputRequests.isEmpty() ? null : Collections.unmodifiableMap(new LinkedHashMap<>(inputRequests)),
						requestState, ResultType.INPUT_REQUIRED, meta);
			}

		}
	}

	// ---------------------------
	// Subscriptions
	// ---------------------------

	/**
	 * The set of change notifications a client opts into via
	 * {@code subscriptions/listen}.
	 */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static final class SubscriptionFilter {

		@JsonProperty("toolsListChanged")
		private final Boolean toolsListChanged;

		@JsonProperty("promptsListChanged")
		private final Boolean promptsListChanged;

		@JsonProperty("resourcesListChanged")
		private final Boolean resourcesListChanged;

		@JsonProperty("resourceSubscriptions")
		private final List<String> resourceSubscriptions;

		@JsonCreator
		public SubscriptionFilter( // @formatter:off
				@JsonProperty("toolsListChanged") Boolean toolsListChanged,
				@JsonProperty("promptsListChanged") Boolean promptsListChanged,
				@JsonProperty("resourcesListChanged") Boolean resourcesListChanged,
				@JsonProperty("resourceSubscriptions") List<String> resourceSubscriptions) { // @formatter:on
			this.toolsListChanged = toolsListChanged;
			this.promptsListChanged = promptsListChanged;
			this.resourcesListChanged = resourcesListChanged;
			this.resourceSubscriptions = resourceSubscriptions;
		}

		public static final SubscriptionFilter EMPTY = new SubscriptionFilter(null, null, null, null);

		public Boolean toolsListChanged() {
			return this.toolsListChanged;
		}

		public Boolean promptsListChanged() {
			return this.promptsListChanged;
		}

		public Boolean resourcesListChanged() {
			return this.resourcesListChanged;
		}

		public List<String> resourceSubscriptions() {
			return this.resourceSubscriptions;
		}

		public boolean wantsToolsListChanged() {
			return Boolean.TRUE.equals(this.toolsListChanged);
		}

		public boolean wantsPromptsListChanged() {
			return Boolean.TRUE.equals(this.promptsListChanged);
		}

		public boolean wantsResourcesListChanged() {
			return Boolean.TRUE.equals(this.resourcesListChanged);
		}

		public List<String> resourceSubscriptionsOrEmpty() {
			return this.resourceSubscriptions == null ? Collections.emptyList() : this.resourceSubscriptions;
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) {
				return true;
			}
			if (!(o instanceof SubscriptionFilter)) {
				return false;
			}
			SubscriptionFilter that = (SubscriptionFilter) o;
			return Objects.equals(this.toolsListChanged, that.toolsListChanged)
					&& Objects.equals(this.promptsListChanged, that.promptsListChanged)
					&& Objects.equals(this.resourcesListChanged, that.resourcesListChanged)
					&& Objects.equals(this.resourceSubscriptions, that.resourceSubscriptions);
		}

		@Override
		public int hashCode() {
			return Objects.hash(this.toolsListChanged, this.promptsListChanged, this.resourcesListChanged,
					this.resourceSubscriptions);
		}

		@Override
		public String toString() {
			return "SubscriptionFilter[toolsListChanged=" + this.toolsListChanged + ", promptsListChanged="
					+ this.promptsListChanged + ", resourcesListChanged=" + this.resourcesListChanged
					+ ", resourceSubscriptions=" + this.resourceSubscriptions + "]";
		}
	}

	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static final class SubscriptionsListenRequest {

		@JsonProperty("notifications")
		private final SubscriptionFilter notifications;

		@JsonProperty("_meta")
		private final Map<String, Object> meta;

		public SubscriptionsListenRequest(SubscriptionFilter notifications, Map<String, Object> meta) {
			this.notifications = notifications;
			this.meta = meta;
		}

		@JsonCreator
		static SubscriptionsListenRequest fromJson(@JsonProperty("notifications") SubscriptionFilter notifications,
				@JsonProperty("_meta") Map<String, Object> meta) {
			return new SubscriptionsListenRequest(notifications == null ? SubscriptionFilter.EMPTY : notifications,
					meta);
		}

		public SubscriptionFilter notifications() {
			return this.notifications;
		}

		public Map<String, Object> meta() {
			return this.meta;
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) {
				return true;
			}
			if (!(o instanceof SubscriptionsListenRequest)) {
				return false;
			}
			SubscriptionsListenRequest that = (SubscriptionsListenRequest) o;
			return Objects.equals(this.notifications, that.notifications) && Objects.equals(this.meta, that.meta);
		}

		@Override
		public int hashCode() {
			return Objects.hash(this.notifications, this.meta);
		}

		@Override
		public String toString() {
			return "SubscriptionsListenRequest[notifications=" + this.notifications + ", meta=" + this.meta + "]";
		}
	}

	/**
	 * Params of {@code notifications/subscriptions/acknowledged}: the honoured subset.
	 */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static final class SubscriptionsAcknowledgedParams {

		@JsonProperty("notifications")
		private final SubscriptionFilter notifications;

		@JsonProperty("_meta")
		private final Map<String, Object> meta;

		@JsonCreator
		public SubscriptionsAcknowledgedParams(@JsonProperty("notifications") SubscriptionFilter notifications,
				@JsonProperty("_meta") Map<String, Object> meta) {
			this.notifications = notifications;
			this.meta = meta;
		}

		public SubscriptionFilter notifications() {
			return this.notifications;
		}

		public Map<String, Object> meta() {
			return this.meta;
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) {
				return true;
			}
			if (!(o instanceof SubscriptionsAcknowledgedParams)) {
				return false;
			}
			SubscriptionsAcknowledgedParams that = (SubscriptionsAcknowledgedParams) o;
			return Objects.equals(this.notifications, that.notifications) && Objects.equals(this.meta, that.meta);
		}

		@Override
		public int hashCode() {
			return Objects.hash(this.notifications, this.meta);
		}

		@Override
		public String toString() {
			return "SubscriptionsAcknowledgedParams[notifications=" + this.notifications + ", meta=" + this.meta + "]";
		}
	}

	/** Params of {@code notifications/resources/updated}. */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static final class ResourceUpdatedParams {

		@JsonProperty("uri")
		private final String uri;

		@JsonProperty("_meta")
		private final Map<String, Object> meta;

		@JsonCreator
		public ResourceUpdatedParams(@JsonProperty("uri") String uri,
				@JsonProperty("_meta") Map<String, Object> meta) {
			Assert.hasText(uri, "uri must not be empty");
			this.uri = uri;
			this.meta = meta;
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
			if (!(o instanceof ResourceUpdatedParams)) {
				return false;
			}
			ResourceUpdatedParams that = (ResourceUpdatedParams) o;
			return Objects.equals(this.uri, that.uri) && Objects.equals(this.meta, that.meta);
		}

		@Override
		public int hashCode() {
			return Objects.hash(this.uri, this.meta);
		}

		@Override
		public String toString() {
			return "ResourceUpdatedParams[uri=" + this.uri + ", meta=" + this.meta + "]";
		}
	}

	/** Params of {@code notifications/{tools,prompts,resources}/list_changed}. */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static final class ListChangedParams {

		@JsonProperty("_meta")
		private final Map<String, Object> meta;

		@JsonCreator
		public ListChangedParams(@JsonProperty("_meta") Map<String, Object> meta) {
			this.meta = meta;
		}

		public static final ListChangedParams EMPTY = new ListChangedParams(null);

		public Map<String, Object> meta() {
			return this.meta;
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) {
				return true;
			}
			if (!(o instanceof ListChangedParams)) {
				return false;
			}
			ListChangedParams that = (ListChangedParams) o;
			return Objects.equals(this.meta, that.meta);
		}

		@Override
		public int hashCode() {
			return Objects.hash(this.meta);
		}

		@Override
		public String toString() {
			return "ListChangedParams[meta=" + this.meta + "]";
		}

	}

	/** The terminal result of a server-closed {@code subscriptions/listen} stream. */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static final class SubscriptionsListenResult implements Result {

		@JsonProperty("resultType")
		private final String resultType;

		@JsonProperty("_meta")
		private final Map<String, Object> meta;

		public SubscriptionsListenResult(String resultType, Map<String, Object> meta) {
			this.resultType = resultType == null ? ResultType.COMPLETE : resultType;
			this.meta = meta;
		}

		@Override
		public String resultType() {
			return this.resultType;
		}

		@Override
		public Map<String, Object> meta() {
			return this.meta;
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) {
				return true;
			}
			if (!(o instanceof SubscriptionsListenResult)) {
				return false;
			}
			SubscriptionsListenResult that = (SubscriptionsListenResult) o;
			return Objects.equals(this.resultType, that.resultType) && Objects.equals(this.meta, that.meta);
		}

		@Override
		public int hashCode() {
			return Objects.hash(this.resultType, this.meta);
		}

		@Override
		public String toString() {
			return "SubscriptionsListenResult[resultType=" + this.resultType + ", meta=" + this.meta + "]";
		}

		public static SubscriptionsListenResult forSubscription(Object subscriptionId) {
			Map<String, Object> meta = new HashMap<>();
			meta.put(MetaKeys.SUBSCRIPTION_ID, subscriptionId);
			return new SubscriptionsListenResult(ResultType.COMPLETE, meta);
		}
	}

	// ---------------------------
	// Other notifications
	// ---------------------------

	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static final class CancelledNotificationParams {

		@JsonProperty("requestId")
		private final Object requestId;

		@JsonProperty("reason")
		private final String reason;

		public CancelledNotificationParams(Object requestId, String reason) {
			Assert.notNull(requestId, "requestId must not be null");
			this.requestId = requestId;
			this.reason = reason;
		}

		@JsonCreator
		static CancelledNotificationParams fromJson(@JsonProperty("requestId") Object requestId,
				@JsonProperty("reason") String reason) {
			if (requestId == null) {
				logger.warn(
						"CancelledNotificationParams: missing required field 'requestId' during deserialization, using default ''");
				requestId = "";
			}
			return new CancelledNotificationParams(requestId, reason);
		}

		public Object requestId() {
			return this.requestId;
		}

		public String reason() {
			return this.reason;
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) {
				return true;
			}
			if (!(o instanceof CancelledNotificationParams)) {
				return false;
			}
			CancelledNotificationParams that = (CancelledNotificationParams) o;
			return Objects.equals(this.requestId, that.requestId) && Objects.equals(this.reason, that.reason);
		}

		@Override
		public int hashCode() {
			return Objects.hash(this.requestId, this.reason);
		}

		@Override
		public String toString() {
			return "CancelledNotificationParams[requestId=" + this.requestId + ", reason=" + this.reason + "]";
		}
	}

	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static final class ProgressParams {

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

		@JsonCreator
		public ProgressParams( // @formatter:off
				@JsonProperty("progressToken") Object progressToken,
				@JsonProperty("progress") Double progress,
				@JsonProperty("total") Double total,
				@JsonProperty("message") String message,
				@JsonProperty("_meta") Map<String, Object> meta) { // @formatter:on
			Assert.notNull(progressToken, "progressToken must not be null");
			Assert.notNull(progress, "progress must not be null");
			this.progressToken = progressToken;
			this.progress = progress;
			this.total = total;
			this.message = message;
			this.meta = meta;
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
			if (!(o instanceof ProgressParams)) {
				return false;
			}
			ProgressParams that = (ProgressParams) o;
			return Objects.equals(this.progressToken, that.progressToken) && Objects.equals(this.progress, that.progress)
					&& Objects.equals(this.total, that.total) && Objects.equals(this.message, that.message)
					&& Objects.equals(this.meta, that.meta);
		}

		@Override
		public int hashCode() {
			return Objects.hash(this.progressToken, this.progress, this.total, this.message, this.meta);
		}

		@Override
		public String toString() {
			return "ProgressParams[progressToken=" + this.progressToken + ", progress=" + this.progress + ", total="
					+ this.total + ", message=" + this.message + ", meta=" + this.meta + "]";
		}
	}

	/** Params of {@code notifications/message}; {@code data} is any JSON value. */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static final class LoggingMessageParams {

		@JsonProperty("level")
		private final LoggingLevel level;

		@JsonProperty("logger")
		private final String logger;

		@JsonProperty("data")
		private final Object data;

		@JsonCreator
		public LoggingMessageParams( // @formatter:off
				@JsonProperty("level") LoggingLevel level,
				@JsonProperty("logger") String logger,
				@JsonProperty("data") Object data) { // @formatter:on
			Assert.notNull(level, "level must not be null");
			Assert.notNull(data, "data must not be null");
			this.level = level;
			this.logger = logger;
			this.data = data;
		}

		public LoggingLevel level() {
			return this.level;
		}

		public String logger() {
			return this.logger;
		}

		public Object data() {
			return this.data;
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) {
				return true;
			}
			if (!(o instanceof LoggingMessageParams)) {
				return false;
			}
			LoggingMessageParams that = (LoggingMessageParams) o;
			return Objects.equals(this.level, that.level) && Objects.equals(this.logger, that.logger)
					&& Objects.equals(this.data, that.data);
		}

		@Override
		public int hashCode() {
			return Objects.hash(this.level, this.logger, this.data);
		}

		@Override
		public String toString() {
			return "LoggingMessageParams[level=" + this.level + ", logger=" + this.logger + ", data=" + this.data + "]";
		}
	}

	// ---------------------------
	// Error data payloads
	// ---------------------------

	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static final class MissingRequiredClientCapabilityData {

		@JsonProperty("requiredCapabilities")
		private final ClientCapabilities requiredCapabilities;

		@JsonCreator
		public MissingRequiredClientCapabilityData(
				@JsonProperty("requiredCapabilities") ClientCapabilities requiredCapabilities) {
			this.requiredCapabilities = requiredCapabilities;
		}

		public ClientCapabilities requiredCapabilities() {
			return this.requiredCapabilities;
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) {
				return true;
			}
			if (!(o instanceof MissingRequiredClientCapabilityData)) {
				return false;
			}
			MissingRequiredClientCapabilityData that = (MissingRequiredClientCapabilityData) o;
			return Objects.equals(this.requiredCapabilities, that.requiredCapabilities);
		}

		@Override
		public int hashCode() {
			return Objects.hash(this.requiredCapabilities);
		}

		@Override
		public String toString() {
			return "MissingRequiredClientCapabilityData[requiredCapabilities=" + this.requiredCapabilities + "]";
		}
	}

	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static final class UnsupportedProtocolVersionData {

		@JsonProperty("supported")
		private final List<String> supported;

		@JsonProperty("requested")
		private final String requested;

		@JsonCreator
		public UnsupportedProtocolVersionData(@JsonProperty("supported") List<String> supported,
				@JsonProperty("requested") String requested) {
			this.supported = supported;
			this.requested = requested;
		}

		public List<String> supported() {
			return this.supported;
		}

		public String requested() {
			return this.requested;
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) {
				return true;
			}
			if (!(o instanceof UnsupportedProtocolVersionData)) {
				return false;
			}
			UnsupportedProtocolVersionData that = (UnsupportedProtocolVersionData) o;
			return Objects.equals(this.supported, that.supported) && Objects.equals(this.requested, that.requested);
		}

		@Override
		public int hashCode() {
			return Objects.hash(this.supported, this.requested);
		}

		@Override
		public String toString() {
			return "UnsupportedProtocolVersionData[supported=" + this.supported + ", requested=" + this.requested + "]";
		}
	}

	// ---------------------------
	// Implementation info
	// ---------------------------

	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static final class Implementation {

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

		public static Builder builder(String name, String version) {
			return new Builder(name, version);
		}

		public static final class Builder {

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
	}

	/**
	 * An icon that can be displayed in a user interface.
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

		public static Builder builder(String src) {
			return new Builder(src);
		}

		public static final class Builder {

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
	}

	// ---------------------------
	// Annotations
	// ---------------------------

	public enum Role {

	// @formatter:off
		@JsonProperty("user") USER,
		@JsonProperty("assistant") ASSISTANT
	} // @formatter:on

	/**
	 * Optional hints for the client on how an object is used or displayed.
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

		public static Builder builder() {
			return new Builder();
		}

		public static final class Builder {

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
	}

	// ---------------------------
	// Resources
	// ---------------------------

	/**
	 * A known resource that the server is capable of reading.
	 */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static final class Resource {

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

		public Resource(String uri, String name, String title, String description, String mimeType, Long size,
				Annotations annotations, Map<String, Object> meta, List<Icon> icons) {
			Assert.notNull(uri, "uri must not be null");
			Assert.notNull(name, "name must not be null");
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

		@JsonCreator
		static Resource fromJson(@JsonProperty("uri") String uri, @JsonProperty("name") String name,
				@JsonProperty("title") String title, @JsonProperty("description") String description,
				@JsonProperty("mimeType") String mimeType, @JsonProperty("size") Long size,
				@JsonProperty("annotations") Annotations annotations, @JsonProperty("_meta") Map<String, Object> meta,
				@JsonProperty("icons") List<Icon> icons) {
			if (uri == null || name == null) {
				List<String> missing = new ArrayList<>();
				if (uri == null) {
					missing.add("uri -> ''");
					uri = "";
				}
				if (name == null) {
					missing.add("name -> ''");
					name = "";
				}
				logger.warn("Resource: missing required fields during deserialization: {}", String.join(", ", missing));
			}
			return new Resource(uri, name, title, description, mimeType, size, annotations, meta, icons);
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

		public static Builder builder(String uri, String name) {
			return new Builder(uri, name);
		}

		public static final class Builder {

			private final String uri;

			private final String name;

			private String title;

			private String description;

			private String mimeType;

			private Long size;

			private Annotations annotations;

			private List<Icon> icons;

			private Map<String, Object> meta;

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
	}

	/**
	 * A parameterized resource, addressed by an RFC 6570 URI template.
	 */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static final class ResourceTemplate {

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

		public ResourceTemplate(String uriTemplate, String name, String title, String description, String mimeType,
				Annotations annotations, Map<String, Object> meta, List<Icon> icons) {
			Assert.notNull(uriTemplate, "uriTemplate must not be null");
			Assert.notNull(name, "name must not be null");
			this.uriTemplate = uriTemplate;
			this.name = name;
			this.title = title;
			this.description = description;
			this.mimeType = mimeType;
			this.annotations = annotations;
			this.meta = meta;
			this.icons = icons;
		}

		@JsonCreator
		static ResourceTemplate fromJson(@JsonProperty("uriTemplate") String uriTemplate,
				@JsonProperty("name") String name, @JsonProperty("title") String title,
				@JsonProperty("description") String description, @JsonProperty("mimeType") String mimeType,
				@JsonProperty("annotations") Annotations annotations, @JsonProperty("_meta") Map<String, Object> meta,
				@JsonProperty("icons") List<Icon> icons) {
			if (uriTemplate == null || name == null) {
				List<String> missing = new ArrayList<>();
				if (uriTemplate == null) {
					missing.add("uriTemplate -> ''");
					uriTemplate = "";
				}
				if (name == null) {
					missing.add("name -> ''");
					name = "";
				}
				logger.warn("ResourceTemplate: missing required fields during deserialization: {}",
						String.join(", ", missing));
			}
			return new ResourceTemplate(uriTemplate, name, title, description, mimeType, annotations, meta, icons);
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

		public static Builder builder(String uriTemplate, String name) {
			return new Builder(uriTemplate, name);
		}

		public static final class Builder {

			private final String uriTemplate;

			private final String name;

			private String title;

			private String description;

			private String mimeType;

			private Annotations annotations;

			private List<Icon> icons;

			private Map<String, Object> meta;

			private Builder(String uriTemplate, String name) {
				Assert.hasText(uriTemplate, "uriTemplate must not be empty");
				Assert.hasText(name, "name must not be empty");
				this.uriTemplate = uriTemplate;
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
	}

	/**
	 * The contents of a specific resource or sub-resource.
	 */
	@JsonTypeInfo(use = JsonTypeInfo.Id.DEDUCTION)
	@JsonSubTypes({ @JsonSubTypes.Type(value = TextResourceContents.class),
			@JsonSubTypes.Type(value = BlobResourceContents.class) })
	public interface ResourceContents {

		String uri();

		String mimeType();

		Map<String, Object> meta();

	}

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

		@Override
		public String uri() {
			return this.uri;
		}

		@Override
		public String mimeType() {
			return this.mimeType;
		}

		public String text() {
			return this.text;
		}

		@Override
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

		public static Builder builder(String uri, String text) {
			return new Builder(uri, text);
		}

		public static final class Builder {

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
	}

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

		@Override
		public String uri() {
			return this.uri;
		}

		@Override
		public String mimeType() {
			return this.mimeType;
		}

		public String blob() {
			return this.blob;
		}

		@Override
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

		public static Builder builder(String uri, String blob) {
			return new Builder(uri, blob);
		}

		public static final class Builder {

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
	}

	// ---------------------------
	// Prompts
	// ---------------------------

	/**
	 * A prompt or prompt template that the server offers.
	 */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static final class Prompt {

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

		public static Builder builder(String name) {
			return new Builder(name);
		}

		public static final class Builder {

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
	}

	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static final class PromptArgument {

		@JsonProperty("name")
		private final String name;

		@JsonProperty("title")
		private final String title;

		@JsonProperty("description")
		private final String description;

		@JsonProperty("required")
		private final Boolean required;

		public PromptArgument(String name, String title, String description, Boolean required) {
			Assert.notNull(name, "name must not be null");
			this.name = name;
			this.title = title;
			this.description = description;
			this.required = required;
		}

		@JsonCreator
		static PromptArgument fromJson(@JsonProperty("name") String name, @JsonProperty("title") String title,
				@JsonProperty("description") String description, @JsonProperty("required") Boolean required) {
			if (name == null) {
				logger.warn("PromptArgument: missing required field 'name' during deserialization, using default ''");
				name = "";
			}
			return new PromptArgument(name, title, description, required);
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

		public static Builder builder(String name) {
			return new Builder(name);
		}

		public static final class Builder {

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
	}

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

		public static Builder builder(Role role, Content content) {
			return new Builder(role, content);
		}

		public static final class Builder {

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
	}

	// ---------------------------
	// Tools
	// ---------------------------

	/**
	 * Hints describing a tool's behaviour. Clients must not make trust decisions based on
	 * annotations received from untrusted servers.
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

		@JsonCreator
		public ToolAnnotations( // @formatter:off
				@JsonProperty("title") String title,
				@JsonProperty("readOnlyHint") Boolean readOnlyHint,
				@JsonProperty("destructiveHint") Boolean destructiveHint,
				@JsonProperty("idempotentHint") Boolean idempotentHint,
				@JsonProperty("openWorldHint") Boolean openWorldHint) { // @formatter:on
			this.title = title;
			this.readOnlyHint = readOnlyHint;
			this.destructiveHint = destructiveHint;
			this.idempotentHint = idempotentHint;
			this.openWorldHint = openWorldHint;
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
					&& Objects.equals(this.openWorldHint, that.openWorldHint);
		}

		@Override
		public int hashCode() {
			return Objects.hash(this.title, this.readOnlyHint, this.destructiveHint, this.idempotentHint,
					this.openWorldHint);
		}

		@Override
		public String toString() {
			return "ToolAnnotations[title=" + this.title + ", readOnlyHint=" + this.readOnlyHint + ", destructiveHint="
					+ this.destructiveHint + ", idempotentHint=" + this.idempotentHint + ", openWorldHint="
					+ this.openWorldHint + "]";
		}

		public static Builder builder() {
			return new Builder();
		}

		public static final class Builder {

			private String title;

			private Boolean readOnlyHint;

			private Boolean destructiveHint;

			private Boolean idempotentHint;

			private Boolean openWorldHint;

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

			public ToolAnnotations build() {
				return new ToolAnnotations(title, readOnlyHint, destructiveHint, idempotentHint, openWorldHint);
			}

		}
	}

	/**
	 * A tool the server exposes. {@code inputSchema} and {@code outputSchema} default to
	 * the JSON Schema 2020-12 dialect unless they carry an explicit {@code $schema}.
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

		public static Builder builder(String name, Map<String, Object> inputSchema) {
			return new Builder(name, inputSchema);
		}

		public static Builder builder(String name, McpJsonMapper jsonMapper, String inputSchema) {
			return new Builder(name, schemaToMap(jsonMapper, inputSchema));
		}

		public static final class Builder {

			private final String name;

			private String title;

			private String description;

			private final Map<String, Object> inputSchema;

			private Map<String, Object> outputSchema;

			private ToolAnnotations annotations;

			private List<Icon> icons;

			private Map<String, Object> meta;

			private Builder(String name, Map<String, Object> inputSchema) {
				Assert.hasText(name, "name must not be empty");
				Assert.notNull(inputSchema, "inputSchema must not be null");
				this.name = name;
				this.inputSchema = inputSchema;
			}

			public Builder title(String title) {
				this.title = title;
				return this;
			}

			public Builder description(String description) {
				this.description = description;
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
				return new Tool(name, title, description, inputSchema, outputSchema, annotations, meta, icons);
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

	// ---------------------------
	// Sampling (MRTR input-request payload)
	// ---------------------------

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

		@JsonCreator
		public ModelPreferences( // @formatter:off
				@JsonProperty("hints") List<ModelHint> hints,
				@JsonProperty("costPriority") Double costPriority,
				@JsonProperty("speedPriority") Double speedPriority,
				@JsonProperty("intelligencePriority") Double intelligencePriority) { // @formatter:on
			this.hints = hints;
			this.costPriority = costPriority;
			this.speedPriority = speedPriority;
			this.intelligencePriority = intelligencePriority;
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

		public static Builder builder() {
			return new Builder();
		}

		public static final class Builder {

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

		public static Builder builder(Role role, Content content) {
			return new Builder(role, content);
		}

		public static final class Builder {

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
	}

	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static final class CreateMessageRequest {

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
					+ ", systemPrompt=" + this.systemPrompt + ", includeContext=" + this.includeContext
					+ ", temperature=" + this.temperature + ", maxTokens=" + this.maxTokens + ", stopSequences="
					+ this.stopSequences + ", metadata=" + this.metadata + ", meta=" + this.meta + "]";
		}

		public enum ContextInclusionStrategy {

		// @formatter:off
			@JsonProperty("none") NONE,
			@JsonProperty("thisServer") THIS_SERVER,
			@JsonProperty("allServers") ALL_SERVERS
		} // @formatter:on

		public static Builder builder(List<SamplingMessage> messages, int maxTokens) {
			return new Builder(messages, maxTokens);
		}

		public static final class Builder {

			private final List<SamplingMessage> messages;

			private ModelPreferences modelPreferences;

			private String systemPrompt;

			private ContextInclusionStrategy includeContext;

			private Double temperature;

			private final int maxTokens;

			private List<String> stopSequences;

			private Map<String, Object> metadata;

			private Map<String, Object> meta;

			private Builder(List<SamplingMessage> messages, int maxTokens) {
				Assert.notNull(messages, "messages must not be null");
				this.messages = messages;
				this.maxTokens = maxTokens;
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

			public CreateMessageRequest build() {
				return new CreateMessageRequest(messages, modelPreferences, systemPrompt, includeContext, temperature,
						maxTokens, stopSequences, metadata, meta);
			}

		}
	}

	// ---------------------------
	// Elicitation (MRTR input-request payload)
	// ---------------------------

	/**
	 * A form-mode elicitation request. {@code requestedSchema} is a restricted subset of
	 * JSON Schema: only top-level properties, no nesting.
	 */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static final class ElicitFormRequest {

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

		public static Builder builder(String message, Map<String, Object> requestedSchema) {
			return new Builder(message, requestedSchema);
		}

		public static final class Builder {

			private final String message;

			private final Map<String, Object> requestedSchema;

			private Map<String, Object> meta;

			private Builder(String message, Map<String, Object> requestedSchema) {
				Assert.notNull(message, "message must not be null");
				Assert.notNull(requestedSchema, "requestedSchema must not be null");
				this.message = message;
				this.requestedSchema = requestedSchema;
			}

			public Builder meta(Map<String, Object> meta) {
				this.meta = meta;
				return this;
			}

			public ElicitFormRequest build() {
				return new ElicitFormRequest(message, requestedSchema, meta);
			}

		}
	}

	// ---------------------------
	// Logging
	// ---------------------------

	/**
	 * Log severities, least to most severe; compare via {@link #level()}. Unrecognized
	 * values deserialize to {@code null}.
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
			BY_NAME = Collections.unmodifiableMap(new LinkedHashMap<>(m));
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

	// ---------------------------
	// Completions
	// ---------------------------

	/**
	 * A prompt or resource-template reference for {@code completion/complete}.
	 */
	@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.EXISTING_PROPERTY, property = "type",
			visible = true)
	@JsonSubTypes({ @JsonSubTypes.Type(value = PromptReference.class, name = PromptReference.TYPE),
			@JsonSubTypes.Type(value = ResourceReference.class, name = ResourceReference.TYPE) })
	public interface CompleteReference {

		String type();

	}

	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static final class PromptReference implements CompleteReference {

		@JsonProperty("type")
		private final String type;

		@JsonProperty("name")
		private final String name;

		@JsonProperty("title")
		private final String title;

		public static final String TYPE = "ref/prompt";

		public PromptReference(String type, String name, String title) {
			Assert.notNull(name, "name must not be null");
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
			if (name == null) {
				logger.warn("PromptReference: missing required field 'name' during deserialization, using default ''");
				name = "";
			}
			return new PromptReference(type, name, title);
		}

		public PromptReference(String name) {
			this(TYPE, name, null);
		}

		@Override
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
		public boolean equals(Object o) {
			if (this == o) {
				return true;
			}
			if (!(o instanceof PromptReference)) {
				return false;
			}
			PromptReference that = (PromptReference) o;
			return Objects.equals(this.type, that.type) && Objects.equals(this.name, that.name)
					&& Objects.equals(this.title, that.title);
		}

		@Override
		public int hashCode() {
			return Objects.hash(this.type, this.name, this.title);
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
				Assert.hasText(name, "name must not be empty");
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
			return TYPE;
		}

		@JsonCreator
		static ResourceReference fromJson(@JsonProperty("uri") String uri, @JsonProperty("type") String type) {
			if (uri == null) {
				logger.warn("ResourceReference: missing required field 'uri' during deserialization, using default ''");
				uri = "";
			}
			return new ResourceReference(uri);
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

	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static final class CompleteRequest implements SelfValidating {

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

		@Override
		public void validate() {
			Assert.notNull(this.ref, "ref must not be null");
			Assert.notNull(this.argument, "argument must not be null");
		}

		@JsonCreator
		static CompleteRequest fromJson(@JsonProperty("ref") CompleteReference ref,
				@JsonProperty("argument") CompleteArgument argument, @JsonProperty("_meta") Map<String, Object> meta,
				@JsonProperty("context") CompleteContext context) {
			// ref is what the request is dispatched on, so it gets no default (fails
			// fast).
			if (argument == null) {
				logger.warn(
						"CompleteRequest: missing required field 'argument' during deserialization, using default {name: '', value: ''}");
				argument = new CompleteArgument("", "");
			}
			return new CompleteRequest(ref, argument, meta, context);
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

		public static Builder builder(CompleteReference ref, CompleteArgument argument) {
			return new Builder(ref, argument);
		}

		public static final class Builder {

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

		@JsonInclude(JsonInclude.Include.NON_ABSENT)
		@JsonIgnoreProperties(ignoreUnknown = true)
		public static final class CompleteArgument {

			@JsonProperty("name")
			private final String name;

			@JsonProperty("value")
			private final String value;

			public CompleteArgument(String name, String value) {
				Assert.notNull(name, "name must not be null");
				Assert.notNull(value, "value must not be null");
				this.name = name;
				this.value = value;
			}

			@JsonCreator
			static CompleteArgument fromJson(@JsonProperty("name") String name, @JsonProperty("value") String value) {
				if (name == null || value == null) {
					List<String> missing = new ArrayList<>();
					if (name == null) {
						missing.add("name -> ''");
						name = "";
					}
					if (value == null) {
						missing.add("value -> ''");
						value = "";
					}
					logger.warn("CompleteArgument: missing required fields during deserialization: {}",
							String.join(", ", missing));
				}
				return new CompleteArgument(name, value);
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

		/** Previously-resolved variables in a URI template or prompt. */
		@JsonInclude(JsonInclude.Include.NON_ABSENT)
		@JsonIgnoreProperties(ignoreUnknown = true)
		public static final class CompleteContext {

			@JsonProperty("arguments")
			private final Map<String, String> arguments;

			@JsonCreator
			public CompleteContext(@JsonProperty("arguments") Map<String, String> arguments) {
				this.arguments = arguments;
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

	// ---------------------------
	// Content
	// ---------------------------

	/**
	 * A content block in a tool result, prompt message or sampling message, discriminated
	 * by its {@code type} property.
	 */
	@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
	@JsonSubTypes({ @JsonSubTypes.Type(value = TextContent.class, name = TextContent.TYPE),
			@JsonSubTypes.Type(value = ImageContent.class, name = ImageContent.TYPE),
			@JsonSubTypes.Type(value = AudioContent.class, name = AudioContent.TYPE),
			@JsonSubTypes.Type(value = EmbeddedResource.class, name = EmbeddedResource.TYPE),
			@JsonSubTypes.Type(value = ResourceLink.class, name = ResourceLink.TYPE) })
	public interface Content {

		@JsonIgnore
		String type();

		Map<String, Object> meta();

	}

	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static final class TextContent implements Content {

		@JsonProperty("annotations")
		private final Annotations annotations;

		@JsonProperty("text")
		private final String text;

		@JsonProperty("_meta")
		private final Map<String, Object> meta;

		public static final String TYPE = "text";

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

		@Override
		public String type() {
			return TYPE;
		}

		public Annotations annotations() {
			return this.annotations;
		}

		public String text() {
			return this.text;
		}

		@Override
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

		public static Builder builder(String text) {
			return new Builder(text);
		}

		public static final class Builder {

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
	}

	/** Base64-encoded image data. */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static final class ImageContent implements Content {

		@JsonProperty("annotations")
		private final Annotations annotations;

		@JsonProperty("data")
		private final String data;

		@JsonProperty("mimeType")
		private final String mimeType;

		@JsonProperty("_meta")
		private final Map<String, Object> meta;

		public static final String TYPE = "image";

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

		@Override
		public String type() {
			return TYPE;
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

		@Override
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
			return "ImageContent[annotations=" + this.annotations + ", data=" + this.data + ", mimeType=" + this.mimeType
					+ ", meta=" + this.meta + "]";
		}

		public static Builder builder(String data, String mimeType) {
			return new Builder(data, mimeType);
		}

		public static final class Builder {

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
	}

	/** Base64-encoded audio data. */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static final class AudioContent implements Content {

		@JsonProperty("annotations")
		private final Annotations annotations;

		@JsonProperty("data")
		private final String data;

		@JsonProperty("mimeType")
		private final String mimeType;

		@JsonProperty("_meta")
		private final Map<String, Object> meta;

		public static final String TYPE = "audio";

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

		@Override
		public String type() {
			return TYPE;
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

		@Override
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
			return "AudioContent[annotations=" + this.annotations + ", data=" + this.data + ", mimeType=" + this.mimeType
					+ ", meta=" + this.meta + "]";
		}

		public static Builder builder(String data, String mimeType) {
			return new Builder(data, mimeType);
		}

		public static final class Builder {

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
	}

	/** Resource contents embedded into a prompt or tool result. */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static final class EmbeddedResource implements Content {

		@JsonProperty("annotations")
		private final Annotations annotations;

		@JsonProperty("resource")
		private final ResourceContents resource;

		@JsonProperty("_meta")
		private final Map<String, Object> meta;

		public static final String TYPE = "resource";

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

		@Override
		public String type() {
			return TYPE;
		}

		public Annotations annotations() {
			return this.annotations;
		}

		public ResourceContents resource() {
			return this.resource;
		}

		@Override
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

		public static Builder builder(ResourceContents resource) {
			return new Builder(resource);
		}

		public static final class Builder {

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
	}

	/** A link to a resource the server can read, returned in place of its contents. */
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

		public static final String TYPE = "resource_link";

		public ResourceLink(String name, String title, String uri, String description, String mimeType, Long size,
				Annotations annotations, Map<String, Object> meta) {
			Assert.notNull(name, "name must not be null");
			Assert.notNull(uri, "uri must not be null");
			this.name = name;
			this.title = title;
			this.uri = uri;
			this.description = description;
			this.mimeType = mimeType;
			this.size = size;
			this.annotations = annotations;
			this.meta = meta;
		}

		@JsonCreator
		static ResourceLink fromJson(@JsonProperty("name") String name, @JsonProperty("title") String title,
				@JsonProperty("uri") String uri, @JsonProperty("description") String description,
				@JsonProperty("mimeType") String mimeType, @JsonProperty("size") Long size,
				@JsonProperty("annotations") Annotations annotations, @JsonProperty("_meta") Map<String, Object> meta) {
			if (name == null || uri == null) {
				List<String> missing = new ArrayList<>();
				if (name == null) {
					missing.add("name -> ''");
					name = "";
				}
				if (uri == null) {
					missing.add("uri -> ''");
					uri = "";
				}
				logger.warn("ResourceLink: missing required fields during deserialization: {}",
						String.join(", ", missing));
			}
			return new ResourceLink(name, title, uri, description, mimeType, size, annotations, meta);
		}

		@Override
		public String type() {
			return TYPE;
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

		@Override
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
			return "ResourceLink[name=" + this.name + ", title=" + this.title + ", uri=" + this.uri + ", description="
					+ this.description + ", mimeType=" + this.mimeType + ", size=" + this.size + ", annotations="
					+ this.annotations + ", meta=" + this.meta + "]";
		}

		public static Builder builder(String uri, String name) {
			return new Builder(uri, name);
		}

		public static final class Builder {

			private final String uri;

			private final String name;

			private String title;

			private String description;

			private String mimeType;

			private Long size;

			private Annotations annotations;

			private Map<String, Object> meta;

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

			public Builder meta(Map<String, Object> meta) {
				this.meta = meta;
				return this;
			}

			public ResourceLink build() {
				return new ResourceLink(name, title, uri, description, mimeType, size, annotations, meta);
			}

		}
	}

}
