/*
 * Copyright 2024-2024 the original author or authors.
 */

package io.modelcontextprotocol.server.transport;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.server.McpTransportContextExtractor;
import io.modelcontextprotocol.spec.*;
import io.modelcontextprotocol.util.Assert;
import io.modelcontextprotocol.util.KeepAliveScheduler;
import lombok.var;
import org.jspecify.annotations.Nullable;
import org.noear.solon.SolonApp;
import org.noear.solon.core.handle.Context;
import org.noear.solon.core.handle.Entity;
import org.noear.solon.core.handle.StatusCodes;
import org.noear.solon.core.util.MimeType;
import org.noear.solon.rx.handle.RxEntity;
import org.noear.solon.web.sse.SseEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.Disposable;
import reactor.core.Exceptions;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server-side implementation of the Model Context Protocol (MCP) streamable transport
 * layer using HTTP with Server-Sent Events (SSE) through Spring WebMVC. This
 * implementation provides a bridge between synchronous WebMVC operations and reactive
 * programming patterns to maintain compatibility with the reactive transport interface.
 *
 *
 * @author Christian Tzolov
 * @author Dariusz Jędrzejczyk
 * @author noear
 * @see McpStreamableServerTransportProvider
 */
public class WebRxStreamableServerTransportProvider implements McpStreamableServerTransportProvider, IMcpHttpServerTransport {

	private static final Logger logger = LoggerFactory.getLogger(WebRxStreamableServerTransportProvider.class);

	/**
	 * Event type for JSON-RPC messages sent through the SSE connection.
	 */
	public static final String MESSAGE_EVENT_TYPE = "message";

	private final McpJsonMapper jsonMapper;

	/**
	 * The endpoint URI where clients should send their JSON-RPC messages. Defaults to
	 * "/mcp".
	 */
	private final String mcpEndpoint;

	/**
	 * Flag indicating whether DELETE requests are disallowed on the endpoint.
	 */
	private final boolean disallowDelete;

	private McpStreamableServerSession.@Nullable Factory sessionFactory;

	/**
	 * Map of active client sessions, keyed by mcp-session-id.
	 */
	private final ConcurrentHashMap<String, McpStreamableServerSession> sessions = new ConcurrentHashMap<>();

	private McpTransportContextExtractor<Context> contextExtractor;


	/**
	 * Flag indicating if the transport is shutting down.
	 */
	private volatile boolean isClosing = false;

	private @Nullable KeepAliveScheduler keepAliveScheduler;

	/**
	 * Duration of inactivity after which an idle session is evicted. If {@code null},
	 * idle sessions are never evicted.
	 */
	private final @Nullable Duration sessionIdleTimeout;

	/**
	 * Tracks the last access time per session id, used to evict idle sessions.
	 */
	private final ConcurrentHashMap<String, Instant> sessionLastAccessTimes = new ConcurrentHashMap<>();

	private @Nullable Disposable idleSessionScheduler;

	/**
	 * Max number of session this provider supports before discarding new initialization
	 * requests.
	 */
	private final long maxSessions;

	/**
	 * Security validator for validating HTTP requests.
	 */
	private final ServerTransportSecurityValidator securityValidator;

	/**
	 * Constructs a new WebMvcStreamableServerTransportProvider instance.
	 * @param jsonMapper The ObjectMapper to use for JSON serialization/deserialization
	 * of messages.
	 * @param mcpEndpoint The endpoint URI where clients should send their JSON-RPC
	 * messages via HTTP. This endpoint will handle GET, POST, and DELETE requests.
	 * @param disallowDelete Whether to disallow DELETE requests on the endpoint.
	 * @throws IllegalArgumentException if any parameter is null
	 */
	private WebRxStreamableServerTransportProvider(McpJsonMapper jsonMapper,
										   String mcpEndpoint,
										   McpTransportContextExtractor<Context> contextExtractor,
										   boolean disallowDelete,
										   @Nullable Duration keepAliveInterval,
										   @Nullable Duration sessionIdleTimeout,
										   long maxSessions,
										   ServerTransportSecurityValidator securityValidator) {
		Assert.notNull(jsonMapper, "McpJsonMapper must not be null");
		Assert.notNull(mcpEndpoint, "MCP endpoint must not be null");
		Assert.notNull(contextExtractor, "McpTransportContextExtractor must not be null");
		Assert.notNull(securityValidator, "Security validator must not be null");

		this.jsonMapper = jsonMapper;
		this.mcpEndpoint = mcpEndpoint;
		this.contextExtractor = contextExtractor;
		this.disallowDelete = disallowDelete;
		this.securityValidator = securityValidator;
		this.maxSessions = maxSessions;
		this.sessionIdleTimeout = sessionIdleTimeout;

		if (keepAliveInterval != null) {
			this.keepAliveScheduler = KeepAliveScheduler
					.builder(() -> (isClosing) ? Flux.empty() : Flux.fromIterable(this.sessions.values()))
					.initialDelay(keepAliveInterval)
					.interval(keepAliveInterval)
					.build();

			this.keepAliveScheduler.start();
		}

		if (sessionIdleTimeout != null) {
			this.idleSessionScheduler = Flux.interval(sessionIdleTimeout, sessionIdleTimeout)
					.subscribe(tick -> this.evictIdleSessions());
		}
	}

