/*
 * Copyright 2025-2026 the original author or authors.
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
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * 支持多租户的 ACP WebSocket 传输实现。
 * 通过将会话上下文存储在 WebSocket 的属性（attr）中，支持多个客户端同时连接或重复连接。
 *
 * @author Gemini & noear
 */
public class WebSocketSolonAcpAgentTransport implements AcpAgentTransport {

	private static final Logger logger = LoggerFactory.getLogger(WebSocketSolonAcpAgentTransport.class);

	public static final String DEFAULT_ACP_PATH = "/acp";
	private static final String ATTR_SESSION_CTX = "ACP_SESSION_CONTEXT";

	private final McpJsonMapper jsonMapper;
	private final String path;
	private final AtomicBoolean isStarted = new AtomicBoolean(false);
	private final AtomicBoolean isClosing = new AtomicBoolean(false);
	private final Sinks.One<Void> terminationSink = Sinks.one();

	// 追踪所有活跃的会话上下文，用于优雅停机
	private final Set<AcpSessionContext> activeContexts = ConcurrentHashMap.newKeySet();

	private Duration idleTimeout = Duration.ofMinutes(30);
	private Consumer<Throwable> exceptionHandler = t -> logger.error("Transport error", t);

	// 业务处理函数，由 Agent 在 start 时注入，所有 Session 共享此逻辑
	private Function<Mono<JSONRPCMessage>, Mono<JSONRPCMessage>> messageHandler;

	/**
	 * 会话上下文：封装每个连接独立的资源
	 */
	private static class AcpSessionContext {
		final Sinks.Many<JSONRPCMessage> inboundSink = Sinks.many().unicast().onBackpressureBuffer();
		final Sinks.Many<JSONRPCMessage> outboundSink = Sinks.many().unicast().onBackpressureBuffer();
		final Sinks.One<Void> connectionReady = Sinks.one();
		final AtomicBoolean sessionClosing = new AtomicBoolean(false);
		final Scheduler outboundScheduler;
		final String socketId;

		AcpSessionContext(String socketId) {
			this.socketId = socketId;
			// 为每个 Session 创建独立的单线程调度器，保证发送顺序
			this.outboundScheduler = Schedulers.fromExecutorService(
					Executors.newSingleThreadExecutor(r -> {
						Thread t = new Thread(r, "acp-ws-out-" + socketId);
						t.setDaemon(true);
						return t;
					}), "ws-out-" + socketId);
		}

		void dispose() {
			if (sessionClosing.compareAndSet(false, true)) {
				inboundSink.tryEmitComplete();
				outboundSink.tryEmitComplete();
				outboundScheduler.dispose();
			}
		}
	}

	public WebSocketSolonAcpAgentTransport(McpJsonMapper jsonMapper) {
		this(DEFAULT_ACP_PATH, jsonMapper);
	}

	public WebSocketSolonAcpAgentTransport(String path, McpJsonMapper jsonMapper) {
		Assert.hasText(path, "Path must not be empty");
		Assert.notNull(jsonMapper, "The JsonMapper can not be null");
		this.path = path;
		this.jsonMapper = jsonMapper;
	}

	public WebSocketSolonAcpAgentTransport idleTimeout(Duration timeout) {
		this.idleTimeout = timeout;
		return this;
	}

	@Override
	public Mono<Void> start(Function<Mono<JSONRPCMessage>, Mono<JSONRPCMessage>> handler) {
		if (!isStarted.compareAndSet(false, true)) {
			return Mono.error(new IllegalStateException("Transport already started"));
		}

		this.messageHandler = handler;

		return Mono.fromCallable(() -> {
			logger.info("Starting Multi-tenant WebSocket ACP Agent at path: {}", path);
			// 注册 Solon WebSocket 路由
			WebSocketRouter.getInstance().of(path, new AcpWebSocketEndpoint());
			return null;
		}).then();
	}

