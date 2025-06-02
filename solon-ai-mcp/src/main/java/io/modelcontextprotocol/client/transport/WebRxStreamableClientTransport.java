package io.modelcontextprotocol.client.transport;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.util.Assert;
import org.noear.solon.net.http.HttpResponse;
import org.noear.solon.net.http.HttpUtils;
import org.noear.solon.net.http.HttpUtilsBuilder;
import org.noear.solon.net.http.textstream.ServerSentEvent;
import org.noear.solon.net.http.textstream.TextStreamUtil;
import org.noear.solon.rx.SimpleSubscriber;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

public class WebRxStreamableClientTransport implements McpClientTransport {

    private static final Logger LOGGER = LoggerFactory.getLogger(WebRxStreamableClientTransport.class);

    private static final String DEFAULT_MCP_ENDPOINT = "/mcp";

    private static final String MCP_SESSION_ID = "Mcp-Session-Id";

    private static final String LAST_EVENT_ID = "Last-Event-ID";

    private static final String ACCEPT = "Accept";

    private static final String CONTENT_TYPE = "Content-Type";

    private static final String APPLICATION_JSON = "application/json";

    private static final String TEXT_EVENT_STREAM = "text/event-stream";

    private static final String APPLICATION_JSON_SEQ = "application/json-seq";

    private static final String DEFAULT_ACCEPT_VALUES = String.format("%s, %s", APPLICATION_JSON, TEXT_EVENT_STREAM);

//    private final HttpClientSseClientTransport sseClientTransport;

    private final WebRxSseClientTransport sseClientTransport;

    private final HttpUtilsBuilder webBuilder;

    private final String endpoint;

//    private final HttpRequest.Builder requestBuilder;

    private final ObjectMapper objectMapper;

    private final AtomicReference<String> lastEventId = new AtomicReference<>();

    private final AtomicReference<String> mcpSessionId = new AtomicReference<>();

    private final AtomicBoolean fallbackToSse = new AtomicBoolean(false);

    public WebRxStreamableClientTransport(final HttpUtilsBuilder webBuilder,
                                   final ObjectMapper objectMapper,
                                          final String endpoint,
                                   final WebRxSseClientTransport sseClientTransport) {
        this.webBuilder = webBuilder;
        this.objectMapper = objectMapper;
        this.endpoint = endpoint;
        this.sseClientTransport = sseClientTransport;
    }

    /**
     * Creates a new instance of StreamableHttpClientTransport using the provided HttpUtilsBuilder.
     *
     * @param webBuilder the HttpUtilsBuilder to use for building HTTP requests
     * @return a new Builder instance
     */
    public static Builder builder(HttpUtilsBuilder webBuilder) {
        return new Builder(webBuilder);
    }

    /**
     * A builder for creating instances of WebSocketClientTransport.
     */
    public static class Builder {

        private final HttpUtilsBuilder webBuilder;

        private ObjectMapper objectMapper = new ObjectMapper();

        private String endpoint = DEFAULT_MCP_ENDPOINT;

        public Builder(HttpUtilsBuilder webBuilder) {
            Assert.notNull(webBuilder, "webBuilder must not be empty");
            this.webBuilder = webBuilder;
        }

        public Builder endpoint(String endpoint) {
            Assert.hasText(endpoint, "endpoint must not be null");
            this.endpoint = endpoint;
            return this;
        }

        public Builder objectMapper(ObjectMapper objectMapper) {
            Assert.notNull(objectMapper, "objectMapper must not be null");
            this.objectMapper = objectMapper;
            return this;
        }

        public WebRxStreamableClientTransport build(){
            return new WebRxStreamableClientTransport(webBuilder, objectMapper, endpoint, new WebRxSseClientTransport(webBuilder, endpoint, objectMapper));
        }

    }

