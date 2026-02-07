/*
 * Copyright 2025-2025 the original author or authors.
 */

package com.agentclientprotocol.sdk.agent;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import java.util.concurrent.Executors;

import com.agentclientprotocol.sdk.spec.AcpAgentSession;
import com.agentclientprotocol.sdk.spec.AcpAgentTransport;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import com.agentclientprotocol.sdk.util.Assert;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

/**
 * Factory class for creating Agent Client Protocol (ACP) agents. ACP agents
 * provide autonomous coding capabilities to clients (such as code editors)
 * through a standardized interface.
 *
 * <p>
 * This class serves as the main entry point for implementing ACP-compliant agents,
 * implementing the agent-side of the ACP specification. The protocol follows a
 * client-agent architecture where:
 * <ul>
 * <li>The agent (this implementation) responds to client requests and sends updates</li>
 * <li>The client connects to the agent and sends prompts</li>
 * <li>Communication occurs through a transport layer (e.g., stdio) using JSON-RPC 2.0</li>
 * </ul>
 *
 * <p>
 * The class provides factory methods to create either:
 * <ul>
 * <li>{@link AcpAsyncAgent} for non-blocking operations with Mono/Flux responses</li>
 * <li>{@link AcpSyncAgent} for blocking operations with direct responses</li>
 * </ul>
 *
 * <p>
 * Example of creating a basic asynchronous agent:
 *
 * <pre>{@code
 * AcpAsyncAgent agent = AcpAgent.async(transport)
 *     .requestTimeout(Duration.ofSeconds(30))
 *     .agentInfo(new AcpSchema.AgentCapabilities(true, null, null))
 *     .initializeHandler(request -> {
 *         return Mono.just(new AcpSchema.InitializeResponse(1,
 *             new AcpSchema.AgentCapabilities(), List.of()));
 *     })
 *     .newSessionHandler(request -> {
 *         return Mono.just(new AcpSchema.NewSessionResponse(
 *             "session-1", null, null));
 *     })
 *     .promptHandler((request, updater) -> {
 *         updater.sendUpdate(new AcpSchema.AgentMessageChunk(
 *             "agent_message_chunk",
 *             new AcpSchema.TextContent("Working on it...")));
 *         return Mono.just(new AcpSchema.PromptResponse(AcpSchema.StopReason.END_TURN));
 *     })
 *     .build();
 *
 * agent.start().block();
 * }</pre>
 *
 * <p>
 * The agent supports:
 * <ul>
 * <li>Protocol version negotiation and capability exchange</li>
 * <li>Optional authentication with various methods</li>
 * <li>Session creation and management (including loadSession)</li>
 * <li>Prompt processing with streaming session updates</li>
 * <li>File system requests to client (read/write)</li>
 * <li>Permission requests for sensitive operations</li>
 * <li>Terminal operations for command execution</li>
 * </ul>
 *
 * @author Mark Pollack
 * @see AcpAsyncAgent
 * @see AcpAgentTransport
 */
public interface AcpAgent {

	Logger logger = LoggerFactory.getLogger(AcpAgent.class);

	/**
	 * Default request timeout duration.
	 */
	Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(60);

	/**
	 * Library-owned scheduler for executing synchronous handlers.
	 * Uses daemon threads with descriptive names to prevent JVM hang on exit.
	 * This follows the best practice of never using global Schedulers.boundedElastic().
	 */
	Scheduler SYNC_HANDLER_SCHEDULER = Schedulers.fromExecutorService(
			Executors.newCachedThreadPool(r -> {
				Thread t = new Thread(r, "acp-agent-sync-handler");
				t.setDaemon(true);
				return t;
			}), "acp-agent-sync-handler");

	/**
	 * Start building a synchronous ACP agent with the specified transport layer.
	 * The synchronous agent provides blocking operations for simpler implementations.
	 * @param transport The transport layer to use for communication
	 * @return A builder for configuring the synchronous agent
	 */
	static SyncAgentBuilder sync(AcpAgentTransport transport) {
		return new SyncAgentBuilder(transport);
	}

	/**
	 * Start building an asynchronous ACP agent with the specified transport layer.
	 * The asynchronous agent provides non-blocking operations with Mono/Flux responses.
	 * @param transport The transport layer to use for communication
	 * @return A builder for configuring the asynchronous agent
	 */
	static AsyncAgentBuilder async(AcpAgentTransport transport) {
		return new AsyncAgentBuilder(transport);
	}

