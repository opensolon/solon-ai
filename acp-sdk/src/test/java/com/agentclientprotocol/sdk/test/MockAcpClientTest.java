/*
 * Copyright 2025-2025 the original author or authors.
 */

package com.agentclientprotocol.sdk.test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import com.agentclientprotocol.sdk.agent.AcpAgent;
import com.agentclientprotocol.sdk.agent.AcpAsyncAgent;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.Collections;

/**
 * Unit tests for {@link MockAcpClient}.
 */
class MockAcpClientTest {

	private static final Duration TIMEOUT = Duration.ofSeconds(5);

	@Test
	void defaultMockClientInitializesAndCreatesSession() throws Exception {
		InMemoryTransportPair pair = InMemoryTransportPair.create();

		try {
			AcpAsyncAgent agent = AcpAgent.async(pair.agentTransport())
				.initializeHandler(request -> Mono
					.just(new AcpSchema.InitializeResponse(1, new AcpSchema.AgentCapabilities(), Collections.emptyList())))
				.newSessionHandler(request -> Mono.just(new AcpSchema.NewSessionResponse("test-session", null, null)))
				.promptHandler(
						(request, updater) -> Mono.just(new AcpSchema.PromptResponse(AcpSchema.StopReason.END_TURN)))
				.build();
			agent.start().subscribe();
			Thread.sleep(100);

			MockAcpClient mockClient = MockAcpClient.createDefault(pair.clientTransport());

			AcpSchema.InitializeResponse initResponse = mockClient.initialize();
			assertThat(initResponse).isNotNull();
			assertThat(initResponse.protocolVersion()).isEqualTo(1);

			AcpSchema.NewSessionResponse sessionResponse = mockClient.newSession("/workspace");
			assertThat(sessionResponse).isNotNull();
			assertThat(sessionResponse.sessionId()).isEqualTo("test-session");

			assertThat(mockClient.getCurrentSessionId()).isEqualTo("test-session");

			mockClient.closeGracefully();
			agent.closeGracefully().block(TIMEOUT);
		}
		finally {
			pair.closeGracefully().block(TIMEOUT);
		}
	}

	@Test
	void mockClientReceivesSessionUpdates() throws Exception {
		InMemoryTransportPair pair = InMemoryTransportPair.create();

		try {
			AtomicReference<AcpAsyncAgent> agentRef = new AtomicReference<>();

			AcpAsyncAgent agent = AcpAgent.async(pair.agentTransport())
				.initializeHandler(request -> Mono
					.just(new AcpSchema.InitializeResponse(1, new AcpSchema.AgentCapabilities(), Collections.emptyList())))
				.newSessionHandler(request -> Mono.just(new AcpSchema.NewSessionResponse("update-session", null, null)))
				.promptHandler((request, updater) -> {
					// Send update during prompt
					return updater
						.sendUpdate("update-session",
								new AcpSchema.AgentMessageChunk("agent_message_chunk",
										new AcpSchema.TextContent("Processing...")))
						.then(Mono.just(new AcpSchema.PromptResponse(AcpSchema.StopReason.END_TURN)));
				})
				.build();
			agentRef.set(agent);
			agent.start().subscribe();
			Thread.sleep(100);

			MockAcpClient mockClient = MockAcpClient.createDefault(pair.clientTransport());
			mockClient.initialize();
			mockClient.newSession("/workspace");

			mockClient.expectUpdates(1);
			mockClient.prompt("update-session", "Do something");

			assertThat(mockClient.awaitUpdates(TIMEOUT)).isTrue();
			assertThat(mockClient.getReceivedUpdates()).hasSize(1);
			assertThat(mockClient.getReceivedUpdates().get(0).update()).isInstanceOf(AcpSchema.AgentMessageChunk.class);

			mockClient.closeGracefully();
			agent.closeGracefully().block(TIMEOUT);
		}
		finally {
			pair.closeGracefully().block(TIMEOUT);
		}
	}