    @Override
    public Mono<Void> connect(final Function<Mono<McpSchema.JSONRPCMessage>, Mono<McpSchema.JSONRPCMessage>> handler) {
        if (fallbackToSse.get()) {
            return sseClientTransport.connect(handler);
        }

        return Mono.defer(() -> Mono.fromFuture(() -> {
                    HttpUtils build = webBuilder.build(endpoint);
                    build.header(ACCEPT, TEXT_EVENT_STREAM);
                    final String lastId = lastEventId.get();
                    if (lastId != null) {
                        build.header(LAST_EVENT_ID, lastId);
                    }
                    if (mcpSessionId.get() != null) {
                        build.header(MCP_SESSION_ID, mcpSessionId.get());
                    }
                    return build.execAsync("POST");
                }).flatMap(response -> {
                    // must like server terminate session and the client need to start a
                    // new session by sending a new `InitializeRequest` without a session
                    // ID attached.
                    if (mcpSessionId.get() != null && response.code() == 404) {
                        mcpSessionId.set(null);
                    }

                    if (response.code() == 405 || response.code() == 404) {
                        LOGGER.warn("Operation not allowed, falling back to SSE");
                        fallbackToSse.set(true);
                        return sseClientTransport.connect(handler);
                    }
                    return handleStreamingResponse(response, handler);
                })
                .retryWhen(Retry.backoff(3, Duration.ofSeconds(3)).filter(err -> err instanceof IllegalStateException))
                .onErrorResume(e -> {
                    LOGGER.error("Streamable transport connection error", e);
                    return Mono.error(e);
                })).doOnTerminate(this::closeGracefully);
    }

    @Override
    public Mono<Void> sendMessage(final McpSchema.JSONRPCMessage message) {
        return sendMessage(message, msg -> msg);
    }

    public Mono<Void> sendMessage(final McpSchema.JSONRPCMessage message,
                                  final Function<Mono<McpSchema.JSONRPCMessage>, Mono<McpSchema.JSONRPCMessage>> handler) {
        if (fallbackToSse.get()) {
            return fallbackToSse(message);
        }

        return serializeJson(message).flatMap(json -> {
            HttpUtils build = webBuilder.build(endpoint)
                    .bodyOfJson(json)
                    .header(ACCEPT, DEFAULT_ACCEPT_VALUES)
                    .header(CONTENT_TYPE, APPLICATION_JSON);
//            final HttpRequest.Builder request = requestBuilder.copy()
//                    .POST(HttpRequest.BodyPublishers.ofString(json))
//                    .header(ACCEPT, DEFAULT_ACCEPT_VALUES)
//                    .header(CONTENT_TYPE, APPLICATION_JSON)
//                    .uri(uri);
            if (mcpSessionId.get() != null) {
//                request.header(MCP_SESSION_ID, mcpSessionId.get());
                build.header(MCP_SESSION_ID, mcpSessionId.get());
            }

            return Mono.fromFuture(build.execAsync("POST"))
                    .flatMap(response -> {

                        // server may assign a session ID at initialization time, if yes we
                        // have to use it for any subsequent requests
                        if (message instanceof McpSchema.JSONRPCRequest
                                && ((McpSchema.JSONRPCRequest) message).getMethod().equals(McpSchema.METHOD_INITIALIZE)) {
                            String sessionId = response.header(MCP_SESSION_ID);
                            if (sessionId != null) {
                                mcpSessionId.set(sessionId);
                            }
                        }

                        // If the response is 202 Accepted, there's no body to process
                        if (response.code() == 202) {
                            return Mono.empty();
                        }

                        // must like server terminate session and the client need to start a
                        // new session by sending a new `InitializeRequest` without a session
                        // ID attached.
                        if (mcpSessionId.get() != null && response.code() == 404) {
                            mcpSessionId.set(null);
                        }

                        if (response.code() == 405 || response.code() == 404) {
                            LOGGER.warn("Operation not allowed, falling back to SSE");
                            fallbackToSse.set(true);
                            return fallbackToSse(message);
                        }

                        if (response.code() >= 400) {
                            return Mono
                                    .error(new IllegalArgumentException("Unexpected status code: " + response.code()));
                        }

                        return handleStreamingResponse(response, handler);
                    });
        }).onErrorResume(e -> {
            LOGGER.error("Streamable transport sendMessages error", e);
            return Mono.error(e);
        });

    }

    private Mono<Void> fallbackToSse(final McpSchema.JSONRPCMessage msg) {
        if (msg instanceof McpSchema.JSONRPCBatchRequest) {
            McpSchema.JSONRPCBatchRequest batchReq = (McpSchema.JSONRPCBatchRequest) msg;
            return Flux.fromIterable(batchReq.getItems())
                    .flatMap(sseClientTransport::sendMessage)
                    .then();
        }

        if (msg instanceof McpSchema.JSONRPCBatchResponse) {
            McpSchema.JSONRPCBatchResponse batch = (McpSchema.JSONRPCBatchResponse) msg;
            return Flux.fromIterable(batch.getItems())
                    .flatMap(sseClientTransport::sendMessage)
                    .then();
        }

        return sseClientTransport.sendMessage(msg);
    }

