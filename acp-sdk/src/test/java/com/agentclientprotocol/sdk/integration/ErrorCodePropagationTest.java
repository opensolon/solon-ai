/*
 * Copyright 2025-2025 the original author or authors.
 */

package com.agentclientprotocol.sdk.integration;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import com.agentclientprotocol.sdk.agent.AcpAgent;
import com.agentclientprotocol.sdk.agent.AcpAsyncAgent;
import com.agentclientprotocol.sdk.client.AcpAsyncClient;
import com.agentclientprotocol.sdk.client.AcpClient;
import com.agentclientprotocol.sdk.error.AcpErrorCodes;
import com.agentclientprotocol.sdk.error.AcpProtocolException;
import com.agentclientprotocol.sdk.spec.AcpAgentSession;
import com.agentclientprotocol.sdk.spec.AcpClientSession;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import com.agentclientprotocol.sdk.test.InMemoryTransportPair;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import java.util.Collections;

/**
 * Tests for error code propagation from agent to client.
 *
 * <p>
 * These tests verify that when an agent handler throws an exception with a specific
 * error code (e.g., INVALID_PARAMS), the client receives that exact error code,
 * not a wrapped INTERNAL_ERROR.
 * </p>
 *
 * @author Mark Pollack
 */
class ErrorCodePropagationTest {

	private static final Duration TIMEOUT = Duration.ofSeconds(5);

	private InMemoryTransportPair transportPair;

	@AfterEach
	void tearDown() {
		if (transportPair != null) {
			transportPair.closeGracefully().block(TIMEOUT);
			transportPair = null;
		}
	}

	/**
	 * Test that INVALID_PARAMS error code is preserved when thrown by agent.
	 * <p>
	 * BUG: Currently the error code is wrapped in INTERNAL_ERROR (-32603) instead of
	 * propagating the original INVALID_PARAMS (-32602) code.
	 */
	@Test
	void invalidParamsErrorCodeIsPreserved() throws Exception {
		transportPair = InMemoryTransportPair.create();

		// Build agent that throws INVALID_PARAMS error for prompts
		AcpAsyncAgent agent = AcpAgent.async(transportPair.agentTransport())
			.requestTimeout(TIMEOUT)
			.initializeHandler(request -> Mono
				.just(new AcpSchema.InitializeResponse(1, new AcpSchema.AgentCapabilities(), Collections.emptyList())))
			.newSessionHandler(request -> Mono.just(new AcpSchema.NewSessionResponse("session-123", null, null)))
			.promptHandler((request, updater) -> {
				// Throw INVALID_PARAMS error when prompt is received
				return Mono.error(new AcpProtocolException(AcpErrorCodes.INVALID_PARAMS, "Invalid prompt content"));
			})
			.build();

		// Build client
		AcpAsyncClient client = AcpClient.async(transportPair.clientTransport()).requestTimeout(TIMEOUT).build();

		// Start agent and initialize client
		agent.start().subscribe();
		Thread.sleep(100);
		client.initialize(new AcpSchema.InitializeRequest(1, new AcpSchema.ClientCapabilities())).block(TIMEOUT);
		client.newSession(new AcpSchema.NewSessionRequest("/workspace", Collections.emptyList())).block(TIMEOUT);

		// Send prompt - should receive INVALID_PARAMS error
		assertThatThrownBy(() -> {
			client.prompt(new AcpSchema.PromptRequest("session-123", Collections.singletonList(new AcpSchema.TextContent("test"))))
				.block(TIMEOUT);
		}).isInstanceOf(AcpClientSession.AcpError.class).satisfies(ex -> {
			AcpClientSession.AcpError acpError = (AcpClientSession.AcpError) ex;
			// BUG: Currently returns -32603 (INTERNAL_ERROR) instead of -32602 (INVALID_PARAMS)
			assertThat(acpError.getCode()).as("Error code should be INVALID_PARAMS, not wrapped in INTERNAL_ERROR")
				.isEqualTo(AcpErrorCodes.INVALID_PARAMS);
		});

		// Cleanup
		client.closeGracefully().block(TIMEOUT);
		agent.closeGracefully().block(TIMEOUT);
	}

