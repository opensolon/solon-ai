/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.agent.support.resolver;

import com.agentclientprotocol.sdk.agent.support.AcpInvocationContext;
import com.agentclientprotocol.sdk.agent.support.AcpMethodParameter;
import com.agentclientprotocol.sdk.spec.AcpSchema.DisableProviderRequest;

/**
 * Resolves {@link DisableProviderRequest} parameters in {@code providers/disable} handlers.
 *
 * @author Mark Pollack
 * @since 0.13.0
 */
public class DisableProviderRequestResolver implements ArgumentResolver {

	@Override
	public boolean supportsParameter(AcpMethodParameter parameter) {
		return DisableProviderRequest.class.isAssignableFrom(parameter.getParameterType());
	}

	@Override
	public Object resolveArgument(AcpMethodParameter parameter, AcpInvocationContext context) {
		Object request = context.getRequest();
		if (request instanceof DisableProviderRequest) {
			return request;
		}
		throw new ArgumentResolutionException("Expected DisableProviderRequest but got: "
				+ (request != null ? request.getClass().getName() : "null"));
	}

}