	@Override
	public void setupHttpHandlers(SolonApp app) {
		if (app != null) {
			app.router().get(this.mcpEndpoint, this::handleGet);
			app.router().post(this.mcpEndpoint, this::handlePost);
			app.router().delete(this.mcpEndpoint, this::handleDelete);
		}
	}

	@Override
	public void cleanupHttpHandlers(SolonApp app) {
		if (app != null) {
			app.router().remove(this.mcpEndpoint);
		}
	}

	@Override
	public String getMcpEndpoint() {
		return mcpEndpoint;
	}

	@Override
	public List<String> protocolVersions() {
		return Collections.unmodifiableList(Arrays.asList(ProtocolVersions.MCP_2024_11_05,
				ProtocolVersions.MCP_2025_03_26,
				ProtocolVersions.MCP_2025_06_18,
				ProtocolVersions.MCP_2025_11_25));
	}

	@Override
	public void setSessionFactory(McpStreamableServerSession.Factory sessionFactory) {
		this.sessionFactory = sessionFactory;
	}

	@Override
	public Mono<Void> notifyClients(String method, Object params) {
		if (sessions.isEmpty()) {
			logger.debug("No active sessions to broadcast message to");
			return Mono.empty();
		}

		if (logger.isDebugEnabled()) {
			logger.debug("Attempting to broadcast message to " + this.sessions.size() + " active sessions");
		}

		return Flux.fromIterable(this.sessions.values())
				.flatMap(session -> session.sendNotification(method, params).doOnError(e -> {
					if (logger.isErrorEnabled()) {
						logger.error("Failed to send message to session " + session.getId() + ": " + e.getMessage());
					}
				}).onErrorComplete())
				.then();
	}

	@Override
	public Mono<Void> notifyClient(String sessionId, String method, Object params) {
		return Mono.defer(() -> {
			McpStreamableServerSession session = this.sessions.get(sessionId);
			if (session == null) {
				if (logger.isDebugEnabled()) {
					logger.debug("Session " + sessionId + " not found");
				}
				return Mono.empty();
			}
			return session.sendNotification(method, params);
		});
	}

	@Override
	public Mono<Void> closeGracefully() {
		return Mono.defer(() -> {
			this.isClosing = true;
			return Flux.fromIterable(this.sessions.values()).doFirst(() -> {
				if (logger.isDebugEnabled()) {
					logger.debug("Initiating graceful shutdown with " + this.sessions.size() + " active sessions");
				}
			}).flatMap(McpStreamableServerSession::closeGracefully).then();
		}).then().doOnSuccess(v -> {
			this.sessions.clear();
			this.sessionLastAccessTimes.clear();
			if (this.keepAliveScheduler != null) {
				this.keepAliveScheduler.shutdown();
			}
			if (this.idleSessionScheduler != null) {
				this.idleSessionScheduler.dispose();
			}
		});
	}

	/**
	 * Opens the listening SSE streams for clients.
	 * @param ctx The incoming server request
	 * @return A Mono which emits a response with the SSE event stream
	 */
	public void handleGet(Context ctx) throws Throwable {
		Mono<Entity> entityMono = doHandleGet(ctx);
		ctx.returnValue(entityMono);
	}

