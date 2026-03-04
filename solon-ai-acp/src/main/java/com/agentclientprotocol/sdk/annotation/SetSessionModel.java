/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.annotation;

import java.lang.annotation.*;

/**
 * Marks a method as the handler for setting the session model.
 *
 * <p>The annotated method handles the {@code session/set_model} JSON-RPC method,
 * which is called when a client wants to change the AI model used by the session.
 *
 * <p>The method can have the following parameters (all optional):
 * <ul>
 *   <li>{@code SetSessionModelRequest} - the request containing sessionId and modelId</li>
 *   <li>{@code @SessionId String} - the session ID</li>
 *   <li>{@code String modelId} - the model ID to set (when parameter name matches)</li>
 * </ul>
 *
 * <p>The method should return one of:
 * <ul>
 *   <li>{@code SetSessionModelResponse} - the response (typically empty)</li>
 *   <li>{@code void} - auto-converts to empty response</li>
 *   <li>{@code Mono<SetSessionModelResponse>} - for async handling</li>
 * </ul>
 *
 * <p>Example usage:
 * <pre>{@code
 * @SetSessionModel
 * public void setModel(SetSessionModelRequest req) {
 *     currentModel = req.modelId();
 * }
 * }</pre>
 *
 * @author Mark Pollack
 * @since 1.0.0
 * @see AcpAgent
 * @see SetSessionMode
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface SetSessionModel {

}
