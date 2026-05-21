/*
 * Copyright 2025-2025 the original author or authors.
 */

package com.agentclientprotocol.sdk;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import com.agentclientprotocol.sdk.client.AcpAsyncClient;
import com.agentclientprotocol.sdk.client.AcpClient;
import com.agentclientprotocol.sdk.client.transport.AgentParameters;
import com.agentclientprotocol.sdk.client.transport.StdioAcpClientTransport;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for ACP protocol against real Gemini CLI agent.
 *
 * <p>These tests require:
 * <ul>
 * <li>{@code GEMINI_API_KEY} environment variable set</li>
 * <li>{@code gemini} CLI installed and in PATH</li>
 * </ul>
 *
 * <p>Tests are named *IT to indicate they are integration tests that interact
 * with external systems (Gemini CLI) rather than mocks.
 *
 * @author Mark Pollack
 */
@EnabledIfEnvironmentVariable(named = "GEMINI_API_KEY", matches = ".+")
class GeminiCliIT {

	private static final Logger logger = LoggerFactory.getLogger(GeminiCliIT.class);

	@Test
	void testGeminiAgentWithHighLevelClient() throws Exception {
		logger.info("Starting Gemini ACP integration test with high-level client");

		// Create agent parameters for gemini
		AgentParameters params = AgentParameters.builder("gemini").arg("--experimental-acp").build();

		// Create JSON mapper - reuse MCP's default configured mapper
		McpJsonMapper jsonMapper = McpJsonDefaults.getMapper();

		// Create transport
		StdioAcpClientTransport transport = new StdioAcpClientTransport(params, jsonMapper);

		// Build client with session update consumer
		AcpAsyncClient client = AcpClient.async(transport)
			.requestTimeout(Duration.ofSeconds(30))
			.sessionUpdateConsumer(notification -> {
				logger.info("Received session update: sessionId={}, updateType={}", notification.sessionId(),
						notification.update().getClass().getSimpleName());
				return Mono.empty();
			})
			.build();

		try {
			logger.info("Sending initialize request");

			// Initialize
			AcpSchema.InitializeResponse initResponse = client
				.initialize(new AcpSchema.InitializeRequest(1, new AcpSchema.ClientCapabilities()))
				.block();

			assertThat(initResponse).isNotNull();
			assertThat(initResponse.protocolVersion()).isEqualTo(1);
			logger.info("Initialize response: protocolVersion={}, agentCapabilities={}", initResponse.protocolVersion(),
					initResponse.agentCapabilities());

			logger.info("Sending session/new request");

			// Create session
			AcpSchema.NewSessionResponse sessionResponse = client
				.newSession(new AcpSchema.NewSessionRequest(System.getProperty("user.dir"), Collections.emptyList()))
				.block();

			assertThat(sessionResponse).isNotNull();
			assertThat(sessionResponse.sessionId()).isNotNull();
			logger.info("New session response: sessionId={}", sessionResponse.sessionId());

			logger.info("Sending session/prompt request");

			// Send prompt
			AcpSchema.PromptResponse promptResponse = client
				.prompt(new AcpSchema.PromptRequest(sessionResponse.sessionId(),
						Collections.singletonList(new AcpSchema.TextContent("What is 2+2?"))))
				.block();

			assertThat(promptResponse).isNotNull();
			assertThat(promptResponse.stopReason()).isNotNull();
			logger.info("Prompt response: stopReason={}", promptResponse.stopReason());

			// Give some time for any final notifications
			Thread.sleep(1000);

			logger.info("Test completed successfully");
		}
		finally {
			logger.info("Closing client and transport");
			client.closeGracefully().block();
			transport.closeGracefully().block();
		}
	}

