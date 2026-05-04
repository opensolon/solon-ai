/*
 * Copyright 2025-2025 the original author or authors.
 */

package com.agentclientprotocol.sdk.spec;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import com.agentclientprotocol.sdk.test.InMemoryTransportPair;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import java.util.Collections;

/**
 * Unit tests for {@link AcpAgentSession}.
 */
class AcpAgentSessionTest {

	private static final Duration TIMEOUT = Duration.ofSeconds(5);

	@Test
	void constructorValidatesArguments() {
		InMemoryTransportPair transportPair = InMemoryTransportPair.create();
		try {
			assertThrows(IllegalArgumentException.class,
					() -> new AcpAgentSession(null, transportPair.agentTransport(), Collections.emptyMap(), Collections.emptyMap()));
			assertThrows(IllegalArgumentException.class,
					() -> new AcpAgentSession(TIMEOUT, null, Collections.emptyMap(), Collections.emptyMap()));
			assertThrows(IllegalArgumentException.class,
					() -> new AcpAgentSession(TIMEOUT, transportPair.agentTransport(), null, Collections.emptyMap()));
			assertThrows(IllegalArgumentException.class,
					() -> new AcpAgentSession(TIMEOUT, transportPair.agentTransport(), Collections.emptyMap(), null));
		}
		finally {
			transportPair.closeGracefully().block(TIMEOUT);
		}
	}

	@Test
	void handlesIncomingRequest() throws Exception {
		InMemoryTransportPair transportPair = InMemoryTransportPair.create();
		try {
			// Create session with an initialize handler
			AtomicReference<Object> receivedParams = new AtomicReference<>();
			Map<String, AcpAgentSession.RequestHandler<?>> requestHandlers = Collections.singletonMap(AcpSchema.METHOD_INITIALIZE, params -> {
						receivedParams.set(params);
						return Mono.just(new AcpSchema.InitializeResponse(1,
								new AcpSchema.AgentCapabilities(false, null, null), Collections.emptyList()));
					});

			new AcpAgentSession(TIMEOUT, transportPair.agentTransport(), requestHandlers, Collections.emptyMap());

			// Allow transport to start
			Thread.sleep(100);

			// Send a request from the client side
			CountDownLatch latch = new CountDownLatch(1);
			AtomicReference<AcpSchema.JSONRPCMessage> response = new AtomicReference<>();

			AcpSchema.JSONRPCRequest request = new AcpSchema.JSONRPCRequest(AcpSchema.JSONRPC_VERSION, "1",
					AcpSchema.METHOD_INITIALIZE, new AcpSchema.InitializeRequest(1, new AcpSchema.ClientCapabilities()));

			transportPair.clientTransport().connect(mono -> mono.doOnNext(msg -> {
				response.set(msg);
				latch.countDown();
			}).then(Mono.empty())).subscribe();

			Thread.sleep(50);
			transportPair.clientTransport().sendMessage(request).block(TIMEOUT);

			assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
			assertThat(response.get()).isInstanceOf(AcpSchema.JSONRPCResponse.class);
			AcpSchema.JSONRPCResponse jsonResponse = (AcpSchema.JSONRPCResponse) response.get();
			assertThat(jsonResponse.id()).isEqualTo("1");
			assertThat(jsonResponse.error()).isNull();
			assertThat(receivedParams.get()).isNotNull();
		}
		finally {
			transportPair.closeGracefully().block(TIMEOUT);
		}
	}

	@Test
	void handlesMethodNotFound() throws Exception {
		InMemoryTransportPair transportPair = InMemoryTransportPair.create();
		try {
			// Create session with no handlers
			new AcpAgentSession(TIMEOUT, transportPair.agentTransport(), Collections.emptyMap(), Collections.emptyMap());

			Thread.sleep(100);

			// Send a request for unknown method
			CountDownLatch latch = new CountDownLatch(1);
			AtomicReference<AcpSchema.JSONRPCMessage> response = new AtomicReference<>();

			AcpSchema.JSONRPCRequest request = new AcpSchema.JSONRPCRequest(AcpSchema.JSONRPC_VERSION, "1",
					"unknown/method", null);

			transportPair.clientTransport().connect(mono -> mono.doOnNext(msg -> {
				response.set(msg);
				latch.countDown();
			}).then(Mono.empty())).subscribe();

			Thread.sleep(50);
			transportPair.clientTransport().sendMessage(request).block(TIMEOUT);

			assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
			assertThat(response.get()).isInstanceOf(AcpSchema.JSONRPCResponse.class);
			AcpSchema.JSONRPCResponse jsonResponse = (AcpSchema.JSONRPCResponse) response.get();
			assertThat(jsonResponse.error()).isNotNull();
			assertThat(jsonResponse.error().code()).isEqualTo(-32601); // Method not found
		}
		finally {
			transportPair.closeGracefully().block(TIMEOUT);
		}
	}

