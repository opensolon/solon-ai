/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.agent.support.resolver;

import com.agentclientprotocol.sdk.agent.support.AcpInvocationContext;
import com.agentclientprotocol.sdk.agent.support.AcpMethodParameter;
import com.agentclientprotocol.sdk.spec.AcpSchema.SetSessionConfigOptionRequest;

/**
 * Resolves {@link SetSessionConfigOptionRequest} parameters in config option handlers.
 *
 * @author Mark Pollack
 * @since 0.12.0
 */
public class SetSessionConfigOptionRequestResolver implements ArgumentResolver {

	@Override
	public boolean supportsParameter(AcpMethodParameter parameter) {
		return SetSessionConfigOptionRequest.class.isAssignableFrom(parameter.getParameterType());
	}

	@Override
	public Object resolveArgument(AcpMethodParameter parameter, AcpInvocationContext context) {
		Object request = context.getRequest();
		if (request instanceof SetSessionConfigOptionRequest) {
			return request;
		}
		throw new ArgumentResolutionException(
				"Expected SetSessionConfigOptionRequest but got: "
						+ (request != null ? request.getClass().getName() : "null"));
	}

}
