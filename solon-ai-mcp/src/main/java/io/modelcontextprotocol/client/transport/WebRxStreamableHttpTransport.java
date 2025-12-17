/*
 * Copyright 2025-2025 the original author or authors.
 */
package io.modelcontextprotocol.client.transport;

import io.modelcontextprotocol.client.McpAsyncClient;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.spec.*;
import io.modelcontextprotocol.util.Assert;
import io.modelcontextprotocol.util.Utils;
import lombok.var;
import org.noear.solon.core.handle.StatusCodes;
import org.noear.solon.core.util.MimeType;
import org.noear.solon.net.http.HttpResponse;
import org.noear.solon.net.http.HttpResponseException;
import org.noear.solon.net.http.HttpUtils;
import org.noear.solon.net.http.HttpUtilsBuilder;
import org.noear.solon.net.http.textstream.ServerSentEvent;
import org.noear.solon.net.http.textstream.TextStreamUtil;
import org.reactivestreams.Publisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.function.Tuple2;
import reactor.util.function.Tuples;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * An implementation of the Streamable HTTP protocol as defined by the
 * <code>2025-03-26</code> version of the MCP specification.
 *
 * <p>
 * The transport is capable of resumability and reconnects. It reacts to transport-level
 * session invalidation and will propagate {@link McpTransportSessionNotFoundException
 * appropriate exceptions} to the higher level abstraction layer when needed in order to
 * allow proper state management. The implementation handles servers that are stateful and
 * provide session meta information, but can also communicate with stateless servers that
 * do not provide a session identifier and do not support SSE streams.
 * </p>
 * <p>
 * This implementation does not handle backwards compatibility with the <a href=
 * "https://modelcontextprotocol.io/specification/2024-11-05/basic/transports#http-with-sse">"HTTP
 * with SSE" transport</a>. In order to communicate over the phased-out
 * <code>2024-11-05</code> protocol, use {@link WebRxSseClientTransport} or
 * {@link WebRxSseClientTransport}.
 * </p>
 *
 * @author Dariusz Jędrzejczyk
 * @author noear
 * @see <a href=
 * "https://modelcontextprotocol.io/specification/2025-03-26/basic/transports#streamable-http">Streamable
 * HTTP transport specification</a>
 */
public class WebRxStreamableHttpTransport implements McpClientTransport {
    private static final String MISSING_SESSION_ID = "[missing_session_id]";

    private static final Logger logger = LoggerFactory.getLogger(WebRxStreamableHttpTransport.class);

    private static final String DEFAULT_ENDPOINT = "/mcp";

    /**
     * Event type for JSON-RPC messages received through the SSE connection. The server
     * sends messages with this event type to transmit JSON-RPC protocol data.
     */
    private static final String MESSAGE_EVENT_TYPE = "message";

    private final McpJsonMapper jsonMapper;

    private final HttpUtilsBuilder webClientBuilder;

    private final String endpoint;

    private final boolean openConnectionOnStartup;

    private final boolean resumableStreams;

    private final AtomicReference<McpTransportSession<Disposable>> activeSession = new AtomicReference<>();

    private final AtomicReference<Function<Mono<McpSchema.JSONRPCMessage>, Mono<McpSchema.JSONRPCMessage>>> handler = new AtomicReference<>();

    private final AtomicReference<Consumer<Throwable>> exceptionHandler = new AtomicReference<>();

    private final List<String> supportedProtocolVersions;

    private final String latestSupportedProtocolVersion;

    private WebRxStreamableHttpTransport(McpJsonMapper jsonMapper,
                                         HttpUtilsBuilder webClientBuilder,
                                         String endpoint,
                                         boolean resumableStreams,
                                         boolean openConnectionOnStartup,
                                         List<String> supportedProtocolVersions) {
        this.jsonMapper = jsonMapper;
        this.webClientBuilder = webClientBuilder;
        this.endpoint = endpoint;
        this.resumableStreams = resumableStreams;
        this.openConnectionOnStartup = openConnectionOnStartup;
        this.activeSession.set(createTransportSession());
        this.supportedProtocolVersions = new ArrayList<>(supportedProtocolVersions);
        this.latestSupportedProtocolVersion = this.supportedProtocolVersions.stream()
                .sorted(Comparator.reverseOrder())
                .findFirst()
                .get();
    }