	@Test
	void testToolCallWithFileRead() throws Exception {
		logger.info("Starting Gemini tool call integration test");

		// Track all SessionUpdate types received
		Map<String, AtomicInteger> updateTypeCounts = new ConcurrentHashMap<>();
		List<AcpSchema.SessionUpdate> allUpdates = Collections.synchronizedList(new ArrayList<>());
		AtomicInteger permissionRequestCount = new AtomicInteger(0);

		// Create agent parameters for gemini
		AgentParameters params = AgentParameters.builder("gemini").arg("--experimental-acp").build();
		McpJsonMapper jsonMapper = McpJsonDefaults.getMapper();
		StdioAcpClientTransport transport = new StdioAcpClientTransport(params, jsonMapper);

		// Build client with handlers for session updates and permissions
		AcpAsyncClient client = AcpClient.async(transport)
			.requestTimeout(Duration.ofSeconds(60))
			.sessionUpdateConsumer(notification -> {
				AcpSchema.SessionUpdate update = notification.update();
				String updateType = update.getClass().getSimpleName();
				updateTypeCounts.computeIfAbsent(updateType, k -> new AtomicInteger(0)).incrementAndGet();
				allUpdates.add(update);

				// Log details for tool calls
				if (update instanceof AcpSchema.ToolCall) {
					AcpSchema.ToolCall toolCall = (AcpSchema.ToolCall) update;
					logger.info("Tool call: id={}, title={}, kind={}, status={}", toolCall.toolCallId(),
							toolCall.title(), toolCall.kind(), toolCall.status());
				}
				else if (update instanceof AcpSchema.ToolCallUpdateNotification) {
					AcpSchema.ToolCallUpdateNotification toolUpdate = (AcpSchema.ToolCallUpdateNotification) update;
					logger.info("Tool update: id={}, status={}", toolUpdate.toolCallId(), toolUpdate.status());
				}
				else if (update instanceof AcpSchema.AgentMessageChunk) {
					AcpSchema.AgentMessageChunk chunk = (AcpSchema.AgentMessageChunk) update;
					if (chunk.content() instanceof AcpSchema.TextContent) {
						AcpSchema.TextContent text = (AcpSchema.TextContent) chunk.content();
						logger.info("Agent message: {}", text.text().substring(0, Math.min(100, text.text().length())));
					}
				}
				else {
					logger.info("Session update: {}", updateType);
				}
				return Mono.empty();
			})
			// Auto-allow permission requests - using typed handler (no manual unmarshalling needed)
			.requestPermissionHandler((AcpSchema.RequestPermissionRequest request) -> {
				permissionRequestCount.incrementAndGet();
				logger.info("Permission request for tool: {}", request.toolCall().toolCallId());

				// Find the allow_once option or use first option
				String optionId = request.options().stream()
					.filter(opt -> opt.kind() == AcpSchema.PermissionOptionKind.ALLOW_ONCE)
					.findFirst()
					.map(AcpSchema.PermissionOption::optionId)
					.orElse(request.options().get(0).optionId());

				logger.info("Auto-allowing with option: {}", optionId);
				return Mono.just(new AcpSchema.RequestPermissionResponse(new AcpSchema.PermissionSelected(optionId)));
			})
			// Handle file read requests - using typed handler (no manual unmarshalling needed)
			.readTextFileHandler((AcpSchema.ReadTextFileRequest request) -> {
				logger.info("File read request: path={}", request.path());
				try {
					String content = new String(Files.readAllBytes(java.nio.file.Paths.get(request.path())), StandardCharsets.UTF_8);
					return Mono.just(new AcpSchema.ReadTextFileResponse(content));
				}
				catch (IOException e) {
					logger.error("Failed to read file: {}", request.path(), e);
					return Mono.error(e);
				}
			})
			.build();

		try {
			// Initialize
			AcpSchema.InitializeResponse initResponse = client
				.initialize(new AcpSchema.InitializeRequest(1, new AcpSchema.ClientCapabilities(
						new AcpSchema.FileSystemCapability(true, false), // read only
						true // permissions
				)))
				.block();

			assertThat(initResponse).isNotNull();
			logger.info("Initialized with agent capabilities: {}", initResponse.agentCapabilities());

			// Create session
			AcpSchema.NewSessionResponse sessionResponse = client
				.newSession(new AcpSchema.NewSessionRequest(System.getProperty("user.dir"), Collections.emptyList()))
				.block();

			assertThat(sessionResponse).isNotNull();
			String sessionId = sessionResponse.sessionId();
			logger.info("Created session: {}", sessionId);

			// Send a prompt that should trigger tool calls
			logger.info("Sending prompt to trigger tool call...");
			AcpSchema.PromptResponse promptResponse = client.prompt(new AcpSchema.PromptRequest(sessionId,
					Collections.singletonList(new AcpSchema.TextContent("Read the pom.xml file and tell me the artifact ID only."))))
				.block();

			assertThat(promptResponse).isNotNull();
			logger.info("Prompt completed with stopReason: {}", promptResponse.stopReason());

			// Wait for any remaining updates
			Thread.sleep(2000);

			// Log summary
			logger.info("=== Session Update Summary ===");
			updateTypeCounts.forEach((type, count) -> logger.info("  {}: {}", type, count.get()));
			logger.info("Total updates received: {}", allUpdates.size());
			logger.info("Permission requests: {}", permissionRequestCount.get());

			// Verify we received expected update types
			assertThat(allUpdates).isNotEmpty();

			// Check for tool calls (either ToolCall or ToolCallUpdateNotification)
			boolean hasToolCalls = allUpdates.stream()
				.anyMatch(u -> u instanceof AcpSchema.ToolCall || u instanceof AcpSchema.ToolCallUpdateNotification);
			logger.info("Received tool calls: {}", hasToolCalls);

			// Check for agent messages
			boolean hasAgentMessages = allUpdates.stream().anyMatch(u -> u instanceof AcpSchema.AgentMessageChunk);
			assertThat(hasAgentMessages).as("Should receive agent message chunks").isTrue();

			logger.info("Tool call integration test completed successfully");
		}
		finally {
			client.closeGracefully().block();
			transport.closeGracefully().block();
		}
	}

