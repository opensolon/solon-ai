/*
 * Copyright 2024-2024 the original author or authors.
 */

package io.modelcontextprotocol.server.transport;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.server.DefaultMcpTransportContext;
import io.modelcontextprotocol.server.McpTransportContext;
import io.modelcontextprotocol.server.McpTransportContextExtractor;
import io.modelcontextprotocol.spec.*;
import io.modelcontextprotocol.util.Assert;
import io.modelcontextprotocol.util.KeepAliveScheduler;
import io.modelcontextprotocol.util.Utils;
import org.noear.solon.SolonApp;
import org.noear.solon.core.handle.Context;
import org.noear.solon.core.handle.Entity;
import org.noear.solon.core.handle.StatusCodes;
import org.noear.solon.web.sse.SseEmitter;
import org.noear.solon.web.sse.SseEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

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

	private final ObjectMapper objectMapper;

	private final String messageEndpoint;

	private final String sseEndpoint;

	private final String baseUrl;

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

	private KeepAliveScheduler keepAliveScheduler;

	/**
	 * Constructs a new WebMvcSseServerTransportProvider instance.
	 * @param objectMapper The ObjectMapper to use for JSON serialization/deserialization
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
	public WebRxSseServerTransportProvider(ObjectMapper objectMapper, String baseUrl, String messageEndpoint,
										   String sseEndpoint,
                                           McpTransportContextExtractor<Context> contextExtractor,
                                           Duration keepAliveInterval) {
		Assert.notNull(objectMapper, "ObjectMapper must not be null");
		Assert.notNull(baseUrl, "Message base URL must not be null");
		Assert.notNull(messageEndpoint, "Message endpoint must not be null");
		Assert.notNull(sseEndpoint, "SSE endpoint must not be null");

		this.objectMapper = objectMapper;
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
		return Utils.asList(ProtocolVersions.MCP_2024_11_05);
	}

	@Override
	public void setSessionFactory(McpServerSession.Factory sessionFactory) {
		this.sessionFactory = sessionFactory;
	}

	/**
	 * Broadcasts a notification to all connected clients through their SSE connections.
	 * The message is serialized to JSON and sent as an SSE event with type "message". If
	 * any errors occur during sending to a particular client, they are logged but don't
	 * prevent sending to other clients.
	 * @param method The method name for the notification
	 * @param params The parameters for the notification
	 * @return A Mono that completes when the broadcast attempt is finished
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
	 * Initiates a graceful shutdown of the transport. This method:
	 * <ul>
	 * <li>Sets the closing flag to prevent new connections</li>
	 * <li>Closes all active SSE connections</li>
	 * <li>Removes all session records</li>
	 * </ul>
	 * @return A Mono that completes when all cleanup operations are finished
	 */
	@Override
	public Mono<Void> closeGracefully() {
		return Flux.fromIterable(sessions.values()).doFirst(() -> {
			this.isClosing = true;
			logger.debug("Initiating graceful shutdown with {} active sessions", sessions.size());
		}).flatMap(McpServerSession::closeGracefully).then().doOnSuccess(v -> {
			logger.debug("Graceful shutdown completed");
			sessions.clear();
			if (this.keepAliveScheduler != null) {
				this.keepAliveScheduler.shutdown();
			}
		});
	}

	/**
	 * Handles new SSE connection requests from clients by creating a new session and
	 * establishing an SSE connection. This method:
	 * <ul>
	 * <li>Generates a unique session ID</li>
	 * <li>Creates a new session with a WebMvcMcpSessionTransport</li>
	 * <li>Sends an initial endpoint event to inform the client where to send
	 * messages</li>
	 * <li>Maintains the session in the sessions map</li>
	 * </ul>
	 * @param request The incoming server request
	 * @return A ServerResponse configured for SSE communication, or an error response if
	 * the server is shutting down or the connection fails
	 */
	private void handleSseConnection(Context request) throws Throwable {
		request.contentType("");

		Object returnValue = handleSseConnectionDo(request);
		if (returnValue instanceof Entity) {
			Entity entity = (Entity) returnValue;
			if (entity.body() != null) {
				if (entity.body() instanceof McpError) {
					McpError mcpError = (McpError) entity.body();
					entity.body(mcpError.getMessage());
				} else if (entity.body() instanceof McpSchema.JSONRPCResponse) {
					entity.body(objectMapper.writeValueAsString(entity.body()));
				}
			}
		}

		request.returnValue(returnValue);
	}

	private Object handleSseConnectionDo(Context request) {
		if (this.isClosing) {
			return new Entity().status(StatusCodes.CODE_SERVICE_UNAVAILABLE).body("Server is shutting down");
		}

		String sessionId = UUID.randomUUID().toString();
		logger.debug("Creating new SSE connection for session: {}", sessionId);

		// Send initial endpoint event
		try {
			SseEmitter sseBuilder = new SseEmitter(0L);
			sseBuilder.onCompletion(() -> {
				logger.debug("SSE connection completed for session: {}", sessionId);
				sessions.remove(sessionId);
                sessionRequests.remove(sessionId);
			});
			sseBuilder.onTimeout(() -> {
				logger.debug("SSE connection timed out for session: {}", sessionId);
				sessions.remove(sessionId);
                sessionRequests.remove(sessionId);
			});

			sseBuilder.onInited(emitter -> {
				WebRxMcpSessionTransport sessionTransport = new WebRxMcpSessionTransport(sessionId, sseBuilder);
				McpServerSession session = sessionFactory.create(sessionTransport);
				this.sessions.put(sessionId, session);
                this.sessionRequests.put(sessionId, request);

				try {
					sseBuilder.send(new SseEvent().id(sessionId)
							.name(ENDPOINT_EVENT_TYPE)
							.data(this.baseUrl + this.messageEndpoint + "?sessionId=" + sessionId));
				} catch (Exception e) {
					logger.error("Failed to send initial endpoint event: {}", e.getMessage());
					sseBuilder.error(e);
				}
			});

			return sseBuilder;
		}
		catch (Exception e) {
			logger.error("Failed to send initial endpoint event to session {}: {}", sessionId, e.getMessage());
			sessions.remove(sessionId);
            sessionRequests.remove(sessionId);

			return new Entity().status(StatusCodes.CODE_INTERNAL_SERVER_ERROR);
		}
	}

	/**
	 * Handles incoming JSON-RPC messages from clients. This method:
	 * <ul>
	 * <li>Deserializes the request body into a JSON-RPC message</li>
	 * <li>Processes the message through the session's handle method</li>
	 * <li>Returns appropriate HTTP responses based on the processing result</li>
	 * </ul>
	 * @param request The incoming server request containing the JSON-RPC message
	 * @return A ServerResponse indicating success (200 OK) or appropriate error status
	 * with error details in case of failures
	 */
	private void handleMessage(Context request) throws Throwable {
		request.contentType("");

		Entity entity = handleMessageDo(request);
		if (entity.body() != null) {
			if (entity.body() instanceof McpError) {
				McpError mcpError = (McpError) entity.body();
				entity.body(mcpError.getMessage());
			} else if (entity.body() instanceof McpSchema.JSONRPCResponse) {
				entity.body(objectMapper.writeValueAsString(entity.body()));
			}
		}

		request.returnValue(entity);
	}

	private Entity handleMessageDo(Context request) {
		if (this.isClosing) {
			return new Entity().status(StatusCodes.CODE_SERVICE_UNAVAILABLE).body("Server is shutting down");
		}

		if (request.param("sessionId").isEmpty()) {
			return new Entity().status(StatusCodes.CODE_BAD_REQUEST).body(new McpError("Session ID missing in message endpoint"));
		}

		String sessionId = request.param("sessionId");
		McpServerSession session = sessions.get(sessionId);

		if (session == null) {
			return new Entity().status(StatusCodes.CODE_NOT_FOUND).body(new McpError("Session not found: " + sessionId));
		}

        Context sessionRequest = this.sessionRequests.get(sessionId);
        McpTransportContext transportContext = this.contextExtractor.extract(sessionRequest, new DefaultMcpTransportContext());

		try {
			String body = request.body();
			McpSchema.JSONRPCMessage message = McpSchema.deserializeJsonRpcMessage(objectMapper, body);

			// Process the message through the session's handle method
			session.handle(message)
                    .contextWrite(ctx->ctx.put(McpTransportContext.KEY,transportContext))
                    .block(); // Block for WebMVC compatibility

			return new Entity().status(StatusCodes.CODE_OK);
		}
		catch (IllegalArgumentException | IOException e) {
			logger.error("Failed to deserialize message: {}", e.getMessage());
			return new Entity().status(StatusCodes.CODE_BAD_REQUEST).body(new McpError("Invalid message format"));
		}
		catch (Exception e) {
			logger.error("Error handling message: {}", e.getMessage());
			return new Entity().status(StatusCodes.CODE_INTERNAL_SERVER_ERROR).body(new McpError(e.getMessage()));
		}
	}

	/**
	 * Implementation of McpServerTransport for WebMVC SSE sessions. This class handles
	 * the transport-level communication for a specific client session.
	 */
	private class WebRxMcpSessionTransport implements McpServerTransport {

		private final String sessionId;

		private final SseEmitter sseBuilder;

		/**
		 * Lock to ensure thread-safe access to the SSE builder when sending messages.
		 * This prevents concurrent modifications that could lead to corrupted SSE events.
		 */
		private final ReentrantLock sseBuilderLock = new ReentrantLock();

		/**
		 * Creates a new session transport with the specified ID and SSE builder.
		 * @param sessionId The unique identifier for this session
		 * @param sseBuilder The SSE builder for sending server events to the client
		 */
		WebRxMcpSessionTransport(String sessionId, SseEmitter sseBuilder) {
			this.sessionId = sessionId;
			this.sseBuilder = sseBuilder;

			logger.debug("Session transport {} initialized with SSE builder", sessionId);
		}

		/**
		 * Sends a JSON-RPC message to the client through the SSE connection.
		 * @param message The JSON-RPC message to send
		 * @return A Mono that completes when the message has been sent
		 */
		@Override
		public Mono<Void> sendMessage(McpSchema.JSONRPCMessage message) {
			return Mono.fromRunnable(() -> {
				sseBuilderLock.lock();
				try {
					String jsonText = objectMapper.writeValueAsString(message);
					sseBuilder.send(new SseEvent().id(sessionId).name(MESSAGE_EVENT_TYPE).data(jsonText));
					logger.debug("Message sent to session {}: {}", sessionId, jsonText);
				}
				catch (Exception e) {
					logger.error("Failed to send message to session {}: {}", sessionId, e.getMessage());
					sseBuilder.error(e);
				}
				finally {
					sseBuilderLock.unlock();
				}
			});
		}

		/**
		 * Converts data from one type to another using the configured ObjectMapper.
		 * @param data The source data object to convert
		 * @param typeRef The target type reference
		 * @return The converted object of type T
		 * @param <T> The target type
		 */
		@Override
		public <T> T unmarshalFrom(Object data, TypeReference<T> typeRef) {
			return objectMapper.convertValue(data, typeRef);
		}

		/**
		 * Initiates a graceful shutdown of the transport.
		 * @return A Mono that completes when the shutdown is complete
		 */
		@Override
		public Mono<Void> closeGracefully() {
			return Mono.fromRunnable(() -> {
				logger.debug("Closing session transport: {}", sessionId);
				sseBuilderLock.lock();
				try {
					sseBuilder.complete();
					logger.debug("Successfully completed SSE builder for session {}", sessionId);
				}
				catch (Exception e) {
					logger.warn("Failed to complete SSE builder for session {}: {}", sessionId, e.getMessage());
				}
				finally {
					sseBuilderLock.unlock();
				}
			});
		}

		/**
		 * Closes the transport immediately.
		 */
		@Override
		public void close() {
			sseBuilderLock.lock();
			try {
				sseBuilder.complete();
				logger.debug("Successfully completed SSE builder for session {}", sessionId);
			}
			catch (Exception e) {
				logger.warn("Failed to complete SSE builder for session {}: {}", sessionId, e.getMessage());
			}
			finally {
				sseBuilderLock.unlock();
			}
		}

	}

	/**
	 * Creates a new Builder instance for configuring and creating instances of
	 * WebMvcSseServerTransportProvider.
	 * @return A new Builder instance
	 */
	public static Builder builder() {
		return new Builder();
	}

	/**
	 * Builder for creating instances of WebMvcSseServerTransportProvider.
	 * <p>
	 * This builder provides a fluent API for configuring and creating instances of
	 * WebMvcSseServerTransportProvider with custom settings.
	 */
	public static class Builder {

		private ObjectMapper objectMapper = new ObjectMapper();

		private String baseUrl = "";

		private String messageEndpoint;

		private String sseEndpoint = DEFAULT_SSE_ENDPOINT;

        private McpTransportContextExtractor<Context> contextExtractor = (serverRequest, context) -> {
            context.put(Context.class.getName(), serverRequest);
            return context;
        };

		private Duration keepAliveInterval;

		/**
		 * Sets the JSON object mapper to use for message serialization/deserialization.
		 * @param objectMapper The object mapper to use
		 * @return This builder instance for method chaining
		 */
		public Builder objectMapper(ObjectMapper objectMapper) {
			Assert.notNull(objectMapper, "ObjectMapper must not be null");
			this.objectMapper = objectMapper;
			return this;
		}

		/**
		 * Sets the base URL for the server transport.
		 * @param baseUrl The base URL to use
		 * @return This builder instance for method chaining
		 */
		public Builder baseUrl(String baseUrl) {
			Assert.notNull(baseUrl, "Base URL must not be null");
			this.baseUrl = baseUrl;
			return this;
		}

		/**
		 * Sets the endpoint path where clients will send their messages.
		 * @param messageEndpoint The message endpoint path
		 * @return This builder instance for method chaining
		 */
		public Builder messageEndpoint(String messageEndpoint) {
			Assert.hasText(messageEndpoint, "Message endpoint must not be empty");
			this.messageEndpoint = messageEndpoint;
			return this;
		}

		/**
		 * Sets the endpoint path where clients will establish SSE connections.
		 * <p>
		 * If not specified, the default value of {@link #DEFAULT_SSE_ENDPOINT} will be
		 * used.
		 * @param sseEndpoint The SSE endpoint path
		 * @return This builder instance for method chaining
		 */
		public Builder sseEndpoint(String sseEndpoint) {
			Assert.hasText(sseEndpoint, "SSE endpoint must not be empty");
			this.sseEndpoint = sseEndpoint;
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
		 * Sets the interval for keep-alive pings.
		 * <p>
		 * If not specified, keep-alive pings will be disabled.
		 * @param keepAliveInterval The interval duration for keep-alive pings
		 * @return This builder instance for method chaining
		 */
		public Builder keepAliveInterval(Duration keepAliveInterval) {
			this.keepAliveInterval = keepAliveInterval;
			return this;
		}

		/**
		 * Builds a new instance of WebMvcSseServerTransportProvider with the configured
		 * settings.
		 * @return A new WebMvcSseServerTransportProvider instance
		 * @throws IllegalStateException if objectMapper or messageEndpoint is not set
		 */
		public WebRxSseServerTransportProvider build() {
			if (messageEndpoint == null) {
				throw new IllegalStateException("MessageEndpoint must be set");
			}
			return new WebRxSseServerTransportProvider(objectMapper, baseUrl,
                    messageEndpoint, sseEndpoint,
                    contextExtractor,
					keepAliveInterval);
		}
	}
}