	/**
	 * Functional interface for handling initialize requests.
	 */
	@FunctionalInterface
	interface InitializeHandler {

		Mono<AcpSchema.InitializeResponse> handle(AcpSchema.InitializeRequest request);

	}

	/**
	 * Functional interface for handling authenticate requests.
	 */
	@FunctionalInterface
	interface AuthenticateHandler {

		Mono<AcpSchema.AuthenticateResponse> handle(AcpSchema.AuthenticateRequest request);

	}

	/**
	 * Functional interface for handling new session requests.
	 */
	@FunctionalInterface
	interface NewSessionHandler {

		Mono<AcpSchema.NewSessionResponse> handle(AcpSchema.NewSessionRequest request);

	}

	/**
	 * Functional interface for handling load session requests.
	 */
	@FunctionalInterface
	interface LoadSessionHandler {

		Mono<AcpSchema.LoadSessionResponse> handle(AcpSchema.LoadSessionRequest request);

	}

	/**
	 * Functional interface for handling prompt requests with full agent context.
	 *
	 * <p>
	 * The handler receives a {@link PromptContext} that provides access to all agent
	 * capabilities including file operations, permission requests, terminal operations,
	 * and session updates.
	 *
	 * <p>Example usage:
	 * <pre>{@code
	 * AcpAgent.async(transport)
	 *     .promptHandler((request, context) -> {
	 *         // Read a file
	 *         var file = context.readTextFile(new ReadTextFileRequest(...)).block();
	 *
	 *         // Send progress update
	 *         context.sendUpdate(sessionId, new AgentThoughtChunk(...));
	 *
	 *         return Mono.just(new PromptResponse(StopReason.END_TURN));
	 *     })
	 *     .build();
	 * }</pre>
	 */
	@FunctionalInterface
	interface PromptHandler {

		/**
		 * Handles a prompt request with full access to agent capabilities.
		 * @param request The prompt request
		 * @param context Context providing all agent capabilities (file ops, permissions, updates, etc.)
		 * @return A Mono containing the prompt response
		 */
		Mono<AcpSchema.PromptResponse> handle(AcpSchema.PromptRequest request, PromptContext context);

	}

	/**
	 * Functional interface for handling set session mode requests.
	 */
	@FunctionalInterface
	interface SetSessionModeHandler {

		Mono<AcpSchema.SetSessionModeResponse> handle(AcpSchema.SetSessionModeRequest request);

	}

	/**
	 * Functional interface for handling set session model requests.
	 */
	@FunctionalInterface
	interface SetSessionModelHandler {

		Mono<AcpSchema.SetSessionModelResponse> handle(AcpSchema.SetSessionModelRequest request);

	}

	/**
	 * Functional interface for handling cancel notifications.
	 */
	@FunctionalInterface
	interface CancelHandler {

		Mono<Void> handle(AcpSchema.CancelNotification notification);

	}

	// ========================================================================
	// Synchronous Handler Interfaces (for SyncAgentBuilder)
	// ========================================================================

	/**
	 * Synchronous functional interface for handling initialize requests.
	 * Returns a plain value instead of Mono for use with sync agents.
	 */
	@FunctionalInterface
	interface SyncInitializeHandler {

		AcpSchema.InitializeResponse handle(AcpSchema.InitializeRequest request);

	}

	/**
	 * Synchronous functional interface for handling authenticate requests.
	 * Returns a plain value instead of Mono for use with sync agents.
	 */
	@FunctionalInterface
	interface SyncAuthenticateHandler {

		AcpSchema.AuthenticateResponse handle(AcpSchema.AuthenticateRequest request);

	}

	/**
	 * Synchronous functional interface for handling new session requests.
	 * Returns a plain value instead of Mono for use with sync agents.
	 */
	@FunctionalInterface
	interface SyncNewSessionHandler {

		AcpSchema.NewSessionResponse handle(AcpSchema.NewSessionRequest request);

	}

	/**
	 * Synchronous functional interface for handling load session requests.
	 * Returns a plain value instead of Mono for use with sync agents.
	 */
	@FunctionalInterface
	interface SyncLoadSessionHandler {

		AcpSchema.LoadSessionResponse handle(AcpSchema.LoadSessionRequest request);

	}

