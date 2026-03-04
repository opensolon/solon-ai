/*
 * Copyright 2025-2025 the original author or authors.
 */

package com.agentclientprotocol.sdk.client;

import java.time.Duration;

import com.agentclientprotocol.sdk.spec.AcpSchema;
import com.agentclientprotocol.sdk.util.Assert;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A synchronous client implementation for the Agent Client Protocol (ACP) that wraps an
 * {@link AcpAsyncClient} to provide blocking operations.
 *
 * <p>
 * This client implements the ACP specification by delegating to an asynchronous client
 * and blocking on the results. Key features include:
 * <ul>
 * <li>Synchronous, blocking API for simpler integration in non-reactive applications</li>
 * <li>Initialize handshake and capability negotiation</li>
 * <li>Session creation and management</li>
 * <li>Prompt submission with streaming updates</li>
 * <li>Authentication support for agents requiring it</li>
 * </ul>
 *
 * <p>
 * The client follows the same lifecycle as its async counterpart:
 * <ol>
 * <li>Initialization - Establishes connection and negotiates protocol version</li>
 * <li>Authentication - Optional authentication step</li>
 * <li>Session Creation - Creates a new agent session with working directory</li>
 * <li>Prompt Interaction - Sends prompts and receives responses</li>
 * <li>Graceful Shutdown - Ensures clean connection termination</li>
 * </ol>
 *
 * <p>
 * This implementation implements {@link AutoCloseable} for resource cleanup and provides
 * both immediate and graceful shutdown options. All operations block until completion or
 * timeout, making it suitable for traditional synchronous programming models.
 *
 * <p>
 * Example usage: <pre>{@code
 * try (AcpSyncClient client = AcpClient.sync(transport).build()) {
 *     // Initialize
 *     AcpSchema.InitializeResponse initResponse = client.initialize(
 *         new AcpSchema.InitializeRequest(1, new AcpSchema.ClientCapabilities()));
 *
 *     // Create session
 *     AcpSchema.NewSessionResponse sessionResponse = client.newSession(
 *         new AcpSchema.NewSessionRequest("/workspace", List.of()));
 *
 *     // Send prompt
 *     AcpSchema.PromptResponse response = client.prompt(
 *         new AcpSchema.PromptRequest(sessionResponse.sessionId(),
 *             List.of(new AcpSchema.TextContent("Fix the bug"))));
 *
 *     System.out.println("Stop reason: " + response.stopReason());
 * }
 * }</pre>
 *
 * @author Mark Pollack
 * @author Christian Tzolov
 * @see AcpClient
 * @see AcpAsyncClient
 * @see AcpSchema
 */
public class AcpSyncClient implements AutoCloseable {

	private static final Logger logger = LoggerFactory.getLogger(AcpSyncClient.class);

	private static final long DEFAULT_CLOSE_TIMEOUT_MS = 10_000L;

	private final AcpAsyncClient delegate;

	/**
	 * Creates a new AcpSyncClient with the given async delegate.
	 * @param delegate the asynchronous client on top of which this synchronous client
	 * provides a blocking API
	 */
	AcpSyncClient(AcpAsyncClient delegate) {
		Assert.notNull(delegate, "Delegate must not be null");
		this.delegate = delegate;
	}

	// --------------------------
	// Lifecycle Management
	// --------------------------

	/**
	 * Closes the client connection and waits for shutdown to complete.
	 *
	 * <p>
	 * This method blocks until the connection is closed or the timeout is reached.
	 * For synchronous clients, this ensures resources are fully released when
	 * try-with-resources completes.
	 * </p>
	 */
	@Override
	public void close() {
		logger.debug("Closing ACP sync client");
		closeGracefully();
	}

	/**
	 * Gracefully closes the client connection with a default timeout.
	 * @return true if the client closed gracefully, false if it timed out
	 */
	public boolean closeGracefully() {
		try {
			logger.debug("Gracefully closing ACP sync client");
			this.delegate.closeGracefully().block(Duration.ofMillis(DEFAULT_CLOSE_TIMEOUT_MS));
		}
		catch (RuntimeException e) {
			logger.warn("Client didn't close within timeout of {} ms", DEFAULT_CLOSE_TIMEOUT_MS, e);
			return false;
		}
		return true;
	}

	// --------------------------
	// Initialization
	// --------------------------

	/**
	 * Initializes the connection with the agent. This is the first step in the ACP
	 * lifecycle and negotiates protocol version and capabilities.
	 *
	 * <p>
	 * The client sends its protocol version and capabilities, and the agent responds with
	 * its supported protocol version, authentication methods, and capabilities.
	 * </p>
	 * @param initializeRequest the initialization request containing protocol version and
	 * client capabilities
	 * @return the initialization response with agent capabilities
	 * @see AcpSchema#METHOD_INITIALIZE
	 */
	public AcpSchema.InitializeResponse initialize(AcpSchema.InitializeRequest initializeRequest) {
		return this.delegate.initialize(initializeRequest).block();
	}

