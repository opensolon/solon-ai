/*
 * Copyright 2025-2025 the original author or authors.
 */

package com.agentclientprotocol.sdk.client.transport;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.Function;

import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.json.McpJsonMapper;
import com.agentclientprotocol.sdk.spec.AcpClientTransport;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import com.agentclientprotocol.sdk.spec.AcpSchema.JSONRPCMessage;
import com.agentclientprotocol.sdk.util.Assert;
import lombok.var;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

/**
 * Implementation of the ACP Stdio transport that communicates with an agent process using
 * standard input/output streams. Messages are exchanged as newline-delimited JSON-RPC
 * messages over stdin/stdout, with errors and debug information sent to stderr.
 *
 * <p>
 * This is a full-featured transport with:
 * <ul>
 * <li>Thread-safe message processing with dedicated schedulers</li>
 * <li>Proper resource management and graceful shutdown</li>
 * <li>Error stream handling</li>
 * <li>Backpressure support via Reactor Sinks</li>
 * </ul>
 *
 * @author Mark Pollack
 * @author Christian Tzolov (MCP Java SDK)
 * @author Dariusz Jędrzejczyk (MCP Java SDK)
 */
public class StdioAcpClientTransport implements AcpClientTransport {

	private static final Logger logger = LoggerFactory.getLogger(StdioAcpClientTransport.class);

	private final Sinks.Many<JSONRPCMessage> inboundSink;

	private final Sinks.Many<JSONRPCMessage> outboundSink;

	/** The agent process being communicated with */
	private Process process;

	private McpJsonMapper jsonMapper;

	/** Scheduler for handling inbound messages from the agent process */
	private Scheduler inboundScheduler;

	/** Scheduler for handling outbound messages to the agent process */
	private Scheduler outboundScheduler;

	/** Scheduler for handling error messages from the agent process */
	private Scheduler errorScheduler;

	/** Parameters for configuring and starting the agent process */
	private final AgentParameters params;

	private final Sinks.Many<String> errorSink;

	private volatile boolean isClosing = false;

	// visible for tests
	private Consumer<String> stdErrorHandler = error -> logger.info("STDERR Message received: {}", error);

	/**
	 * Creates a new StdioAcpClientTransport with the specified parameters using the default JsonMapper.
	 * @param params The parameters for configuring the agent process
	 */
	public StdioAcpClientTransport(AgentParameters params) {
		this(params, McpJsonMapper.getDefault());
	}

	/**
	 * Creates a new StdioAcpClientTransport with the specified parameters and JsonMapper.
	 * @param params The parameters for configuring the agent process
	 * @param jsonMapper The JsonMapper to use for JSON serialization/deserialization
	 */
	public StdioAcpClientTransport(AgentParameters params, McpJsonMapper jsonMapper) {
		Assert.notNull(params, "The params can not be null");
		Assert.notNull(jsonMapper, "The JsonMapper can not be null");

		this.inboundSink = Sinks.many().unicast().onBackpressureBuffer();
		this.outboundSink = Sinks.many().unicast().onBackpressureBuffer();

		this.params = params;

		this.jsonMapper = jsonMapper;

		this.errorSink = Sinks.many().unicast().onBackpressureBuffer();

		// Start threads - use daemon threads so JVM can exit if closeGracefully() isn't called
		this.inboundScheduler = Schedulers.fromExecutorService(
				Executors.newSingleThreadExecutor(r -> {
					Thread t = new Thread(r, "acp-client-inbound");
					t.setDaemon(true);
					return t;
				}), "inbound");
		this.outboundScheduler = Schedulers.fromExecutorService(
				Executors.newSingleThreadExecutor(r -> {
					Thread t = new Thread(r, "acp-client-outbound");
					t.setDaemon(true);
					return t;
				}), "outbound");
		this.errorScheduler = Schedulers.fromExecutorService(
				Executors.newSingleThreadExecutor(r -> {
					Thread t = new Thread(r, "acp-client-error");
					t.setDaemon(true);
					return t;
				}), "error");
	}

