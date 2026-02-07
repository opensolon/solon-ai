/*
 * Copyright 2025-2025 the original author or authors.
 */

package com.agentclientprotocol.sdk.spec;

import java.util.Arrays;
import java.util.List;

import com.agentclientprotocol.sdk.spec.AcpSchema.JSONRPCMessage;
import io.modelcontextprotocol.json.TypeRef;
import reactor.core.publisher.Mono;

/**
 * Defines the asynchronous transport layer for the Agent Client Protocol (ACP).
 *
 * <p>
 * The AcpTransport interface provides the foundation for implementing custom transport
 * mechanisms in the Agent Client Protocol. It handles the bidirectional communication
 * between the client and agent components, supporting asynchronous message exchange using
 * JSON-RPC format.
 * </p>
 *
 * <p>
 * Implementations of this interface are responsible for:
 * </p>
 * <ul>
 * <li>Managing the lifecycle of the transport connection</li>
 * <li>Handling incoming messages and errors from the peer</li>
 * <li>Sending outbound messages to the peer</li>
 * </ul>
 *
 * <p>
 * The transport layer is designed to be protocol-agnostic, allowing for various
 * implementations such as STDIO, HTTP, SSE, or custom protocols.
 * </p>
 *
 * @author Mark Pollack
 * @author Christian Tzolov
 */
public interface AcpTransport {

	/**
	 * Closes the transport connection and releases any associated resources.
	 *
	 * <p>
	 * This method ensures proper cleanup of resources when the transport is no longer
	 * needed. It should handle the graceful shutdown of any active connections.
	 * </p>
	 */
	default void close() {
		this.closeGracefully().subscribe();
	}

	/**
	 * Closes the transport connection and releases any associated resources
	 * asynchronously.
	 * @return a {@link Mono<Void>} that completes when the connection has been closed.
	 */
	Mono<Void> closeGracefully();

	/**
	 * Sends a message to the peer asynchronously.
	 *
	 * <p>
	 * This method handles the transmission of messages to the peer in an asynchronous
	 * manner. Messages are sent in JSON-RPC format as specified by the ACP protocol.
	 * </p>
	 * @param message the {@link JSONRPCMessage} to be sent to the peer
	 * @return a {@link Mono<Void>} that completes when the message has been sent
	 */
	Mono<Void> sendMessage(JSONRPCMessage message);

	/**
	 * Unmarshals the given data into an object of the specified type.
	 * @param <T> the type of the object to unmarshal
	 * @param data the data to unmarshal
	 * @param typeRef the type reference for the object to unmarshal
	 * @return the unmarshalled object
	 */
	<T> T unmarshalFrom(Object data, TypeRef<T> typeRef);

	/**
	 * Returns the list of protocol versions supported by this transport.
	 * @return list of supported protocol versions
	 */
	default List<Integer> protocolVersions() {
		return Arrays.asList(AcpSchema.LATEST_PROTOCOL_VERSION);
	}

}
