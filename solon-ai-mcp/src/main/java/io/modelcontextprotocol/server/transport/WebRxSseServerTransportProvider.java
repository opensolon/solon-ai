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
import reactor.core.Exceptions;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server-side implementation of the Model Context Protocol (MCP) transport layer using
 * HTTP with Server-Sent Events (SSE) through Spring WebMVC. This implementation provides
 * a bridge between synchronous WebMVC operations and reactive programming patterns to
 * maintain compatibility with the reactive transport interface.
 *
 * <p>
 * Key features:
 * <ul>
 * <li>Implements bidirectional communication using HTTP POST for client-to-server
 * messages and SSE for server-to-client messages</li>
 * <li>Manages client sessions with unique IDs for reliable message delivery</li>
 * <li>Supports graceful shutdown with proper session cleanup</li>
 * <li>Provides JSON-RPC message handling through configured endpoints</li>
 * <li>Includes built-in error handling and logging</li>
 * </ul>
 *
 * <p>
 * The transport operates on two main endpoints:
 * <ul>
 * <li>{@code /sse} - The SSE endpoint where clients establish their event stream
 * connection</li>
 * <li>A configurable message endpoint where clients send their JSON-RPC messages via HTTP
 * POST</li>
 * </ul>
 *
 * <p>
 * This implementation uses {@link ConcurrentHashMap} to safely manage multiple client
 * sessions in a thread-safe manner. Each client session is assigned a unique ID and
 * maintains its own SSE connection.
 *
 * @author Christian Tzolov
 * @author Alexandros Pappas
 * @author noear
 * @see McpServerTransportProvider
 * @see org.noear.solon.core.handle.Handler
 */
public class WebRxSseServerTransportProvider implements McpServerTransportProvider, IMcpHttpServerTransport {

	private static final Logger logger = LoggerFactory.getLogger(WebRxSseServerTransportProvider.class);

	/**
	 * Event type for JSON-RPC messages sent through the SSE connection.
	 */
	public static final String MESSAGE_EVENT_TYPE = "message";

	/**
	 * Event type for sending the message endpoint URI to clients.
	 */
	public static final String ENDPOINT_EVENT_TYPE = "endpoint";

	/**
	 * Default SSE endpoint path as specified by the MCP transport specification.
	 */
	public static final String DEFAULT_SSE_ENDPOINT = "/sse";

	public static final String SESSION_ID = "sessionId";

	public static final String DEFAULT_BASE_URL = "";


	private final McpJsonMapper jsonMapper;

	/**
	 * Base URL for the message endpoint. This is used to construct the full URL for
	 * clients to send their JSON-RPC messages.
	 */
	private final String baseUrl;

	private final String messageEndpoint;

	private final String sseEndpoint;

	private McpServerSession.Factory sessionFactory;

	/**
	 * Map of active client sessions, keyed by session ID.
	 */
	private final ConcurrentHashMap<String, McpServerSession> sessions = new ConcurrentHashMap<>();
	private final ConcurrentHashMap<String, Context> sessionRequests = new ConcurrentHashMap<>();

    private McpTransportContextExtractor<Context> contextExtractor;

	/**
	 * Flag indicating if the transport is shutting down.
	 */
	private volatile boolean isClosing = false;

	/**
	 * Keep-alive scheduler for managing session pings. Activated if keepAliveInterval is
	 * set. Disabled by default.
	 */
	private KeepAliveScheduler keepAliveScheduler;

