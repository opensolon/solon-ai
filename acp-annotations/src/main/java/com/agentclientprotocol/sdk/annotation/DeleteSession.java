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
 * Marks a method as the handler for deleting ACP sessions.
 *
 * <p>The annotated method handles the {@code session/delete} JSON-RPC method,
 * which is called when a client wants to permanently delete a stored session so
 * it no longer appears in {@code session/list}.
 *
 * <p>Only available if the agent advertises the {@code sessionCapabilities.delete}
 * capability.
 *
 * <p>The method can have the following parameters (all optional):
 * <ul>
 *   <li>{@code DeleteSessionRequest} - the delete session request containing the sessionId</li>
 *   <li>{@code @SessionId String} - the session ID being deleted</li>
 * </ul>
 *
 * <p>The method should return one of:
 * <ul>
 *   <li>{@code DeleteSessionResponse} - the delete session response</li>
 *   <li>{@code Mono<DeleteSessionResponse>} - for async handling</li>
 *   <li>{@code void} - an empty response is sent automatically</li>
 * </ul>
 *
 * <p>Example usage:
 * <pre>{@code
 * @DeleteSession
 * public DeleteSessionResponse delete(DeleteSessionRequest req) {
 *     // Permanently remove the stored session
 *     return new DeleteSessionResponse();
 * }
 * }</pre>
 *
 * @author Mark Pollack
 * @since 1.0.0
 * @see AcpAgent
 * @see CloseSession
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface DeleteSession {

}
