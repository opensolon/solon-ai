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
 * Abstract base class for protocol-level tests.
 *
 * <p>
 * This class contains test methods that verify core protocol behavior. Concrete
 * subclasses provide specific {@link ProtocolDriver} implementations to run
 * these tests against different transports.
 * </p>
 *
 * <p>
 * By extending this class with different drivers, the same test logic runs against:
 * </p>
 * <ul>
 * <li>In-memory transports (fast unit tests)</li>
 * <li>Stdio transports (integration tests)</li>
 * <li>WebSocket transports (future)</li>
 * </ul>
 *
 * <p>
 * Usage:
 * </p>
 * <pre>{@code
 * class InMemoryProtocolTest extends AbstractProtocolTest {
 *     InMemoryProtocolTest() {
 *         super(new InMemoryProtocolDriver());
 *     }
 * }
 * }</pre>
 *
 * @author Mark Pollack
 */
public abstract class AbstractProtocolTest {

	private static final Duration TIMEOUT = Duration.ofSeconds(5);

	private final ProtocolDriver driver;

	protected AbstractProtocolTest(ProtocolDriver driver) {
		this.driver = driver;
	}

	@Test
	void messageFromClientArrivesAtAgent() {
		driver.runWithTransports((clientTransport, agentTransport) -> {
			AtomicReference<AcpSchema.JSONRPCMessage> receivedMessage = new AtomicReference<>();
			CountDownLatch latch = new CountDownLatch(1);

			// Agent listens for messages
			agentTransport.start(incomingMessage -> {
				return incomingMessage.doOnNext(msg -> {
					receivedMessage.set(msg);
					latch.countDown();
				}).then(Mono.empty());
			}).subscribe();

			// Client connects and sends
			clientTransport.connect(msg -> msg).subscribe();

			AcpSchema.JSONRPCRequest request = AcpTestFixtures.createJsonRpcRequest(AcpSchema.METHOD_INITIALIZE, "1",
					AcpTestFixtures.createInitializeRequest());
			clientTransport.sendMessage(request).block(TIMEOUT);

			try {
				assertThat(latch.await(5, TimeUnit.SECONDS)).as("Agent should receive message").isTrue();
				assertThat(receivedMessage.get()).isNotNull();
				assertThat(receivedMessage.get()).isInstanceOf(AcpSchema.JSONRPCRequest.class);
				AcpSchema.JSONRPCRequest received = (AcpSchema.JSONRPCRequest) receivedMessage.get();
				assertThat(received.method()).isEqualTo(AcpSchema.METHOD_INITIALIZE);
			}
			catch (InterruptedException e) {
				fail("Test interrupted", e);
			}
		});
	}

	@Test
	void messageFromAgentArrivesAtClient() {
		driver.runWithTransports((clientTransport, agentTransport) -> {
			AtomicReference<AcpSchema.JSONRPCMessage> receivedMessage = new AtomicReference<>();
			CountDownLatch latch = new CountDownLatch(1);

			// Client listens for messages
			clientTransport.connect(incomingMessage -> {
				return incomingMessage.doOnNext(msg -> {
					receivedMessage.set(msg);
					latch.countDown();
				}).then(Mono.empty());
			}).subscribe();

			// Agent starts and sends
			agentTransport.start(msg -> msg).subscribe();

			AcpSchema.JSONRPCNotification notification = AcpTestFixtures.createJsonRpcNotification(
					AcpSchema.METHOD_SESSION_UPDATE,
					new AcpSchema.SessionNotification("session-123",
							new AcpSchema.AgentMessageChunk("agentMessage", AcpTestFixtures.createTextContent("Hello"))));
			agentTransport.sendMessage(notification).block(TIMEOUT);

			try {
				assertThat(latch.await(5, TimeUnit.SECONDS)).as("Client should receive message").isTrue();
				assertThat(receivedMessage.get()).isNotNull();
				assertThat(receivedMessage.get()).isInstanceOf(AcpSchema.JSONRPCNotification.class);
			}
			catch (InterruptedException e) {
				fail("Test interrupted", e);
			}
		});
	}

	@Test
	void bidirectionalMessageExchange() {
		driver.runWithTransports((clientTransport, agentTransport) -> {
			AtomicReference<AcpSchema.JSONRPCMessage> agentReceived = new AtomicReference<>();
			AtomicReference<AcpSchema.JSONRPCMessage> clientReceived = new AtomicReference<>();
			CountDownLatch agentLatch = new CountDownLatch(1);
			CountDownLatch clientLatch = new CountDownLatch(1);

			// Agent receives request and sends response
			agentTransport.start(incomingMessage -> {
				return incomingMessage.doOnNext(msg -> {
					agentReceived.set(msg);
					agentLatch.countDown();
					// Send response back
					if (msg instanceof AcpSchema.JSONRPCRequest) {
						AcpSchema.JSONRPCRequest req = (AcpSchema.JSONRPCRequest) msg;
						AcpSchema.JSONRPCResponse response = AcpTestFixtures.createJsonRpcResponse(req.id(),
								AcpTestFixtures.createInitializeResponse());
						agentTransport.sendMessage(response).subscribe();
					}
				}).then(Mono.empty());
			}).subscribe();

			// Client receives response
			clientTransport.connect(incomingMessage -> {
				return incomingMessage.doOnNext(msg -> {
					clientReceived.set(msg);
					clientLatch.countDown();
				}).then(Mono.empty());
			}).subscribe();

			// Client sends request
			AcpSchema.JSONRPCRequest request = AcpTestFixtures.createJsonRpcRequest(AcpSchema.METHOD_INITIALIZE, "req-1",
					AcpTestFixtures.createInitializeRequest());
			clientTransport.sendMessage(request).block(TIMEOUT);

			try {
				assertThat(agentLatch.await(5, TimeUnit.SECONDS)).as("Agent should receive request").isTrue();
				assertThat(clientLatch.await(5, TimeUnit.SECONDS)).as("Client should receive response").isTrue();

				assertThat(agentReceived.get()).isInstanceOf(AcpSchema.JSONRPCRequest.class);
				assertThat(clientReceived.get()).isInstanceOf(AcpSchema.JSONRPCResponse.class);

				AcpSchema.JSONRPCResponse response = (AcpSchema.JSONRPCResponse) clientReceived.get();
				assertThat(response.id()).isEqualTo("req-1");
				assertThat(response.error()).isNull();
			}
			catch (InterruptedException e) {
				fail("Test interrupted", e);
			}
		});
	}

	@Test
	void transportCloseGracefullyCompletesWithoutError() {
		driver.runWithTransports((clientTransport, agentTransport) -> {
			// Agent starts
			agentTransport.start(msg -> msg).subscribe();

			// Client connects
			clientTransport.connect(msg -> msg).subscribe();

			// Close both transports - should complete without error
			agentTransport.closeGracefully().block(TIMEOUT);
			clientTransport.closeGracefully().block(TIMEOUT);

			// If we reach here without exception, the test passes
		});
	}

}