	/**
	 * Constructs a new WebMvcSseServerTransportProvider instance.
	 * @param jsonMapper The ObjectMapper to use for JSON serialization/deserialization
	 * of messages.
	 * @param baseUrl The base URL for the message endpoint, used to construct the full
	 * endpoint URL for clients.
	 * @param messageEndpoint The endpoint URI where clients should send their JSON-RPC
	 * messages via HTTP POST. This endpoint will be communicated to clients through the
	 * SSE connection's initial endpoint event.
	 * @param sseEndpoint The endpoint URI where clients establish their SSE connections.
	 * * @param keepAliveInterval The interval for sending keep-alive messages to
	 * @throws IllegalArgumentException if any parameter is null
	 * @deprecated Use the builder {@link #builder()} instead for better configuration
	 * options.
	 */
	@Deprecated
	public WebRxSseServerTransportProvider(McpJsonMapper jsonMapper,
										   String baseUrl,
										   String messageEndpoint,
										   String sseEndpoint,
										   Duration keepAliveInterval,
										   McpTransportContextExtractor<Context> contextExtractor) {
		Assert.notNull(jsonMapper, "McpJsonMapper must not be null");
		Assert.notNull(baseUrl, "Message base path must not be null");
		Assert.notNull(messageEndpoint, "Message endpoint must not be null");
		Assert.notNull(sseEndpoint, "SSE endpoint must not be null");
		Assert.notNull(contextExtractor, "Context extractor must not be null");

		this.jsonMapper = jsonMapper;
		this.baseUrl = baseUrl;
		this.messageEndpoint = messageEndpoint;
		this.sseEndpoint = sseEndpoint;
		this.contextExtractor = contextExtractor;

		if (keepAliveInterval != null) {
			this.keepAliveScheduler = KeepAliveScheduler
					.builder(() -> (isClosing) ? Flux.empty() : Flux.fromIterable(sessions.values()))
					.initialDelay(keepAliveInterval)
					.interval(keepAliveInterval)
					.build();

			this.keepAliveScheduler.start();
		}
    }

	@Override
	public void toHttpHandler(SolonApp app) {
		if (app != null) {
			app.get(this.sseEndpoint, this::handleSseConnection);
			app.post(this.messageEndpoint, this::handleMessage);
		}
	}

	@Override
	public String getMcpEndpoint() {
		return sseEndpoint;
	}

	@Override
	public List<String> protocolVersions() {
		return Arrays.asList(ProtocolVersions.MCP_2024_11_05);
	}

	@Override
	public void setSessionFactory(McpServerSession.Factory sessionFactory) {
		this.sessionFactory = sessionFactory;
	}

	/**
	 * Broadcasts a JSON-RPC message to all connected clients through their SSE
	 * connections. The message is serialized to JSON and sent as a server-sent event to
	 * each active session.
	 *
	 * <p>
	 * The method:
	 * <ul>
	 * <li>Serializes the message to JSON</li>
	 * <li>Creates a server-sent event with the message data</li>
	 * <li>Attempts to send the event to all active sessions</li>
	 * <li>Tracks and reports any delivery failures</li>
	 * </ul>
	 * @param method The JSON-RPC method to send to clients
	 * @param params The method parameters to send to clients
	 * @return A Mono that completes when the message has been sent to all sessions, or
	 * errors if any session fails to receive the message
	 */
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

	/**
	 * Initiates a graceful shutdown of all the sessions. This method ensures all active
	 * sessions are properly closed and cleaned up.
	 * @return A Mono that completes when all sessions have been closed
	 */
	@Override
	public Mono<Void> closeGracefully() {
		return Flux.fromIterable(sessions.values())
				.doFirst(() -> logger.debug("Initiating graceful shutdown with {} active sessions", sessions.size()))
				.flatMap(McpServerSession::closeGracefully)
				.then()
				.doOnSuccess(v -> {
					logger.debug("Graceful shutdown completed");
					sessions.clear();
					if (this.keepAliveScheduler != null) {
						this.keepAliveScheduler.shutdown();
					}
				});
	}

	private void handleSseConnection(Context ctx) throws Throwable{
		Mono<Entity> entityMono = doHandleSseConnection(ctx);
		ctx.returnValue(entityMono);
	}

