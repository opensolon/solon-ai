/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method as the handler for the {@code providers/list} JSON-RPC method (UNSTABLE).
 *
 * <p>The annotated method lists the providers the agent can route to. It may take a
 * {@code ListProvidersRequest} parameter and should return a {@code ListProvidersResponse}
 * (or {@code Mono<ListProvidersResponse>}).
 *
 * @author Mark Pollack
 * @since 0.13.0
 * @see AcpAgent
 */
@UnstableAcpApi
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface ListProviders {

}
