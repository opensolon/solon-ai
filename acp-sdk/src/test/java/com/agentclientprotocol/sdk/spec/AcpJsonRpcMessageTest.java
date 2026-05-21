/*
 * Copyright 2025-2025 the original author or authors.
 */

package com.agentclientprotocol.sdk.spec;

import java.io.IOException;

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for JSON-RPC message deserialization.
 *
 * <p>
 * Verifies that the ACP schema can correctly deserialize JSON-RPC messages and
 * distinguish between requests, responses, and notifications.
 * </p>
 *
 * @author Mark Pollack
 * @author Christian Tzolov
 */
class AcpJsonRpcMessageTest {

	private final McpJsonMapper jsonMapper = McpJsonDefaults.getMapper();

	@Test
	void deserializeJsonRpcRequest() throws IOException {
		String json = "{\n	\"jsonrpc\": \"2.0\",\n	\"id\": 1,\n	\"method\": \"initialize\",\n	\"params\": {\"protocolVersion\": 1}\n}\n";

		AcpSchema.JSONRPCMessage message = AcpSchema.deserializeJsonRpcMessage(jsonMapper, json);

		assertThat(message).isInstanceOf(AcpSchema.JSONRPCRequest.class);
		AcpSchema.JSONRPCRequest request = (AcpSchema.JSONRPCRequest) message;
		assertThat(request.jsonrpc()).isEqualTo("2.0");
		assertThat(request.id()).isEqualTo(1);
		assertThat(request.method()).isEqualTo("initialize");
		assertThat(request.params()).isNotNull();
	}

	@Test
	void deserializeJsonRpcRequestWithStringId() throws IOException {
		String json = "{\n	\"jsonrpc\": \"2.0\",\n	\"id\": \"request-123\",\n	\"method\": \"session/prompt\",\n	\"params\": {}\n}\n";

		AcpSchema.JSONRPCMessage message = AcpSchema.deserializeJsonRpcMessage(jsonMapper, json);

		assertThat(message).isInstanceOf(AcpSchema.JSONRPCRequest.class);
		AcpSchema.JSONRPCRequest request = (AcpSchema.JSONRPCRequest) message;
		assertThat(request.id()).isEqualTo("request-123");
	}

	@Test
	void deserializeJsonRpcNotification() throws IOException {
		String json = "{\n	\"jsonrpc\": \"2.0\",\n	\"method\": \"session/update\",\n	\"params\": {\"sessionId\": \"test\"}\n}\n";

		AcpSchema.JSONRPCMessage message = AcpSchema.deserializeJsonRpcMessage(jsonMapper, json);

		assertThat(message).isInstanceOf(AcpSchema.JSONRPCNotification.class);
		AcpSchema.JSONRPCNotification notification = (AcpSchema.JSONRPCNotification) message;
		assertThat(notification.jsonrpc()).isEqualTo("2.0");
		assertThat(notification.method()).isEqualTo("session/update");
		assertThat(notification.params()).isNotNull();
	}

	@Test
	void deserializeJsonRpcResponse() throws IOException {
		String json = "{\n	\"jsonrpc\": \"2.0\",\n	\"id\": 1,\n	\"result\": {\"protocolVersion\": 1}\n}\n";

		AcpSchema.JSONRPCMessage message = AcpSchema.deserializeJsonRpcMessage(jsonMapper, json);

		assertThat(message).isInstanceOf(AcpSchema.JSONRPCResponse.class);
		AcpSchema.JSONRPCResponse response = (AcpSchema.JSONRPCResponse) message;
		assertThat(response.jsonrpc()).isEqualTo("2.0");
		assertThat(response.id()).isEqualTo(1);
		assertThat(response.result()).isNotNull();
		assertThat(response.error()).isNull();
	}

	@Test
	void deserializeJsonRpcErrorResponse() throws IOException {
		String json = "{\n	\"jsonrpc\": \"2.0\",\n	\"id\": 1,\n	\"error\": {\n		\"code\": -32600,\n		\"message\": \"Invalid Request\"\n	}\n}\n";

		AcpSchema.JSONRPCMessage message = AcpSchema.deserializeJsonRpcMessage(jsonMapper, json);

		assertThat(message).isInstanceOf(AcpSchema.JSONRPCResponse.class);
		AcpSchema.JSONRPCResponse response = (AcpSchema.JSONRPCResponse) message;
		assertThat(response.jsonrpc()).isEqualTo("2.0");
		assertThat(response.id()).isEqualTo(1);
		assertThat(response.result()).isNull();
		assertThat(response.error()).isNotNull();
		assertThat(response.error().code()).isEqualTo(-32600);
		assertThat(response.error().message()).isEqualTo("Invalid Request");
	}

	@Test
	void deserializeInvalidJsonThrowsException() {
		String json = "not valid json";

		assertThatThrownBy(() -> AcpSchema.deserializeJsonRpcMessage(jsonMapper, json)).isInstanceOf(IOException.class);
	}

	@Test
	void deserializeUnknownMessageTypeThrowsException() {
		String json = "{\n	\"jsonrpc\": \"2.0\",\n	\"unknownField\": \"value\"\n}\n";

		assertThatThrownBy(() -> AcpSchema.deserializeJsonRpcMessage(jsonMapper, json))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("Cannot deserialize JSONRPCMessage");
	}

}
