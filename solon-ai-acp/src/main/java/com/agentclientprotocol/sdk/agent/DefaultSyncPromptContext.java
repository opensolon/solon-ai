/*
 * Copyright 2025-2025 the original author or authors.
 */

package com.agentclientprotocol.sdk.agent;

import java.util.Optional;

import com.agentclientprotocol.sdk.capabilities.NegotiatedCapabilities;
import com.agentclientprotocol.sdk.spec.AcpSchema;

/**
 * Default implementation of {@link SyncPromptContext} that wraps an async {@link PromptContext}
 * and provides blocking methods.
 *
 * <p>
 * This class is created internally by the sync-to-async handler converter in {@link AcpAgent.SyncAgentBuilder}.
 *
 * @author Mark Pollack
 * @since 0.9.1
 */
class DefaultSyncPromptContext implements SyncPromptContext {

	private final PromptContext asyncContext;

	/**
	 * Creates a new sync context wrapping the given async context.
	 * @param asyncContext The async context to wrap
	 */
	DefaultSyncPromptContext(PromptContext asyncContext) {
		this.asyncContext = asyncContext;
	}

	// ========================================================================
	// Low-Level API
	// ========================================================================

	@Override
	public void sendUpdate(String sessionId, AcpSchema.SessionUpdate update) {
		asyncContext.sendUpdate(sessionId, update).block();
	}

	@Override
	public AcpSchema.ReadTextFileResponse readTextFile(AcpSchema.ReadTextFileRequest request) {
		return asyncContext.readTextFile(request).block();
	}

	@Override
	public AcpSchema.WriteTextFileResponse writeTextFile(AcpSchema.WriteTextFileRequest request) {
		return asyncContext.writeTextFile(request).block();
	}

	@Override
	public AcpSchema.RequestPermissionResponse requestPermission(AcpSchema.RequestPermissionRequest request) {
		return asyncContext.requestPermission(request).block();
	}

	@Override
	public AcpSchema.CreateTerminalResponse createTerminal(AcpSchema.CreateTerminalRequest request) {
		return asyncContext.createTerminal(request).block();
	}

	@Override
	public AcpSchema.TerminalOutputResponse getTerminalOutput(AcpSchema.TerminalOutputRequest request) {
		return asyncContext.getTerminalOutput(request).block();
	}

	@Override
	public AcpSchema.ReleaseTerminalResponse releaseTerminal(AcpSchema.ReleaseTerminalRequest request) {
		return asyncContext.releaseTerminal(request).block();
	}

	@Override
	public AcpSchema.WaitForTerminalExitResponse waitForTerminalExit(AcpSchema.WaitForTerminalExitRequest request) {
		return asyncContext.waitForTerminalExit(request).block();
	}

	@Override
	public AcpSchema.KillTerminalCommandResponse killTerminal(AcpSchema.KillTerminalCommandRequest request) {
		return asyncContext.killTerminal(request).block();
	}

	@Override
	public NegotiatedCapabilities getClientCapabilities() {
		return asyncContext.getClientCapabilities();
	}

	// ========================================================================
	// Convenience API
	// ========================================================================

	@Override
	public String getSessionId() {
		return asyncContext.getSessionId();
	}

	@Override
	public void sendMessage(String text) {
		asyncContext.sendMessage(text).block();
	}

	@Override
	public void sendThought(String text) {
		asyncContext.sendThought(text).block();
	}

	@Override
	public String readFile(String path) {
		return asyncContext.readFile(path).block();
	}

	@Override
	public String readFile(String path, Integer startLine, Integer lineCount) {
		return asyncContext.readFile(path, startLine, lineCount).block();
	}

	@Override
	public Optional<String> tryReadFile(String path) {
		try {
			String content = readFile(path);
			return Optional.ofNullable(content);
		}
		catch (Exception e) {
			return Optional.empty();
		}
	}

	@Override
	public void writeFile(String path, String content) {
		asyncContext.writeFile(path, content).block();
	}

	@Override
	public boolean askPermission(String action) {
		Boolean result = asyncContext.askPermission(action).block();
		return result != null && result;
	}

	@Override
	public String askChoice(String question, String... options) {
		return asyncContext.askChoice(question, options).block();
	}

	@Override
	public CommandResult execute(String... commandAndArgs) {
		return asyncContext.execute(commandAndArgs).block();
	}

	@Override
	public CommandResult execute(Command command) {
		return asyncContext.execute(command).block();
	}

}
