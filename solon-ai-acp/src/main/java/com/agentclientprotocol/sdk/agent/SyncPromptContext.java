/*
 * Copyright 2025-2025 the original author or authors.
 */

package com.agentclientprotocol.sdk.agent;

import java.util.Optional;

import com.agentclientprotocol.sdk.capabilities.NegotiatedCapabilities;
import com.agentclientprotocol.sdk.spec.AcpSchema;

/**
 * Synchronous context provided to prompt handlers for accessing agent capabilities.
 *
 * <p>
 * This is the synchronous equivalent of {@link PromptContext}, providing blocking
 * methods for use with {@link AcpAgent.SyncPromptHandler}.
 *
 * <p>
 * Example usage:
 * <pre>{@code
 * AcpAgent.sync(transport)
 *     .promptHandler((request, context) -> {
 *         // Send an update (blocks until sent)
 *         context.sendUpdate(sessionId, update);
 *
 *         // Read a file (blocks until complete)
 *         var content = context.readTextFile(new ReadTextFileRequest(...));
 *
 *         // Request permission (blocks until user responds)
 *         var permission = context.requestPermission(new RequestPermissionRequest(...));
 *
 *         return new PromptResponse(StopReason.END_TURN);
 *     })
 *     .build();
 * }</pre>
 *
 * @author Mark Pollack
 * @since 0.9.1
 * @see PromptContext
 * @see AcpAgent.SyncPromptHandler
 */
public interface SyncPromptContext {

	// ========================================================================
	// Session Updates
	// ========================================================================

	/**
	 * Sends a session update notification to the client.
	 * Blocks until the notification is sent.
	 * @param sessionId The session ID
	 * @param update The session update to send
	 */
	void sendUpdate(String sessionId, AcpSchema.SessionUpdate update);

	// ========================================================================
	// File System Operations
	// ========================================================================

	/**
	 * Requests the client to read a text file.
	 * Blocks until the response is received.
	 * @param request The read file request
	 * @return The file content
	 * @throws com.agentclientprotocol.sdk.error.AcpCapabilityException if client doesn't support file reading
	 */
	AcpSchema.ReadTextFileResponse readTextFile(AcpSchema.ReadTextFileRequest request);

	/**
	 * Requests the client to write a text file.
	 * Blocks until the write is complete.
	 * @param request The write file request
	 * @return The write response
	 * @throws com.agentclientprotocol.sdk.error.AcpCapabilityException if client doesn't support file writing
	 */
	AcpSchema.WriteTextFileResponse writeTextFile(AcpSchema.WriteTextFileRequest request);

	// ========================================================================
	// Permission Requests
	// ========================================================================

	/**
	 * Requests permission from the client for a sensitive operation.
	 * Blocks until the user responds.
	 * @param request The permission request
	 * @return The permission response
	 */
	AcpSchema.RequestPermissionResponse requestPermission(AcpSchema.RequestPermissionRequest request);

	// ========================================================================
	// Terminal Operations
	// ========================================================================

	/**
	 * Requests the client to create a terminal.
	 * Blocks until the terminal is created.
	 * @param request The create terminal request
	 * @return The terminal ID response
	 * @throws com.agentclientprotocol.sdk.error.AcpCapabilityException if client doesn't support terminals
	 */
	AcpSchema.CreateTerminalResponse createTerminal(AcpSchema.CreateTerminalRequest request);

	/**
	 * Requests terminal output from the client.
	 * Blocks until the output is received.
	 * @param request The terminal output request
	 * @return The terminal output
	 */
	AcpSchema.TerminalOutputResponse getTerminalOutput(AcpSchema.TerminalOutputRequest request);

	/**
	 * Requests the client to release a terminal.
	 * Blocks until the terminal is released.
	 * @param request The release terminal request
	 * @return The release response
	 */
	AcpSchema.ReleaseTerminalResponse releaseTerminal(AcpSchema.ReleaseTerminalRequest request);

