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
 * Marks a method as the handler for logout requests.
 *
 * <p>The annotated method handles the {@code logout} JSON-RPC method, which is
 * called when a client wants to clear stored credentials and terminate the
 * current authenticated session.
 *
 * <p>The method can have the following parameter (optional):
 * <ul>
 *   <li>{@code LogoutRequest} - the logout request</li>
 * </ul>
 *
 * <p>The method should return one of:
 * <ul>
 *   <li>{@code LogoutResponse} - the logout response</li>
 *   <li>{@code Mono<LogoutResponse>} - for async handling</li>
 *   <li>{@code void} - an empty response is sent automatically</li>
 * </ul>
 *
 * <p>Example usage:
 * <pre>{@code
 * @Logout
 * public LogoutResponse logout(LogoutRequest req) {
 *     // Clear stored credentials
 *     return new LogoutResponse();
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
public @interface Logout {

}
