/*
 * Copyright 2025-2025 the original author or authors.
 */

package com.agentclientprotocol.sdk.agent;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import com.agentclientprotocol.sdk.capabilities.NegotiatedCapabilities;
import com.agentclientprotocol.sdk.error.AcpCapabilityException;
import com.agentclientprotocol.sdk.spec.AcpAgentSession;
import com.agentclientprotocol.sdk.spec.AcpAgentTransport;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import com.agentclientprotocol.sdk.json.TypeRef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * Default implementation of {@link AcpAsyncAgent} that provides non-blocking
 * operations for handling client requests.
 *
 * <p>
 * This implementation creates an {@link AcpAgentSession} to manage the JSON-RPC
 * communication and registers handlers for all ACP protocol methods.
 * </p>
 *
 * @author Mark Pollack
 */
class DefaultAcpAsyncAgent implements AcpAsyncAgent {

	private static final Logger logger = LoggerFactory.getLogger(DefaultAcpAsyncAgent.class);

	private final AcpAgentTransport transport;

	private final Duration requestTimeout;

	private final AcpAgent.InitializeHandler initializeHandler;

	private final AcpAgent.AuthenticateHandler authenticateHandler;

	private final AcpAgent.LogoutHandler logoutHandler;

	private final AcpAgent.NewSessionHandler newSessionHandler;

	private final AcpAgent.LoadSessionHandler loadSessionHandler;

	private final AcpAgent.PromptHandler promptHandler;

	private final AcpAgent.SetSessionModeHandler setSessionModeHandler;

	@SuppressWarnings("removal")
	private final AcpAgent.SetSessionModelHandler setSessionModelHandler;

	private final AcpAgent.ListSessionsHandler listSessionsHandler;

	private final AcpAgent.CloseSessionHandler closeSessionHandler;

	private final AcpAgent.DeleteSessionHandler deleteSessionHandler;

	private final AcpAgent.ResumeSessionHandler resumeSessionHandler;

	private final AcpAgent.ForkSessionHandler forkSessionHandler;

	private final AcpAgent.SetSessionConfigOptionHandler setSessionConfigOptionHandler;

	private final AcpAgent.ListProvidersHandler listProvidersHandler;

	private final AcpAgent.SetProviderHandler setProviderHandler;

	private final AcpAgent.DisableProviderHandler disableProviderHandler;

	private final AcpAgent.CancelHandler cancelHandler;

	private volatile AcpAgentSession session;

	/**
	 * Capabilities negotiated with the client during initialization.
	 */
	private final AtomicReference<NegotiatedCapabilities> clientCapabilities = new AtomicReference<>();

	@SuppressWarnings("removal") // accepts the deprecated-for-removal SetSessionModelHandler
	DefaultAcpAsyncAgent(AcpAgentTransport transport, Duration requestTimeout,
			AcpAgent.InitializeHandler initializeHandler, AcpAgent.AuthenticateHandler authenticateHandler,
			AcpAgent.LogoutHandler logoutHandler, AcpAgent.NewSessionHandler newSessionHandler,
			AcpAgent.LoadSessionHandler loadSessionHandler,
			AcpAgent.PromptHandler promptHandler, AcpAgent.SetSessionModeHandler setSessionModeHandler,
			AcpAgent.SetSessionModelHandler setSessionModelHandler,
			AcpAgent.ListSessionsHandler listSessionsHandler, AcpAgent.CloseSessionHandler closeSessionHandler,
			AcpAgent.DeleteSessionHandler deleteSessionHandler,
			AcpAgent.ResumeSessionHandler resumeSessionHandler, AcpAgent.ForkSessionHandler forkSessionHandler,
			AcpAgent.SetSessionConfigOptionHandler setSessionConfigOptionHandler,
			AcpAgent.ListProvidersHandler listProvidersHandler, AcpAgent.SetProviderHandler setProviderHandler,
			AcpAgent.DisableProviderHandler disableProviderHandler,
			AcpAgent.CancelHandler cancelHandler) {
		this.transport = transport;
		this.requestTimeout = requestTimeout;
		this.initializeHandler = initializeHandler;
		this.authenticateHandler = authenticateHandler;
		this.logoutHandler = logoutHandler;
		this.newSessionHandler = newSessionHandler;
		this.loadSessionHandler = loadSessionHandler;
		this.promptHandler = promptHandler;
		this.setSessionModeHandler = setSessionModeHandler;
		this.setSessionModelHandler = setSessionModelHandler;
		this.listSessionsHandler = listSessionsHandler;
		this.closeSessionHandler = closeSessionHandler;
		this.deleteSessionHandler = deleteSessionHandler;
		this.resumeSessionHandler = resumeSessionHandler;
		this.forkSessionHandler = forkSessionHandler;
		this.setSessionConfigOptionHandler = setSessionConfigOptionHandler;
		this.listProvidersHandler = listProvidersHandler;
		this.setProviderHandler = setProviderHandler;
		this.disableProviderHandler = disableProviderHandler;
		this.cancelHandler = cancelHandler;
	}

