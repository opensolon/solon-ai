/*
* Copyright 2024 - 2024 the original author or authors.
*/
package io.modelcontextprotocol.spec;

import reactor.core.publisher.Mono;

import java.util.function.Function;

/**
 * Marker interface for the client-side MCP transport.
 *
 * @author Christian Tzolov
 * @author Dariusz Jędrzejczyk
 */
public interface McpClientTransport extends ClientMcpTransport {

	@Override
	Mono<Void> connect(Function<Mono<McpSchema.JSONRPCMessage>, Mono<McpSchema.JSONRPCMessage>> handler);

}