    @Override
    public List<String> protocolVersions() {
        return supportedProtocolVersions;
    }

    /**
     * Create a stateful builder for creating {@link WebRxStreamableHttpTransport}
     * instances.
     * @param webClientBuilder the {@link HttpUtilsBuilder} to use
     * @return a builder which will create an instance of
     * {@link WebRxStreamableHttpTransport} once {@link Builder#build()} is called
     */
    public static Builder builder(HttpUtilsBuilder webClientBuilder) {
        return new Builder(webClientBuilder);
    }

    @Override
    public Mono<Void> connect(Function<Mono<McpSchema.JSONRPCMessage>, Mono<McpSchema.JSONRPCMessage>> handler) {
        return Mono.deferContextual(ctx -> {
            this.handler.set(handler);
            if (openConnectionOnStartup) {
                logger.debug("Eagerly opening connection on startup");
                return this.reconnect(null).then();
            }
            return Mono.empty();
        });
    }

    private DefaultMcpTransportSession createTransportSession() {
        Function<String, Publisher<Void>> onClose = sessionId -> {
            if (sessionId == null) {
                return Mono.empty();
            } else {
                return Mono.fromFuture(webClientBuilder.build(this.endpoint)
                                .header(HttpHeaders.MCP_SESSION_ID, sessionId)
                                .header(HttpHeaders.PROTOCOL_VERSION, this.latestSupportedProtocolVersion)
                                .execAsync("DELETE"))
                        .onErrorComplete(e -> {
                            logger.warn("Got error when closing transport", e);
                            return true;
                        })
                        .then();
            }
        };

        return new DefaultMcpTransportSession(onClose);
    }

    private McpTransportSession<Disposable> createClosedSession(McpTransportSession<Disposable> existingSession) {
        var existingSessionId = Optional.ofNullable(existingSession)
                .filter(session -> !(session instanceof ClosedMcpTransportSession))
                .flatMap(McpTransportSession::sessionId)
                .orElse(null);
        return new ClosedMcpTransportSession<>(existingSessionId);
    }

    @Override
    public void setExceptionHandler(Consumer<Throwable> handler) {
        logger.debug("Exception handler registered");
        this.exceptionHandler.set(handler);
    }

    private void handleException(Throwable t) {
        logger.debug("Handling exception for session {}", sessionIdOrPlaceholder(this.activeSession.get()), t);
        if (t instanceof McpTransportSessionNotFoundException) {
            McpTransportSession<?> invalidSession = this.activeSession.getAndSet(createTransportSession());
            logger.warn("Server does not recognize session {}. Invalidating.", invalidSession.sessionId());
            invalidSession.close();
        }
        Consumer<Throwable> handler = this.exceptionHandler.get();
        if (handler != null) {
            handler.accept(t);
        }
    }

    @Override
    public Mono<Void> closeGracefully() {
        return Mono.defer(() -> {
            logger.debug("Graceful close triggered");
            McpTransportSession<Disposable> currentSession = this.activeSession.getAndUpdate(this::createClosedSession);
            if (currentSession != null) {
                return Mono.from(currentSession.closeGracefully());
            }
            return Mono.empty();
        });
    }

