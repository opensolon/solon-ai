/*
 * Copyright 2025-2025 the original author or authors.
 */

package com.agentclientprotocol.sdk.spec;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;
import lombok.var;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import com.agentclientprotocol.sdk.error.AcpErrorCodes;
import com.agentclientprotocol.sdk.error.AcpProtocolException;
import com.agentclientprotocol.sdk.util.Assert;
import io.modelcontextprotocol.json.TypeRef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.core.publisher.MonoSink;

/**
 * Agent-side implementation of the ACP (Agent Client Protocol) session that manages
 * bidirectional JSON-RPC communication between agents and clients. This is the agent-side
 * counterpart to {@link AcpClientSession}.
 *
 * <p>
 * The session manages:
 * <ul>
 * <li>Request/response handling with unique message IDs</li>
 * <li>Notification processing</li>
 * <li>Message timeout management</li>
 * <li>Transport layer abstraction</li>
 * <li>Single-turn enforcement (only one prompt active at a time per session)</li>
 * </ul>
 *
 * <p>
 * This is the agent-side session that receives requests from clients (initialize,
 * newSession, prompt, etc.) and sends requests to clients (readTextFile, writeTextFile,
 * requestPermission, etc.)
 * </p>
 *
 * @author Mark Pollack
 */
public class AcpAgentSession implements AcpSession {

	private static final Logger logger = LoggerFactory.getLogger(AcpAgentSession.class);

	/** Duration to wait for request responses before timing out */
	private final Duration requestTimeout;

	/**
	 * Per-session daemon scheduler for timeout operations. Disposed when session closes.
	 */
	private final Scheduler timeoutScheduler;

	/** Transport layer implementation for message exchange */
	private final AcpAgentTransport transport;

	/** Map of pending responses keyed by request ID */
	private final ConcurrentHashMap<Object, MonoSink<AcpSchema.JSONRPCResponse>> pendingResponses = new ConcurrentHashMap<>();

	/** Map of request handlers keyed by method name */
	private final ConcurrentHashMap<String, RequestHandler<?>> requestHandlers = new ConcurrentHashMap<>();

	/** Map of notification handlers keyed by method name */
	private final ConcurrentHashMap<String, NotificationHandler> notificationHandlers = new ConcurrentHashMap<>();

	/** Session-specific prefix for request IDs */
	private final String sessionPrefix = UUID.randomUUID().toString().substring(0, 8);

	/** Atomic counter for generating unique request IDs */
	private final AtomicLong requestCounter = new AtomicLong(0);

	/**
	 * Active prompt tracking for single-turn enforcement.
	 * Only ONE prompt can be active at a time per ACP session.
	 */
	private final AtomicReference<ActivePrompt> activePrompt = new AtomicReference<>(null);

	/**
	 * Represents an active prompt session for single-turn enforcement.
	 */
	@NoArgsConstructor
	@AllArgsConstructor
	private static class ActivePrompt {
		String sessionId;
		Object requestId;

		public String sessionId(){
			return sessionId;
		}

		public Object requestId(){
			return requestId;
		}
	}

	/**
	 * Functional interface for handling incoming JSON-RPC requests. Implementations
	 * should process the request parameters and return a response.
	 *
	 * @param <T> Response type
	 */
	@FunctionalInterface
	public interface RequestHandler<T> {

		/**
		 * Handles an incoming request with the given parameters.
		 * @param params The request parameters
		 * @return A Mono containing the response object
		 */
		Mono<T> handle(Object params);

	}

	/**
	 * Functional interface for handling incoming JSON-RPC notifications. Implementations
	 * should process the notification parameters without returning a response.
	 */
	@FunctionalInterface
	public interface NotificationHandler {

		/**
		 * Handles an incoming notification with the given parameters.
		 * @param params The notification parameters
		 * @return A Mono that completes when the notification is processed
		 */
		Mono<Void> handle(Object params);

	}

