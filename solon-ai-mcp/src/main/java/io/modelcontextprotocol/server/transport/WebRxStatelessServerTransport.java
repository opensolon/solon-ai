package io.modelcontextprotocol.server.transport;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.McpStatelessServerHandler;
import io.modelcontextprotocol.server.McpTransportContextExtractor;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpStatelessServerTransport;
import io.modelcontextprotocol.util.Assert;
import org.noear.solon.SolonApp;
import org.noear.solon.core.handle.Context;
import org.noear.solon.core.handle.Entity;
import org.noear.solon.core.handle.StatusCodes;
import org.noear.solon.core.util.MimeType;
import org.noear.solon.rx.handle.RxEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * Implementation of a WebFlux based {@link McpStatelessServerTransport}.
 *
 * @author Dariusz Jędrzejczyk
 */
public class WebRxStatelessServerTransport implements McpStatelessServerTransport, IMcpHttpServerTransport {

	private static final Logger logger = LoggerFactory.getLogger(WebRxStatelessServerTransport.class);

	private final McpJsonMapper jsonMapper;

	private final String mcpEndpoint;

	private McpStatelessServerHandler mcpHandler;

	private McpTransportContextExtractor<Context> contextExtractor;

	private volatile boolean isClosing = false;

	private WebRxStatelessServerTransport(McpJsonMapper jsonMapper, String mcpEndpoint,
                                          McpTransportContextExtractor<Context> contextExtractor) {
		Assert.notNull(jsonMapper, "jsonMapper must not be null");
		Assert.notNull(mcpEndpoint, "mcpEndpoint must not be null");
		Assert.notNull(contextExtractor, "contextExtractor must not be null");

		this.jsonMapper = jsonMapper;
		this.mcpEndpoint = mcpEndpoint;
		this.contextExtractor = contextExtractor;
	}

	@Override
	public void toHttpHandler(SolonApp app) {
		if (app != null) {
			app.get(this.mcpEndpoint, this::handleGet);
			app.post(this.mcpEndpoint, this::handlePost);
		}
	}

	@Override
	public String getMcpEndpoint() {
		return mcpEndpoint;
	}


	@Override
	public void setMcpHandler(McpStatelessServerHandler mcpHandler) {
		this.mcpHandler = mcpHandler;
	}

	@Override
	public Mono<Void> closeGracefully() {
		return Mono.fromRunnable(() -> this.isClosing = true);
	}


	private void handleGet(Context ctx) throws Throwable{
		Object entity = doHandleGet(ctx);
		ctx.returnValue(entity);
	}

	private Entity doHandleGet(Context request) {
		return new Entity().status(StatusCodes.CODE_METHOD_NOT_ALLOWED);
	}

	private void handlePost(Context ctx) throws Throwable{
		Mono<Entity> entityMono = doHandlePost(ctx);
		ctx.returnValue(entityMono);
	}

	private Mono<Entity> doHandlePost(Context request) throws Throwable {
		if (isClosing) {
			return RxEntity.status(StatusCodes.CODE_SERVICE_UNAVAILABLE).body("Server is shutting down");
		}

		McpTransportContext transportContext = this.contextExtractor.extract(request);

		String acceptHeaders = request.accept();
		if (!(acceptHeaders.contains(MimeType.APPLICATION_JSON_VALUE)
				&& acceptHeaders.contains(MimeType.TEXT_EVENT_STREAM_VALUE))) {
			return RxEntity.badRequest().build();
		}

		return Mono.just(request.body()).<Entity>flatMap(body -> {
			try {
				McpSchema.JSONRPCMessage message = McpSchema.deserializeJsonRpcMessage(jsonMapper, body);

				if (message instanceof McpSchema.JSONRPCRequest) {
					McpSchema.JSONRPCRequest jsonrpcRequest = (McpSchema.JSONRPCRequest)message;
					return this.mcpHandler.handleRequest(transportContext, jsonrpcRequest).flatMap(jsonrpcResponse -> {
						try {
							String json = jsonMapper.writeValueAsString(jsonrpcResponse);
							return RxEntity.ok().contentType(MimeType.APPLICATION_JSON_VALUE).body(json);
						}
						catch (IOException e) {
							logger.error("Failed to serialize response: {}", e.getMessage());
							return RxEntity.status(StatusCodes.CODE_INTERNAL_SERVER_ERROR)
									.body(new McpError("Failed to serialize response"));
						}
					});
				}
				else if (message instanceof McpSchema.JSONRPCNotification) {
					McpSchema.JSONRPCNotification jsonrpcNotification = (McpSchema.JSONRPCNotification)message;
					return this.mcpHandler.handleNotification(transportContext, jsonrpcNotification)
							.then(RxEntity.accepted().build());
				}
				else {
					return RxEntity.badRequest()
							.body(new McpError("The server accepts either requests or notifications"));
				}
			}
			catch (IllegalArgumentException | IOException e) {
				logger.error("Failed to deserialize message: {}", e.getMessage());
				return RxEntity.badRequest().body(new McpError("Invalid message format"));
			}
		}).contextWrite(ctx -> ctx.put(McpTransportContext.KEY, transportContext));
	}