	/**
	 * Opens the listening SSE streams for clients.
	 * @param request The incoming server request
	 * @return A Mono which emits a response with the SSE event stream
	 */
	private Mono<Entity> doHandleGet(Context request) {
		if (isClosing) {
			return RxEntity.status(StatusCodes.CODE_SERVICE_UNAVAILABLE).body("Server is shutting down");
		}

		try {
			Map<String, List<String>> headers = request.headerMap().toValuesMap();
			this.securityValidator.validateHeaders(headers);
		}
		catch (ServerTransportSecurityException e) {
			String errorMessage = e.getMessage();
			return RxEntity.status(e.getStatusCode()).body(errorMessage != null ? errorMessage : "");
		}

		McpTransportContext transportContext = this.contextExtractor.extract(request);

		return Mono.defer(() -> {
			String acceptHeaders = request.accept();
			if (!acceptAllows(acceptHeaders, MimeType.TEXT_EVENT_STREAM_VALUE)) {
				return RxEntity.badRequest().build();
			}

			if (!hasMcpSessionIdHeader(request)) {
				return RxEntity.badRequest().build(); // TODO: say we need a session
													// id
			}

			String sessionId = request.header(HttpHeaders.MCP_SESSION_ID);

			McpStreamableServerSession session = this.sessions.get(sessionId);

			if (session == null) {
				return RxEntity.notFound().build();
			}

			touchSession(sessionId);

			if (request.headerMap().containsKey(HttpHeaders.LAST_EVENT_ID)) {
				String lastId = request.header(HttpHeaders.LAST_EVENT_ID);
				return RxEntity.ok()
						.contentType(MimeType.TEXT_EVENT_STREAM_VALUE)
						.body(session.replay(lastId)
										.contextWrite(ctx -> ctx.put(McpTransportContext.KEY, transportContext)));
			}

			return RxEntity.ok()
					.contentType(MimeType.TEXT_EVENT_STREAM_VALUE)
					.body(Flux.<SseEvent>create(sink -> {
						WebRxStreamableMcpSessionTransport sessionTransport = new WebRxStreamableMcpSessionTransport(
								sink);
						McpStreamableServerSession.McpStreamableServerSessionStream listeningStream = session
								.listeningStream(sessionTransport);
						sink.onDispose(listeningStream::close);
						// TODO Clarify why the outer context is not present in the
						// Flux.create sink?
					}).contextWrite(ctx -> ctx.put(McpTransportContext.KEY, transportContext)));

		}).contextWrite(ctx -> ctx.put(McpTransportContext.KEY, transportContext));
	}


	/**
	 * Handles incoming JSON-RPC messages from clients.
	 * @param ctx The incoming server request containing the JSON-RPC message
	 * @return A Mono with the response appropriate to a particular Streamable HTTP flow.
	 */
	public void handlePost(Context ctx) throws Throwable{
		Mono<Entity> entityMono = doHandlePost(ctx);
		ctx.returnValue(entityMono);
	}

