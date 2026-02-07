/*
 * Copyright 2025-2025 the original author or authors.
 */

package com.agentclientprotocol.sdk.agent.transport;

import com.agentclientprotocol.sdk.spec.AcpAgentTransport;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import com.agentclientprotocol.sdk.spec.AcpSchema.JSONRPCMessage;
import com.agentclientprotocol.sdk.util.Assert;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.TypeRef;
import org.noear.solon.net.websocket.WebSocket;
import org.noear.solon.net.websocket.WebSocketRouter;
import org.noear.solon.net.websocket.listener.SimpleWebSocketListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Implementation of the ACP WebSocket transport for agents that accepts WebSocket
 * connections from clients. Uses the Solon WebSocket server.
 *
 * <p>
 * This transport starts an embedded Solon server that listens for WebSocket connections
 * on the configured port. When a client connects, messages are exchanged as JSON-RPC
 * messages over WebSocket text frames.
 * </p>
 *
 * <p>
 * Key features:
 * <ul>
 * <li>Embedded Solon WebSocket server</li>
 * <li>Thread-safe message processing with dedicated schedulers</li>
 * <li>Proper resource management and graceful shutdown</li>
 * <li>Backpressure support via Reactor Sinks</li>
 * </ul>
 *
 * @author Mark Pollack
 */
public class WebSocketAcpAgentTransport implements AcpAgentTransport {

	private static final Logger logger = LoggerFactory.getLogger(WebSocketAcpAgentTransport.class);

	/**
     * Default path for ACP WebSocket endpoints
     */
	public static final String DEFAULT_ACP_PATH = "/acp";

	private final McpJsonMapper jsonMapper;

	private final int port;

	private final String path;

	private final Sinks.Many<JSONRPCMessage> inboundSink;

	private final Sinks.Many<JSONRPCMessage> outboundSink;

	private final Sinks.One<Void> connectionReady = Sinks.one();

	private final Sinks.One<Void> terminationSink = Sinks.one();

	private Scheduler outboundScheduler;

	private final AtomicBoolean isClosing = new AtomicBoolean(false);

	private final AtomicBoolean isStarted = new AtomicBoolean(false);

	private Consumer<Throwable> exceptionHandler = t -> logger.error("Transport error", t);

	private volatile WebSocket clientSession;

	private Duration idleTimeout = Duration.ofMinutes(30);

	/**
     * Creates a new WebSocketAcpAgentTransport on the specified port with default path.
     *
     * @param port       The port to listen on
     * @param jsonMapper The JsonMapper to use for JSON serialization/deserialization
     */
	public WebSocketAcpAgentTransport(int port, McpJsonMapper jsonMapper) {
		this(port, DEFAULT_ACP_PATH, jsonMapper);
	}

	/**
     * Creates a new WebSocketAcpAgentTransport on the specified port and path.
     *
     * @param port       The port to listen on
     * @param path       The WebSocket endpoint path (e.g., "/acp")
     * @param jsonMapper The JsonMapper to use for JSON serialization/deserialization
     */
	public WebSocketAcpAgentTransport(int port, String path, McpJsonMapper jsonMapper) {
		Assert.isTrue(port > 0, "Port must be positive");
		Assert.hasText(path, "Path must not be empty");
		Assert.notNull(jsonMapper, "The JsonMapper can not be null");

		this.port = port;
		this.path = path;
		this.jsonMapper = jsonMapper;

		this.inboundSink = Sinks.many().unicast().onBackpressureBuffer();
		this.outboundSink = Sinks.many().unicast().onBackpressureBuffer();
		// Use daemon thread so JVM can exit if closeGracefully() isn't called
		this.outboundScheduler = Schedulers.fromExecutorService(
                Executors.newSingleThreadExecutor(r -> {
                    Thread t = new Thread(r, "acp-ws-agent-outbound");
                    t.setDaemon(true);
                    return t;
                }), "ws-agent-outbound");
	}

	/**
     * Sets the WebSocket idle timeout.
     *
     * @param timeout The idle timeout
     * @return This transport for chaining
     */
	public WebSocketAcpAgentTransport idleTimeout(Duration timeout) {
		this.idleTimeout = timeout;
		return this;
	}