    private Mono<Disposable> reconnect(McpTransportStream<Disposable> stream) {
        return Mono.deferContextual(ctx -> {
            if (stream != null) {
                logger.debug("Reconnecting stream {} with lastId {}", stream.streamId(), stream.lastId());
            }
            else {
                logger.debug("Reconnecting with no prior stream");
            }
            // Here we attempt to initialize the client. In case the server supports SSE,
            // we will establish a long-running
            // session here and listen for messages. If it doesn't, that's ok, the server
            // is a simple, stateless one.
            final AtomicReference<Disposable> disposableRef = new AtomicReference<>();
            final McpTransportSession<Disposable> transportSession = this.activeSession.get();


            Disposable connection = Mono.fromFuture(webClientBuilder.build(this.endpoint)
                            .accept(MimeType.TEXT_EVENT_STREAM_VALUE)
                            .header(HttpHeaders.PROTOCOL_VERSION,
                                    ctx.getOrDefault(McpAsyncClient.NEGOTIATED_PROTOCOL_VERSION,
                                            this.latestSupportedProtocolVersion))
                            .fill(http -> {
                                transportSession.sessionId().ifPresent(id -> http.header(HttpHeaders.MCP_SESSION_ID, id));
                                if (stream != null) {
                                    stream.lastId().ifPresent(id -> http.header(HttpHeaders.LAST_EVENT_ID, id));
                                }
                            })
                            .execAsync("GET"))
                    .flatMapMany(response -> {
                        if (isEventStream(response)) {
                            logger.debug("Established SSE stream via GET");
                            return eventStream(stream, response);
                        }
                        else if (isNotAllowed(response)) {
                            logger.debug("The server does not support SSE streams, using request-response mode.");
                            return Flux.empty();
                        }
                        else if (isNotFound(response)) {
                            if (transportSession.sessionId().isPresent()) {
                                String sessionIdRepresentation = sessionIdOrPlaceholder(transportSession);
                                return mcpSessionNotFoundError(sessionIdRepresentation);
                            }
                            else {
                                return this.extractError(response, MISSING_SESSION_ID);
                            }
                        }
                        else {
                            //todo:优化了 StreamableHttp 模式下 服务端正常返回时 客户端异常日志打印的情况
                            String sessionRepresentation = sessionIdOrPlaceholder(transportSession);
                            if (response.code() >= StatusCodes.CODE_OK && response.code() < StatusCodes.CODE_MULTIPLE_CHOICES) {
                                String ct = response.contentType();
                                logger.info("GET returned {} with content-type '{}', no SSE opened for session {}", response.code(), ct, sessionRepresentation);
                                return Flux.empty();
                            }
                            return this.extractError(response, sessionRepresentation);

//                            return Flux.<McpSchema.JSONRPCMessage>error(response.createError()).doOnError(e -> {
//                                logger.info("Opening an SSE stream failed. This can be safely ignored.", e);
//                            });
                        }
                    })
                    .flatMap(jsonrpcMessage -> this.handler.get().apply(Mono.just(jsonrpcMessage)))
                    .onErrorComplete(t -> {
                        this.handleException(t);
                        return true;
                    })
                    .doFinally(s -> {
                        Disposable ref = disposableRef.getAndSet(null);
                        if (ref != null) {
                            transportSession.removeConnection(ref);
                        }
                    })
                    .contextWrite(ctx)
                    .subscribe();

            disposableRef.set(connection);
            transportSession.addConnection(connection);
            return Mono.just(connection);
        });
    }

