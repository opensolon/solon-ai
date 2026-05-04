/*
 * Copyright 2025-2025 the original author or authors.
 */

package com.agentclientprotocol.sdk.spec;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import com.agentclientprotocol.sdk.AcpTestFixtures;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.TypeRef;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests JSON serialization and deserialization of ACP schema types.
 *
 * <p>
 * Verifies that all ACP protocol types can be correctly serialized to JSON and
 * deserialized back to Java objects with all fields preserved.
 * </p>
 *
 * @author Mark Pollack
 * @author Christian Tzolov
 */
class AcpSchemaSerializationTest {

	private final McpJsonMapper jsonMapper = McpJsonMapper.getDefault();

	// ---------------------------
	// Request/Response Serialization
	// ---------------------------

	@Test
	void initializeRequestSerialization() throws IOException {
		AcpSchema.InitializeRequest request = AcpTestFixtures.createInitializeRequest();

		String json = jsonMapper.writeValueAsString(request);
		AcpSchema.InitializeRequest deserialized = jsonMapper.readValue(json,
				new TypeRef<AcpSchema.InitializeRequest>() {
				});

		assertThat(deserialized.protocolVersion()).isEqualTo(1);
		assertThat(deserialized.clientCapabilities()).isNotNull();
	}

	@Test
	void initializeResponseSerialization() throws IOException {
		AcpSchema.InitializeResponse response = AcpTestFixtures.createInitializeResponse();

		String json = jsonMapper.writeValueAsString(response);
		AcpSchema.InitializeResponse deserialized = jsonMapper.readValue(json,
				new TypeRef<AcpSchema.InitializeResponse>() {
				});

		assertThat(deserialized.protocolVersion()).isEqualTo(1);
		assertThat(deserialized.agentCapabilities()).isNotNull();
		assertThat(deserialized.authMethods()).isEmpty();
	}

	@Test
	void newSessionRequestSerialization() throws IOException {
		AcpSchema.NewSessionRequest request = AcpTestFixtures.createNewSessionRequest();

		String json = jsonMapper.writeValueAsString(request);
		AcpSchema.NewSessionRequest deserialized = jsonMapper.readValue(json,
				new TypeRef<AcpSchema.NewSessionRequest>() {
				});

		assertThat(deserialized.cwd()).isEqualTo("/test/workspace");
		assertThat(deserialized.mcpServers()).isEmpty();
	}

	@Test
	void newSessionResponseSerialization() throws IOException {
		AcpSchema.NewSessionResponse response = AcpTestFixtures.createNewSessionResponse();

		String json = jsonMapper.writeValueAsString(response);
		AcpSchema.NewSessionResponse deserialized = jsonMapper.readValue(json,
				new TypeRef<AcpSchema.NewSessionResponse>() {
				});

		assertThat(deserialized.sessionId()).isEqualTo("test-session-id");
		assertThat(deserialized.modes()).isNotNull();
		assertThat(deserialized.models()).isNotNull();
	}

	@Test
	void promptRequestSerialization() throws IOException {
		AcpSchema.PromptRequest request = AcpTestFixtures.createPromptRequest();

		String json = jsonMapper.writeValueAsString(request);
		AcpSchema.PromptRequest deserialized = jsonMapper.readValue(json, new TypeRef<AcpSchema.PromptRequest>() {
		});

		assertThat(deserialized.sessionId()).isEqualTo("test-session-id");
		assertThat(deserialized.prompt()).hasSize(1);
	}

	@Test
	void promptResponseSerialization() throws IOException {
		AcpSchema.PromptResponse response = AcpTestFixtures.createPromptResponse();

		String json = jsonMapper.writeValueAsString(response);
		AcpSchema.PromptResponse deserialized = jsonMapper.readValue(json, new TypeRef<AcpSchema.PromptResponse>() {
		});

		assertThat(deserialized.stopReason()).isEqualTo(AcpSchema.StopReason.END_TURN);
	}

	// ---------------------------
	// Capabilities Serialization
	// ---------------------------

