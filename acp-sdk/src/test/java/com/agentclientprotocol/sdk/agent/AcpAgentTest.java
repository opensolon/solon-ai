/*
 * Copyright 2025-2025 the original author or authors.
 */

package com.agentclientprotocol.sdk.agent;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import com.agentclientprotocol.sdk.spec.AcpSchema;
import com.agentclientprotocol.sdk.test.InMemoryTransportPair;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.Collections;

/**
 * Unit tests for {@link AcpAgent} factory and the high-level agent API.
 */
class AcpAgentTest {

	private static final Duration TIMEOUT = Duration.ofSeconds(5);

	@Test
	void asyncBuilderCreatesAgent() {
		InMemoryTransportPair transportPair = InMemoryTransportPair.create();
		try {
			AcpAsyncAgent agent = AcpAgent.async(transportPair.agentTransport())
				.requestTimeout(Duration.ofSeconds(30))
				.initializeHandler(
						request -> Mono.just(new AcpSchema.InitializeResponse(1, new AcpSchema.AgentCapabilities(),
								Collections.emptyList())))
				.newSessionHandler(request -> Mono.just(new AcpSchema.NewSessionResponse("session-1", null, null)))
				.promptHandler((request, updater) -> Mono
					.just(new AcpSchema.PromptResponse(AcpSchema.StopReason.END_TURN)))
				.build();

			assertThat(agent).isNotNull();
			assertThat(agent).isInstanceOf(DefaultAcpAsyncAgent.class);

			agent.close();
		}
		finally {
			transportPair.closeGracefully().block(TIMEOUT);
		}
	}

	@Test
	void syncBuilderCreatesAgent() {
		InMemoryTransportPair transportPair = InMemoryTransportPair.create();
		try {
			// Sync builder now uses sync handler interfaces (plain return values, no Mono)
			AcpSyncAgent agent = AcpAgent.sync(transportPair.agentTransport())
				.requestTimeout(Duration.ofSeconds(30))
				.initializeHandler(
						request -> new AcpSchema.InitializeResponse(1, new AcpSchema.AgentCapabilities(), Collections.emptyList()))
				.newSessionHandler(request -> new AcpSchema.NewSessionResponse("session-1", null, null))
				.promptHandler((request, updater) -> new AcpSchema.PromptResponse(AcpSchema.StopReason.END_TURN))
				.build();

			assertThat(agent).isNotNull();
			assertThat(agent.async()).isNotNull();

			agent.close();
		}
		finally {
			transportPair.closeGracefully().block(TIMEOUT);
		}
	}

	@Test
	void agentHandlesInitializeRequest() throws Exception {
		InMemoryTransportPair transportPair = InMemoryTransportPair.create();
		try {
			AtomicReference<AcpSchema.InitializeRequest> receivedRequest = new AtomicReference<>();

			AcpAsyncAgent agent = AcpAgent.async(transportPair.agentTransport())
				.initializeHandler(request -> {
					receivedRequest.set(request);
					return Mono.just(new AcpSchema.InitializeResponse(1,
							new AcpSchema.AgentCapabilities(true, null, null), Collections.emptyList()));
				})
				.build();

			agent.start().block(TIMEOUT);
			Thread.sleep(100);

			// Set up client
			CountDownLatch responseLatch = new CountDownLatch(1);
			AtomicReference<AcpSchema.JSONRPCMessage> response = new AtomicReference<>();

			transportPair.clientTransport().connect(mono -> mono.doOnNext(msg -> {
				response.set(msg);
				responseLatch.countDown();
			}).then(Mono.empty())).subscribe();

			Thread.sleep(50);

			// Send initialize request
			AcpSchema.JSONRPCRequest request = new AcpSchema.JSONRPCRequest(AcpSchema.JSONRPC_VERSION, "1",
					AcpSchema.METHOD_INITIALIZE, new AcpSchema.InitializeRequest(1, new AcpSchema.ClientCapabilities()));

			transportPair.clientTransport().sendMessage(request).block(TIMEOUT);

			assertThat(responseLatch.await(5, TimeUnit.SECONDS)).isTrue();
			assertThat(response.get()).isInstanceOf(AcpSchema.JSONRPCResponse.class);
			AcpSchema.JSONRPCResponse jsonResponse = (AcpSchema.JSONRPCResponse) response.get();
			assertThat(jsonResponse.error()).isNull();
			assertThat(receivedRequest.get()).isNotNull();
			assertThat(receivedRequest.get().protocolVersion()).isEqualTo(1);

			agent.closeGracefully().block(TIMEOUT);
		}
		finally {
			transportPair.closeGracefully().block(TIMEOUT);
		}
	}

