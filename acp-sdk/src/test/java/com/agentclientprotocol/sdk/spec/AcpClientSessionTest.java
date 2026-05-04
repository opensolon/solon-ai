/*
 * Copyright 2025-2025 the original author or authors.
 */

package com.agentclientprotocol.sdk.spec;

import java.time.Duration;
import java.util.Map;
import java.util.function.Function;

import com.agentclientprotocol.sdk.MockAcpClientTransport;
import io.modelcontextprotocol.json.TypeRef;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.Collections;

/**
 * Test suite for {@link AcpClientSession} that verifies its JSON-RPC message handling,
 * request-response correlation, and notification processing.
 *
 * @author Mark Pollack
 * @author Christian Tzolov
 */
class AcpClientSessionTest {

	private static final Logger logger = LoggerFactory.getLogger(AcpClientSessionTest.class);

	private static final Duration TIMEOUT = Duration.ofSeconds(5);

	private static final String TEST_METHOD = "test.method";

	private static final String TEST_NOTIFICATION = "test.notification";

	private static final String ECHO_METHOD = "echo";

	TypeRef<String> responseType = new TypeRef<String>() {
	};

	@Test
	void testSendRequest() {
		String testParam = "test parameter";
		String responseData = "test response";

		MockAcpClientTransport transport = new MockAcpClientTransport();
		AcpClientSession session = new AcpClientSession(TIMEOUT, transport, Collections.emptyMap(),
				Collections.singletonMap(TEST_NOTIFICATION, params -> Mono.fromRunnable(() -> logger.info("Status update: {}", params))),
				Function.identity());

		// Create a Mono that will emit the response after the request is sent
		Mono<String> responseMono = session.sendRequest(TEST_METHOD, testParam, responseType);

		// Verify response handling
		StepVerifier.create(responseMono).then(() -> {
			AcpSchema.JSONRPCRequest request = transport.getLastSentMessageAsRequest();
			transport.simulateIncomingMessage(
					new AcpSchema.JSONRPCResponse(AcpSchema.JSONRPC_VERSION, request.id(), responseData, null));
		}).consumeNextWith(response -> {
			// Verify the request was sent
			AcpSchema.JSONRPCMessage sentMessage = transport.getLastSentMessageAsRequest();
			assertThat(sentMessage).isInstanceOf(AcpSchema.JSONRPCRequest.class);
			AcpSchema.JSONRPCRequest request = (AcpSchema.JSONRPCRequest) sentMessage;
			assertThat(request.method()).isEqualTo(TEST_METHOD);
			assertThat(request.params()).isEqualTo(testParam);
			assertThat(response).isEqualTo(responseData);
		}).verifyComplete();

		session.close();
	}

	@Test
	void testSendRequestWithError() {
		MockAcpClientTransport transport = new MockAcpClientTransport();
		AcpClientSession session = new AcpClientSession(TIMEOUT, transport, Collections.emptyMap(),
				Collections.singletonMap(TEST_NOTIFICATION, params -> Mono.fromRunnable(() -> logger.info("Status update: {}", params))),
				Function.identity());

		Mono<String> responseMono = session.sendRequest(TEST_METHOD, "test", responseType);

		// Verify error handling
		StepVerifier.create(responseMono).then(() -> {
			AcpSchema.JSONRPCRequest request = transport.getLastSentMessageAsRequest();
			// Simulate error response
			AcpSchema.JSONRPCError error = new AcpSchema.JSONRPCError(-32601, "Method not found", null);
			transport.simulateIncomingMessage(
					new AcpSchema.JSONRPCResponse(AcpSchema.JSONRPC_VERSION, request.id(), null, error));
		}).expectError(AcpClientSession.AcpError.class).verify();

		session.close();
	}

	@Test
	void testRequestTimeout() {
		MockAcpClientTransport transport = new MockAcpClientTransport();
		AcpClientSession session = new AcpClientSession(TIMEOUT, transport, Collections.emptyMap(),
				Collections.singletonMap(TEST_NOTIFICATION, params -> Mono.fromRunnable(() -> logger.info("Status update: {}", params))),
				Function.identity());

		Mono<String> responseMono = session.sendRequest(TEST_METHOD, "test", responseType);

		// Verify timeout
		StepVerifier.create(responseMono)
			.expectError(java.util.concurrent.TimeoutException.class)
			.verify(TIMEOUT.plusSeconds(1));

		session.close();
	}

