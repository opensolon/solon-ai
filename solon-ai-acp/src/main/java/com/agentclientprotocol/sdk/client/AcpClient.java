/*
 * Copyright 2025-2025 the original author or authors.
 */

package com.agentclientprotocol.sdk.client;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

import java.util.concurrent.Executors;
import java.util.stream.Collectors;

import com.agentclientprotocol.sdk.spec.AcpClientSession;
import com.agentclientprotocol.sdk.spec.AcpClientTransport;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import com.agentclientprotocol.sdk.spec.AcpSession;
import com.agentclientprotocol.sdk.util.Assert;
import io.modelcontextprotocol.json.TypeRef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

/**
 * Factory class for creating Agent Client Protocol (ACP) clients. ACP is a protocol that
 * enables applications to interact with autonomous coding agents through a standardized
 * interface.
 *
 * <p>
 * This class serves as the main entry point for establishing connections with ACP agents,
 * implementing the client-side of the ACP specification. The protocol follows a
 * client-agent architecture where:
 * <ul>
 * <li>The client (this implementation) initiates connections and sends prompts</li>
 * <li>The agent responds to prompts and can request client capabilities (file access,
 * etc.)</li>
 * <li>Communication occurs through a transport layer (e.g., stdio) using JSON-RPC
 * 2.0</li>
 * </ul>
 *
 * <p>
 * The class provides factory methods to create either:
 * <ul>
 * <li>{@link AcpAsyncClient} for non-blocking operations with Mono responses</li>
 * <li>{@link AcpSyncClient} for blocking operations with direct responses (future)</li>
 * </ul>
 *
 * <p>
 * Example of creating a basic asynchronous client:
 *
 * <pre>{@code
 * // Create transport
 * AgentParameters params = AgentParameters.builder("gemini")
 *     .arg("--experimental-acp")
 *     .build();
 * StdioAcpClientTransport transport = new StdioAcpClientTransport(params, McpJsonMapper.getDefault());
 *
 * // Build client
 * AcpAsyncClient client = AcpClient.async(transport)
 *     .requestTimeout(Duration.ofSeconds(30))
 *     .sessionUpdateConsumer(notification -> {
 *         System.out.println("Session update: " + notification);
 *         return Mono.empty();
 *     })
 *     .build();
 *
 * // Initialize and use
 * client.initialize(new AcpSchema.InitializeRequest(1, new AcpSchema.ClientCapabilities()))
 *     .flatMap(initResponse -> client.newSession(new AcpSchema.NewSessionRequest("/workspace", List.of())))
 *     .flatMap(sessionResponse -> client.prompt(new AcpSchema.PromptRequest(
 *         sessionResponse.sessionId(),
 *         List.of(new AcpSchema.TextContent("Fix the failing test")))))
 *     .doOnNext(response -> System.out.println("Response: " + response))
 *     .block();
 *
 * client.closeGracefully().block();
 * }</pre>
 *
 * <p>
 * The client supports:
 * <ul>
 * <li>Protocol version negotiation and capability exchange</li>
 * <li>Optional authentication with various methods</li>
 * <li>Session creation and management</li>
 * <li>Prompt submission with streaming updates</li>
 * <li>File system operations (read/write) through client handlers</li>
 * <li>Permission requests for sensitive operations</li>
 * <li>Terminal operations for command execution</li>
 * </ul>
 *
 * @author Mark Pollack
 * @author Christian Tzolov
 * @see AcpAsyncClient
 * @see AcpClientTransport
 */
public interface AcpClient {

	Logger logger = LoggerFactory.getLogger(AcpClient.class);

	// ====================================================================
	// Sync Handler Scheduler (library-owned, daemon threads)
	// ====================================================================

	/**
	 * Library-owned scheduler for executing synchronous handlers.
	 * Uses daemon threads with descriptive names to prevent JVM hang on exit.
	 * This follows the best practice of never using global Schedulers.boundedElastic().
	 */
	Scheduler SYNC_HANDLER_SCHEDULER = Schedulers.fromExecutorService(
			Executors.newCachedThreadPool(r -> {
				Thread t = new Thread(r, "acp-sync-handler");
				t.setDaemon(true);
				return t;
			}), "acp-sync-handler");

	// ====================================================================
	// Sync Handler Interfaces (for use with AcpClient.sync())
	// ====================================================================

	/**
	 * Functional interface for synchronous request handlers. Unlike
	 * {@link AcpClientSession.RequestHandler}, this interface returns the response
	 * directly without wrapping in Mono, making it natural for blocking I/O operations.
	 *
	 * <p>Use with {@link SyncSpec} builder methods to register handlers that don't
	 * require reactive programming patterns.
	 *
	 * @param <T> The response type
	 */
	@FunctionalInterface
	interface SyncRequestHandler<T> {
		/**
		 * Handles an incoming request with the given parameters.
		 * @param params The raw request parameters (requires unmarshalling)
		 * @return The response object
		 */
		T handle(Object params);
	}

