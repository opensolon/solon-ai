/*
 * Copyright 2025-2025 the original author or authors.
 */

package com.agentclientprotocol.sdk.capabilities;

import com.agentclientprotocol.sdk.error.AcpCapabilityException;
import com.agentclientprotocol.sdk.spec.AcpSchema.AgentCapabilities;
import com.agentclientprotocol.sdk.spec.AcpSchema.ClientCapabilities;
import com.agentclientprotocol.sdk.spec.AcpSchema.FileSystemCapability;
import com.agentclientprotocol.sdk.spec.AcpSchema.McpCapabilities;
import com.agentclientprotocol.sdk.spec.AcpSchema.PromptCapabilities;

/**
 * Tracks the capabilities negotiated during the ACP initialization handshake.
 *
 * <p>
 * After the {@code initialize} exchange, both client and agent know what features
 * the other side supports. This class provides convenient methods to check capabilities
 * and validate that operations are supported before attempting them.
 * </p>
 *
 * <p>
 * Example usage for an agent checking client capabilities:
 * <pre>{@code
 * NegotiatedCapabilities caps = NegotiatedCapabilities.fromClient(clientCapabilities);
 *
 * // Check before calling fs/read_text_file
 * if (caps.supportsReadTextFile()) {
 *     agent.readTextFile(request);
 * }
 *
 * // Or require the capability (throws if not supported)
 * caps.requireReadTextFile();
 * agent.readTextFile(request);
 * }</pre>
 *
 * <p>
 * Example usage for a client checking agent capabilities:
 * <pre>{@code
 * NegotiatedCapabilities caps = NegotiatedCapabilities.fromAgent(agentCapabilities);
 *
 * // Check if agent supports image content
 * if (caps.supportsImageContent()) {
 *     prompt.add(imageContent);
 * }
 * }</pre>
 *
 * @author Mark Pollack
 * @see ClientCapabilities
 * @see AgentCapabilities
 */
public final class NegotiatedCapabilities {

	// Client capabilities (what client offers to agent)
	private final boolean readTextFile;

	private final boolean writeTextFile;

	private final boolean terminal;

	// Agent capabilities (what agent offers to client)
	private final boolean loadSession;

	private final boolean imageContent;

	private final boolean audioContent;

	private final boolean embeddedContext;

	private final boolean mcpHttp;

	private final boolean mcpSse;

	private NegotiatedCapabilities(Builder builder) {
		this.readTextFile = builder.readTextFile;
		this.writeTextFile = builder.writeTextFile;
		this.terminal = builder.terminal;
		this.loadSession = builder.loadSession;
		this.imageContent = builder.imageContent;
		this.audioContent = builder.audioContent;
		this.embeddedContext = builder.embeddedContext;
		this.mcpHttp = builder.mcpHttp;
		this.mcpSse = builder.mcpSse;
	}

	/**
	 * Creates negotiated capabilities from client capabilities (used by agents).
	 * @param caps the client's advertised capabilities, may be null
	 * @return negotiated capabilities based on client
	 */
	public static NegotiatedCapabilities fromClient(ClientCapabilities caps) {
		if (caps == null) {
			return new Builder().build();
		}

		Builder builder = new Builder();

		FileSystemCapability fs = caps.fs();
		if (fs != null) {
			builder.readTextFile(Boolean.TRUE.equals(fs.readTextFile()));
			builder.writeTextFile(Boolean.TRUE.equals(fs.writeTextFile()));
		}

		builder.terminal(Boolean.TRUE.equals(caps.terminal()));

		return builder.build();
	}

	/**
	 * Creates negotiated capabilities from agent capabilities (used by clients).
	 * @param caps the agent's advertised capabilities, may be null
	 * @return negotiated capabilities based on agent
	 */
	public static NegotiatedCapabilities fromAgent(AgentCapabilities caps) {
		if (caps == null) {
			return new Builder().build();
		}

		Builder builder = new Builder();
		builder.loadSession(Boolean.TRUE.equals(caps.loadSession()));

		PromptCapabilities prompt = caps.promptCapabilities();
		if (prompt != null) {
			builder.imageContent(Boolean.TRUE.equals(prompt.image()));
			builder.audioContent(Boolean.TRUE.equals(prompt.audio()));
			builder.embeddedContext(Boolean.TRUE.equals(prompt.embeddedContext()));
		}

		McpCapabilities mcp = caps.mcpCapabilities();
		if (mcp != null) {
			builder.mcpHttp(Boolean.TRUE.equals(mcp.http()));
			builder.mcpSse(Boolean.TRUE.equals(mcp.sse()));
		}

		return builder.build();
	}

	// --------------------------
	// Client Capability Checks (for agents)
	// --------------------------

	/**
	 * Returns true if the client supports reading text files.
	 * @return true if fs.readTextFile capability was advertised
	 */
	public boolean supportsReadTextFile() {
		return readTextFile;
	}

	/**
	 * Returns true if the client supports writing text files.
	 * @return true if fs.writeTextFile capability was advertised
	 */
	public boolean supportsWriteTextFile() {
		return writeTextFile;
	}

