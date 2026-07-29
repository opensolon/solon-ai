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
 * Marks a method as the handler for setting session configuration options.
 *
 * <p>The annotated method handles the {@code session/set_config_option}
 * JSON-RPC method, which changes a configuration value for the session.
 *
 * @author Mark Pollack
 * @since 0.12.0
 * @see AcpAgent
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface SetSessionConfigOption {

}