	/**
	 * Creates a new AcpAgentSession with the specified configuration and handlers.
	 * @param requestTimeout Duration to wait for responses
	 * @param transport Transport implementation for message exchange
	 * @param requestHandlers Map of method names to request handlers
	 * @param notificationHandlers Map of method names to notification handlers
	 */
	public AcpAgentSession(Duration requestTimeout, AcpAgentTransport transport,
			Map<String, RequestHandler<?>> requestHandlers, Map<String, NotificationHandler> notificationHandlers) {

		Assert.notNull(requestTimeout, "The requestTimeout can not be null");
		Assert.notNull(transport, "The transport can not be null");
		Assert.notNull(requestHandlers, "The requestHandlers can not be null");
		Assert.notNull(notificationHandlers, "The notificationHandlers can not be null");

		this.requestTimeout = requestTimeout;
		this.transport = transport;
		this.requestHandlers.putAll(requestHandlers);
		this.notificationHandlers.putAll(notificationHandlers);

		// Create per-session timeout scheduler with daemon thread
		this.timeoutScheduler = Schedulers.fromExecutorService(
				Executors.newScheduledThreadPool(1, r -> {
					Thread t = new Thread(r, "acp-agent-timeout-" + sessionPrefix);
					t.setDaemon(true);
					return t;
				}), "acp-agent-timeout-" + sessionPrefix);

		this.transport.start(mono -> mono.flatMap(this::handle)).subscribe();
	}

	private void dismissPendingResponses() {
		this.pendingResponses.forEach((id, sink) -> {
			logger.warn("Abruptly terminating exchange for request {}", id);
			sink.error(new RuntimeException("ACP session with client terminated"));
		});
		this.pendingResponses.clear();
	}

	/**
	 * Handles an incoming JSON-RPC message and returns an optional response message.
	 * @param message The incoming message
	 * @return A Mono containing the response message, or empty for notifications
	 */
	private Mono<AcpSchema.JSONRPCMessage> handle(AcpSchema.JSONRPCMessage message) {
		if (message instanceof AcpSchema.JSONRPCResponse) {
			AcpSchema.JSONRPCResponse response = (AcpSchema.JSONRPCResponse) message;
			logger.debug("Received response: {}", response);
			if (response.id() != null) {
				var sink = pendingResponses.remove(response.id());
				if (sink == null) {
					logger.warn("Unexpected response for unknown id {}", response.id());
				}
				else {
					sink.success(response);
				}
			}
			else {
				logger.error("Discarded ACP request response without session id. "
						+ "This is an indication of a bug in the request sender code that can lead to memory "
						+ "leaks as pending requests will never be completed.");
			}
			return Mono.empty();
		}
		else if (message instanceof AcpSchema.JSONRPCRequest) {
			AcpSchema.JSONRPCRequest request = (AcpSchema.JSONRPCRequest) message;
			logger.debug("Received request: {}", request);
			return handleIncomingRequest(request).onErrorResume(error -> {
				// Preserve error codes from AcpProtocolException, wrap others in INTERNAL_ERROR
				int errorCode;
				Object errorData = null;
				if (error instanceof AcpProtocolException) {
					AcpProtocolException protocolException = (AcpProtocolException) error;
					errorCode = protocolException.getCode();
					errorData = protocolException.getData();
				}
				else {
					errorCode = AcpErrorCodes.INTERNAL_ERROR;
				}
				var errorResponse = new AcpSchema.JSONRPCResponse(AcpSchema.JSONRPC_VERSION, request.id(), null,
						new AcpSchema.JSONRPCError(errorCode, error.getMessage(), errorData));
				return Mono.just(errorResponse);
			}).map(response -> (AcpSchema.JSONRPCMessage) response);
		}
		else if (message instanceof AcpSchema.JSONRPCNotification) {
			AcpSchema.JSONRPCNotification notification = (AcpSchema.JSONRPCNotification) message;
			logger.debug("Received notification: {}", notification);
			return handleIncomingNotification(notification).then(Mono.empty());
		}
		else {
			logger.warn("Received unknown message type: {}", message);
			return Mono.empty();
		}
	}