	@Test
	void clientCapabilitiesSerialization() throws IOException {
		AcpSchema.ClientCapabilities capabilities = AcpTestFixtures.createClientCapabilitiesWithFs();

		String json = jsonMapper.writeValueAsString(capabilities);
		AcpSchema.ClientCapabilities deserialized = jsonMapper.readValue(json,
				new TypeRef<AcpSchema.ClientCapabilities>() {
				});

		assertThat(deserialized.fs()).isNotNull();
		assertThat(deserialized.fs().readTextFile()).isTrue();
		assertThat(deserialized.fs().writeTextFile()).isTrue();
	}

	@Test
	void agentCapabilitiesSerialization() throws IOException {
		AcpSchema.AgentCapabilities capabilities = AcpTestFixtures.createAgentCapabilitiesWithLoadSession();

		String json = jsonMapper.writeValueAsString(capabilities);
		AcpSchema.AgentCapabilities deserialized = jsonMapper.readValue(json,
				new TypeRef<AcpSchema.AgentCapabilities>() {
				});

		assertThat(deserialized.loadSession()).isTrue();
		assertThat(deserialized.mcpCapabilities()).isNotNull();
		assertThat(deserialized.promptCapabilities()).isNotNull();
	}

	// ---------------------------
	// Content Block Serialization
	// ---------------------------

	@Test
	void textContentSerialization() throws IOException {
		AcpSchema.TextContent content = AcpTestFixtures.createTextContent("Hello, world!");

		String json = jsonMapper.writeValueAsString(content);
		AcpSchema.TextContent deserialized = jsonMapper.readValue(json, new TypeRef<AcpSchema.TextContent>() {
		});

		assertThat(deserialized.text()).isEqualTo("Hello, world!");
		assertThat(deserialized.type()).isEqualTo("text");
	}

	@Test
	void imageContentSerialization() throws IOException {
		AcpSchema.ImageContent content = AcpTestFixtures.createImageContent();

		String json = jsonMapper.writeValueAsString(content);
		AcpSchema.ImageContent deserialized = jsonMapper.readValue(json, new TypeRef<AcpSchema.ImageContent>() {
		});

		assertThat(deserialized.type()).isEqualTo("image");
		assertThat(deserialized.data()).isEqualTo("base64-encoded-data");
		assertThat(deserialized.mimeType()).isEqualTo("image/png");
	}

	@Test
	void audioContentSerialization() throws IOException {
		AcpSchema.AudioContent content = AcpTestFixtures.createAudioContent();

		String json = jsonMapper.writeValueAsString(content);
		AcpSchema.AudioContent deserialized = jsonMapper.readValue(json, new TypeRef<AcpSchema.AudioContent>() {
		});

		assertThat(deserialized.type()).isEqualTo("audio");
		assertThat(deserialized.data()).isEqualTo("base64-encoded-data");
		assertThat(deserialized.mimeType()).isEqualTo("audio/wav");
	}

	// ---------------------------
	// JSON-RPC Message Serialization
	// ---------------------------

	@Test
	void jsonRpcRequestSerialization() throws IOException {
		AcpSchema.JSONRPCRequest request = AcpTestFixtures.createJsonRpcRequest("test/method", 1,
				Collections.singletonMap("key", "value"));

		String json = jsonMapper.writeValueAsString(request);
		AcpSchema.JSONRPCRequest deserialized = jsonMapper.readValue(json, new TypeRef<AcpSchema.JSONRPCRequest>() {
		});

		assertThat(deserialized.jsonrpc()).isEqualTo("2.0");
		assertThat(deserialized.id()).isEqualTo(1);
		assertThat(deserialized.method()).isEqualTo("test/method");
		assertThat(deserialized.params()).isNotNull();
	}

	@Test
	void jsonRpcNotificationSerialization() throws IOException {
		AcpSchema.JSONRPCNotification notification = AcpTestFixtures.createJsonRpcNotification("test/notification",
				Collections.singletonMap("key", "value"));

		String json = jsonMapper.writeValueAsString(notification);
		AcpSchema.JSONRPCNotification deserialized = jsonMapper.readValue(json,
				new TypeRef<AcpSchema.JSONRPCNotification>() {
				});

		assertThat(deserialized.jsonrpc()).isEqualTo("2.0");
		assertThat(deserialized.method()).isEqualTo("test/notification");
		assertThat(deserialized.params()).isNotNull();
	}

