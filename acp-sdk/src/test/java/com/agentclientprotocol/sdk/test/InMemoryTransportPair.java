/*
 * Copyright 2025-2025 the original author or authors.
 */

package com.agentclientprotocol.sdk.test;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

import com.agentclientprotocol.sdk.spec.AcpAgentTransport;
import com.agentclientprotocol.sdk.spec.AcpClientTransport;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.TypeRef;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import java.util.Collections;

/**
 * Creates a bidirectional in-memory transport pair for testing client ↔ agent communication
 * without real processes or network connections.
 *
 * <p>
 * This class provides connected client and agent transports that communicate through
 * in-memory sinks, enabling:
 * </p>
 * <ul>
 * <li>Unit testing of protocol logic without I/O</li>
 * <li>Fast, deterministic tests</li>
 * <li>Testing both client and agent sides in isolation or together</li>
 * </ul>
 *
 * <p>
 * Example usage:
 * </p>
 * <pre>{@code
 * InMemoryTransportPair pair = InMemoryTransportPair.create();
 *
 * // Use client transport in client code
 * AcpClientTransport clientTransport = pair.clientTransport();
 *
 * // Use agent transport in agent code
 * AcpAgentTransport agentTransport = pair.agentTransport();
 *
 * // Messages sent by client arrive at agent, and vice versa
 * }</pre>
 *
 * @author Mark Pollack
 */
public class InMemoryTransportPair {

	private final InMemoryClientTransport clientTransport;

	private final InMemoryAgentTransport agentTransport;

	private InMemoryTransportPair() {
		// Bidirectional sinks: client→agent and agent→client
		Sinks.Many<AcpSchema.JSONRPCMessage> clientToAgent = Sinks.many().unicast().onBackpressureBuffer();
		Sinks.Many<AcpSchema.JSONRPCMessage> agentToClient = Sinks.many().unicast().onBackpressureBuffer();

		this.clientTransport = new InMemoryClientTransport(clientToAgent, agentToClient);
		this.agentTransport = new InMemoryAgentTransport(agentToClient, clientToAgent);
	}

	/**
	 * Creates a new transport pair with connected client and agent transports.
	 * @return a new InMemoryTransportPair
	 */
	public static InMemoryTransportPair create() {
		return new InMemoryTransportPair();
	}

	/**
	 * Gets the client-side transport.
	 * @return the client transport
	 */
	public AcpClientTransport clientTransport() {
		return clientTransport;
	}

	/**
	 * Gets the agent-side transport.
	 * @return the agent transport
	 */
	public AcpAgentTransport agentTransport() {
		return agentTransport;
	}

	/**
	 * Closes both transports gracefully.
	 * @return a Mono that completes when both transports are closed
	 */
	public Mono<Void> closeGracefully() {
		return Mono.when(clientTransport.closeGracefully(), agentTransport.closeGracefully());
	}

	/**
	 * In-memory client transport implementation.
	 */
	private static class InMemoryClientTransport implements AcpClientTransport {

		private final Sinks.Many<AcpSchema.JSONRPCMessage> outbound;

		private final Sinks.Many<AcpSchema.JSONRPCMessage> inbound;

		private volatile boolean connected = false;

		private Consumer<Throwable> exceptionHandler = t -> {
		};

		InMemoryClientTransport(Sinks.Many<AcpSchema.JSONRPCMessage> outbound,
				Sinks.Many<AcpSchema.JSONRPCMessage> inbound) {
			this.outbound = outbound;
			this.inbound = inbound;
		}

		@Override
		public List<Integer> protocolVersions() {
			return Collections.singletonList(AcpSchema.LATEST_PROTOCOL_VERSION);
		}

		@Override
		public Mono<Void> connect(Function<Mono<AcpSchema.JSONRPCMessage>, Mono<AcpSchema.JSONRPCMessage>> handler) {
			if (connected) {
				return Mono.error(new IllegalStateException("Already connected"));
			}
			connected = true;
			return inbound.asFlux()
				.flatMap(message -> Mono.just(message).transform(handler))
				.doOnError(exceptionHandler::accept)
				.doFinally(signal -> connected = false)
				.then();
		}

		@Override
		public Mono<Void> sendMessage(AcpSchema.JSONRPCMessage message) {
			Sinks.EmitResult result = outbound.tryEmitNext(message);
			if (result.isFailure()) {
				return Mono.error(new RuntimeException("Failed to send message: " + result));
			}
			return Mono.empty();
		}

		@Override
		public Mono<Void> closeGracefully() {
			return Mono.defer(() -> {
				connected = false;
				outbound.tryEmitComplete();
				return Mono.empty();
			});
		}

		@Override
		public void setExceptionHandler(Consumer<Throwable> handler) {
			this.exceptionHandler = handler;
		}

		@Override
		public <T> T unmarshalFrom(Object data, TypeRef<T> typeRef) {
			return McpJsonMapper.getDefault().convertValue(data, typeRef);
		}

	}

	/**
	 * In-memory agent transport implementation.
	 */
	private static class InMemoryAgentTransport implements AcpAgentTransport {

		private final Sinks.Many<AcpSchema.JSONRPCMessage> outbound;

		private final Sinks.Many<AcpSchema.JSONRPCMessage> inbound;

		private final Sinks.One<Void> terminationSink = Sinks.one();

		private volatile boolean started = false;

		private Consumer<Throwable> exceptionHandler = t -> {
		};

		InMemoryAgentTransport(Sinks.Many<AcpSchema.JSONRPCMessage> outbound,
				Sinks.Many<AcpSchema.JSONRPCMessage> inbound) {
			this.outbound = outbound;
			this.inbound = inbound;
		}

		@Override
		public List<Integer> protocolVersions() {
			return Collections.singletonList(AcpSchema.LATEST_PROTOCOL_VERSION);
		}

		@Override
		public Mono<Void> start(Function<Mono<AcpSchema.JSONRPCMessage>, Mono<AcpSchema.JSONRPCMessage>> handler) {
			if (started) {
				return Mono.error(new IllegalStateException("Already started"));
			}
			started = true;
			return inbound.asFlux()
				.flatMap(message -> Mono.just(message)
					.transform(handler)
					.flatMap(response -> {
						// Send response back through outbound sink
						Sinks.EmitResult result = outbound.tryEmitNext(response);
						if (result.isFailure()) {
							return Mono.error(new RuntimeException("Failed to send response: " + result));
						}
						return Mono.empty();
					}))
				.doOnError(exceptionHandler::accept)
				.doFinally(signal -> started = false)
				.then();
		}

		@Override
		public Mono<Void> sendMessage(AcpSchema.JSONRPCMessage message) {
			Sinks.EmitResult result = outbound.tryEmitNext(message);
			if (result.isFailure()) {
				return Mono.error(new RuntimeException("Failed to send message: " + result));
			}
			return Mono.empty();
		}

		@Override
		public Mono<Void> closeGracefully() {
			return Mono.defer(() -> {
				started = false;
				outbound.tryEmitComplete();
				terminationSink.tryEmitValue(null);
				return Mono.empty();
			});
		}

		@Override
		public Mono<Void> awaitTermination() {
			return terminationSink.asMono();
		}

		@Override
		public void setExceptionHandler(Consumer<Throwable> handler) {
			this.exceptionHandler = handler;
		}

		@Override
		public <T> T unmarshalFrom(Object data, TypeRef<T> typeRef) {
			return McpJsonMapper.getDefault().convertValue(data, typeRef);
		}

	}

}
