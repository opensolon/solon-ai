/*
 * Copyright 2025-2025 the original author or authors.
 */

package com.agentclientprotocol.sdk.spec;

import java.util.function.Consumer;
import java.util.function.Function;

import reactor.core.publisher.Mono;

/**
 * Interface for the agent side of the {@link AcpTransport}. It allows setting handlers
 * for messages that are incoming from the ACP client and hooking in to exceptions raised
 * on the transport layer.
 *
 * <p>
 * The agent transport is used by agent implementations that want to receive requests from
 * code editors/clients through the Agent Client Protocol.
 * </p>
 *
 * @author Mark Pollack
 * @author Christian Tzolov
 */
public interface AcpAgentTransport extends AcpTransport {

	/**
	 * Used to register the incoming messages' handler and start listening for client
	 * connections.
	 * @param handler a transformer for incoming messages
	 * @return a {@link Mono} that terminates upon successful agent setup. The successful
	 * termination of the returned {@link Mono} simply means the agent can now receive
	 * requests. An error can be retried according to the application requirements.
	 */
	Mono<Void> start(Function<Mono<AcpSchema.JSONRPCMessage>, Mono<AcpSchema.JSONRPCMessage>> handler);

	/**
	 * Sets the exception handler for exceptions raised on the transport layer.
	 * @param handler Allows reacting to transport level exceptions by the higher layers
	 */
	default void setExceptionHandler(Consumer<Throwable> handler) {
	}

	/**
	 * Returns a Mono that completes when the transport terminates.
	 * This is useful for agents that need to block until the transport is done,
	 * particularly when using daemon threads.
	 *
	 * <p>Example usage:
	 * <pre>{@code
	 * transport.start(handler).block();
	 * transport.awaitTermination().block(); // Block until stdin closes
	 * }</pre>
	 *
	 * @return a {@link Mono} that completes when the transport terminates
	 */
	Mono<Void> awaitTermination();

}