	/**
	 * Start building a synchronous ACP client with the specified transport layer. The
	 * synchronous ACP client provides blocking operations. Synchronous clients wait for
	 * each operation to complete before returning, making them simpler to use but
	 * potentially less performant for concurrent operations.
	 * @param transport The transport layer implementation for ACP communication
	 * @return A new builder instance for configuring the client
	 * @throws IllegalArgumentException if transport is null
	 */
	static SyncSpec sync(AcpClientTransport transport) {
		return new SyncSpec(transport);
	}

	/**
	 * Start building an asynchronous ACP client with the specified transport layer. The
	 * asynchronous ACP client provides non-blocking operations using Project Reactor's
	 * Mono type. The transport layer handles the low-level communication between client
	 * and agent using protocols like stdio.
	 * @param transport The transport layer implementation for ACP communication. Common
	 * implementation is {@code StdioAcpClientTransport} for stdio-based communication.
	 * @return A new builder instance for configuring the client
	 * @throws IllegalArgumentException if transport is null
	 */
	static AsyncSpec async(AcpClientTransport transport) {
		return new AsyncSpec(transport);
	}

	/**
	 * Asynchronous client specification. This class follows the builder pattern to
	 * provide a fluent API for setting up clients with custom configurations.
	 *
	 * <p>
	 * The builder supports configuration of:
	 * <ul>
	 * <li>Transport layer for client-agent communication</li>
	 * <li>Request timeouts for operation boundaries</li>
	 * <li>Client capabilities for feature negotiation</li>
	 * <li>Request handlers for incoming agent requests (file operations, etc.)</li>
	 * <li>Notification handlers for streaming updates</li>
	 * </ul>
	 */
	class AsyncSpec {

		private final AcpClientTransport transport;

		private Duration requestTimeout = Duration.ofSeconds(30); // Default timeout

		private AcpSchema.ClientCapabilities clientCapabilities;

		private final Map<String, AcpClientSession.RequestHandler<?>> requestHandlers = new HashMap<>();

		private final Map<String, AcpClientSession.NotificationHandler> notificationHandlers = new HashMap<>();

		private final List<Function<AcpSchema.SessionNotification, Mono<Void>>> sessionUpdateConsumers = new ArrayList<>();

		private AsyncSpec(AcpClientTransport transport) {
			Assert.notNull(transport, "Transport must not be null");
			this.transport = transport;
		}

		/**
		 * Sets the duration to wait for agent responses before timing out requests. This
		 * timeout applies to all requests made through the client, including initialize,
		 * prompt, and session operations.
		 * @param requestTimeout The duration to wait before timing out requests. Must not
		 * be null.
		 * @return This builder instance for method chaining
		 * @throws IllegalArgumentException if requestTimeout is null
		 */
		public AsyncSpec requestTimeout(Duration requestTimeout) {
			Assert.notNull(requestTimeout, "Request timeout must not be null");
			this.requestTimeout = requestTimeout;
			return this;
		}

		/**
		 * Sets the client capabilities that will be advertised to the agent during
		 * initialization. Capabilities define what features the client supports, such as
		 * file system operations, terminal access, and authentication methods.
		 * @param clientCapabilities The client capabilities configuration. Must not be
		 * null.
		 * @return This builder instance for method chaining
		 * @throws IllegalArgumentException if clientCapabilities is null
		 */
		public AsyncSpec clientCapabilities(AcpSchema.ClientCapabilities clientCapabilities) {
			Assert.notNull(clientCapabilities, "Client capabilities must not be null");
			this.clientCapabilities = clientCapabilities;
			return this;
		}

		/**
		 * Adds a typed handler for file system read requests from the agent.
		 * This is the preferred method as it provides type-safe request handling
		 * without manual unmarshalling.
		 *
		 * <p>Example usage:
		 * <pre>{@code
		 * .readTextFileHandler(req ->
		 *     Mono.fromCallable(() -> Files.readString(Path.of(req.path())))
		 *         .map(ReadTextFileResponse::new))
		 * }</pre>
		 *
		 * @param handler The typed handler function that processes read requests
		 * @return This builder instance for method chaining
		 * @throws IllegalArgumentException if handler is null
		 */
		public AsyncSpec readTextFileHandler(
				Function<AcpSchema.ReadTextFileRequest, Mono<AcpSchema.ReadTextFileResponse>> handler) {
			Assert.notNull(handler, "Read text file handler must not be null");
			AcpClientSession.RequestHandler<AcpSchema.ReadTextFileResponse> rawHandler = params -> {
				AcpSchema.ReadTextFileRequest request = transport.unmarshalFrom(params,
						new TypeRef<AcpSchema.ReadTextFileRequest>() {});
				return handler.apply(request);
			};
			this.requestHandlers.put(AcpSchema.METHOD_FS_READ_TEXT_FILE, rawHandler);
			return this;
		}

