/*
 * Copyright 2025-2025 the original author or authors.
 */

package com.agentclientprotocol.sdk.test;

import java.io.IOException;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.util.function.BiConsumer;

import com.agentclientprotocol.sdk.agent.transport.StdioAcpAgentTransport;
import com.agentclientprotocol.sdk.spec.AcpAgentTransport;
import com.agentclientprotocol.sdk.spec.AcpClientTransport;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.TypeRef;
import reactor.core.publisher.Mono;
import java.util.Collections;

/**
 * Protocol driver that uses stdio-based transports with piped streams for testing.
 *
 * <p>
 * This driver creates a client and agent transport connected via {@link PipedInputStream}
 * and {@link PipedOutputStream}, simulating the real stdio communication within the same
 * JVM for faster, more deterministic tests.
 * </p>
 *
 * <p>
 * The data flow is:
 * <ul>
 * <li>Client writes to clientOut → clientOut pipes to agentIn → Agent reads from agentIn</li>
 * <li>Agent writes to agentOut → agentOut pipes to clientIn → Client reads from clientIn</li>
 * </ul>
 *
 * @author Mark Pollack
 */
public class StdioProtocolDriver implements ProtocolDriver {

	@Override
	public void runWithTransports(BiConsumer<AcpClientTransport, AcpAgentTransport> testBlock) {
		try {
			// Create piped streams for bidirectional communication
			// Client → Agent: clientOut pipes to agentIn
			PipedOutputStream clientOut = new PipedOutputStream();
			PipedInputStream agentIn = new PipedInputStream(clientOut, 65536);

			// Agent → Client: agentOut pipes to clientIn
			PipedOutputStream agentOut = new PipedOutputStream();
			PipedInputStream clientIn = new PipedInputStream(agentOut, 65536);

			McpJsonMapper jsonMapper = McpJsonMapper.getDefault();

			// Create transports
			PipedClientTransport clientTransport = new PipedClientTransport(jsonMapper, clientIn, clientOut);
			StdioAcpAgentTransport agentTransport = new StdioAcpAgentTransport(jsonMapper, agentIn, agentOut);

			try {
				testBlock.accept(clientTransport, agentTransport);
			}
			finally {
				// Clean up
				clientTransport.closeGracefully().block();
				agentTransport.closeGracefully().block();
				closeQuietly(clientIn);
				closeQuietly(clientOut);
				closeQuietly(agentIn);
				closeQuietly(agentOut);
			}
		}
		catch (IOException e) {
			throw new RuntimeException("Failed to create piped streams", e);
		}
	}

	private void closeQuietly(java.io.Closeable closeable) {
		try {
			closeable.close();
		}
		catch (IOException ignored) {
		}
	}

	/**
	 * A simple client transport that uses piped streams instead of a real process.
	 * This is similar to StdioAcpClientTransport but reads/writes directly to piped streams.
	 */
	private static class PipedClientTransport implements AcpClientTransport {

		private final McpJsonMapper jsonMapper;

		private final PipedInputStream inputStream;

		private final PipedOutputStream outputStream;

		private volatile boolean isClosing = false;

		PipedClientTransport(McpJsonMapper jsonMapper, PipedInputStream inputStream, PipedOutputStream outputStream) {
			this.jsonMapper = jsonMapper;
			this.inputStream = inputStream;
			this.outputStream = outputStream;
		}

		@Override
		public java.util.List<Integer> protocolVersions() {
			return Collections.singletonList(com.agentclientprotocol.sdk.spec.AcpSchema.LATEST_PROTOCOL_VERSION);
		}

		@Override
		public Mono<Void> connect(
				java.util.function.Function<Mono<com.agentclientprotocol.sdk.spec.AcpSchema.JSONRPCMessage>, Mono<com.agentclientprotocol.sdk.spec.AcpSchema.JSONRPCMessage>> handler) {
			return Mono.fromRunnable(() -> {
				reactor.core.publisher.Sinks.Many<com.agentclientprotocol.sdk.spec.AcpSchema.JSONRPCMessage> inboundSink = reactor.core.publisher.Sinks
					.many()
					.unicast()
					.onBackpressureBuffer();

				// Handle incoming messages
				inboundSink.asFlux().flatMap(message -> Mono.just(message).transform(handler)).subscribe();

				// Start reading in background thread
				Thread readThread = new Thread(() -> {
					try (java.io.BufferedReader reader = new java.io.BufferedReader(
							new java.io.InputStreamReader(inputStream))) {
						String line;
						while (!isClosing && (line = reader.readLine()) != null) {
							try {
								com.agentclientprotocol.sdk.spec.AcpSchema.JSONRPCMessage message = com.agentclientprotocol.sdk.spec.AcpSchema
									.deserializeJsonRpcMessage(jsonMapper, line);
								inboundSink.tryEmitNext(message);
							}
							catch (Exception e) {
								if (!isClosing) {
									throw new RuntimeException("Error parsing message: " + line, e);
								}
							}
						}
					}
					catch (IOException e) {
						if (!isClosing) {
							throw new RuntimeException("Error reading from pipe", e);
						}
					}
					finally {
						inboundSink.tryEmitComplete();
					}
				});
				readThread.setDaemon(true);
				readThread.start();
			});
		}

		@Override
		public Mono<Void> sendMessage(com.agentclientprotocol.sdk.spec.AcpSchema.JSONRPCMessage message) {
			return Mono.fromRunnable(() -> {
				try {
					String jsonMessage = jsonMapper.writeValueAsString(message);
					jsonMessage = jsonMessage.replace("\r\n", "\\n").replace("\n", "\\n").replace("\r", "\\n");
					synchronized (outputStream) {
						outputStream.write(jsonMessage.getBytes(java.nio.charset.StandardCharsets.UTF_8));
						outputStream.write("\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
						outputStream.flush();
					}
				}
				catch (IOException e) {
					throw new RuntimeException("Failed to send message", e);
				}
			});
		}

		@Override
		public Mono<Void> closeGracefully() {
			return Mono.fromRunnable(() -> {
				isClosing = true;
			});
		}

		@Override
		public <T> T unmarshalFrom(Object data, TypeRef<T> typeRef) {
			return jsonMapper.convertValue(data, typeRef);
		}

	}

}