	/**
	 * Handles an incoming JSON-RPC request by routing it to the appropriate handler.
	 * For session/prompt requests, enforces single-turn semantics.
	 * @param request The incoming JSON-RPC request
	 * @return A Mono containing the JSON-RPC response
	 */
	private Mono<AcpSchema.JSONRPCResponse> handleIncomingRequest(AcpSchema.JSONRPCRequest request) {
		return Mono.defer(() -> {
			var handler = this.requestHandlers.get(request.method());
			if (handler == null) {
				MethodNotFoundError error = getMethodNotFoundError(request.method());
				return Mono.just(new AcpSchema.JSONRPCResponse(AcpSchema.JSONRPC_VERSION, request.id(), null,
						new AcpSchema.JSONRPCError(-32601, error.message(), error.data())));
			}

			// Single-turn enforcement for session/prompt requests
			if (AcpSchema.METHOD_SESSION_PROMPT.equals(request.method())) {
				// Extract sessionId from params
				String sessionId = extractSessionId(request.params());
				ActivePrompt newPrompt = new ActivePrompt(sessionId, request.id());

				// Try to set as active prompt - fails if another prompt is active
				if (!activePrompt.compareAndSet(null, newPrompt)) {
					ActivePrompt current = activePrompt.get();
					logger.warn("Rejected concurrent prompt request. Active prompt: sessionId={}, requestId={}",
							current != null ? current.sessionId() : "unknown",
							current != null ? current.requestId() : "unknown");
					return Mono.just(new AcpSchema.JSONRPCResponse(AcpSchema.JSONRPC_VERSION, request.id(), null,
							new AcpSchema.JSONRPCError(-32000, "There is already an active prompt execution", null)));
				}

				// Execute handler and clear active prompt when done
				return handler.handle(request.params())
					.map(result -> new AcpSchema.JSONRPCResponse(AcpSchema.JSONRPC_VERSION, request.id(), result, null))
					.doFinally(signal -> {
						activePrompt.compareAndSet(newPrompt, null);
						logger.debug("Prompt completed with signal: {}", signal);
					});
			}

			return handler.handle(request.params())
				.map(result -> new AcpSchema.JSONRPCResponse(AcpSchema.JSONRPC_VERSION, request.id(), result, null));
		});
	}

	/**
	 * Extracts the sessionId from request parameters.
	 */
	@SuppressWarnings("unchecked")
	private String extractSessionId(Object params) {
		if (params instanceof Map<?, ?>) {
			Map<?, ?> map = (Map<?, ?>) params;
			Object sessionId = map.get("sessionId");
			return sessionId != null ? sessionId.toString() : "unknown";
		}
		return "unknown";
	}

	@AllArgsConstructor
	@NoArgsConstructor
	static class MethodNotFoundError {
		String method;
		String message;
		Object data;

		public String method() {
			return method;
		}

		public String message() {
			return message;
		}

		public Object data() {
			return data;
		}
	}

	private MethodNotFoundError getMethodNotFoundError(String method) {
		return new MethodNotFoundError(method, "Method not found: " + method, null);
	}

	/**
	 * Handles an incoming JSON-RPC notification by routing it to the appropriate handler.
	 * For session/cancel notifications, clears the active prompt.
	 * @param notification The incoming JSON-RPC notification
	 * @return A Mono that completes when the notification is processed
	 */
	private Mono<Void> handleIncomingNotification(AcpSchema.JSONRPCNotification notification) {
		return Mono.defer(() -> {
			// Handle cancel notification specially
			if (AcpSchema.METHOD_SESSION_CANCEL.equals(notification.method())) {
				String sessionId = extractSessionId(notification.params());
				ActivePrompt current = activePrompt.get();
				if (current != null && sessionId.equals(current.sessionId())) {
					activePrompt.compareAndSet(current, null);
					logger.debug("Cancelled active prompt for session: {}", sessionId);
				}
			}

			var handler = notificationHandlers.get(notification.method());
			if (handler == null) {
				logger.warn("No handler registered for notification method: {}", notification.method());
				return Mono.empty();
			}
			return handler.handle(notification.params());
		});
	}

	/**
	 * Generates a unique request ID in a non-blocking way. Combines a session-specific
	 * prefix with an atomic counter to ensure uniqueness.
	 * @return A unique request ID string
	 */
	private String generateRequestId() {
		return this.sessionPrefix + "-" + this.requestCounter.getAndIncrement();
	}

