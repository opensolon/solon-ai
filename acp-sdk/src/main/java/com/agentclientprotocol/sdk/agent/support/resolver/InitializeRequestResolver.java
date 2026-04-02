/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.agent.support.resolver;

import com.agentclientprotocol.sdk.agent.support.AcpInvocationContext;
import com.agentclientprotocol.sdk.agent.support.AcpMethodParameter;
import com.agentclientprotocol.sdk.spec.AcpSchema.InitializeRequest;

/**
 * Resolves {@link InitializeRequest} parameters in initialize handlers.
 *
 * @author Mark Pollack
 * @since 1.0.0
 */
public class InitializeRequestResolver implements ArgumentResolver {

	@Override
	public boolean supportsParameter(AcpMethodParameter parameter) {
		return InitializeRequest.class.isAssignableFrom(parameter.getParameterType());
	}

	@Override
	public Object resolveArgument(AcpMethodParameter parameter, AcpInvocationContext context) {
		Object request = context.getRequest();
		if (request instanceof InitializeRequest) {
			return request;
		}
		throw new ArgumentResolutionException(
				"Expected InitializeRequest but got: " + (request != null ? request.getClass().getName() : "null"));
	}

}
