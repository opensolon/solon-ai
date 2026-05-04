/*
 * Copyright 2025-2025 the original author or authors.
 */

package com.agentclientprotocol.sdk.integration;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import com.agentclientprotocol.sdk.agent.AcpAgent;
import com.agentclientprotocol.sdk.agent.AcpSyncAgent;
import com.agentclientprotocol.sdk.client.AcpClient;
import com.agentclientprotocol.sdk.client.AcpSyncClient;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import com.agentclientprotocol.sdk.test.InMemoryTransportPair;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.Collections;

/**
 * Integration tests to verify that ACP transports clean up properly on shutdown
 * and don't leave lingering threads that prevent JVM exit.
 *
 * <p>
 * These tests address GitHub issue: Transport threads prevent JVM exit when
 * closeGracefully() isn't called properly. The fix uses daemon threads for
 * all transport schedulers.
 * </p>
 *
 * @author Mark Pollack
 */
class CleanShutdownIT {

	/**
	 * Verifies that no ACP-prefixed threads remain after client close.
	 *
	 * <p>
	 * This test ensures that the daemon thread fix is working correctly.
	 * Before the fix, non-daemon threads would prevent the JVM from exiting.
	 * </p>
	 */
	@Test
	void noLingeringAcpThreadsAfterClientClose() throws InterruptedException {
		// Record threads before test
		Set<String> acpThreadsBefore = getAcpThreadNames();

		// Create and use client with in-memory transport
		InMemoryTransportPair transportPair = InMemoryTransportPair.create();

		try (AcpSyncClient client = AcpClient.sync(transportPair.clientTransport()).build()) {
			// The transport threads are created when build() connects
			// Give threads a moment to start
			Thread.sleep(100);

			// Verify threads were created (sanity check)
			Set<String> threadsWhileRunning = getAcpThreadNames();
			// Note: InMemory transport may not create threads in the same way as stdio,
			// but we should still verify no leak occurs
		}

		// Allow time for thread cleanup
		Thread.sleep(500);

		// Verify no ACP threads remain
		Set<String> acpThreadsAfter = getAcpThreadNames();
		assertThat(acpThreadsAfter)
			.describedAs("ACP threads should be cleaned up after client close")
			.isEqualTo(acpThreadsBefore);
	}

	/**
	 * Verifies that daemon threads are used for transport schedulers.
	 *
	 * <p>
	 * This test inspects active threads to ensure any ACP-prefixed threads
	 * are daemon threads, which allows the JVM to exit gracefully.
	 * </p>
	 */
	@Test
	void acpThreadsAreDaemonThreads() throws InterruptedException {
		InMemoryTransportPair transportPair = InMemoryTransportPair.create();

		try (AcpSyncClient client = AcpClient.sync(transportPair.clientTransport()).build()) {
			// Give threads a moment to start
			Thread.sleep(100);

			// Check that any ACP threads are daemon threads
			Set<Thread> acpThreads = Thread.getAllStackTraces().keySet().stream()
				.filter(t -> t.getName().startsWith("acp-"))
				.collect(Collectors.toSet());

			for (Thread thread : acpThreads) {
				assertThat(thread.isDaemon())
					.describedAs("Thread '%s' should be a daemon thread", thread.getName())
					.isTrue();
			}
		}
	}

	/**
	 * Verifies that agent awaitTermination() blocks until transport closes.
	 *
	 * <p>
	 * This test ensures the await() fix for daemon thread early exit is working.
	 * Before the fix, agents using daemon threads would exit immediately after start().
	 * With await(), agents properly block until the transport terminates.
	 * </p>
	 */
	@Test
	void agentAwaitBlocksUntilTransportCloses() throws InterruptedException {
		InMemoryTransportPair transportPair = InMemoryTransportPair.create();

		// Build agent - SyncAgentBuilder expects SyncInitializeHandler (returns response directly)
		AcpSyncAgent agent = AcpAgent.sync(transportPair.agentTransport())
			.requestTimeout(Duration.ofSeconds(5))
			.initializeHandler(request ->
				new AcpSchema.InitializeResponse(1, new AcpSchema.AgentCapabilities(), Collections.emptyList()))
			.build();

		// Track whether await() returned
		AtomicBoolean awaitReturned = new AtomicBoolean(false);
		CountDownLatch agentStarted = new CountDownLatch(1);

		// Start agent in background thread that calls await()
		Thread agentThread = new Thread(() -> {
			agent.start();
			agentStarted.countDown();
			agent.await(); // Should block until transport closes
			awaitReturned.set(true);
		});
		agentThread.setDaemon(true);
		agentThread.start();

		// Wait for agent to start
		assertThat(agentStarted.await(5, TimeUnit.SECONDS)).isTrue();

		// Give a moment and verify await() has NOT returned yet
		Thread.sleep(200);
		assertThat(awaitReturned.get())
			.describedAs("await() should NOT return while transport is still open")
			.isFalse();

		// Close transport - this should unblock await()
		transportPair.closeGracefully().block(Duration.ofSeconds(5));

		// Wait for agent thread to complete
		agentThread.join(5000);
		assertThat(awaitReturned.get())
			.describedAs("await() should return after transport closes")
			.isTrue();
	}

	/**
	 * Verifies that agent run() combines start() and await().
	 *
	 * <p>
	 * The run() method is a convenience for standalone agents that want to
	 * start and block in one call, similar to HTTP server patterns.
	 * </p>
	 */
	@Test
	void agentRunBlocksUntilTransportCloses() throws InterruptedException {
		InMemoryTransportPair transportPair = InMemoryTransportPair.create();

		// Build agent - SyncAgentBuilder expects SyncInitializeHandler (returns response directly)
		AcpSyncAgent agent = AcpAgent.sync(transportPair.agentTransport())
			.requestTimeout(Duration.ofSeconds(5))
			.initializeHandler(request ->
				new AcpSchema.InitializeResponse(1, new AcpSchema.AgentCapabilities(), Collections.emptyList()))
			.build();

		// Track whether run() returned
		AtomicBoolean runReturned = new AtomicBoolean(false);

		// Start agent using run() in background thread
		Thread agentThread = new Thread(() -> {
			agent.run(); // Should block until transport closes
			runReturned.set(true);
		});
		agentThread.setDaemon(true);
		agentThread.start();

		// Give a moment for agent to start
		Thread.sleep(200);
		assertThat(runReturned.get())
			.describedAs("run() should NOT return while transport is still open")
			.isFalse();

		// Close transport - this should unblock run()
		transportPair.closeGracefully().block(Duration.ofSeconds(5));

		// Wait for agent thread to complete
		agentThread.join(5000);
		assertThat(runReturned.get())
			.describedAs("run() should return after transport closes")
			.isTrue();
	}

	/**
	 * Gets the names of all threads that start with "acp-".
	 * @return set of ACP thread names
	 */
	private Set<String> getAcpThreadNames() {
		return Thread.getAllStackTraces().keySet().stream()
			.map(Thread::getName)
			.filter(name -> name.startsWith("acp-"))
			.collect(Collectors.toSet());
	}

}
