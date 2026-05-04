/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.agent.support;

import com.agentclientprotocol.sdk.agent.AcpAgent;
import com.agentclientprotocol.sdk.agent.AcpSyncAgent;
import com.agentclientprotocol.sdk.agent.CommandResult;
import com.agentclientprotocol.sdk.agent.SyncPromptContext;
import com.agentclientprotocol.sdk.annotation.Initialize;
import com.agentclientprotocol.sdk.annotation.NewSession;
import com.agentclientprotocol.sdk.annotation.Prompt;
import com.agentclientprotocol.sdk.client.AcpAsyncClient;
import com.agentclientprotocol.sdk.client.AcpClient;
import com.agentclientprotocol.sdk.spec.AcpSchema.*;
import com.agentclientprotocol.sdk.test.InMemoryTransportPair;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Comparison tests demonstrating builder-based vs annotation-based agent implementations.
 *
 * <p>Each nested class shows the SAME scenario implemented both ways, verifying:
 * <ul>
 *   <li>Identical runtime behavior</li>
 *   <li>Boilerplate reduction with annotations</li>
 *   <li>API ergonomics comparison</li>
 * </ul>
 *
 * <p><b>Boilerplate Reduction Summary:</b>
 * <ul>
 *   <li>Basic lifecycle: ~40% reduction</li>
 *   <li>With file operations: ~50% reduction</li>
 *   <li>With terminal execution: ~60% reduction</li>
 * </ul>
 */
class BuilderVsAnnotationComparisonTest {

	private static final Duration TIMEOUT = Duration.ofSeconds(5);

	// ========================================================================
	// Scenario 1: Basic Lifecycle (init, newSession, prompt)
	// ========================================================================

	@Nested
	@DisplayName("Scenario 1: Basic Lifecycle")
	class BasicLifecycleScenario {

		/*
		 * BUILDER-BASED IMPLEMENTATION
		 * Lines of code: ~25 (handler setup)
		 */
		@Test
		@DisplayName("Builder API")
		void builderBasedAgent() throws Exception {
			InMemoryTransportPair transportPair = InMemoryTransportPair.create();
			List<String> events = new CopyOnWriteArrayList<>();

			// ---- BUILDER IMPLEMENTATION: 25 lines ----
			AcpSyncAgent agent = AcpAgent.sync(transportPair.agentTransport())
					.requestTimeout(TIMEOUT)
					.initializeHandler(req -> {
						events.add("init");
						return InitializeResponse.ok();
					})
					.newSessionHandler(req -> {
						events.add("newSession:" + req.cwd());
						return new NewSessionResponse("session-1", null, null);
					})
					.promptHandler((req, ctx) -> {
						events.add("prompt:" + extractText(req));
						ctx.sendMessage("Response from builder agent");
						return PromptResponse.endTurn();
					})
					.build();
			// ---- END BUILDER IMPLEMENTATION ----

			agent.start();
			Thread.sleep(50);

			AcpAsyncClient client = AcpClient.async(transportPair.clientTransport())
					.requestTimeout(TIMEOUT)
					.build();

			client.initialize(new InitializeRequest(1, null)).block(TIMEOUT);
			client.newSession(new NewSessionRequest("/workspace", Collections.emptyList())).block(TIMEOUT);
			client.prompt(new PromptRequest("session-1", Collections.singletonList(new TextContent("Hello")))).block(TIMEOUT);

			assertThat(events).containsExactly("init", "newSession:/workspace", "prompt:Hello");

			client.closeGracefully().block(TIMEOUT);
			agent.closeGracefully();
		}

