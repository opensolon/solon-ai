/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.annotation;

import java.lang.annotation.*;

/**
 * Marks a class as an ACP agent that can handle client requests.
 *
 * <p>Classes annotated with {@code @AcpAgent} can define handler methods
 * using annotations like {@link Prompt}, {@link Initialize}, and {@link NewSession}.
 *
 * <p>Example usage:
 * <pre>{@code
 * @AcpAgent(name = "support-agent", version = "1.0")
 * public class SupportAgent {
 *
 *     @Initialize
 *     public InitializeResponse init(InitializeRequest req) {
 *         return InitializeResponse.ok();
 *     }
 *
 *     @Prompt
 *     public PromptResponse handlePrompt(PromptRequest req, SyncPromptContext context) {
 *         context.sendMessage("Hello!");
 *         return PromptResponse.endTurn();
 *     }
 * }
 * }</pre>
 *
 * @author Mark Pollack
 * @since 1.0.0
 * @see Prompt
 * @see Initialize
 * @see NewSession
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface AcpAgent {

	/**
	 * The name of the agent. If not specified, the class name will be used.
	 * @return the agent name
	 */
	String name() default "";

	/**
	 * The version of the agent.
	 * @return the agent version
	 */
	String version() default "";

}
