/*
 * Copyright 2025-2025 the original author or authors.
 */

package com.agentclientprotocol.sdk.test;

import java.util.function.BiConsumer;

import com.agentclientprotocol.sdk.spec.AcpAgentTransport;
import com.agentclientprotocol.sdk.spec.AcpClientTransport;

/**
 * Protocol driver that uses in-memory transports for unit testing.
 *
 * <p>
 * This driver creates an {@link InMemoryTransportPair} for each test,
 * providing fast, deterministic tests without I/O overhead.
 * </p>
 *
 * @author Mark Pollack
 */
public class InMemoryProtocolDriver implements ProtocolDriver {

	@Override
	public void runWithTransports(BiConsumer<AcpClientTransport, AcpAgentTransport> testBlock) {
		InMemoryTransportPair pair = InMemoryTransportPair.create();
		try {
			testBlock.accept(pair.clientTransport(), pair.agentTransport());
		}
		finally {
			pair.closeGracefully().block();
		}
	}

}
