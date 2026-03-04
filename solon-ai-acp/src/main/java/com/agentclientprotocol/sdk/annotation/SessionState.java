/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.annotation;

import java.lang.annotation.*;

/**
 * Marks a parameter as session-scoped state that should be injected
 * and persisted across requests within the same session.
 *
 * <p>Session state is automatically managed by the runtime:
 * <ul>
 *   <li>On first access, a new instance is created (if not present)</li>
 *   <li>The same instance is returned for subsequent requests in the session</li>
 *   <li>State is cleared when the session ends</li>
 * </ul>
 *
 * <p>Example usage:
 * <pre>{@code
 * public class ConversationHistory {
 *     private final List<Message> messages = new ArrayList<>();
 *
 *     public void add(String role, String content) {
 *         messages.add(new Message(role, content));
 *     }
 *
 *     public List<Message> getMessages() {
 *         return messages;
 *     }
 * }
 *
 * @Prompt
 * public PromptResponse handlePrompt(
 *         PromptRequest req,
 *         @SessionState ConversationHistory history,
 *         SyncPromptContext context) {
 *
 *     history.add("user", req.prompt().toString());
 *     String response = processWithHistory(history);
 *     history.add("assistant", response);
 *
 *     return PromptResponse.text(response);
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
public @interface SessionState {

	/**
	 * Optional key to identify the state. If not specified, the parameter
	 * type's fully qualified name is used as the key.
	 * @return the state key
	 */
	String value() default "";

}
