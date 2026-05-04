/*
 * Copyright 2025-2025 the original author or authors.
 */

package com.agentclientprotocol.sdk.test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import com.agentclientprotocol.sdk.agent.AcpAgent;
import com.agentclientprotocol.sdk.agent.AcpAsyncAgent;
import com.agentclientprotocol.sdk.spec.AcpAgentTransport;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import reactor.core.publisher.Mono;

/**
 * A mock ACP agent for testing client implementations.
 *
 * <p>
 * This mock provides a configurable agent that:
 * <ul>
 * <li>Records all received requests for verification</li>
 * <li>Allows customizing responses via response providers</li>
 * <li>Supports synchronous waiting for specific operations</li>
 * </ul>
 *
 * <p>
 * Example usage:
 * <pre>{@code
 * InMemoryTransportPair transportPair = InMemoryTransportPair.create();
 * MockAcpAgent mockAgent = MockAcpAgent.builder(transportPair.agentTransport())
 *     .initializeResponse(new AcpSchema.InitializeResponse(1, new AcpSchema.AgentCapabilities(), Collections.emptyList()))
 *     .promptResponse(request -> new AcpSchema.PromptResponse(AcpSchema.StopReason.END_TURN))
 *     .build();
 *
 * mockAgent.start();
 *
 * // ... test client operations ...
 *
 * assertThat(mockAgent.getReceivedPrompts()).hasSize(1);
 * mockAgent.close();
 * }</pre>
 *
 * @author Mark Pollack
 */
public class MockAcpAgent {

	private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(10);

	private final AcpAsyncAgent delegate;

	private final List<AcpSchema.InitializeRequest> receivedInitRequests = new CopyOnWriteArrayList<>();

	private final List<AcpSchema.NewSessionRequest> receivedNewSessionRequests = new CopyOnWriteArrayList<>();

	private final List<AcpSchema.PromptRequest> receivedPrompts = new CopyOnWriteArrayList<>();

	private final List<AcpSchema.CancelNotification> receivedCancellations = new CopyOnWriteArrayList<>();

	private final AtomicReference<CountDownLatch> promptLatch = new AtomicReference<>(new CountDownLatch(0));

	private MockAcpAgent(AcpAsyncAgent delegate) {
		this.delegate = delegate;
	}

	/**
	 * Creates a builder for configuring the mock agent.
	 * @param transport The agent transport
	 * @return A new builder instance
	 */
	public static Builder builder(AcpAgentTransport transport) {
		return new Builder(transport);
	}

	/**
	 * Creates a simple mock agent with default responses.
	 * @param transport The agent transport
	 * @return A new mock agent with default behavior
	 */
	public static MockAcpAgent createDefault(AcpAgentTransport transport) {
		return builder(transport).build();
	}

	/**
	 * Starts the mock agent.
	 */
	public void start() {
		delegate.start().subscribe();
	}

	/**
	 * Sets a latch to wait for a specific number of prompts.
	 * @param count The number of prompts to wait for
	 */
	public void expectPrompts(int count) {
		promptLatch.set(new CountDownLatch(count));
	}

	/**
	 * Waits for expected prompts to be received.
	 * @param timeout The maximum time to wait
	 * @return true if all expected prompts were received
	 * @throws InterruptedException if interrupted while waiting
	 */
	public boolean awaitPrompts(Duration timeout) throws InterruptedException {
		return promptLatch.get().await(timeout.toMillis(), TimeUnit.MILLISECONDS);
	}

	/**
	 * Gets all received initialize requests.
	 * @return The list of initialize requests
	 */
	public List<AcpSchema.InitializeRequest> getReceivedInitRequests() {
		return Collections.unmodifiableList(new ArrayList<>(receivedInitRequests));
	}

	/**
	 * Gets all received new session requests.
	 * @return The list of new session requests
	 */
	public List<AcpSchema.NewSessionRequest> getReceivedNewSessionRequests() {
		return Collections.unmodifiableList(new ArrayList<>(receivedNewSessionRequests));
	}

	/**
	 * Gets all received prompts.
	 * @return The list of prompts
	 */
	public List<AcpSchema.PromptRequest> getReceivedPrompts() {
		return Collections.unmodifiableList(new ArrayList<>(receivedPrompts));
	}