	@Test
	void agentHandlesNewSessionRequest() throws Exception {
		InMemoryTransportPair transportPair = InMemoryTransportPair.create();
		try {
			AtomicReference<AcpSchema.NewSessionRequest> receivedRequest = new AtomicReference<>();

			AcpAsyncAgent agent = AcpAgent.async(transportPair.agentTransport())
				.newSessionHandler(request -> {
					receivedRequest.set(request);
					return Mono.just(new AcpSchema.NewSessionResponse("session-123", null, null));
				})
				.build();

			agent.start().block(TIMEOUT);
			Thread.sleep(100);

			// Set up client
			CountDownLatch responseLatch = new CountDownLatch(1);
			AtomicReference<AcpSchema.JSONRPCMessage> response = new AtomicReference<>();

			transportPair.clientTransport().connect(mono -> mono.doOnNext(msg -> {
				response.set(msg);
				responseLatch.countDown();
			}).then(Mono.empty())).subscribe();

			Thread.sleep(50);

			// Send new session request
			AcpSchema.JSONRPCRequest request = new AcpSchema.JSONRPCRequest(AcpSchema.JSONRPC_VERSION, "1",
					AcpSchema.METHOD_SESSION_NEW, new AcpSchema.NewSessionRequest("/workspace", Collections.emptyList()));

			transportPair.clientTransport().sendMessage(request).block(TIMEOUT);

			assertThat(responseLatch.await(5, TimeUnit.SECONDS)).isTrue();
			assertThat(response.get()).isInstanceOf(AcpSchema.JSONRPCResponse.class);
			AcpSchema.JSONRPCResponse jsonResponse = (AcpSchema.JSONRPCResponse) response.get();
			assertThat(jsonResponse.error()).isNull();
			assertThat(receivedRequest.get()).isNotNull();
			assertThat(receivedRequest.get().cwd()).isEqualTo("/workspace");

			agent.closeGracefully().block(TIMEOUT);
		}
		finally {
			transportPair.closeGracefully().block(TIMEOUT);
		}
	}

	@Test
	void agentHandlesPromptRequest() throws Exception {
		InMemoryTransportPair transportPair = InMemoryTransportPair.create();
		try {
			AtomicReference<AcpSchema.PromptRequest> receivedRequest = new AtomicReference<>();

			AcpAsyncAgent agent = AcpAgent.async(transportPair.agentTransport())
				.promptHandler((request, updater) -> {
					receivedRequest.set(request);
					return Mono.just(new AcpSchema.PromptResponse(AcpSchema.StopReason.END_TURN));
				})
				.build();

			agent.start().block(TIMEOUT);
			Thread.sleep(100);

			// Set up client
			CountDownLatch responseLatch = new CountDownLatch(1);
			AtomicReference<AcpSchema.JSONRPCMessage> response = new AtomicReference<>();

			transportPair.clientTransport().connect(mono -> mono.doOnNext(msg -> {
				response.set(msg);
				responseLatch.countDown();
			}).then(Mono.empty())).subscribe();

			Thread.sleep(50);

			// Send prompt request
			AcpSchema.JSONRPCRequest request = new AcpSchema.JSONRPCRequest(AcpSchema.JSONRPC_VERSION, "1",
					AcpSchema.METHOD_SESSION_PROMPT,
					new AcpSchema.PromptRequest("session-1", Collections.singletonList(new AcpSchema.TextContent("Hello"))));

			transportPair.clientTransport().sendMessage(request).block(TIMEOUT);

			assertThat(responseLatch.await(5, TimeUnit.SECONDS)).isTrue();
			assertThat(response.get()).isInstanceOf(AcpSchema.JSONRPCResponse.class);
			AcpSchema.JSONRPCResponse jsonResponse = (AcpSchema.JSONRPCResponse) response.get();
			assertThat(jsonResponse.error()).isNull();
			assertThat(receivedRequest.get()).isNotNull();
			assertThat(receivedRequest.get().sessionId()).isEqualTo("session-1");

			agent.closeGracefully().block(TIMEOUT);
		}
		finally {
			transportPair.closeGracefully().block(TIMEOUT);
		}
	}