	/**
	 * 初始化特定会话的消息流处理管道
	 */
	private void initSessionPipeline(WebSocket session, AcpSessionContext ctx) {
		activeContexts.add(ctx);

		// 1. 处理入站消息：从 Client 到 Agent Handler
		ctx.inboundSink.asFlux()
				.flatMap(message ->
						Mono.just(message)
								.transform(messageHandler)
								// 使用弹性调度器处理业务逻辑，防止阻塞 WebSocket IO 线程
								.subscribeOn(Schedulers.boundedElastic())
								.onErrorResume(e -> {
									logger.error("Handler error on session {}", ctx.socketId, e);
									return Mono.empty();
								})
				)
				.doOnNext(response -> {
					if (response != null) {
						ctx.outboundSink.tryEmitNext(response);
					}
				})
				.doFinally(signal -> activeContexts.remove(ctx))
				.subscribe();

		// 2. 处理出站消息：从 Sink 发送到物理 WebSocket
		ctx.outboundSink.asFlux()
				.publishOn(ctx.outboundScheduler)
				.subscribe(message -> {
					if (message != null && !ctx.sessionClosing.get() && session.isValid()) {
						try {
							String json = jsonMapper.writeValueAsString(message);
							logger.debug("Pushing message to {}: {}", session.id(), json);
							session.send(json);
						} catch (Exception e) {
							if (!ctx.sessionClosing.get()) {
								logger.error("WebSocket send failed for session {}", session.id(), e);
								exceptionHandler.accept(e);
							}
						}
					}
				});
	}

	@Override
	public Mono<Void> sendMessage(JSONRPCMessage message) {
		// 多租户模式下，若需主动推送，需遍历所有活跃 Session
		// 这里提供一个广播逻辑作为示例（或维持 Unsupported）
		return Mono.fromRunnable(() -> {
			for (AcpSessionContext ctx : activeContexts) {
				ctx.outboundSink.tryEmitNext(message);
			}
		});
	}

	@Override
	public Mono<Void> closeGracefully() {
		return Mono.fromRunnable(() -> {
			if (isClosing.compareAndSet(false, true)) {
				logger.info("Stopping WebSocket ACP transport and clearing {} active sessions...", activeContexts.size());
				isStarted.set(false);

				// 清理所有活跃会话
				activeContexts.forEach(AcpSessionContext::dispose);
				activeContexts.clear();

				// 触发 awaitTermination 的信号
				terminationSink.tryEmitValue(null);
			}
		});
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
	 * WebSocket 事件监听器实现
	 */
	public class AcpWebSocketEndpoint extends SimpleWebSocketListener {

		@Override
		public void onOpen(WebSocket session) {
			if (isClosing.get()) {
				session.close();
				return;
			}

			logger.info("ACP Client connected: {} (IP: {})", session.id(), session.remoteAddress());
			session.setIdleTimeout(idleTimeout.toMillis());

			AcpSessionContext ctx = new AcpSessionContext(session.id());
			session.attr(ATTR_SESSION_CTX, ctx);

			initSessionPipeline(session, ctx);
			ctx.connectionReady.tryEmitValue(null);
		}

		@Override
		public void onMessage(WebSocket session, String message) {
			AcpSessionContext ctx = session.attr(ATTR_SESSION_CTX);
			if (ctx == null || ctx.sessionClosing.get()) return;

			logger.debug("Received from {}: {}", session.id(), message);
			try {
				JSONRPCMessage jsonRpcMessage = AcpSchema.deserializeJsonRpcMessage(jsonMapper, message);
				if (!ctx.inboundSink.tryEmitNext(jsonRpcMessage).isSuccess()) {
					logger.warn("Inbound buffer full or closed for session {}", session.id());
				}
			} catch (Exception e) {
				logger.error("Deserialization error from {}", session.id(), e);
				exceptionHandler.accept(e);
			}
		}

		@Override
		public void onClose(WebSocket session) {
			AcpSessionContext ctx = session.attr(ATTR_SESSION_CTX);
			if (ctx != null) {
				session.attrMap().remove(ATTR_SESSION_CTX);
				ctx.dispose();
				activeContexts.remove(ctx);
				logger.info("ACP Client disconnected: {}", session.id());
			}
		}

		@Override
		public void onError(WebSocket session, Throwable error) {
			if (!isClosing.get()) {
				logger.error("WebSocket error on session {}", session != null ? session.id() : "unknown", error);
				exceptionHandler.accept(error);
			}
			if (session != null) {
				onClose(session);
			}
		}
	}
}