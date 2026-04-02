/*
 * Copyright 2025-2025 the original author or authors.
 */

package com.agentclientprotocol.sdk.agent;

import lombok.AllArgsConstructor;

/**
 * Result of executing a terminal command via the convenience API.
 *
 * <p>
 * This record wraps the output and exit code from a terminal command execution,
 * providing a clean interface for the common case of running a command and
 * checking its result.
 *
 * <p>
 * Example usage:
 * <pre>{@code
 * CommandResult result = context.execute("make", "build");
 * if (result.exitCode() == 0) {
 *     context.sendMessage("Build succeeded!");
 * } else {
 *     context.sendMessage("Build failed: " + result.output());
 * }
 * }</pre>
 *
 * @author Mark Pollack
 * @since 0.9.2
 * @see SyncPromptContext#execute(String...)
 * @see PromptContext#execute(String...)
 */
@AllArgsConstructor
public class CommandResult {
	String output;
	int exitCode;
	boolean timedOut;

	/**
	 * Creates a CommandResult with the given output and exit code.
	 * Sets timedOut to false.
	 * @param output The command output
	 * @param exitCode The exit code
	 */
	public CommandResult(String output, int exitCode) {
		this(output, exitCode, false);
	}

	/**
	 * Returns true if the command completed successfully (exit code 0).
	 * @return true if exit code is 0 and command did not time out
	 */
	public boolean success() {
		return exitCode == 0 && !timedOut;
	}

	public String output() {
		return output;
	}

	public int exitCode() {
		return exitCode;
	}

	public boolean timedOut() {
		return timedOut;
	}

}