    @Override
    public Mono<Void> sendMessage(McpSchema.JSONRPCMessage message) {
        return Mono.create(sink -> {
            logger.debug("Sending message {}", message);
            // Here we attempt to initialize the client.
            // In case the server supports SSE, we will establish a long-running session
            // here and
            // listen for messages.
            // If it doesn't, nothing actually happens here, that's just the way it is...
            final AtomicReference<Disposable> disposableRef = new AtomicReference<>();
            final McpTransportSession<Disposable> transportSession = this.activeSession.get();

            String messageJsonStr0 = null;

            try {
                messageJsonStr0 = jsonMapper.writeValueAsString(message);
            } catch (Exception e) {
                sink.error(e);
                return;
            }

            String messageJsonStr = messageJsonStr0;

            Disposable connection = Mono.deferContextual(ctx -> Mono.fromFuture(webClientBuilder.build(this.endpoint)
                            .accept(MimeType.APPLICATION_JSON_VALUE + ", " + MimeType.TEXT_EVENT_STREAM_VALUE)
                            .header(HttpHeaders.PROTOCOL_VERSION,
                                    ctx.getOrDefault(McpAsyncClient.NEGOTIATED_PROTOCOL_VERSION,
                                            this.latestSupportedProtocolVersion))
                            .fill(http -> {
                                transportSession.sessionId().ifPresent(id -> http.header(HttpHeaders.MCP_SESSION_ID, id));
                            })
                            .bodyOfJson(messageJsonStr).execAsync("POST")))
                    .flatMapMany(response -> {
                        if (transportSession
                                .markInitialized(response.header(HttpHeaders.MCP_SESSION_ID))) {
                            // Once we have a session, we try to open an async stream for
                            // the server to send notifications and requests out-of-band.
                            reconnect(null).contextWrite(sink.contextView()).subscribe();
                        }

                        String sessionRepresentation = sessionIdOrPlaceholder(transportSession);

                        // The spec mentions only ACCEPTED, but the existing SDKs can return
                        // 200 OK for notifications
                        if (response.code() >= 200 && response.code() < 300) {
                            String contentType = response.contentType();
                            // Existing SDKs consume notifications with no response body nor
                            // content type
                            if (contentType == null || contentType.isEmpty() || contentType.length() == 0) {
                                logger.trace("Message was successfully sent via POST for session {}",
                                        sessionRepresentation);
                                // signal the caller that the message was successfully
                                // delivered
                                sink.success();
                                // communicate to downstream there is no streamed data coming
                                return Flux.empty();
                            } else {
                                if (contentType.startsWith(MimeType.TEXT_EVENT_STREAM_VALUE)) {
                                    logger.debug("Established SSE stream via POST");
                                    // communicate to caller that the message was delivered
                                    sink.success();
                                    // starting a stream
                                    return newEventStream(response, sessionRepresentation);
                                } else if (contentType.startsWith(MimeType.APPLICATION_JSON_VALUE)) {
                                    logger.trace("Received response to POST for session {}", sessionRepresentation);
                                    // communicate to caller the message was delivered
                                    sink.success();
                                    return directResponseFlux(message, response);
                                } else {
                                    logger.warn("Unknown media type {} returned for POST in session {}", contentType,
                                            sessionRepresentation);
                                    return Flux.error(new RuntimeException("Unknown media type returned: " + contentType));
                                }
                            }
                        } else {
                            if (isNotFound(response) && !sessionRepresentation.equals(MISSING_SESSION_ID)) {
                                return mcpSessionNotFoundError(sessionRepresentation);
                            }
                            return this.extractError(response, sessionRepresentation);
                        }
                    })
                    .flatMap(jsonRpcMessage -> this.handler.get().apply(Mono.just(jsonRpcMessage)))
                    .onErrorComplete(t -> {
                        // handle the error first
                        this.handleException(t);
                        // inform the caller of sendMessage
                        sink.error(t);
                        return true;
                    })
                    .doFinally(s -> {
                        Disposable ref = disposableRef.getAndSet(null);
                        if (ref != null) {
                            transportSession.removeConnection(ref);
                        }
                    })
                    .contextWrite(sink.contextView())
                    .subscribe();
            disposableRef.set(connection);
            transportSession.addConnection(connection);
        });
    }

    private static Flux<McpSchema.JSONRPCMessage> mcpSessionNotFoundError(String sessionRepresentation) {
        logger.warn("Session {} was not found on the MCP server", sessionRepresentation);
        // inform the stream/connection subscriber
        return Flux.error(new McpTransportSessionNotFoundException(sessionRepresentation));
    }

    private Flux<McpSchema.JSONRPCMessage> extractError(HttpResponse response, String sessionRepresentation) {
        return Flux.<McpSchema.JSONRPCMessage>defer(() -> {
            try {
                HttpResponseException e = response.createError();
                byte[] body = e.bodyBytes();

                McpSchema.JSONRPCResponse.JSONRPCError jsonRpcError = null;
                Exception toPropagate;

                try {
                    McpSchema.JSONRPCResponse jsonRpcResponse = jsonMapper.readValue(body, McpSchema.JSONRPCResponse.class);
                    jsonRpcError = jsonRpcResponse.error();
                    toPropagate = jsonRpcError != null ? new McpError(jsonRpcError)
                            : new McpTransportException("Can't parse the jsonResponse " + jsonRpcResponse);
                }
                catch (IOException ex) {
                    toPropagate = new McpTransportException("Sending request failed, " + e.getMessage(), e);
                    logger.debug("Received content together with {} HTTP code response: {}", response.code(), body);
                }

                // Some implementations can return 400 when presented with a
                // session id that it doesn't know about, so we will
                // invalidate the session
                // https://github.com/modelcontextprotocol/typescript-sdk/issues/389
                if (response.code() == StatusCodes.CODE_BAD_REQUEST) {
                    if (!sessionRepresentation.equals(MISSING_SESSION_ID)) {
                        return Mono.error(new McpTransportSessionNotFoundException(sessionRepresentation, toPropagate));
                    }
                    return Mono.error(new McpTransportException("Received 400 BAD REQUEST for session "
                            + sessionRepresentation + ". " + toPropagate.getMessage(), toPropagate));
                }
                return Mono.error(toPropagate);
            } catch (Exception ex) {
                return Flux.error(ex);
            }
        });
    }

