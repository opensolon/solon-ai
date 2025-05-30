package io.modelcontextprotocol.server.transport;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.spec.*;
import io.modelcontextprotocol.util.Assert;
import org.noear.solon.core.exception.StatusException;
import org.noear.solon.core.handle.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class WebRxStreamableServerTransportProvider implements McpServerTransportProvider {

    /**
     * Logger for this class
     */
    private static final Logger logger = LoggerFactory.getLogger(WebRxStreamableServerTransportProvider.class);

    private static final String MCP_SESSION_ID = "Mcp-Session-Id";
    private static final String APPLICATION_JSON = "application/json";
    private static final String TEXT_EVENT_STREAM = "text/event-stream";
    private static final String DEFAULT_MCP_ENDPOINT = "/mcp";

    private final ObjectMapper objectMapper;
    private final String endpoint;
    /**
     * Map of active client sessions, keyed by session ID
     */
    private final Map<String, McpSession> sessions = new ConcurrentHashMap<>();
    private McpServerSession.Factory sessionFactory;

    public WebRxStreamableServerTransportProvider(final ObjectMapper objectMapper, String endpoint) {
        this.objectMapper = objectMapper;
        this.endpoint = endpoint;
    }


    @Override
    public void setSessionFactory(McpServerSession.Factory sessionFactory) {
        this.sessionFactory = sessionFactory;
    }

    @Override
    public Mono<Void> notifyClients(String method, Map<String, Object> params) {
        if (sessions.isEmpty()) {
            logger.debug("No active sessions to broadcast message to");
            return Mono.empty();
        }

        logger.debug("Attempting to broadcast message to {} active sessions", sessions.size());
        return Flux.fromIterable(sessions.values())
                .flatMap(session -> session.sendNotification(method, params)
                        .doOnError(e -> logger.error("Failed to send message to session {}: {}", session.getId(), e.getMessage()))
                        .onErrorComplete())
                .then();
    }

    @Override
    public Mono<Void> closeGracefully() {
        logger.debug("Initiating graceful shutdown with {} active sessions", sessions.size());
        return Flux.fromIterable(sessions.values()).flatMap(McpSession::closeGracefully).then();
    }

    public void doPost(Context ctx) throws Throwable {
        // 2. Accept header routing
        final String accept = ctx.headerOrDefault("Accept", "");
        final List<String> acceptTypes = Arrays.stream(accept.split(","))
                .map(String::trim)
                .collect(Collectors.toList());

        // todo!!!!
        if (!acceptTypes.contains(APPLICATION_JSON) && !acceptTypes.contains(TEXT_EVENT_STREAM)) {
//            if (legacyTransportProvider instanceof HttpServletSseServerTransportProvider legacy) {
//                legacy.doPost(req, resp);
//            } else {
//                resp.sendError(HttpServletResponse.SC_NOT_ACCEPTABLE, "Legacy transport not available");
//            }
            return;
        }

        // 3. Enable async
        ctx.asyncStart();

        // resp
        ctx.status(200);
        ctx.charset("UTF-8");
        ctx.keepAlive(60);

        final McpServerTransport transport = new StreamableHttpServerTransport(ctx, objectMapper);
        final McpSession session = getOrCreateSession(ctx.header(MCP_SESSION_ID), transport);
        if (!"stateless".equals(session.getId())) {
            ctx.headerSet(MCP_SESSION_ID, session.getId());
        }
        final Flux<McpSchema.JSONRPCMessage> messages = parseRequestBodyAsStream(ctx);

        if (accept.contains(TEXT_EVENT_STREAM)) {
            // TODO: Handle streaming JSON-RPC over HTTP
            ctx.contentType(TEXT_EVENT_STREAM);

            messages.flatMap(session::handle)
                    .doOnError(e -> sendError(ctx, 500, "Streaming failed: " + e.getMessage()))
                    .then(transport.closeGracefully())
                    .subscribe();
        } else if (accept.contains(APPLICATION_JSON)) {
            // TODO: Handle traditional JSON-RPC response
            ctx.contentType(APPLICATION_JSON);

            messages.flatMap(session::handle)
                    .collectList()
                    .flatMap(responses -> {
                        try {
                            String json = new ObjectMapper().writeValueAsString(
                                    responses.size() == 1 ? responses.get(0) : responses
                            );
                            ctx.output(json);
                            return transport.closeGracefully();
                        } catch (IOException e) {
                            return Mono.error(e);
                        }
                    })
                    .doOnError(e -> sendError(ctx, 500, "JSON response failed: " + e.getMessage()))
                    .subscribe();

        } else {
            ctx.status(StatusException.CODE_NOT_ACCEPTABLE, "Unsupported Accept header");
        }
    }

    public void doGet(Context ctx) throws IOException {
        // todo 需要实现新旧实现新旧SSE传输提供者的兼容处理
//        if (legacyTransportProvider instanceof HttpServletSseServerTransportProvider legacy) {
//            legacy.doGet(req, resp);
//        } else {
//            resp.sendError(HttpServletResponse.SC_NOT_ACCEPTABLE, "Legacy transport not available");
//        }
    }

    public void doDelete(Context ctx) throws IOException {
        final String sessionId = ctx.header("mcp-session-id");
        if (sessionId == null || !sessions.containsKey(sessionId)) {
            ctx.status(StatusException.CODE_NOT_FOUND, "Session not found");
            return;
        }

        final McpSession session = sessions.remove(sessionId);
        session.closeGracefully().subscribe();
        ctx.status(StatusException.CODE_NO_CONTENT);
    }

    // todo:!!!
    private Flux<McpSchema.JSONRPCMessage> parseRequestBodyAsStream(final Context req) {
        return Mono.fromCallable(() -> {
            try (final InputStream inputStream = req.bodyAsStream()) {
                final JsonNode node = objectMapper.readTree(inputStream);
                if (node.isArray()) {
                    final List<McpSchema.JSONRPCMessage> messages = new ArrayList<>();
                    for (final JsonNode item : node) {
                        messages.add(objectMapper.treeToValue(item, McpSchema.JSONRPCMessage.class));
                    }
                    return messages;
                } else if (node.isObject()) {
                    return Collections.singletonList(objectMapper.treeToValue(node, McpSchema.JSONRPCMessage.class));
                } else {
                    throw new IllegalArgumentException("Invalid JSON-RPC request: not object or array");
                }
            }
        }).flatMapMany(Flux::fromIterable);
    }

    private McpSession getOrCreateSession(final String sessionId, final McpServerTransport transport) {
        if (sessionId != null && sessionFactory != null) {
            // Reuse or track sessions if you support that; for now, we just create new ones
            return sessions.get(sessionId);
        } else if (sessionFactory != null) {
            final String newSessionId = UUID.randomUUID().toString();
            return sessions.put(newSessionId, sessionFactory.create(transport));
        } else {
            return new StatelessMcpSession(transport);
        }
    }

    private void sendError(final Context resp, final int code, final String msg) {
        try {
            resp.status(code, msg);
        } catch (Exception ignored) {
            logger.debug("Exception during send error");
        }
    }

    public static class StreamableHttpServerTransport implements McpServerTransport {
        private final ObjectMapper objectMapper;
        private final Context ctx;

        public StreamableHttpServerTransport(final Context ctx, final ObjectMapper objectMapper) {
            this.objectMapper = objectMapper;
            this.ctx = ctx;
        }

        @Override
        public Mono<Void> sendMessage(final McpSchema.JSONRPCMessage message) {
            return Mono.fromRunnable(() -> {
                try {
                    String json = objectMapper.writeValueAsString(message);
                    ctx.output(json.getBytes(StandardCharsets.UTF_8));
                    ctx.output(new byte[]{'\n'});
                    ctx.flush();
                } catch (IOException e) {
                    throw new RuntimeException("Failed to send message", e);
                }
            });
        }

        @Override
        public <T> T unmarshalFrom(Object data, TypeReference<T> typeRef) {
            return objectMapper.convertValue(data, typeRef);
        }

        @Override
        public Mono<Void> closeGracefully() {
            return Mono.fromRunnable(() -> {
                try {
                    ctx.flush();
                    ctx.close();
                } catch (IOException e) {
                    // ignore or log
                }
            });
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for creating instances of {@link WebRxSseServerTransportProvider}.
     * <p>
     * This builder provides a fluent API for configuring and creating instances of
     * WebFluxSseServerTransportProvider with custom settings.
     */
    public static class Builder {

        private ObjectMapper objectMapper;

        private String endpoint = DEFAULT_MCP_ENDPOINT;

        /**
         * Sets the ObjectMapper to use for JSON serialization/deserialization of MCP
         * messages.
         * @param objectMapper The ObjectMapper instance. Must not be null.
         * @return this builder instance
         * @throws IllegalArgumentException if objectMapper is null
         */
        public Builder objectMapper(ObjectMapper objectMapper) {
            Assert.notNull(objectMapper, "ObjectMapper must not be null");
            this.objectMapper = objectMapper;
            return this;
        }

        /**
         * Sets the endpoint URI where clients should send their JSON-RPC messages.
         * @param endpoint The endpoint URI. Must not be null.
         * @return this builder instance
         * @throws IllegalArgumentException if endpoint is null
         */
        public Builder endpoint(String endpoint) {
            Assert.notNull(endpoint, "Endpoint must not be null");
            this.endpoint = endpoint;
            return this;
        }

        /**
         * Builds a new instance of {@link WebRxSseServerTransportProvider} with the
         * configured settings.
         * @return A new WebFluxSseServerTransportProvider instance
         * @throws IllegalStateException if required parameters are not set
         */
        public WebRxStreamableServerTransportProvider build() {
            Assert.notNull(objectMapper, "ObjectMapper must be set");
            Assert.notNull(endpoint, "Endpoint must be set");

            return new WebRxStreamableServerTransportProvider(objectMapper, endpoint);
        }
    }
}
