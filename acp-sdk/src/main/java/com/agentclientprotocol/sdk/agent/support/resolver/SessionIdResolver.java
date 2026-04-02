/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.agent.support.resolver;

import com.agentclientprotocol.sdk.agent.support.AcpInvocationContext;
import com.agentclientprotocol.sdk.agent.support.AcpMethodParameter;
import com.agentclientprotocol.sdk.annotation.SessionId;

/**
 * Resolves String parameters annotated with {@link SessionId}.
 *
 * @author Mark Pollack
 * @since 1.0.0
 */
public class SessionIdResolver implements ArgumentResolver {

	@Override
	public boolean supportsParameter(AcpMethodParameter parameter) {
		return parameter.hasAnnotation(SessionId.class)
				&& String.class.isAssignableFrom(parameter.getParameterType());
	}

	@Override
	public Object resolveArgument(AcpMethodParameter parameter, AcpInvocationContext context) {
		return context.getSessionId()
				.orElseThrow(() -> new ArgumentResolutionException(
						"Session ID not available in current context"));
	}

}