	/**
	 * Waits for a terminal to exit.
	 * Blocks until the terminal exits.
	 * @param request The wait for exit request
	 * @return The exit status
	 */
	AcpSchema.WaitForTerminalExitResponse waitForTerminalExit(AcpSchema.WaitForTerminalExitRequest request);

	/**
	 * Requests the client to kill a terminal.
	 * Blocks until the terminal is killed.
	 * @param request The kill terminal request
	 * @return The kill response
	 */
	AcpSchema.KillTerminalCommandResponse killTerminal(AcpSchema.KillTerminalCommandRequest request);

	// ========================================================================
	// Client Capabilities
	// ========================================================================

	/**
	 * Returns the capabilities negotiated with the client during initialization.
	 *
	 * <p>
	 * Use this to check what features the client supports before calling
	 * methods like {@link #readTextFile} or {@link #createTerminal}.
	 *
	 * @return the negotiated client capabilities, or null if not yet initialized
	 */
	NegotiatedCapabilities getClientCapabilities();

	// ========================================================================
	// Convenience API
	// ========================================================================

	/**
	 * Returns the session ID for this prompt invocation.
	 * @return the session ID
	 */
	String getSessionId();

	/**
	 * Sends a message to the client as an agent message chunk.
	 * This is a convenience method that wraps the text in the appropriate
	 * session update structure.
	 * @param text The message text to send
	 */
	void sendMessage(String text);

	/**
	 * Sends a thought to the client as an agent thought chunk.
	 * Thoughts are typically displayed differently than messages,
	 * showing the agent's reasoning process.
	 * @param text The thought text to send
	 */
	void sendThought(String text);

	/**
	 * Reads a text file from the client's file system.
	 * @param path The path to the file
	 * @return The file content
	 * @throws com.agentclientprotocol.sdk.error.AcpCapabilityException if client doesn't support file reading
	 */
	String readFile(String path);

	/**
	 * Reads a portion of a text file from the client's file system.
	 * @param path The path to the file
	 * @param startLine The line number to start reading from (0-indexed, null for beginning)
	 * @param lineCount The number of lines to read (null for all remaining)
	 * @return The file content
	 * @throws com.agentclientprotocol.sdk.error.AcpCapabilityException if client doesn't support file reading
	 */
	String readFile(String path, Integer startLine, Integer lineCount);

	/**
	 * Attempts to read a text file, returning empty if the file cannot be read
	 * or the client doesn't support file reading.
	 * @param path The path to the file
	 * @return Optional containing the file content, or empty if unavailable
	 */
	Optional<String> tryReadFile(String path);

	/**
	 * Writes content to a text file on the client's file system.
	 * @param path The path to the file
	 * @param content The content to write
	 * @throws com.agentclientprotocol.sdk.error.AcpCapabilityException if client doesn't support file writing
	 */
	void writeFile(String path, String content);

	/**
	 * Asks the client for permission to perform an action.
	 * Presents a simple Allow/Deny choice.
	 * @param action A description of the action to request permission for
	 * @return true if the user allowed the action, false otherwise
	 */
	boolean askPermission(String action);

	/**
	 * Asks the client to choose from multiple options.
	 * @param question The question to ask
	 * @param options The available options (at least 2)
	 * @return The selected option text, or null if cancelled
	 */
	String askChoice(String question, String... options);

	/**
	 * Executes a command in a terminal and waits for completion.
	 * The terminal is automatically released after execution.
	 * @param commandAndArgs The command and arguments to execute
	 * @return The command result containing output and exit code
	 * @throws com.agentclientprotocol.sdk.error.AcpCapabilityException if client doesn't support terminals
	 */
	CommandResult execute(String... commandAndArgs);

	/**
	 * Executes a command with options and waits for completion.
	 * The terminal is automatically released after execution.
	 * @param command The command configuration
	 * @return The command result containing output and exit code
	 * @throws com.agentclientprotocol.sdk.error.AcpCapabilityException if client doesn't support terminals
	 */
	CommandResult execute(Command command);

}