		/**
		 * Adds a typed handler for file system write requests from the agent.
		 * This is the preferred method as it provides type-safe request handling
		 * without manual unmarshalling.
		 *
		 * <p>Example usage:
		 * <pre>{@code
		 * .writeTextFileHandler(req ->
		 *     Mono.fromRunnable(() -> Files.writeString(Path.of(req.path()), req.content()))
		 *         .then(Mono.just(new WriteTextFileResponse())))
		 * }</pre>
		 *
		 * @param handler The typed handler function that processes write requests
		 * @return This builder instance for method chaining
		 * @throws IllegalArgumentException if handler is null
		 */
		public AsyncSpec writeTextFileHandler(
				Function<AcpSchema.WriteTextFileRequest, Mono<AcpSchema.WriteTextFileResponse>> handler) {
			Assert.notNull(handler, "Write text file handler must not be null");
			AcpClientSession.RequestHandler<AcpSchema.WriteTextFileResponse> rawHandler = params -> {
				AcpSchema.WriteTextFileRequest request = transport.unmarshalFrom(params,
						new TypeRef<AcpSchema.WriteTextFileRequest>() {});
				return handler.apply(request);
			};
			this.requestHandlers.put(AcpSchema.METHOD_FS_WRITE_TEXT_FILE, rawHandler);
			return this;
		}

		/**
		 * Adds a typed handler for permission requests from the agent.
		 * This is the preferred method as it provides type-safe request handling
		 * without manual unmarshalling.
		 *
		 * <p>Example usage:
		 * <pre>{@code
		 * .requestPermissionHandler(req ->
		 *     Mono.just(new RequestPermissionResponse(
		 *         new RequestPermissionOutcome("approve", null))))
		 * }</pre>
		 *
		 * @param handler The typed handler function that processes permission requests
		 * @return This builder instance for method chaining
		 * @throws IllegalArgumentException if handler is null
		 */
		public AsyncSpec requestPermissionHandler(
				Function<AcpSchema.RequestPermissionRequest, Mono<AcpSchema.RequestPermissionResponse>> handler) {
			Assert.notNull(handler, "Request permission handler must not be null");
			AcpClientSession.RequestHandler<AcpSchema.RequestPermissionResponse> rawHandler = params -> {
				AcpSchema.RequestPermissionRequest request = transport.unmarshalFrom(params,
						new TypeRef<AcpSchema.RequestPermissionRequest>() {});
				return handler.apply(request);
			};
			this.requestHandlers.put(AcpSchema.METHOD_SESSION_REQUEST_PERMISSION, rawHandler);
			return this;
		}

		/**
		 * Adds a typed handler for terminal creation requests from the agent.
		 *
		 * <p>Example usage:
		 * <pre>{@code
		 * .createTerminalHandler(req -> {
		 *     String terminalId = UUID.randomUUID().toString();
		 *     // Start process with req.command(), req.args(), req.cwd()
		 *     return Mono.just(new CreateTerminalResponse(terminalId));
		 * })
		 * }</pre>
		 *
		 * @param handler The typed handler function that processes terminal creation requests
		 * @return This builder instance for method chaining
		 * @throws IllegalArgumentException if handler is null
		 */
		public AsyncSpec createTerminalHandler(
				Function<AcpSchema.CreateTerminalRequest, Mono<AcpSchema.CreateTerminalResponse>> handler) {
			Assert.notNull(handler, "Create terminal handler must not be null");
			AcpClientSession.RequestHandler<AcpSchema.CreateTerminalResponse> rawHandler = params -> {
				AcpSchema.CreateTerminalRequest request = transport.unmarshalFrom(params,
						new TypeRef<AcpSchema.CreateTerminalRequest>() {});
				return handler.apply(request);
			};
			this.requestHandlers.put(AcpSchema.METHOD_TERMINAL_CREATE, rawHandler);
			return this;
		}

		/**
		 * Adds a typed handler for terminal output requests from the agent.
		 *
		 * <p>Example usage:
		 * <pre>{@code
		 * .terminalOutputHandler(req -> {
		 *     String output = getTerminalOutput(req.terminalId());
		 *     return Mono.just(new TerminalOutputResponse(output, false, null));
		 * })
		 * }</pre>
		 *
		 * @param handler The typed handler function that processes terminal output requests
		 * @return This builder instance for method chaining
		 * @throws IllegalArgumentException if handler is null
		 */
		public AsyncSpec terminalOutputHandler(
				Function<AcpSchema.TerminalOutputRequest, Mono<AcpSchema.TerminalOutputResponse>> handler) {
			Assert.notNull(handler, "Terminal output handler must not be null");
			AcpClientSession.RequestHandler<AcpSchema.TerminalOutputResponse> rawHandler = params -> {
				AcpSchema.TerminalOutputRequest request = transport.unmarshalFrom(params,
						new TypeRef<AcpSchema.TerminalOutputRequest>() {});
				return handler.apply(request);
			};
			this.requestHandlers.put(AcpSchema.METHOD_TERMINAL_OUTPUT, rawHandler);
			return this;
		}