	@Test
	void handlesNotification() throws Exception {
		InMemoryTransportPair transportPair = InMemoryTransportPair.create();
		try {
			AtomicReference<Object> receivedParams = new AtomicReference<>();
			CountDownLatch notificationLatch = new CountDownLatch(1);

			Map<String, AcpAgentSession.NotificationHandler> notificationHandlers = Collections.singletonMap(
				AcpSchema.METHOD_SESSION_CANCEL, params -> {
					receivedParams.set(params);
					notificationLatch.countDown();
					return Mono.empty();
				});

			new AcpAgentSession(TIMEOUT, transportPair.agentTransport(), Collections.emptyMap(), notificationHandlers);

			Thread.sleep(100);

			// Send a notification from client
			AcpSchema.JSONRPCNotification notification = new AcpSchema.JSONRPCNotification(AcpSchema.JSONRPC_VERSION,
					AcpSchema.METHOD_SESSION_CANCEL, new AcpSchema.CancelNotification("session-1"));

			transportPair.clientTransport().connect(mono -> mono.then(Mono.empty())).subscribe();
			Thread.sleep(50);
			transportPair.clientTransport().sendMessage(notification).block(TIMEOUT);

			assertThat(notificationLatch.await(5, TimeUnit.SECONDS)).isTrue();
			assertThat(receivedParams.get()).isNotNull();
		}
		finally {
			transportPair.closeGracefully().block(TIMEOUT);
		}
	}

	@Test
	void singleTurnEnforcementRejectsConcurrentPrompts() throws Exception {
		InMemoryTransportPair transportPair = InMemoryTransportPair.create();
		try {
			// Create a handler that uses a Mono.delay to simulate async processing
			AtomicReference<CountDownLatch> promptCanProceedRef = new AtomicReference<>(new CountDownLatch(1));

			Map<String, AcpAgentSession.RequestHandler<?>> requestHandlers = Collections.singletonMap(AcpSchema.METHOD_SESSION_PROMPT, params -> Mono.defer(() -> {
						// First call gets blocked, second call should be rejected before getting here
						return Mono.delay(Duration.ofMillis(100))
							.map(ignored -> new AcpSchema.PromptResponse(AcpSchema.StopReason.END_TURN));
					}));

			AcpAgentSession session = new AcpAgentSession(TIMEOUT, transportPair.agentTransport(), requestHandlers,
					Collections.emptyMap());

			Thread.sleep(100);

			// Manually set active prompt to simulate an in-progress prompt
			// We use reflection to access the activePrompt field for testing
			java.lang.reflect.Field activePromptField = AcpAgentSession.class.getDeclaredField("activePrompt");
			activePromptField.setAccessible(true);
			@SuppressWarnings("unchecked")
			AtomicReference<Object> activePromptRef = (AtomicReference<Object>) activePromptField.get(session);

			// Create an ActivePrompt instance using reflection
			Class<?> activePromptClass = Class.forName(
					"com.agentclientprotocol.sdk.spec.AcpAgentSession$ActivePrompt");
			java.lang.reflect.Constructor<?> constructor = activePromptClass.getDeclaredConstructor(String.class,
					Object.class);
			constructor.setAccessible(true);
			Object activePrompt = constructor.newInstance("session-1", "existing-request-id");
			activePromptRef.set(activePrompt);

			// Verify active prompt is set
			assertThat(session.hasActivePrompt()).isTrue();

			// Set up client to receive response
			CountDownLatch responseLatch = new CountDownLatch(1);
			AtomicReference<AcpSchema.JSONRPCResponse> response = new AtomicReference<>();

			transportPair.clientTransport().connect(mono -> mono.doOnNext(msg -> {
				response.set((AcpSchema.JSONRPCResponse) msg);
				responseLatch.countDown();
			}).then(Mono.empty())).subscribe();

			Thread.sleep(50);

			// Send prompt request while another is "active"
			Map<String, Object> params = new HashMap<>();
			params.put("sessionId", "session-1");
			params.put("prompt", Collections.singletonList(new AcpSchema.TextContent("Hello")));
			AcpSchema.JSONRPCRequest request = new AcpSchema.JSONRPCRequest(AcpSchema.JSONRPC_VERSION, "1",
					AcpSchema.METHOD_SESSION_PROMPT, params);
			transportPair.clientTransport().sendMessage(request).block(TIMEOUT);

			// Wait for response
			assertThat(responseLatch.await(5, TimeUnit.SECONDS)).isTrue();

			// Should be rejected with error
			assertThat(response.get()).isNotNull();
			assertThat(response.get().error()).isNotNull();
			assertThat(response.get().error().code()).isEqualTo(-32000);
			assertThat(response.get().error().message()).contains("already an active prompt");
		}
		finally {
			transportPair.closeGracefully().block(TIMEOUT);
		}
	}