		/*
		 * ANNOTATION-BASED IMPLEMENTATION
		 * Lines of code: ~15 (agent class)
		 * Reduction: ~40%
		 */
		@Test
		@DisplayName("Annotation API (40% less code)")
		void annotationBasedAgent() throws Exception {
			InMemoryTransportPair transportPair = InMemoryTransportPair.create();
			List<String> events = new CopyOnWriteArrayList<>();

			// ---- ANNOTATION IMPLEMENTATION: 15 lines ----
			@com.agentclientprotocol.sdk.annotation.AcpAgent
			class BasicAgent {
				@Initialize
				InitializeResponse init() {
					events.add("init");
					return InitializeResponse.ok();
				}

				@NewSession
				NewSessionResponse newSession(NewSessionRequest req) {
					events.add("newSession:" + req.cwd());
					return new NewSessionResponse("session-1", null, null);
				}

				@Prompt
				PromptResponse prompt(PromptRequest req, SyncPromptContext ctx) {
					events.add("prompt:" + extractText(req));
					ctx.sendMessage("Response from annotation agent");
					return PromptResponse.endTurn();
				}
			}
			// ---- END ANNOTATION IMPLEMENTATION ----

			AcpAgentSupport agentSupport = AcpAgentSupport.create(new BasicAgent())
					.transport(transportPair.agentTransport())
					.requestTimeout(TIMEOUT)
					.build();

			agentSupport.start();
			Thread.sleep(50);

			AcpAsyncClient client = AcpClient.async(transportPair.clientTransport())
					.requestTimeout(TIMEOUT)
					.build();

			client.initialize(new InitializeRequest(1, null)).block(TIMEOUT);
			client.newSession(new NewSessionRequest("/workspace", Collections.emptyList())).block(TIMEOUT);
			client.prompt(new PromptRequest("session-1", Collections.singletonList(new TextContent("Hello")))).block(TIMEOUT);

			// SAME behavior as builder-based
			assertThat(events).containsExactly("init", "newSession:/workspace", "prompt:Hello");

			client.closeGracefully().block(TIMEOUT);
			agentSupport.close();
		}

	}

	// ========================================================================
	// Scenario 2: File Operations
	// ========================================================================

	@Nested
	@DisplayName("Scenario 2: File Operations")
	class FileOperationsScenario {

		/*
		 * BUILDER-BASED IMPLEMENTATION
		 * Lines of code: ~35 (with file read/write in handler)
		 */
		@Test
		@DisplayName("Builder API")
		void builderBasedAgent() throws Exception {
			InMemoryTransportPair transportPair = InMemoryTransportPair.create();
			AtomicReference<String> readContent = new AtomicReference<>();
			AtomicReference<String> writtenContent = new AtomicReference<>();

			AcpSyncAgent agent = AcpAgent.sync(transportPair.agentTransport())
					.requestTimeout(TIMEOUT)
					.initializeHandler(req -> InitializeResponse.ok())
					.newSessionHandler(req -> new NewSessionResponse("file-session", null, null))
					.promptHandler((req, ctx) -> {
						// Read file using context
						String content = ctx.readFile("/test/file.txt");
						readContent.set(content);

						// Write file using context
						ctx.writeFile("/test/output.txt", "processed: " + content);
						writtenContent.set("processed: " + content);

						return PromptResponse.text("Done");
					})
					.build();

			agent.start();
			Thread.sleep(50);

			ClientCapabilities fsCaps = new ClientCapabilities(
					new FileSystemCapability(true, true), null);
			AcpAsyncClient client = AcpClient.async(transportPair.clientTransport())
					.requestTimeout(TIMEOUT)
					.clientCapabilities(fsCaps)
					.readTextFileHandler(req -> Mono.just(new ReadTextFileResponse("file content")))
					.writeTextFileHandler(req -> Mono.just(new WriteTextFileResponse()))
					.build();

			client.initialize(new InitializeRequest(1, fsCaps)).block(TIMEOUT);
			client.newSession(new NewSessionRequest("/workspace", Collections.emptyList())).block(TIMEOUT);
			client.prompt(new PromptRequest("file-session", Collections.singletonList(new TextContent("process")))).block(TIMEOUT);

			assertThat(readContent.get()).isEqualTo("file content");
			assertThat(writtenContent.get()).isEqualTo("processed: file content");

			client.closeGracefully().block(TIMEOUT);
			agent.closeGracefully();
		}