	/**
	 * Handles incoming JSON-RPC messages from clients.
	 * @param request The incoming server request containing the JSON-RPC message
	 * @return A Mono with the response appropriate to a particular Streamable HTTP flow.
	 */
	private Mono<Entity> doHandlePost(Context request) throws Throwable {
		if (isClosing) {
			return RxEntity.status(StatusCodes.CODE_SERVICE_UNAVAILABLE).body("Server is shutting down");
		}

		try {
			Map<String, List<String>> headers = request.headerMap().toValuesMap();
			this.securityValidator.validateHeaders(headers);
		} catch (ServerTransportSecurityException e) {
			String errorMessage = e.getMessage();
			return RxEntity.status(e.getStatusCode()).body(errorMessage != null ? errorMessage : "");
		}

		McpTransportContext transportContext = this.contextExtractor.extract(request);

		String acceptHeaders = request.accept();
		if (!(acceptAllows(acceptHeaders, MimeType.APPLICATION_JSON_VALUE)
				&& acceptAllows(acceptHeaders, MimeType.TEXT_EVENT_STREAM_VALUE))) {
			return RxEntity.badRequest().build();
		}

		return Mono.just(request.body()).<Entity>flatMap(body -> {
					try {
						McpSchema.JSONRPCMessage message = McpSchema.deserializeJsonRpcMessage(jsonMapper, body);
						if (message instanceof McpSchema.JSONRPCRequest) {
							McpSchema.JSONRPCRequest jsonrpcRequest = (McpSchema.JSONRPCRequest) message;
							if (jsonrpcRequest.method().equals(McpSchema.METHOD_INITIALIZE)) {
								return handleInitRequest(jsonrpcRequest, hasMcpSessionIdHeader(request));
							}
						}

						if (!hasMcpSessionIdHeader(request)) {
							return RxEntity.badRequest().body(McpError.builder(McpSchema.ErrorCodes.METHOD_NOT_FOUND)
									.message("Session ID missing")
									.build());
						}

						String sessionId = request.header(HttpHeaders.MCP_SESSION_ID);
						McpStreamableServerSession session = sessions.get(sessionId);

						if (session == null || sessionId == null) {
							return RxEntity.status(StatusCodes.CODE_NOT_FOUND)
									.body(McpError.builder(McpSchema.ErrorCodes.INTERNAL_ERROR)
											.message("Session not found: " + sessionId)
											.build());
						}

						touchSession(sessionId);

						if (message instanceof McpSchema.JSONRPCResponse) {
							McpSchema.JSONRPCResponse jsonrpcResponse = (McpSchema.JSONRPCResponse) message;
							return session.accept(jsonrpcResponse).then(RxEntity.accepted().build());
						} else if (message instanceof McpSchema.JSONRPCNotification) {
							McpSchema.JSONRPCNotification jsonrpcNotification = (McpSchema.JSONRPCNotification) message;
							return session.accept(jsonrpcNotification).then(RxEntity.accepted().build());
						} else if (message instanceof McpSchema.JSONRPCRequest) {
							McpSchema.JSONRPCRequest jsonrpcRequest = (McpSchema.JSONRPCRequest) message;
							return RxEntity.ok()
									.contentType(MimeType.TEXT_EVENT_STREAM_VALUE)
									.body(Flux.<SseEvent>create(sink -> {
										WebRxStreamableMcpSessionTransport st = new WebRxStreamableMcpSessionTransport(sink);
										Mono<Void> stream = session.responseStream(jsonrpcRequest, st);
										Disposable streamSubscription = stream.onErrorComplete(err -> {
											sink.error(err);
											return true;
										}).contextWrite(sink.contextView()).subscribe();
										sink.onCancel(streamSubscription);
										// TODO Clarify why the outer context is not present in the
										// Flux.create sink?
									}).contextWrite(ctx -> ctx.put(McpTransportContext.KEY, transportContext)));
						} else {
							return RxEntity.badRequest().body(McpError.builder(McpSchema.ErrorCodes.INVALID_REQUEST)
									.message("Unknown message type")
									.build());
						}
					} catch (IllegalArgumentException | IOException e) {
						if (logger.isErrorEnabled()) {
							logger.error("Failed to deserialize message: " + e.getMessage());
						}
						return RxEntity.badRequest().body(McpError.builder(McpSchema.ErrorCodes.INVALID_REQUEST)
								.message("Invalid message format")
								.build());
					}
				})
				.switchIfEmpty(RxEntity.badRequest().build())
				.contextWrite(ctx -> ctx.put(McpTransportContext.KEY, transportContext));
	}

	public void handleDelete(Context ctx) throws Throwable {
		Mono<Entity> entityMono = doHandleDelete(ctx);
		ctx.returnValue(entityMono);
	}

	private Mono<Entity> doHandleDelete(Context request) {
		if (isClosing) {
			return RxEntity.status(StatusCodes.CODE_SERVICE_UNAVAILABLE).body("Server is shutting down");
		}

		try {
			Map<String, List<String>> headers = request.headerMap().toValuesMap();
			this.securityValidator.validateHeaders(headers);
		}
		catch (ServerTransportSecurityException e) {
			String errorMessage = e.getMessage();
			return RxEntity.status(e.getStatusCode()).body(errorMessage != null ? errorMessage : "");
		}

		McpTransportContext transportContext = this.contextExtractor.extract(request);

		return Mono.defer(() -> {
			if (!hasMcpSessionIdHeader(request)) {
				return RxEntity.badRequest().build(); // TODO: say we need a session
													// id
			}

			if (this.disallowDelete) {
				return RxEntity.status(StatusCodes.CODE_METHOD_NOT_ALLOWED).build();
			}

			String sessionId = request.header(HttpHeaders.MCP_SESSION_ID);

			McpStreamableServerSession session = this.sessions.get(sessionId);

			if (session == null) {
				return RxEntity.notFound().build();
			}

			return session.delete().doOnSuccess(unused -> {
				if (sessionId != null) {
					removeSession(sessionId);
				}
			}).then(RxEntity.ok().build());
		}).contextWrite(ctx -> ctx.put(McpTransportContext.KEY, transportContext));
	}

