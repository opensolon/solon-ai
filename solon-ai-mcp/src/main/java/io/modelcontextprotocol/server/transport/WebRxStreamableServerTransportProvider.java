/*
 * Copyright 2024-2024 the original author or authors.
 */

package io.modelcontextprotocol.server.transport;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.server.McpTransportContextExtractor;
import io.modelcontextprotocol.spec.*;
import io.modelcontextprotocol.util.Assert;
import io.modelcontextprotocol.util.KeepAliveScheduler;
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
import java.util.List;
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

	/**
	 * Event type for sending the message endpoint URI to clients.
	 */
	public static final String ENDPOINT_EVENT_TYPE = "endpoint";

	/**
	 * Default base URL for the message endpoint.
	 */
	public static final String DEFAULT_BASE_URL = "";


	/**
	 * The endpoint URI where clients should send their JSON-RPC messages. Defaults to
	 * "/mcp".
	 */
	private final String mcpEndpoint;

	/**
	 * Flag indicating whether DELETE requests are disallowed on the endpoint.
	 */
	private final boolean disallowDelete;

	private final McpJsonMapper jsonMapper;

	private McpStreamableServerSession.Factory sessionFactory;

	/**
	 * Map of active client sessions, keyed by mcp-session-id.
	 */
	private final ConcurrentHashMap<String, McpStreamableServerSession> sessions = new ConcurrentHashMap<>();

	private McpTransportContextExtractor<Context> contextExtractor;


	/**
	 * Flag indicating if the transport is shutting down.
	 */
	private volatile boolean isClosing = false;

	private KeepAliveScheduler keepAliveScheduler;

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
												   Duration keepAliveInterval) {
		Assert.notNull(jsonMapper, "McpJsonMapper must not be null");
		Assert.notNull(mcpEndpoint, "MCP endpoint must not be null");
		Assert.notNull(contextExtractor, "McpTransportContextExtractor must not be null");

		this.jsonMapper = jsonMapper;
		this.mcpEndpoint = mcpEndpoint;
		this.contextExtractor = contextExtractor;
		this.disallowDelete = disallowDelete;

		if (keepAliveInterval != null) {
			this.keepAliveScheduler = KeepAliveScheduler
					.builder(() -> (isClosing) ? Flux.empty() : Flux.fromIterable(this.sessions.values()))
					.initialDelay(keepAliveInterval)
					.interval(keepAliveInterval)
					.build();

			this.keepAliveScheduler.start();
		}
	}

	@Override
	public void toHttpHandler(SolonApp app) {
		if (app != null) {
			app.get(this.mcpEndpoint, this::handleGet);
			app.post(this.mcpEndpoint, this::handlePost);
			app.delete(this.mcpEndpoint, this::handleDelete);
		}
	}

	@Override
	public String getMcpEndpoint() {
		return mcpEndpoint;
	}

	@Override
	public List<String> protocolVersions() {
		return List.of(ProtocolVersions.MCP_2024_11_05, ProtocolVersions.MCP_2025_03_26,
				ProtocolVersions.MCP_2025_06_18);
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

		logger.debug("Attempting to broadcast message to {} active sessions", sessions.size());

		return Flux.fromIterable(sessions.values())
				.flatMap(session -> session.sendNotification(method, params)
						.doOnError(
								e -> logger.error("Failed to send message to session {}: {}", session.getId(), e.getMessage()))
						.onErrorComplete())
				.then();
	}

	@Override
	public Mono<Void> closeGracefully() {
		return Mono.defer(() -> {
			this.isClosing = true;
			return Flux.fromIterable(sessions.values())
					.doFirst(() -> logger.debug("Initiating graceful shutdown with {} active sessions", sessions.size()))
					.flatMap(McpStreamableServerSession::closeGracefully)
					.then();
		}).then().doOnSuccess(v -> {
			sessions.clear();
			if (this.keepAliveScheduler != null) {
				this.keepAliveScheduler.shutdown();
			}
		});
	}

	/**
	 * Opens the listening SSE streams for clients.
	 * @param ctx The incoming server request
	 * @return A Mono which emits a response with the SSE event stream
	 */
	private void handleGet(Context ctx) throws Throwable {
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

		McpTransportContext transportContext = this.contextExtractor.extract(request);

		return Mono.defer(() -> {
			String acceptHeaders = request.accept();
			if (!acceptHeaders.contains(MimeType.TEXT_EVENT_STREAM_VALUE)) {
				return RxEntity.badRequest().build();
			}

			if (request.headerMap().containsKey(HttpHeaders.MCP_SESSION_ID) == false) {
				return RxEntity.badRequest().build(); // TODO: say we need a session
				// id
			}

			String sessionId = request.header(HttpHeaders.MCP_SESSION_ID);

			McpStreamableServerSession session = this.sessions.get(sessionId);

			if (session == null) {
				return RxEntity.notFound().build();
			}

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
						WebFluxStreamableMcpSessionTransport sessionTransport = new WebFluxStreamableMcpSessionTransport(
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
	private void handlePost(Context ctx) throws Throwable{
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

		McpTransportContext transportContext = this.contextExtractor.extract(request);

		String acceptHeaders = request.accept();
		if (!(acceptHeaders.contains(MimeType.APPLICATION_JSON_VALUE)
				&& acceptHeaders.contains(MimeType.TEXT_EVENT_STREAM_VALUE))) {
			return RxEntity.badRequest().build();
		}

		return Mono.just(request.body()).<Entity>flatMap(body -> {
					try {
						McpSchema.JSONRPCMessage message = McpSchema.deserializeJsonRpcMessage(jsonMapper, body);
						if (message instanceof McpSchema.JSONRPCRequest jsonrpcRequest
								&& jsonrpcRequest.method().equals(McpSchema.METHOD_INITIALIZE)) {
							var typeReference = new TypeRef<McpSchema.InitializeRequest>() {
							};
							McpSchema.InitializeRequest initializeRequest = jsonMapper.convertValue(jsonrpcRequest.params(),
									typeReference);
							McpStreamableServerSession.McpStreamableServerSessionInit init = this.sessionFactory
									.startSession(initializeRequest);
							sessions.put(init.session().getId(), init.session());
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

						if (request.headerMap().containsKey(HttpHeaders.MCP_SESSION_ID) == false) {
							return RxEntity.badRequest().body(new McpError("Session ID missing"));
						}

						String sessionId = request.header(HttpHeaders.MCP_SESSION_ID);
						McpStreamableServerSession session = sessions.get(sessionId);

						if (session == null) {
							return RxEntity.status(StatusCodes.CODE_NOT_FOUND)
									.body(new McpError("Session not found: " + sessionId));
						}

						if (message instanceof McpSchema.JSONRPCResponse jsonrpcResponse) {
							return session.accept(jsonrpcResponse).then(RxEntity.accepted().build());
						}
						else if (message instanceof McpSchema.JSONRPCNotification jsonrpcNotification) {
							return session.accept(jsonrpcNotification).then(RxEntity.accepted().build());
						}
						else if (message instanceof McpSchema.JSONRPCRequest jsonrpcRequest) {
							return RxEntity.ok()
									.contentType(MimeType.TEXT_EVENT_STREAM_VALUE)
									.body(Flux.<SseEvent>create(sink -> {
												WebFluxStreamableMcpSessionTransport st = new WebFluxStreamableMcpSessionTransport(sink);
												Mono<Void> stream = session.responseStream(jsonrpcRequest, st);
												Disposable streamSubscription = stream.onErrorComplete(err -> {
													sink.error(err);
													return true;
												}).contextWrite(sink.contextView()).subscribe();
												sink.onCancel(streamSubscription);
												// TODO Clarify why the outer context is not present in the
												// Flux.create sink?
											}).contextWrite(ctx -> ctx.put(McpTransportContext.KEY, transportContext)));
						}
						else {
							return RxEntity.badRequest().body(new McpError("Unknown message type"));
						}
					}
					catch (IllegalArgumentException | IOException e) {
						logger.error("Failed to deserialize message: {}", e.getMessage());
						return RxEntity.badRequest().body(new McpError("Invalid message format"));
					}
				})
				.switchIfEmpty(RxEntity.badRequest().build())
				.contextWrite(ctx -> ctx.put(McpTransportContext.KEY, transportContext));
	}

	private void handleDelete(Context ctx) throws Throwable {
		Mono<Entity> entityMono = doHandleDelete(ctx);
		ctx.returnValue(entityMono);
	}

	private Mono<Entity> doHandleDelete(Context request) {
		if (isClosing) {
			return RxEntity.status(StatusCodes.CODE_SERVICE_UNAVAILABLE).body("Server is shutting down");
		}

		McpTransportContext transportContext = this.contextExtractor.extract(request);

		return Mono.defer(() -> {
			if (request.headerMap().containsKey(HttpHeaders.MCP_SESSION_ID) == false) {
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

			return session.delete().then(RxEntity.ok().build());
		}).contextWrite(ctx -> ctx.put(McpTransportContext.KEY, transportContext));
	}

	private class WebFluxStreamableMcpSessionTransport implements McpStreamableServerTransport {

		private final FluxSink<SseEvent> sink;

		public WebFluxStreamableMcpSessionTransport(FluxSink<SseEvent> sink) {
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
						.id(messageId)
						.name(MESSAGE_EVENT_TYPE)
						.data(jsonText);
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

		private McpTransportContextExtractor<Context> contextExtractor = (
				serverRequest) -> McpTransportContext.EMPTY;

		private boolean disallowDelete;

		private Duration keepAliveInterval;

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
		 * Builds a new instance of {@link WebRxStreamableServerTransportProvider} with
		 * the configured settings.
		 * @return A new WebFluxStreamableServerTransportProvider instance
		 * @throws IllegalStateException if required parameters are not set
		 */
		public WebRxStreamableServerTransportProvider build() {
			Assert.notNull(mcpEndpoint, "Message endpoint must be set");
			return new WebRxStreamableServerTransportProvider(
					jsonMapper == null ? McpJsonMapper.getDefault() : jsonMapper, mcpEndpoint, contextExtractor,
					disallowDelete, keepAliveInterval);
		}

	}
}