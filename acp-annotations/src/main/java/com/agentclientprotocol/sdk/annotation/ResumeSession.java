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
 * Marks a method as the handler for resuming ACP sessions.
 *
 * <p>The annotated method handles the {@code session/resume} JSON-RPC method,
 * which is called when a client wants to reconnect to an existing session
 * without replaying conversation history. Unlike {@link LoadSession}, this
 * method does not send previous messages via session/update notifications.
 *
 * <p>The method can have the following parameters (all optional):
 * <ul>
 *   <li>{@code ResumeSessionRequest} - the resume session request containing sessionId, cwd, and mcpServers</li>
 *   <li>{@code @SessionId String} - the session ID being resumed</li>
 * </ul>
 *
 * <p>The method should return one of:
 * <ul>
 *   <li>{@code ResumeSessionResponse} - the resume session response with modes and models</li>
 *   <li>{@code Mono<ResumeSessionResponse>} - for async handling</li>
 * </ul>
 *
 * <p>Example usage:
 * <pre>{@code
 * @ResumeSession
 * public ResumeSessionResponse resume(ResumeSessionRequest req) {
 *     // Reconnect to session without history replay
 *     return new ResumeSessionResponse(modes, models);
 * }
 * }</pre>
 *
 * @author Mark Pollack
 * @since 1.0.0
 * @see AcpAgent
 * @see LoadSession
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface ResumeSession {

}