	@Test
	void agentHandlesCancelNotification() throws Exception {
		InMemoryTransportPair transportPair = InMemoryTransportPair.create();
		try {
			AtomicReference<AcpSchema.CancelNotification> receivedNotification = new AtomicReference<>();
			CountDownLatch notificationLatch = new CountDownLatch(1);

			AcpAsyncAgent agent = AcpAgent.async(transportPair.agentTransport())
				.cancelHandler(notification -> {
					receivedNotification.set(notification);
					notificationLatch.countDown();
					return Mono.empty();
				})
				.build();

			agent.start().block(TIMEOUT);
			Thread.sleep(100);

			// Set up client
			transportPair.clientTransport().connect(mono -> mono.then(Mono.empty())).subscribe();

			Thread.sleep(50);

			// Send cancel notification
			AcpSchema.JSONRPCNotification notification = new AcpSchema.JSONRPCNotification(AcpSchema.JSONRPC_VERSION,
					AcpSchema.METHOD_SESSION_CANCEL, new AcpSchema.CancelNotification("session-1"));

			transportPair.clientTransport().sendMessage(notification).block(TIMEOUT);

			assertThat(notificationLatch.await(5, TimeUnit.SECONDS)).isTrue();
			assertThat(receivedNotification.get()).isNotNull();
			assertThat(receivedNotification.get().sessionId()).isEqualTo("session-1");

			agent.closeGracefully().block(TIMEOUT);
		}
		finally {
			transportPair.closeGracefully().block(TIMEOUT);
		}
	}

	@Test
	void syncAgentDelegatesAsyncOperations() {
		InMemoryTransportPair transportPair = InMemoryTransportPair.create();
		try {
			AtomicReference<AcpSchema.InitializeRequest> receivedRequest = new AtomicReference<>();

			// Sync builder now uses sync handler interfaces (plain return values, no Mono)
			AcpSyncAgent agent = AcpAgent.sync(transportPair.agentTransport())
				.initializeHandler(request -> {
					receivedRequest.set(request);
					return new AcpSchema.InitializeResponse(1, new AcpSchema.AgentCapabilities(), Collections.emptyList());
				})
				.build();

			// Verify async() returns the underlying agent
			assertThat(agent.async()).isNotNull();

			agent.close();
		}
		finally {
			transportPair.closeGracefully().block(TIMEOUT);
		}
	}

	@Test
	void agentCloseGracefullyCompletesWithoutError() throws Exception {
		InMemoryTransportPair transportPair = InMemoryTransportPair.create();

		AcpAsyncAgent agent = AcpAgent.async(transportPair.agentTransport()).build();

		agent.start().block(TIMEOUT);
		Thread.sleep(100);

		// Should complete without error
		agent.closeGracefully().block(TIMEOUT);
		transportPair.closeGracefully().block(TIMEOUT);
	}

	@Test
	void syncAgentGetClientCapabilitiesReturnsNullBeforeInitialization() {
		InMemoryTransportPair transportPair = InMemoryTransportPair.create();
		try {
			AcpSyncAgent agent = AcpAgent.sync(transportPair.agentTransport())
				.initializeHandler(
						request -> new AcpSchema.InitializeResponse(1, new AcpSchema.AgentCapabilities(), Collections.emptyList()))
				.build();

			// Before initialization, capabilities should be null
			assertThat(agent.getClientCapabilities()).isNull();

			agent.close();
		}
		finally {
			transportPair.closeGracefully().block(TIMEOUT);
		}
	}

	@Test
	void asyncAgentGetClientCapabilitiesReturnsNullBeforeInitialization() {
		InMemoryTransportPair transportPair = InMemoryTransportPair.create();
		try {
			AcpAsyncAgent agent = AcpAgent.async(transportPair.agentTransport())
				.initializeHandler(request -> Mono
					.just(new AcpSchema.InitializeResponse(1, new AcpSchema.AgentCapabilities(), Collections.emptyList())))
				.build();

			// Before initialization, capabilities should be null
			assertThat(agent.getClientCapabilities()).isNull();

			agent.close();
		}
		finally {
			transportPair.closeGracefully().block(TIMEOUT);
		}
	}

}