	private Mono<Entity> handleInitRequest(final McpSchema.JSONRPCRequest jsonrpcRequest,
			final boolean hasMcpSessionIdHeader) {
		if (this.sessionFactory == null) {
			return RxEntity.status(StatusCodes.CODE_INTERNAL_SERVER_ERROR)
					.body(McpError.builder(McpSchema.ErrorCodes.INTERNAL_ERROR)
							.message("Session factory not initialized")
						.build());
		}
		if (hasMcpSessionIdHeader) {
			return RxEntity.status(StatusCodes.CODE_BAD_REQUEST)
					.body(McpError.builder(McpSchema.ErrorCodes.INTERNAL_ERROR)
							.message("Session already initialized")
						.build());
		}
		if (this.sessions.size() >= this.maxSessions) {
			return RxEntity.status(StatusCodes.CODE_SERVICE_UNAVAILABLE)
					.body(McpError.builder(McpSchema.ErrorCodes.INTERNAL_ERROR)
							.message("Max number of sessions reached")
						.build());
		}
		var typeReference = new TypeRef<McpSchema.InitializeRequest>() {};
		McpSchema.InitializeRequest initializeRequest = jsonMapper.convertValue(jsonrpcRequest.params(),
				typeReference);
		McpStreamableServerSession.McpStreamableServerSessionInit init = this.sessionFactory
					.startSession(initializeRequest);
		sessions.put(init.session().getId(), init.session());
		if (this.sessionIdleTimeout != null) {
			this.sessionLastAccessTimes.put(init.session().getId(), Instant.now());
		}
		return init.initResult().map(initializeResult -> {
					McpSchema.JSONRPCResponse jsonrpcResponse = new McpSchema.JSONRPCResponse(
							McpSchema.JSONRPC_VERSION, jsonrpcRequest.id(), initializeResult, null);
					try {
						return this.jsonMapper.writeValueAsString(jsonrpcResponse);
					}
					catch (IOException e) {
						logger.warn("Failed to serialize initResponse", e);
						throw Exceptions.propagate(e);
					}
				})
				.flatMap(initResult -> RxEntity.ok()
						.contentType(MimeType.APPLICATION_JSON_VALUE)
						.headerSet(HttpHeaders.MCP_SESSION_ID, init.session().getId())
						.body(initResult));
	}

	/**
	 * Records the current time as the last access time for the given session, so that an
	 * active session is not evicted as idle. No-op when idle eviction is disabled or the
	 * session is no longer tracked.
	 * @param sessionId the id of the session that was just accessed
	 */
	private void touchSession(String sessionId) {
		if (this.sessionIdleTimeout != null) {
			this.sessionLastAccessTimes.computeIfPresent(sessionId, (id, lastAccess) -> Instant.now());
		}
	}

	/**
	 * Evicts and closes sessions that have been idle for longer than the configured
	 * {@code sessionIdleTimeout}. This reclaims session slots held by clients that
	 * initialized a session but never interacted with it again, mitigating resource
	 * exhaustion from abandoned sessions.
	 */
	private void evictIdleSessions() {
		if (this.sessionIdleTimeout == null || this.isClosing) {
			return;
		}
		final Instant now = Instant.now();
		for (Map.Entry<String, Instant> entry : this.sessionLastAccessTimes.entrySet()) {
			if (Duration.between(entry.getValue(), now).compareTo(this.sessionIdleTimeout) > 0) {
				final String sessionId = entry.getKey();
				McpStreamableServerSession session = removeSession(sessionId);
				if (session != null) {
					if (logger.isDebugEnabled()) {
						logger.debug("Evicting idle session: {}", sessionId);
					}
					session.closeGracefully().onErrorComplete().subscribe();
				}
			}
		}
	}

	/**
	 * Checks whether the given request carries an {@code Mcp-Session-Id} header.
	 * @param request the incoming server request
	 * @return {@code true} if the header is present, regardless of its value
	 */
	private static boolean hasMcpSessionIdHeader(Context request) {
		return request.headerMap().containsKey(HttpHeaders.MCP_SESSION_ID);
	}