	/**
	 * Starts the agent process and initializes the message processing streams. This
	 * method sets up the process with the configured command, arguments, and environment,
	 * then starts the inbound, outbound, and error processing threads.
	 * @throws RuntimeException if the process fails to start or if the process streams
	 * are null
	 */
	@Override
	public Mono<Void> connect(Function<Mono<JSONRPCMessage>, Mono<JSONRPCMessage>> handler) {
		return Mono.<Void>fromRunnable(() -> {
			logger.info("ACP agent starting.");
			handleIncomingMessages(handler);
			handleIncomingErrors();

			// Prepare command and environment
			List<String> fullCommand = new ArrayList<>();
			fullCommand.add(params.getCommand());
			fullCommand.addAll(params.getArgs());

			ProcessBuilder processBuilder = this.getProcessBuilder();
			processBuilder.command(fullCommand);
			processBuilder.environment().putAll(params.getEnv());

			// Start the process
			try {
				this.process = processBuilder.start();
			}
			catch (IOException e) {
				throw new RuntimeException("Failed to start process with command: " + fullCommand, e);
			}

			// Validate process streams
			if (this.process.getInputStream() == null || process.getOutputStream() == null) {
				this.process.destroy();
				throw new RuntimeException("Process input or output stream is null");
			}

			// Start threads
			startInboundProcessing();
			startOutboundProcessing();
			startErrorProcessing();
			logger.info("ACP agent started");
		});
	}

	/**
	 * Creates and returns a new ProcessBuilder instance. Protected to allow overriding in
	 * tests.
	 * @return A new ProcessBuilder instance
	 */
	protected ProcessBuilder getProcessBuilder() {
		return new ProcessBuilder();
	}

	/**
	 * Sets the handler for processing transport-level errors.
	 *
	 * <p>
	 * The provided handler will be called when errors occur during transport operations,
	 * such as connection failures or protocol violations.
	 * </p>
	 * @param errorHandler a consumer that processes error messages
	 */
	public void setStdErrorHandler(Consumer<String> errorHandler) {
		this.stdErrorHandler = errorHandler;
	}

	/**
	 * Waits for the agent process to exit.
	 * @throws RuntimeException if the process is interrupted while waiting
	 */
	public void awaitForExit() {
		try {
			this.process.waitFor();
		}
		catch (InterruptedException e) {
			throw new RuntimeException("Process interrupted", e);
		}
	}

	/**
	 * Starts the error processing thread that reads from the process's error stream.
	 * Error messages are logged and emitted to the error sink.
	 */
	private void startErrorProcessing() {
		this.errorScheduler.schedule(() -> {
			try (BufferedReader processErrorReader = new BufferedReader(
					new InputStreamReader(process.getErrorStream()))) {
				String line;
				while (!isClosing && (line = processErrorReader.readLine()) != null) {
					try {
						if (!this.errorSink.tryEmitNext(line).isSuccess()) {
							if (!isClosing) {
								logger.error("Failed to emit error message");
							}
							break;
						}
					}
					catch (Exception e) {
						if (!isClosing) {
							logger.error("Error processing error message", e);
						}
						break;
					}
				}
			}
			catch (IOException e) {
				if (!isClosing) {
					logger.error("Error reading from error stream", e);
				}
			}
			finally {
				isClosing = true;
				errorSink.tryEmitComplete();
			}
		});
	}

	private void handleIncomingMessages(Function<Mono<JSONRPCMessage>, Mono<JSONRPCMessage>> inboundMessageHandler) {
		this.inboundSink.asFlux()
			.flatMap(message -> Mono.just(message)
				.transform(inboundMessageHandler)
				.contextWrite(ctx -> ctx.put("observation", "myObservation")))
			.subscribe();
	}

	private void handleIncomingErrors() {
		this.errorSink.asFlux().subscribe(e -> {
			this.stdErrorHandler.accept(e);
		});
	}

	@Override
	public Mono<Void> sendMessage(JSONRPCMessage message) {
		if (this.outboundSink.tryEmitNext(message).isSuccess()) {
			return Mono.empty();
		}
		else {
			return Mono.error(new RuntimeException("Failed to enqueue message"));
		}
	}

	/**
	 * Starts the inbound processing thread that reads JSON-RPC messages from the
	 * process's input stream. Messages are deserialized and emitted to the inbound sink.
	 */
	private void startInboundProcessing() {
		this.inboundScheduler.schedule(() -> {
			try (BufferedReader processReader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
				String line;
				while (!isClosing && (line = processReader.readLine()) != null) {
					try {
						logger.trace("RECV: {}", line);
						JSONRPCMessage message = AcpSchema.deserializeJsonRpcMessage(this.jsonMapper, line);
						if (!this.inboundSink.tryEmitNext(message).isSuccess()) {
							if (!isClosing) {
								logger.error("Failed to enqueue inbound message: {}", message);
							}
							break;
						}
					}
					catch (Exception e) {
						if (!isClosing) {
							logger.error("Error processing inbound message for line: {}", line, e);
						}
						break;
					}
				}
			}
			catch (IOException e) {
				if (!isClosing) {
					logger.error("Error reading from input stream", e);
				}
			}
			finally {
				isClosing = true;
				inboundSink.tryEmitComplete();
			}
		});
	}

