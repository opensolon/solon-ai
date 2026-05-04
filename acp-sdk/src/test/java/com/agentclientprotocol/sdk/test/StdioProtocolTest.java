/*
 * Copyright 2025-2025 the original author or authors.
 */

package com.agentclientprotocol.sdk.test;

/**
 * Protocol tests using stdio-based transports with piped streams.
 *
 * <p>
 * This class runs all {@link AbstractProtocolTest} tests using the
 * {@link StdioProtocolDriver}, validating protocol behavior through
 * the actual stdio transport implementation.
 * </p>
 *
 * @author Mark Pollack
 */
class StdioProtocolTest extends AbstractProtocolTest {

	StdioProtocolTest() {
		super(new StdioProtocolDriver());
	}

}
