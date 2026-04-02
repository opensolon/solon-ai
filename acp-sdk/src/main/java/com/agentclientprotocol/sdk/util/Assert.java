/*
 * Copyright 2025-2025 the original author or authors.
 *
 * Adapted from MCP Java SDK
 */

package com.agentclientprotocol.sdk.util;

/**
 * Assertion utility class that assists in validating arguments. Useful for identifying
 * programmer errors early and clearly at runtime.
 *
 * @author Mark Pollack
 * @author Christian Tzolov (MCP Java SDK)
 */
public abstract class Assert {

	/**
	 * Assert that an object is not {@code null}.
	 * @param object the object to check
	 * @param message the exception message to use if the assertion fails
	 * @throws IllegalArgumentException if the object is {@code null}
	 */
	public static void notNull(Object object, String message) {
		if (object == null) {
			throw new IllegalArgumentException(message);
		}
	}

	/**
	 * Assert that a string is not empty; that is, it must not be {@code null} and not the
	 * empty string.
	 * @param text the string to check
	 * @param message the exception message to use if the assertion fails
	 * @throws IllegalArgumentException if the text is empty
	 */
	public static void hasText(String text, String message) {
		if (text == null || text.trim().isEmpty()) {
			throw new IllegalArgumentException(message);
		}
	}

	/**
	 * Assert a boolean expression, throwing an {@code IllegalArgumentException} if the
	 * expression evaluates to {@code false}.
	 * @param expression a boolean expression
	 * @param message the exception message to use if the assertion fails
	 * @throws IllegalArgumentException if {@code expression} is {@code false}
	 */
	public static void isTrue(boolean expression, String message) {
		if (!expression) {
			throw new IllegalArgumentException(message);
		}
	}

}
