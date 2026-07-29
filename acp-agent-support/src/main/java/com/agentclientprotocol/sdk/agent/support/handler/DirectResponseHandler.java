/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.agent.support.handler;

import com.agentclientprotocol.sdk.agent.support.AcpInvocationContext;
import com.agentclientprotocol.sdk.agent.support.AcpMethodParameter;
import com.agentclientprotocol.sdk.spec.AcpSchema.CloseSessionResponse;
import com.agentclientprotocol.sdk.spec.AcpSchema.DeleteSessionResponse;
import com.agentclientprotocol.sdk.spec.AcpSchema.DisableProviderResponse;
import com.agentclientprotocol.sdk.spec.AcpSchema.ForkSessionResponse;
import com.agentclientprotocol.sdk.spec.AcpSchema.InitializeResponse;
import com.agentclientprotocol.sdk.spec.AcpSchema.ListProvidersResponse;
import com.agentclientprotocol.sdk.spec.AcpSchema.ListSessionsResponse;
import com.agentclientprotocol.sdk.spec.AcpSchema.LoadSessionResponse;
import com.agentclientprotocol.sdk.spec.AcpSchema.LogoutResponse;
import com.agentclientprotocol.sdk.spec.AcpSchema.NewSessionResponse;
import com.agentclientprotocol.sdk.spec.AcpSchema.PromptResponse;
import com.agentclientprotocol.sdk.spec.AcpSchema.ResumeSessionResponse;
import com.agentclientprotocol.sdk.spec.AcpSchema.SetProviderResponse;
import com.agentclientprotocol.sdk.spec.AcpSchema.SetSessionConfigOptionResponse;
import com.agentclientprotocol.sdk.spec.AcpSchema.SetSessionModeResponse;
import com.agentclientprotocol.sdk.spec.AcpSchema.SetSessionModelResponse;

/**
 * Handles direct protocol response types that need no conversion.
 *
 * <p>Supports {@link InitializeResponse}, {@link NewSessionResponse},
 * {@link LoadSessionResponse}, {@link PromptResponse}, {@link SetSessionModeResponse},
 * and {@link SetSessionModelResponse}.
 *
 * @author Mark Pollack
 * @since 1.0.0
 */
public class DirectResponseHandler implements ReturnValueHandler {

	@Override
	@SuppressWarnings("removal") // recognizes the deprecated-for-removal SetSessionModelResponse
	public boolean supportsReturnType(AcpMethodParameter returnType) {
		Class<?> type = returnType.getParameterType();
		return InitializeResponse.class.isAssignableFrom(type)
				|| LogoutResponse.class.isAssignableFrom(type)
				|| NewSessionResponse.class.isAssignableFrom(type)
				|| LoadSessionResponse.class.isAssignableFrom(type)
				|| PromptResponse.class.isAssignableFrom(type)
				|| SetSessionModeResponse.class.isAssignableFrom(type)
				|| SetSessionModelResponse.class.isAssignableFrom(type)
				|| ListSessionsResponse.class.isAssignableFrom(type)
				|| CloseSessionResponse.class.isAssignableFrom(type)
				|| DeleteSessionResponse.class.isAssignableFrom(type)
				|| ResumeSessionResponse.class.isAssignableFrom(type)
				|| ForkSessionResponse.class.isAssignableFrom(type)
				|| SetSessionConfigOptionResponse.class.isAssignableFrom(type)
				|| ListProvidersResponse.class.isAssignableFrom(type)
				|| SetProviderResponse.class.isAssignableFrom(type)
				|| DisableProviderResponse.class.isAssignableFrom(type);
	}

	@Override
	public Object handleReturnValue(Object returnValue, AcpMethodParameter returnType, AcpInvocationContext context) {
		// Direct passthrough - no conversion needed
		return returnValue;
	}

}
