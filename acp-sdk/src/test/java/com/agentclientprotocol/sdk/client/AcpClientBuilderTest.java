/*
 * Copyright 2025-2025 the original author or authors.
 */

package com.agentclientprotocol.sdk.client;

import java.time.Duration;

import com.agentclientprotocol.sdk.MockAcpClientTransport;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for {@link AcpClient} builder functionality.
 *
 * @author Mark Pollack
 * @author Christian Tzolov
 */
class AcpClientBuilderTest {

	@Test
	void asyncBuilderCreatesClient() {
		MockAcpClientTransport transport = new MockAcpClientTransport();

		AcpAsyncClient client = AcpClient.async(transport).build();

		assertThat(client).isNotNull();
	}

	@Test
	void asyncBuilderWithRequestTimeout() {
		MockAcpClientTransport transport = new MockAcpClientTransport();

		AcpAsyncClient client = AcpClient.async(transport).requestTimeout(Duration.ofSeconds(10)).build();

		assertThat(client).isNotNull();
	}

	@Test
	void asyncBuilderWithSessionUpdateConsumer() {
		MockAcpClientTransport transport = new MockAcpClientTransport();

		AcpAsyncClient client = AcpClient.async(transport)
			.sessionUpdateConsumer(notification -> Mono.empty())
			.build();

		assertThat(client).isNotNull();
	}

	@Test
	void asyncBuilderRequiresTransport() {
		assertThatThrownBy(() -> AcpClient.async(null).build()).isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("Transport");
	}

	@Test
	void syncClientWrapsAsyncClient() {
		MockAcpClientTransport transport = new MockAcpClientTransport();
		AcpAsyncClient asyncClient = AcpClient.async(transport).build();

		AcpSyncClient syncClient = new AcpSyncClient(asyncClient);

		assertThat(syncClient).isNotNull();
	}

	@Test
	void syncClientGetAgentCapabilitiesReturnsNullBeforeInitialization() {
		MockAcpClientTransport transport = new MockAcpClientTransport();
		AcpAsyncClient asyncClient = AcpClient.async(transport).build();
		AcpSyncClient syncClient = new AcpSyncClient(asyncClient);

		// Before initialization, capabilities should be null
		assertThat(syncClient.getAgentCapabilities()).isNull();
	}

	@Test
	void asyncClientGetAgentCapabilitiesReturnsNullBeforeInitialization() {
		MockAcpClientTransport transport = new MockAcpClientTransport();
		AcpAsyncClient asyncClient = AcpClient.async(transport).build();

		// Before initialization, capabilities should be null
		assertThat(asyncClient.getAgentCapabilities()).isNull();
	}

}