	/**
	 * Initializes the ACP client with default settings.
	 *
	 * <p>
	 * Uses protocol version 1 and default client capabilities. This is a convenience
	 * method for the common case where no special capabilities need to be advertised.
	 * </p>
	 * @return the initialization response with agent capabilities
	 * @see #initialize(AcpSchema.InitializeRequest)
	 */
	public AcpSchema.InitializeResponse initialize() {
		return this.delegate.initialize().block();
	}

	/**
	 * Returns the capabilities negotiated with the agent during initialization.
	 *
	 * <p>
	 * This method returns null if {@link #initialize} has not been called yet.
	 * </p>
	 * @return the negotiated agent capabilities, or null if not initialized
	 */
	public com.agentclientprotocol.sdk.capabilities.NegotiatedCapabilities getAgentCapabilities() {
		return this.delegate.getAgentCapabilities();
	}

	// --------------------------
	// Authentication
	// --------------------------

	/**
	 * Authenticates with the agent using the specified authentication method.
	 *
	 * <p>
	 * Authentication is optional and depends on the agent's configuration. The
	 * authentication methods available are returned in the initialize response.
	 * </p>
	 * @param authenticateRequest the authentication request specifying the auth method
	 * and credentials
	 * @return the authentication response
	 * @see AcpSchema#METHOD_AUTHENTICATE
	 */
	public AcpSchema.AuthenticateResponse authenticate(AcpSchema.AuthenticateRequest authenticateRequest) {
		return this.delegate.authenticate(authenticateRequest).block();
	}

	// --------------------------
	// Session Management
	// --------------------------

	/**
	 * Creates a new agent session with the specified working directory.
	 *
	 * <p>
	 * A session represents a conversation context with the agent. All prompts within a
	 * session share the same working directory and conversation history.
	 * </p>
	 * @param newSessionRequest the session creation request with working directory and
	 * initial context
	 * @return the session response containing the session ID
	 * @see AcpSchema#METHOD_SESSION_NEW
	 */
	public AcpSchema.NewSessionResponse newSession(AcpSchema.NewSessionRequest newSessionRequest) {
		return this.delegate.newSession(newSessionRequest).block();
	}

	/**
	 * Loads an existing agent session by ID.
	 *
	 * <p>
	 * This allows resuming a previous conversation with the agent, maintaining the full
	 * history and context.
	 * </p>
	 * @param loadSessionRequest the session load request with session ID
	 * @return the load response confirming the session was loaded
	 * @see AcpSchema#METHOD_SESSION_LOAD
	 */
	public AcpSchema.LoadSessionResponse loadSession(AcpSchema.LoadSessionRequest loadSessionRequest) {
		return this.delegate.loadSession(loadSessionRequest).block();
	}

	/**
	 * Sets the operational mode for a session (e.g., "code", "plan", "review").
	 *
	 * <p>
	 * Different modes may change how the agent processes prompts and what capabilities it
	 * exposes.
	 * </p>
	 * @param setModeRequest the set mode request with session ID and desired mode
	 * @return the response confirming the mode change
	 * @see AcpSchema#METHOD_SESSION_SET_MODE
	 */
	public AcpSchema.SetSessionModeResponse setSessionMode(AcpSchema.SetSessionModeRequest setModeRequest) {
		return this.delegate.setSessionMode(setModeRequest).block();
	}

	/**
	 * Sets the AI model for the specified session.
	 * <p>
	 * This allows changing which AI model is used for subsequent prompts in the session.
	 * </p>
	 * @param setModelRequest the set model request with session ID and desired model
	 * @return the response confirming the model change
	 * @see AcpSchema#METHOD_SESSION_SET_MODEL
	 */
	public AcpSchema.SetSessionModelResponse setSessionModel(AcpSchema.SetSessionModelRequest setModelRequest) {
		return this.delegate.setSessionModel(setModelRequest).block();
	}

	// --------------------------
	// Prompt Interaction
	// --------------------------

	/**
	 * Sends a prompt to the agent within a session.
	 *
	 * <p>
	 * The prompt can contain text, images, or other content types. The agent processes
	 * the prompt and may send streaming updates via session/update notifications before
	 * returning the final response.
	 * </p>
	 * @param promptRequest the prompt request with session ID and content
	 * @return the prompt response with stop reason
	 * @see AcpSchema#METHOD_SESSION_PROMPT
	 */
	public AcpSchema.PromptResponse prompt(AcpSchema.PromptRequest promptRequest) {
		return this.delegate.prompt(promptRequest).block();
	}

	/**
	 * Cancels ongoing operations for a session.
	 *
	 * <p>
	 * This sends a notification to the agent to stop any in-progress work for the
	 * specified session. Note that this is a notification (fire-and-forget), not a
	 * request.
	 * </p>
	 * @param cancelNotification the cancel notification with session ID and optional
	 * reason
	 * @see AcpSchema#METHOD_SESSION_CANCEL
	 */
	public void cancel(AcpSchema.CancelNotification cancelNotification) {
		this.delegate.cancel(cancelNotification).block();
	}

}
