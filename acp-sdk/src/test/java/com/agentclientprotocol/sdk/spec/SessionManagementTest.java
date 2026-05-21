/*
 * Copyright 2025-2025 the original author or authors.
 */

package com.agentclientprotocol.sdk.spec;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.TypeRef;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.Collections;
import java.util.Arrays;

/**
 * Tests for session management types: session/load, session/set_mode, and session modes.
 *
 * @author Mark Pollack
 */
class SessionManagementTest {

	private final McpJsonMapper jsonMapper = McpJsonDefaults.getMapper();

	private final ObjectMapper objectMapper = new ObjectMapper();

	// ---------------------------
	// Helper Methods
	// ---------------------------

	private String loadGolden(String name) throws IOException {
		String path = "/golden/" + name;
		try (InputStream is = getClass().getResourceAsStream(path)) {
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

	// ---------------------------
	// LoadSessionRequest Tests
	// ---------------------------

	@Test
	void loadSessionRequestDeserialization() throws IOException {
		String json = loadGolden("session-load-request.json");

		AcpSchema.LoadSessionRequest request = jsonMapper.readValue(json,
				new TypeRef<AcpSchema.LoadSessionRequest>() {
				});

		assertThat(request.sessionId()).isEqualTo("sess_789xyz");
		assertThat(request.cwd()).isEqualTo("/home/user/project");
		assertThat(request.mcpServers()).hasSize(1);
		assertThat(request.mcpServers().get(0)).isInstanceOf(AcpSchema.McpServerStdio.class);

		AcpSchema.McpServerStdio server = (AcpSchema.McpServerStdio) request.mcpServers().get(0);
		assertThat(server.name()).isEqualTo("filesystem");
		assertThat(server.command()).isEqualTo("/path/to/mcp-server");
	}

	@Test
	void loadSessionRequestRoundTrip() throws IOException {
		AcpSchema.LoadSessionRequest original = new AcpSchema.LoadSessionRequest("sess_test123", "/workspace",
				Collections.unmodifiableList(Arrays.asList(new AcpSchema.McpServerStdio("fs", "/bin/mcp", Collections.emptyList(), Collections.emptyList()))));

		String json = jsonMapper.writeValueAsString(original);
		AcpSchema.LoadSessionRequest result = jsonMapper.readValue(json,
				new TypeRef<AcpSchema.LoadSessionRequest>() {
				});

		assertThat(result.sessionId()).isEqualTo(original.sessionId());
		assertThat(result.cwd()).isEqualTo(original.cwd());
		assertThat(result.mcpServers()).hasSize(1);
	}

	// ---------------------------
	// LoadSessionResponse Tests
	// ---------------------------

	@Test
	void loadSessionResponseWithModesDeserialization() throws IOException {
		String json = loadGolden("session-load-response.json");

		AcpSchema.LoadSessionResponse response = jsonMapper.readValue(json,
				new TypeRef<AcpSchema.LoadSessionResponse>() {
				});

		assertThat(response.modes()).isNotNull();
		assertThat(response.modes().currentModeId()).isEqualTo("ask");
		assertThat(response.modes().availableModes()).hasSize(3);

		// Verify first mode
		AcpSchema.SessionMode askMode = response.modes().availableModes().get(0);
		assertThat(askMode.id()).isEqualTo("ask");
		assertThat(askMode.name()).isEqualTo("Ask");
		assertThat(askMode.description()).isEqualTo("Request permission before making any changes");

		// Verify other modes exist
		assertThat(response.modes().availableModes().get(1).id()).isEqualTo("architect");
		assertThat(response.modes().availableModes().get(2).id()).isEqualTo("code");
	}

	@Test
	void loadSessionResponseRoundTrip() throws IOException {
		List<AcpSchema.SessionMode> modes = Collections.unmodifiableList(Arrays.asList(
				new AcpSchema.SessionMode("ask", "Ask", "Ask for permission"),
				new AcpSchema.SessionMode("code", "Code", "Full access")));

		AcpSchema.LoadSessionResponse original = new AcpSchema.LoadSessionResponse(
				new AcpSchema.SessionModeState("ask", modes), null);

		String json = jsonMapper.writeValueAsString(original);
		AcpSchema.LoadSessionResponse result = jsonMapper.readValue(json,
				new TypeRef<AcpSchema.LoadSessionResponse>() {
				});

		assertThat(result.modes().currentModeId()).isEqualTo("ask");
		assertThat(result.modes().availableModes()).hasSize(2);
	}

	// ---------------------------
	// SetSessionModeRequest Tests
	// ---------------------------

	@Test
	void setSessionModeRequestDeserialization() throws IOException {
		String json = loadGolden("session-set-mode-request.json");

		AcpSchema.SetSessionModeRequest request = jsonMapper.readValue(json,
				new TypeRef<AcpSchema.SetSessionModeRequest>() {
				});

		assertThat(request.sessionId()).isEqualTo("sess_abc123def456");
		assertThat(request.modeId()).isEqualTo("code");
	}

	@Test
	void setSessionModeRequestRoundTrip() throws IOException {
		AcpSchema.SetSessionModeRequest original = new AcpSchema.SetSessionModeRequest("sess_test", "architect");

		String json = jsonMapper.writeValueAsString(original);
		AcpSchema.SetSessionModeRequest result = jsonMapper.readValue(json,
				new TypeRef<AcpSchema.SetSessionModeRequest>() {
				});

		assertThat(result.sessionId()).isEqualTo(original.sessionId());
		assertThat(result.modeId()).isEqualTo(original.modeId());
	}

	// ---------------------------
	// NewSessionResponse with Modes Tests
	// ---------------------------

	@Test
	void newSessionResponseWithModesDeserialization() throws IOException {
		String json = loadGolden("session-new-response-with-modes.json");

		AcpSchema.NewSessionResponse response = jsonMapper.readValue(json,
				new TypeRef<AcpSchema.NewSessionResponse>() {
				});

		assertThat(response.sessionId()).isEqualTo("sess_abc123def456");
		assertThat(response.modes()).isNotNull();
		assertThat(response.modes().currentModeId()).isEqualTo("ask");
		assertThat(response.modes().availableModes()).hasSize(3);
	}

	@Test
	void newSessionResponseWithModesRoundTrip() throws IOException {
		List<AcpSchema.SessionMode> modes = Collections.unmodifiableList(Arrays.asList(
				new AcpSchema.SessionMode("ask", "Ask", null),
				new AcpSchema.SessionMode("code", "Code", "Write code")));

		AcpSchema.NewSessionResponse original = new AcpSchema.NewSessionResponse("sess_new123",
				new AcpSchema.SessionModeState("ask", modes), null);

		String json = jsonMapper.writeValueAsString(original);
		AcpSchema.NewSessionResponse result = jsonMapper.readValue(json,
				new TypeRef<AcpSchema.NewSessionResponse>() {
				});

		assertThat(result.sessionId()).isEqualTo("sess_new123");
		assertThat(result.modes().currentModeId()).isEqualTo("ask");
		assertThat(result.modes().availableModes()).hasSize(2);
	}

	// ---------------------------
	// SessionModeState Tests
	// ---------------------------

	@Test
	void sessionModeStateWithAllFields() throws IOException {
		List<AcpSchema.SessionMode> modes = Collections.unmodifiableList(Arrays.asList(new AcpSchema.SessionMode("ask", "Ask", "Request permission before making any changes"), new AcpSchema.SessionMode("architect", "Architect",
						"Design and plan software systems without implementation"), new AcpSchema.SessionMode("code", "Code", "Write and modify code with full tool access")));

		AcpSchema.SessionModeState modeState = new AcpSchema.SessionModeState("architect", modes);

		String json = jsonMapper.writeValueAsString(modeState);
		JsonNode node = objectMapper.readTree(json);

		assertThat(node.get("currentModeId").asText()).isEqualTo("architect");
		assertThat(node.get("availableModes").isArray()).isTrue();
		assertThat(node.get("availableModes").size()).isEqualTo(3);
	}

	@Test
	void sessionModeWithNullDescription() throws IOException {
		AcpSchema.SessionMode mode = new AcpSchema.SessionMode("custom", "Custom Mode", null);

		String json = jsonMapper.writeValueAsString(mode);
		JsonNode node = objectMapper.readTree(json);

		assertThat(node.get("id").asText()).isEqualTo("custom");
		assertThat(node.get("name").asText()).isEqualTo("Custom Mode");
		// Null description should not be serialized (NON_NULL)
		assertThat(node.has("description")).isFalse();
	}

	// ---------------------------
	// CurrentModeUpdate Tests
	// ---------------------------

	@Test
	void currentModeUpdateDeserialization() throws IOException {
		// Already tested in SessionUpdateDeserializationTest, but verify structure
		String json = loadGolden("session-update-current-mode.json");

		AcpSchema.SessionUpdate update = jsonMapper.readValue(json, new TypeRef<AcpSchema.SessionUpdate>() {
		});

		assertThat(update).isInstanceOf(AcpSchema.CurrentModeUpdate.class);
		AcpSchema.CurrentModeUpdate modeUpdate = (AcpSchema.CurrentModeUpdate) update;
		assertThat(modeUpdate.currentModeId()).isEqualTo("architect");
	}

	@Test
	void currentModeUpdateRoundTrip() throws IOException {
		AcpSchema.CurrentModeUpdate original = new AcpSchema.CurrentModeUpdate("current_mode_update", "code");

		String json = jsonMapper.writeValueAsString(original);
		AcpSchema.SessionUpdate result = jsonMapper.readValue(json, new TypeRef<AcpSchema.SessionUpdate>() {
		});

		assertThat(result).isInstanceOf(AcpSchema.CurrentModeUpdate.class);
		AcpSchema.CurrentModeUpdate modeUpdate = (AcpSchema.CurrentModeUpdate) result;
		assertThat(modeUpdate.sessionUpdate()).isEqualTo("current_mode_update");
		assertThat(modeUpdate.currentModeId()).isEqualTo("code");
	}

	// ---------------------------
	// Edge Cases
	// ---------------------------

	@Test
	void loadSessionResponseWithNullModes() throws IOException {
		AcpSchema.LoadSessionResponse response = new AcpSchema.LoadSessionResponse(null, null);

		String json = jsonMapper.writeValueAsString(response);
		JsonNode node = objectMapper.readTree(json);

		// Null fields should not be serialized
		assertThat(node.has("modes")).isFalse();
		assertThat(node.has("models")).isFalse();
	}

	@Test
	void loadSessionRequestWithEmptyMcpServers() throws IOException {
		AcpSchema.LoadSessionRequest request = new AcpSchema.LoadSessionRequest("sess_test", "/project", Collections.emptyList());

		String json = jsonMapper.writeValueAsString(request);
		AcpSchema.LoadSessionRequest result = jsonMapper.readValue(json,
				new TypeRef<AcpSchema.LoadSessionRequest>() {
				});

		assertThat(result.mcpServers()).isEmpty();
	}

}
