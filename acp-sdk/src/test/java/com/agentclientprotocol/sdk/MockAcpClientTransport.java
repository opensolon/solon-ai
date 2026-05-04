/*
 * Copyright 2025-2025 the original author or authors.
 */

package com.agentclientprotocol.sdk;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Function;

import com.agentclientprotocol.sdk.spec.AcpClientTransport;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import com.agentclientprotocol.sdk.spec.AcpSchema.JSONRPCNotification;
import com.agentclientprotocol.sdk.spec.AcpSchema.JSONRPCRequest;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.TypeRef;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import java.util.Collections;

/**
 * A mock implementation of the {@link AcpClientTransport} interface for testing.
 *
 * <p>
 * This mock transport allows tests to:
 * </p>
 * <ul>
 * <li>Simulate incoming messages from an agent without running a real process</li>
 * <li>Inspect messages sent by the client</li>
 * <li>Test client behavior in isolation</li>
 * </ul>
 *
 * @author Mark Pollack
 * @author Christian Tzolov
 */
public class MockAcpClientTransport implements AcpClientTransport {

	private final Sinks.Many<AcpSchema.JSONRPCMessage> inbound = Sinks.many().unicast().onBackpressureBuffer();

	private final List<AcpSchema.JSONRPCMessage> sent = new ArrayList<>();

	private final BiConsumer<MockAcpClientTransport, AcpSchema.JSONRPCMessage> interceptor;

	private int protocolVersion = AcpSchema.LATEST_PROTOCOL_VERSION;

	private volatile boolean connected = false;

	/**
	 * Creates a mock transport with no interceptor.
	 */
	public MockAcpClientTransport() {
		this((t, msg) -> {
		});
	}

	/**
	 * Creates a mock transport with a custom interceptor for sent messages.
	 * @param interceptor called whenever a message is sent
	 */
	public MockAcpClientTransport(BiConsumer<MockAcpClientTransport, AcpSchema.JSONRPCMessage> interceptor) {
		this.interceptor = interceptor;
	}

	/**
	 * Sets the protocol version this mock transport reports.
	 * @param protocolVersion the protocol version
	 * @return this transport for chaining
	 */
	public MockAcpClientTransport withProtocolVersion(int protocolVersion) {
		this.protocolVersion = protocolVersion;
		return this;
	}

	@Override
	public List<Integer> protocolVersions() {
		return Collections.singletonList(protocolVersion);
	}

	/**
	 * Simulates an incoming message from the agent.
	 * @param message the message to simulate
	 */
	public void simulateIncomingMessage(AcpSchema.JSONRPCMessage message) {
		if (inbound.tryEmitNext(message).isFailure()) {
			throw new RuntimeException("Failed to process incoming message " + message);
		}
	}

	@Override
	public Mono<Void> sendMessage(AcpSchema.JSONRPCMessage message) {
		sent.add(message);
		interceptor.accept(this, message);
		return Mono.empty();
	}

	/**
	 * Gets the last message sent by the client as a request.
	 * @return the last sent request, or null if no messages sent
	 * @throws ClassCastException if the last message was not a request
	 */
	public JSONRPCRequest getLastSentMessageAsRequest() {
		return (JSONRPCRequest) getLastSentMessage();
	}

	/**
	 * Gets the last message sent by the client as a notification.
	 * @return the last sent notification, or null if no messages sent
	 * @throws ClassCastException if the last message was not a notification
	 */
	public JSONRPCNotification getLastSentMessageAsNotification() {
		return (JSONRPCNotification) getLastSentMessage();
	}

	/**
	 * Gets the last message sent by the client.
	 * @return the last sent message, or null if no messages sent
	 */
	public AcpSchema.JSONRPCMessage getLastSentMessage() {
		return !sent.isEmpty() ? sent.get(sent.size() - 1) : null;
	}

	/**
	 * Gets all messages sent by the client.
	 * @return list of all sent messages
	 */
	public List<AcpSchema.JSONRPCMessage> getSentMessages() {
		return new ArrayList<>(sent);
	}

	/**
	 * Clears the list of sent messages.
	 */
	public void clearSentMessages() {
		sent.clear();
	}

	@Override
	public Mono<Void> connect(Function<Mono<AcpSchema.JSONRPCMessage>, Mono<AcpSchema.JSONRPCMessage>> handler) {
		if (connected) {
			return Mono.error(new IllegalStateException("Already connected"));
		}
		connected = true;
		return inbound.asFlux()
			.flatMap(message -> Mono.just(message).transform(handler))
			.doFinally(signal -> connected = false)
			.then();
	}

	@Override
	public Mono<Void> closeGracefully() {
		return Mono.defer(() -> {
			connected = false;
			inbound.tryEmitComplete();
			return Mono.empty();
		});
	}

	@Override
	public <T> T unmarshalFrom(Object data, TypeRef<T> typeRef) {
		return McpJsonMapper.getDefault().convertValue(data, typeRef);
	}

}