	private McpStreamableServerSession removeSession(String sessionId) {
		this.sessionLastAccessTimes.remove(sessionId);
		return this.sessions.remove(sessionId);
	}

	/**
	 * Checks whether the raw {@code Accept} header value allows the given media type.
	 * Parses the comma-separated list, trimming entries and ignoring media type
	 * parameters (e.g. {@code ;q=0.9}), matching case-insensitively.
	 */
	private static boolean acceptAllows(@Nullable String acceptHeader, String mimeType) {
		if (acceptHeader == null || acceptHeader.isEmpty()) {
			return false;
		}
		for (String entry : acceptHeader.split(",")) {
			int paramIdx = entry.indexOf(';');
			String type = paramIdx >= 0 ? entry.substring(0, paramIdx) : entry;
			if (mimeType.equalsIgnoreCase(type.trim())) {
				return true;
			}
		}
		return false;
	}

	private class WebRxStreamableMcpSessionTransport implements McpStreamableServerTransport {

		private final FluxSink<SseEvent> sink;

		public WebRxStreamableMcpSessionTransport(FluxSink<SseEvent> sink) {
			this.sink = sink;
		}

		@Override
		public Mono<Void> sendMessage(McpSchema.JSONRPCMessage message) {
			return this.sendMessage(message, null);
		}

		@Override
		public Mono<Void> sendMessage(McpSchema.JSONRPCMessage message, String messageId) {
			return Mono.fromSupplier(() -> {
				try {
					return jsonMapper.writeValueAsString(message);
				}
				catch (IOException e) {
					throw Exceptions.propagate(e);
				}
			}).doOnNext(jsonText -> {
				SseEvent event = new SseEvent()
						.name(MESSAGE_EVENT_TYPE)
						.data(jsonText);
				if (messageId != null) {
					event.id(messageId);
				}
				sink.next(event);
			}).doOnError(e -> {
				// TODO log with sessionid
				Throwable exception = Exceptions.unwrap(e);
				sink.error(exception);
			}).then();
		}

		@Override
		public <T> T unmarshalFrom(Object data, TypeRef<T> typeRef) {
			return jsonMapper.convertValue(data, typeRef);
		}

		@Override
		public Mono<Void> closeGracefully() {
			return Mono.fromRunnable(sink::complete);
		}

		@Override
		public void close() {
			sink.complete();
		}

	}

	public static Builder builder() {
		return new Builder();
	}

	/**
	 * Builder for creating instances of {@link WebRxStreamableServerTransportProvider}.
	 * <p>
	 * This builder provides a fluent API for configuring and creating instances of
	 * WebFluxStreamableServerTransportProvider with custom settings.
	 */
	public static class Builder {

		private McpJsonMapper jsonMapper;

		private String mcpEndpoint = "/mcp";

		private McpTransportContextExtractor<Context> contextExtractor = (serverRequest) -> {
			Map<String,Object> context = new HashMap<>();
			context.put(Context.class.getName(), serverRequest);
			return McpTransportContext.create(context);
		};

		private boolean disallowDelete;

		private @Nullable Duration keepAliveInterval;

		private long maxSessions = 100_000L;

		private @Nullable Duration sessionIdleTimeout;

		private ServerTransportSecurityValidator securityValidator = ServerTransportSecurityValidator.NOOP;

		private Builder() {
			// used by a static method
		}

		/**
		 * Sets the {@link McpJsonMapper} to use for JSON serialization/deserialization of
		 * MCP messages.
		 * @param jsonMapper The {@link McpJsonMapper} instance. Must not be null.
		 * @return this builder instance
		 * @throws IllegalArgumentException if jsonMapper is null
		 */
		public Builder jsonMapper(McpJsonMapper jsonMapper) {
			Assert.notNull(jsonMapper, "McpJsonMapper must not be null");
			this.jsonMapper = jsonMapper;
			return this;
		}

		/**
		 * Sets the endpoint URI where clients should send their JSON-RPC messages.
		 * @param messageEndpoint The message endpoint URI. Must not be null.
		 * @return this builder instance
		 * @throws IllegalArgumentException if messageEndpoint is null
		 */
		public Builder messageEndpoint(String messageEndpoint) {
			Assert.notNull(messageEndpoint, "Message endpoint must not be null");
			this.mcpEndpoint = messageEndpoint;
			return this;
		}

