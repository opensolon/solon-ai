/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.annotation;

import java.lang.annotation.*;

/**
 * Marks a method as the handler for prompt requests.
 *
 * <p>The annotated method handles the {@code session/prompt} JSON-RPC method,
 * which is the main entry point for processing user messages.
 *
 * <p>The method can have the following parameters (all optional, in any order):
 * <ul>
 *   <li>{@code PromptRequest} - the prompt request containing user message</li>
 *   <li>{@code SyncPromptContext} - context for sync handlers with convenience methods</li>
 *   <li>{@code PromptContext} - context for async handlers returning Mono</li>
 *   <li>{@code NegotiatedCapabilities} - the negotiated client capabilities</li>
 *   <li>Custom types annotated with {@code @SessionState} - session-scoped state</li>
 * </ul>
 *
 * <p>The method should return one of:
 * <ul>
 *   <li>{@code PromptResponse} - the prompt response</li>
 *   <li>{@code String} - converted to PromptResponse.text()</li>
 *   <li>{@code void} - converted to PromptResponse.endTurn()</li>
 *   <li>{@code Mono<PromptResponse>} - for async handling</li>
 * </ul>
 *
 * <p>Example usage:
 * <pre>{@code
 * @Prompt
 * public PromptResponse handlePrompt(PromptRequest req, SyncPromptContext context) {
 *     context.sendMessage("Processing your request...");
 *
 *     // Read files, execute commands, etc.
 *     String content = context.readFile("/path/to/file.txt");
 *
 *     return PromptResponse.text("Here's what I found: " + content);
 * }
 * }</pre>
 *
 * @author Mark Pollack
 * @since 1.0.0
 * @see AcpAgent
 * @see SessionState
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Prompt {

}
