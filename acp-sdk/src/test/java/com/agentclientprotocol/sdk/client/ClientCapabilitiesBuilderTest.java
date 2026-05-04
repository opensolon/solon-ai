/*
 * Copyright 2025-2025 the original author or authors.
 */

package com.agentclientprotocol.sdk.client;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import com.agentclientprotocol.sdk.agent.AcpAgent;
import com.agentclientprotocol.sdk.agent.AcpAsyncAgent;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import com.agentclientprotocol.sdk.spec.AcpSchema.ClientCapabilities;
import com.agentclientprotocol.sdk.spec.AcpSchema.FileSystemCapability;
import com.agentclientprotocol.sdk.test.InMemoryTransportPair;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.Collections;

/**
 * Tests for the clientCapabilities() builder method.
 *
 * <p>
 * These tests verify that the clientCapabilities set via the builder are properly
 * passed to the agent during initialization.
 * </p>
 *
 * @author Mark Pollack
 */
class ClientCapabilitiesBuilderTest {

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
	 * Test that clientCapabilities set via the builder are sent to the agent.
	 * <p>
	 * BUG: Currently the clientCapabilities() builder method is ignored - the agent
	 * receives default capabilities instead of the custom ones configured.
	 */
	@Test
	void clientCapabilitiesBuilderMethodSendsCapabilitiesToAgent() throws Exception {
		transportPair = InMemoryTransportPair.create();

		AtomicReference<ClientCapabilities> receivedCapabilities = new AtomicReference<>();
		CountDownLatch capabilitiesLatch = new CountDownLatch(1);

		// Build agent that captures the client capabilities
		AcpAsyncAgent agent = AcpAgent.async(transportPair.agentTransport())
			.requestTimeout(TIMEOUT)
			.initializeHandler(request -> {
				receivedCapabilities.set(request.clientCapabilities());
				capabilitiesLatch.countDown();
				return Mono.just(new AcpSchema.InitializeResponse(1, new AcpSchema.AgentCapabilities(), Collections.emptyList()));
			})
			.build();

		// Create custom client capabilities with file system and terminal enabled
		FileSystemCapability fsCaps = new FileSystemCapability(true, true);
		ClientCapabilities customCaps = new ClientCapabilities(fsCaps, true);

		// Build client with custom capabilities via builder
		AcpAsyncClient client = AcpClient.async(transportPair.clientTransport())
			.requestTimeout(TIMEOUT)
			.clientCapabilities(customCaps) // This should be sent to agent
			.build();

		// Start agent
		agent.start().subscribe();
		Thread.sleep(100);

		// Initialize client using no-arg method (should use builder capabilities)
		client.initialize().block(TIMEOUT);

		// Verify agent received our custom capabilities
		assertThat(capabilitiesLatch.await(5, TimeUnit.SECONDS)).isTrue();
		assertThat(receivedCapabilities.get()).isNotNull();

		// BUG: These assertions currently FAIL because builder capabilities are ignored
		assertThat(receivedCapabilities.get().fs())
			.as("File system capabilities should be set from builder")
			.isNotNull();
		assertThat(receivedCapabilities.get().fs().readTextFile())
			.as("readTextFile should be true from builder")
			.isTrue();
		assertThat(receivedCapabilities.get().fs().writeTextFile())
			.as("writeTextFile should be true from builder")
			.isTrue();
		assertThat(receivedCapabilities.get().terminal())
			.as("terminal should be true from builder")
			.isTrue();

		// Cleanup
		client.closeGracefully().block(TIMEOUT);
		agent.closeGracefully().block(TIMEOUT);
	}

	/**
	 * Test that initialize(request) still works and overrides builder capabilities.
	 * This is the workaround currently used in tutorial Module 17.
	 */
	@Test
	void initializeWithRequestOverridesBuilderCapabilities() throws Exception {
		transportPair = InMemoryTransportPair.create();

		AtomicReference<ClientCapabilities> receivedCapabilities = new AtomicReference<>();
		CountDownLatch capabilitiesLatch = new CountDownLatch(1);

		// Build agent
		AcpAsyncAgent agent = AcpAgent.async(transportPair.agentTransport())
			.requestTimeout(TIMEOUT)
			.initializeHandler(request -> {
				receivedCapabilities.set(request.clientCapabilities());
				capabilitiesLatch.countDown();
				return Mono.just(new AcpSchema.InitializeResponse(1, new AcpSchema.AgentCapabilities(), Collections.emptyList()));
			})
			.build();

		// Create different capabilities for builder vs initialize request
		FileSystemCapability builderFs = new FileSystemCapability(false, false);
		ClientCapabilities builderCaps = new ClientCapabilities(builderFs, false);

		FileSystemCapability requestFs = new FileSystemCapability(true, true);
		ClientCapabilities requestCaps = new ClientCapabilities(requestFs, true);

		// Build client with builder capabilities
		AcpAsyncClient client = AcpClient.async(transportPair.clientTransport())
			.requestTimeout(TIMEOUT)
			.clientCapabilities(builderCaps)
			.build();

		// Start agent
		agent.start().subscribe();
		Thread.sleep(100);

		// Initialize with explicit request (should override builder)
		client.initialize(new AcpSchema.InitializeRequest(1, requestCaps)).block(TIMEOUT);

		// Verify agent received the request capabilities, not builder capabilities
		assertThat(capabilitiesLatch.await(5, TimeUnit.SECONDS)).isTrue();
		assertThat(receivedCapabilities.get().fs().readTextFile())
			.as("Should receive request capabilities, not builder")
			.isTrue();
		assertThat(receivedCapabilities.get().terminal())
			.as("Should receive request capabilities, not builder")
			.isTrue();

		// Cleanup
		client.closeGracefully().block(TIMEOUT);
		agent.closeGracefully().block(TIMEOUT);
	}

}
