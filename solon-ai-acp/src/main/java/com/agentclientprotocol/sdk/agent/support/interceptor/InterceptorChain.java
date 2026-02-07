/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.agent.support.interceptor;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import com.agentclientprotocol.sdk.agent.support.AcpInvocationContext;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manages interceptor execution with proper lifecycle tracking.
 * Ensures afterCompletion is only called for interceptors that started.
 *
 * <p>This class follows the Spring MVC pattern of tracking the index
 * of the last successfully invoked interceptor, ensuring proper cleanup
 * even when exceptions occur.
 *
 * @author Mark Pollack
 * @since 1.0.0
 */
public class InterceptorChain {

	private static final Logger log = LoggerFactory.getLogger(InterceptorChain.class);

	private final List<AcpInterceptor> interceptors;

	private int interceptorIndex = -1;

	/**
	 * Create an interceptor chain with the given interceptors.
	 * Interceptors are sorted by order (lower values first).
	 * @param interceptors the interceptors
	 */
	public InterceptorChain(List<AcpInterceptor> interceptors) {
		this.interceptors = new ArrayList<>(interceptors);
		this.interceptors.sort(Comparator.comparingInt(AcpInterceptor::getOrder));
	}

	/**
	 * Apply preInvoke to all interceptors.
	 * @param context the invocation context
	 * @return true to continue, false to abort
	 */
	public boolean applyPreInvoke(AcpInvocationContext context) {
		for (int i = 0; i < interceptors.size(); i++) {
			AcpInterceptor interceptor = interceptors.get(i);
			try {
				if (!interceptor.preInvoke(context)) {
					// Short-circuit: trigger cleanup for completed interceptors
					triggerAfterCompletion(context, null);
					return false;
				}
				this.interceptorIndex = i;
			}
			catch (Exception e) {
				triggerAfterCompletion(context, e);
				throw e;
			}
		}
		return true;
	}

	/**
	 * Apply postInvoke in reverse order.
	 * @param context the invocation context
	 * @param result the handler result
	 * @return the (possibly modified) result
	 */
	public Object applyPostInvoke(AcpInvocationContext context, Object result) {
		for (int i = interceptors.size() - 1; i >= 0; i--) {
			try {
				result = interceptors.get(i).postInvoke(context, result);
			}
			catch (Exception e) {
				log.warn("Interceptor postInvoke threw exception", e);
				// Continue with other interceptors
			}
		}
		return result;
	}

	/**
	 * Apply onError in reverse order until one handles it.
	 * @param context the invocation context
	 * @param ex the exception
	 * @return replacement result, or null to propagate exception
	 */
	public Object applyOnError(AcpInvocationContext context, Throwable ex) {
		for (int i = interceptors.size() - 1; i >= 0; i--) {
			try {
				Object replacement = interceptors.get(i).onError(context, ex);
				if (replacement != null) {
					return replacement;
				}
			}
			catch (Exception e) {
				log.warn("Interceptor onError threw exception", e);
				// Continue with other interceptors
			}
		}
		return null;
	}

	/**
	 * Trigger afterCompletion for all started interceptors.
	 * Called in finally block - must not throw.
	 * @param context the invocation context
	 * @param ex the exception (may be null)
	 */
	public void triggerAfterCompletion(AcpInvocationContext context, Throwable ex) {
		for (int i = this.interceptorIndex; i >= 0; i--) {
			try {
				interceptors.get(i).afterCompletion(context);
			}
			catch (Throwable t) {
				log.warn("Interceptor afterCompletion threw exception", t);
				// Don't propagate - continue cleanup
			}
		}
	}

}