	/**
	 * Synchronous functional interface for handling prompt requests with full agent context.
	 *
	 * <p>
	 * The handler receives a {@link SyncPromptContext} that provides blocking access to all
	 * agent capabilities including file operations, permission requests, terminal operations,
	 * and session updates.
	 *
	 * <p>Example usage:
	 * <pre>{@code
	 * AcpAgent.sync(transport)
	 *     .promptHandler((request, context) -> {
	 *         // Read a file (blocks)
	 *         var file = context.readTextFile(new ReadTextFileRequest(...));
	 *
	 *         // Send progress update (blocks)
	 *         context.sendUpdate(sessionId, new AgentThoughtChunk(...));
	 *
	 *         return new PromptResponse(StopReason.END_TURN);
	 *     })
	 *     .build();
	 * }</pre>
	 */
	@FunctionalInterface
	interface SyncPromptHandler {

		/**
		 * Handles a prompt request with full access to agent capabilities.
		 * @param request The prompt request
		 * @param context Context providing blocking access to all agent capabilities
		 * @return The prompt response
		 */
		AcpSchema.PromptResponse handle(AcpSchema.PromptRequest request, SyncPromptContext context);

	}

	/**
	 * Synchronous functional interface for handling set session mode requests.
	 * Returns a plain value instead of Mono for use with sync agents.
	 */
	@FunctionalInterface
	interface SyncSetSessionModeHandler {

		AcpSchema.SetSessionModeResponse handle(AcpSchema.SetSessionModeRequest request);

	}

	/**
	 * Synchronous functional interface for handling set session model requests.
	 * Returns a plain value instead of Mono for use with sync agents.
	 */
	@FunctionalInterface
	interface SyncSetSessionModelHandler {

		AcpSchema.SetSessionModelResponse handle(AcpSchema.SetSessionModelRequest request);

	}

	/**
	 * Synchronous functional interface for handling cancel notifications.
	 * Returns void instead of Mono for use with sync agents.
	 */
	@FunctionalInterface
	interface SyncCancelHandler {

		void handle(AcpSchema.CancelNotification notification);

	}

	/**
	 * Builder for creating asynchronous ACP agents.
	 */
	class AsyncAgentBuilder {

		private final AcpAgentTransport transport;

		private Duration requestTimeout = DEFAULT_REQUEST_TIMEOUT;

		private InitializeHandler initializeHandler;

		private AuthenticateHandler authenticateHandler;

		private NewSessionHandler newSessionHandler;

		private LoadSessionHandler loadSessionHandler;

		private PromptHandler promptHandler;

		private SetSessionModeHandler setSessionModeHandler;

		private SetSessionModelHandler setSessionModelHandler;

		private CancelHandler cancelHandler;

		AsyncAgentBuilder(AcpAgentTransport transport) {
			Assert.notNull(transport, "Transport must not be null");
			this.transport = transport;
		}

		/**
		 * Sets the timeout for requests sent to the client.
		 * @param timeout The request timeout duration
		 * @return This builder for chaining
		 */
		public AsyncAgentBuilder requestTimeout(Duration timeout) {
			Assert.notNull(timeout, "Timeout must not be null");
			this.requestTimeout = timeout;
			return this;
		}

		/**
		 * Sets the handler for initialize requests.
		 * @param handler The initialize handler
		 * @return This builder for chaining
		 */
		public AsyncAgentBuilder initializeHandler(InitializeHandler handler) {
			this.initializeHandler = handler;
			return this;
		}

		/**
		 * Sets the handler for authenticate requests.
		 * @param handler The authenticate handler
		 * @return This builder for chaining
		 */
		public AsyncAgentBuilder authenticateHandler(AuthenticateHandler handler) {
			this.authenticateHandler = handler;
			return this;
		}

		/**
		 * Sets the handler for new session requests.
		 * @param handler The new session handler
		 * @return This builder for chaining
		 */
		public AsyncAgentBuilder newSessionHandler(NewSessionHandler handler) {
			this.newSessionHandler = handler;
			return this;
		}

		/**
		 * Sets the handler for load session requests.
		 * @param handler The load session handler
		 * @return This builder for chaining
		 */
		public AsyncAgentBuilder loadSessionHandler(LoadSessionHandler handler) {
			this.loadSessionHandler = handler;
			return this;
		}

