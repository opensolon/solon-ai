/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.agent.support.resolver;

import com.agentclientprotocol.sdk.agent.support.AcpInvocationContext;
import com.agentclientprotocol.sdk.agent.support.AcpMethodParameter;
import com.agentclientprotocol.sdk.spec.AcpSchema.ListProvidersRequest;

/**
 * Resolves {@link ListProvidersRequest} parameters in {@code providers/list} handlers.
 *
 * @author Mark Pollack
 * @since 0.13.0
 */
public class ListProvidersRequestResolver implements ArgumentResolver {

	@Override
	public boolean supportsParameter(AcpMethodParameter parameter) {
		return ListProvidersRequest.class.isAssignableFrom(parameter.getParameterType());
	}

	@Override
	public Object resolveArgument(AcpMethodParameter parameter, AcpInvocationContext context) {
		Object request = context.getRequest();
		if (request instanceof ListProvidersRequest) {
			return request;
		}
		throw new ArgumentResolutionException(
				"Expected ListProvidersRequest but got: " + (request != null ? request.getClass().getName() : "null"));
	}

}
