/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.agent.support.resolver;

/**
 * Exception thrown when argument resolution fails.
 *
 * @author Mark Pollack
 * @since 1.0.0
 */
public class ArgumentResolutionException extends RuntimeException {

	/**
	 * Create a new exception with a message.
	 * @param message the error message
	 */
	public ArgumentResolutionException(String message) {
		super(message);
	}

	/**
	 * Create a new exception with a message and cause.
	 * @param message the error message
	 * @param cause the underlying cause
	 */
	public ArgumentResolutionException(String message, Throwable cause) {
		super(message, cause);
	}

}
