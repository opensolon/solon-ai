/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.agent.support.resolver;

import com.agentclientprotocol.sdk.agent.support.AcpInvocationContext;
import com.agentclientprotocol.sdk.agent.support.AcpMethodParameter;
import com.agentclientprotocol.sdk.spec.AcpSchema.ForkSessionRequest;

/**
 * Resolves {@link ForkSessionRequest} parameters in fork session handlers.
 *
 * @author Mark Pollack
 * @since 0.12.0
 */
public class ForkSessionRequestResolver implements ArgumentResolver {

	@Override
	public boolean supportsParameter(AcpMethodParameter parameter) {
		return ForkSessionRequest.class.isAssignableFrom(parameter.getParameterType());
	}

	@Override
	public Object resolveArgument(AcpMethodParameter parameter, AcpInvocationContext context) {
		Object request = context.getRequest();
		if (request instanceof ForkSessionRequest) {
			return request;
		}
		throw new ArgumentResolutionException(
				"Expected ForkSessionRequest but got: " + (request != null ? request.getClass().getName() : "null"));
	}

}
