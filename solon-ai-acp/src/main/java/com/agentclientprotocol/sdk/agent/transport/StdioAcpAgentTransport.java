/*
 * Copyright 2025-2025 the original author or authors.
 */

package com.agentclientprotocol.sdk.agent.transport;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;

import com.agentclientprotocol.sdk.spec.AcpAgentTransport;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import com.agentclientprotocol.sdk.spec.AcpSchema.JSONRPCMessage;
import com.agentclientprotocol.sdk.util.Assert;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.TypeRef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

/**
 * Implementation of the ACP Stdio transport for agents that communicates with clients
 * using standard input/output streams. Messages are exchanged as newline-delimited JSON-RPC
 * messages over stdin/stdout, with errors and debug information sent to stderr.
 *
 * <p>
 * This is the agent-side counterpart to {@code StdioAcpClientTransport}. While the client
 * spawns an agent process and connects to its stdin/stdout, the agent transport reads from
 * the process's System.in and writes to System.out.
 * </p>
 *
 * <p>
 * Key features:
 * <ul>
 * <li>Thread-safe message processing with dedicated schedulers</li>
 * <li>Proper resource management and graceful shutdown</li>
 * <li>Backpressure support via Reactor Sinks</li>
 * </ul>
 *
 * @author Mark Pollack
 */
public class StdioAcpAgentTransport implements AcpAgentTransport {

	private static final Logger logger = LoggerFactory.getLogger(StdioAcpAgentTransport.class);

	private final McpJsonMapper jsonMapper;

	private final InputStream inputStream;

	private final OutputStream outputStream;

	private final Sinks.Many<JSONRPCMessage> inboundSink;

	private final Sinks.Many<JSONRPCMessage> outboundSink;

	private final Sinks.One<Void> inboundReady = Sinks.one();

	private final Sinks.One<Void> outboundReady = Sinks.one();

	private final Sinks.One<Void> terminationSink = Sinks.one();

	private Scheduler inboundScheduler;

	private Scheduler outboundScheduler;

	private final AtomicBoolean isClosing = new AtomicBoolean(false);

	private final AtomicBoolean isStarted = new AtomicBoolean(false);

	private Consumer<Throwable> exceptionHandler = t -> logger.error("Transport error", t);

	/**
	 * Creates a new StdioAcpAgentTransport with the default JsonMapper using
	 * System.in and System.out for communication.
	 */
	public StdioAcpAgentTransport() {
		this(McpJsonMapper.getDefault());
	}

	/**
	 * Creates a new StdioAcpAgentTransport with the specified JsonMapper using
	 * System.in and System.out for communication.
	 * @param jsonMapper The JsonMapper to use for JSON serialization/deserialization
	 */
	public StdioAcpAgentTransport(McpJsonMapper jsonMapper) {
		this(jsonMapper, System.in, System.out);
	}

	/**
	 * Creates a new StdioAcpAgentTransport with the specified JsonMapper and streams.
	 * This constructor allows for custom streams (useful for testing).
	 * @param jsonMapper The JsonMapper to use for JSON serialization/deserialization
	 * @param inputStream The input stream to read messages from (client → agent)
	 * @param outputStream The output stream to write messages to (agent → client)
	 */
	public StdioAcpAgentTransport(McpJsonMapper jsonMapper, InputStream inputStream, OutputStream outputStream) {
		Assert.notNull(jsonMapper, "The JsonMapper can not be null");
		Assert.notNull(inputStream, "The InputStream can not be null");
		Assert.notNull(outputStream, "The OutputStream can not be null");

		this.jsonMapper = jsonMapper;
		this.inputStream = inputStream;
		this.outputStream = outputStream;

		this.inboundSink = Sinks.many().unicast().onBackpressureBuffer();
		this.outboundSink = Sinks.many().unicast().onBackpressureBuffer();

		// Use daemon threads so JVM can exit if closeGracefully() isn't called
		this.inboundScheduler = Schedulers.fromExecutorService(
				Executors.newSingleThreadExecutor(r -> {
					Thread t = new Thread(r, "acp-agent-inbound");
					t.setDaemon(true);
					return t;
				}), "agent-inbound");
		this.outboundScheduler = Schedulers.fromExecutorService(
				Executors.newSingleThreadExecutor(r -> {
					Thread t = new Thread(r, "acp-agent-outbound");
					t.setDaemon(true);
					return t;
				}), "agent-outbound");
	}

	@Override
	public List<Integer> protocolVersions() {
		return Arrays.asList(AcpSchema.LATEST_PROTOCOL_VERSION);
	}

	@Override
	public Mono<Void> start(Function<Mono<JSONRPCMessage>, Mono<JSONRPCMessage>> handler) {
		if (!isStarted.compareAndSet(false, true)) {
			return Mono.error(new IllegalStateException("Already started"));
		}

		return Mono.fromRunnable(() -> {
			logger.info("ACP agent transport starting");
			handleIncomingMessages(handler);
			startInboundProcessing();
			startOutboundProcessing();
			logger.info("ACP agent transport started");
		}).then(Mono.zip(inboundReady.asMono(), outboundReady.asMono()).then());
	}