	@Override
	@SuppressWarnings("removal") // registers the deprecated-for-removal session/set_model handler
	public Mono<Void> start() {
		return Mono.fromRunnable(() -> {
			logger.info("Starting ACP async agent");

			// Build request handlers
			Map<String, AcpAgentSession.RequestHandler<?>> requestHandlers = new HashMap<>();

			// Initialize handler - also captures client capabilities
			if (initializeHandler != null) {
				requestHandlers.put(AcpSchema.METHOD_INITIALIZE, params -> {
					AcpSchema.InitializeRequest request = transport.unmarshalFrom(params,
							new TypeRef<AcpSchema.InitializeRequest>() {
							});
					// Capture the client capabilities
					NegotiatedCapabilities caps = NegotiatedCapabilities.fromClient(request.clientCapabilities());
					clientCapabilities.set(caps);
					logger.debug("Negotiated client capabilities: {}", caps);
					return initializeHandler.handle(request).cast(Object.class);
				});
			}

			// Authenticate handler
			if (authenticateHandler != null) {
				requestHandlers.put(AcpSchema.METHOD_AUTHENTICATE, params -> {
					AcpSchema.AuthenticateRequest request = transport.unmarshalFrom(params,
							new TypeRef<AcpSchema.AuthenticateRequest>() {
							});
					return authenticateHandler.handle(request).cast(Object.class);
				});
			}

			// Logout handler
			if (logoutHandler != null) {
				requestHandlers.put(AcpSchema.METHOD_LOGOUT, params -> {
					AcpSchema.LogoutRequest request = transport.unmarshalFrom(params,
							new TypeRef<AcpSchema.LogoutRequest>() {
							});
					return logoutHandler.handle(request).cast(Object.class);
				});
			}

			// New session handler
			if (newSessionHandler != null) {
				requestHandlers.put(AcpSchema.METHOD_SESSION_NEW, params -> {
					AcpSchema.NewSessionRequest request = transport.unmarshalFrom(params,
							new TypeRef<AcpSchema.NewSessionRequest>() {
							});
					return newSessionHandler.handle(request).cast(Object.class);
				});
			}

			// Load session handler
			if (loadSessionHandler != null) {
				requestHandlers.put(AcpSchema.METHOD_SESSION_LOAD, params -> {
					AcpSchema.LoadSessionRequest request = transport.unmarshalFrom(params,
							new TypeRef<AcpSchema.LoadSessionRequest>() {
							});
					return loadSessionHandler.handle(request).cast(Object.class);
				});
			}

			// Prompt handler - provides full PromptContext with all agent capabilities
			if (promptHandler != null) {
				requestHandlers.put(AcpSchema.METHOD_SESSION_PROMPT, params -> {
					AcpSchema.PromptRequest request = transport.unmarshalFrom(params,
							new TypeRef<AcpSchema.PromptRequest>() {
							});
					// Create PromptContext that wraps this agent, giving handler access to all capabilities
					PromptContext context = new DefaultPromptContext(this, request.sessionId());
					return promptHandler.handle(request, context)
						.cast(Object.class);
				});
			}

			// Set session mode handler
			if (setSessionModeHandler != null) {
				requestHandlers.put(AcpSchema.METHOD_SESSION_SET_MODE, params -> {
					AcpSchema.SetSessionModeRequest request = transport.unmarshalFrom(params,
							new TypeRef<AcpSchema.SetSessionModeRequest>() {
							});
					return setSessionModeHandler.handle(request).cast(Object.class);
				});
			}

			// Set session model handler
			if (setSessionModelHandler != null) {
				requestHandlers.put(AcpSchema.METHOD_SESSION_SET_MODEL, params -> {
					AcpSchema.SetSessionModelRequest request = transport.unmarshalFrom(params,
							new TypeRef<AcpSchema.SetSessionModelRequest>() {
							});
					return setSessionModelHandler.handle(request).cast(Object.class);
				});
			}

			// List sessions handler
			if (listSessionsHandler != null) {
				requestHandlers.put(AcpSchema.METHOD_SESSION_LIST, params -> {
					AcpSchema.ListSessionsRequest request = transport.unmarshalFrom(params,
							new TypeRef<AcpSchema.ListSessionsRequest>() {
							});
					return listSessionsHandler.handle(request).cast(Object.class);
				});
			}

			// Close session handler
			if (closeSessionHandler != null) {
				requestHandlers.put(AcpSchema.METHOD_SESSION_CLOSE, params -> {
					AcpSchema.CloseSessionRequest request = transport.unmarshalFrom(params,
							new TypeRef<AcpSchema.CloseSessionRequest>() {
							});
					return closeSessionHandler.handle(request).cast(Object.class);
				});
			}

			// Delete session handler
			if (deleteSessionHandler != null) {
				requestHandlers.put(AcpSchema.METHOD_SESSION_DELETE, params -> {
					AcpSchema.DeleteSessionRequest request = transport.unmarshalFrom(params,
							new TypeRef<AcpSchema.DeleteSessionRequest>() {
							});
					return deleteSessionHandler.handle(request).cast(Object.class);
				});
			}

			// Resume session handler
			if (resumeSessionHandler != null) {
				requestHandlers.put(AcpSchema.METHOD_SESSION_RESUME, params -> {
					AcpSchema.ResumeSessionRequest request = transport.unmarshalFrom(params,
							new TypeRef<AcpSchema.ResumeSessionRequest>() {
							});
					return resumeSessionHandler.handle(request).cast(Object.class);
				});
			}

			// Fork session handler
			if (forkSessionHandler != null) {
				requestHandlers.put(AcpSchema.METHOD_SESSION_FORK, params -> {
					AcpSchema.ForkSessionRequest request = transport.unmarshalFrom(params,
							new TypeRef<AcpSchema.ForkSessionRequest>() {
							});
					return forkSessionHandler.handle(request).cast(Object.class);
				});
			}

			// Set config option handler
			if (setSessionConfigOptionHandler != null) {
				requestHandlers.put(AcpSchema.METHOD_SESSION_SET_CONFIG_OPTION, params -> {
					AcpSchema.SetSessionConfigOptionRequest request = transport.unmarshalFrom(params,
							new TypeRef<AcpSchema.SetSessionConfigOptionRequest>() {
							});
					return setSessionConfigOptionHandler.handle(request).cast(Object.class);
				});
			}

			// List providers handler (unstable)
			if (listProvidersHandler != null) {
				requestHandlers.put(AcpSchema.METHOD_PROVIDERS_LIST, params -> {
					AcpSchema.ListProvidersRequest request = transport.unmarshalFrom(params,
							new TypeRef<AcpSchema.ListProvidersRequest>() {
							});
					return listProvidersHandler.handle(request).cast(Object.class);
				});
			}

			// Set provider handler (unstable)
			if (setProviderHandler != null) {
				requestHandlers.put(AcpSchema.METHOD_PROVIDERS_SET, params -> {
					AcpSchema.SetProviderRequest request = transport.unmarshalFrom(params,
							new TypeRef<AcpSchema.SetProviderRequest>() {
							});
					return setProviderHandler.handle(request).cast(Object.class);
				});
			}

			// Disable provider handler (unstable)
			if (disableProviderHandler != null) {
				requestHandlers.put(AcpSchema.METHOD_PROVIDERS_DISABLE, params -> {
					AcpSchema.DisableProviderRequest request = transport.unmarshalFrom(params,
							new TypeRef<AcpSchema.DisableProviderRequest>() {
							});
					return disableProviderHandler.handle(request).cast(Object.class);
				});
			}

			// Build notification handlers
			Map<String, AcpAgentSession.NotificationHandler> notificationHandlers = new HashMap<>();

			// Cancel handler
			if (cancelHandler != null) {
				notificationHandlers.put(AcpSchema.METHOD_SESSION_CANCEL, params -> {
					AcpSchema.CancelNotification notification = transport.unmarshalFrom(params,
							new TypeRef<AcpSchema.CancelNotification>() {
							});
					return cancelHandler.handle(notification);
				});
			}

			// Create and start the session
			this.session = new AcpAgentSession(requestTimeout, transport, requestHandlers, notificationHandlers);

			logger.info("ACP async agent started");
		});
	}

