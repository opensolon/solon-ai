/*
 * Copyright 2025-2025 the original author or authors.
 */

package com.agentclientprotocol.sdk.agent.transport;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import com.agentclientprotocol.sdk.AcpTestFixtures;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import io.modelcontextprotocol.json.McpJsonMapper;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link StdioAcpAgentTransport}.
 */
class StdioAcpAgentTransportTest {

	private static final Duration TIMEOUT = Duration.ofSeconds(5);

	private final McpJsonMapper jsonMapper = McpJsonMapper.getDefault();

	@Test
	void constructorValidatesArguments() {
		org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
				() -> new StdioAcpAgentTransport(null));
		org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
				() -> new StdioAcpAgentTransport(jsonMapper, null, System.out));
		org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
				() -> new StdioAcpAgentTransport(jsonMapper, System.in, null));
	}

	@Test
	void startTwiceFails() {
		ByteArrayInputStream in = new ByteArrayInputStream(new byte[0]);
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		StdioAcpAgentTransport transport = new StdioAcpAgentTransport(jsonMapper, in, out);

		transport.start(msg -> msg).subscribe();

		Mono<Void> secondStart = transport.start(msg -> msg);
		org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, () -> secondStart.block(TIMEOUT));
	}

	@Test
	void protocolVersionsReturnsLatest() {
		StdioAcpAgentTransport transport = new StdioAcpAgentTransport(jsonMapper);
		assertThat(transport.protocolVersions()).contains(AcpSchema.LATEST_PROTOCOL_VERSION);
	}

	@Test
	void receivesMessageFromInputStream() throws Exception {
		// Create a message to send
		AcpSchema.JSONRPCRequest request = AcpTestFixtures.createJsonRpcRequest(AcpSchema.METHOD_INITIALIZE, "1",
				AcpTestFixtures.createInitializeRequest());
		String json = jsonMapper.writeValueAsString(request) + "\n";

		// Create piped streams
		PipedOutputStream clientOut = new PipedOutputStream();
		PipedInputStream agentIn = new PipedInputStream(clientOut, 65536);
		ByteArrayOutputStream agentOut = new ByteArrayOutputStream();

		StdioAcpAgentTransport transport = new StdioAcpAgentTransport(jsonMapper, agentIn, agentOut);

		AtomicReference<AcpSchema.JSONRPCMessage> received = new AtomicReference<>();
		CountDownLatch latch = new CountDownLatch(1);

		transport.start(msg -> {
			return msg.doOnNext(m -> {
				received.set(m);
				latch.countDown();
			}).then(Mono.empty());
		}).subscribe();

		// Write the message
		clientOut.write(json.getBytes(StandardCharsets.UTF_8));
		clientOut.flush();

		assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
		assertThat(received.get()).isNotNull();
		assertThat(received.get()).isInstanceOf(AcpSchema.JSONRPCRequest.class);
		assertThat(((AcpSchema.JSONRPCRequest) received.get()).method()).isEqualTo(AcpSchema.METHOD_INITIALIZE);

		transport.closeGracefully().block(TIMEOUT);
	}

	@Test
	void sendsMessageToOutputStream() throws Exception {
		// Create piped streams for bidirectional communication
		PipedOutputStream clientOut = new PipedOutputStream();
		PipedInputStream agentIn = new PipedInputStream(clientOut, 65536);
		PipedOutputStream agentOut = new PipedOutputStream();
		PipedInputStream clientIn = new PipedInputStream(agentOut, 65536);

		StdioAcpAgentTransport transport = new StdioAcpAgentTransport(jsonMapper, agentIn, agentOut);

		// Use a non-echoing handler - the default echo handler (msg -> msg) would
		// echo the warmup message back to output, causing the test to read the
		// wrong message. We only want to test explicit sendMessage() output.
		transport.start(msg -> Mono.empty()).subscribe();

		// Wait for transport to be ready by sending a dummy message from "client" first
		AcpSchema.JSONRPCRequest dummyRequest = AcpTestFixtures.createJsonRpcRequest(AcpSchema.METHOD_INITIALIZE, "0",
				AcpTestFixtures.createInitializeRequest());
		String dummyJson = jsonMapper.writeValueAsString(dummyRequest) + "\n";
		clientOut.write(dummyJson.getBytes(StandardCharsets.UTF_8));
		clientOut.flush();

		// Small delay to ensure transport is fully started
		Thread.sleep(100);

		// Now send a message from the agent
		AcpSchema.JSONRPCResponse response = AcpTestFixtures.createJsonRpcResponse("1",
				AcpTestFixtures.createInitializeResponse());
		transport.sendMessage(response).block(TIMEOUT);

		// Read from client side
		byte[] buffer = new byte[4096];
		int read = clientIn.read(buffer);
		String received = new String(buffer, 0, read, StandardCharsets.UTF_8);

		assertThat(received).contains("\"jsonrpc\":\"2.0\"");
		assertThat(received).contains("\"id\":\"1\"");

		transport.closeGracefully().block(TIMEOUT);
	}

	@Test
	void closeGracefullyCompletesWithoutError() {
		ByteArrayInputStream in = new ByteArrayInputStream(new byte[0]);
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		StdioAcpAgentTransport transport = new StdioAcpAgentTransport(jsonMapper, in, out);

		transport.start(msg -> msg).subscribe();
		transport.closeGracefully().block(TIMEOUT);
		// Test passes if no exception is thrown
	}

}