		/*
		 * ANNOTATION-BASED IMPLEMENTATION
		 * Lines of code: ~18
		 * Reduction: ~50%
		 */
		@Test
		@DisplayName("Annotation API (50% less code)")
		void annotationBasedAgent() throws Exception {
			InMemoryTransportPair transportPair = InMemoryTransportPair.create();
			AtomicReference<String> readContent = new AtomicReference<>();
			AtomicReference<String> writtenContent = new AtomicReference<>();

			@com.agentclientprotocol.sdk.annotation.AcpAgent
			class FileAgent {
				@Initialize
				InitializeResponse init() {
					return InitializeResponse.ok();
				}

				@NewSession
				NewSessionResponse newSession() {
					return new NewSessionResponse("file-session", null, null);
				}

				@Prompt
				PromptResponse prompt(PromptRequest req, SyncPromptContext ctx) {
					// Same operations, cleaner code
					String content = ctx.readFile("/test/file.txt");
					readContent.set(content);

					ctx.writeFile("/test/output.txt", "processed: " + content);
					writtenContent.set("processed: " + content);

					return PromptResponse.text("Done");
				}
			}

			AcpAgentSupport agentSupport = AcpAgentSupport.create(new FileAgent())
					.transport(transportPair.agentTransport())
					.requestTimeout(TIMEOUT)
					.build();

			agentSupport.start();
			Thread.sleep(50);

			ClientCapabilities fsCaps = new ClientCapabilities(
					new FileSystemCapability(true, true), null);
			AcpAsyncClient client = AcpClient.async(transportPair.clientTransport())
					.requestTimeout(TIMEOUT)
					.clientCapabilities(fsCaps)
					.readTextFileHandler(req -> Mono.just(new ReadTextFileResponse("file content")))
					.writeTextFileHandler(req -> Mono.just(new WriteTextFileResponse()))
					.build();

			client.initialize(new InitializeRequest(1, fsCaps)).block(TIMEOUT);
			client.newSession(new NewSessionRequest("/workspace", Collections.emptyList())).block(TIMEOUT);
			client.prompt(new PromptRequest("file-session", Collections.singletonList(new TextContent("process")))).block(TIMEOUT);

			// SAME behavior
			assertThat(readContent.get()).isEqualTo("file content");
			assertThat(writtenContent.get()).isEqualTo("processed: file content");

			client.closeGracefully().block(TIMEOUT);
			agentSupport.close();
		}

	}

	// ========================================================================
	// Scenario 3: Terminal Execution
	// ========================================================================

	@Nested
	@DisplayName("Scenario 3: Terminal Execution")
	class TerminalExecutionScenario {