	@Override
	public Mono<Void> awaitTermination() {
		return transport.awaitTermination();
	}

	@Override
	public NegotiatedCapabilities getClientCapabilities() {
		return clientCapabilities.get();
	}

	@Override
	public Mono<Void> sendSessionUpdate(String sessionId, AcpSchema.SessionUpdate update) {
		if (session == null) {
			return Mono.error(new IllegalStateException("Agent not started"));
		}
		AcpSchema.SessionNotification notification = new AcpSchema.SessionNotification(sessionId, update);
		return session.sendNotification(AcpSchema.METHOD_SESSION_UPDATE, notification);
	}

	@Override
	public Mono<AcpSchema.RequestPermissionResponse> requestPermission(AcpSchema.RequestPermissionRequest request) {
		if (session == null) {
			return Mono.error(new IllegalStateException("Agent not started"));
		}
		return session.sendRequest(AcpSchema.METHOD_SESSION_REQUEST_PERMISSION, request,
				new TypeRef<AcpSchema.RequestPermissionResponse>() {
				});
	}

	@Override
	public Mono<AcpSchema.ReadTextFileResponse> readTextFile(AcpSchema.ReadTextFileRequest request) {
		if (session == null) {
			return Mono.error(new IllegalStateException("Agent not started"));
		}
		// Validate client supports file reading
		NegotiatedCapabilities caps = clientCapabilities.get();
		if (caps != null && !caps.supportsReadTextFile()) {
			return Mono.error(new AcpCapabilityException("fs.readTextFile"));
		}
		return session.sendRequest(AcpSchema.METHOD_FS_READ_TEXT_FILE, request,
				new TypeRef<AcpSchema.ReadTextFileResponse>() {
				});
	}

