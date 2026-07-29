/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.agent.support.handler;

import com.agentclientprotocol.sdk.agent.support.AcpInvocationContext;
import com.agentclientprotocol.sdk.agent.support.AcpMethodParameter;
import com.agentclientprotocol.sdk.spec.AcpSchema.PromptResponse;

/**
 * Converts String return values to {@link PromptResponse} using
 * {@link PromptResponse#text(String)}.
 *
 * <p>This handler only applies to prompt handlers (session/prompt method).
 *
 * @author Mark Pollack
 * @since 1.0.0
 */
public class StringToPromptResponseHandler implements ReturnValueHandler {

	@Override
	public boolean supportsReturnType(AcpMethodParameter returnType) {
		return String.class.equals(returnType.getParameterType());
	}

	@Override
	public Object handleReturnValue(Object returnValue, AcpMethodParameter returnType, AcpInvocationContext context) {
		// Only convert to PromptResponse for prompt handlers
		if ("session/prompt".equals(context.getAcpMethod())) {
			String text = (String) returnValue;
			return text != null ? PromptResponse.text(text) : PromptResponse.endTurn();
		}
		// For other methods, return as-is (may cause error if unexpected)
		return returnValue;
	}

}
