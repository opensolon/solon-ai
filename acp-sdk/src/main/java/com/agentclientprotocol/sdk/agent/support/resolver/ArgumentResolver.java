/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.agent.support.resolver;

import com.agentclientprotocol.sdk.agent.support.AcpInvocationContext;
import com.agentclientprotocol.sdk.agent.support.AcpMethodParameter;

/**
 * Strategy interface for resolving method arguments during handler invocation.
 *
 * <p>Implementations of this interface are responsible for providing values
 * for specific types of method parameters. The framework maintains a chain
 * of resolvers, and the first resolver that supports a parameter is used.
 *
 * <p>Custom resolvers can be registered to support application-specific
 * parameter types.
 *
 * @author Mark Pollack
 * @since 1.0.0
 * @see ArgumentResolverComposite
 */
public interface ArgumentResolver {

	/**
	 * Determine whether this resolver supports the given parameter.
	 * @param parameter the method parameter to check
	 * @return true if this resolver can resolve the parameter
	 */
	boolean supportsParameter(AcpMethodParameter parameter);

	/**
	 * Resolve the argument value for the given parameter.
	 * @param parameter the method parameter
	 * @param context the invocation context
	 * @return the resolved argument value (may be null)
	 * @throws ArgumentResolutionException if resolution fails
	 */
	Object resolveArgument(AcpMethodParameter parameter, AcpInvocationContext context);

}
