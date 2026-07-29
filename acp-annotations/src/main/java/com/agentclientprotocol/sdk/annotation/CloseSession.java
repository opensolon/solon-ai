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
 * Marks a method as the handler for closing ACP sessions.
 *
 * <p>The annotated method handles the {@code session/close} JSON-RPC method,
 * which is called when a client wants to close an active session and cancel
 * any in-flight work.
 *
 * <p>The method can have the following parameters (all optional):
 * <ul>
 *   <li>{@code CloseSessionRequest} - the close session request containing the sessionId</li>
 *   <li>{@code @SessionId String} - the session ID being closed</li>
 * </ul>
 *
 * <p>The method should return one of:
 * <ul>
 *   <li>{@code CloseSessionResponse} - the close session response</li>
 *   <li>{@code Mono<CloseSessionResponse>} - for async handling</li>
 * </ul>
 *
 * <p>Example usage:
 * <pre>{@code
 * @CloseSession
 * public CloseSessionResponse close(CloseSessionRequest req) {
 *     // Clean up session resources
 *     return new CloseSessionResponse();
 * }
 * }</pre>
 *
 * @author Mark Pollack
 * @since 1.0.0
 * @see AcpAgent
 * @see Cancel
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface CloseSession {

}