		/**
		 * Adds a typed handler for terminal release requests from the agent.
		 *
		 * <p>Example usage:
		 * <pre>{@code
		 * .releaseTerminalHandler(req -> {
		 *     releaseTerminal(req.terminalId());
		 *     return Mono.just(new ReleaseTerminalResponse());
		 * })
		 * }</pre>
		 *
		 * @param handler The typed handler function that processes terminal release requests
		 * @return This builder instance for method chaining
		 * @throws IllegalArgumentException if handler is null
		 */
		public AsyncSpec releaseTerminalHandler(
				Function<AcpSchema.ReleaseTerminalRequest, Mono<AcpSchema.ReleaseTerminalResponse>> handler) {
			Assert.notNull(handler, "Release terminal handler must not be null");
			AcpClientSession.RequestHandler<AcpSchema.ReleaseTerminalResponse> rawHandler = params -> {
				AcpSchema.ReleaseTerminalRequest request = transport.unmarshalFrom(params,
						new TypeRef<AcpSchema.ReleaseTerminalRequest>() {});
				return handler.apply(request);
			};
			this.requestHandlers.put(AcpSchema.METHOD_TERMINAL_RELEASE, rawHandler);
			return this;
		}

		/**
		 * Adds a typed handler for wait-for-terminal-exit requests from the agent.
		 *
		 * <p>Example usage:
		 * <pre>{@code
		 * .waitForTerminalExitHandler(req -> {
		 *     int exitCode = waitForExit(req.terminalId());
		 *     return Mono.just(new WaitForTerminalExitResponse(exitCode, null));
		 * })
		 * }</pre>
		 *
		 * @param handler The typed handler function that processes wait-for-exit requests
		 * @return This builder instance for method chaining
		 * @throws IllegalArgumentException if handler is null
		 */
		public AsyncSpec waitForTerminalExitHandler(
				Function<AcpSchema.WaitForTerminalExitRequest, Mono<AcpSchema.WaitForTerminalExitResponse>> handler) {
			Assert.notNull(handler, "Wait for terminal exit handler must not be null");
			AcpClientSession.RequestHandler<AcpSchema.WaitForTerminalExitResponse> rawHandler = params -> {
				AcpSchema.WaitForTerminalExitRequest request = transport.unmarshalFrom(params,
						new TypeRef<AcpSchema.WaitForTerminalExitRequest>() {});
				return handler.apply(request);
			};
			this.requestHandlers.put(AcpSchema.METHOD_TERMINAL_WAIT_FOR_EXIT, rawHandler);
			return this;
		}

		/**
		 * Adds a typed handler for terminal kill requests from the agent.
		 *
		 * <p>Example usage:
		 * <pre>{@code
		 * .killTerminalHandler(req -> {
		 *     killProcess(req.terminalId());
		 *     return Mono.just(new KillTerminalCommandResponse());
		 * })
		 * }</pre>
		 *
		 * @param handler The typed handler function that processes terminal kill requests
		 * @return This builder instance for method chaining
		 * @throws IllegalArgumentException if handler is null
		 */
		public AsyncSpec killTerminalHandler(
				Function<AcpSchema.KillTerminalCommandRequest, Mono<AcpSchema.KillTerminalCommandResponse>> handler) {
			Assert.notNull(handler, "Kill terminal handler must not be null");
			AcpClientSession.RequestHandler<AcpSchema.KillTerminalCommandResponse> rawHandler = params -> {
				AcpSchema.KillTerminalCommandRequest request = transport.unmarshalFrom(params,
						new TypeRef<AcpSchema.KillTerminalCommandRequest>() {});
				return handler.apply(request);
			};
			this.requestHandlers.put(AcpSchema.METHOD_TERMINAL_KILL, rawHandler);
			return this;
		}

		/**
		 * Adds a consumer to be notified when session update notifications are received
		 * from the agent. Session updates include agent thoughts, message chunks, and
		 * other streaming content during prompt processing.
		 * @param sessionUpdateConsumer A consumer that receives session update
		 * notifications. Must not be null.
		 * @return This builder instance for method chaining
		 * @throws IllegalArgumentException if sessionUpdateConsumer is null
		 */
		public AsyncSpec sessionUpdateConsumer(
				Function<AcpSchema.SessionNotification, Mono<Void>> sessionUpdateConsumer) {
			Assert.notNull(sessionUpdateConsumer, "Session update consumer must not be null");
			this.sessionUpdateConsumers.add(sessionUpdateConsumer);
			return this;
		}