    private Mono<String> serializeJson(final McpSchema.JSONRPCMessage msg) {
        try {
            return Mono.just(objectMapper.writeValueAsString(msg));
        } catch (IOException e) {
            LOGGER.error("Error serializing JSON-RPC message", e);
            return Mono.error(e);
        }
    }

    private Mono<Void> handleStreamingResponse(final HttpResponse response,
                                               final Function<Mono<McpSchema.JSONRPCMessage>, Mono<McpSchema.JSONRPCMessage>> handler) {
        final String contentType = response.header(CONTENT_TYPE);
//        final String contentType = response.headers().firstValue(CONTENT_TYPE).orElse("");
        if (contentType.contains(APPLICATION_JSON_SEQ)) {
            return handleJsonStream(response, handler);
        } else if (contentType.contains(TEXT_EVENT_STREAM)) {
            return handleSseStream(response, handler);
        } else if (contentType.contains(APPLICATION_JSON)) {
            return handleSingleJson(response, handler);
        }
        return Mono.error(new UnsupportedOperationException("Unsupported Content-Type: " + contentType));
    }

    private Mono<Void> handleSingleJson(final HttpResponse response,
                                        final Function<Mono<McpSchema.JSONRPCMessage>, Mono<McpSchema.JSONRPCMessage>> handler) {
        return Mono.fromCallable(() -> {
            try {
                final McpSchema.JSONRPCMessage msg = McpSchema.deserializeJsonRpcMessage(objectMapper,
                        new String(response.bodyAsBytes(), StandardCharsets.UTF_8));
                return handler.apply(Mono.just(msg));
            } catch (IOException e) {
                LOGGER.error("Error processing JSON response", e);
                return Mono.error(e);
            }
        }).flatMap(Function.identity()).then();
    }

    private Mono<Void> handleJsonStream(final HttpResponse response,
                                        final Function<Mono<McpSchema.JSONRPCMessage>, Mono<McpSchema.JSONRPCMessage>> handler) {
        return Flux.fromStream(new BufferedReader(new InputStreamReader(response.body())).lines()).flatMap(jsonLine -> {
            try {
                final McpSchema.JSONRPCMessage message = McpSchema.deserializeJsonRpcMessage(objectMapper, jsonLine);
                return handler.apply(Mono.just(message));
            } catch (IOException e) {
                LOGGER.error("Error processing JSON line", e);
                return Mono.error(e);
            }
        }).then();
    }

    private Mono<Void> handleSseStream(final HttpResponse response,
                                       final Function<Mono<McpSchema.JSONRPCMessage>, Mono<McpSchema.JSONRPCMessage>> handler) {

        return Mono.create(sink -> {
            try {
                TextStreamUtil.parseSseStream(response.body(), new SimpleSubscriber<ServerSentEvent>()
                        .doOnNext(sseEvent -> {
                            String rawData = sseEvent.getData();
                            try {
                                JsonNode node = objectMapper.readTree(rawData);
                                List<McpSchema.JSONRPCMessage> messages = new ArrayList<>();
                                if (node.isArray()) {
                                    for (JsonNode item : node) {
                                        messages.add(McpSchema.deserializeJsonRpcMessage(objectMapper, item.toString()));
                                    }
                                } else if (node.isObject()) {
                                    messages.add(McpSchema.deserializeJsonRpcMessage(objectMapper, node.toString()));
                                } else {
                                    String warning = "Unexpected JSON in SSE data: " + rawData;
                                    LOGGER.warn(warning);
                                    sink.error(new IllegalArgumentException(warning));
                                    return;
                                }

                                for (McpSchema.JSONRPCMessage message : messages) {
                                    handler.apply(Mono.just(message));
                                }

                                if (!sseEvent.id().isEmpty()) {
                                    lastEventId.set(sseEvent.id());
                                }
                            } catch (IOException e) {
                                LOGGER.error("Error parsing SSE JSON: {}", rawData, e);
                                sink.error(e);
                                return;
                            }
                        })
                        .doOnComplete(() -> {
                            sink.success();
                        })
                        .doOnError(err -> {
                            sink.error(err);
                        }));
            } catch (IOException e) {
                sink.error(e);
            }
        });
    }

    @Override
    public Mono<Void> closeGracefully() {
        mcpSessionId.set(null);
        lastEventId.set(null);
        if (fallbackToSse.get()) {
            return sseClientTransport.closeGracefully();
        }
        return Mono.empty();
    }

    @Override
    public <T> T unmarshalFrom(final Object data, final TypeReference<T> typeRef) {
        return objectMapper.convertValue(data, typeRef);
    }
}