		/**
		 * Sets the handler for prompt requests.
		 * @param handler The prompt handler
		 * @return This builder for chaining
		 */
		public AsyncAgentBuilder promptHandler(PromptHandler handler) {
			this.promptHandler = handler;
			return this;
		}

		/**
		 * Sets the handler for set session mode requests.
		 * @param handler The set session mode handler
		 * @return This builder for chaining
		 */
		public AsyncAgentBuilder setSessionModeHandler(SetSessionModeHandler handler) {
			this.setSessionModeHandler = handler;
			return this;
		}

		/**
		 * Sets the handler for set session model requests.
		 * @param handler The set session model handler
		 * @return This builder for chaining
		 */
		public AsyncAgentBuilder setSessionModelHandler(SetSessionModelHandler handler) {
			this.setSessionModelHandler = handler;
			return this;
		}

		/**
		 * Sets the handler for cancel notifications.
		 * @param handler The cancel handler
		 * @return This builder for chaining
		 */
		public AsyncAgentBuilder cancelHandler(CancelHandler handler) {
			this.cancelHandler = handler;
			return this;
		}

		/**
		 * Builds the asynchronous ACP agent.
		 * @return A new AcpAsyncAgent instance
		 */
		public AcpAsyncAgent build() {
			return new DefaultAcpAsyncAgent(transport, requestTimeout, initializeHandler, authenticateHandler,
					newSessionHandler, loadSessionHandler, promptHandler, setSessionModeHandler, setSessionModelHandler,
					cancelHandler);
		}

	}

	/**
	 * Builder for creating synchronous ACP agents.
	 * <p>
	 * This builder accepts synchronous handler interfaces that return plain values
	 * instead of Mono. Internally, handlers are converted to async handlers using
	 * the MCP SDK pattern (Mono.fromCallable + boundedElastic scheduler).
	 * </p>
	 *
	 * <p>Example usage:</p>
	 * <pre>{@code
	 * AcpSyncAgent agent = AcpAgent.sync(transport)
	 *     .initializeHandler(req -> new InitializeResponse(1, capabilities, List.of()))
	 *     .newSessionHandler(req -> new NewSessionResponse(sessionId, null, null))
	 *     .promptHandler((req, updater) -> {
	 *         updater.sendUpdate(sessionId, thought);  // blocks, void return
	 *         updater.sendUpdate(sessionId, message);  // blocks, void return
	 *         return new PromptResponse(StopReason.END_TURN);  // plain return
	 *     })
	 *     .build();
	 * }</pre>
	 */
	class SyncAgentBuilder {

		private final AsyncAgentBuilder asyncBuilder;

		SyncAgentBuilder(AcpAgentTransport transport) {
			this.asyncBuilder = new AsyncAgentBuilder(transport);
		}

		/**
		 * Sets the timeout for requests sent to the client.
		 * @param timeout The request timeout duration
		 * @return This builder for chaining
		 */
		public SyncAgentBuilder requestTimeout(Duration timeout) {
			asyncBuilder.requestTimeout(timeout);
			return this;
		}

		/**
		 * Sets the synchronous handler for initialize requests.
		 * @param handler The sync initialize handler (returns plain value)
		 * @return This builder for chaining
		 */
		public SyncAgentBuilder initializeHandler(SyncInitializeHandler handler) {
			asyncBuilder.initializeHandler(fromSync(handler));
			return this;
		}

		/**
		 * Sets the synchronous handler for authenticate requests.
		 * @param handler The sync authenticate handler (returns plain value)
		 * @return This builder for chaining
		 */
		public SyncAgentBuilder authenticateHandler(SyncAuthenticateHandler handler) {
			asyncBuilder.authenticateHandler(fromSync(handler));
			return this;
		}

		/**
		 * Sets the synchronous handler for new session requests.
		 * @param handler The sync new session handler (returns plain value)
		 * @return This builder for chaining
		 */
		public SyncAgentBuilder newSessionHandler(SyncNewSessionHandler handler) {
			asyncBuilder.newSessionHandler(fromSync(handler));
			return this;
		}

		/**
		 * Sets the synchronous handler for load session requests.
		 * @param handler The sync load session handler (returns plain value)
		 * @return This builder for chaining
		 */
		public SyncAgentBuilder loadSessionHandler(SyncLoadSessionHandler handler) {
			asyncBuilder.loadSessionHandler(fromSync(handler));
			return this;
		}