	@Test
	void testSendNotification() {
		MockAcpClientTransport transport = new MockAcpClientTransport();
		AcpClientSession session = new AcpClientSession(TIMEOUT, transport, Collections.emptyMap(),
				Collections.singletonMap(TEST_NOTIFICATION, params -> Mono.fromRunnable(() -> logger.info("Status update: {}", params))),
				Function.identity());

		Map<String, Object> params = Collections.singletonMap("key", "value");
		Mono<Void> notificationMono = session.sendNotification(TEST_NOTIFICATION, params);

		// Verify notification was sent
		StepVerifier.create(notificationMono).consumeSubscriptionWith(response -> {
			AcpSchema.JSONRPCMessage sentMessage = transport.getLastSentMessage();
			assertThat(sentMessage).isInstanceOf(AcpSchema.JSONRPCNotification.class);
			AcpSchema.JSONRPCNotification notification = (AcpSchema.JSONRPCNotification) sentMessage;
			assertThat(notification.method()).isEqualTo(TEST_NOTIFICATION);
			assertThat(notification.params()).isEqualTo(params);
		}).verifyComplete();

		session.close();
	}

	@Test
	void testRequestHandling() {
		String echoMessage = "Hello ACP!";
		Map<String, AcpClientSession.RequestHandler<?>> requestHandlers = Collections.singletonMap(ECHO_METHOD, params -> Mono.just(params));
		MockAcpClientTransport transport = new MockAcpClientTransport();
		AcpClientSession session = new AcpClientSession(TIMEOUT, transport, requestHandlers, Collections.emptyMap(), Function.identity());

		// Simulate incoming request
		AcpSchema.JSONRPCRequest request = new AcpSchema.JSONRPCRequest(AcpSchema.JSONRPC_VERSION, "test-id",
				ECHO_METHOD, echoMessage);
		transport.simulateIncomingMessage(request);

		// Give time for async processing
		try {
			Thread.sleep(100);
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}

		// Verify response
		AcpSchema.JSONRPCMessage sentMessage = transport.getLastSentMessage();
		assertThat(sentMessage).isInstanceOf(AcpSchema.JSONRPCResponse.class);
		AcpSchema.JSONRPCResponse response = (AcpSchema.JSONRPCResponse) sentMessage;
		assertThat(response.result()).isEqualTo(echoMessage);
		assertThat(response.error()).isNull();

		session.close();
	}

	@Test
	void testNotificationHandling() {
		Sinks.One<Object> receivedParams = Sinks.one();

		MockAcpClientTransport transport = new MockAcpClientTransport();
		AcpClientSession session = new AcpClientSession(TIMEOUT, transport, Collections.emptyMap(),
				Collections.singletonMap(TEST_NOTIFICATION, params -> Mono.fromRunnable(() -> receivedParams.tryEmitValue(params))),
				Function.identity());

		// Simulate incoming notification from the agent
		Map<String, Object> notificationParams = Collections.singletonMap("status", "ready");

		AcpSchema.JSONRPCNotification notification = new AcpSchema.JSONRPCNotification(AcpSchema.JSONRPC_VERSION,
				TEST_NOTIFICATION, notificationParams);

		transport.simulateIncomingMessage(notification);

		// Verify handler was called
		assertThat(receivedParams.asMono().block(Duration.ofSeconds(1))).isEqualTo(notificationParams);

		session.close();
	}

	@Test
	void testUnknownMethodHandling() {
		MockAcpClientTransport transport = new MockAcpClientTransport();
		AcpClientSession session = new AcpClientSession(TIMEOUT, transport, Collections.emptyMap(),
				Collections.singletonMap(TEST_NOTIFICATION, params -> Mono.fromRunnable(() -> logger.info("Status update: {}", params))),
				Function.identity());

		// Simulate incoming request for unknown method
		AcpSchema.JSONRPCRequest request = new AcpSchema.JSONRPCRequest(AcpSchema.JSONRPC_VERSION, "test-id",
				"unknown.method", null);
		transport.simulateIncomingMessage(request);

		// Give time for async processing
		try {
			Thread.sleep(100);
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}

		// Verify error response
		AcpSchema.JSONRPCMessage sentMessage = transport.getLastSentMessage();
		assertThat(sentMessage).isInstanceOf(AcpSchema.JSONRPCResponse.class);
		AcpSchema.JSONRPCResponse response = (AcpSchema.JSONRPCResponse) sentMessage;
		assertThat(response.error()).isNotNull();
		assertThat(response.error().code()).isEqualTo(-32601);

		session.close();
	}

