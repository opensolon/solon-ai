/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.agent.support.handler;

import java.util.ArrayList;
import java.util.List;

import com.agentclientprotocol.sdk.agent.support.AcpInvocationContext;
import com.agentclientprotocol.sdk.agent.support.AcpMethodParameter;

/**
 * Composite that chains multiple return value handlers.
 *
 * <p>NOTE: Unlike argument resolvers, NO caching is used here.
 * This follows the Spring MVC pattern where return types vary more
 * and caching provides less benefit.
 *
 * @author Mark Pollack
 * @since 1.0.0
 */
public class ReturnValueHandlerComposite implements ReturnValueHandler {

	private final List<ReturnValueHandler> handlers = new ArrayList<>();

	/**
	 * Add a handler to the chain.
	 * @param handler the handler to add
	 * @return this composite for chaining
	 */
	public ReturnValueHandlerComposite addHandler(ReturnValueHandler handler) {
		this.handlers.add(handler);
		return this;
	}

	/**
	 * Add multiple handlers to the chain.
	 * @param handlers the handlers to add
	 * @return this composite for chaining
	 */
	public ReturnValueHandlerComposite addHandlers(List<ReturnValueHandler> handlers) {
		this.handlers.addAll(handlers);
		return this;
	}

	/**
	 * Get the list of registered handlers.
	 * @return unmodifiable list of handlers
	 */
	public List<ReturnValueHandler> getHandlers() {
		return new ArrayList<>(handlers);
	}

	@Override
	public boolean supportsReturnType(AcpMethodParameter returnType) {
		return findHandler(returnType) != null;
	}

	@Override
	public Object handleReturnValue(Object returnValue, AcpMethodParameter returnType, AcpInvocationContext context) {
		ReturnValueHandler handler = findHandler(returnType);
		if (handler == null) {
			throw new ReturnValueHandlingException(
					"No handler for return type: " + returnType.getParameterType().getName());
		}
		return handler.handleReturnValue(returnValue, returnType, context);
	}

	private ReturnValueHandler findHandler(AcpMethodParameter returnType) {
		for (ReturnValueHandler handler : handlers) {
			if (handler.supportsReturnType(returnType)) {
				return handler;
			}
		}
		return null;
	}

}
