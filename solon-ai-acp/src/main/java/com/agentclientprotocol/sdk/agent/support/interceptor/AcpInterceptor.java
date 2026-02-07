/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.agent.support.interceptor;

import com.agentclientprotocol.sdk.agent.support.AcpInvocationContext;

/**
 * Interceptor interface for ACP handler invocations.
 *
 * <p>Interceptors allow cross-cutting concerns to be applied to handler
 * invocations. Common uses include logging, metrics, authentication,
 * and error handling.
 *
 * <p>The lifecycle methods are called in this order:
 * <ol>
 *   <li>{@link #preInvoke} - before handler invocation (all interceptors)</li>
 *   <li>Handler method execution</li>
 *   <li>{@link #postInvoke} - after successful invocation (reverse order)</li>
 *   <li>OR {@link #onError} - if an exception occurred (reverse order)</li>
 *   <li>{@link #afterCompletion} - always called for cleanup (reverse order)</li>
 * </ol>
 *
 * @author Mark Pollack
 * @since 1.0.0
 * @see InterceptorChain
 */
public interface AcpInterceptor {

	/**
	 * Default order value for interceptors (middle priority).
	 */
	int DEFAULT_ORDER = 0;

	/**
	 * Get the order of this interceptor. Lower values execute first.
	 * @return the order value
	 */
	default int getOrder() {
		return DEFAULT_ORDER;
	}

	/**
	 * Called before handler invocation.
	 * @param context the invocation context
	 * @return true to continue, false to abort invocation
	 */
	default boolean preInvoke(AcpInvocationContext context) {
		return true;
	}

	/**
	 * Called after successful handler invocation.
	 * @param context the invocation context
	 * @param result the handler result
	 * @return the (possibly modified) result
	 */
	default Object postInvoke(AcpInvocationContext context, Object result) {
		return result;
	}

	/**
	 * Called when handler throws an exception.
	 * @param context the invocation context
	 * @param ex the exception
	 * @return replacement result (non-null to suppress exception), or null to propagate
	 */
	default Object onError(AcpInvocationContext context, Throwable ex) {
		return null;
	}

	/**
	 * Called after invocation completes (success or failure).
	 * Always called for interceptors that had their preInvoke succeed.
	 * Use for resource cleanup.
	 * @param context the invocation context
	 */
	default void afterCompletion(AcpInvocationContext context) {
		// Default: no-op
	}

}