	@Test
	void testMultiplePromptsInSameSession() throws Exception {
		logger.info("Starting multiple prompts in same session test");

		AgentParameters params = AgentParameters.builder("gemini").arg("--experimental-acp").build();
		McpJsonMapper jsonMapper = McpJsonDefaults.getMapper();
		StdioAcpClientTransport transport = new StdioAcpClientTransport(params, jsonMapper);

		List<AcpSchema.SessionUpdate> allUpdates = Collections.synchronizedList(new ArrayList<>());

		AcpAsyncClient client = AcpClient.async(transport)
			.requestTimeout(Duration.ofSeconds(30))
			.sessionUpdateConsumer(notification -> {
				allUpdates.add(notification.update());
				return Mono.empty();
			})
			.build();

		try {
			// Initialize
			AcpSchema.InitializeResponse initResponse = client
				.initialize(new AcpSchema.InitializeRequest(1, new AcpSchema.ClientCapabilities()))
				.block();
			assertThat(initResponse).isNotNull();

			// Create session
			AcpSchema.NewSessionResponse sessionResponse = client
				.newSession(new AcpSchema.NewSessionRequest(System.getProperty("user.dir"), Collections.emptyList()))
				.block();
			assertThat(sessionResponse).isNotNull();
			String sessionId = sessionResponse.sessionId();
			logger.info("Created session: {}", sessionId);

			// First prompt
			logger.info("Sending first prompt...");
			AcpSchema.PromptResponse prompt1 = client
				.prompt(new AcpSchema.PromptRequest(sessionId,
						Collections.singletonList(new AcpSchema.TextContent("What is 2+2? Answer with just the number."))))
				.block();
			assertThat(prompt1).isNotNull();
			assertThat(prompt1.stopReason()).isNotNull();
			logger.info("First prompt completed: stopReason={}", prompt1.stopReason());

			int updatesAfterFirstPrompt = allUpdates.size();
			logger.info("Updates after first prompt: {}", updatesAfterFirstPrompt);

			// Second prompt in same session
			logger.info("Sending second prompt...");
			AcpSchema.PromptResponse prompt2 = client
				.prompt(new AcpSchema.PromptRequest(sessionId,
						Collections.singletonList(new AcpSchema.TextContent("What is 3+3? Answer with just the number."))))
				.block();
			assertThat(prompt2).isNotNull();
			assertThat(prompt2.stopReason()).isNotNull();
			logger.info("Second prompt completed: stopReason={}", prompt2.stopReason());

			int updatesAfterSecondPrompt = allUpdates.size();
			logger.info("Updates after second prompt: {}", updatesAfterSecondPrompt);

			// Should have received updates from both prompts
			assertThat(updatesAfterSecondPrompt).isGreaterThan(updatesAfterFirstPrompt);

			// Third prompt referencing context from previous
			logger.info("Sending third prompt with context reference...");
			AcpSchema.PromptResponse prompt3 = client
				.prompt(new AcpSchema.PromptRequest(sessionId,
						Collections.singletonList(new AcpSchema.TextContent("What is the sum of the two numbers I asked about?"))))
				.block();
			assertThat(prompt3).isNotNull();
			logger.info("Third prompt completed: stopReason={}", prompt3.stopReason());

			logger.info("Multiple prompts test completed - total updates: {}", allUpdates.size());
		}
		finally {
			client.closeGracefully().block();
			transport.closeGracefully().block();
		}
	}

