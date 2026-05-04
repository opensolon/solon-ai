/*
 * Copyright 2025-2025 the original author or authors.
 */

package com.agentclientprotocol.sdk.test;

/**
 * Protocol tests using in-memory transports.
 *
 * <p>
 * This class runs all {@link AbstractProtocolTest} tests using the
 * {@link InMemoryProtocolDriver}, providing fast unit tests for protocol behavior.
 * </p>
 *
 * @author Mark Pollack
 */
class InMemoryProtocolTest extends AbstractProtocolTest {

	InMemoryProtocolTest() {
		super(new InMemoryProtocolDriver());
	}

}
