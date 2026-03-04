/*
 * Copyright 2025-2025 the original author or authors.
 */

package com.agentclientprotocol.sdk.agent;

import com.agentclientprotocol.sdk.capabilities.NegotiatedCapabilities;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import reactor.core.publisher.Mono;

/**
 * Asynchronous ACP agent interface providing non-blocking operations for
 * handling client requests and sending notifications.
 *
 * <p>
 * The agent is the server-side component in the ACP protocol, responsible for:
 * <ul>
 * <li>Responding to client initialization and authentication requests</li>
 * <li>Managing sessions and processing prompts</li>
 * <li>Sending session update notifications during processing</li>
 * <li>Requesting client capabilities (file system, terminal, permissions)</li>
 * </ul>
 *
 * @author Mark Pollack
 * @see AcpAgent
 * @see AcpSyncAgent
 */
public interface AcpAsyncAgent {

	/**
	 * Starts the agent, beginning to accept client connections.
	 * @return A Mono that completes when the agent is started
	 */
	Mono<Void> start();

	/**
	 * Returns a Mono that completes when the agent terminates.
	 * This is useful for blocking until the transport closes, particularly
	 * when using daemon threads.
	 *
	 * <p>Example usage:
	 * <pre>{@code
	 * agent.start().block();
	 * agent.awaitTermination().block(); // Block until transport closes
	 * }</pre>
	 *
	 * @return A Mono that completes when the agent terminates
	 */
	Mono<Void> awaitTermination();

	/**
	 * Returns the capabilities negotiated with the client during initialization.
	 *
	 * <p>
	 * This method returns null if initialization has not been completed yet.
	 * Use this to check what features the client supports before calling
	 * methods like {@link #readTextFile} or {@link #createTerminal}.
	 * </p>
	 * @return the negotiated client capabilities, or null if not initialized
	 */
	NegotiatedCapabilities getClientCapabilities();

	/**
	 * Sends a session update notification to the client.
	 * Used for streaming updates during prompt processing.
	 * @param sessionId The session ID
	 * @param update The session update to send
	 * @return A Mono that completes when the notification is sent
	 */
	Mono<Void> sendSessionUpdate(String sessionId, AcpSchema.SessionUpdate update);

	/**
	 * Requests permission from the client for a sensitive operation.
	 * @param request The permission request
	 * @return A Mono containing the permission response
	 */
	Mono<AcpSchema.RequestPermissionResponse> requestPermission(AcpSchema.RequestPermissionRequest request);

	/**
	 * Requests the client to read a text file.
	 * @param request The read file request
	 * @return A Mono containing the file content
	 */
	Mono<AcpSchema.ReadTextFileResponse> readTextFile(AcpSchema.ReadTextFileRequest request);

	/**
	 * Requests the client to write a text file.
	 * @param request The write file request
	 * @return A Mono that completes when the file is written
	 */
	Mono<AcpSchema.WriteTextFileResponse> writeTextFile(AcpSchema.WriteTextFileRequest request);

	/**
	 * Requests the client to create a terminal.
	 * @param request The create terminal request
	 * @return A Mono containing the terminal ID
	 */
	Mono<AcpSchema.CreateTerminalResponse> createTerminal(AcpSchema.CreateTerminalRequest request);

	/**
	 * Requests terminal output from the client.
	 * @param request The terminal output request
	 * @return A Mono containing the terminal output
	 */
	Mono<AcpSchema.TerminalOutputResponse> getTerminalOutput(AcpSchema.TerminalOutputRequest request);

	/**
	 * Requests the client to release a terminal.
	 * @param request The release terminal request
	 * @return A Mono that completes when the terminal is released
	 */
	Mono<AcpSchema.ReleaseTerminalResponse> releaseTerminal(AcpSchema.ReleaseTerminalRequest request);

	/**
	 * Waits for a terminal to exit.
	 * @param request The wait for exit request
	 * @return A Mono containing the exit status
	 */
	Mono<AcpSchema.WaitForTerminalExitResponse> waitForTerminalExit(AcpSchema.WaitForTerminalExitRequest request);

	/**
	 * Requests the client to kill a terminal.
	 * @param request The kill terminal request
	 * @return A Mono that completes when the terminal is killed
	 */
	Mono<AcpSchema.KillTerminalCommandResponse> killTerminal(AcpSchema.KillTerminalCommandRequest request);

	/**
	 * Closes the agent gracefully, allowing pending operations to complete.
	 * @return A Mono that completes when the agent is closed
	 */
	Mono<Void> closeGracefully();

	/**
	 * Closes the agent immediately.
	 */
	void close();

}
