/*
 * Copyright 2025-2025 the original author or authors.
 */

package com.agentclientprotocol.sdk.agent;

import java.util.*;

/**
 * Builder for terminal command execution via the convenience API.
 *
 * <p>
 * This class allows configuring command execution with options like
 * working directory, environment variables, and output limits.
 *
 * <p>
 * Example usage:
 * <pre>{@code
 * // Simple command
 * CommandResult result = context.execute("echo", "hello");
 *
 * // Command with options
 * CommandResult result = context.execute(
 *     Command.of("make", "build")
 *         .cwd("/workspace")
 *         .env(Collections.singletonMap("DEBUG", "true"))
 *         .outputByteLimit(10000));
 * }</pre>
 *
 * @author Mark Pollack
 * @since 0.9.2
 * @see SyncPromptContext#execute(Command)
 * @see PromptContext#execute(Command)
 */
public final class Command {

	private final String executable;
	private final List<String> args;
	private final String cwd;
	private final Map<String, String> env;
	private final Long outputByteLimit;

	public Command(String executable, List<String> args, String cwd, Map<String, String> env, Long outputByteLimit) {
		this.executable = executable;
		this.args = args;
		this.cwd = cwd;
		this.env = env;
		this.outputByteLimit = outputByteLimit;
	}

	public String executable() {
		return this.executable;
	}

	public List<String> args() {
		return this.args;
	}

	public String cwd() {
		return this.cwd;
	}

	public Map<String, String> env() {
		return this.env;
	}

	public Long outputByteLimit() {
		return this.outputByteLimit;
	}

	/**
	 * Creates a Command from command-line arguments.
	 * The first argument is the executable, remaining arguments are passed as args.
	 * @param commandAndArgs The command and its arguments
	 * @return A new Command instance
	 */
	public static Command of(String... commandAndArgs) {
		if (commandAndArgs == null || commandAndArgs.length == 0) {
			throw new IllegalArgumentException("At least one argument (the command) is required");
		}
		return new Command(
				commandAndArgs[0],
				commandAndArgs.length > 1
						? Arrays.asList(commandAndArgs).subList(1, commandAndArgs.length)
						: Collections.<String>emptyList(),
				null, null, null);
	}

	/**
	 * Returns a new Command with the specified working directory.
	 * @param cwd The working directory
	 * @return A new Command with the working directory set
	 */
	public Command cwd(String cwd) {
		return new Command(executable, args, cwd, env, outputByteLimit);
	}

	/**
	 * Returns a new Command with the specified environment variables.
	 * @param env The environment variables
	 * @return A new Command with the environment variables set
	 */
	public Command env(Map<String, String> env) {
		return new Command(executable, args, cwd, env, outputByteLimit);
	}

	/**
	 * Returns a new Command with the specified output byte limit.
	 * @param limit The maximum bytes of output to capture
	 * @return A new Command with the output byte limit set
	 */
	public Command outputByteLimit(long limit) {
		return new Command(executable, args, cwd, env, limit);
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (o == null || getClass() != o.getClass()) return false;
		Command that = (Command) o;
		return Objects.equals(executable, that.executable) && Objects.equals(args, that.args)
				&& Objects.equals(cwd, that.cwd) && Objects.equals(env, that.env)
				&& Objects.equals(outputByteLimit, that.outputByteLimit);
	}

	@Override
	public int hashCode() {
		return Objects.hash(executable, args, cwd, env, outputByteLimit);
	}

	@Override
	public String toString() {
		return "Command[executable=" + executable + ", args=" + args + ", cwd=" + cwd
				+ ", env=" + env + ", outputByteLimit=" + outputByteLimit + "]";
	}

}