	@Override
	public Mono<AcpSchema.WriteTextFileResponse> writeTextFile(AcpSchema.WriteTextFileRequest request) {
		if (session == null) {
			return Mono.error(new IllegalStateException("Agent not started"));
		}
		// Validate client supports file writing
		NegotiatedCapabilities caps = clientCapabilities.get();
		if (caps != null && !caps.supportsWriteTextFile()) {
			return Mono.error(new AcpCapabilityException("fs.writeTextFile"));
		}
		return session.sendRequest(AcpSchema.METHOD_FS_WRITE_TEXT_FILE, request,
				new TypeRef<AcpSchema.WriteTextFileResponse>() {
				});
	}

	@Override
	public Mono<AcpSchema.CreateTerminalResponse> createTerminal(AcpSchema.CreateTerminalRequest request) {
		if (session == null) {
			return Mono.error(new IllegalStateException("Agent not started"));
		}
		// Validate client supports terminal
		NegotiatedCapabilities caps = clientCapabilities.get();
		if (caps != null && !caps.supportsTerminal()) {
			return Mono.error(new AcpCapabilityException("terminal"));
		}
		return session.sendRequest(AcpSchema.METHOD_TERMINAL_CREATE, request,
				new TypeRef<AcpSchema.CreateTerminalResponse>() {
				});
	}