		/**
		 * Adds a custom request handler for a specific method. This allows handling
		 * additional agent requests beyond the standard file system and permission
		 * operations.
		 * @param method The method name (e.g., "custom/operation")
		 * @param handler The handler function for this method
		 * @return This builder instance for method chaining
		 * @throws IllegalArgumentException if method or handler is null
		 */
		public AsyncSpec requestHandler(String method, AcpClientSession.RequestHandler<?> handler) {
			Assert.notNull(method, "Method must not be null");
			Assert.notNull(handler, "Handler must not be null");
			this.requestHandlers.put(method, handler);
			return this;
		}

		/**
		 * Adds a custom notification handler for a specific method. This allows handling
		 * additional agent notifications beyond session updates.
		 * @param method The method name (e.g., "custom/notification")
		 * @param handler The handler function for this method
		 * @return This builder instance for method chaining
		 * @throws IllegalArgumentException if method or handler is null
		 */
		public AsyncSpec notificationHandler(String method, AcpClientSession.NotificationHandler handler) {
			Assert.notNull(method, "Method must not be null");
			Assert.notNull(handler, "Handler must not be null");
			this.notificationHandlers.put(method, handler);
			return this;
		}

		/**
		 * Creates an instance of {@link AcpAsyncClient} with the provided configurations
		 * or sensible defaults.
		 * @return a new instance of {@link AcpAsyncClient}
		 */
		public AcpAsyncClient build() {
			// Set up session update notification handler
			if (!sessionUpdateConsumers.isEmpty()) {
				notificationHandlers.put(AcpSchema.METHOD_SESSION_UPDATE, params -> {
					AcpSchema.SessionNotification notification = transport.unmarshalFrom(params,
							new io.modelcontextprotocol.json.TypeRef<AcpSchema.SessionNotification>() {
							});
					logger.debug("Received session update for session: {}", notification.sessionId());

					// Call all registered consumers
					return Mono
						.when(sessionUpdateConsumers.stream().map(consumer -> consumer.apply(notification)).collect(Collectors.toList()));
				});
			}

			// Create session with request and notification handlers
			AcpSession session = new AcpClientSession(requestTimeout, transport, requestHandlers, notificationHandlers,
					Function.identity());

			return new AcpAsyncClient(session, transport, clientCapabilities);
		}

	}

	/**
	 * Synchronous client specification. This class follows the builder pattern to
	 * provide a fluent API for setting up synchronous clients with custom configurations.
	 *
	 * <p>
	 * The builder supports configuration of:
	 * <ul>
	 * <li>Transport layer for client-agent communication</li>
	 * <li>Request timeouts for operation boundaries</li>
	 * <li>Client capabilities for feature negotiation</li>
	 * <li>Request handlers for incoming agent requests (file operations, etc.)</li>
	 * <li>Notification handlers for streaming updates</li>
	 * </ul>
	 */
	class SyncSpec {

		private final AcpClientTransport transport;

		private Duration requestTimeout = Duration.ofSeconds(30); // Default timeout

		private AcpSchema.ClientCapabilities clientCapabilities;

		private final Map<String, AcpClientSession.RequestHandler<?>> requestHandlers = new HashMap<>();

		private final Map<String, AcpClientSession.NotificationHandler> notificationHandlers = new HashMap<>();

		private final List<Function<AcpSchema.SessionNotification, Mono<Void>>> sessionUpdateConsumers = new ArrayList<>();

		private SyncSpec(AcpClientTransport transport) {
			Assert.notNull(transport, "Transport must not be null");
			this.transport = transport;
		}

		/**
		 * Converts a sync request handler to an async request handler.
		 * Follows the MCP SDK pattern of wrapping sync handlers with Mono.fromCallable()
		 * and scheduling on a library-owned daemon scheduler to prevent blocking the event loop.
		 *
		 * @param <T> The response type
		 * @param syncHandler The synchronous handler to convert
		 * @return An async handler that wraps the sync handler
		 */
		private static <T> AcpClientSession.RequestHandler<T> fromSync(SyncRequestHandler<T> syncHandler) {
			return params -> Mono.fromCallable(() -> syncHandler.handle(params))
					.subscribeOn(SYNC_HANDLER_SCHEDULER);
		}

		/**
		 * Sets the duration to wait for agent responses before timing out requests. This
		 * timeout applies to all requests made through the client, including initialize,
		 * prompt, and session operations.
		 * @param requestTimeout The duration to wait before timing out requests. Must not
		 * be null.
		 * @return This builder instance for method chaining
		 * @throws IllegalArgumentException if requestTimeout is null
		 */
		public SyncSpec requestTimeout(Duration requestTimeout) {
			Assert.notNull(requestTimeout, "Request timeout must not be null");
			this.requestTimeout = requestTimeout;
			return this;
		}

