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
import com.agentclientprotocol.sdk.spec.AcpSchema.ElicitationCapabilities;
import com.agentclientprotocol.sdk.spec.AcpSchema.SessionCapabilities;

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

	// Client elicitation capabilities
	private final boolean elicitation;

	private final boolean elicitationForm;

	private final boolean elicitationUrl;

	// Agent capabilities (what agent offers to client)
	private final boolean loadSession;

	private final boolean listSessions;

	private final boolean closeSession;

	private final boolean resumeSession;

	private final boolean deleteSession;

	private final boolean additionalDirectories;

	private final boolean forkSession;

	private final boolean providers;

	private final boolean imageContent;

	private final boolean audioContent;

	private final boolean embeddedContext;

	private final boolean mcpHttp;

	private final boolean mcpSse;

	private NegotiatedCapabilities(Builder builder) {
		this.readTextFile = builder.readTextFile;
		this.writeTextFile = builder.writeTextFile;
		this.terminal = builder.terminal;
		this.elicitation = builder.elicitation;
		this.elicitationForm = builder.elicitationForm;
		this.elicitationUrl = builder.elicitationUrl;
		this.loadSession = builder.loadSession;
		this.listSessions = builder.listSessions;
		this.closeSession = builder.closeSession;
		this.resumeSession = builder.resumeSession;
		this.deleteSession = builder.deleteSession;
		this.additionalDirectories = builder.additionalDirectories;
		this.forkSession = builder.forkSession;
		this.providers = builder.providers;
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

		ElicitationCapabilities elicit = caps.elicitation();
		if (elicit != null) {
			builder.elicitation(true);
			builder.elicitationForm(elicit.form() != null);
			builder.elicitationUrl(elicit.url() != null);
		}

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

		SessionCapabilities sc = caps.sessionCapabilities();
		if (sc != null) {
			builder.listSessions(sc.list() != null);
			builder.closeSession(sc.close() != null);
			builder.resumeSession(sc.resume() != null);
			builder.deleteSession(sc.delete() != null);
			builder.additionalDirectories(sc.additionalDirectories() != null);
			builder.forkSession(sc.fork() != null);
		}

		builder.providers(caps.providers() != null);

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

	/**
	 * Returns true if the client supports elicitation (any mode).
	 * @return true if elicitation capability was advertised
	 */
	public boolean supportsElicitation() {
		return elicitation;
	}

	/**
	 * Returns true if the client supports form-based elicitation.
	 * @return true if elicitation.form capability was advertised
	 */
	public boolean supportsElicitationForm() {
		return elicitationForm;
	}

	/**
	 * Returns true if the client supports URL-based elicitation.
	 * @return true if elicitation.url capability was advertised
	 */
	public boolean supportsElicitationUrl() {
		return elicitationUrl;
	}

	/**
	 * Requires elicitation capability, throwing if not supported.
	 * @throws AcpCapabilityException if the client doesn't support elicitation
	 */
	public void requireElicitation() {
		if (!elicitation) {
			throw new AcpCapabilityException("elicitation");
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
	 * Returns true if the agent supports listing sessions.
	 * @return true if sessionCapabilities.list was advertised
	 */
	public boolean supportsListSessions() {
		return listSessions;
	}

	/**
	 * Returns true if the agent supports closing sessions.
	 * @return true if sessionCapabilities.close was advertised
	 */
	public boolean supportsCloseSession() {
		return closeSession;
	}

	/**
	 * Returns true if the agent supports resuming sessions.
	 * @return true if sessionCapabilities.resume was advertised
	 */
	public boolean supportsResumeSession() {
		return resumeSession;
	}

	/**
	 * Returns true if the agent supports deleting sessions.
	 * @return true if sessionCapabilities.delete was advertised
	 */
	public boolean supportsDeleteSession() {
		return deleteSession;
	}

	/**
	 * Returns true if the agent supports additional workspace directories on session
	 * lifecycle requests (session/new, session/load, session/resume).
	 * @return true if sessionCapabilities.additionalDirectories was advertised
	 */
	public boolean supportsAdditionalDirectories() {
		return additionalDirectories;
	}

	/**
	 * Returns true if the agent supports forking sessions.
	 * @return true if sessionCapabilities.fork was advertised
	 */
	public boolean supportsForkSession() {
		return forkSession;
	}

	/**
	 * Returns true if the agent supports the {@code providers/*} configuration methods.
	 * @return true if {@code providers} capability was advertised
	 */
	public boolean supportsProviders() {
		return providers;
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
	 * Requires list sessions capability, throwing if not supported.
	 * @throws AcpCapabilityException if the agent doesn't support this capability
	 */
	public void requireListSessions() {
		if (!listSessions) {
			throw new AcpCapabilityException("sessionCapabilities.list");
		}
	}

	/**
	 * Requires close session capability, throwing if not supported.
	 * @throws AcpCapabilityException if the agent doesn't support this capability
	 */
	public void requireCloseSession() {
		if (!closeSession) {
			throw new AcpCapabilityException("sessionCapabilities.close");
		}
	}

	/**
	 * Requires resume session capability, throwing if not supported.
	 * @throws AcpCapabilityException if the agent doesn't support this capability
	 */
	public void requireResumeSession() {
		if (!resumeSession) {
			throw new AcpCapabilityException("sessionCapabilities.resume");
		}
	}

	/**
	 * Requires delete session capability, throwing if not supported.
	 * @throws AcpCapabilityException if the agent doesn't support this capability
	 */
	public void requireDeleteSession() {
		if (!deleteSession) {
			throw new AcpCapabilityException("sessionCapabilities.delete");
		}
	}

	/**
	 * Requires additional directories capability, throwing if not supported.
	 * @throws AcpCapabilityException if the agent doesn't support this capability
	 */
	public void requireAdditionalDirectories() {
		if (!additionalDirectories) {
			throw new AcpCapabilityException("sessionCapabilities.additionalDirectories");
		}
	}

	public void requireForkSession() {
		if (!forkSession) {
			throw new AcpCapabilityException("sessionCapabilities.fork");
		}
	}

	/**
	 * Requires provider configuration capability, throwing if not supported.
	 * @throws AcpCapabilityException if the agent doesn't support the {@code providers/*} methods
	 */
	public void requireProviders() {
		if (!providers) {
			throw new AcpCapabilityException("providers");
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
				+ ", terminal=" + terminal + ", elicitation=" + elicitation + ", elicitationForm="
				+ elicitationForm + ", elicitationUrl=" + elicitationUrl + ", loadSession=" + loadSession
				+ ", listSessions=" + listSessions
				+ ", closeSession=" + closeSession + ", resumeSession=" + resumeSession + ", deleteSession="
				+ deleteSession + ", additionalDirectories=" + additionalDirectories + ", forkSession="
				+ forkSession + ", providers=" + providers + ", imageContent="
				+ imageContent + ", audioContent=" + audioContent + ", embeddedContext=" + embeddedContext
				+ ", mcpHttp=" + mcpHttp + ", mcpSse=" + mcpSse + '}';
	}

	/**
	 * Builder for NegotiatedCapabilities.
	 */
	public static class Builder {

		private boolean readTextFile = false;

		private boolean writeTextFile = false;

		private boolean terminal = false;

		private boolean elicitation = false;

		private boolean elicitationForm = false;

		private boolean elicitationUrl = false;

		private boolean loadSession = false;

		private boolean listSessions = false;

		private boolean closeSession = false;

		private boolean resumeSession = false;

		private boolean deleteSession = false;

		private boolean additionalDirectories = false;

		private boolean forkSession = false;

		private boolean providers = false;

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

		public Builder elicitation(boolean value) {
			this.elicitation = value;
			return this;
		}

		public Builder elicitationForm(boolean value) {
			this.elicitationForm = value;
			return this;
		}

		public Builder elicitationUrl(boolean value) {
			this.elicitationUrl = value;
			return this;
		}

		public Builder loadSession(boolean value) {
			this.loadSession = value;
			return this;
		}

		public Builder listSessions(boolean value) {
			this.listSessions = value;
			return this;
		}

		public Builder closeSession(boolean value) {
			this.closeSession = value;
			return this;
		}

		public Builder resumeSession(boolean value) {
			this.resumeSession = value;
			return this;
		}

		public Builder deleteSession(boolean value) {
			this.deleteSession = value;
			return this;
		}

		public Builder additionalDirectories(boolean value) {
			this.additionalDirectories = value;
			return this;
		}

		public Builder forkSession(boolean value) {
			this.forkSession = value;
			return this;
		}

		public Builder providers(boolean value) {
			this.providers = value;
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