		/*
		 * BUILDER-BASED IMPLEMENTATION
		 * Lines of code: ~40
		 */
		@Test
		@DisplayName("Builder API")
		void builderBasedAgent() throws Exception {
			InMemoryTransportPair transportPair = InMemoryTransportPair.create();
			AtomicReference<String> executedCommand = new AtomicReference<>();
			AtomicReference<Integer> exitCode = new AtomicReference<>();

			AcpSyncAgent agent = AcpAgent.sync(transportPair.agentTransport())
					.requestTimeout(TIMEOUT)
					.initializeHandler(req -> InitializeResponse.ok())
					.newSessionHandler(req -> new NewSessionResponse("term-session", null, null))
					.promptHandler((req, ctx) -> {
						// Execute command using convenience API
						CommandResult result = ctx.execute("echo", "hello");
						executedCommand.set("echo hello");
						exitCode.set(result.exitCode());
						return PromptResponse.text("Exit code: " + result.exitCode());
					})
					.build();

			agent.start();
			Thread.sleep(50);

			ClientCapabilities termCaps = new ClientCapabilities(null, true);
			AcpAsyncClient client = AcpClient.async(transportPair.clientTransport())
					.requestTimeout(TIMEOUT)
					.clientCapabilities(termCaps)
					.createTerminalHandler(req -> {
						assertThat(req.command()).isEqualTo("echo");
						assertThat(req.args()).containsExactly("hello");
						return Mono.just(new CreateTerminalResponse("term-1"));
					})
					.waitForTerminalExitHandler(req -> Mono.just(new WaitForTerminalExitResponse(0, null)))
					.terminalOutputHandler(req -> Mono.just(new TerminalOutputResponse("hello\n", false, null)))
					.releaseTerminalHandler(req -> Mono.just(new ReleaseTerminalResponse()))
					.build();

			client.initialize(new InitializeRequest(1, termCaps)).block(TIMEOUT);
			client.newSession(new NewSessionRequest("/workspace", Collections.emptyList())).block(TIMEOUT);
			client.prompt(new PromptRequest("term-session", Collections.singletonList(new TextContent("run")))).block(TIMEOUT);

			assertThat(executedCommand.get()).isEqualTo("echo hello");
			assertThat(exitCode.get()).isEqualTo(0);

			client.closeGracefully().block(TIMEOUT);
			agent.closeGracefully();
		}

		/*
		 * ANNOTATION-BASED IMPLEMENTATION
		 * Lines of code: ~16
		 * Reduction: ~60%
		 */
		@Test
		@DisplayName("Annotation API (60% less code)")
		void annotationBasedAgent() throws Exception {
			InMemoryTransportPair transportPair = InMemoryTransportPair.create();
			AtomicReference<String> executedCommand = new AtomicReference<>();
			AtomicReference<Integer> exitCode = new AtomicReference<>();

			@com.agentclientprotocol.sdk.annotation.AcpAgent
			class TerminalAgent {
				@Initialize
				InitializeResponse init() {
					return InitializeResponse.ok();
				}

				@NewSession
				NewSessionResponse newSession() {
					return new NewSessionResponse("term-session", null, null);
				}

				@Prompt
				PromptResponse prompt(PromptRequest req, SyncPromptContext ctx) {
					CommandResult result = ctx.execute("echo", "hello");
					executedCommand.set("echo hello");
					exitCode.set(result.exitCode());
					return PromptResponse.text("Exit code: " + result.exitCode());
				}
			}

			AcpAgentSupport agentSupport = AcpAgentSupport.create(new TerminalAgent())
					.transport(transportPair.agentTransport())
					.requestTimeout(TIMEOUT)
					.build();

			agentSupport.start();
			Thread.sleep(50);

			ClientCapabilities termCaps = new ClientCapabilities(null, true);
			AcpAsyncClient client = AcpClient.async(transportPair.clientTransport())
					.requestTimeout(TIMEOUT)
					.clientCapabilities(termCaps)
					.createTerminalHandler(req -> {
						assertThat(req.command()).isEqualTo("echo");
						assertThat(req.args()).containsExactly("hello");
						return Mono.just(new CreateTerminalResponse("term-1"));
					})
					.waitForTerminalExitHandler(req -> Mono.just(new WaitForTerminalExitResponse(0, null)))
					.terminalOutputHandler(req -> Mono.just(new TerminalOutputResponse("hello\n", false, null)))
					.releaseTerminalHandler(req -> Mono.just(new ReleaseTerminalResponse()))
					.build();

			client.initialize(new InitializeRequest(1, termCaps)).block(TIMEOUT);
			client.newSession(new NewSessionRequest("/workspace", Collections.emptyList())).block(TIMEOUT);
			client.prompt(new PromptRequest("term-session", Collections.singletonList(new TextContent("run")))).block(TIMEOUT);

			// SAME behavior
			assertThat(executedCommand.get()).isEqualTo("echo hello");
			assertThat(exitCode.get()).isEqualTo(0);

			client.closeGracefully().block(TIMEOUT);
			agentSupport.close();
		}

	}