	@Test
	void jsonRpcResponseSerialization() throws IOException {
		AcpSchema.JSONRPCResponse response = AcpTestFixtures.createJsonRpcResponse(1,
				Collections.singletonMap("result", "success"));

		String json = jsonMapper.writeValueAsString(response);
		AcpSchema.JSONRPCResponse deserialized = jsonMapper.readValue(json, new TypeRef<AcpSchema.JSONRPCResponse>() {
		});

		assertThat(deserialized.jsonrpc()).isEqualTo("2.0");
		assertThat(deserialized.id()).isEqualTo(1);
		assertThat(deserialized.result()).isNotNull();
		assertThat(deserialized.error()).isNull();
	}

	@Test
	void jsonRpcErrorResponseSerialization() throws IOException {
		AcpSchema.JSONRPCResponse response = AcpTestFixtures.createJsonRpcErrorResponse(1, -32600,
				"Invalid Request");

		String json = jsonMapper.writeValueAsString(response);
		AcpSchema.JSONRPCResponse deserialized = jsonMapper.readValue(json, new TypeRef<AcpSchema.JSONRPCResponse>() {
		});

		assertThat(deserialized.jsonrpc()).isEqualTo("2.0");
		assertThat(deserialized.id()).isEqualTo(1);
		assertThat(deserialized.result()).isNull();
		assertThat(deserialized.error()).isNotNull();
		assertThat(deserialized.error().code()).isEqualTo(-32600);
		assertThat(deserialized.error().message()).isEqualTo("Invalid Request");
	}

	// ---------------------------
	// Session State Serialization
	// ---------------------------

	@Test
	void sessionModeStateSerialization() throws IOException {
		AcpSchema.SessionModeState state = AcpTestFixtures.createSessionModeState();

		String json = jsonMapper.writeValueAsString(state);
		AcpSchema.SessionModeState deserialized = jsonMapper.readValue(json,
				new TypeRef<AcpSchema.SessionModeState>() {
				});

		assertThat(deserialized.currentModeId()).isEqualTo("code");
		assertThat(deserialized.availableModes()).hasSize(1);
		assertThat(deserialized.availableModes().get(0).id()).isEqualTo("code");
	}

	@Test
	void sessionModelStateSerialization() throws IOException {
		AcpSchema.SessionModelState state = AcpTestFixtures.createSessionModelState();

		String json = jsonMapper.writeValueAsString(state);
		AcpSchema.SessionModelState deserialized = jsonMapper.readValue(json,
				new TypeRef<AcpSchema.SessionModelState>() {
				});

		assertThat(deserialized.currentModelId()).isEqualTo("test-model");
		assertThat(deserialized.availableModels()).hasSize(1);
		assertThat(deserialized.availableModels().get(0).modelId()).isEqualTo("test-model");
	}

	// ---------------------------
	// _meta Field Serialization Tests
	// ---------------------------

	@Test
	void initializeRequestWithMetaSerialization() throws IOException {
		Map<String, Object> meta = Collections.unmodifiableMap(new java.util.LinkedHashMap<String, Object>() {{ put("zed.dev/debugMode", true); put("custom/version", "1.0.0"); }});
		AcpSchema.InitializeRequest request = new AcpSchema.InitializeRequest(1,
				new AcpSchema.ClientCapabilities(new AcpSchema.FileSystemCapability(true, true), true), null, meta);

		String json = jsonMapper.writeValueAsString(request);
		assertThat(json).contains("\"_meta\"");
		assertThat(json).contains("zed.dev/debugMode");

		AcpSchema.InitializeRequest deserialized = jsonMapper.readValue(json,
				new TypeRef<AcpSchema.InitializeRequest>() {
				});

		assertThat(deserialized.meta()).isNotNull();
		assertThat(deserialized.meta()).containsKey("zed.dev/debugMode");
		assertThat(deserialized.meta().get("zed.dev/debugMode")).isEqualTo(true);
		assertThat(deserialized.meta().get("custom/version")).isEqualTo("1.0.0");
	}