		/**
		 * Sets the context extractor that allows providing the MCP feature
		 * implementations to inspect HTTP transport level metadata that was present at
		 * HTTP request processing time. This allows to extract custom headers and other
		 * useful data for use during execution later on in the process.
		 * @param contextExtractor The contextExtractor to fill in a
		 * {@link McpTransportContext}.
		 * @return this builder instance
		 * @throws IllegalArgumentException if contextExtractor is null
		 */
		public Builder contextExtractor(McpTransportContextExtractor<Context> contextExtractor) {
			Assert.notNull(contextExtractor, "contextExtractor must not be null");
			this.contextExtractor = contextExtractor;
			return this;
		}

		/**
		 * Sets whether the session removal capability is disabled.
		 * @param disallowDelete if {@code true}, the DELETE endpoint will not be
		 * supported and sessions won't be deleted.
		 * @return this builder instance
		 */
		public Builder disallowDelete(boolean disallowDelete) {
			this.disallowDelete = disallowDelete;
			return this;
		}

		/**
		 * Sets the keep-alive interval for the server transport.
		 * @param keepAliveInterval The interval for sending keep-alive messages. If null,
		 * no keep-alive will be scheduled.
		 * @return this builder instance
		 */
		public Builder keepAliveInterval(Duration keepAliveInterval) {
			this.keepAliveInterval = keepAliveInterval;
			return this;
		}

		/**
		 * Sets the security validator for validating HTTP requests.
		 * @param securityValidator The security validator to use. Must not be null.
		 * @return this builder instance
		 * @throws IllegalArgumentException if securityValidator is null
		 */
		public Builder securityValidator(ServerTransportSecurityValidator securityValidator) {
			Assert.notNull(securityValidator, "Security validator must not be null");
			this.securityValidator = securityValidator;
			return this;
		}

		/**
		 * Sets the maximum supported sessions for this provider. If not set defaults to
		 * 100_000.
		 * @param maxSessions - maximum allowed sessions for this provider
		 * @return this builder instance
		 * @throws IllegalArgumentException if maximum sessions is not positive number
		 */
		public Builder maxSessions(long maxSessions) {
			Assert.isTrue(maxSessions >= 0, "Max sessions must be greater than 0");
			this.maxSessions = maxSessions;
			return this;
		}

		/**
		 * Sets the idle timeout after which sessions with no client activity are evicted
		 * and closed. Activity is any inbound request (GET, POST or DELETE) resolving to
		 * the session. This reclaims session slots held by clients that initialized a
		 * session but never interacted with it again, mitigating resource exhaustion from
		 * abandoned sessions.
		 * <p>
		 * Keep-alive and idle eviction are complementary and may be enabled together:
		 * keep-alive pings preserve connected clients (a ping response counts as
		 * activity), while idle eviction reclaims abandoned sessions. When both are set,
		 * {@code sessionIdleTimeout} must be greater than the keep-alive interval so that
		 * a connected client answering pings is not evicted before it can respond;
		 * otherwise {@link #build()} throws.
		 * @param sessionIdleTimeout the maximum idle duration before eviction, or null to
		 * disable idle eviction (the default)
		 * @return this builder instance
		 */
		public Builder sessionIdleTimeout(@Nullable Duration sessionIdleTimeout) {
			this.sessionIdleTimeout = sessionIdleTimeout;
			return this;
		}

		/**
		 * Builds a new instance of {@link WebRxStreamableServerTransportProvider} with
		 * the configured settings.
		 * @return A new WebFluxStreamableServerTransportProvider instance
		 * @throws IllegalStateException if required parameters are not set
		 */
		public WebRxStreamableServerTransportProvider build() {
			Assert.notNull(mcpEndpoint, "Message endpoint must be set");
			if (this.sessionIdleTimeout != null && this.keepAliveInterval != null) {
				Assert.isTrue(this.sessionIdleTimeout.compareTo(this.keepAliveInterval) > 0,
						"sessionIdleTimeout must be greater than keepAliveInterval, otherwise a connected client "
								+ "may be evicted before it can answer a keep-alive ping");
			}
			return new WebRxStreamableServerTransportProvider(
					this.jsonMapper == null ? McpJsonDefaults.getMapper() : this.jsonMapper,
					this.mcpEndpoint,
					this.contextExtractor,
					this.disallowDelete,
					this.keepAliveInterval,
					this.sessionIdleTimeout,
					this.maxSessions,
					this.securityValidator);
		}

	}
}