	@Test
	void testCancelDuringPrompt() throws Exception {
		logger.info("Starting cancel during prompt test");

		AgentParameters params = AgentParameters.builder("gemini").arg("--experimental-acp").build();
		McpJsonMapper jsonMapper = McpJsonDefaults.getMapper();
		StdioAcpClientTransport transport = new StdioAcpClientTransport(params, jsonMapper);

		AtomicInteger updateCount = new AtomicInteger(0);

		AcpAsyncClient client = AcpClient.async(transport)
			.requestTimeout(Duration.ofSeconds(60))
			.sessionUpdateConsumer(notification -> {
				updateCount.incrementAndGet();
				logger.info("Update received: {}", notification.update().getClass().getSimpleName());
				return Mono.empty();
			})
			.build();

		try {
			// Initialize
			client.initialize(new AcpSchema.InitializeRequest(1, new AcpSchema.ClientCapabilities())).block();

			// Create session
			AcpSchema.NewSessionResponse sessionResponse = client
				.newSession(new AcpSchema.NewSessionRequest(System.getProperty("user.dir"), Collections.emptyList()))
				.block();
			String sessionId = sessionResponse.sessionId();
			logger.info("Created session: {}", sessionId);

			// Send a prompt that will take a while (ask for detailed explanation)
			logger.info("Sending long-running prompt...");
			Mono<AcpSchema.PromptResponse> promptMono = client.prompt(new AcpSchema.PromptRequest(sessionId,
					Collections.singletonList(new AcpSchema.TextContent("Explain quantum computing in detail."))));

			// Start the prompt asynchronously
			Disposable disposable = promptMono.subscribe(
					response -> logger.info("Prompt completed with: {}", response.stopReason()),
					error -> logger.info("Prompt error: {}", error.getMessage()));

			// Wait for some updates to arrive (Gemini may take a few seconds to start)
			// Poll for up to 10 seconds
			int updatesBeforeCancel = 0;
			for (int i = 0; i < 20; i++) {
				Thread.sleep(500);
				updatesBeforeCancel = updateCount.get();
				if (updatesBeforeCancel > 0) {
					break;
				}
			}
			logger.info("Updates before cancel: {}", updatesBeforeCancel);

			// Send cancel
			logger.info("Sending cancel notification...");
			client.cancel(new AcpSchema.CancelNotification(sessionId)).block();

			// Wait a bit for cancel to take effect
			Thread.sleep(1000);

			// Dispose if still running
			disposable.dispose();

			logger.info("Cancel test completed - updates received: {}", updateCount.get());

			// We should have received at least some updates (if Gemini responded at all)
			// This is best-effort - Gemini may be slow or busy
			if (updatesBeforeCancel == 0) {
				logger.warn("No updates received before cancel - Gemini may be slow");
			}
		}
		finally {
			client.closeGracefully().block();
			transport.closeGracefully().block();
		}
	}