	/**
	 * Test that METHOD_NOT_FOUND error code is preserved when thrown by agent.
	 */
	@Test
	void methodNotFoundErrorCodeIsPreserved() throws Exception {
		transportPair = InMemoryTransportPair.create();

		// Build agent that throws METHOD_NOT_FOUND error for prompts
		AcpAsyncAgent agent = AcpAgent.async(transportPair.agentTransport())
			.requestTimeout(TIMEOUT)
			.initializeHandler(request -> Mono
				.just(new AcpSchema.InitializeResponse(1, new AcpSchema.AgentCapabilities(), Collections.emptyList())))
			.newSessionHandler(request -> Mono.just(new AcpSchema.NewSessionResponse("session-123", null, null)))
			.promptHandler((request, updater) -> {
				// Throw METHOD_NOT_FOUND error
				return Mono.error(new AcpProtocolException(AcpErrorCodes.METHOD_NOT_FOUND, "Method not available"));
			})
			.build();

		// Build client
		AcpAsyncClient client = AcpClient.async(transportPair.clientTransport()).requestTimeout(TIMEOUT).build();

		// Start agent and initialize client
		agent.start().subscribe();
		Thread.sleep(100);
		client.initialize(new AcpSchema.InitializeRequest(1, new AcpSchema.ClientCapabilities())).block(TIMEOUT);
		client.newSession(new AcpSchema.NewSessionRequest("/workspace", Collections.emptyList())).block(TIMEOUT);

		// Send prompt - should receive METHOD_NOT_FOUND error
		assertThatThrownBy(() -> {
			client.prompt(new AcpSchema.PromptRequest("session-123", Collections.singletonList(new AcpSchema.TextContent("test"))))
				.block(TIMEOUT);
		}).isInstanceOf(AcpClientSession.AcpError.class).satisfies(ex -> {
			AcpClientSession.AcpError acpError = (AcpClientSession.AcpError) ex;
			assertThat(acpError.getCode()).as("Error code should be METHOD_NOT_FOUND, not wrapped in INTERNAL_ERROR")
				.isEqualTo(AcpErrorCodes.METHOD_NOT_FOUND);
		});

		// Cleanup
		client.closeGracefully().block(TIMEOUT);
		agent.closeGracefully().block(TIMEOUT);
	}

	/**
	 * Test that ACP-specific error codes (like CONCURRENT_PROMPT) are preserved.
	 */
	@Test
	void acpSpecificErrorCodeIsPreserved() throws Exception {
		transportPair = InMemoryTransportPair.create();

		// Build agent that throws CAPABILITY_NOT_SUPPORTED error
		AcpAsyncAgent agent = AcpAgent.async(transportPair.agentTransport())
			.requestTimeout(TIMEOUT)
			.initializeHandler(request -> Mono
				.just(new AcpSchema.InitializeResponse(1, new AcpSchema.AgentCapabilities(), Collections.emptyList())))
			.newSessionHandler(request -> Mono.just(new AcpSchema.NewSessionResponse("session-123", null, null)))
			.promptHandler((request, updater) -> {
				// Throw CAPABILITY_NOT_SUPPORTED error
				return Mono.error(
						new AcpProtocolException(AcpErrorCodes.CAPABILITY_NOT_SUPPORTED, "Terminal not supported"));
			})
			.build();

		// Build client
		AcpAsyncClient client = AcpClient.async(transportPair.clientTransport()).requestTimeout(TIMEOUT).build();

		// Start agent and initialize client
		agent.start().subscribe();
		Thread.sleep(100);
		client.initialize(new AcpSchema.InitializeRequest(1, new AcpSchema.ClientCapabilities())).block(TIMEOUT);
		client.newSession(new AcpSchema.NewSessionRequest("/workspace", Collections.emptyList())).block(TIMEOUT);

		// Send prompt - should receive CAPABILITY_NOT_SUPPORTED error
		assertThatThrownBy(() -> {
			client.prompt(new AcpSchema.PromptRequest("session-123", Collections.singletonList(new AcpSchema.TextContent("test"))))
				.block(TIMEOUT);
		}).isInstanceOf(AcpClientSession.AcpError.class).satisfies(ex -> {
			AcpClientSession.AcpError acpError = (AcpClientSession.AcpError) ex;
			assertThat(acpError.getCode())
				.as("Error code should be CAPABILITY_NOT_SUPPORTED, not wrapped in INTERNAL_ERROR")
				.isEqualTo(AcpErrorCodes.CAPABILITY_NOT_SUPPORTED);
		});

		// Cleanup
		client.closeGracefully().block(TIMEOUT);
		agent.closeGracefully().block(TIMEOUT);
	}

