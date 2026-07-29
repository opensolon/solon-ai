/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.agent.support.resolver;

import com.agentclientprotocol.sdk.agent.support.AcpInvocationContext;
import com.agentclientprotocol.sdk.agent.support.AcpMethodParameter;
import com.agentclientprotocol.sdk.spec.AcpSchema.PromptRequest;

/**
 * Resolves {@link PromptRequest} parameters in prompt handlers.
 *
 * @author Mark Pollack
 * @since 1.0.0
 */
public class PromptRequestResolver implements ArgumentResolver {

	@Override
	public boolean supportsParameter(AcpMethodParameter parameter) {
		return PromptRequest.class.isAssignableFrom(parameter.getParameterType());
	}

	@Override
	public Object resolveArgument(AcpMethodParameter parameter, AcpInvocationContext context) {
		Object request = context.getRequest();
		if (request instanceof PromptRequest) {
			return request;
		}
		throw new ArgumentResolutionException(
				"Expected PromptRequest but got: " + (request != null ? request.getClass().getName() : "null"));
	}

}
