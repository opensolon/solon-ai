/*
 * Copyright 2025-2025 the original author or authors.
 */

package com.agentclientprotocol.sdk.capabilities;

import com.agentclientprotocol.sdk.error.AcpCapabilityException;
import com.agentclientprotocol.sdk.error.AcpProtocolException;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for {@link NegotiatedCapabilities}.
 */
class NegotiatedCapabilitiesTest {

	// -------------------------
	// Client Capabilities Tests
	// -------------------------

	@Test
	void fromClientWithNullReturnsAllFalse() {
		NegotiatedCapabilities caps = NegotiatedCapabilities.fromClient(null);

		assertThat(caps.supportsReadTextFile()).isFalse();
		assertThat(caps.supportsWriteTextFile()).isFalse();
		assertThat(caps.supportsTerminal()).isFalse();
	}

	@Test
	void fromClientWithDefaultCapabilitiesReturnsAllFalse() {
		AcpSchema.ClientCapabilities clientCaps = new AcpSchema.ClientCapabilities();

		NegotiatedCapabilities caps = NegotiatedCapabilities.fromClient(clientCaps);

		assertThat(caps.supportsReadTextFile()).isFalse();
		assertThat(caps.supportsWriteTextFile()).isFalse();
		assertThat(caps.supportsTerminal()).isFalse();
	}

	@Test
	void fromClientWithAllCapabilitiesEnabled() {
		AcpSchema.FileSystemCapability fs = new AcpSchema.FileSystemCapability(true, true);
		AcpSchema.ClientCapabilities clientCaps = new AcpSchema.ClientCapabilities(fs, true);

		NegotiatedCapabilities caps = NegotiatedCapabilities.fromClient(clientCaps);

		assertThat(caps.supportsReadTextFile()).isTrue();
		assertThat(caps.supportsWriteTextFile()).isTrue();
		assertThat(caps.supportsTerminal()).isTrue();
	}

	@Test
	void fromClientWithPartialCapabilities() {
		AcpSchema.FileSystemCapability fs = new AcpSchema.FileSystemCapability(true, false);
		AcpSchema.ClientCapabilities clientCaps = new AcpSchema.ClientCapabilities(fs, false);

		NegotiatedCapabilities caps = NegotiatedCapabilities.fromClient(clientCaps);

		assertThat(caps.supportsReadTextFile()).isTrue();
		assertThat(caps.supportsWriteTextFile()).isFalse();
		assertThat(caps.supportsTerminal()).isFalse();
	}

	@Test
	void requireReadTextFileThrowsWhenNotSupported() {
		NegotiatedCapabilities caps = NegotiatedCapabilities.fromClient(null);

		assertThatThrownBy(caps::requireReadTextFile).isInstanceOf(AcpCapabilityException.class)
			.hasMessageContaining("fs.readTextFile");
	}

	@Test
	void requireReadTextFileDoesNotThrowWhenSupported() {
		AcpSchema.FileSystemCapability fs = new AcpSchema.FileSystemCapability(true, false);
		AcpSchema.ClientCapabilities clientCaps = new AcpSchema.ClientCapabilities(fs, false);
		NegotiatedCapabilities caps = NegotiatedCapabilities.fromClient(clientCaps);

		caps.requireReadTextFile(); // Should not throw
	}

	@Test
	void requireWriteTextFileThrowsWhenNotSupported() {
		NegotiatedCapabilities caps = NegotiatedCapabilities.fromClient(null);

		assertThatThrownBy(caps::requireWriteTextFile).isInstanceOf(AcpCapabilityException.class)
			.hasMessageContaining("fs.writeTextFile");
	}

	@Test
	void requireTerminalThrowsWhenNotSupported() {
		NegotiatedCapabilities caps = NegotiatedCapabilities.fromClient(null);

		assertThatThrownBy(caps::requireTerminal).isInstanceOf(AcpCapabilityException.class)
			.hasMessageContaining("terminal");
	}

	// -------------------------
	// Agent Capabilities Tests
	// -------------------------

	@Test
	void fromAgentWithNullReturnsAllFalse() {
		NegotiatedCapabilities caps = NegotiatedCapabilities.fromAgent(null);

		assertThat(caps.supportsLoadSession()).isFalse();
		assertThat(caps.supportsImageContent()).isFalse();
		assertThat(caps.supportsAudioContent()).isFalse();
		assertThat(caps.supportsEmbeddedContext()).isFalse();
		assertThat(caps.supportsMcpHttp()).isFalse();
		assertThat(caps.supportsMcpSse()).isFalse();
	}

	@Test
	void fromAgentWithDefaultCapabilitiesReturnsAllFalse() {
		AcpSchema.AgentCapabilities agentCaps = new AcpSchema.AgentCapabilities();

		NegotiatedCapabilities caps = NegotiatedCapabilities.fromAgent(agentCaps);

		assertThat(caps.supportsLoadSession()).isFalse();
		assertThat(caps.supportsImageContent()).isFalse();
		assertThat(caps.supportsAudioContent()).isFalse();
		assertThat(caps.supportsEmbeddedContext()).isFalse();
		assertThat(caps.supportsMcpHttp()).isFalse();
		assertThat(caps.supportsMcpSse()).isFalse();
	}