    private Flux<McpSchema.JSONRPCMessage> eventStream(McpTransportStream<Disposable> stream, HttpResponse response) {
        McpTransportStream<Disposable> sessionStream = stream != null ? stream
                : new DefaultMcpTransportStream<>(this.resumableStreams, this::reconnect);
        logger.debug("Connected stream {}", sessionStream.streamId());

        var idWithMessages = Flux.from(TextStreamUtil.parseSseStream(response)).map(this::parse);
        return Flux.from(sessionStream.consumeSseStream(idWithMessages));
    }

    private static boolean isNotFound(HttpResponse response) {
        return response.code() == StatusCodes.CODE_NOT_FOUND;
    }

    private static boolean isNotAllowed(HttpResponse response) {
        return response.code() == StatusCodes.CODE_METHOD_NOT_ALLOWED;
    }

    private static boolean isEventStream(HttpResponse response) {
        if (response.code() >= StatusCodes.CODE_OK) {
            String ct = response.contentType();
            if (ct != null && ct.startsWith(MimeType.TEXT_EVENT_STREAM_VALUE)) {
                return true;
            }
        }

        return false;
    }

    private static String sessionIdOrPlaceholder(McpTransportSession<?> transportSession) {
        return transportSession.sessionId().orElse(MISSING_SESSION_ID);
    }

    private Flux<McpSchema.JSONRPCMessage> directResponseFlux(McpSchema.JSONRPCMessage sentMessage, HttpResponse response) {
        return Flux.create(s -> {
                    try {
                        String responseMessage = response.bodyAsString();

                        if (sentMessage instanceof McpSchema.JSONRPCNotification) {
                            logger.warn("Notification: {} received non-compliant response: {}", sentMessage,
                                    Utils.hasText(responseMessage) ? responseMessage : "[empty]");
                            s.complete();
                        } else {
                            McpSchema.JSONRPCMessage jsonRpcResponse = McpSchema.deserializeJsonRpcMessage(jsonMapper,
                                    responseMessage);
                            s.next(jsonRpcResponse);
                        }
                    } catch (IOException e) {
                        s.error(new McpTransportException(e));
                    }
                }
        );
    }

    private Flux<McpSchema.JSONRPCMessage> newEventStream(HttpResponse response, String sessionRepresentation) {
        McpTransportStream<Disposable> sessionStream = new DefaultMcpTransportStream<>(this.resumableStreams,
                this::reconnect);
        logger.trace("Sent POST and opened a stream ({}) for session {}", sessionStream.streamId(),
                sessionRepresentation);
        return eventStream(sessionStream, response);
    }

    @Override
    public <T> T unmarshalFrom(Object data, TypeRef<T> typeRef) {
        return this.jsonMapper.convertValue(data, typeRef);
    }

    private Tuple2<Optional<String>, Iterable<McpSchema.JSONRPCMessage>> parse(ServerSentEvent event) {
        if (MESSAGE_EVENT_TYPE.equals(event.event())) {
            try {
                // We don't support batching ATM and probably won't since the next version
                // considers removing it.
                McpSchema.JSONRPCMessage message = McpSchema.deserializeJsonRpcMessage(this.jsonMapper, event.data());
                return Tuples.of(Optional.ofNullable(event.id()), Arrays.asList(message));
            } catch (IOException ioException) {
                throw new McpTransportException("Error parsing JSON-RPC message: " + event.data(), ioException);
            }
        } else {
            logger.debug("Received SSE event with type: {}", event);
            return Tuples.of(Optional.empty(), Collections.emptyList());
        }
    }

    /**
     * Builder for {@link WebRxStreamableHttpTransport}.
     */
    public static class Builder {

        private McpJsonMapper jsonMapper;

        private HttpUtilsBuilder webClientBuilder;

        private String endpoint = DEFAULT_ENDPOINT;