	@Test
	void testFileWriteRequest() throws Exception {
		logger.info("Starting file write request test");

		AtomicInteger writeRequestCount = new AtomicInteger(0);
		List<AcpSchema.WriteTextFileRequest> writeRequests = Collections.synchronizedList(new ArrayList<>());

		AgentParameters params = AgentParameters.builder("gemini").arg("--experimental-acp").build();
		McpJsonMapper jsonMapper = McpJsonDefaults.getMapper();
		StdioAcpClientTransport transport = new StdioAcpClientTransport(params, jsonMapper);

		// Create a temp directory for file operations
		Path tempDir = Files.createTempDirectory("gemini-it-");
		logger.info("Using temp directory: {}", tempDir);

		AcpAsyncClient client = AcpClient.async(transport)
			.requestTimeout(Duration.ofSeconds(60))
			.sessionUpdateConsumer(notification -> {
				logger.info("Update: {}", notification.update().getClass().getSimpleName());
				return Mono.empty();
			})
			// Handle permission requests - auto-allow
			.requestPermissionHandler((AcpSchema.RequestPermissionRequest request) -> {
				logger.info("Permission request: {}", request.toolCall().title());
				String optionId = request.options().stream()
					.filter(opt -> opt.kind() == AcpSchema.PermissionOptionKind.ALLOW_ONCE)
					.findFirst()
					.map(AcpSchema.PermissionOption::optionId)
					.orElse(request.options().get(0).optionId());
				return Mono.just(new AcpSchema.RequestPermissionResponse(new AcpSchema.PermissionSelected(optionId)));
			})
			// Handle file read requests
			.readTextFileHandler((AcpSchema.ReadTextFileRequest request) -> {
				logger.info("Read request: {}", request.path());
				try {
					String content = new String(Files.readAllBytes(java.nio.file.Paths.get(request.path())), StandardCharsets.UTF_8);
					return Mono.just(new AcpSchema.ReadTextFileResponse(content));
				}
				catch (IOException e) {
					return Mono.error(e);
				}
			})
			// Handle file write requests
			.writeTextFileHandler((AcpSchema.WriteTextFileRequest request) -> {
				logger.info("Write request: path={}, contentLength={}", request.path(),
						request.content() != null ? request.content().length() : 0);
				writeRequestCount.incrementAndGet();
				writeRequests.add(request);
				try {
					Path filePath = java.nio.file.Paths.get(request.path());
					// Only write to temp directory for safety
					if (filePath.startsWith(tempDir)) {
						Files.createDirectories(filePath.getParent());
						Files.write(filePath, request.content().getBytes(StandardCharsets.UTF_8));
						logger.info("File written: {}", filePath);
					}
					else {
						logger.warn("Blocked write outside temp dir: {}", filePath);
					}
					return Mono.just(new AcpSchema.WriteTextFileResponse());
				}
				catch (IOException e) {
					return Mono.error(e);
				}
			})
			.build();

		try {
			// Initialize with write capability
			client.initialize(new AcpSchema.InitializeRequest(1, new AcpSchema.ClientCapabilities(
					new AcpSchema.FileSystemCapability(true, true), // read and write
					true // permissions
			))).block();

			// Create session in temp directory
			AcpSchema.NewSessionResponse sessionResponse = client
				.newSession(new AcpSchema.NewSessionRequest(tempDir.toString(), Collections.emptyList()))
				.block();
			String sessionId = sessionResponse.sessionId();
			logger.info("Created session in: {}", tempDir);

			// Ask agent to create a file
			logger.info("Asking agent to create a file...");
			AcpSchema.PromptResponse response = client.prompt(new AcpSchema.PromptRequest(sessionId,
					Collections.singletonList(new AcpSchema.TextContent(
							"Create a file called hello.txt in the current directory with the content 'Hello World'"))))
				.timeout(Duration.ofSeconds(90))
				.block();

			assertThat(response).isNotNull();
			logger.info("Prompt completed: stopReason={}", response.stopReason());

			// Wait for any pending operations
			Thread.sleep(2000);

			logger.info("Write requests received: {}", writeRequestCount.get());
			writeRequests.forEach(req -> logger.info("  - {}", req.path()));

			// Note: Gemini may use internal file operations instead of client fs/write_text_file
			// This test verifies the handler is registered and working
			logger.info("File write test completed");
		}
		finally {
			client.closeGracefully().block();
			transport.closeGracefully().block();
			// Cleanup temp directory
			try {
				Files.walk(tempDir)
					.sorted((a, b) -> -a.compareTo(b))
					.forEach(p -> {
						try {
							Files.delete(p);
						}
						catch (IOException ignored) {
						}
					});
			}
			catch (IOException ignored) {
			}
		}
	}

}
