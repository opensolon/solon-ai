/*
 * Copyright 2025-2025 the original author or authors.
 */

package com.agentclientprotocol.sdk.client.transport;

import java.net.URI;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;

import com.agentclientprotocol.sdk.spec.AcpClientTransport;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import com.agentclientprotocol.sdk.spec.AcpSchema.JSONRPCMessage;
import com.agentclientprotocol.sdk.util.Assert;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.TypeRef;
import org.java_websocket.handshake.ServerHandshake;
import org.noear.java_websocket.client.SimpleWebSocketClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

/**
 * Implementation of the ACP WebSocket transport for clients that communicates with an
 * agent using WebSocket connections.
 *
 * <p>
 * Messages are exchanged as JSON-RPC messages over WebSocket text frames.
 * </p>
 *
 * <p>
 * Key features:
 * <ul>
 * <li>Zero external dependencies (uses JDK built-in WebSocket)</li>
 * <li>Thread-safe message processing with dedicated schedulers</li>
 * <li>Proper resource management and graceful shutdown</li>
 * <li>Backpressure support via Reactor Sinks</li>
 * </ul>
 *
 * @author Mark Pollack
 */
public class WebSocketAcpClientTransport implements AcpClientTransport {

	private static final Logger logger = LoggerFactory.getLogger(WebSocketAcpClientTransport.class);

	/**
	 * Default path for ACP WebSocket endpoints
	 */
	public static final String DEFAULT_ACP_PATH = "/acp";

	private final URI serverUri;

	private final McpJsonMapper jsonMapper;

	private final SimpleWebSocketClient websocketClient;

	private final Sinks.Many<JSONRPCMessage> inboundSink;

	private final Sinks.Many<JSONRPCMessage> outboundSink;

	private final Sinks.One<Void> connectionReady = Sinks.one();

	private Scheduler outboundScheduler;

	private final AtomicBoolean isClosing = new AtomicBoolean(false);

	private final AtomicBoolean isConnected = new AtomicBoolean(false);

	private Consumer<Throwable> exceptionHandler = t -> logger.error("Transport error", t);

	private Duration connectTimeout = Duration.ofSeconds(30);

	/**
	 * Creates a new WebSocketAcpClientTransport with the specified server URI and JsonMapper.
	 *
	 * @param serverUri  The WebSocket URI to connect to (e.g., "ws://localhost:8080/acp")
	 * @param jsonMapper The JsonMapper to use for JSON serialization/deserialization
	 */
	public WebSocketAcpClientTransport(URI serverUri, McpJsonMapper jsonMapper) {
		Assert.notNull(serverUri, "The serverUri can not be null");
		Assert.notNull(jsonMapper, "The JsonMapper can not be null");

		this.serverUri = serverUri;
		this.jsonMapper = jsonMapper;
		this.websocketClient = new AcpWebSocketListener(serverUri);

		this.inboundSink = Sinks.many().unicast().onBackpressureBuffer();
		this.outboundSink = Sinks.many().unicast().onBackpressureBuffer();
		// Use daemon thread so JVM can exit if closeGracefully() isn't called
		this.outboundScheduler = Schedulers.fromExecutorService(
				Executors.newSingleThreadExecutor(r -> {
					Thread t = new Thread(r, "acp-ws-client-outbound");
					t.setDaemon(true);
					return t;
				}), "ws-client-outbound");
	}

	/**
	 * Sets the connection timeout for WebSocket establishment.
	 *
	 * @param timeout The connection timeout
	 * @return This transport for chaining
	 */
	public WebSocketAcpClientTransport connectTimeout(Duration timeout) {
		this.connectTimeout = timeout;
		return this;
	}

	@Override
	public Mono<Void> connect(Function<Mono<JSONRPCMessage>, Mono<JSONRPCMessage>> handler) {
		if (!isConnected.compareAndSet(false, true)) {
			return Mono.error(new IllegalStateException("Already connected"));
		}

		return Mono.defer(() -> {
			logger.info("Connecting to WebSocket server at {}", serverUri);

			// Set up inbound message handling
			handleIncomingMessages(handler);

			// Build WebSocket connection with listener
			try {
				websocketClient.connectBlocking(10, TimeUnit.SECONDS);
				websocketClient.heartbeat(20_000, true);

				return Mono.just(websocketClient);
			} catch (Exception e) {
				logger.error("Failed to connect to WebSocket server at {}", serverUri, e);
				return Mono.error(e);
			}
		}).doOnSuccess(ws -> {
			startOutboundProcessing();
			connectionReady.tryEmitValue(null);
			logger.info("Connected to WebSocket server at {}", serverUri);
		}).doOnError(e -> {
			logger.error("Failed to connect to WebSocket server at {}", serverUri, e);
			isConnected.set(false);
			exceptionHandler.accept(e);
		}).doOnCancel(() -> {
			logger.debug("WebSocket connection cancelled");
			isConnected.set(false);
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
					if (message != null && !isClosing.get() && websocketClient.isOpen()) {
						try {
							String jsonMessage = jsonMapper.writeValueAsString(message);
							logger.debug("Sending WebSocket message: {}", jsonMessage);
							websocketClient.send(jsonMessage);
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
			logger.debug("WebSocket transport closing gracefully");
			isClosing.set(true);
			inboundSink.tryEmitComplete();
			outboundSink.tryEmitComplete();
		}).then(Mono.defer(() -> {
			if (websocketClient.isOpen()) {
				websocketClient.close();
				return Mono.empty();
			}
			return Mono.empty();
		})).then(Mono.fromRunnable(() -> {
			try {
				outboundScheduler.dispose();
				logger.debug("WebSocket transport closed");
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
	public <T> T unmarshalFrom(Object data, TypeRef<T> typeRef) {
		return jsonMapper.convertValue(data, typeRef);
	}

	/**
	 * WebSocket.Listener implementation for handling incoming messages.
	 */
	private class AcpWebSocketListener extends SimpleWebSocketClient {

		public AcpWebSocketListener(URI serverUri) {
			super(serverUri);
		}

		public AcpWebSocketListener(String serverUri) {
			super(serverUri);
		}

		@Override
		public void onOpen(ServerHandshake webSocket) {
			logger.debug("WebSocket connection opened");
			//webSocket.request(1);
		}

		@Override
		public void onMessage(String message) {
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
		public void onClose(int code, String reason, boolean remote) {
			logger.info("WebSocket connection closed: {} - {}", code, reason);
			isClosing.set(true);
			inboundSink.tryEmitComplete();
		}

		@Override
		public void onError(Exception error) {
			if (!isClosing.get()) {
				logger.error("WebSocket error", error);
				exceptionHandler.accept(error);
			}
			isClosing.set(true);
			inboundSink.tryEmitComplete();
		}
	}
}
