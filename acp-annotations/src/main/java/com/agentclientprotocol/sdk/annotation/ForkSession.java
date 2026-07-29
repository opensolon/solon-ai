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
 * Marks a method as the handler for forking ACP sessions.
 *
 * <p>The annotated method handles the {@code session/fork} JSON-RPC method,
 * which creates a new session branched from an existing one.
 *
 * @author Mark Pollack
 * @since 0.12.0
 * @see AcpAgent
 */
@UnstableAcpApi
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface ForkSession {

}
