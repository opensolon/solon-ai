/*
 * Copyright 2025-2025 the original author or authors.
 */

package com.agentclientprotocol.sdk.client;

import java.util.concurrent.atomic.AtomicReference;

import com.agentclientprotocol.sdk.capabilities.NegotiatedCapabilities;
import com.agentclientprotocol.sdk.spec.AcpClientTransport;
import com.agentclientprotocol.sdk.json.TypeRef;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import com.agentclientprotocol.sdk.spec.AcpSession;
import com.agentclientprotocol.sdk.util.Assert;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * The Agent Client Protocol (ACP) async client implementation that provides asynchronous
 * communication with ACP-compliant agents using Project Reactor's Mono type.
 *
 * <p>
 * This client implements the ACP specification, enabling applications to interact with
 * autonomous coding agents through a standardized interface. Key features include:
 * <ul>
 * <li>Asynchronous communication using reactive programming patterns</li>
 * <li>Initialize handshake and capability negotiation</li>
 * <li>Session creation and management</li>
 * <li>Prompt submission with streaming updates</li>
 * <li>Authentication support for agents requiring it</li>
 * <li>Cancel operations for long-running tasks</li>
 * </ul>
 *
 * <p>
 * The client follows a lifecycle:
 * <ol>
 * <li>Initialization - Establishes connection and negotiates protocol version</li>
 * <li>Authentication - Optional authentication step</li>
 * <li>Session Creation - Creates a new agent session with working directory</li>
 * <li>Prompt Interaction - Sends prompts and receives responses</li>
 * <li>Graceful Shutdown - Ensures clean connection termination</li>
 * </ol>
 *
 * <p>
 * This implementation uses Project Reactor for non-blocking operations, making it
 * suitable for high-throughput scenarios and reactive applications. All operations return
 * Mono types that can be composed into reactive pipelines.
 *
 * <p>
 * Example usage: <pre>{@code
 * // Create transport
 * AgentParameters params = AgentParameters.builder("gemini")
 *     .arg("--experimental-acp")
 *     .build();
 * StdioAcpClientTransport transport = new StdioAcpClientTransport(params, AcpJsonMapper.createDefault());
 *
 * // Create client
 * AcpAsyncClient client = AcpClient.async(transport)
 *     .requestTimeout(Duration.ofSeconds(30))
 *     .build();
 *
 * // Initialize
 * AcpSchema.InitializeResponse initResponse = client
 *     .initialize(new AcpSchema.InitializeRequest(1, new AcpSchema.ClientCapabilities()))
 *     .block();
 *
 * // Create session and interact
 * String sessionId = client
 *     .newSession(new AcpSchema.NewSessionRequest("/workspace", List.of()))
 *     .map(AcpSchema.NewSessionResponse::sessionId)
 *     .block();
 *
 * AcpSchema.PromptResponse response = client
 *     .prompt(new AcpSchema.PromptRequest(sessionId, List.of(new AcpSchema.TextContent("Fix the bug"))))
 *     .block();
 *
 * client.closeGracefully().block();
 * }</pre>
 *
 * @author Mark Pollack
 * @author Christian Tzolov
 * @see AcpSession
 * @see AcpSchema
 */
public class AcpAsyncClient {

	private static final Logger logger = LoggerFactory.getLogger(AcpAsyncClient.class);

	private static final TypeRef<AcpSchema.InitializeResponse> INITIALIZE_RESPONSE_TYPE_REF = new TypeRef<AcpSchema.InitializeResponse>() {
	};

	private static final TypeRef<AcpSchema.AuthenticateResponse> AUTHENTICATE_RESPONSE_TYPE_REF = new TypeRef<AcpSchema.AuthenticateResponse>() {
	};

	private static final TypeRef<AcpSchema.NewSessionResponse> NEW_SESSION_RESPONSE_TYPE_REF = new TypeRef<AcpSchema.NewSessionResponse>() {
	};

	private static final TypeRef<AcpSchema.LoadSessionResponse> LOAD_SESSION_RESPONSE_TYPE_REF = new TypeRef<AcpSchema.LoadSessionResponse>() {
	};