	@Test
	void testRequestHandlerThrowsRuntimeException() {
		// Setup: Create a request handler that throws a generic RuntimeException
		String testMethod = "test.genericError";
		RuntimeException exception = new RuntimeException("Something went wrong");
		AcpClientSession.RequestHandler<?> failingHandler = params -> Mono.error(exception);

		MockAcpClientTransport transport = new MockAcpClientTransport();
		AcpClientSession session = new AcpClientSession(TIMEOUT, transport, Collections.singletonMap(testMethod, failingHandler), Collections.emptyMap(),
				Function.identity());

		// Simulate incoming request that will trigger the error
		AcpSchema.JSONRPCRequest request = new AcpSchema.JSONRPCRequest(AcpSchema.JSONRPC_VERSION, "test-id",
				testMethod, null);
		transport.simulateIncomingMessage(request);

		// Give time for async processing
		try {
			Thread.sleep(100);
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}

		// Verify: The response should contain INTERNAL_ERROR
		AcpSchema.JSONRPCMessage sentMessage = transport.getLastSentMessage();
		assertThat(sentMessage).isInstanceOf(AcpSchema.JSONRPCResponse.class);
		AcpSchema.JSONRPCResponse response = (AcpSchema.JSONRPCResponse) sentMessage;
		assertThat(response.error()).isNotNull();
		assertThat(response.error().code()).isEqualTo(-32603);
		assertThat(response.error().message()).isEqualTo("Something went wrong");

		session.close();
	}

	@Test
	void testRequestHandlerThrowsExceptionWithCause() {
		// Setup: Create a request handler that throws an exception with a cause chain
		String testMethod = "test.chainedError";
		RuntimeException rootCause = new IllegalArgumentException("Root cause message");
		RuntimeException middleCause = new IllegalStateException("Middle cause message", rootCause);
		RuntimeException topException = new RuntimeException("Top level message", middleCause);
		AcpClientSession.RequestHandler<?> failingHandler = params -> Mono.error(topException);

		MockAcpClientTransport transport = new MockAcpClientTransport();
		AcpClientSession session = new AcpClientSession(TIMEOUT, transport, Collections.singletonMap(testMethod, failingHandler), Collections.emptyMap(),
				Function.identity());

		// Simulate incoming request that will trigger the error
		AcpSchema.JSONRPCRequest request = new AcpSchema.JSONRPCRequest(AcpSchema.JSONRPC_VERSION, "test-id",
				testMethod, null);
		transport.simulateIncomingMessage(request);

		// Give time for async processing
		try {
			Thread.sleep(100);
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}

		// Verify: The response should contain INTERNAL_ERROR with exception message
		AcpSchema.JSONRPCMessage sentMessage = transport.getLastSentMessage();
		assertThat(sentMessage).isInstanceOf(AcpSchema.JSONRPCResponse.class);
		AcpSchema.JSONRPCResponse response = (AcpSchema.JSONRPCResponse) sentMessage;
		assertThat(response.error()).isNotNull();
		assertThat(response.error().code()).isEqualTo(-32603);
		assertThat(response.error().message()).isEqualTo("Top level message");

		session.close();
	}

	@Test
	void testGracefulShutdown() {
		MockAcpClientTransport transport = new MockAcpClientTransport();
		AcpClientSession session = new AcpClientSession(TIMEOUT, transport, Collections.emptyMap(),
				Collections.singletonMap(TEST_NOTIFICATION, params -> Mono.fromRunnable(() -> logger.info("Status update: {}", params))),
				Function.identity());

		StepVerifier.create(session.closeGracefully()).verifyComplete();
	}

	@Test
	void testConcurrentRequests() {
		MockAcpClientTransport transport = new MockAcpClientTransport();
		AcpClientSession session = new AcpClientSession(TIMEOUT, transport, Collections.emptyMap(),
				Collections.singletonMap(TEST_NOTIFICATION, params -> Mono.fromRunnable(() -> logger.info("Status update: {}", params))),
				Function.identity());

		// Send 5 concurrent requests
		Mono<String> req1 = session.sendRequest("method1", "param1", responseType);
		Mono<String> req2 = session.sendRequest("method2", "param2", responseType);
		Mono<String> req3 = session.sendRequest("method3", "param3", responseType);
		Mono<String> req4 = session.sendRequest("method4", "param4", responseType);
		Mono<String> req5 = session.sendRequest("method5", "param5", responseType);

		// Combine all requests using zip with combinator function
		Mono<String> combined = Mono.zip(arrays -> {
			String result = "";
			for (Object r : arrays) {
				result += r.toString();
			}
			return result;
		}, req1, req2, req3, req4, req5);

		// Simulate responses in different order
		StepVerifier.create(combined).then(() -> {
			java.util.List<AcpSchema.JSONRPCMessage> messages = transport.getSentMessages();
			assertThat(messages).hasSize(5);

			// Respond in reverse order to test correlation
			for (int i = messages.size() - 1; i >= 0; i--) {
				AcpSchema.JSONRPCRequest request = (AcpSchema.JSONRPCRequest) messages.get(i);
				transport.simulateIncomingMessage(new AcpSchema.JSONRPCResponse(AcpSchema.JSONRPC_VERSION,
						request.id(), "response" + (i + 1), null));
			}
		}).expectNext("response1response2response3response4response5").verifyComplete();

		session.close();
	}

}
