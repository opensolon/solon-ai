/*
 * Copyright 2025-2025 the original author or authors.
 */

package com.agentclientprotocol.sdk.test;

import java.time.Duration;
import java.util.List;

import com.agentclientprotocol.sdk.client.AcpAsyncClient;
import com.agentclientprotocol.sdk.client.AcpClient;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.Collections;

/**
 * Unit tests for {@link MockAcpAgent}.
 */
class MockAcpAgentTest {

	private static final Duration TIMEOUT = Duration.ofSeconds(5);

	@Test
	void defaultMockAgentAcceptsInitialize() throws Exception {
		InMemoryTransportPair pair = InMemoryTransportPair.create();

		try {
			MockAcpAgent mockAgent = MockAcpAgent.createDefault(pair.agentTransport());
			mockAgent.start();
			Thread.sleep(100);

			AcpAsyncClient client = AcpClient.async(pair.clientTransport()).build();

			AcpSchema.InitializeResponse response = client
				.initialize(new AcpSchema.InitializeRequest(1, new AcpSchema.ClientCapabilities()))
				.block(TIMEOUT);

			assertThat(response).isNotNull();
			assertThat(response.protocolVersion()).isEqualTo(1);
			assertThat(mockAgent.getReceivedInitRequests()).hasSize(1);

			client.closeGracefully().block(TIMEOUT);
			mockAgent.closeGracefully();
		}
		finally {
			pair.closeGracefully().block(TIMEOUT);
		}
	}

	@Test
	void mockAgentRecordsPrompts() throws Exception {
		InMemoryTransportPair pair = InMemoryTransportPair.create();

		try {
			MockAcpAgent mockAgent = MockAcpAgent.builder(pair.agentTransport())
				.newSessionResponse(new AcpSchema.NewSessionResponse("test-session", null, null))
				.build();
			mockAgent.start();
			Thread.sleep(100);

			AcpAsyncClient client = AcpClient.async(pair.clientTransport()).build();
			client.initialize(new AcpSchema.InitializeRequest(1, new AcpSchema.ClientCapabilities())).block(TIMEOUT);
			client.newSession(new AcpSchema.NewSessionRequest("/workspace", Collections.emptyList())).block(TIMEOUT);

			mockAgent.expectPrompts(1);
			client
				.prompt(new AcpSchema.PromptRequest("test-session", Collections.singletonList(new AcpSchema.TextContent("Hello agent"))))
				.block(TIMEOUT);

			assertThat(mockAgent.awaitPrompts(TIMEOUT)).isTrue();
			assertThat(mockAgent.getReceivedPrompts()).hasSize(1);
			assertThat(((AcpSchema.TextContent) mockAgent.getReceivedPrompts().get(0).prompt().get(0)).text())
				.isEqualTo("Hello agent");

			client.closeGracefully().block(TIMEOUT);
			mockAgent.closeGracefully();
		}
		finally {
			pair.closeGracefully().block(TIMEOUT);
		}
	}

	@Test
	void mockAgentRecordsCancellations() throws Exception {
		InMemoryTransportPair pair = InMemoryTransportPair.create();

		try {
			MockAcpAgent mockAgent = MockAcpAgent.builder(pair.agentTransport())
				.newSessionResponse(new AcpSchema.NewSessionResponse("cancel-session", null, null))
				.build();
			mockAgent.start();
			Thread.sleep(100);

			AcpAsyncClient client = AcpClient.async(pair.clientTransport()).build();
			client.initialize(new AcpSchema.InitializeRequest(1, new AcpSchema.ClientCapabilities())).block(TIMEOUT);
			client.newSession(new AcpSchema.NewSessionRequest("/workspace", Collections.emptyList())).block(TIMEOUT);

			client.cancel(new AcpSchema.CancelNotification("cancel-session")).block(TIMEOUT);
			Thread.sleep(100); // Give time for notification to arrive

			assertThat(mockAgent.getReceivedCancellations()).hasSize(1);
			assertThat(mockAgent.getReceivedCancellations().get(0).sessionId()).isEqualTo("cancel-session");

			client.closeGracefully().block(TIMEOUT);
			mockAgent.closeGracefully();
		}
		finally {
			pair.closeGracefully().block(TIMEOUT);
		}
	}

}