	/**
	 * Gets all received cancellation notifications.
	 * @return The list of cancellations
	 */
	public List<AcpSchema.CancelNotification> getReceivedCancellations() {
		return Collections.unmodifiableList(new ArrayList<>(receivedCancellations));
	}

	/**
	 * Sends a session update notification to the client.
	 * @param sessionId The session ID
	 * @param update The update to send
	 */
	public void sendSessionUpdate(String sessionId, AcpSchema.SessionUpdate update) {
		delegate.sendSessionUpdate(sessionId, update).block(DEFAULT_TIMEOUT);
	}

	/**
	 * Returns the underlying async agent.
	 * @return The async agent
	 */
	public AcpAsyncAgent async() {
		return delegate;
	}

	/**
	 * Closes the mock agent gracefully.
	 */
	public void closeGracefully() {
		delegate.closeGracefully().block(DEFAULT_TIMEOUT);
	}

	/**
	 * Closes the mock agent immediately.
	 */
	public void close() {
		delegate.close();
	}

	/**
	 * Builder for MockAcpAgent.
	 */
	public static class Builder {

		private final AcpAgentTransport transport;

		private AcpSchema.InitializeResponse initializeResponse;

		private AcpSchema.NewSessionResponse newSessionResponse;

		private Function<AcpSchema.PromptRequest, AcpSchema.PromptResponse> promptResponseProvider;

		private Duration requestTimeout = DEFAULT_TIMEOUT;

		private Builder(AcpAgentTransport transport) {
			this.transport = transport;
			// Set defaults
			this.initializeResponse = new AcpSchema.InitializeResponse(1, new AcpSchema.AgentCapabilities(), Collections.emptyList());
			this.newSessionResponse = new AcpSchema.NewSessionResponse("mock-session", null, null);
			this.promptResponseProvider = request -> new AcpSchema.PromptResponse(AcpSchema.StopReason.END_TURN);
		}

		/**
		 * Sets the response for initialize requests.
		 * @param response The initialize response
		 * @return This builder
		 */
		public Builder initializeResponse(AcpSchema.InitializeResponse response) {
			this.initializeResponse = response;
			return this;
		}

		/**
		 * Sets the response for new session requests.
		 * @param response The new session response
		 * @return This builder
		 */
		public Builder newSessionResponse(AcpSchema.NewSessionResponse response) {
			this.newSessionResponse = response;
			return this;
		}

		/**
		 * Sets a function to generate prompt responses.
		 * @param provider The prompt response provider
		 * @return This builder
		 */
		public Builder promptResponse(Function<AcpSchema.PromptRequest, AcpSchema.PromptResponse> provider) {
			this.promptResponseProvider = provider;
			return this;
		}

		/**
		 * Sets the request timeout.
		 * @param timeout The timeout
		 * @return This builder
		 */
		public Builder requestTimeout(Duration timeout) {
			this.requestTimeout = timeout;
			return this;
		}

		/**
		 * Builds the mock agent.
		 * @return The configured mock agent
		 */
		public MockAcpAgent build() {
			MockAcpAgent mockAgent = new MockAcpAgent(null);

			AcpAsyncAgent delegate = AcpAgent.async(transport)
				.requestTimeout(requestTimeout)
				.initializeHandler(request -> {
					mockAgent.receivedInitRequests.add(request);
					return Mono.just(initializeResponse);
				})
				.newSessionHandler(request -> {
					mockAgent.receivedNewSessionRequests.add(request);
					return Mono.just(newSessionResponse);
				})
				.promptHandler((request, updater) -> {
					mockAgent.receivedPrompts.add(request);
					mockAgent.promptLatch.get().countDown();
					return Mono.just(promptResponseProvider.apply(request));
				})
				.cancelHandler(notification -> {
					mockAgent.receivedCancellations.add(notification);
					return Mono.empty();
				})
				.build();

			// Replace the delegate using reflection (a bit ugly but avoids circular reference issues)
			try {
				java.lang.reflect.Field field = MockAcpAgent.class.getDeclaredField("delegate");
				field.setAccessible(true);
				field.set(mockAgent, delegate);
			}
			catch (Exception e) {
				throw new RuntimeException("Failed to set delegate", e);
			}

			return mockAgent;
		}

	}

}