	@Test
	void fromAgentWithAllCapabilitiesEnabled() {
		AcpSchema.PromptCapabilities prompt = new AcpSchema.PromptCapabilities(true, true, true);
		AcpSchema.McpCapabilities mcp = new AcpSchema.McpCapabilities(true, true);
		AcpSchema.AgentCapabilities agentCaps = new AcpSchema.AgentCapabilities(true, mcp, prompt);

		NegotiatedCapabilities caps = NegotiatedCapabilities.fromAgent(agentCaps);

		assertThat(caps.supportsLoadSession()).isTrue();
		assertThat(caps.supportsImageContent()).isTrue();
		assertThat(caps.supportsAudioContent()).isTrue();
		assertThat(caps.supportsEmbeddedContext()).isTrue();
		assertThat(caps.supportsMcpHttp()).isTrue();
		assertThat(caps.supportsMcpSse()).isTrue();
	}

	@Test
	void fromAgentWithPartialCapabilities() {
		AcpSchema.PromptCapabilities prompt = new AcpSchema.PromptCapabilities(false, false, true);
		AcpSchema.McpCapabilities mcp = new AcpSchema.McpCapabilities(true, false);
		AcpSchema.AgentCapabilities agentCaps = new AcpSchema.AgentCapabilities(true, mcp, prompt);

		NegotiatedCapabilities caps = NegotiatedCapabilities.fromAgent(agentCaps);

		assertThat(caps.supportsLoadSession()).isTrue();
		assertThat(caps.supportsImageContent()).isTrue();
		assertThat(caps.supportsAudioContent()).isFalse();
		assertThat(caps.supportsEmbeddedContext()).isFalse();
		assertThat(caps.supportsMcpHttp()).isTrue();
		assertThat(caps.supportsMcpSse()).isFalse();
	}

	@Test
	void requireLoadSessionThrowsWhenNotSupported() {
		NegotiatedCapabilities caps = NegotiatedCapabilities.fromAgent(null);

		assertThatThrownBy(caps::requireLoadSession).isInstanceOf(AcpCapabilityException.class)
			.hasMessageContaining("loadSession");
	}

	@Test
	void requireImageContentThrowsWhenNotSupported() {
		NegotiatedCapabilities caps = NegotiatedCapabilities.fromAgent(null);

		assertThatThrownBy(caps::requireImageContent).isInstanceOf(AcpCapabilityException.class)
			.hasMessageContaining("promptCapabilities.image");
	}

	@Test
	void requireAudioContentThrowsWhenNotSupported() {
		NegotiatedCapabilities caps = NegotiatedCapabilities.fromAgent(null);

		assertThatThrownBy(caps::requireAudioContent).isInstanceOf(AcpCapabilityException.class)
			.hasMessageContaining("promptCapabilities.audio");
	}

	// -------------------------
	// Builder Tests
	// -------------------------

	@Test
	void builderSetsAllValues() {
		NegotiatedCapabilities caps = new NegotiatedCapabilities.Builder().readTextFile(true)
			.writeTextFile(true)
			.terminal(true)
			.loadSession(true)
			.imageContent(true)
			.audioContent(true)
			.embeddedContext(true)
			.mcpHttp(true)
			.mcpSse(true)
			.build();

		assertThat(caps.supportsReadTextFile()).isTrue();
		assertThat(caps.supportsWriteTextFile()).isTrue();
		assertThat(caps.supportsTerminal()).isTrue();
		assertThat(caps.supportsLoadSession()).isTrue();
		assertThat(caps.supportsImageContent()).isTrue();
		assertThat(caps.supportsAudioContent()).isTrue();
		assertThat(caps.supportsEmbeddedContext()).isTrue();
		assertThat(caps.supportsMcpHttp()).isTrue();
		assertThat(caps.supportsMcpSse()).isTrue();
	}

	@Test
	void toStringContainsAllCapabilities() {
		NegotiatedCapabilities caps = new NegotiatedCapabilities.Builder().readTextFile(true)
			.terminal(true)
			.build();

		String str = caps.toString();
		assertThat(str).contains("readTextFile=true");
		assertThat(str).contains("terminal=true");
		assertThat(str).contains("writeTextFile=false");
	}

	// -------------------------
	// AcpCapabilityException Tests
	// -------------------------

	@Test
	void capabilityExceptionContainsCapabilityName() {
		AcpCapabilityException exception = new AcpCapabilityException("fs.readTextFile");

		assertThat(exception.getCapability()).isEqualTo("fs.readTextFile");
		assertThat(exception.getMessage()).contains("fs.readTextFile");
	}

	@Test
	void capabilityExceptionConvertsToProtocolException() {
		AcpCapabilityException exception = new AcpCapabilityException("terminal");

		AcpProtocolException protocolException = exception.toProtocolException();

		assertThat(protocolException.getCode()).isEqualTo(-32001);
		assertThat(protocolException.getData()).isEqualTo("terminal");
	}

}