	/**
	 * Handles new SSE connection requests from clients. Creates a new session for each
	 * connection and sets up the SSE event stream.
	 * @param request The incoming server request
	 * @return A Mono which emits a response with the SSE event stream
	 */
	private Mono<Entity> doHandleSseConnection(Context request) {
		if (isClosing) {
			return RxEntity.status(StatusCodes.CODE_SERVICE_UNAVAILABLE).body("Server is shutting down");
		}

		McpTransportContext transportContext = this.contextExtractor.extract(request);

		return RxEntity.ok()
				.contentType(MimeType.TEXT_EVENT_STREAM_VALUE)
				.body(Flux.<SseEvent>create(sink -> {
					WebFluxMcpSessionTransport sessionTransport = new WebFluxMcpSessionTransport(sink);

					McpServerSession session = sessionFactory.create(sessionTransport);
					String sessionId = session.getId();

					logger.debug("Created new SSE connection for session: {}", sessionId);
					sessions.put(sessionId, session);
					sessionRequests.put(sessionId, request);

					// Send initial endpoint event
					logger.debug("Sending initial endpoint event to session: {}", sessionId);
					sink.next(new SseEvent().name(ENDPOINT_EVENT_TYPE).data(buildEndpointUrl(sessionId)));
					sink.onCancel(() -> {
						logger.debug("Session {} cancelled", sessionId);
						sessions.remove(sessionId);
						sessionRequests.remove(sessionId);
					});
				}).contextWrite(ctx -> ctx.put(McpTransportContext.KEY, transportContext)));
	}

	/**
	 * Constructs the full message endpoint URL by combining the base URL, message path,
	 * and the required session_id query parameter.
	 * @param sessionId the unique session identifier
	 * @return the fully qualified endpoint URL as a string
	 */
	private String buildEndpointUrl(String sessionId) {
		return this.baseUrl + this.messageEndpoint + "?sessionId=" + sessionId;
	}

	/**
	 * Handles incoming JSON-RPC messages from clients. Deserializes the message and
	 * processes it through the configured message handler.
	 *
	 * <p>
	 * The handler:
	 * <ul>
	 * <li>Deserializes the incoming JSON-RPC message</li>
	 * <li>Passes it through the message handler chain</li>
	 * <li>Returns appropriate HTTP responses based on processing results</li>
	 * <li>Handles various error conditions with appropriate error responses</li>
	 * </ul>
	 * @param request The incoming server request containing the JSON-RPC message
	 * @return A Mono emitting the response indicating the message processing result
	 */
	private void handleMessage(Context request) throws Throwable{
		Mono<Entity> entityMono = doHandleMessage(request);
		request.returnValue(entityMono);
	}

	/**
	 * Handles incoming JSON-RPC messages from clients. Deserializes the message and
	 * processes it through the configured message handler.
	 *
	 * <p>
	 * The handler:
	 * <ul>
	 * <li>Deserializes the incoming JSON-RPC message</li>
	 * <li>Passes it through the message handler chain</li>
	 * <li>Returns appropriate HTTP responses based on processing results</li>
	 * <li>Handles various error conditions with appropriate error responses</li>
	 * </ul>
	 * @param request The incoming server request containing the JSON-RPC message
	 * @return A Mono emitting the response indicating the message processing result
	 */
	private Mono<Entity> doHandleMessage(Context request) throws Throwable {
		if (isClosing) {
			return RxEntity.status(StatusCodes.CODE_SERVICE_UNAVAILABLE).body("Server is shutting down");
		}

		if (request.paramMap().containsKey("sessionId") == false) {
			return RxEntity.badRequest().body(new McpError("Session ID missing in message endpoint"));
		}

		String sessionId = request.param("sessionId");
		McpServerSession session = sessions.get(sessionId);
		Context sessionRequest = sessionRequests.get(sessionId);

		if (session == null) {
			return RxEntity.status(StatusCodes.CODE_NOT_FOUND)
					.body(new McpError("Session not found: " + sessionId));
		}

		McpTransportContext transportContext = this.contextExtractor.extract(sessionRequest);


		return Mono.just(request.body()).flatMap(body -> {
			try {
				McpSchema.JSONRPCMessage message = McpSchema.deserializeJsonRpcMessage(jsonMapper, body);
				return session.handle(message).flatMap(response -> RxEntity.ok().build()).onErrorResume(error -> {
					logger.error("Error processing  message: {}", error.getMessage());
					// TODO: instead of signalling the error, just respond with 200 OK
					// - the error is signalled on the SSE connection
					// return ServerResponse.ok().build();
					return RxEntity.status(StatusCodes.CODE_INTERNAL_SERVER_ERROR)
							.body(new McpError(error.getMessage()));
				});
			}
			catch (IllegalArgumentException | IOException e) {
				logger.error("Failed to deserialize message: {}", e.getMessage());
				return RxEntity.badRequest().body(new McpError("Invalid message format"));
			}
		}).contextWrite(ctx -> ctx.put(McpTransportContext.KEY, transportContext));
	}