	/**
	 * Test that client-side handler errors (e.g., file not found) also preserve error codes.
	 * When client throws AcpProtocolException with a specific code, agent should receive that code.
	 */
	@Test
	void clientHandlerErrorCodeIsPreserved() throws Exception {
		transportPair = InMemoryTransportPair.create();

		AtomicReference<AcpAsyncAgent> agentRef = new AtomicReference<>();
		AtomicReference<AcpAgentSession.AcpError> receivedError = new AtomicReference<>();
		CountDownLatch errorLatch = new CountDownLatch(1);

		// Build agent that will request a file read
		AcpAsyncAgent agent = AcpAgent.async(transportPair.agentTransport())
			.requestTimeout(TIMEOUT)
			.initializeHandler(request -> Mono
				.just(new AcpSchema.InitializeResponse(1, new AcpSchema.AgentCapabilities(), Collections.emptyList())))
			.newSessionHandler(request -> Mono.just(new AcpSchema.NewSessionResponse("session-123", null, null)))
			.promptHandler((request, updater) -> {
				// Request file read from client - client will throw INVALID_PARAMS
				return agentRef.get()
					.readTextFile(new AcpSchema.ReadTextFileRequest("session-123", "/nonexistent.txt", null, null))
					.onErrorResume(error -> {
						if (error instanceof AcpAgentSession.AcpError) {
							AcpAgentSession.AcpError acpError = (AcpAgentSession.AcpError) error;
							receivedError.set(acpError);
							errorLatch.countDown();
						}
						return Mono.just(new AcpSchema.ReadTextFileResponse(""));
					})
					.then(Mono.just(new AcpSchema.PromptResponse(AcpSchema.StopReason.END_TURN)));
			})
			.build();
		agentRef.set(agent);

		// Build client that throws INVALID_PARAMS for file reads
		AcpAsyncClient client = AcpClient.async(transportPair.clientTransport())
			.requestTimeout(TIMEOUT)
			.readTextFileHandler((AcpSchema.ReadTextFileRequest request) -> {
				// Throw INVALID_PARAMS to simulate validation error
				return Mono
					.error(new AcpProtocolException(AcpErrorCodes.INVALID_PARAMS, "Invalid file path: " + request.path()));
			})
			.build();

		// Start agent and initialize
		agent.start().subscribe();
		Thread.sleep(100);
		AcpSchema.FileSystemCapability fsCaps = new AcpSchema.FileSystemCapability(true, false);
		AcpSchema.ClientCapabilities clientCaps = new AcpSchema.ClientCapabilities(fsCaps, false);
		client.initialize(new AcpSchema.InitializeRequest(1, clientCaps)).block(TIMEOUT);
		client.newSession(new AcpSchema.NewSessionRequest("/workspace", Collections.emptyList())).block(TIMEOUT);

		// Send prompt which triggers file read
		client.prompt(new AcpSchema.PromptRequest("session-123", Collections.singletonList(new AcpSchema.TextContent("test"))))
			.block(TIMEOUT);

		assertThat(errorLatch.await(5, TimeUnit.SECONDS)).isTrue();
		assertThat(receivedError.get()).isNotNull();
		// Agent should receive INVALID_PARAMS, not wrapped in INTERNAL_ERROR
		assertThat(receivedError.get().getCode())
			.as("Client handler error code should be preserved, not wrapped in INTERNAL_ERROR")
			.isEqualTo(AcpErrorCodes.INVALID_PARAMS);

		// Cleanup
		client.closeGracefully().block(TIMEOUT);
		agent.closeGracefully().block(TIMEOUT);
	}

	/**
	 * Test that non-AcpProtocolException errors are correctly wrapped in INTERNAL_ERROR.
	 * This is the expected behavior for unexpected exceptions.
	 */
	@Test
	void unexpectedExceptionGetsWrappedInInternalError() throws Exception {
		transportPair = InMemoryTransportPair.create();

		// Build agent that throws a plain RuntimeException
		AcpAsyncAgent agent = AcpAgent.async(transportPair.agentTransport())
			.requestTimeout(TIMEOUT)
			.initializeHandler(request -> Mono
				.just(new AcpSchema.InitializeResponse(1, new AcpSchema.AgentCapabilities(), Collections.emptyList())))
			.newSessionHandler(request -> Mono.just(new AcpSchema.NewSessionResponse("session-123", null, null)))
			.promptHandler((request, updater) -> {
				// Throw a plain exception (not AcpProtocolException)
				return Mono.error(new RuntimeException("Unexpected database error"));
			})
			.build();

		// Build client
		AcpAsyncClient client = AcpClient.async(transportPair.clientTransport()).requestTimeout(TIMEOUT).build();

		// Start agent and initialize client
		agent.start().subscribe();
		Thread.sleep(100);
		client.initialize(new AcpSchema.InitializeRequest(1, new AcpSchema.ClientCapabilities())).block(TIMEOUT);
		client.newSession(new AcpSchema.NewSessionRequest("/workspace", Collections.emptyList())).block(TIMEOUT);

		// Send prompt - should receive INTERNAL_ERROR (this is correct behavior)
		assertThatThrownBy(() -> {
			client.prompt(new AcpSchema.PromptRequest("session-123", Collections.singletonList(new AcpSchema.TextContent("test"))))
				.block(TIMEOUT);
		}).isInstanceOf(AcpClientSession.AcpError.class).satisfies(ex -> {
			AcpClientSession.AcpError acpError = (AcpClientSession.AcpError) ex;
			// This SHOULD be INTERNAL_ERROR - unexpected exceptions should be wrapped
			assertThat(acpError.getCode()).as("Unexpected exceptions should be wrapped in INTERNAL_ERROR")
				.isEqualTo(AcpErrorCodes.INTERNAL_ERROR);
		});

		// Cleanup
		client.closeGracefully().block(TIMEOUT);
		agent.closeGracefully().block(TIMEOUT);
	}

}
