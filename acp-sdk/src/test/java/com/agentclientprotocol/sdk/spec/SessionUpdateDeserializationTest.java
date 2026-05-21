/*
 * Copyright 2025-2025 the original author or authors.
 */

package com.agentclientprotocol.sdk.spec;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.TypeRef;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for deserializing all SessionUpdate types from JSON.
 *
 * <p>
 * These tests verify that each SessionUpdate type can be correctly deserialized from JSON,
 * including complex nested structures like tool call content, plan entries, and available commands.
 * </p>
 *
 * <p>
 * Golden files are located in src/test/resources/golden/ and match the format used by the
 * Python SDK for consistency across implementations.
 * </p>
 *
 * @author Mark Pollack
 */
class SessionUpdateDeserializationTest {

	private final McpJsonMapper jsonMapper = McpJsonDefaults.getMapper();

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

	private AcpSchema.SessionUpdate deserializeSessionUpdate(String json) throws IOException {
		return jsonMapper.readValue(json, new TypeRef<AcpSchema.SessionUpdate>() {
		});
	}

	// ---------------------------
	// UserMessageChunk Tests
	// ---------------------------

	@Test
	void userMessageChunkDeserialization() throws IOException {
		String json = loadGolden("session-update-user-message-chunk.json");

		AcpSchema.SessionUpdate update = deserializeSessionUpdate(json);

		assertThat(update).isInstanceOf(AcpSchema.UserMessageChunk.class);
		AcpSchema.UserMessageChunk chunk = (AcpSchema.UserMessageChunk) update;
		assertThat(chunk.sessionUpdate()).isEqualTo("user_message_chunk");
		assertThat(chunk.content()).isInstanceOf(AcpSchema.TextContent.class);
		AcpSchema.TextContent text = (AcpSchema.TextContent) chunk.content();
		assertThat(text.text()).isEqualTo("What's the capital of France?");
	}

	// ---------------------------
	// AgentMessageChunk Tests
	// ---------------------------

	@Test
	void agentMessageChunkDeserialization() throws IOException {
		String json = loadGolden("session-update-agent-message-chunk.json");

		AcpSchema.SessionUpdate update = deserializeSessionUpdate(json);

		assertThat(update).isInstanceOf(AcpSchema.AgentMessageChunk.class);
		AcpSchema.AgentMessageChunk chunk = (AcpSchema.AgentMessageChunk) update;
		assertThat(chunk.sessionUpdate()).isEqualTo("agent_message_chunk");
		assertThat(chunk.content()).isInstanceOf(AcpSchema.TextContent.class);
		AcpSchema.TextContent text = (AcpSchema.TextContent) chunk.content();
		assertThat(text.text()).isEqualTo("I'll help you fix the failing test.");
	}

	// ---------------------------
	// AgentThoughtChunk Tests
	// ---------------------------

	@Test
	void agentThoughtChunkDeserialization() throws IOException {
		String json = loadGolden("session-update-agent-thought-chunk.json");

		AcpSchema.SessionUpdate update = deserializeSessionUpdate(json);

		assertThat(update).isInstanceOf(AcpSchema.AgentThoughtChunk.class);
		AcpSchema.AgentThoughtChunk chunk = (AcpSchema.AgentThoughtChunk) update;
		assertThat(chunk.sessionUpdate()).isEqualTo("agent_thought_chunk");
		assertThat(chunk.content()).isInstanceOf(AcpSchema.TextContent.class);
		AcpSchema.TextContent text = (AcpSchema.TextContent) chunk.content();
		assertThat(text.text()).isEqualTo("Let me think about this problem...");
	}

	// ---------------------------
	// ToolCall Tests
	// ---------------------------

	@Test
	void toolCallMinimalDeserialization() throws IOException {
		String json = loadGolden("session-update-tool-call.json");

		AcpSchema.SessionUpdate update = deserializeSessionUpdate(json);

		assertThat(update).isInstanceOf(AcpSchema.ToolCall.class);
		AcpSchema.ToolCall toolCall = (AcpSchema.ToolCall) update;
		assertThat(toolCall.sessionUpdate()).isEqualTo("tool_call");
		assertThat(toolCall.toolCallId()).isEqualTo("call_001");
		assertThat(toolCall.title()).isEqualTo("Reading configuration file");
		assertThat(toolCall.kind()).isEqualTo(AcpSchema.ToolKind.READ);
		assertThat(toolCall.status()).isEqualTo(AcpSchema.ToolCallStatus.PENDING);
	}