	@Test
	void hasActivePromptReturnsCorrectState() throws Exception {
		InMemoryTransportPair transportPair = InMemoryTransportPair.create();
		try {
			Map<String, AcpAgentSession.RequestHandler<?>> requestHandlers = Collections.singletonMap(AcpSchema.METHOD_SESSION_PROMPT, params -> Mono.just(new AcpSchema.PromptResponse(AcpSchema.StopReason.END_TURN)));

			AcpAgentSession session = new AcpAgentSession(TIMEOUT, transportPair.agentTransport(), requestHandlers,
					Collections.emptyMap());

			Thread.sleep(100);

			// Initially no active prompt
			assertThat(session.hasActivePrompt()).isFalse();
			assertThat(session.getActivePromptSessionId()).isNull();

			// Manually set active prompt using reflection to test the getter methods
			java.lang.reflect.Field activePromptField = AcpAgentSession.class.getDeclaredField("activePrompt");
			activePromptField.setAccessible(true);
			@SuppressWarnings("unchecked")
			AtomicReference<Object> activePromptRef = (AtomicReference<Object>) activePromptField.get(session);

			// Create an ActivePrompt instance using reflection
			Class<?> activePromptClass = Class.forName(
					"com.agentclientprotocol.sdk.spec.AcpAgentSession$ActivePrompt");
			java.lang.reflect.Constructor<?> constructor = activePromptClass.getDeclaredConstructor(String.class,
					Object.class);
			constructor.setAccessible(true);
			Object activePrompt = constructor.newInstance("session-1", "request-1");
			activePromptRef.set(activePrompt);

			// Now there should be an active prompt
			assertThat(session.hasActivePrompt()).isTrue();
			assertThat(session.getActivePromptSessionId()).isEqualTo("session-1");

			// Clear active prompt
			activePromptRef.set(null);

			// Active prompt should be cleared
			assertThat(session.hasActivePrompt()).isFalse();
			assertThat(session.getActivePromptSessionId()).isNull();
		}
		finally {
			transportPair.closeGracefully().block(TIMEOUT);
		}
	}

	@Test
	void closeGracefullyCompletes() throws Exception {
		InMemoryTransportPair transportPair = InMemoryTransportPair.create();

		AcpAgentSession session = new AcpAgentSession(TIMEOUT, transportPair.agentTransport(), Collections.emptyMap(), Collections.emptyMap());

		Thread.sleep(100);

		// Should complete without error
		session.closeGracefully().block(TIMEOUT);
		transportPair.closeGracefully().block(TIMEOUT);
	}

	@Test
	void handlerErrorReturnsJsonRpcError() throws Exception {
		InMemoryTransportPair transportPair = InMemoryTransportPair.create();
		try {
			// Create session with a handler that throws
			Map<String, AcpAgentSession.RequestHandler<?>> requestHandlers = Collections.singletonMap(AcpSchema.METHOD_INITIALIZE, params -> Mono.error(new RuntimeException("Handler error")));

			new AcpAgentSession(TIMEOUT, transportPair.agentTransport(), requestHandlers, Collections.emptyMap());

			Thread.sleep(100);

			CountDownLatch latch = new CountDownLatch(1);
			AtomicReference<AcpSchema.JSONRPCMessage> response = new AtomicReference<>();

			AcpSchema.JSONRPCRequest request = new AcpSchema.JSONRPCRequest(AcpSchema.JSONRPC_VERSION, "1",
					AcpSchema.METHOD_INITIALIZE, new AcpSchema.InitializeRequest(1, new AcpSchema.ClientCapabilities()));

			transportPair.clientTransport().connect(mono -> mono.doOnNext(msg -> {
				response.set(msg);
				latch.countDown();
			}).then(Mono.empty())).subscribe();

			Thread.sleep(50);
			transportPair.clientTransport().sendMessage(request).block(TIMEOUT);

			assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
			assertThat(response.get()).isInstanceOf(AcpSchema.JSONRPCResponse.class);
			AcpSchema.JSONRPCResponse jsonResponse = (AcpSchema.JSONRPCResponse) response.get();
			assertThat(jsonResponse.error()).isNotNull();
			assertThat(jsonResponse.error().code()).isEqualTo(-32603); // Internal error
			assertThat(jsonResponse.error().message()).isEqualTo("Handler error");
		}
		finally {
			transportPair.closeGracefully().block(TIMEOUT);
		}
	}

}
