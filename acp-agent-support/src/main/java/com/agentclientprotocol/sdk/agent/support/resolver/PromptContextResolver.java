/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.agent.support.resolver;

import com.agentclientprotocol.sdk.agent.PromptContext;
import com.agentclientprotocol.sdk.agent.SyncPromptContext;
import com.agentclientprotocol.sdk.agent.support.AcpInvocationContext;
import com.agentclientprotocol.sdk.agent.support.AcpMethodParameter;

/**
 * Resolves {@link PromptContext} and {@link SyncPromptContext} parameters.
 *
 * <p>The prompt context provides access to all agent capabilities including
 * file operations, terminal execution, and permission requests.
 *
 * @author Mark Pollack
 * @since 1.0.0
 */
public class PromptContextResolver implements ArgumentResolver {

	@Override
	public boolean supportsParameter(AcpMethodParameter parameter) {
		Class<?> type = parameter.getParameterType();
		return PromptContext.class.isAssignableFrom(type)
				|| SyncPromptContext.class.isAssignableFrom(type);
	}

	@Override
	public Object resolveArgument(AcpMethodParameter parameter, AcpInvocationContext context) {
		Class<?> type = parameter.getParameterType();

		if (SyncPromptContext.class.isAssignableFrom(type)) {
			return context.getSyncPromptContext()
					.orElseThrow(() -> new ArgumentResolutionException(
							"SyncPromptContext is only available for @Prompt handlers"));
		}

		if (PromptContext.class.isAssignableFrom(type)) {
			return context.getPromptContext()
					.orElseThrow(() -> new ArgumentResolutionException(
							"PromptContext is only available for @Prompt handlers"));
		}

		throw new ArgumentResolutionException("Unsupported context type: " + type.getName());
	}

}