	/**
	 * Starts the outbound processing thread that writes JSON-RPC messages to the
	 * process's output stream. Messages are serialized to JSON and written with a newline
	 * delimiter.
	 */
	private void startOutboundProcessing() {
		this.handleOutbound(messages -> messages
			// this bit is important since writes come from user threads, and we
			// want to ensure that the actual writing happens on a dedicated thread
			.publishOn(outboundScheduler)
			.handle((message, s) -> {
				if (message != null && !isClosing) {
					try {
						String jsonMessage = jsonMapper.writeValueAsString(message);
						// Escape any embedded newlines in the JSON message as per spec:
						// Messages are delimited by newlines, and MUST NOT contain
						// embedded newlines.
						jsonMessage = jsonMessage.replace("\r\n", "\\n").replace("\n", "\\n").replace("\r", "\\n");
						logger.trace("SEND: {}", jsonMessage);

						var os = this.process.getOutputStream();
						synchronized (os) {
							os.write(jsonMessage.getBytes(StandardCharsets.UTF_8));
							os.write("\n".getBytes(StandardCharsets.UTF_8));
							os.flush();
						}
						s.next(message);
					}
					catch (IOException e) {
						s.error(new RuntimeException(e));
					}
				}
			}));
	}

	protected void handleOutbound(Function<Flux<JSONRPCMessage>, Flux<JSONRPCMessage>> outboundConsumer) {
		outboundConsumer.apply(outboundSink.asFlux()).doOnComplete(() -> {
			isClosing = true;
			outboundSink.tryEmitComplete();
		}).doOnError(e -> {
			if (!isClosing) {
				logger.error("Error in outbound processing", e);
				isClosing = true;
				outboundSink.tryEmitComplete();
			}
		}).subscribe();
	}

	/**
	 * Gracefully closes the transport by destroying the process and disposing of the
	 * schedulers. This method sends a TERM signal to the process and waits for it to exit
	 * before cleaning up resources.
	 * @return A Mono that completes when the transport is closed
	 */
	@Override
	public Mono<Void> closeGracefully() {
		return Mono.fromRunnable(() -> {
			isClosing = true;
			logger.debug("Initiating graceful shutdown");

			// Complete all sinks to stop accepting new messages
			inboundSink.tryEmitComplete();
			outboundSink.tryEmitComplete();
			errorSink.tryEmitComplete();

			// Destroy process FIRST - this closes streams and unblocks readLine()
			// Use blocking waitFor() instead of Mono.fromFuture(process.onExit())
			// to avoid ForkJoinPool.commonPool (per BEST-PRACTICES-REACTIVE-SCHEDULERS.md Rule 1)
			if (this.process != null) {
				logger.debug("Sending TERM to process");
				this.process.destroy();
				try {
					boolean exited = process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
					if (exited) {
						int exitCode = process.exitValue();
						// 143 = SIGTERM (128+15), 137 = SIGKILL (128+9) - expected when we destroy
						if (exitCode == 0 || exitCode == 143 || exitCode == 137) {
							logger.info("ACP agent process stopped (exit code {})", exitCode);
						}
						else {
							logger.warn("Process terminated unexpectedly with code {}", exitCode);
						}
					}
					else {
						logger.warn("Process did not exit within timeout, forcing kill");
						process.destroyForcibly();
					}
				}
				catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					logger.debug("Interrupted while waiting for process exit");
				}
			}

			// Now that process is dead and streams closed, threads should be unblocked
			try {
				inboundScheduler.dispose();
				errorScheduler.dispose();
				outboundScheduler.dispose();
				logger.debug("Graceful shutdown completed");
			}
			catch (Exception e) {
				logger.error("Error during graceful shutdown", e);
			}
		});
	}

	public Sinks.Many<String> getErrorSink() {
		return this.errorSink;
	}

	@Override
	public <T> T unmarshalFrom(Object data, TypeRef<T> typeRef) {
		return this.jsonMapper.convertValue(data, typeRef);
	}

}