	@Test
	void mockClientHandlesPermissionRequests() throws Exception {
		InMemoryTransportPair pair = InMemoryTransportPair.create();

		try {
			AtomicReference<AcpAsyncAgent> agentRef = new AtomicReference<>();

			AcpAsyncAgent agent = AcpAgent.async(pair.agentTransport())
				.initializeHandler(request -> Mono
					.just(new AcpSchema.InitializeResponse(1, new AcpSchema.AgentCapabilities(), Collections.emptyList())))
				.newSessionHandler(request -> Mono.just(new AcpSchema.NewSessionResponse("perm-session", null, null)))
				.promptHandler((request, updater) -> {
					AcpSchema.ToolCallUpdate toolCall = new AcpSchema.ToolCallUpdate("tool-1", "Edit File",
							AcpSchema.ToolKind.EDIT, AcpSchema.ToolCallStatus.PENDING, null, null, null, null);
					List<AcpSchema.PermissionOption> options = Collections.singletonList(new AcpSchema.PermissionOption("allow", "Allow",
							AcpSchema.PermissionOptionKind.ALLOW_ONCE));

					return agentRef.get()
						.requestPermission(new AcpSchema.RequestPermissionRequest("perm-session", toolCall, options))
						.then(Mono.just(new AcpSchema.PromptResponse(AcpSchema.StopReason.END_TURN)));
				})
				.build();
			agentRef.set(agent);
			agent.start().subscribe();
			Thread.sleep(100);

			MockAcpClient mockClient = MockAcpClient.builder(pair.clientTransport())
				.permissionResponse(request -> new AcpSchema.RequestPermissionResponse(
						new AcpSchema.PermissionSelected("allow")))
				.build();

			mockClient.initialize();
			mockClient.newSession("/workspace");
			mockClient.prompt("perm-session", "Need permission");

			assertThat(mockClient.getReceivedPermissionRequests()).hasSize(1);
			assertThat(mockClient.getReceivedPermissionRequests().get(0).toolCall().title()).isEqualTo("Edit File");

			mockClient.closeGracefully();
			agent.closeGracefully().block(TIMEOUT);
		}
		finally {
			pair.closeGracefully().block(TIMEOUT);
		}
	}

	@Test
	void mockClientHandlesFileReadRequests() throws Exception {
		InMemoryTransportPair pair = InMemoryTransportPair.create();

		try {
			AtomicReference<AcpAsyncAgent> agentRef = new AtomicReference<>();

			AcpAsyncAgent agent = AcpAgent.async(pair.agentTransport())
				.initializeHandler(request -> Mono
					.just(new AcpSchema.InitializeResponse(1, new AcpSchema.AgentCapabilities(), Collections.emptyList())))
				.newSessionHandler(request -> Mono.just(new AcpSchema.NewSessionResponse("file-session", null, null)))
				.promptHandler((request, updater) -> {
					return agentRef.get()
						.readTextFile(
								new AcpSchema.ReadTextFileRequest("file-session", "/src/Main.java", null, null))
						.then(Mono.just(new AcpSchema.PromptResponse(AcpSchema.StopReason.END_TURN)));
				})
				.build();
			agentRef.set(agent);
			agent.start().subscribe();
			Thread.sleep(100);

			MockAcpClient mockClient = MockAcpClient.builder(pair.clientTransport())
				.fileContent("/src/Main.java", "public class Main {}")
				.build();

			mockClient.initialize();
			mockClient.newSession("/workspace");
			mockClient.prompt("file-session", "Read file");

			assertThat(mockClient.getReceivedFileReadRequests()).hasSize(1);
			assertThat(mockClient.getReceivedFileReadRequests().get(0).path()).isEqualTo("/src/Main.java");

			mockClient.closeGracefully();
			agent.closeGracefully().block(TIMEOUT);
		}
		finally {
			pair.closeGracefully().block(TIMEOUT);
		}
	}

}
