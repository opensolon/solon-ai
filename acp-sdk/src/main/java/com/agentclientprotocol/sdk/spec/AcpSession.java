/*
 * Copyright 2025-2025 the original author or authors.
 */

package com.agentclientprotocol.sdk.spec;

import io.modelcontextprotocol.json.TypeRef;
import reactor.core.publisher.Mono;

/**
 * Represents an Agent Client Protocol (ACP) session that handles communication between
 * clients and agents. This interface provides methods for sending requests and
 * notifications, as well as managing the session lifecycle.
 *
 * <p>
 * The session operates asynchronously using Project Reactor's {@link Mono} type for
 * non-blocking operations. It supports both request-response patterns and one-way
 * notifications.
 * </p>
 *
 * <p>
 * ACP is bidirectional - both clients and agents can send requests to each other:
 * </p>
 * <ul>
 * <li>Client → Agent: initialize, newSession, prompt, setSessionMode, etc.</li>
 * <li>Agent → Client: readTextFile, writeTextFile, requestPermission, sessionUpdate,
 * etc.</li>
 * </ul>
 *
 * @author Mark Pollack
 * @author Christian Tzolov
 */
public interface AcpSession {

	/**
	 * Sends a request to the peer and expects a response of type T.
	 *
	 * <p>
	 * This method handles the request-response pattern where a response is expected from
	 * the peer (agent or client). The response type is determined by the provided
	 * TypeReference.
	 * </p>
	 * @param <T> the type of the expected response
	 * @param method the name of the method to be called on the peer
	 * @param requestParams the parameters to be sent with the request
	 * @param typeRef the TypeReference describing the expected response type
	 * @return a Mono that will emit the response when received
	 */
	<T> Mono<T> sendRequest(String method, Object requestParams, TypeRef<T> typeRef);

	/**
	 * Sends a notification to the peer without parameters.
	 *
	 * <p>
	 * This method implements the notification pattern where no response is expected from
	 * the peer. It's useful for fire-and-forget scenarios like session updates.
	 * </p>
	 * @param method the name of the notification method to be called on the peer
	 * @return a Mono that completes when the notification has been sent
	 */
	default Mono<Void> sendNotification(String method) {
		return sendNotification(method, null);
	}

	/**
	 * Sends a notification to the peer with parameters.
	 *
	 * <p>
	 * Similar to {@link #sendNotification(String)} but allows sending additional
	 * parameters with the notification.
	 * </p>
	 * @param method the name of the notification method to be sent to the peer
	 * @param params parameters to be sent with the notification
	 * @return a Mono that completes when the notification has been sent
	 */
	Mono<Void> sendNotification(String method, Object params);

	/**
	 * Closes the session and releases any associated resources asynchronously.
	 * @return a {@link Mono<Void>} that completes when the session has been closed.
	 */
	Mono<Void> closeGracefully();

	/**
	 * Closes the session and releases any associated resources.
	 */
	void close();

}