		/**
		 * Sets the synchronous handler for prompt requests.
		 * @param handler The sync prompt handler (returns plain value, receives SyncPromptContext)
		 * @return This builder for chaining
		 */
		public SyncAgentBuilder promptHandler(SyncPromptHandler handler) {
			asyncBuilder.promptHandler(fromSync(handler));
			return this;
		}

		/**
		 * Sets the synchronous handler for set session mode requests.
		 * @param handler The sync set session mode handler (returns plain value)
		 * @return This builder for chaining
		 */
		public SyncAgentBuilder setSessionModeHandler(SyncSetSessionModeHandler handler) {
			asyncBuilder.setSessionModeHandler(fromSync(handler));
			return this;
		}

		/**
		 * Sets the synchronous handler for set session model requests.
		 * @param handler The sync set session model handler (returns plain value)
		 * @return This builder for chaining
		 */
		public SyncAgentBuilder setSessionModelHandler(SyncSetSessionModelHandler handler) {
			asyncBuilder.setSessionModelHandler(fromSync(handler));
			return this;
		}

		/**
		 * Sets the synchronous handler for cancel notifications.
		 * @param handler The sync cancel handler (returns void)
		 * @return This builder for chaining
		 */
		public SyncAgentBuilder cancelHandler(SyncCancelHandler handler) {
			asyncBuilder.cancelHandler(fromSync(handler));
			return this;
		}

		/**
		 * Builds the synchronous ACP agent.
		 * @return A new AcpSyncAgent instance
		 */
		public AcpSyncAgent build() {
			return new AcpSyncAgent(asyncBuilder.build());
		}

		// ========================================================================
		// fromSync() conversion methods - following MCP SDK pattern
		// Wraps sync handlers in Mono.fromCallable() with library-owned daemon scheduler
		// ========================================================================

		private static InitializeHandler fromSync(SyncInitializeHandler syncHandler) {
			if (syncHandler == null) {
				return null;
			}
			return request -> Mono.fromCallable(() -> syncHandler.handle(request))
				.subscribeOn(SYNC_HANDLER_SCHEDULER);
		}

		private static AuthenticateHandler fromSync(SyncAuthenticateHandler syncHandler) {
			if (syncHandler == null) {
				return null;
			}
			return request -> Mono.fromCallable(() -> syncHandler.handle(request))
				.subscribeOn(SYNC_HANDLER_SCHEDULER);
		}

		private static NewSessionHandler fromSync(SyncNewSessionHandler syncHandler) {
			if (syncHandler == null) {
				return null;
			}
			return request -> Mono.fromCallable(() -> syncHandler.handle(request))
				.subscribeOn(SYNC_HANDLER_SCHEDULER);
		}

		private static LoadSessionHandler fromSync(SyncLoadSessionHandler syncHandler) {
			if (syncHandler == null) {
				return null;
			}
			return request -> Mono.fromCallable(() -> syncHandler.handle(request))
				.subscribeOn(SYNC_HANDLER_SCHEDULER);
		}

		private static PromptHandler fromSync(SyncPromptHandler syncHandler) {
			if (syncHandler == null) {
				return null;
			}
			return (request, asyncContext) -> Mono.fromCallable(() -> {
				// Create a blocking wrapper around the async PromptContext
				SyncPromptContext syncContext = new DefaultSyncPromptContext(asyncContext);
				return syncHandler.handle(request, syncContext);
			}).subscribeOn(SYNC_HANDLER_SCHEDULER);
		}

		private static SetSessionModeHandler fromSync(SyncSetSessionModeHandler syncHandler) {
			if (syncHandler == null) {
				return null;
			}
			return request -> Mono.fromCallable(() -> syncHandler.handle(request))
				.subscribeOn(SYNC_HANDLER_SCHEDULER);
		}

		private static SetSessionModelHandler fromSync(SyncSetSessionModelHandler syncHandler) {
			if (syncHandler == null) {
				return null;
			}
			return request -> Mono.fromCallable(() -> syncHandler.handle(request))
				.subscribeOn(SYNC_HANDLER_SCHEDULER);
		}

		private static CancelHandler fromSync(SyncCancelHandler syncHandler) {
			if (syncHandler == null) {
				return null;
			}
			return notification -> Mono.<Void>fromRunnable(() -> syncHandler.handle(notification))
				.subscribeOn(SYNC_HANDLER_SCHEDULER);
		}

	}

}
