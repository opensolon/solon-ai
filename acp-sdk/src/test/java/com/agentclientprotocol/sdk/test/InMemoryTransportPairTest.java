/*
 * Copyright 2025-2025 the original author or authors.
 */

package com.agentclientprotocol.sdk.test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import com.agentclientprotocol.sdk.AcpTestFixtures;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * Unit tests for {@link InMemoryTransportPair}.
 */
class InMemoryTransportPairTest {

	private static final Duration TIMEOUT = Duration.ofSeconds(5);

	@Test
	void createReturnsPair() {
		InMemoryTransportPair pair = InMemoryTransportPair.create();
		assertThat(pair).isNotNull();
		assertThat(pair.clientTransport()).isNotNull();
		assertThat(pair.agentTransport()).isNotNull();
	}

	@Test
	void clientCanSendToAgent() throws InterruptedException {
		InMemoryTransportPair pair = InMemoryTransportPair.create();
		AtomicReference<AcpSchema.JSONRPCMessage> received = new AtomicReference<>();
		CountDownLatch latch = new CountDownLatch(1);

		// Agent listens
		pair.agentTransport().start(msg -> {
			return msg.doOnNext(m -> {
				received.set(m);
				latch.countDown();
			}).then(Mono.empty());
		}).subscribe();

		// Client connects and sends
		pair.clientTransport().connect(msg -> msg).subscribe();
		pair.clientTransport()
			.sendMessage(
					AcpTestFixtures.createJsonRpcRequest(AcpSchema.METHOD_INITIALIZE, "1", AcpTestFixtures.createInitializeRequest()))
			.block(TIMEOUT);

		assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
		assertThat(received.get()).isNotNull();
		assertThat(received.get()).isInstanceOf(AcpSchema.JSONRPCRequest.class);
	}

	@Test
	void agentCanSendToClient() throws InterruptedException {
		InMemoryTransportPair pair = InMemoryTransportPair.create();
		AtomicReference<AcpSchema.JSONRPCMessage> received = new AtomicReference<>();
		CountDownLatch latch = new CountDownLatch(1);

		// Client listens
		pair.clientTransport().connect(msg -> {
			return msg.doOnNext(m -> {
				received.set(m);
				latch.countDown();
			}).then(Mono.empty());
		}).subscribe();

		// Agent starts and sends
		pair.agentTransport().start(msg -> msg).subscribe();
		pair.agentTransport()
			.sendMessage(AcpTestFixtures.createJsonRpcResponse("1", AcpTestFixtures.createInitializeResponse()))
			.block(TIMEOUT);

		assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
		assertThat(received.get()).isNotNull();
		assertThat(received.get()).isInstanceOf(AcpSchema.JSONRPCResponse.class);
	}

	@Test
	void closeGracefullyCompletesBothTransports() {
		InMemoryTransportPair pair = InMemoryTransportPair.create();
		CountDownLatch agentLatch = new CountDownLatch(1);
		CountDownLatch clientLatch = new CountDownLatch(1);

		pair.agentTransport().start(msg -> msg).doFinally(signal -> agentLatch.countDown()).subscribe();
		pair.clientTransport().connect(msg -> msg).doFinally(signal -> clientLatch.countDown()).subscribe();

		pair.closeGracefully().block(TIMEOUT);

		try {
			assertThat(agentLatch.await(5, TimeUnit.SECONDS)).isTrue();
			assertThat(clientLatch.await(5, TimeUnit.SECONDS)).isTrue();
		}
		catch (InterruptedException e) {
			fail("Test interrupted", e);
		}
	}

}