	@Test
	void toolCallCompleteDeserialization() throws IOException {
		String json = loadGolden("session-update-tool-call-complete.json");

		AcpSchema.SessionUpdate update = deserializeSessionUpdate(json);

		assertThat(update).isInstanceOf(AcpSchema.ToolCall.class);
		AcpSchema.ToolCall toolCall = (AcpSchema.ToolCall) update;
		assertThat(toolCall.sessionUpdate()).isEqualTo("tool_call");
		assertThat(toolCall.toolCallId()).isEqualTo("call_002");
		assertThat(toolCall.title()).isEqualTo("Editing source file");
		assertThat(toolCall.kind()).isEqualTo(AcpSchema.ToolKind.EDIT);
		assertThat(toolCall.status()).isEqualTo(AcpSchema.ToolCallStatus.COMPLETED);

		// Verify locations
		assertThat(toolCall.locations()).hasSize(1);
		assertThat(toolCall.locations().get(0).path()).isEqualTo("/home/user/project/src/main.java");
		assertThat(toolCall.locations().get(0).line()).isEqualTo(42);

		// Verify rawInput and rawOutput
		assertThat(toolCall.rawInput()).isNotNull();
		assertThat(toolCall.rawOutput()).isNotNull();

		// Verify content
		assertThat(toolCall.content()).hasSize(1);
		assertThat(toolCall.content().get(0)).isInstanceOf(AcpSchema.ToolCallContentBlock.class);
	}

	// ---------------------------
	// ToolCallUpdate Tests
	// ---------------------------

	@Test
	void toolCallUpdateDeserialization() throws IOException {
		String json = loadGolden("session-update-tool-call-update.json");

		AcpSchema.SessionUpdate update = deserializeSessionUpdate(json);

		assertThat(update).isInstanceOf(AcpSchema.ToolCallUpdateNotification.class);
		AcpSchema.ToolCallUpdateNotification toolCallUpdate = (AcpSchema.ToolCallUpdateNotification) update;
		assertThat(toolCallUpdate.sessionUpdate()).isEqualTo("tool_call_update");
		assertThat(toolCallUpdate.toolCallId()).isEqualTo("call_010");
		assertThat(toolCallUpdate.title()).isEqualTo("Processing changes");
		assertThat(toolCallUpdate.kind()).isEqualTo(AcpSchema.ToolKind.EDIT);
		assertThat(toolCallUpdate.status()).isEqualTo(AcpSchema.ToolCallStatus.IN_PROGRESS);

		// Verify locations
		assertThat(toolCallUpdate.locations()).hasSize(1);
		assertThat(toolCallUpdate.locations().get(0).path()).isEqualTo("/home/user/project/src/config.json");

		// Verify content
		assertThat(toolCallUpdate.content()).hasSize(1);
	}

	// ---------------------------
	// Plan Tests
	// ---------------------------

	@Test
	void planDeserialization() throws IOException {
		String json = loadGolden("session-update-plan.json");

		AcpSchema.SessionUpdate update = deserializeSessionUpdate(json);

		assertThat(update).isInstanceOf(AcpSchema.Plan.class);
		AcpSchema.Plan plan = (AcpSchema.Plan) update;
		assertThat(plan.sessionUpdate()).isEqualTo("plan");
		assertThat(plan.entries()).hasSize(3);

		// Verify first entry (high priority, completed)
		AcpSchema.PlanEntry entry1 = plan.entries().get(0);
		assertThat(entry1.content()).isEqualTo("Check for syntax errors");
		assertThat(entry1.priority()).isEqualTo(AcpSchema.PlanEntryPriority.HIGH);
		assertThat(entry1.status()).isEqualTo(AcpSchema.PlanEntryStatus.COMPLETED);

		// Verify second entry (medium priority, in_progress)
		AcpSchema.PlanEntry entry2 = plan.entries().get(1);
		assertThat(entry2.content()).isEqualTo("Identify potential type issues");
		assertThat(entry2.priority()).isEqualTo(AcpSchema.PlanEntryPriority.MEDIUM);
		assertThat(entry2.status()).isEqualTo(AcpSchema.PlanEntryStatus.IN_PROGRESS);

		// Verify third entry (low priority, pending)
		AcpSchema.PlanEntry entry3 = plan.entries().get(2);
		assertThat(entry3.content()).isEqualTo("Run unit tests");
		assertThat(entry3.priority()).isEqualTo(AcpSchema.PlanEntryPriority.LOW);
		assertThat(entry3.status()).isEqualTo(AcpSchema.PlanEntryStatus.PENDING);
	}

	// ---------------------------
	// AvailableCommandsUpdate Tests
	// ---------------------------

	@Test
	void availableCommandsUpdateDeserialization() throws IOException {
		String json = loadGolden("session-update-available-commands.json");

		AcpSchema.SessionUpdate update = deserializeSessionUpdate(json);

		assertThat(update).isInstanceOf(AcpSchema.AvailableCommandsUpdate.class);
		AcpSchema.AvailableCommandsUpdate commandsUpdate = (AcpSchema.AvailableCommandsUpdate) update;
		assertThat(commandsUpdate.sessionUpdate()).isEqualTo("available_commands_update");
		assertThat(commandsUpdate.availableCommands()).hasSize(3);

		// Verify first command (no input hint)
		AcpSchema.AvailableCommand cmd1 = commandsUpdate.availableCommands().get(0);
		assertThat(cmd1.name()).isEqualTo("/help");
		assertThat(cmd1.description()).isEqualTo("Show available commands");
		assertThat(cmd1.input()).isNull();

		// Verify third command (with input hint)
		AcpSchema.AvailableCommand cmd3 = commandsUpdate.availableCommands().get(2);
		assertThat(cmd3.name()).isEqualTo("/review");
		assertThat(cmd3.description()).isEqualTo("Review pending changes");
		assertThat(cmd3.input()).isNotNull();
		assertThat(cmd3.input().hint()).isEqualTo("file path (optional)");
	}

