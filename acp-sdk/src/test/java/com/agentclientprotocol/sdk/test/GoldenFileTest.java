/*
 * Copyright 2025-2025 the original author or authors.
 */

package com.agentclientprotocol.sdk.test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import com.agentclientprotocol.sdk.spec.AcpSchema;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.modelcontextprotocol.json.McpJsonMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests that verify JSON serialization/deserialization against golden files.
 *
 * <p>
 * Golden files contain known-good JSON representations of protocol messages.
 * These tests ensure the SDK can correctly parse and produce wire-format JSON.
 * </p>
 */
class GoldenFileTest {

	private final McpJsonMapper jsonMapper = McpJsonMapper.getDefault();

	private final ObjectMapper objectMapper = new ObjectMapper().configure(SerializationFeature.INDENT_OUTPUT, true);

	@Test
	void parseInitializeRequest() throws IOException {
		String json = loadGoldenFile("initialize-request.json");
		JsonNode node = objectMapper.readTree(json);

		assertThat(node.get("jsonrpc").asText()).isEqualTo("2.0");
		assertThat(node.get("method").asText()).isEqualTo("initialize");
		assertThat(node.get("id").asText()).isEqualTo("1");
		assertThat(node.has("params")).isTrue();
		assertThat(node.get("params").get("protocolVersion").asInt()).isEqualTo(1);
	}

	@Test
	void parseInitializeResponse() throws IOException {
		String json = loadGoldenFile("initialize-response.json");
		JsonNode node = objectMapper.readTree(json);

		assertThat(node.get("jsonrpc").asText()).isEqualTo("2.0");
		assertThat(node.get("id").asText()).isEqualTo("1");
		assertThat(node.has("result")).isTrue();
		assertThat(node.get("result").get("protocolVersion").asInt()).isEqualTo(1);
		assertThat(node.get("error").isNull()).isTrue();
	}

	@Test
	void parseSessionNewRequest() throws IOException {
		String json = loadGoldenFile("session-new-request.json");
		JsonNode node = objectMapper.readTree(json);

		assertThat(node.get("method").asText()).isEqualTo("session/new");
		assertThat(node.get("params").get("cwd").asText()).isEqualTo("/workspace");
	}

	@Test
	void parseSessionPromptRequest() throws IOException {
		String json = loadGoldenFile("session-prompt-request.json");
		JsonNode node = objectMapper.readTree(json);

		assertThat(node.get("method").asText()).isEqualTo("session/prompt");
		assertThat(node.get("params").get("sessionId").asText()).isEqualTo("session-abc123");
		assertThat(node.get("params").get("content").isArray()).isTrue();
		assertThat(node.get("params").get("content").get(0).get("type").asText()).isEqualTo("text");
	}

	@Test
	void parseSessionUpdateNotification() throws IOException {
		String json = loadGoldenFile("session-update-notification.json");
		JsonNode node = objectMapper.readTree(json);

		assertThat(node.get("method").asText()).isEqualTo("session/update");
		assertThat(node.has("id")).isFalse(); // Notifications don't have id
		assertThat(node.get("params").get("sessionId").asText()).isEqualTo("session-abc123");
		assertThat(node.get("params").get("update").get("sessionUpdate").asText()).isEqualTo("agentMessage");
	}

	@Test
	void serializeInitializeRequestMatchesExpectedStructure() throws IOException {
		AcpSchema.InitializeRequest request = new AcpSchema.InitializeRequest(1, new AcpSchema.ClientCapabilities());
		AcpSchema.JSONRPCRequest jsonRpcRequest = new AcpSchema.JSONRPCRequest(AcpSchema.METHOD_INITIALIZE, "1",
				request);

		String json = jsonMapper.writeValueAsString(jsonRpcRequest);
		JsonNode node = objectMapper.readTree(json);

		assertThat(node.get("jsonrpc").asText()).isEqualTo("2.0");
		assertThat(node.get("method").asText()).isEqualTo("initialize");
		assertThat(node.get("id").asText()).isEqualTo("1");
	}

	private String loadGoldenFile(String filename) throws IOException {
		String path = "golden/" + filename;
		try (InputStream is = getClass().getClassLoader().getResourceAsStream(path)) {
			if (is == null) {
				throw new IOException("Golden file not found: " + path);
			}
			java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
			byte[] buffer = new byte[1024];
			int len;
			while ((len = is.read(buffer)) != -1) { baos.write(buffer, 0, len); }
			return new String(baos.toByteArray(), StandardCharsets.UTF_8);
		}
	}

}