		/**
		 * Sets the client capabilities that will be advertised to the agent during
		 * initialization. Capabilities define what features the client supports, such as
		 * file system operations, terminal access, and authentication methods.
		 * @param clientCapabilities The client capabilities configuration. Must not be
		 * null.
		 * @return This builder instance for method chaining
		 * @throws IllegalArgumentException if clientCapabilities is null
		 */
		public SyncSpec clientCapabilities(AcpSchema.ClientCapabilities clientCapabilities) {
			Assert.notNull(clientCapabilities, "Client capabilities must not be null");
			this.clientCapabilities = clientCapabilities;
			return this;
		}

		/**
		 * Adds a typed handler for file system read requests from the agent.
		 * Provides type-safe request handling with automatic unmarshalling.
		 *
		 * <p>Example usage:
		 * <pre>{@code
		 * .readTextFileHandler(req ->
		 *     new ReadTextFileResponse(Files.readString(Path.of(req.path()))))
		 * }</pre>
		 *
		 * @param handler The typed handler function that processes read requests
		 * @return This builder instance for method chaining
		 * @throws IllegalArgumentException if handler is null
		 */
		public SyncSpec readTextFileHandler(
				Function<AcpSchema.ReadTextFileRequest, AcpSchema.ReadTextFileResponse> handler) {
			Assert.notNull(handler, "Read text file handler must not be null");
			SyncRequestHandler<AcpSchema.ReadTextFileResponse> rawHandler = params -> {
				logger.debug("readTextFile request params: {}", params);
				AcpSchema.ReadTextFileRequest request = transport.unmarshalFrom(params,
						new TypeRef<AcpSchema.ReadTextFileRequest>() {});
				return handler.apply(request);
			};
			this.requestHandlers.put(AcpSchema.METHOD_FS_READ_TEXT_FILE, fromSync(rawHandler));
			return this;
		}

		/**
		 * Adds a typed handler for file system write requests from the agent.
		 * Provides type-safe request handling with automatic unmarshalling.
		 *
		 * <p>Example usage:
		 * <pre>{@code
		 * .writeTextFileHandler(req -> {
		 *     Files.writeString(Path.of(req.path()), req.content());
		 *     return new WriteTextFileResponse();
		 * })
		 * }</pre>
		 *
		 * @param handler The typed handler function that processes write requests
		 * @return This builder instance for method chaining
		 * @throws IllegalArgumentException if handler is null
		 */
		public SyncSpec writeTextFileHandler(
				Function<AcpSchema.WriteTextFileRequest, AcpSchema.WriteTextFileResponse> handler) {
			Assert.notNull(handler, "Write text file handler must not be null");
			SyncRequestHandler<AcpSchema.WriteTextFileResponse> rawHandler = params -> {
				logger.debug("writeTextFile request params: {}", params);
				AcpSchema.WriteTextFileRequest request = transport.unmarshalFrom(params,
						new TypeRef<AcpSchema.WriteTextFileRequest>() {});
				return handler.apply(request);
			};
			this.requestHandlers.put(AcpSchema.METHOD_FS_WRITE_TEXT_FILE, fromSync(rawHandler));
			return this;
		}

		/**
		 * Adds a typed handler for permission requests from the agent.
		 * Provides type-safe request handling with automatic unmarshalling.
		 *
		 * <p>Example usage:
		 * <pre>{@code
		 * .requestPermissionHandler(req -> {
		 *     System.out.println("Permission requested: " + req.toolCall().title());
		 *     // Show UI or auto-approve
		 *     return new RequestPermissionResponse(
		 *         new RequestPermissionOutcome("approve", null));
		 * })
		 * }</pre>
		 *
		 * @param handler The typed handler function that processes permission requests
		 * @return This builder instance for method chaining
		 * @throws IllegalArgumentException if handler is null
		 */
		public SyncSpec requestPermissionHandler(
				Function<AcpSchema.RequestPermissionRequest, AcpSchema.RequestPermissionResponse> handler) {
			Assert.notNull(handler, "Request permission handler must not be null");
			SyncRequestHandler<AcpSchema.RequestPermissionResponse> rawHandler = params -> {
				logger.debug("requestPermission request params: {}", params);
				AcpSchema.RequestPermissionRequest request = transport.unmarshalFrom(params,
						new TypeRef<AcpSchema.RequestPermissionRequest>() {});
				return handler.apply(request);
			};
			this.requestHandlers.put(AcpSchema.METHOD_SESSION_REQUEST_PERMISSION, fromSync(rawHandler));
			return this;
		}