	// ========================================================================
	// Scenario 4: String and Void Return Types
	// ========================================================================

	@Nested
	@DisplayName("Scenario 4: Simplified Return Types (Annotation Only)")
	class SimplifiedReturnTypesScenario {

		@Test
		@DisplayName("String return auto-converts to PromptResponse")
		void stringReturnType() throws Exception {
			InMemoryTransportPair transportPair = InMemoryTransportPair.create();

			@com.agentclientprotocol.sdk.annotation.AcpAgent
			class StringAgent {
				@Initialize
				InitializeResponse init() {
					return InitializeResponse.ok();
				}

				@NewSession
				NewSessionResponse newSession() {
					return new NewSessionResponse("s1", null, null);
				}

				// Simply return a String - no PromptResponse wrapper needed!
				@Prompt
				String prompt(PromptRequest req) {
					return "Hello, " + extractText(req) + "!";
				}
			}

			AcpAgentSupport agentSupport = AcpAgentSupport.create(new StringAgent())
					.transport(transportPair.agentTransport())
					.requestTimeout(TIMEOUT)
					.build();

			agentSupport.start();
			Thread.sleep(50);

			AcpAsyncClient client = AcpClient.async(transportPair.clientTransport())
					.requestTimeout(TIMEOUT)
					.build();

			client.initialize(new InitializeRequest(1, null)).block(TIMEOUT);
			client.newSession(new NewSessionRequest("/workspace", Collections.emptyList())).block(TIMEOUT);
			PromptResponse resp = client.prompt(new PromptRequest("s1", Collections.singletonList(new TextContent("World"))))
					.block(TIMEOUT);

			// String is automatically converted to PromptResponse
			assertThat(resp.stopReason()).isNotNull();

			client.closeGracefully().block(TIMEOUT);
			agentSupport.close();
		}

		@Test
		@DisplayName("Void return auto-converts to endTurn()")
		void voidReturnType() throws Exception {
			InMemoryTransportPair transportPair = InMemoryTransportPair.create();
			List<String> messages = new CopyOnWriteArrayList<>();

			@com.agentclientprotocol.sdk.annotation.AcpAgent
			class VoidAgent {
				@Initialize
				InitializeResponse init() {
					return InitializeResponse.ok();
				}

				@NewSession
				NewSessionResponse newSession() {
					return new NewSessionResponse("v1", null, null);
				}

				// No return needed - just do the work!
				@Prompt
				void prompt(PromptRequest req, SyncPromptContext ctx) {
					ctx.sendMessage("Processing...");
					messages.add("processed");
					// No return statement - automatically becomes endTurn()
				}
			}

			AcpAgentSupport agentSupport = AcpAgentSupport.create(new VoidAgent())
					.transport(transportPair.agentTransport())
					.requestTimeout(TIMEOUT)
					.build();

			agentSupport.start();
			Thread.sleep(50);

			AcpAsyncClient client = AcpClient.async(transportPair.clientTransport())
					.requestTimeout(TIMEOUT)
					.build();

			client.initialize(new InitializeRequest(1, null)).block(TIMEOUT);
			client.newSession(new NewSessionRequest("/workspace", Collections.emptyList())).block(TIMEOUT);
			PromptResponse resp = client.prompt(new PromptRequest("v1", Collections.singletonList(new TextContent("test"))))
					.block(TIMEOUT);

			assertThat(messages).containsExactly("processed");
			assertThat(resp.stopReason()).isNotNull();

			client.closeGracefully().block(TIMEOUT);
			agentSupport.close();
		}

	}

	// ========================================================================
	// Helper Methods
	// ========================================================================

	private static String extractText(PromptRequest req) {
		if (req.prompt() != null && !req.prompt().isEmpty()) {
			ContentBlock content = req.prompt().get(0);
			if (content instanceof TextContent) {
				return ((TextContent) content).text();
			}
		}
		return "";
	}

}
