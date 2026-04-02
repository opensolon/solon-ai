/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.agent.support.resolver;

import com.agentclientprotocol.sdk.agent.support.AcpInvocationContext;
import com.agentclientprotocol.sdk.agent.support.AcpMethodParameter;
import com.agentclientprotocol.sdk.spec.AcpSchema.NewSessionRequest;

/**
 * Resolves {@link NewSessionRequest} parameters in new session handlers.
 *
 * @author Mark Pollack
 * @since 1.0.0
 */
public class NewSessionRequestResolver implements ArgumentResolver {

	@Override
	public boolean supportsParameter(AcpMethodParameter parameter) {
		return NewSessionRequest.class.isAssignableFrom(parameter.getParameterType());
	}

	@Override
	public Object resolveArgument(AcpMethodParameter parameter, AcpInvocationContext context) {
		Object request = context.getRequest();
		if (request instanceof NewSessionRequest) {
			return request;
		}
		throw new ArgumentResolutionException(
				"Expected NewSessionRequest but got: " + (request != null ? request.getClass().getName() : "null"));
	}

}