	// ---------------------------
	// CurrentModeUpdate Tests
	// ---------------------------

	@Test
	void currentModeUpdateDeserialization() throws IOException {
		String json = loadGolden("session-update-current-mode.json");

		AcpSchema.SessionUpdate update = deserializeSessionUpdate(json);

		assertThat(update).isInstanceOf(AcpSchema.CurrentModeUpdate.class);
		AcpSchema.CurrentModeUpdate modeUpdate = (AcpSchema.CurrentModeUpdate) update;
		assertThat(modeUpdate.sessionUpdate()).isEqualTo("current_mode_update");
		assertThat(modeUpdate.currentModeId()).isEqualTo("architect");
	}

	// ---------------------------
	// UsageUpdate Tests
	// ---------------------------

	@Test
	void usageUpdateDeserialization() throws IOException {
		String json = loadGolden("session-update-usage-update.json");

		AcpSchema.SessionUpdate update = deserializeSessionUpdate(json);

		assertThat(update).isInstanceOf(AcpSchema.UsageUpdate.class);
		AcpSchema.UsageUpdate usageUpdate = (AcpSchema.UsageUpdate) update;
		assertThat(usageUpdate.sessionUpdate()).isEqualTo("usage_update");
		assertThat(usageUpdate.used()).isEqualTo(53000L);
		assertThat(usageUpdate.size()).isEqualTo(200000L);
		assertThat(usageUpdate.cost()).isNull();
	}

	@Test
	void usageUpdateWithCostDeserialization() throws IOException {
		String json = "{\n  \"sessionUpdate\": \"usage_update\",\n  \"used\": 53000,\n  \"size\": 200000,\n  \"cost\": {\n    \"amount\": 0.42,\n    \"currency\": \"USD\"\n  }\n}\n";

		AcpSchema.SessionUpdate update = deserializeSessionUpdate(json);

		assertThat(update).isInstanceOf(AcpSchema.UsageUpdate.class);
		AcpSchema.UsageUpdate usageUpdate = (AcpSchema.UsageUpdate) update;
		assertThat(usageUpdate.cost()).isNotNull();
		assertThat(usageUpdate.cost().amount()).isEqualTo(0.42);
		assertThat(usageUpdate.cost().currency()).isEqualTo("USD");
	}

	// ---------------------------
	// All Status Values Tests
	// ---------------------------

	@Test
	void toolCallStatusAllValues() throws IOException {
		// Test all ToolCallStatus enum values can be deserialized
		for (AcpSchema.ToolCallStatus status : AcpSchema.ToolCallStatus.values()) {
			String json = String.format(
					"{\"sessionUpdate\":\"tool_call\",\"toolCallId\":\"call_test\",\"title\":\"Test\",\"kind\":\"read\",\"status\":\"%s\"}",
					status.name().toLowerCase());

			AcpSchema.SessionUpdate update = deserializeSessionUpdate(json);
			assertThat(update).isInstanceOf(AcpSchema.ToolCall.class);
			AcpSchema.ToolCall toolCall = (AcpSchema.ToolCall) update;
			assertThat(toolCall.status()).isEqualTo(status);
		}
	}

	@Test
	void toolKindAllValues() throws IOException {
		// Test all ToolKind enum values can be deserialized
		for (AcpSchema.ToolKind kind : AcpSchema.ToolKind.values()) {
			String json = String.format(
					"{\"sessionUpdate\":\"tool_call\",\"toolCallId\":\"call_test\",\"title\":\"Test\",\"kind\":\"%s\",\"status\":\"pending\"}",
					kind.name().toLowerCase());

			AcpSchema.SessionUpdate update = deserializeSessionUpdate(json);
			assertThat(update).isInstanceOf(AcpSchema.ToolCall.class);
			AcpSchema.ToolCall toolCall = (AcpSchema.ToolCall) update;
			assertThat(toolCall.kind()).isEqualTo(kind);
		}
	}

	@Test
	void planEntryPriorityAllValues() throws IOException {
		// Test all PlanEntryPriority enum values can be deserialized
		for (AcpSchema.PlanEntryPriority priority : AcpSchema.PlanEntryPriority.values()) {
			String json = String.format(
					"{\"sessionUpdate\":\"plan\",\"entries\":[{\"content\":\"Test\",\"priority\":\"%s\",\"status\":\"pending\"}]}",
					priority.name().toLowerCase());

			AcpSchema.SessionUpdate update = deserializeSessionUpdate(json);
			assertThat(update).isInstanceOf(AcpSchema.Plan.class);
			AcpSchema.Plan plan = (AcpSchema.Plan) update;
			assertThat(plan.entries().get(0).priority()).isEqualTo(priority);
		}
	}

}
