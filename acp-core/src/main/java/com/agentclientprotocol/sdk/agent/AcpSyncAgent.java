/*
 * Copyright 2025-2025 the original author or authors.
 */

package com.agentclientprotocol.sdk.agent;

import java.time.Duration;

import com.agentclientprotocol.sdk.spec.AcpSchema;

/**
 * Synchronous ACP agent that provides blocking operations for handling client requests.
 *
 * <p>
 * This is a blocking wrapper around {@link AcpAsyncAgent} that uses {@code block()}
 * to convert reactive operations to blocking calls. Use this when you prefer a
 * simpler synchronous API and don't need the scalability of non-blocking operations.
 * </p>
 *
 * @author Mark Pollack
 * @see AcpAsyncAgent
 * @see AcpAgent
 */
public class AcpSyncAgent {

	private static final Duration DEFAULT_BLOCK_TIMEOUT = Duration.ofMinutes(5);

	private final AcpAsyncAgent asyncAgent;

	private final Duration blockTimeout;

	/**
	 * Creates a new synchronous agent wrapping the given async agent.
	 * @param asyncAgent The async agent to wrap
	 */
	public AcpSyncAgent(AcpAsyncAgent asyncAgent) {
		this(asyncAgent, DEFAULT_BLOCK_TIMEOUT);
	}

	/**
	 * Creates a new synchronous agent wrapping the given async agent.
	 * @param asyncAgent The async agent to wrap
	 * @param blockTimeout The timeout for blocking operations
	 */
	public AcpSyncAgent(AcpAsyncAgent asyncAgent, Duration blockTimeout) {
		this.asyncAgent = asyncAgent;
		this.blockTimeout = blockTimeout;
	}

	/**
	 * Starts the agent, beginning to accept client connections.
	 * This method returns immediately after setup is complete.
	 * Use {@link #await()} or {@link #run()} to block until the transport closes.
	 */
	public void start() {
		asyncAgent.start().block(blockTimeout);
	}

	/**
	 * Blocks until the agent terminates (transport closes).
	 * This is useful for keeping the main thread alive when using daemon threads.
	 *
	 * <p>Example usage:
	 * <pre>{@code
	 * agent.start();
	 * agent.await(); // Blocks until stdin closes
	 * }</pre>
	 */
	public void await() {
		asyncAgent.awaitTermination().block();
	}

	/**
	 * Starts the agent and blocks until it terminates.
	 * This is a convenience method combining {@link #start()} and {@link #await()}.
	 *
	 * <p>Example usage for a standalone agent:
	 * <pre>{@code
	 * public static void main(String[] args) {
	 *     AcpSyncAgent agent = AcpAgent.sync(transport)
	 *         .promptHandler(...)
	 *         .build();
	 *     agent.run(); // Start and block until done
	 * }
	 * }</pre>
	 */
	public void run() {
		start();
		await();
	}

	/**
	 * Sends a session update notification to the client.
	 * @param sessionId The session ID
	 * @param update The session update to send
	 */
	public void sendSessionUpdate(String sessionId, AcpSchema.SessionUpdate update) {
		asyncAgent.sendSessionUpdate(sessionId, update).block(blockTimeout);
	}

	/**
	 * Requests permission from the client for a sensitive operation.
	 * @param request The permission request
	 * @return The permission response
	 */
	public AcpSchema.RequestPermissionResponse requestPermission(AcpSchema.RequestPermissionRequest request) {
		return asyncAgent.requestPermission(request).block(blockTimeout);
	}

	/**
	 * Requests the client to read a text file.
	 * @param request The read file request
	 * @return The file content
	 */
	public AcpSchema.ReadTextFileResponse readTextFile(AcpSchema.ReadTextFileRequest request) {
		return asyncAgent.readTextFile(request).block(blockTimeout);
	}

	/**
	 * Requests the client to write a text file.
	 * @param request The write file request
	 * @return The write response
	 */
	public AcpSchema.WriteTextFileResponse writeTextFile(AcpSchema.WriteTextFileRequest request) {
		return asyncAgent.writeTextFile(request).block(blockTimeout);
	}

	/**
	 * Requests the client to create a terminal.
	 * @param request The create terminal request
	 * @return The terminal ID response
	 */
	public AcpSchema.CreateTerminalResponse createTerminal(AcpSchema.CreateTerminalRequest request) {
		return asyncAgent.createTerminal(request).block(blockTimeout);
	}

	/**
	 * Requests terminal output from the client.
	 * @param request The terminal output request
	 * @return The terminal output
	 */
	public AcpSchema.TerminalOutputResponse getTerminalOutput(AcpSchema.TerminalOutputRequest request) {
		return asyncAgent.getTerminalOutput(request).block(blockTimeout);
	}

	/**
	 * Requests the client to release a terminal.
	 * @param request The release terminal request
	 * @return The release response
	 */
	public AcpSchema.ReleaseTerminalResponse releaseTerminal(AcpSchema.ReleaseTerminalRequest request) {
		return asyncAgent.releaseTerminal(request).block(blockTimeout);
	}

	/**
	 * Waits for a terminal to exit.
	 * @param request The wait for exit request
	 * @return The exit status
	 */
	public AcpSchema.WaitForTerminalExitResponse waitForTerminalExit(AcpSchema.WaitForTerminalExitRequest request) {
		return asyncAgent.waitForTerminalExit(request).block(blockTimeout);
	}

	/**
	 * Requests the client to kill a terminal.
	 * @param request The kill terminal request
	 * @return The kill response
	 */
	public AcpSchema.KillTerminalCommandResponse killTerminal(AcpSchema.KillTerminalCommandRequest request) {
		return asyncAgent.killTerminal(request).block(blockTimeout);
	}

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
	public com.agentclientprotocol.sdk.capabilities.NegotiatedCapabilities getClientCapabilities() {
		return asyncAgent.getClientCapabilities();
	}

	/**
	 * Returns the underlying async agent.
	 * @return The async agent
	 */
	public AcpAsyncAgent async() {
		return asyncAgent;
	}

	/**
	 * Closes the agent gracefully, allowing pending operations to complete.
	 */
	public void closeGracefully() {
		asyncAgent.closeGracefully().block(blockTimeout);
	}

	/**
	 * Closes the agent immediately.
	 */
	public void close() {
		asyncAgent.close();
	}

}
