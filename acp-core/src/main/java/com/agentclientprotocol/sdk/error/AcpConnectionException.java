/*
 * Copyright 2025-2025 the original author or authors.
 */

package com.agentclientprotocol.sdk.error;

/**
 * Exception thrown when there is a connection or transport error.
 *
 * <p>
 * This exception is thrown for transport-level issues such as:
 * <ul>
 * <li>Failed to establish connection to the agent</li>
 * <li>Connection was closed unexpectedly</li>
 * <li>Transport timeout</li>
 * <li>I/O errors during communication</li>
 * </ul>
 *
 * <p>
 * Example usage:
 * <pre>{@code
 * try {
 *     client.initialize(request).block();
 * } catch (AcpConnectionException e) {
 *     logger.error("Failed to connect to agent", e);
 *     // Handle reconnection or notify user
 * }
 * }</pre>
 *
 * @author Mark Pollack
 */
public class AcpConnectionException extends AcpException {

	/**
	 * Constructs a new connection exception with the specified message.
	 * @param message the detail message
	 */
	public AcpConnectionException(String message) {
		super(message);
	}

	/**
	 * Constructs a new connection exception with the specified message and cause.
	 * @param message the detail message
	 * @param cause the underlying cause
	 */
	public AcpConnectionException(String message, Throwable cause) {
		super(message, cause);
	}

	/**
	 * Constructs a new connection exception with the specified cause.
	 * @param cause the underlying cause
	 */
	public AcpConnectionException(Throwable cause) {
		super(cause);
	}

}