	private static final TypeRef<AcpSchema.SetSessionModeResponse> SET_SESSION_MODE_RESPONSE_TYPE_REF = new TypeRef<AcpSchema.SetSessionModeResponse>() {
	};

	@SuppressWarnings("removal")
	private static final TypeRef<AcpSchema.SetSessionModelResponse> SET_SESSION_MODEL_RESPONSE_TYPE_REF = new TypeRef<AcpSchema.SetSessionModelResponse>() {
	};

	private static final TypeRef<AcpSchema.ListSessionsResponse> LIST_SESSIONS_RESPONSE_TYPE_REF = new TypeRef<AcpSchema.ListSessionsResponse>() {
	};

	private static final TypeRef<AcpSchema.CloseSessionResponse> CLOSE_SESSION_RESPONSE_TYPE_REF = new TypeRef<AcpSchema.CloseSessionResponse>() {
	};

	private static final TypeRef<AcpSchema.DeleteSessionResponse> DELETE_SESSION_RESPONSE_TYPE_REF = new TypeRef<AcpSchema.DeleteSessionResponse>() {
	};

	private static final TypeRef<AcpSchema.LogoutResponse> LOGOUT_RESPONSE_TYPE_REF = new TypeRef<AcpSchema.LogoutResponse>() {
	};

	private static final TypeRef<AcpSchema.ResumeSessionResponse> RESUME_SESSION_RESPONSE_TYPE_REF = new TypeRef<AcpSchema.ResumeSessionResponse>() {
	};

	private static final TypeRef<AcpSchema.ForkSessionResponse> FORK_SESSION_RESPONSE_TYPE_REF = new TypeRef<AcpSchema.ForkSessionResponse>() {
	};

	private static final TypeRef<AcpSchema.SetSessionConfigOptionResponse> SET_SESSION_CONFIG_OPTION_RESPONSE_TYPE_REF = new TypeRef<AcpSchema.SetSessionConfigOptionResponse>() {
	};

	private static final TypeRef<AcpSchema.ListProvidersResponse> LIST_PROVIDERS_RESPONSE_TYPE_REF = new TypeRef<AcpSchema.ListProvidersResponse>() {
	};

	private static final TypeRef<AcpSchema.SetProviderResponse> SET_PROVIDER_RESPONSE_TYPE_REF = new TypeRef<AcpSchema.SetProviderResponse>() {
	};

	private static final TypeRef<AcpSchema.DisableProviderResponse> DISABLE_PROVIDER_RESPONSE_TYPE_REF = new TypeRef<AcpSchema.DisableProviderResponse>() {
	};

	private static final TypeRef<AcpSchema.PromptResponse> PROMPT_RESPONSE_TYPE_REF = new TypeRef<AcpSchema.PromptResponse>() {
	};

	private static final TypeRef<Void> VOID_TYPE_REF = new TypeRef<Void>() {
	};

	/**
	 * The underlying ACP session that handles request/response communication.
	 */
	private final AcpSession session;

	/**
	 * The transport layer for this client.
	 */
	private final AcpClientTransport transport;

	/**
	 * Client capabilities configured via the builder. Used by no-arg initialize().
	 */
	private final AcpSchema.ClientCapabilities clientCapabilities;

	/**
	 * Capabilities negotiated with the agent during initialization.
	 */
	private final AtomicReference<NegotiatedCapabilities> agentCapabilities = new AtomicReference<>();

	/**
	 * Creates a new AcpAsyncClient with the given session and transport. Uses default
	 * client capabilities.
	 * @param session the ACP session for communication
	 * @param transport the transport layer for this client
	 */
	AcpAsyncClient(AcpSession session, AcpClientTransport transport) {
		this(session, transport, null);
	}