		/**
		 * Adds a typed handler for terminal creation requests from the agent.
		 *
		 * <p>Example usage:
		 * <pre>{@code
		 * .createTerminalHandler(req -> {
		 *     String terminalId = UUID.randomUUID().toString();
		 *     // Start process with req.command(), req.args(), req.cwd()
		 *     return new CreateTerminalResponse(terminalId);
		 * })
		 * }</pre>
		 *
		 * @param handler The typed handler function that processes terminal creation requests
		 * @return This builder instance for method chaining
		 * @throws IllegalArgumentException if handler is null
		 */
		public SyncSpec createTerminalHandler(
				Function<AcpSchema.CreateTerminalRequest, AcpSchema.CreateTerminalResponse> handler) {
			Assert.notNull(handler, "Create terminal handler must not be null");
			SyncRequestHandler<AcpSchema.CreateTerminalResponse> rawHandler = params -> {
				logger.debug("createTerminal request params: {}", params);
				AcpSchema.CreateTerminalRequest request = transport.unmarshalFrom(params,
						new TypeRef<AcpSchema.CreateTerminalRequest>() {});
				return handler.apply(request);
			};
			this.requestHandlers.put(AcpSchema.METHOD_TERMINAL_CREATE, fromSync(rawHandler));
			return this;
		}

		/**
		 * Adds a typed handler for terminal output requests from the agent.
		 *
		 * <p>Example usage:
		 * <pre>{@code
		 * .terminalOutputHandler(req -> {
		 *     String output = getTerminalOutput(req.terminalId());
		 *     return new TerminalOutputResponse(output, false, null);
		 * })
		 * }</pre>
		 *
		 * @param handler The typed handler function that processes terminal output requests
		 * @return This builder instance for method chaining
		 * @throws IllegalArgumentException if handler is null
		 */
		public SyncSpec terminalOutputHandler(
				Function<AcpSchema.TerminalOutputRequest, AcpSchema.TerminalOutputResponse> handler) {
			Assert.notNull(handler, "Terminal output handler must not be null");
			SyncRequestHandler<AcpSchema.TerminalOutputResponse> rawHandler = params -> {
				logger.debug("terminalOutput request params: {}", params);
				AcpSchema.TerminalOutputRequest request = transport.unmarshalFrom(params,
						new TypeRef<AcpSchema.TerminalOutputRequest>() {});
				return handler.apply(request);
			};
			this.requestHandlers.put(AcpSchema.METHOD_TERMINAL_OUTPUT, fromSync(rawHandler));
			return this;
		}

		/**
		 * Adds a typed handler for terminal release requests from the agent.
		 *
		 * <p>Example usage:
		 * <pre>{@code
		 * .releaseTerminalHandler(req -> {
		 *     releaseTerminal(req.terminalId());
		 *     return new ReleaseTerminalResponse();
		 * })
		 * }</pre>
		 *
		 * @param handler The typed handler function that processes terminal release requests
		 * @return This builder instance for method chaining
		 * @throws IllegalArgumentException if handler is null
		 */
		public SyncSpec releaseTerminalHandler(
				Function<AcpSchema.ReleaseTerminalRequest, AcpSchema.ReleaseTerminalResponse> handler) {
			Assert.notNull(handler, "Release terminal handler must not be null");
			SyncRequestHandler<AcpSchema.ReleaseTerminalResponse> rawHandler = params -> {
				logger.debug("releaseTerminal request params: {}", params);
				AcpSchema.ReleaseTerminalRequest request = transport.unmarshalFrom(params,
						new TypeRef<AcpSchema.ReleaseTerminalRequest>() {});
				return handler.apply(request);
			};
			this.requestHandlers.put(AcpSchema.METHOD_TERMINAL_RELEASE, fromSync(rawHandler));
			return this;
		}

		/**
		 * Adds a typed handler for wait-for-terminal-exit requests from the agent.
		 *
		 * <p>Example usage:
		 * <pre>{@code
		 * .waitForTerminalExitHandler(req -> {
		 *     int exitCode = waitForExit(req.terminalId());
		 *     return new WaitForTerminalExitResponse(exitCode, null);
		 * })
		 * }</pre>
		 *
		 * @param handler The typed handler function that processes wait-for-exit requests
		 * @return This builder instance for method chaining
		 * @throws IllegalArgumentException if handler is null
		 */
		public SyncSpec waitForTerminalExitHandler(
				Function<AcpSchema.WaitForTerminalExitRequest, AcpSchema.WaitForTerminalExitResponse> handler) {
			Assert.notNull(handler, "Wait for terminal exit handler must not be null");
			SyncRequestHandler<AcpSchema.WaitForTerminalExitResponse> rawHandler = params -> {
				logger.debug("waitForTerminalExit request params: {}", params);
				AcpSchema.WaitForTerminalExitRequest request = transport.unmarshalFrom(params,
						new TypeRef<AcpSchema.WaitForTerminalExitRequest>() {});
				return handler.apply(request);
			};
			this.requestHandlers.put(AcpSchema.METHOD_TERMINAL_WAIT_FOR_EXIT, fromSync(rawHandler));
			return this;
		}

