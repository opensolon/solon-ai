/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.annotation;

import java.lang.annotation.*;

/**
 * Marks a method as the handler for setting the session mode.
 *
 * <p>The annotated method handles the {@code session/set_mode} JSON-RPC method,
 * which is called when a client wants to change the operating mode of the session.
 *
 * <p>The method can have the following parameters (all optional):
 * <ul>
 *   <li>{@code SetSessionModeRequest} - the request containing sessionId and modeId</li>
 *   <li>{@code @SessionId String} - the session ID</li>
 *   <li>{@code String modeId} - the mode ID to set (when parameter name matches)</li>
 * </ul>
 *
 * <p>The method should return one of:
 * <ul>
 *   <li>{@code SetSessionModeResponse} - the response (typically empty)</li>
 *   <li>{@code void} - auto-converts to empty response</li>
 *   <li>{@code Mono<SetSessionModeResponse>} - for async handling</li>
 * </ul>
 *
 * <p>Example usage:
 * <pre>{@code
 * @SetSessionMode
 * public void setMode(SetSessionModeRequest req) {
 *     currentMode = req.modeId();
 * }
 * }</pre>
 *
 * @author Mark Pollack
 * @since 1.0.0
 * @see AcpAgent
 * @see SetSessionModel
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface SetSessionMode {

}
