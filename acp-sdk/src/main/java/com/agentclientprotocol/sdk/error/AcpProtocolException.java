/*
 * Copyright 2025-2025 the original author or authors.
 */

package com.agentclientprotocol.sdk.error;

import com.agentclientprotocol.sdk.spec.AcpSchema;

/**
 * Exception representing a JSON-RPC protocol error from the peer.
 *
 * <p>
 * This exception is thrown when the peer returns a JSON-RPC error response. It wraps
 * the error code, message, and optional data from the protocol response.
 * </p>
 *
 * <p>
 * Common error codes are defined in {@link AcpErrorCodes}:
 * <ul>
 * <li>{@link AcpErrorCodes#METHOD_NOT_FOUND} (-32601): Method not available</li>
 * <li>{@link AcpErrorCodes#INVALID_PARAMS} (-32602): Invalid parameters</li>
 * <li>{@link AcpErrorCodes#INTERNAL_ERROR} (-32603): Internal error</li>
 * <li>{@link AcpErrorCodes#CONCURRENT_PROMPT} (-32000): Already processing a prompt</li>
 * </ul>
 *
 * <p>
 * Example usage:
 * <pre>{@code
 * try {
 *     client.prompt(request).block();
 * } catch (AcpProtocolException e) {
 *     if (e.getCode() == AcpErrorCodes.CONCURRENT_PROMPT) {
 *         // Handle concurrent prompt error
 *     }
 * }
 * }</pre>
 *
 * @author Mark Pollack
 * @see AcpErrorCodes
 */
public class AcpProtocolException extends AcpException {

	private final int code;

	private final Object data;

	/**
	 * Constructs a new protocol exception from a JSON-RPC error.
	 * @param error the JSON-RPC error from the protocol response
	 */
	public AcpProtocolException(AcpSchema.JSONRPCError error) {
		super(formatMessage(error.code(), error.message()));
		this.code = error.code();
		this.data = error.data();
	}

	/**
	 * Constructs a new protocol exception with the specified code and message.
	 * @param code the JSON-RPC error code
	 * @param message the error message
	 */
	public AcpProtocolException(int code, String message) {
		super(formatMessage(code, message));
		this.code = code;
		this.data = null;
	}

	/**
	 * Constructs a new protocol exception with the specified code, message, and data.
	 * @param code the JSON-RPC error code
	 * @param message the error message
	 * @param data optional additional error data
	 */
	public AcpProtocolException(int code, String message, Object data) {
		super(formatMessage(code, message));
		this.code = code;
		this.data = data;
	}

	/**
	 * Returns the JSON-RPC error code.
	 * @return the error code
	 * @see AcpErrorCodes
	 */
	public int getCode() {
		return code;
	}

	/**
	 * Returns the optional additional error data.
	 * @return the error data, or null if none was provided
	 */
	public Object getData() {
		return data;
	}

	/**
	 * Converts this exception to a JSON-RPC error object for sending over the wire.
	 * @return a JSON-RPC error object
	 */
	public AcpSchema.JSONRPCError toJsonRpcError() {
		return new AcpSchema.JSONRPCError(code, getMessage(), data);
	}

	/**
	 * Returns true if this error indicates the method was not found.
	 * @return true if method not found error
	 */
	public boolean isMethodNotFound() {
		return code == AcpErrorCodes.METHOD_NOT_FOUND;
	}

	/**
	 * Returns true if this error indicates a concurrent prompt violation.
	 * @return true if concurrent prompt error
	 */
	public boolean isConcurrentPrompt() {
		return code == AcpErrorCodes.CONCURRENT_PROMPT;
	}

	/**
	 * Returns true if this error indicates invalid parameters.
	 * @return true if invalid params error
	 */
	public boolean isInvalidParams() {
		return code == AcpErrorCodes.INVALID_PARAMS;
	}

	/**
	 * Returns true if this error indicates an internal error.
	 * @return true if internal error
	 */
	public boolean isInternalError() {
		return code == AcpErrorCodes.INTERNAL_ERROR;
	}

	private static String formatMessage(int code, String message) {
		return String.format("[%d] %s", code, message);
	}

}
