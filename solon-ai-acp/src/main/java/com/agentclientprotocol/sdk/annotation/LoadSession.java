/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.annotation;

import java.lang.annotation.*;

/**
 * Marks a method as the handler for loading existing ACP sessions.
 *
 * <p>The annotated method handles the {@code session/load} JSON-RPC method,
 * which is called when a client wants to resume an existing session.
 *
 * <p>The method can have the following parameters (all optional):
 * <ul>
 *   <li>{@code LoadSessionRequest} - the load session request containing sessionId, cwd, and mcpServers</li>
 *   <li>{@code @SessionId String} - the session ID being loaded</li>
 * </ul>
 *
 * <p>The method should return one of:
 * <ul>
 *   <li>{@code LoadSessionResponse} - the load session response with modes and models</li>
 *   <li>{@code Mono<LoadSessionResponse>} - for async handling</li>
 * </ul>
 *
 * <p>Example usage:
 * <pre>{@code
 * @LoadSession
 * public LoadSessionResponse load(LoadSessionRequest req) {
 *     // Restore session state
 *     return new LoadSessionResponse(modes, models);
 * }
 * }</pre>
 *
 * @author Mark Pollack
 * @since 1.0.0
 * @see AcpAgent
 * @see NewSession
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface LoadSession {

}
