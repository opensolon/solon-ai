/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.agent.support.handler;

import com.agentclientprotocol.sdk.agent.support.AcpInvocationContext;
import com.agentclientprotocol.sdk.agent.support.AcpMethodParameter;

/**
 * Strategy interface for handling method return values and converting
 * them to protocol responses.
 *
 * <p>Implementations of this interface are responsible for converting
 * handler method return values to the appropriate protocol response types.
 *
 * @author Mark Pollack
 * @since 1.0.0
 * @see ReturnValueHandlerComposite
 */
public interface ReturnValueHandler {

	/**
	 * Determine whether this handler supports the given return type.
	 * @param returnType the return type metadata
	 * @return true if this handler can process the return type
	 */
	boolean supportsReturnType(AcpMethodParameter returnType);

	/**
	 * Handle the return value and convert to protocol response.
	 * @param returnValue the value returned by the handler method
	 * @param returnType the return type metadata
	 * @param context the invocation context
	 * @return the protocol response
	 * @throws ReturnValueHandlingException if handling fails
	 */
	Object handleReturnValue(Object returnValue, AcpMethodParameter returnType, AcpInvocationContext context);

}
