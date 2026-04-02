/*
 * Copyright 2025-2025 the original author or authors.
 */

package com.agentclientprotocol.sdk.agent;

import java.util.Objects;

/**
 * Result of executing a terminal command via the convenience API.
 *
 * <p>
 * This class wraps the output and exit code from a terminal command execution,
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
public final class CommandResult {

	private final String output;
	private final int exitCode;
	private final boolean timedOut;

	public CommandResult(String output, int exitCode, boolean timedOut) {
		this.output = output;
		this.exitCode = exitCode;
		this.timedOut = timedOut;
	}

	/**
	 * Creates a CommandResult with the given output and exit code.
	 * Sets timedOut to false.
	 * @param output The command output
	 * @param exitCode The exit code
	 */
	public CommandResult(String output, int exitCode) {
		this(output, exitCode, false);
	}

	public String output() {
		return this.output;
	}

	public int exitCode() {
		return this.exitCode;
	}

	public boolean timedOut() {
		return this.timedOut;
	}

	/**
	 * Returns true if the command completed successfully (exit code 0).
	 * @return true if exit code is 0 and command did not time out
	 */
	public boolean success() {
		return exitCode == 0 && !timedOut;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (o == null || getClass() != o.getClass()) return false;
		CommandResult that = (CommandResult) o;
		return exitCode == that.exitCode && timedOut == that.timedOut && Objects.equals(output, that.output);
	}

	@Override
	public int hashCode() {
		return Objects.hash(output, exitCode, timedOut);
	}

	@Override
	public String toString() {
		return "CommandResult[output=" + output + ", exitCode=" + exitCode + ", timedOut=" + timedOut + "]";
	}

}