	private class WebFluxMcpSessionTransport implements McpServerTransport {

		private final FluxSink<SseEvent> sink;

		public WebFluxMcpSessionTransport(FluxSink<SseEvent> sink) {
			this.sink = sink;
		}

		@Override
		public Mono<Void> sendMessage(McpSchema.JSONRPCMessage message) {
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
	 * Builder for creating instances of {@link WebRxSseServerTransportProvider}.
	 * <p>
	 * This builder provides a fluent API for configuring and creating instances of
	 * WebFluxSseServerTransportProvider with custom settings.
	 */
	public static class Builder {

		private McpJsonMapper jsonMapper;

		private String baseUrl = DEFAULT_BASE_URL;

		private String messageEndpoint;

		private String sseEndpoint = DEFAULT_SSE_ENDPOINT;

		private Duration keepAliveInterval;

		private McpTransportContextExtractor<Context> contextExtractor = (serverRequest) -> {
			Map<String,Object> context = new HashMap<>();
			context.put(Context.class.getName(), serverRequest);
			return McpTransportContext.create(context);
		};

		/**
		 * Sets the McpJsonMapper to use for JSON serialization/deserialization of MCP
		 * messages.
		 * @param jsonMapper The McpJsonMapper instance. Must not be null.
		 * @return this builder instance
		 * @throws IllegalArgumentException if jsonMapper is null
		 */
		public Builder jsonMapper(McpJsonMapper jsonMapper) {
			Assert.notNull(jsonMapper, "JsonMapper must not be null");
			this.jsonMapper = jsonMapper;
			return this;
		}

		/**
		 * Sets the project basePath as endpoint prefix where clients should send their
		 * JSON-RPC messages
		 * @param baseUrl the message basePath . Must not be null.
		 * @return this builder instance
		 * @throws IllegalArgumentException if basePath is null
		 */
		public Builder basePath(String baseUrl) {
			Assert.notNull(baseUrl, "basePath must not be null");
			this.baseUrl = baseUrl;
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
			this.messageEndpoint = messageEndpoint;
			return this;
		}

		/**
		 * Sets the SSE endpoint path.
		 * @param sseEndpoint The SSE endpoint path. Must not be null.
		 * @return this builder instance
		 * @throws IllegalArgumentException if sseEndpoint is null
		 */
		public Builder sseEndpoint(String sseEndpoint) {
			Assert.notNull(sseEndpoint, "SSE endpoint must not be null");
			this.sseEndpoint = sseEndpoint;
			return this;
		}

		/**
		 * Sets the interval for sending keep-alive pings to clients.
		 * @param keepAliveInterval The keep-alive interval duration. If null, keep-alive
		 * is disabled.
		 * @return this builder instance
		 */
		public Builder keepAliveInterval(Duration keepAliveInterval) {
			this.keepAliveInterval = keepAliveInterval;
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
		 * Builds a new instance of {@link WebRxSseServerTransportProvider} with the
		 * configured settings.
		 * @return A new WebFluxSseServerTransportProvider instance
		 * @throws IllegalStateException if required parameters are not set
		 */
		public WebRxSseServerTransportProvider build() {
			Assert.notNull(messageEndpoint, "Message endpoint must be set");
			return new WebRxSseServerTransportProvider(jsonMapper == null ? McpJsonMapper.getDefault() : jsonMapper,
					baseUrl, messageEndpoint, sseEndpoint, keepAliveInterval, contextExtractor);
		}
	}
}