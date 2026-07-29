/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.agent.support.resolver;

import com.agentclientprotocol.sdk.agent.support.AcpInvocationContext;
import com.agentclientprotocol.sdk.agent.support.AcpMethodParameter;
import com.agentclientprotocol.sdk.capabilities.NegotiatedCapabilities;

/**
 * Resolves {@link NegotiatedCapabilities} parameters.
 *
 * @author Mark Pollack
 * @since 1.0.0
 */
public class CapabilitiesResolver implements ArgumentResolver {

	@Override
	public boolean supportsParameter(AcpMethodParameter parameter) {
		return NegotiatedCapabilities.class.isAssignableFrom(parameter.getParameterType());
	}

	@Override
	public Object resolveArgument(AcpMethodParameter parameter, AcpInvocationContext context) {
		return context.getCapabilities()
				.orElseThrow(() -> new ArgumentResolutionException(
						"NegotiatedCapabilities not available in current context"));
	}

}
