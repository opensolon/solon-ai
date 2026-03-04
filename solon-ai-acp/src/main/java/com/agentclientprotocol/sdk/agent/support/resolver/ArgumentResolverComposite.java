/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.agent.support.resolver;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import com.agentclientprotocol.sdk.agent.support.AcpInvocationContext;
import com.agentclientprotocol.sdk.agent.support.AcpMethodParameter;

/**
 * Composite that chains multiple argument resolvers.
 * Caches resolver selection per parameter for performance.
 *
 * <p>This follows the Spring MVC pattern where resolver lookup is
 * cached using {@link AcpMethodParameter} as the cache key. The first
 * resolver that supports a parameter is cached and reused for subsequent
 * invocations.
 *
 * @author Mark Pollack
 * @since 1.0.0
 */
public class ArgumentResolverComposite implements ArgumentResolver {

	private final List<ArgumentResolver> resolvers = new ArrayList<>();

	// Cache: parameter -> resolver (Spring pattern)
	private final ConcurrentMap<AcpMethodParameter, ArgumentResolver> resolverCache = new ConcurrentHashMap<>(256);

	// Sentinel value for "no resolver found"
	private static final ArgumentResolver NO_RESOLVER = new ArgumentResolver() {
		@Override
		public boolean supportsParameter(AcpMethodParameter parameter) {
			return false;
		}

		@Override
		public Object resolveArgument(AcpMethodParameter parameter, AcpInvocationContext context) {
			throw new UnsupportedOperationException();
		}
	};

	/**
	 * Add a resolver to the chain.
	 * @param resolver the resolver to add
	 * @return this composite for chaining
	 */
	public ArgumentResolverComposite addResolver(ArgumentResolver resolver) {
		this.resolvers.add(resolver);
		return this;
	}

	/**
	 * Add multiple resolvers to the chain.
	 * @param resolvers the resolvers to add
	 * @return this composite for chaining
	 */
	public ArgumentResolverComposite addResolvers(List<ArgumentResolver> resolvers) {
		this.resolvers.addAll(resolvers);
		return this;
	}

	/**
	 * Get the list of registered resolvers.
	 * @return unmodifiable list of resolvers
	 */
	public List<ArgumentResolver> getResolvers() {
		return new ArrayList<>(resolvers);
	}

	/**
	 * Clear the resolver cache. Call this if resolvers are modified after
	 * the composite has been used.
	 */
	public void clearCache() {
		resolverCache.clear();
	}

	@Override
	public boolean supportsParameter(AcpMethodParameter parameter) {
		return getResolver(parameter) != null;
	}

	@Override
	public Object resolveArgument(AcpMethodParameter parameter, AcpInvocationContext context) {
		ArgumentResolver resolver = getResolver(parameter);
		if (resolver == null) {
			throw new ArgumentResolutionException(
					"No resolver for parameter: " + parameter.getName() + " of type "
							+ parameter.getParameterType().getName());
		}
		return resolver.resolveArgument(parameter, context);
	}

	private ArgumentResolver getResolver(AcpMethodParameter parameter) {
		// Check cache first
		ArgumentResolver resolver = resolverCache.get(parameter);
		if (resolver != null) {
			return resolver == NO_RESOLVER ? null : resolver;
		}

		// Find matching resolver
		for (ArgumentResolver r : resolvers) {
			if (r.supportsParameter(parameter)) {
				resolverCache.put(parameter, r);
				return r;
			}
		}

		// Cache the miss to avoid repeated lookups
		resolverCache.put(parameter, NO_RESOLVER);
		return null;
	}

}
