/*
 * Copyright 2025-2025 the original author or authors.
 */

package io.modelcontextprotocol.spec;

import reactor.util.annotation.Nullable;

/**
 * Exception thrown when trying to use an {@link McpTransportSession} that has been
 * closed.
 *
 * @see ClosedMcpTransportSession
 * @author Daniel Garnier-Moiroux
 */
public class McpTransportSessionClosedException extends RuntimeException {

	public McpTransportSessionClosedException(@Nullable String sessionId) {
		super(sessionId != null ? String.format("MCP session with ID %s has been closed", sessionId)
				: "MCP session has been closed");
	}

}
