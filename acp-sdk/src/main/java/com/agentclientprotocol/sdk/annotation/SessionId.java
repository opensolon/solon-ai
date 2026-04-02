/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.annotation;

import java.lang.annotation.*;

/**
 * Marks a String parameter to receive the current session ID.
 *
 * <p>This annotation can be used on a {@code String} parameter in handler
 * methods to inject the current session identifier without needing to
 * extract it from the request object.
 *
 * <p>Example usage:
 * <pre>{@code
 * @Prompt
 * public PromptResponse handlePrompt(
 *         PromptRequest req,
 *         @SessionId String sessionId,
 *         SyncPromptContext context) {
 *
 *     context.sendMessage("Processing request for session: " + sessionId);
 *     return PromptResponse.endTurn();
 * }
 * }</pre>
 *
 * @author Mark Pollack
 * @since 1.0.0
 * @see AcpAgent
 * @see Prompt
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface SessionId {

}