	/**
	 * Sends a JSON-RPC request to the client and expects a response of type T.
	 * This is used for agent→client requests like fs/read_text_file, terminal/*, etc.
	 * @param <T> the type of the expected response
	 * @param method the name of the method to call on the client
	 * @param requestParams the parameters to send with the request
	 * @param typeRef the TypeReference describing the expected response type
	 * @return a Mono that will emit the response when received
	 */
	@Override
	public <T> Mono<T> sendRequest(String method, Object requestParams, TypeRef<T> typeRef) {
		String requestId = this.generateRequestId();

		return Mono.deferContextual(ctx -> Mono.<AcpSchema.JSONRPCResponse>create(pendingResponseSink -> {
			logger.debug("Sending request for method {} with id {}", method, requestId);
			this.pendingResponses.put(requestId, pendingResponseSink);
			AcpSchema.JSONRPCRequest jsonrpcRequest = new AcpSchema.JSONRPCRequest(AcpSchema.JSONRPC_VERSION, requestId,
					method, requestParams);
			this.transport.sendMessage(jsonrpcRequest).contextWrite(ctx).subscribe(v -> {
			}, error -> {
				this.pendingResponses.remove(requestId);
				pendingResponseSink.error(error);
			});
		})).timeout(this.requestTimeout, timeoutScheduler).handle((jsonRpcResponse, deliveredResponseSink) -> {
			if (jsonRpcResponse.error() != null) {
				logger.error("Error handling request: {}", jsonRpcResponse.error());
				deliveredResponseSink.error(new AcpError(jsonRpcResponse.error()));
			}
			else {
				if (typeRef.getType().equals(Void.class)) {
					deliveredResponseSink.complete();
				}
				else {
					deliveredResponseSink.next(this.transport.unmarshalFrom(jsonRpcResponse.result(), typeRef));
				}
			}
		});
	}

	/**
	 * Sends a JSON-RPC notification to the client.
	 * This is used for agent→client notifications like session/update.
	 * @param method the name of the notification method
	 * @param params the notification parameters
	 * @return a Mono that completes when the notification is sent
	 */
	@Override
	public Mono<Void> sendNotification(String method, Object params) {
		AcpSchema.JSONRPCNotification jsonrpcNotification = new AcpSchema.JSONRPCNotification(AcpSchema.JSONRPC_VERSION,
				method, params);
		return this.transport.sendMessage(jsonrpcNotification);
	}

	/**
	 * Checks if there is an active prompt being processed.
	 * @return true if a prompt is currently active
	 */
	public boolean hasActivePrompt() {
		return activePrompt.get() != null;
	}

	/**
	 * Gets the session ID of the active prompt, if any.
	 * @return the session ID or null if no prompt is active
	 */
	public String getActivePromptSessionId() {
		ActivePrompt current = activePrompt.get();
		return current != null ? current.sessionId() : null;
	}

	/**
	 * Closes the session gracefully, allowing pending operations to complete.
	 * @return A Mono that completes when the session is closed
	 */
	@Override
	public Mono<Void> closeGracefully() {
		return Mono.fromRunnable(() -> {
			activePrompt.set(null);
			dismissPendingResponses();
			timeoutScheduler.dispose();
		}).then(this.transport.closeGracefully());
	}

	/**
	 * Closes the session immediately, potentially interrupting pending operations.
	 */
	@Override
	public void close() {
		activePrompt.set(null);
		dismissPendingResponses();
		timeoutScheduler.dispose();
		transport.close();
	}

	/**
	 * ACP-specific error wrapper for JSON-RPC errors.
	 * Provides detailed error information including code, message, and data.
	 */
	public static class AcpError extends RuntimeException {

		private final AcpSchema.JSONRPCError error;

		public AcpError(AcpSchema.JSONRPCError error) {
			super(buildErrorMessage(error));
			this.error = error;
		}

		private static String buildErrorMessage(AcpSchema.JSONRPCError error) {
			StringBuilder sb = new StringBuilder();
			sb.append(error.message());
			sb.append(" [code=").append(error.code()).append("]");
			if (error.data() != null) {
				sb.append(": ").append(formatErrorData(error.data()));
			}
			return sb.toString();
		}

		private static String formatErrorData(Object data) {
			if (data instanceof java.util.Map<?, ?>) {
				java.util.Map<?, ?> map = (java.util.Map<?, ?>)data;

				// Extract common fields for better readability
				Object details = map.get("details");
				if (details != null) {
					return details.toString();
				}
				Object reason = map.get("reason");
				if (reason != null) {
					return reason.toString();
				}
			}
			return data.toString();
		}

		public AcpSchema.JSONRPCError getError() {
			return error;
		}

		public int getCode() {
			return error.code();
		}

		public Object getData() {
			return error.data();
		}

	}

}