	/**
	 * Creates a new AcpAsyncClient with the given session, transport, and client
	 * capabilities.
	 * @param session the ACP session for communication
	 * @param transport the transport layer for this client
	 * @param clientCapabilities the client capabilities to use during initialization (may
	 * be null for defaults)
	 */
	AcpAsyncClient(AcpSession session, AcpClientTransport transport, AcpSchema.ClientCapabilities clientCapabilities) {
		Assert.notNull(session, "Session must not be null");
		Assert.notNull(transport, "Transport must not be null");
		this.session = session;
		this.transport = transport;
		this.clientCapabilities = clientCapabilities != null ? clientCapabilities : new AcpSchema.ClientCapabilities();
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
	 *
	 * <p>
	 * After initialization, the agent's capabilities can be accessed via
	 * {@link #getAgentCapabilities()}.
	 * </p>
	 * @param initializeRequest the initialization request containing protocol version and
	 * client capabilities
	 * @return a Mono emitting the initialization response with agent capabilities
	 * @see AcpSchema#METHOD_INITIALIZE
	 * @see #getAgentCapabilities()
	 */
	public Mono<AcpSchema.InitializeResponse> initialize(AcpSchema.InitializeRequest initializeRequest) {
		Assert.notNull(initializeRequest, "Initialize request must not be null");
		logger.debug("Initializing ACP client with protocol version: {}", initializeRequest.protocolVersion());
		return session.sendRequest(AcpSchema.METHOD_INITIALIZE, initializeRequest, INITIALIZE_RESPONSE_TYPE_REF)
			.doOnNext(response -> {
				// Store the negotiated agent capabilities
				NegotiatedCapabilities caps = NegotiatedCapabilities.fromAgent(response.agentCapabilities());
				agentCapabilities.set(caps);
				logger.debug("Negotiated agent capabilities: {}", caps);
			});
	}

	/**
	 * Initializes the ACP client with default settings.
	 *
	 * <p>
	 * Uses protocol version 1 and default client capabilities. This is a convenience
	 * method for the common case where no special capabilities need to be advertised.
	 * </p>
	 * @return a Mono emitting the initialization response with agent capabilities
	 * @see #initialize(AcpSchema.InitializeRequest)
	 */
	public Mono<AcpSchema.InitializeResponse> initialize() {
		return initialize(new AcpSchema.InitializeRequest(1, this.clientCapabilities));
	}

	/**
	 * Returns the capabilities negotiated with the agent during initialization.
	 *
	 * <p>
	 * This method returns null if {@link #initialize} has not been called yet.
	 * </p>
	 * @return the negotiated agent capabilities, or null if not initialized
	 */
	public NegotiatedCapabilities getAgentCapabilities() {
		return agentCapabilities.get();
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
	 * @return a Mono emitting the authentication response
	 * @see AcpSchema#METHOD_AUTHENTICATE
	 */
	public Mono<AcpSchema.AuthenticateResponse> authenticate(AcpSchema.AuthenticateRequest authenticateRequest) {
		Assert.notNull(authenticateRequest, "Authenticate request must not be null");
		logger.debug("Authenticating with method: {}", authenticateRequest.methodId());
		return session.sendRequest(AcpSchema.METHOD_AUTHENTICATE, authenticateRequest, AUTHENTICATE_RESPONSE_TYPE_REF);
	}

	/**
	 * Logs out of the agent, clearing any stored credentials.
	 *
	 * <p>
	 * Terminates the current authenticated session. After logout, the client must
	 * authenticate again before performing operations that require authentication.
	 * </p>
	 * @param logoutRequest the logout request
	 * @return a Mono emitting the logout response
	 * @see AcpSchema#METHOD_LOGOUT
	 */
	public Mono<AcpSchema.LogoutResponse> logout(AcpSchema.LogoutRequest logoutRequest) {
		Assert.notNull(logoutRequest, "Logout request must not be null");
		logger.debug("Logging out");
		return session.sendRequest(AcpSchema.METHOD_LOGOUT, logoutRequest, LOGOUT_RESPONSE_TYPE_REF);
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
	 * @return a Mono emitting the session response containing the session ID
	 * @see AcpSchema#METHOD_SESSION_NEW
	 */
	public Mono<AcpSchema.NewSessionResponse> newSession(AcpSchema.NewSessionRequest newSessionRequest) {
		Assert.notNull(newSessionRequest, "New session request must not be null");
		logger.debug("Creating new session with cwd: {}", newSessionRequest.cwd());
		return session.sendRequest(AcpSchema.METHOD_SESSION_NEW, newSessionRequest, NEW_SESSION_RESPONSE_TYPE_REF);
	}

	/**
	 * Loads an existing agent session by ID.
	 *
	 * <p>
	 * This allows resuming a previous conversation with the agent, maintaining the full
	 * history and context.
	 * </p>
	 * @param loadSessionRequest the session load request with session ID
	 * @return a Mono emitting the load response confirming the session was loaded
	 * @see AcpSchema#METHOD_SESSION_LOAD
	 */
	public Mono<AcpSchema.LoadSessionResponse> loadSession(AcpSchema.LoadSessionRequest loadSessionRequest) {
		Assert.notNull(loadSessionRequest, "Load session request must not be null");
		logger.debug("Loading session: {}", loadSessionRequest.sessionId());
		return session.sendRequest(AcpSchema.METHOD_SESSION_LOAD, loadSessionRequest, LOAD_SESSION_RESPONSE_TYPE_REF);
	}

	/**
	 * Sets the operational mode for a session (e.g., "code", "plan", "review").
	 *
	 * <p>
	 * Different modes may change how the agent processes prompts and what capabilities it
	 * exposes.
	 * </p>
	 * @param setModeRequest the set mode request with session ID and desired mode
	 * @return a Mono emitting the response confirming the mode change
	 * @see AcpSchema#METHOD_SESSION_SET_MODE
	 */
	public Mono<AcpSchema.SetSessionModeResponse> setSessionMode(AcpSchema.SetSessionModeRequest setModeRequest) {
		Assert.notNull(setModeRequest, "Set session mode request must not be null");
		logger.debug("Setting session mode: {} for session: {}", setModeRequest.modeId(), setModeRequest.sessionId());
		return session.sendRequest(AcpSchema.METHOD_SESSION_SET_MODE, setModeRequest,
				SET_SESSION_MODE_RESPONSE_TYPE_REF);
	}

	/**
	 * Sets the AI model for the specified session.
	 * <p>
	 * This allows changing which AI model is used for subsequent prompts in the session.
	 * </p>
	 * @param setModelRequest the set model request with session ID and desired model
	 * @return a Mono emitting the response confirming the model change
	 * @see AcpSchema#METHOD_SESSION_SET_MODEL
	 * @deprecated The {@code session/set_model} method was removed from the ACP spec
	 * (June 2026). Use {@link #setSessionConfigOption} with a {@code "model"} category config
	 * option instead. Slated for removal.
	 */
	@Deprecated
	@SuppressWarnings("removal")
	public Mono<AcpSchema.SetSessionModelResponse> setSessionModel(AcpSchema.SetSessionModelRequest setModelRequest) {
		Assert.notNull(setModelRequest, "Set session model request must not be null");
		logger.debug("Setting session model: {} for session: {}", setModelRequest.modelId(),
				setModelRequest.sessionId());
		return session.sendRequest(AcpSchema.METHOD_SESSION_SET_MODEL, setModelRequest,
				SET_SESSION_MODEL_RESPONSE_TYPE_REF);
	}

	/**
	 * Lists sessions known to the agent, optionally filtered by working directory.
	 *
	 * <p>
	 * Results may be paginated. Use the {@code nextCursor} from the response to fetch
	 * subsequent pages.
	 * </p>
	 * @param listSessionsRequest the list sessions request with optional cwd filter and cursor
	 * @return a Mono emitting the list sessions response
	 * @see AcpSchema#METHOD_SESSION_LIST
	 */
	public Mono<AcpSchema.ListSessionsResponse> listSessions(AcpSchema.ListSessionsRequest listSessionsRequest) {
		Assert.notNull(listSessionsRequest, "List sessions request must not be null");
		logger.debug("Listing sessions");
		return session.sendRequest(AcpSchema.METHOD_SESSION_LIST, listSessionsRequest,
				LIST_SESSIONS_RESPONSE_TYPE_REF);
	}

	/**
	 * Closes an active session, cancelling any in-flight work.
	 *
	 * <p>
	 * The agent will cancel ongoing operations as if {@code session/cancel} had been
	 * called, then free resources associated with the session.
	 * </p>
	 * @param closeSessionRequest the close session request with session ID
	 * @return a Mono emitting the close session response
	 * @see AcpSchema#METHOD_SESSION_CLOSE
	 */
	public Mono<AcpSchema.CloseSessionResponse> closeSession(AcpSchema.CloseSessionRequest closeSessionRequest) {
		Assert.notNull(closeSessionRequest, "Close session request must not be null");
		logger.debug("Closing session: {}", closeSessionRequest.sessionId());
		return session.sendRequest(AcpSchema.METHOD_SESSION_CLOSE, closeSessionRequest,
				CLOSE_SESSION_RESPONSE_TYPE_REF);
	}

	/**
	 * Permanently deletes a stored session.
	 *
	 * <p>
	 * Unlike {@link #closeSession}, which frees resources for an active session, this
	 * removes the session from the agent's storage entirely so it no longer appears in
	 * {@code session/list}. Only available if the agent advertises the
	 * {@code sessionCapabilities.delete} capability.
	 * </p>
	 * @param deleteSessionRequest the delete session request with session ID
	 * @return a Mono emitting the delete session response
	 * @see AcpSchema#METHOD_SESSION_DELETE
	 */
	public Mono<AcpSchema.DeleteSessionResponse> deleteSession(AcpSchema.DeleteSessionRequest deleteSessionRequest) {
		Assert.notNull(deleteSessionRequest, "Delete session request must not be null");
		logger.debug("Deleting session: {}", deleteSessionRequest.sessionId());
		return session.sendRequest(AcpSchema.METHOD_SESSION_DELETE, deleteSessionRequest,
				DELETE_SESSION_RESPONSE_TYPE_REF);
	}

	/**
	 * Resumes an existing session without replaying conversation history.
	 *
	 * <p>
	 * Unlike {@link #loadSession}, this method does not replay previous messages.
	 * It is useful for reconnecting to a still-running agent or when the client
	 * manages history replay independently.
	 * </p>
	 * @param resumeSessionRequest the resume session request with session ID and cwd
	 * @return a Mono emitting the resume session response
	 * @see AcpSchema#METHOD_SESSION_RESUME
	 */
	public Mono<AcpSchema.ResumeSessionResponse> resumeSession(
			AcpSchema.ResumeSessionRequest resumeSessionRequest) {
		Assert.notNull(resumeSessionRequest, "Resume session request must not be null");
		logger.debug("Resuming session: {}", resumeSessionRequest.sessionId());
		return session.sendRequest(AcpSchema.METHOD_SESSION_RESUME, resumeSessionRequest,
				RESUME_SESSION_RESPONSE_TYPE_REF);
	}

	/**
	 * Forks an existing session, creating a new session branched from it.
	 * @param forkSessionRequest the fork request with source session ID and cwd
	 * @return a Mono emitting the fork response with the new session ID
	 * @see AcpSchema#METHOD_SESSION_FORK
	 */
	public Mono<AcpSchema.ForkSessionResponse> forkSession(AcpSchema.ForkSessionRequest forkSessionRequest) {
		Assert.notNull(forkSessionRequest, "Fork session request must not be null");
		logger.debug("Forking session: {}", forkSessionRequest.sessionId());
		return session.sendRequest(AcpSchema.METHOD_SESSION_FORK, forkSessionRequest,
				FORK_SESSION_RESPONSE_TYPE_REF);
	}

	/**
	 * Sets a configuration option for a session.
	 * @param request the config option request with session ID, config ID, and value
	 * @return a Mono emitting the response with the full config state
	 * @see AcpSchema#METHOD_SESSION_SET_CONFIG_OPTION
	 */
	public Mono<AcpSchema.SetSessionConfigOptionResponse> setSessionConfigOption(
			AcpSchema.SetSessionConfigOptionRequest request) {
		Assert.notNull(request, "Set config option request must not be null");
		logger.debug("Setting config option {} for session: {}", request.configId(), request.sessionId());
		return session.sendRequest(AcpSchema.METHOD_SESSION_SET_CONFIG_OPTION, request,
				SET_SESSION_CONFIG_OPTION_RESPONSE_TYPE_REF);
	}

	// --------------------------
	// Provider Configuration (UNSTABLE)
	// --------------------------

	/**
	 * Lists the providers the agent can route to (UNSTABLE).
	 *
	 * <p>Only available if the agent advertises the {@code providers} capability.
	 * @param request the list providers request
	 * @return a Mono emitting the list of configurable providers
	 * @see AcpSchema#METHOD_PROVIDERS_LIST
	 */
	public Mono<AcpSchema.ListProvidersResponse> listProviders(AcpSchema.ListProvidersRequest request) {
		Assert.notNull(request, "List providers request must not be null");
		logger.debug("Listing providers");
		return session.sendRequest(AcpSchema.METHOD_PROVIDERS_LIST, request, LIST_PROVIDERS_RESPONSE_TYPE_REF);
	}

	/**
	 * Configures a provider's routing (protocol, base URL, headers) (UNSTABLE).
	 * @param request the set provider request
	 * @return a Mono emitting the response
	 * @see AcpSchema#METHOD_PROVIDERS_SET
	 */
	public Mono<AcpSchema.SetProviderResponse> setProvider(AcpSchema.SetProviderRequest request) {
		Assert.notNull(request, "Set provider request must not be null");
		logger.debug("Setting provider: {}", request.id());
		return session.sendRequest(AcpSchema.METHOD_PROVIDERS_SET, request, SET_PROVIDER_RESPONSE_TYPE_REF);
	}

	/**
	 * Disables a provider by id (UNSTABLE).
	 * @param request the disable provider request
	 * @return a Mono emitting the response
	 * @see AcpSchema#METHOD_PROVIDERS_DISABLE
	 */
	public Mono<AcpSchema.DisableProviderResponse> disableProvider(AcpSchema.DisableProviderRequest request) {
		Assert.notNull(request, "Disable provider request must not be null");
		logger.debug("Disabling provider: {}", request.id());
		return session.sendRequest(AcpSchema.METHOD_PROVIDERS_DISABLE, request, DISABLE_PROVIDER_RESPONSE_TYPE_REF);
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
	 * @return a Mono emitting the prompt response with stop reason
	 * @see AcpSchema#METHOD_SESSION_PROMPT
	 */
	public Mono<AcpSchema.PromptResponse> prompt(AcpSchema.PromptRequest promptRequest) {
		Assert.notNull(promptRequest, "Prompt request must not be null");
		logger.debug("Sending prompt to session: {}", promptRequest.sessionId());
		return session.sendRequest(AcpSchema.METHOD_SESSION_PROMPT, promptRequest, PROMPT_RESPONSE_TYPE_REF);
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
	 * @return a Mono that completes when the notification is sent
	 * @see AcpSchema#METHOD_SESSION_CANCEL
	 */
	public Mono<Void> cancel(AcpSchema.CancelNotification cancelNotification) {
		Assert.notNull(cancelNotification, "Cancel notification must not be null");
		logger.debug("Canceling operations for session: {}", cancelNotification.sessionId());
		return session.sendNotification(AcpSchema.METHOD_SESSION_CANCEL, cancelNotification);
	}

	// --------------------------
	// Lifecycle Management
	// --------------------------

	/**
	 * Closes the client connection immediately.
	 *
	 * <p>
	 * This closes both the session and the underlying transport.
	 * </p>
	 */
	public void close() {
		logger.debug("Closing ACP client");
		session.close();
		transport.close();
	}

	/**
	 * Gracefully closes the client connection, allowing pending operations to complete.
	 *
	 * <p>
	 * This closes both the session and the underlying transport.
	 * </p>
	 * @return a Mono that completes when the connection is closed
	 */
	public Mono<Void> closeGracefully() {
		logger.debug("Gracefully closing ACP client");
		return session.closeGracefully().then(transport.closeGracefully());
	}

}