	@Test
	void initializeRequestWithoutMetaOmitsField() throws IOException {
		AcpSchema.InitializeRequest request = new AcpSchema.InitializeRequest(1, new AcpSchema.ClientCapabilities());

		String json = jsonMapper.writeValueAsString(request);
		assertThat(json).doesNotContain("\"_meta\"");

		AcpSchema.InitializeRequest deserialized = jsonMapper.readValue(json,
				new TypeRef<AcpSchema.InitializeRequest>() {
				});
		assertThat(deserialized.meta()).isNull();
	}

	@Test
	void promptRequestWithMetaSerialization() throws IOException {
		Map<String, Object> meta = Collections.singletonMap("zed.dev/debugMode", true);
		AcpSchema.PromptRequest request = new AcpSchema.PromptRequest("sess_123",
				Collections.singletonList(new AcpSchema.TextContent("Hello")), meta);

		String json = jsonMapper.writeValueAsString(request);
		assertThat(json).contains("\"_meta\"");

		AcpSchema.PromptRequest deserialized = jsonMapper.readValue(json, new TypeRef<AcpSchema.PromptRequest>() {
		});

		assertThat(deserialized.meta()).isNotNull();
		assertThat(deserialized.meta().get("zed.dev/debugMode")).isEqualTo(true);
	}

	@Test
	void agentCapabilitiesWithMetaSerialization() throws IOException {
		// Nested _meta object as shown in spec for advertising custom capabilities
		Map<String, Object> zedCapabilities = Collections.unmodifiableMap(new java.util.LinkedHashMap<String, Object>() {{ put("workspace", true); put("fileNotifications", true); }});
		Map<String, Object> meta = Collections.singletonMap("zed.dev", zedCapabilities);
		AcpSchema.AgentCapabilities caps = new AcpSchema.AgentCapabilities(true, new AcpSchema.McpCapabilities(true, false),
				new AcpSchema.PromptCapabilities(false, true, true), meta);

		String json = jsonMapper.writeValueAsString(caps);
		assertThat(json).contains("\"_meta\"");
		assertThat(json).contains("zed.dev");

		AcpSchema.AgentCapabilities deserialized = jsonMapper.readValue(json,
				new TypeRef<AcpSchema.AgentCapabilities>() {
				});

		assertThat(deserialized.meta()).isNotNull();
		@SuppressWarnings("unchecked")
		Map<String, Object> zedCaps = (Map<String, Object>) deserialized.meta().get("zed.dev");
		assertThat(zedCaps).containsEntry("workspace", true);
		assertThat(zedCaps).containsEntry("fileNotifications", true);
	}

	@Test
	void sessionUpdateWithMetaSerialization() throws IOException {
		Map<String, Object> meta = Collections.singletonMap("custom/field", "value");
		AcpSchema.AgentMessageChunk update = new AcpSchema.AgentMessageChunk("agent_message_chunk",
				new AcpSchema.TextContent("Hello"), meta);

		String json = jsonMapper.writeValueAsString(update);
		assertThat(json).contains("\"_meta\"");

		AcpSchema.AgentMessageChunk deserialized = jsonMapper.readValue(json,
				new TypeRef<AcpSchema.AgentMessageChunk>() {
				});

		assertThat(deserialized.meta()).isNotNull();
		assertThat(deserialized.meta().get("custom/field")).isEqualTo("value");
	}

	@Test
	void metaFieldRoundTripFromGoldenFile() throws IOException {
		// Read golden file and verify round-trip
		try (java.io.InputStream is = getClass().getResourceAsStream("/golden/initialize-request-with-meta.json")) {
			if (is == null) {
				throw new IOException("Golden file not found: /golden/initialize-request-with-meta.json");
			}
			java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
			byte[] buffer = new byte[1024];
			int len;
			while ((len = is.read(buffer)) != -1) { baos.write(buffer, 0, len); }
			String goldenJson = new String(baos.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);

			AcpSchema.InitializeRequest deserialized = jsonMapper.readValue(goldenJson,
					new TypeRef<AcpSchema.InitializeRequest>() {
					});

			assertThat(deserialized.protocolVersion()).isEqualTo(1);
			assertThat(deserialized.meta()).isNotNull();
			assertThat(deserialized.meta()).containsKey("zed.dev/debugMode");
			assertThat(deserialized.meta().get("zed.dev/debugMode")).isEqualTo(true);
			assertThat(deserialized.meta().get("custom/version")).isEqualTo("1.0.0");
		}
	}

}