        private boolean resumableStreams = true;

        private boolean openConnectionOnStartup = false;

        private List<String> supportedProtocolVersions = Arrays.asList(ProtocolVersions.MCP_2024_11_05,
                ProtocolVersions.MCP_2025_03_26, ProtocolVersions.MCP_2025_06_18);

        private Builder(HttpUtilsBuilder webClientBuilder) {
            Assert.notNull(webClientBuilder, "webClientBuilder must not be null");
            this.webClientBuilder = webClientBuilder;
        }

        /**
         * Configure the {@link McpJsonMapper} to use.
         * @param jsonMapper instance to use
         * @return the builder instance
         */
        public Builder jsonMapper(McpJsonMapper jsonMapper) {
            Assert.notNull(jsonMapper, "JsonMapper must not be null");
            this.jsonMapper = jsonMapper;
            return this;
        }

        /**
         * Configure the {@link HttpUtilsBuilder} to construct the {@link HttpUtils}.
         * @param webClientBuilder instance to use
         * @return the builder instance
         */
        public Builder webClientBuilder(HttpUtilsBuilder webClientBuilder) {
            Assert.notNull(webClientBuilder, "HttpUtilsBuilder must not be null");
            this.webClientBuilder = webClientBuilder;
            return this;
        }

        /**
         * Configure the endpoint to make HTTP requests against.
         * @param endpoint endpoint to use
         * @return the builder instance
         */
        public Builder endpoint(String endpoint) {
            Assert.hasText(endpoint, "endpoint must be a non-empty String");
            this.endpoint = endpoint;
            return this;
        }

        /**
         * Configure whether to use the stream resumability feature by keeping track of
         * SSE event ids.
         * @param resumableStreams if {@code true} event ids will be tracked and upon
         * disconnection, the last seen id will be used upon reconnection as a header to
         * resume consuming messages.
         * @return the builder instance
         */
        public Builder resumableStreams(boolean resumableStreams) {
            this.resumableStreams = resumableStreams;
            return this;
        }

        /**
         * Configure whether the client should open an SSE connection upon startup. Not
         * all servers support this (although it is in theory possible with the current
         * specification), so use with caution. By default, this value is {@code false}.
         * @param openConnectionOnStartup if {@code true} the {@link #connect(Function)}
         * method call will try to open an SSE connection before sending any JSON-RPC
         * request
         * @return the builder instance
         */
        public Builder openConnectionOnStartup(boolean openConnectionOnStartup) {
            this.openConnectionOnStartup = openConnectionOnStartup;
            return this;
        }

        /**
         * Sets the list of supported protocol versions used in version negotiation. By
         * default, the client will send the latest of those versions in the
         * {@code MCP-Protocol-Version} header.
         * <p>
         * Setting this value only updates the values used in version negotiation, and
         * does NOT impact the actual capabilities of the transport. It should only be
         * used for compatibility with servers having strict requirements around the
         * {@code MCP-Protocol-Version} header.
         * @param supportedProtocolVersions protocol versions supported by this transport
         * @return this builder
         * @see <a href=
         * "https://modelcontextprotocol.io/specification/2024-11-05/basic/lifecycle#version-negotiation">version
         * negotiation specification</a>
         * @see <a href=
         * "https://modelcontextprotocol.io/specification/2025-06-18/basic/transports#protocol-version-header">Protocol
         * Version Header</a>
         */
        public Builder supportedProtocolVersions(List<String> supportedProtocolVersions) {
            Assert.notEmpty(supportedProtocolVersions, "supportedProtocolVersions must not be empty");
            this.supportedProtocolVersions = Collections.unmodifiableList(supportedProtocolVersions);
            return this;
        }


        /**
         * Construct a fresh instance of {@link WebRxStreamableHttpTransport} using
         * the current builder configuration.
         *
         * @return a new instance of {@link WebRxStreamableHttpTransport}
         */
        public WebRxStreamableHttpTransport build() {
            return new WebRxStreamableHttpTransport(
                    jsonMapper == null ? McpJsonMapper.getDefault() : jsonMapper,
                    webClientBuilder,
                    endpoint,
                    resumableStreams,
                    openConnectionOnStartup,
                    supportedProtocolVersions);
        }
    }
}
