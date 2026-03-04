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
import io.modelcontextprotocol.json.TypeRef;
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

	private final AcpAgent.NewSessionHandler newSessionHandler;

	private final AcpAgent.LoadSessionHandler loadSessionHandler;

	private final AcpAgent.PromptHandler promptHandler;

	private final AcpAgent.SetSessionModeHandler setSessionModeHandler;

	private final AcpAgent.SetSessionModelHandler setSessionModelHandler;

	private final AcpAgent.CancelHandler cancelHandler;

	private volatile AcpAgentSession session;

	/**
	 * Capabilities negotiated with the client during initialization.
	 */
	private final AtomicReference<NegotiatedCapabilities> clientCapabilities = new AtomicReference<>();

	DefaultAcpAsyncAgent(AcpAgentTransport transport, Duration requestTimeout,
			AcpAgent.InitializeHandler initializeHandler, AcpAgent.AuthenticateHandler authenticateHandler,
			AcpAgent.NewSessionHandler newSessionHandler, AcpAgent.LoadSessionHandler loadSessionHandler,
			AcpAgent.PromptHandler promptHandler, AcpAgent.SetSessionModeHandler setSessionModeHandler,
			AcpAgent.SetSessionModelHandler setSessionModelHandler, AcpAgent.CancelHandler cancelHandler) {
		this.transport = transport;
		this.requestTimeout = requestTimeout;
		this.initializeHandler = initializeHandler;
		this.authenticateHandler = authenticateHandler;
		this.newSessionHandler = newSessionHandler;
		this.loadSessionHandler = loadSessionHandler;
		this.promptHandler = promptHandler;
		this.setSessionModeHandler = setSessionModeHandler;
		this.setSessionModelHandler = setSessionModelHandler;
		this.cancelHandler = cancelHandler;
	}

	@Override
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
