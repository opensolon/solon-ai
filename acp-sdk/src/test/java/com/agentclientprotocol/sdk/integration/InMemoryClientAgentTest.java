/*
 * Copyright 2025-2025 the original author or authors.
 */

package com.agentclientprotocol.sdk.integration;

import com.agentclientprotocol.sdk.spec.AcpAgentTransport;
import com.agentclientprotocol.sdk.spec.AcpClientTransport;
import com.agentclientprotocol.sdk.test.InMemoryTransportPair;

/**
 * Integration tests using in-memory transport for fast, deterministic testing.
 *
 * <p>
 * This test class extends {@link AbstractAcpClientAgentIT} and provides
 * in-memory transport implementations that enable testing client ↔ agent
 * communication without real processes or network connections.
 * </p>
 *
 * @author Mark Pollack
 */
class InMemoryClientAgentTest extends AbstractAcpClientAgentIT {

	private InMemoryTransportPair transportPair;

	@Override
	protected AcpClientTransport createClientTransport() {
		// Create a new transport pair for each test
		transportPair = InMemoryTransportPair.create();
		return transportPair.clientTransport();
	}

	@Override
	protected AcpAgentTransport createAgentTransport() {
		// Reuse the same transport pair created in createClientTransport
		return transportPair.agentTransport();
	}

	@Override
	protected void closeTransports() {
		if (transportPair != null) {
			transportPair.closeGracefully().block(TIMEOUT);
			transportPair = null;
		}
	}

}
