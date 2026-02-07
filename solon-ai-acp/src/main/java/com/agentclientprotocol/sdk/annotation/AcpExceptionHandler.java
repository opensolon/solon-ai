/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.annotation;

import java.lang.annotation.*;

/**
 * Marks a method as an exception handler for ACP agent operations.
 *
 * <p>The annotated method is called when an exception occurs during
 * handler method invocation. This allows for centralized error handling
 * and conversion of exceptions to appropriate responses.
 *
 * <p>The method can have the following parameters (all optional):
 * <ul>
 *   <li>The exception type to handle (required as first parameter)</li>
 *   <li>{@code @SessionId String} - the session ID where the error occurred</li>
 *   <li>{@code AcpInvocationContext} - the invocation context</li>
 * </ul>
 *
 * <p>The method should return one of:
 * <ul>
 *   <li>A response object appropriate for the failed method</li>
 *   <li>{@code void} - to let the exception propagate</li>
 *   <li>{@code null} - to let the exception propagate</li>
 * </ul>
 *
 * <p>Exception handler methods are matched by exception type. More specific
 * exception types take precedence over general ones.
 *
 * <p>Example usage:
 * <pre>{@code
 * @AcpExceptionHandler
 * public PromptResponse handleIllegalArgument(IllegalArgumentException ex) {
 *     return PromptResponse.text("Invalid input: " + ex.getMessage());
 * }
 *
 * @AcpExceptionHandler
 * public PromptResponse handleGeneral(Exception ex) {
 *     log.error("Unexpected error", ex);
 *     return PromptResponse.text("An error occurred");
 * }
 * }</pre>
 *
 * @author Mark Pollack
 * @since 1.0.0
 * @see AcpAgent
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface AcpExceptionHandler {

	/**
	 * Exception types handled by this method.
	 * <p>If empty, the method parameter types are used to determine
	 * which exceptions are handled.
	 * @return array of exception classes to handle
	 */
	Class<? extends Throwable>[] value() default {};

}
