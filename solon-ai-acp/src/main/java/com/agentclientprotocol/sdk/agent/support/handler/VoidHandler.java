/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.agent.support.handler;

import com.agentclientprotocol.sdk.agent.support.AcpInvocationContext;
import com.agentclientprotocol.sdk.agent.support.AcpMethodParameter;
import com.agentclientprotocol.sdk.spec.AcpSchema.PromptResponse;

/**
 * Handles void return types for prompt handlers by returning
 * {@link PromptResponse#endTurn()}.
 *
 * @author Mark Pollack
 * @since 1.0.0
 */
public class VoidHandler implements ReturnValueHandler {

	@Override
	public boolean supportsReturnType(AcpMethodParameter returnType) {
		Class<?> type = returnType.getParameterType();
		return void.class.equals(type) || Void.class.equals(type);
	}

	@Override
	public Object handleReturnValue(Object returnValue, AcpMethodParameter returnType, AcpInvocationContext context) {
		// Only convert to PromptResponse for prompt handlers
		if ("session/prompt".equals(context.getAcpMethod())) {
			return PromptResponse.endTurn();
		}
		// For other methods, return null
		return null;
	}

}