		/**
		 * Adds a typed handler for terminal kill requests from the agent.
		 *
		 * <p>Example usage:
		 * <pre>{@code
		 * .killTerminalHandler(req -> {
		 *     killProcess(req.terminalId());
		 *     return new KillTerminalCommandResponse();
		 * })
		 * }</pre>
		 *
		 * @param handler The typed handler function that processes terminal kill requests
		 * @return This builder instance for method chaining
		 * @throws IllegalArgumentException if handler is null
		 */
		public SyncSpec killTerminalHandler(
				Function<AcpSchema.KillTerminalCommandRequest, AcpSchema.KillTerminalCommandResponse> handler) {
			Assert.notNull(handler, "Kill terminal handler must not be null");
			SyncRequestHandler<AcpSchema.KillTerminalCommandResponse> rawHandler = params -> {
				logger.debug("killTerminal request params: {}", params);
				AcpSchema.KillTerminalCommandRequest request = transport.unmarshalFrom(params,
						new TypeRef<AcpSchema.KillTerminalCommandRequest>() {});
				return handler.apply(request);
			};
			this.requestHandlers.put(AcpSchema.METHOD_TERMINAL_KILL, fromSync(rawHandler));
			return this;
		}

		/**
		 * Adds a synchronous consumer to be notified when session update notifications
		 * are received from the agent. This is the preferred method for sync clients.
		 *
		 * <p>Example usage:
		 * <pre>{@code
		 * .sessionUpdateConsumer(notification -> {
		 *     if (notification.update() instanceof AgentMessageChunk msg) {
		 *         System.out.println(msg.content());
		 *     }
		 * })
		 * }</pre>
		 *
		 * @param sessionUpdateConsumer A consumer that receives session update
		 * notifications. Must not be null.
		 * @return This builder instance for method chaining
		 * @throws IllegalArgumentException if sessionUpdateConsumer is null
		 */
		public SyncSpec sessionUpdateConsumer(Consumer<AcpSchema.SessionNotification> sessionUpdateConsumer) {
			Assert.notNull(sessionUpdateConsumer, "Session update consumer must not be null");
			// Convert sync consumer to async Function
			this.sessionUpdateConsumers.add(notification -> {
				return Mono.fromRunnable(() -> sessionUpdateConsumer.accept(notification))
						.subscribeOn(SYNC_HANDLER_SCHEDULER)
						.then();
			});
			return this;
		}

		/**
		 * Adds a synchronous custom request handler for a specific method.
		 * This is the preferred method for sync clients.
		 *
		 * @param <T> The response type
		 * @param method The method name (e.g., "custom/operation")
		 * @param handler The synchronous handler function for this method
		 * @return This builder instance for method chaining
		 * @throws IllegalArgumentException if method or handler is null
		 */
		public <T> SyncSpec requestHandler(String method, SyncRequestHandler<T> handler) {
			Assert.notNull(method, "Method must not be null");
			Assert.notNull(handler, "Handler must not be null");
			this.requestHandlers.put(method, fromSync(handler));
			return this;
		}

		/**
		 * Adds a custom notification handler for a specific method. This allows handling
		 * additional agent notifications beyond session updates.
		 * @param method The method name (e.g., "custom/notification")
		 * @param handler The handler function for this method
		 * @return This builder instance for method chaining
		 * @throws IllegalArgumentException if method or handler is null
		 */
		public SyncSpec notificationHandler(String method, AcpClientSession.NotificationHandler handler) {
			Assert.notNull(method, "Method must not be null");
			Assert.notNull(handler, "Handler must not be null");
			this.notificationHandlers.put(method, handler);
			return this;
		}

		/**
		 * Creates an instance of {@link AcpSyncClient} with the provided configurations
		 * or sensible defaults.
		 * @return a new instance of {@link AcpSyncClient}
		 */
		public AcpSyncClient build() {
			// Set up session update notification handler
			if (!sessionUpdateConsumers.isEmpty()) {
				notificationHandlers.put(AcpSchema.METHOD_SESSION_UPDATE, params -> {
					AcpSchema.SessionNotification notification = transport.unmarshalFrom(params,
							new io.modelcontextprotocol.json.TypeRef<AcpSchema.SessionNotification>() {
							});
					logger.debug("Received session update for session: {}", notification.sessionId());

					// Call all registered consumers
					return Mono
						.when(sessionUpdateConsumers.stream().map(consumer -> consumer.apply(notification)).collect(Collectors.toList()));
				});
			}

			// Create session with request and notification handlers
			AcpSession session = new AcpClientSession(requestTimeout, transport, requestHandlers, notificationHandlers,
					Function.identity());

			return new AcpSyncClient(new AcpAsyncClient(session, transport, clientCapabilities));
		}

	}

}
