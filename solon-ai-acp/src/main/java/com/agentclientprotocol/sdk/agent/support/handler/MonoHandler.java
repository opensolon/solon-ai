/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.agent.support.handler;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;

import com.agentclientprotocol.sdk.agent.support.AcpInvocationContext;
import com.agentclientprotocol.sdk.agent.support.AcpMethodParameter;

import reactor.core.publisher.Mono;

/**
 * Handles {@link Mono} return types for async handlers.
 *
 * <p>The Mono is unwrapped (blocked) to get the actual response value.
 * For true reactive operation, use the reactive agent API directly.
 *
 * @author Mark Pollack
 * @since 1.0.0
 */
public class MonoHandler implements ReturnValueHandler {

	@Override
	public boolean supportsReturnType(AcpMethodParameter returnType) {
		return Mono.class.isAssignableFrom(returnType.getParameterType());
	}

	@Override
	public Object handleReturnValue(Object returnValue, AcpMethodParameter returnType, AcpInvocationContext context) {
		if (returnValue == null) {
			return null;
		}

		@SuppressWarnings("unchecked")
		Mono<Object> mono = (Mono<Object>) returnValue;

		// Block to get the actual response
		// Note: For fully reactive operation, use the reactive agent API
		return mono.block();
	}

	/**
	 * Get the generic type parameter of the Mono (e.g., PromptResponse from Mono&lt;PromptResponse&gt;).
	 *
	 * @param returnType the return type parameter
	 * @return the generic type, or Object if not determinable
	 */
	public static Class<?> getMonoGenericType(AcpMethodParameter returnType) {
		Type genericType = returnType.getGenericType();
		if (genericType instanceof ParameterizedType) {
			ParameterizedType pt = (ParameterizedType) genericType;
			Type[] typeArgs = pt.getActualTypeArguments();
			if (typeArgs.length > 0 && typeArgs[0] instanceof Class<?>) {
				return (Class<?>) typeArgs[0];
			}
		}
		return Object.class;
	}
}