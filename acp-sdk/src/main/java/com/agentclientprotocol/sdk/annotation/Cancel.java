/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.annotation;

import java.lang.annotation.*;

/**
 * Marks a method as the handler for cancellation notifications.
 *
 * <p>The annotated method handles the {@code session/cancel} JSON-RPC notification,
 * which is sent when a client wants to cancel an ongoing operation.
 *
 * <p>Note: This is a notification handler, not a request handler. The method
 * should not return a response - it should perform cleanup and return void.
 *
 * <p>The method can have the following parameters (all optional):
 * <ul>
 *   <li>{@code CancelNotification} - the cancel notification containing the sessionId</li>
 *   <li>{@code @SessionId String} - the session ID being cancelled</li>
 * </ul>
 *
 * <p>The method should return:
 * <ul>
 *   <li>{@code void} - notifications do not have responses</li>
 * </ul>
 *
 * <p>Example usage:
 * <pre>{@code
 * @Cancel
 * public void onCancel(@SessionId String sessionId) {
 *     // Clean up resources for the cancelled session
 *     runningTasks.get(sessionId).cancel();
 * }
 * }</pre>
 *
 * @author Mark Pollack
 * @since 1.0.0
 * @see AcpAgent
 * @see Prompt
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Cancel {

}
