/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.annotation;

import java.lang.annotation.*;

/**
 * Marks a method as the handler for new session requests.
 *
 * <p>The annotated method handles the {@code session/new} JSON-RPC method,
 * which is called when a client wants to create a new conversation session.
 *
 * <p>The method can have the following parameters (all optional):
 * <ul>
 *   <li>{@code NewSessionRequest} - the new session request</li>
 * </ul>
 *
 * <p>The method should return one of:
 * <ul>
 *   <li>{@code NewSessionResponse} - the new session response with session ID</li>
 *   <li>{@code Mono<NewSessionResponse>} - for async handling</li>
 * </ul>
 *
 * <p>Example usage:
 * <pre>{@code
 * @NewSession
 * public NewSessionResponse newSession(NewSessionRequest req) {
 *     return new NewSessionResponse(UUID.randomUUID().toString(), null, null);
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
public @interface NewSession {

}
