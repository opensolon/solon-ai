/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.annotation;

import java.lang.annotation.*;

/**
 * Marks a method as the handler for ACP initialization requests.
 *
 * <p>The annotated method handles the {@code initialize} JSON-RPC method,
 * which is called when a client first connects to negotiate capabilities.
 *
 * <p>The method can have the following parameters (all optional):
 * <ul>
 *   <li>{@code InitializeRequest} - the initialization request</li>
 * </ul>
 *
 * <p>The method should return one of:
 * <ul>
 *   <li>{@code InitializeResponse} - the initialization response</li>
 *   <li>{@code Mono<InitializeResponse>} - for async handling</li>
 * </ul>
 *
 * <p>Example usage:
 * <pre>{@code
 * @Initialize
 * public InitializeResponse init(InitializeRequest req) {
 *     return InitializeResponse.ok();
 * }
 * }</pre>
 *
 * @author Mark Pollack
 * @since 1.0.0
 * @see AcpAgent
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Initialize {

}