	/**
	 * Create a builder for the server.
	 * @return a fresh {@link Builder} instance.
	 */
	public static Builder builder() {
		return new Builder();
	}

	/**
	 * Builder for creating instances of {@link WebRxStatelessServerTransport}.
	 * <p>
	 * This builder provides a fluent API for configuring and creating instances of
	 * WebFluxSseServerTransportProvider with custom settings.
	 */
	public static class Builder {

		private McpJsonMapper jsonMapper;

		private String mcpEndpoint = "/mcp";

		private McpTransportContextExtractor<Context> contextExtractor = (serverRequest) -> {
			Map<String,Object> context = new HashMap<>();
			context.put(Context.class.getName(), serverRequest);
			return McpTransportContext.create(context);
		};

		private Builder() {
			// used by a static method
		}

		/**
		 * Sets the JsonMapper to use for JSON serialization/deserialization of MCP
		 * messages.
		 * @param jsonMapper The JsonMapper instance. Must not be null.
		 * @return this builder instance
		 * @throws IllegalArgumentException if jsonMapper is null
		 */
		public Builder jsonMapper(McpJsonMapper jsonMapper) {
			Assert.notNull(jsonMapper, "JsonMapper must not be null");
			this.jsonMapper = jsonMapper;
			return this;
		}

		/**
		 * Sets the endpoint URI where clients should send their JSON-RPC messages.
		 * @param messageEndpoint The message endpoint URI. Must not be null.
		 * @return this builder instance
		 * @throws IllegalArgumentException if messageEndpoint is null
		 */
		public Builder messageEndpoint(String messageEndpoint) {
			Assert.notNull(messageEndpoint, "Message endpoint must not be null");
			this.mcpEndpoint = messageEndpoint;
			return this;
		}

		/**
		 * Sets the context extractor that allows providing the MCP feature
		 * implementations to inspect HTTP transport level metadata that was present at
		 * HTTP request processing time. This allows to extract custom headers and other
		 * useful data for use during execution later on in the process.
		 * @param contextExtractor The contextExtractor to fill in a
		 * {@link McpTransportContext}.
		 * @return this builder instance
		 * @throws IllegalArgumentException if contextExtractor is null
		 */
		public Builder contextExtractor(McpTransportContextExtractor<Context> contextExtractor) {
			Assert.notNull(contextExtractor, "Context extractor must not be null");
			this.contextExtractor = contextExtractor;
			return this;
		}

		/**
		 * Builds a new instance of {@link WebRxStatelessServerTransport} with the
		 * configured settings.
		 * @return A new WebFluxSseServerTransportProvider instance
		 * @throws IllegalStateException if required parameters are not set
		 */
		public WebRxStatelessServerTransport build() {
			Assert.notNull(mcpEndpoint, "Message endpoint must be set");
			return new WebRxStatelessServerTransport(jsonMapper == null ? McpJsonMapper.getDefault() : jsonMapper,
					mcpEndpoint, contextExtractor);
		}
	}
}