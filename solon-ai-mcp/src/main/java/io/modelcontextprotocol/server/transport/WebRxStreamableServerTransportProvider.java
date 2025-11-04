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
import org.noear.solon.core.util.MimeType;
import org.noear.solon.web.sse.SseEmitter;
import org.noear.solon.web.sse.SseEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

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

	private final ObjectMapper objectMapper;

	private McpStreamableServerSession.Factory sessionFactory;

	/**
	 * Map of active client sessions, keyed by mcp-session-id.
	 */
	private final ConcurrentHashMap<String, McpStreamableServerSession> sessions = new ConcurrentHashMap<>();

	private McpTransportContextExtractor<Context> contextExtractor;

	// private Function<ServerRequest, McpTransportContext> contextExtractor = req -> new
	// DefaultMcpTransportContext();

	/**
	 * Flag indicating if the transport is shutting down.
	 */
	private volatile boolean isClosing = false;

	private KeepAliveScheduler keepAliveScheduler;

	/**
	 * Constructs a new WebMvcStreamableServerTransportProvider instance.
	 * @param objectMapper The ObjectMapper to use for JSON serialization/deserialization
	 * of messages.
	 * @param mcpEndpoint The endpoint URI where clients should send their JSON-RPC
	 * messages via HTTP. This endpoint will handle GET, POST, and DELETE requests.
	 * @param disallowDelete Whether to disallow DELETE requests on the endpoint.
	 * @throws IllegalArgumentException if any parameter is null
	 */
	private WebRxStreamableServerTransportProvider(ObjectMapper objectMapper, String mcpEndpoint,
                                                   boolean disallowDelete, McpTransportContextExtractor<Context> contextExtractor,
                                                   Duration keepAliveInterval) {
		Assert.notNull(objectMapper, "ObjectMapper must not be null");
		Assert.notNull(mcpEndpoint, "MCP endpoint must not be null");
		Assert.notNull(contextExtractor, "McpTransportContextExtractor must not be null");

		this.objectMapper = objectMapper;
		this.mcpEndpoint = mcpEndpoint;
		this.disallowDelete = disallowDelete;
		this.contextExtractor = contextExtractor;

		if (keepAliveInterval != null) {
			this.keepAliveScheduler = KeepAliveScheduler
				.builder(() -> (isClosing) ? Flux.empty() : Flux.fromIterable(this.sessions.values()))
				.initialDelay(keepAliveInterval)
				.interval(keepAliveInterval)
				.build();

			this.keepAliveScheduler.start();
		}
		else {
			logger.warn("Keep-alive interval is not set or invalid. No keep-alive will be scheduled.");
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
		return Utils.asList(ProtocolVersions.MCP_2024_11_05, ProtocolVersions.MCP_2025_03_26);
	}

	@Override
	public void setSessionFactory(McpStreamableServerSession.Factory sessionFactory) {
		this.sessionFactory = sessionFactory;
	}

	/**
	 * Broadcasts a notification to all connected clients through their SSE connections.
	 * If any errors occur during sending to a particular client, they are logged but
	 * don't prevent sending to other clients.
	 * @param method The method name for the notification
	 * @param params The parameters for the notification
	 * @return A Mono that completes when the broadcast attempt is finished
	 */
	@Override
	public Mono<Void> notifyClients(String method, Object params) {
		if (this.sessions.isEmpty()) {
			logger.debug("No active sessions to broadcast message to");
			return Mono.empty();
		}

		logger.debug("Attempting to broadcast message to {} active sessions", this.sessions.size());

		return Mono.fromRunnable(() -> {
			this.sessions.values().parallelStream().forEach(session -> {
				try {
					session.sendNotification(method, params).block();
				}
				catch (Exception e) {
					logger.error("Failed to send message to session {}: {}", session.getId(), e.getMessage());
				}
			});
		});
	}

	/**
	 * Initiates a graceful shutdown of the transport.
	 * @return A Mono that completes when all cleanup operations are finished
	 */
	@Override
	public Mono<Void> closeGracefully() {
		return Mono.fromRunnable(() -> {
			this.isClosing = true;
			logger.debug("Initiating graceful shutdown with {} active sessions", this.sessions.size());

			this.sessions.values().parallelStream().forEach(session -> {
				try {
					session.closeGracefully().block();
				}
				catch (Exception e) {
					logger.error("Failed to close session {}: {}", session.getId(), e.getMessage());
				}
			});

			this.sessions.clear();
			logger.debug("Graceful shutdown completed");
		}).then().doOnSuccess(v -> {
			if (this.keepAliveScheduler != null) {
				this.keepAliveScheduler.shutdown();
			}
		});
	}

	/**
	 * Setup the listening SSE connections and message replay.
	 * @param request The incoming server request
	 * @return A ServerResponse configured for SSE communication, or an error response
	 */
	private void handleGet(Context request) throws Throwable {
		Object returnValue = handleGetDo(request);
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

	private Object handleGetDo(Context request) {
		if (this.isClosing) {
			return new Entity().status(StatusCodes.CODE_SERVICE_UNAVAILABLE).body("Server is shutting down");
		}

		String acceptHeaders = request.acceptNew();
		if (!acceptHeaders.contains(MimeType.TEXT_EVENT_STREAM_VALUE)) {
			return new Entity().status(StatusCodes.CODE_BAD_REQUEST).body("Invalid Accept header. Expected TEXT_EVENT_STREAM");
		}

		McpTransportContext transportContext = this.contextExtractor.extract(request, new DefaultMcpTransportContext());

		if (!request.headerMap().containsKey(HttpHeaders.MCP_SESSION_ID)) {
			return new Entity().status(StatusCodes.CODE_BAD_REQUEST).body("Session ID required in mcp-session-id header");
		}

		String sessionId = request.header(HttpHeaders.MCP_SESSION_ID);
		McpStreamableServerSession session = this.sessions.get(sessionId);

		if (session == null) {
			return new Entity().status(StatusCodes.CODE_NOT_FOUND);
		}

		logger.debug("Handling GET request for session: {}", sessionId);

		try {
			SseEmitter sseEmitter = new SseEmitter(-1L);

			sseEmitter.onTimeout(() -> {
				logger.debug("SSE connection timed out for session: {}", sessionId);
			});

			sseEmitter.onInited(emitter -> {
				WebRxStreamableMcpSessionTransport sessionTransport = new WebRxStreamableMcpSessionTransport(
						sessionId, emitter);

				// Check if this is a replay request
				if (request.headerMap().containsKey(HttpHeaders.LAST_EVENT_ID)) {
					String lastId = request.header(HttpHeaders.LAST_EVENT_ID);

					try {
						session.replay(lastId)
								.contextWrite(ctx -> ctx.put(McpTransportContext.KEY, transportContext))
								.toIterable()
								.forEach(message -> {
									try {
										sessionTransport.sendMessage(message)
												.contextWrite(ctx -> ctx.put(McpTransportContext.KEY, transportContext))
												.block();
									} catch (Exception e) {
										logger.error("Failed to replay message: {}", e.getMessage());
										emitter.error(e);
									}
								});
					} catch (Exception e) {
						logger.error("Failed to replay messages: {}", e.getMessage());
						emitter.error(e);
					}
				} else {
					// Establish new listening stream
					McpStreamableServerSession.McpStreamableServerSessionStream listeningStream = session
							.listeningStream(sessionTransport);

					emitter.onCompletion(() -> {
						logger.debug("SSE connection completed for session: {}", sessionId);
						listeningStream.close();
					});
				}
			});

			return sseEmitter;
		} catch (Exception e) {
			logger.error("Failed to handle GET request for session {}: {}", sessionId, e.getMessage());
			return new Entity().status(StatusCodes.CODE_INTERNAL_SERVER_ERROR);
		}
	}

	/**
	 * Handles POST requests for incoming JSON-RPC messages from clients.
	 * @param request The incoming server request containing the JSON-RPC message
	 * @return A ServerResponse indicating success or appropriate error status
	 */
	private void handlePost(Context request) throws Throwable {
		Object returnValue = handlePostDo(request);
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
	private Object handlePostDo(Context request) {
		if (this.isClosing) {
			return new Entity().status(StatusCodes.CODE_SERVICE_UNAVAILABLE).body("Server is shutting down");
		}

		String acceptHeaders = request.acceptNew();
		if (!acceptHeaders.contains(MimeType.TEXT_EVENT_STREAM_VALUE)
				|| !acceptHeaders.contains(MimeType.APPLICATION_JSON_VALUE)) {
			return new Entity().status(StatusCodes.CODE_BAD_REQUEST)
					.body(new McpError("Invalid Accept headers. Expected TEXT_EVENT_STREAM and APPLICATION_JSON"));
		}

		McpTransportContext transportContext = this.contextExtractor.extract(request, new DefaultMcpTransportContext());

		try {
			String body = request.body();
			McpSchema.JSONRPCMessage message = McpSchema.deserializeJsonRpcMessage(objectMapper, body);

			// Handle initialization request
			if (message instanceof McpSchema.JSONRPCRequest) {
				McpSchema.JSONRPCRequest jsonrpcRequest = (McpSchema.JSONRPCRequest) message;

				if (jsonrpcRequest.getMethod().equals(McpSchema.METHOD_INITIALIZE)) {
					McpSchema.InitializeRequest initializeRequest = objectMapper.convertValue(jsonrpcRequest.getParams(),
							new TypeReference<McpSchema.InitializeRequest>() {
							});
					McpStreamableServerSession.McpStreamableServerSessionInit init = this.sessionFactory
							.startSession(initializeRequest);
					this.sessions.put(init.getSession().getId(), init.getSession());

					try {
						McpSchema.InitializeResult initResult = init.getInitResult().block();

						return new Entity()
								.contentType(MimeType.APPLICATION_JSON_VALUE)
								.headerAdd(HttpHeaders.MCP_SESSION_ID, init.getSession().getId())
								.body(new McpSchema.JSONRPCResponse(McpSchema.JSONRPC_VERSION, jsonrpcRequest.getId(), initResult,
										null));
					} catch (Exception e) {
						logger.error("Failed to initialize session: {}", e.getMessage());
						return new Entity().status(StatusCodes.CODE_INTERNAL_SERVER_ERROR).body(new McpError(e.getMessage()));
					}
				}
			}

			// Handle other messages that require a session
			if (!request.headerMap().containsKey(HttpHeaders.MCP_SESSION_ID)) {
				return new Entity().status(StatusCodes.CODE_BAD_REQUEST).body(new McpError("Session ID missing"));
			}

			String sessionId = request.header(HttpHeaders.MCP_SESSION_ID);
			McpStreamableServerSession session = this.sessions.get(sessionId);

			if (session == null) {
				return new Entity().status(StatusCodes.CODE_NOT_FOUND)
						.body(new McpError("Session not found: " + sessionId));
			}

			if (message instanceof McpSchema.JSONRPCResponse) {
				McpSchema.JSONRPCResponse jsonrpcResponse = (McpSchema.JSONRPCResponse) message;
				session.accept(jsonrpcResponse)
						.contextWrite(ctx -> ctx.put(McpTransportContext.KEY, transportContext))
						.block();
				return new Entity().status(StatusCodes.CODE_ACCEPTED);
			} else if (message instanceof McpSchema.JSONRPCNotification) {
				McpSchema.JSONRPCNotification jsonrpcNotification = (McpSchema.JSONRPCNotification) message;
				session.accept(jsonrpcNotification)
						.contextWrite(ctx -> ctx.put(McpTransportContext.KEY, transportContext))
						.block();
				return new Entity().status(StatusCodes.CODE_ACCEPTED);
			} else if (message instanceof McpSchema.JSONRPCRequest) {
				McpSchema.JSONRPCRequest jsonrpcRequest = (McpSchema.JSONRPCRequest) message;
				// For streaming responses, we need to return SSE
				SseEmitter sseEmitter = new SseEmitter(-1L);

				sseEmitter.onCompletion(() -> {
					logger.debug("Request response stream completed for session: {}", sessionId);
				});
				sseEmitter.onTimeout(() -> {
					logger.debug("Request response stream timed out for session: {}", sessionId);
				});

				sseEmitter.onInited(emitter -> {
					WebRxStreamableMcpSessionTransport sessionTransport = new WebRxStreamableMcpSessionTransport(
							sessionId, emitter);

					try {
						session.responseStream(jsonrpcRequest, sessionTransport)
								.contextWrite(ctx -> ctx.put(McpTransportContext.KEY, transportContext))
								.block();
					} catch (Exception e) {
						logger.error("Failed to handle request stream: {}", e.getMessage());
						emitter.error(e);
					}
				});

				return sseEmitter;
			} else {
				return new Entity().status(StatusCodes.CODE_INTERNAL_SERVER_ERROR)
						.body(new McpError("Unknown message type"));
			}
		} catch (IllegalArgumentException | IOException e) {
			logger.error("Failed to deserialize message: {}", e.getMessage());
			return new Entity().status(StatusCodes.CODE_BAD_REQUEST).body(new McpError("Invalid message format"));
		} catch (Exception e) {
			logger.error("Error handling message: {}", e.getMessage());
			return new Entity().status(StatusCodes.CODE_INTERNAL_SERVER_ERROR).body(new McpError(e.getMessage()));
		}
	}

	/**
	 * Handles DELETE requests for session deletion.
	 * @param request The incoming server request
	 * @return A ServerResponse indicating success or appropriate error status
	 */
	private void handleDelete(Context request) throws Throwable {
		Entity entity = handleDeleteDo(request);
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

	private Entity handleDeleteDo(Context request) {
		if (this.isClosing) {
			return new Entity().status(StatusCodes.CODE_SERVICE_UNAVAILABLE).body("Server is shutting down");
		}

		if (this.disallowDelete) {
			return new Entity().status(StatusCodes.CODE_METHOD_NOT_ALLOWED);
		}

		McpTransportContext transportContext = this.contextExtractor.extract(request, new DefaultMcpTransportContext());

		if (!request.headerMap().containsKey(HttpHeaders.MCP_SESSION_ID)) {
			return new Entity().status(StatusCodes.CODE_BAD_REQUEST).body("Session ID required in mcp-session-id header");
		}

		String sessionId = request.header(HttpHeaders.MCP_SESSION_ID);
		McpStreamableServerSession session = this.sessions.get(sessionId);

		if (session == null) {
			return new Entity().status(StatusCodes.CODE_NOT_FOUND);
		}

		try {
			session.delete().contextWrite(ctx -> ctx.put(McpTransportContext.KEY, transportContext)).block();
			this.sessions.remove(sessionId);
			return new Entity();
		}
		catch (Exception e) {
			logger.error("Failed to delete session {}: {}", sessionId, e.getMessage());
			return new Entity().status(StatusCodes.CODE_INTERNAL_SERVER_ERROR).body(new McpError(e.getMessage()));
		}
	}

	/**
	 * Implementation of McpStreamableServerTransport for WebMVC SSE sessions. This class
	 * handles the transport-level communication for a specific client session.
	 *
	 * <p>
	 * This class is thread-safe and uses a ReentrantLock to synchronize access to the
	 * underlying SSE builder to prevent race conditions when multiple threads attempt to
	 * send messages concurrently.
	 */
	private class WebRxStreamableMcpSessionTransport implements McpStreamableServerTransport {

		private final String sessionId;

		private final SseEmitter sseEmitter;

		private final ReentrantLock lock = new ReentrantLock();

		private volatile boolean closed = false;

		/**
		 * Creates a new session transport with the specified ID and SSE builder.
		 * @param sessionId The unique identifier for this session
		 * @param sseEmitter The SSE builder for sending server events to the client
		 */
		WebRxStreamableMcpSessionTransport(String sessionId, SseEmitter sseEmitter) {
			this.sessionId = sessionId;
			this.sseEmitter = sseEmitter;
			logger.debug("Streamable session transport {} initialized with SSE builder", sessionId);
		}

		/**
		 * Sends a JSON-RPC message to the client through the SSE connection.
		 * @param message The JSON-RPC message to send
		 * @return A Mono that completes when the message has been sent
		 */
		@Override
		public Mono<Void> sendMessage(McpSchema.JSONRPCMessage message) {
			return sendMessage(message, null);
		}

		/**
		 * Sends a JSON-RPC message to the client through the SSE connection with a
		 * specific message ID.
		 * @param message The JSON-RPC message to send
		 * @param messageId The message ID for SSE event identification
		 * @return A Mono that completes when the message has been sent
		 */
		@Override
		public Mono<Void> sendMessage(McpSchema.JSONRPCMessage message, String messageId) {
			return Mono.fromRunnable(() -> {
				if (this.closed) {
					logger.debug("Attempted to send message to closed session: {}", this.sessionId);
					return;
				}

				this.lock.lock();
				try {
					if (this.closed) {
						logger.debug("Session {} was closed during message send attempt", this.sessionId);
						return;
					}

					String jsonText = objectMapper.writeValueAsString(message);
					this.sseEmitter.send(new SseEvent().id(messageId != null ? messageId : this.sessionId)
							.name(MESSAGE_EVENT_TYPE)
							.data(jsonText));
					logger.debug("Message sent to session {} with ID {}: {}", this.sessionId, messageId, jsonText);
				}
				catch (Exception e) {
					logger.error("Failed to send message to session {}: {}", this.sessionId, e.getMessage());
					try {
						this.sseEmitter.error(e);
					}
					catch (Exception errorException) {
						logger.error("Failed to send error to SSE builder for session {}: {}", this.sessionId,
								errorException.getMessage());
					}
				}
				finally {
					this.lock.unlock();
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
				WebRxStreamableMcpSessionTransport.this.close();
			});
		}

		/**
		 * Closes the transport immediately.
		 */
		@Override
		public void close() {
			this.lock.lock();
			try {
				if (this.closed) {
					logger.debug("Session transport {} already closed", this.sessionId);
					return;
				}

				this.closed = true;

				this.sseEmitter.complete();
				logger.debug("Successfully completed SSE builder for session {}", sessionId);
			}
			catch (Exception e) {
				logger.warn("Failed to complete SSE builder for session {}: {}", sessionId, e.getMessage());
			}
			finally {
				this.lock.unlock();
			}
		}

	}

	public static Builder builder() {
		return new Builder();
	}

	/**
	 * Builder for creating instances of {@link WebRxStreamableServerTransportProvider}.
	 */
	public static class Builder {

		private ObjectMapper objectMapper;

		private String mcpEndpoint = "/mcp";

		private boolean disallowDelete = false;

		private McpTransportContextExtractor<Context> contextExtractor = (serverRequest, context) -> {
			context.put(Context.class.getName(), serverRequest);
			return context;
		};

		private Duration keepAliveInterval;

		/**
		 * Sets the ObjectMapper to use for JSON serialization/deserialization of MCP
		 * messages.
		 * @param objectMapper The ObjectMapper instance. Must not be null.
		 * @return this builder instance
		 * @throws IllegalArgumentException if objectMapper is null
		 */
		public Builder objectMapper(ObjectMapper objectMapper) {
			Assert.notNull(objectMapper, "ObjectMapper must not be null");
			this.objectMapper = objectMapper;
			return this;
		}

		/**
		 * Sets the endpoint URI where clients should send their JSON-RPC messages.
		 * @param mcpEndpoint The MCP endpoint URI. Must not be null.
		 * @return this builder instance
		 * @throws IllegalArgumentException if mcpEndpoint is null
		 */
		public Builder mcpEndpoint(String mcpEndpoint) {
			Assert.notNull(mcpEndpoint, "MCP endpoint must not be null");
			this.mcpEndpoint = mcpEndpoint;
			return this;
		}

		/**
		 * Sets whether to disallow DELETE requests on the endpoint.
		 * @param disallowDelete true to disallow DELETE requests, false otherwise
		 * @return this builder instance
		 */
		public Builder disallowDelete(boolean disallowDelete) {
			this.disallowDelete = disallowDelete;
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
		 * Sets the keep-alive interval for the transport. If set, a keep-alive scheduler
		 * will be created to periodically check and send keep-alive messages to clients.
		 * @param keepAliveInterval The interval duration for keep-alive messages, or null
		 * to disable keep-alive
		 * @return this builder instance
		 */
		public Builder keepAliveInterval(Duration keepAliveInterval) {
			this.keepAliveInterval = keepAliveInterval;
			return this;
		}

		/**
		 * Builds a new instance of {@link WebRxStreamableServerTransportProvider} with
		 * the configured settings.
		 * @return A new WebMvcStreamableServerTransportProvider instance
		 * @throws IllegalStateException if required parameters are not set
		 */
		public WebRxStreamableServerTransportProvider build() {
			Assert.notNull(this.objectMapper, "ObjectMapper must be set");
			Assert.notNull(this.mcpEndpoint, "MCP endpoint must be set");

			return new WebRxStreamableServerTransportProvider(this.objectMapper, this.mcpEndpoint, this.disallowDelete,
					this.contextExtractor, this.keepAliveInterval);
		}

	}
}