	/**
	 * Returns true if the client supports terminal operations.
	 * @return true if terminal capability was advertised
	 */
	public boolean supportsTerminal() {
		return terminal;
	}

	/**
	 * Requires read text file capability, throwing if not supported.
	 * @throws AcpCapabilityException if the client doesn't support this capability
	 */
	public void requireReadTextFile() {
		if (!readTextFile) {
			throw new AcpCapabilityException("fs.readTextFile");
		}
	}

	/**
	 * Requires write text file capability, throwing if not supported.
	 * @throws AcpCapabilityException if the client doesn't support this capability
	 */
	public void requireWriteTextFile() {
		if (!writeTextFile) {
			throw new AcpCapabilityException("fs.writeTextFile");
		}
	}

	/**
	 * Requires terminal capability, throwing if not supported.
	 * @throws AcpCapabilityException if the client doesn't support this capability
	 */
	public void requireTerminal() {
		if (!terminal) {
			throw new AcpCapabilityException("terminal");
		}
	}

	// --------------------------
	// Agent Capability Checks (for clients)
	// --------------------------

	/**
	 * Returns true if the agent supports loading sessions.
	 * @return true if loadSession capability was advertised
	 */
	public boolean supportsLoadSession() {
		return loadSession;
	}

	/**
	 * Returns true if the agent supports image content in prompts.
	 * @return true if promptCapabilities.image was advertised
	 */
	public boolean supportsImageContent() {
		return imageContent;
	}

	/**
	 * Returns true if the agent supports audio content in prompts.
	 * @return true if promptCapabilities.audio was advertised
	 */
	public boolean supportsAudioContent() {
		return audioContent;
	}

	/**
	 * Returns true if the agent supports embedded context (resources) in prompts.
	 * @return true if promptCapabilities.embeddedContext was advertised
	 */
	public boolean supportsEmbeddedContext() {
		return embeddedContext;
	}

	/**
	 * Returns true if the agent supports MCP servers via HTTP.
	 * @return true if mcpCapabilities.http was advertised
	 */
	public boolean supportsMcpHttp() {
		return mcpHttp;
	}

	/**
	 * Returns true if the agent supports MCP servers via SSE.
	 * @return true if mcpCapabilities.sse was advertised
	 */
	public boolean supportsMcpSse() {
		return mcpSse;
	}

	/**
	 * Requires load session capability, throwing if not supported.
	 * @throws AcpCapabilityException if the agent doesn't support this capability
	 */
	public void requireLoadSession() {
		if (!loadSession) {
			throw new AcpCapabilityException("loadSession");
		}
	}

	/**
	 * Requires image content capability, throwing if not supported.
	 * @throws AcpCapabilityException if the agent doesn't support this capability
	 */
	public void requireImageContent() {
		if (!imageContent) {
			throw new AcpCapabilityException("promptCapabilities.image");
		}
	}

	/**
	 * Requires audio content capability, throwing if not supported.
	 * @throws AcpCapabilityException if the agent doesn't support this capability
	 */
	public void requireAudioContent() {
		if (!audioContent) {
			throw new AcpCapabilityException("promptCapabilities.audio");
		}
	}

	@Override
	public String toString() {
		return "NegotiatedCapabilities{" + "readTextFile=" + readTextFile + ", writeTextFile=" + writeTextFile
				+ ", terminal=" + terminal + ", loadSession=" + loadSession + ", imageContent=" + imageContent
				+ ", audioContent=" + audioContent + ", embeddedContext=" + embeddedContext + ", mcpHttp=" + mcpHttp
				+ ", mcpSse=" + mcpSse + '}';
	}

	/**
	 * Builder for NegotiatedCapabilities.
	 */
	public static class Builder {

		private boolean readTextFile = false;

		private boolean writeTextFile = false;

		private boolean terminal = false;

		private boolean loadSession = false;

		private boolean imageContent = false;

		private boolean audioContent = false;

		private boolean embeddedContext = false;

		private boolean mcpHttp = false;

		private boolean mcpSse = false;

		public Builder readTextFile(boolean value) {
			this.readTextFile = value;
			return this;
		}

		public Builder writeTextFile(boolean value) {
			this.writeTextFile = value;
			return this;
		}

		public Builder terminal(boolean value) {
			this.terminal = value;
			return this;
		}

		public Builder loadSession(boolean value) {
			this.loadSession = value;
			return this;
		}

		public Builder imageContent(boolean value) {
			this.imageContent = value;
			return this;
		}

		public Builder audioContent(boolean value) {
			this.audioContent = value;
			return this;
		}

		public Builder embeddedContext(boolean value) {
			this.embeddedContext = value;
			return this;
		}

		public Builder mcpHttp(boolean value) {
			this.mcpHttp = value;
			return this;
		}

		public Builder mcpSse(boolean value) {
			this.mcpSse = value;
			return this;
		}

		public NegotiatedCapabilities build() {
			return new NegotiatedCapabilities(this);
		}

	}

}