	private void handleIncomingMessages(Function<Mono<JSONRPCMessage>, Mono<JSONRPCMessage>> handler) {
		this.inboundSink.asFlux()
			.flatMap(message -> Mono.just(message).transform(handler))
			.doOnNext(response -> {
				if (response != null) {
					this.outboundSink.tryEmitNext(response);
				}
			})
			.doOnTerminate(() -> {
				this.outboundSink.tryEmitComplete();
				this.inboundScheduler.dispose();
			})
			.subscribe();
	}

	/**
	 * Starts the inbound processing thread that reads JSON-RPC messages from stdin.
	 * Messages are deserialized and emitted to the inbound sink.
	 */
	private void startInboundProcessing() {
		this.inboundScheduler.schedule(() -> {
			inboundReady.tryEmitValue(null);
			BufferedReader reader = null;
			try {
				reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8));
				while (!isClosing.get()) {
					try {
						String line = reader.readLine();
						if (line == null || isClosing.get()) {
							break;
						}

						logger.debug("Received JSON message: {}", line);

						try {
							JSONRPCMessage message = AcpSchema.deserializeJsonRpcMessage(jsonMapper, line);
							if (!this.inboundSink.tryEmitNext(message).isSuccess()) {
								logIfNotClosing("Failed to enqueue inbound message");
								break;
							}
						}
						catch (Exception e) {
							logIfNotClosing("Error processing inbound message", e);
							exceptionHandler.accept(e);
							break;
						}
					}
					catch (IOException e) {
						logIfNotClosing("Error reading from stdin", e);
						exceptionHandler.accept(e);
						break;
					}
				}
			}
			catch (Exception e) {
				logIfNotClosing("Error in inbound processing", e);
				exceptionHandler.accept(e);
			}
			finally {
				isClosing.set(true);
				inboundSink.tryEmitComplete();
				terminationSink.tryEmitValue(null);  // Signal termination for awaitTermination()
				logger.debug("Agent transport terminated");
			}
		});
	}

	/**
	 * Starts the outbound processing thread that writes JSON-RPC messages to stdout.
	 * Messages are serialized to JSON and written with a newline delimiter.
	 */
	private void startOutboundProcessing() {
		Function<Flux<JSONRPCMessage>, Flux<JSONRPCMessage>> outboundConsumer = messages -> messages
			.doOnSubscribe(subscription -> outboundReady.tryEmitValue(null))
			.publishOn(outboundScheduler)
			.handle((message, sink) -> {
				if (message != null && !isClosing.get()) {
					try {
						String jsonMessage = jsonMapper.writeValueAsString(message);
						// Escape any embedded newlines in the JSON message as per spec:
						// Messages are delimited by newlines, and MUST NOT contain embedded newlines.
						jsonMessage = jsonMessage.replace("\r\n", "\\n").replace("\n", "\\n").replace("\r", "\\n");

						synchronized (outputStream) {
							outputStream.write(jsonMessage.getBytes(StandardCharsets.UTF_8));
							outputStream.write("\n".getBytes(StandardCharsets.UTF_8));
							outputStream.flush();
						}
						logger.debug("Sent JSON message: {}", jsonMessage);
						sink.next(message);
					}
					catch (IOException e) {
						if (!isClosing.get()) {
							logger.error("Error writing message", e);
							exceptionHandler.accept(e);
							sink.error(new RuntimeException(e));
						}
						else {
							logger.debug("Stream closed during shutdown", e);
						}
					}
				}
				else if (isClosing.get()) {
					sink.complete();
				}
			})
			.doOnComplete(() -> {
				isClosing.set(true);
				outboundScheduler.dispose();
			})
			.doOnError(e -> {
				if (!isClosing.get()) {
					logger.error("Error in outbound processing", e);
					isClosing.set(true);
					outboundScheduler.dispose();
				}
			})
			.map(msg -> (JSONRPCMessage) msg);

		outboundConsumer.apply(outboundSink.asFlux()).subscribe();
	}

	@Override
	public Mono<Void> sendMessage(JSONRPCMessage message) {
		return Mono.zip(inboundReady.asMono(), outboundReady.asMono()).then(Mono.defer(() -> {
			if (outboundSink.tryEmitNext(message).isSuccess()) {
				return Mono.empty();
			}
			else {
				return Mono.error(new RuntimeException("Failed to enqueue message"));
			}
		}));
	}

	@Override
	public Mono<Void> closeGracefully() {
		return Mono.fromRunnable(() -> {
			logger.debug("Agent transport closing gracefully");
			isClosing.set(true);
			inboundSink.tryEmitComplete();
			outboundSink.tryEmitComplete();
		}).then(Mono.fromRunnable(() -> {
			try {
				inboundScheduler.dispose();
				outboundScheduler.dispose();
				logger.debug("Agent transport closed");
			}
			catch (Exception e) {
				logger.error("Error during graceful shutdown", e);
			}
		}));
	}

	@Override
	public void setExceptionHandler(Consumer<Throwable> handler) {
		this.exceptionHandler = handler;
	}

	@Override
	public Mono<Void> awaitTermination() {
		return terminationSink.asMono();
	}

	@Override
	public <T> T unmarshalFrom(Object data, TypeRef<T> typeRef) {
		return jsonMapper.convertValue(data, typeRef);
	}

	private void logIfNotClosing(String message) {
		if (!isClosing.get()) {
			logger.error(message);
		}
	}

	private void logIfNotClosing(String message, Exception e) {
		if (!isClosing.get()) {
			logger.error(message, e);
		}
	}

}