	/**
     * Returns the port this transport is configured to listen on.
     *
     * @return The port number
     */
	public int getPort() {
		return port;
	}

	@Override
	public Mono<Void> start(Function<Mono<JSONRPCMessage>, Mono<JSONRPCMessage>> handler) {
		if (!isStarted.compareAndSet(false, true)) {
			return Mono.error(new IllegalStateException("Already started"));
		}

		return Mono.fromCallable(() -> {
			logger.info("Starting WebSocket agent server on port {} at path {}", port, path);

			// Set up inbound message handling
			handleIncomingMessages(handler);

			// Register to Solon
			WebSocketRouter.getInstance().of(path, new AcpWebSocketEndpoint());

			startOutboundProcessing();

			logger.info("WebSocket agent server started on port {} at path {}", port, path);
			return null;
		}).then();
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
                })
                .subscribe();
	}

	private void startOutboundProcessing() {
		this.outboundSink.asFlux()
                .publishOn(outboundScheduler)
                .subscribe(message -> {
                    if (message != null && !isClosing.get() && clientSession != null && clientSession.isValid()) {
                        try {
                            String jsonMessage = jsonMapper.writeValueAsString(message);
                            logger.debug("Sending WebSocket message: {}", jsonMessage);
                            clientSession.send(jsonMessage);
                        } catch (Exception e) {
                            if (!isClosing.get()) {
                                logger.error("Error sending WebSocket message", e);
                                exceptionHandler.accept(e);
                            }
                        }
                    }
                });
	}

	@Override
	public Mono<Void> sendMessage(JSONRPCMessage message) {
		return connectionReady.asMono().then(Mono.defer(() -> {
			if (outboundSink.tryEmitNext(message).isSuccess()) {
				return Mono.empty();
			} else {
				return Mono.error(new RuntimeException("Failed to enqueue message"));
			}
		}));
	}

	@Override
	public Mono<Void> closeGracefully() {
		return Mono.fromRunnable(() -> {
			logger.debug("WebSocket agent transport closing gracefully");
			isClosing.set(true);
			inboundSink.tryEmitComplete();
			outboundSink.tryEmitComplete();
		}).then(Mono.fromCallable(() -> {
			if (clientSession != null && clientSession.isValid()) {
				clientSession.close();
			}

			return null;
		})).then(Mono.fromRunnable(() -> {
			try {
				outboundScheduler.dispose();
				logger.debug("WebSocket agent transport closed");
			} catch (Exception e) {
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

	/**
     * Jetty WebSocket endpoint for handling client connections.
     */
	public class AcpWebSocketEndpoint extends SimpleWebSocketListener {

		@Override
		public void onOpen(WebSocket session) {
			logger.info("WebSocket client connected from {}", session.remoteAddress());
			clientSession = session;
			connectionReady.tryEmitValue(null);
		}

		@Override
		public void onMessage(WebSocket session, String message) {
			logger.debug("Received WebSocket message: {}", message);

			try {
				JSONRPCMessage jsonRpcMessage = AcpSchema.deserializeJsonRpcMessage(jsonMapper, message);
				if (!inboundSink.tryEmitNext(jsonRpcMessage).isSuccess()) {
					if (!isClosing.get()) {
						logger.error("Failed to enqueue inbound message");
					}
				}
			} catch (Exception e) {
				if (!isClosing.get()) {
					logger.error("Error processing inbound message", e);
					exceptionHandler.accept(e);
				}
			}
		}

		@Override
		public void onClose(WebSocket session) {
			//logger.info("WebSocket client disconnected: {} - {}", statusCode, reason);
			clientSession = null;
			isClosing.set(true);
			inboundSink.tryEmitComplete();
			terminationSink.tryEmitValue(null);  // Signal termination for awaitTermination()
		}

		@Override
		public void onError(WebSocket session, Throwable error) {
			if (!isClosing.get()) {
				logger.error("WebSocket error", error);
				exceptionHandler.accept(error);
			}
			isClosing.set(true);
			inboundSink.tryEmitComplete();
			terminationSink.tryEmitValue(null);  // Signal termination for awaitTermination()
		}
	}
}
