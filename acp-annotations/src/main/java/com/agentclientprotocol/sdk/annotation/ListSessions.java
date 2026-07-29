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
 * Marks a method as the handler for listing ACP sessions.
 *
 * <p>The annotated method handles the {@code session/list} JSON-RPC method,
 * which is called when a client wants to list available sessions, optionally
 * filtered by working directory.
 *
 * <p>The method can have the following parameters (all optional):
 * <ul>
 *   <li>{@code ListSessionsRequest} - the list sessions request containing optional cwd filter and cursor</li>
 * </ul>
 *
 * <p>The method should return one of:
 * <ul>
 *   <li>{@code ListSessionsResponse} - the response containing a list of session info objects</li>
 *   <li>{@code Mono<ListSessionsResponse>} - for async handling</li>
 * </ul>
 *
 * <p>Example usage:
 * <pre>{@code
 * @ListSessions
 * public ListSessionsResponse list(ListSessionsRequest req) {
 *     return new ListSessionsResponse(List.of(
 *         new SessionInfo("session-1", "/workspace")));
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
public @interface ListSessions {

}