	@Override
	public Mono<AcpSchema.TerminalOutputResponse> getTerminalOutput(AcpSchema.TerminalOutputRequest request) {
		if (session == null) {
			return Mono.error(new IllegalStateException("Agent not started"));
		}
		return session.sendRequest(AcpSchema.METHOD_TERMINAL_OUTPUT, request,
				new TypeRef<AcpSchema.TerminalOutputResponse>() {
				});
	}

	@Override
	public Mono<AcpSchema.ReleaseTerminalResponse> releaseTerminal(AcpSchema.ReleaseTerminalRequest request) {
		if (session == null) {
			return Mono.error(new IllegalStateException("Agent not started"));
		}
		return session.sendRequest(AcpSchema.METHOD_TERMINAL_RELEASE, request,
				new TypeRef<AcpSchema.ReleaseTerminalResponse>() {
				});
	}

	@Override
	public Mono<AcpSchema.WaitForTerminalExitResponse> waitForTerminalExit(
			AcpSchema.WaitForTerminalExitRequest request) {
		if (session == null) {
			return Mono.error(new IllegalStateException("Agent not started"));
		}
		return session.sendRequest(AcpSchema.METHOD_TERMINAL_WAIT_FOR_EXIT, request,
				new TypeRef<AcpSchema.WaitForTerminalExitResponse>() {
				});
	}

	@Override
	public Mono<AcpSchema.KillTerminalCommandResponse> killTerminal(AcpSchema.KillTerminalCommandRequest request) {
		if (session == null) {
			return Mono.error(new IllegalStateException("Agent not started"));
		}
		return session.sendRequest(AcpSchema.METHOD_TERMINAL_KILL, request,
				new TypeRef<AcpSchema.KillTerminalCommandResponse>() {
				});
	}

	@Override
	public Mono<AcpSchema.CreateElicitationResponse> createElicitation(
			AcpSchema.CreateElicitationRequest request) {
		if (session == null) {
			return Mono.error(new IllegalStateException("Agent not started"));
		}
		NegotiatedCapabilities caps = clientCapabilities.get();
		if (caps != null && !caps.supportsElicitation()) {
			return Mono.error(new AcpCapabilityException("elicitation"));
		}
		return session.sendRequest(AcpSchema.METHOD_ELICITATION_CREATE, request,
				new TypeRef<AcpSchema.CreateElicitationResponse>() {
				});
	}

	@Override
	public Mono<Void> completeElicitation(AcpSchema.CompleteElicitationNotification notification) {
		if (session == null) {
			return Mono.error(new IllegalStateException("Agent not started"));
		}
		return session.sendNotification(AcpSchema.METHOD_ELICITATION_COMPLETE, notification);
	}

	@Override
	public Mono<Void> closeGracefully() {
		return Mono.defer(() -> {
			logger.info("Closing ACP async agent gracefully");
			if (session != null) {
				return session.closeGracefully();
			}
			return Mono.empty();
		});
	}

	@Override
	public void close() {
		logger.info("Closing ACP async agent");
		if (session != null) {
			session.close();
		}
	}

}
