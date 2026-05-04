/*
 * Copyright 2025-2025 the original author or authors.
 */

package com.agentclientprotocol.sdk.agent;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import com.agentclientprotocol.sdk.client.AcpAsyncClient;
import com.agentclientprotocol.sdk.client.AcpClient;
import com.agentclientprotocol.sdk.spec.AcpSchema.AgentCapabilities;
import com.agentclientprotocol.sdk.spec.AcpSchema.AgentMessageChunk;
import com.agentclientprotocol.sdk.spec.AcpSchema.AgentThoughtChunk;
import com.agentclientprotocol.sdk.spec.AcpSchema.ClientCapabilities;
import com.agentclientprotocol.sdk.spec.AcpSchema.CreateTerminalResponse;
import com.agentclientprotocol.sdk.spec.AcpSchema.FileSystemCapability;
import com.agentclientprotocol.sdk.spec.AcpSchema.InitializeRequest;
import com.agentclientprotocol.sdk.spec.AcpSchema.InitializeResponse;
import com.agentclientprotocol.sdk.spec.AcpSchema.NewSessionRequest;
import com.agentclientprotocol.sdk.spec.AcpSchema.NewSessionResponse;
import com.agentclientprotocol.sdk.spec.AcpSchema.PermissionSelected;
import com.agentclientprotocol.sdk.spec.AcpSchema.PromptRequest;
import com.agentclientprotocol.sdk.spec.AcpSchema.PromptResponse;
import com.agentclientprotocol.sdk.spec.AcpSchema.ReadTextFileResponse;
import com.agentclientprotocol.sdk.spec.AcpSchema.ReleaseTerminalResponse;
import com.agentclientprotocol.sdk.spec.AcpSchema.RequestPermissionResponse;
import com.agentclientprotocol.sdk.spec.AcpSchema.SessionNotification;
import com.agentclientprotocol.sdk.spec.AcpSchema.StopReason;
import com.agentclientprotocol.sdk.spec.AcpSchema.TerminalOutputResponse;
import com.agentclientprotocol.sdk.spec.AcpSchema.TextContent;
import com.agentclientprotocol.sdk.spec.AcpSchema.WaitForTerminalExitResponse;
import com.agentclientprotocol.sdk.spec.AcpSchema.WriteTextFileResponse;
import com.agentclientprotocol.sdk.test.InMemoryTransportPair;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.Collections;

/**
 * Tests for the convenience API methods on {@link SyncPromptContext} and {@link PromptContext}.
 *
 * @author Mark Pollack
 */
class ConvenienceApiTest {

	private static final Duration TIMEOUT = Duration.ofSeconds(10);

	private InMemoryTransportPair transportPair;

	@BeforeEach
	void setUp() {
		transportPair = InMemoryTransportPair.create();
	}

	@AfterEach
	void tearDown() {
		if (transportPair != null) {
			transportPair.closeGracefully().block(TIMEOUT);
		}
	}

	// ========================================================================
	// getSessionId() tests
	// ========================================================================

	@Test
	void getSessionIdReturnsCorrectValue() throws Exception {
		AtomicReference<String> capturedSessionId = new AtomicReference<>();

		AcpSyncAgent agent = AcpAgent.sync(transportPair.agentTransport())
			.requestTimeout(TIMEOUT)
			.initializeHandler(req -> InitializeResponse.ok())
			.newSessionHandler(req -> new NewSessionResponse("test-session-123", null, null))
			.promptHandler((request, context) -> {
				capturedSessionId.set(context.getSessionId());
				return PromptResponse.endTurn();
			})
			.build();

		AcpAsyncClient client = AcpClient.async(transportPair.clientTransport())
			.requestTimeout(TIMEOUT)
			.build();

		agent.start();
		Thread.sleep(100);

		client.initialize(new InitializeRequest(1, new ClientCapabilities())).block(TIMEOUT);
		client.newSession(new NewSessionRequest("/workspace", Collections.emptyList())).block(TIMEOUT);
		client.prompt(new PromptRequest("test-session-123", Collections.singletonList(new TextContent("test")))).block(TIMEOUT);

		assertThat(capturedSessionId.get()).isEqualTo("test-session-123");

		client.closeGracefully().block(TIMEOUT);
		agent.closeGracefully();
	}

	// ========================================================================
	// sendMessage() / sendThought() tests
	// ========================================================================

	@Test
	void sendMessageSendsAgentMessageChunk() throws Exception {
		List<SessionNotification> receivedUpdates = new CopyOnWriteArrayList<>();
		CountDownLatch latch = new CountDownLatch(1);

		AcpSyncAgent agent = AcpAgent.sync(transportPair.agentTransport())
			.requestTimeout(TIMEOUT)
			.initializeHandler(req -> InitializeResponse.ok())
			.newSessionHandler(req -> new NewSessionResponse("msg-session", null, null))
			.promptHandler((request, context) -> {
				context.sendMessage("Hello from agent!");
				return PromptResponse.endTurn();
			})
			.build();

		AcpAsyncClient client = AcpClient.async(transportPair.clientTransport())
			.requestTimeout(TIMEOUT)
			.sessionUpdateConsumer(notification -> {
				receivedUpdates.add(notification);
				latch.countDown();
				return Mono.empty();
			})
			.build();

		agent.start();
		Thread.sleep(100);

		client.initialize(new InitializeRequest(1, new ClientCapabilities())).block(TIMEOUT);
		client.newSession(new NewSessionRequest("/workspace", Collections.emptyList())).block(TIMEOUT);
		client.prompt(new PromptRequest("msg-session", Collections.singletonList(new TextContent("test")))).block(TIMEOUT);

		assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
		assertThat(receivedUpdates).hasSize(1);
		assertThat(receivedUpdates.get(0).update()).isInstanceOf(AgentMessageChunk.class);
		AgentMessageChunk chunk = (AgentMessageChunk) receivedUpdates.get(0).update();
		assertThat(((TextContent) chunk.content()).text()).isEqualTo("Hello from agent!");

		client.closeGracefully().block(TIMEOUT);
		agent.closeGracefully();
	}

	@Test
	void sendThoughtSendsAgentThoughtChunk() throws Exception {
		List<SessionNotification> receivedUpdates = new CopyOnWriteArrayList<>();
		CountDownLatch latch = new CountDownLatch(1);

		AcpSyncAgent agent = AcpAgent.sync(transportPair.agentTransport())
			.requestTimeout(TIMEOUT)
			.initializeHandler(req -> InitializeResponse.ok())
			.newSessionHandler(req -> new NewSessionResponse("thought-session", null, null))
			.promptHandler((request, context) -> {
				context.sendThought("Thinking about the problem...");
				return PromptResponse.endTurn();
			})
			.build();

		AcpAsyncClient client = AcpClient.async(transportPair.clientTransport())
			.requestTimeout(TIMEOUT)
			.sessionUpdateConsumer(notification -> {
				receivedUpdates.add(notification);
				latch.countDown();
				return Mono.empty();
			})
			.build();

		agent.start();
		Thread.sleep(100);

		client.initialize(new InitializeRequest(1, new ClientCapabilities())).block(TIMEOUT);
		client.newSession(new NewSessionRequest("/workspace", Collections.emptyList())).block(TIMEOUT);
		client.prompt(new PromptRequest("thought-session", Collections.singletonList(new TextContent("test")))).block(TIMEOUT);

		assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
		assertThat(receivedUpdates).hasSize(1);
		assertThat(receivedUpdates.get(0).update()).isInstanceOf(AgentThoughtChunk.class);
		AgentThoughtChunk chunk = (AgentThoughtChunk) receivedUpdates.get(0).update();
		assertThat(((TextContent) chunk.content()).text()).isEqualTo("Thinking about the problem...");

		client.closeGracefully().block(TIMEOUT);
		agent.closeGracefully();
	}

	// ========================================================================
	// readFile() / writeFile() tests
	// ========================================================================

	@Test
	void readFileReturnsFileContent() throws Exception {
		AtomicReference<String> readContent = new AtomicReference<>();

		AcpSyncAgent agent = AcpAgent.sync(transportPair.agentTransport())
			.requestTimeout(TIMEOUT)
			.initializeHandler(req -> InitializeResponse.ok())
			.newSessionHandler(req -> new NewSessionResponse("file-session", null, null))
			.promptHandler((request, context) -> {
				String content = context.readFile("/path/to/file.txt");
				readContent.set(content);
				return PromptResponse.endTurn();
			})
			.build();

		AcpAsyncClient client = AcpClient.async(transportPair.clientTransport())
			.requestTimeout(TIMEOUT)
			.readTextFileHandler(req -> {
				assertThat(req.path()).isEqualTo("/path/to/file.txt");
				return Mono.just(new ReadTextFileResponse("File content here"));
			})
			.build();

		agent.start();
		Thread.sleep(100);

		FileSystemCapability fsCaps = new FileSystemCapability(true, false);
		ClientCapabilities clientCaps = new ClientCapabilities(fsCaps, false);
		client.initialize(new InitializeRequest(1, clientCaps)).block(TIMEOUT);
		client.newSession(new NewSessionRequest("/workspace", Collections.emptyList())).block(TIMEOUT);
		client.prompt(new PromptRequest("file-session", Collections.singletonList(new TextContent("test")))).block(TIMEOUT);

		assertThat(readContent.get()).isEqualTo("File content here");

		client.closeGracefully().block(TIMEOUT);
		agent.closeGracefully();
	}

	@Test
	void writeFileWritesContent() throws Exception {
		AtomicReference<String> writtenPath = new AtomicReference<>();
		AtomicReference<String> writtenContent = new AtomicReference<>();

		AcpSyncAgent agent = AcpAgent.sync(transportPair.agentTransport())
			.requestTimeout(TIMEOUT)
			.initializeHandler(req -> InitializeResponse.ok())
			.newSessionHandler(req -> new NewSessionResponse("write-session", null, null))
			.promptHandler((request, context) -> {
				context.writeFile("/output/result.txt", "Generated content");
				return PromptResponse.endTurn();
			})
			.build();

		AcpAsyncClient client = AcpClient.async(transportPair.clientTransport())
			.requestTimeout(TIMEOUT)
			.writeTextFileHandler(req -> {
				writtenPath.set(req.path());
				writtenContent.set(req.content());
				return Mono.just(new WriteTextFileResponse());
			})
			.build();

		agent.start();
		Thread.sleep(100);

		FileSystemCapability fsCaps = new FileSystemCapability(false, true);
		ClientCapabilities clientCaps = new ClientCapabilities(fsCaps, false);
		client.initialize(new InitializeRequest(1, clientCaps)).block(TIMEOUT);
		client.newSession(new NewSessionRequest("/workspace", Collections.emptyList())).block(TIMEOUT);
		client.prompt(new PromptRequest("write-session", Collections.singletonList(new TextContent("test")))).block(TIMEOUT);

		assertThat(writtenPath.get()).isEqualTo("/output/result.txt");
		assertThat(writtenContent.get()).isEqualTo("Generated content");

		client.closeGracefully().block(TIMEOUT);
		agent.closeGracefully();
	}

	@Test
	void tryReadFileReturnsEmptyOnError() throws Exception {
		AtomicReference<Boolean> resultPresent = new AtomicReference<>();

		AcpSyncAgent agent = AcpAgent.sync(transportPair.agentTransport())
			.requestTimeout(TIMEOUT)
			.initializeHandler(req -> InitializeResponse.ok())
			.newSessionHandler(req -> new NewSessionResponse("try-read-session", null, null))
			.promptHandler((request, context) -> {
				java.util.Optional<String> result = context.tryReadFile("/nonexistent.txt");
				resultPresent.set(result.isPresent());
				return PromptResponse.endTurn();
			})
			.build();

		AcpAsyncClient client = AcpClient.async(transportPair.clientTransport())
			.requestTimeout(TIMEOUT)
			.readTextFileHandler(req -> Mono.error(new RuntimeException("File not found")))
			.build();

		agent.start();
		Thread.sleep(100);

		FileSystemCapability fsCaps = new FileSystemCapability(true, false);
		ClientCapabilities clientCaps = new ClientCapabilities(fsCaps, false);
		client.initialize(new InitializeRequest(1, clientCaps)).block(TIMEOUT);
		client.newSession(new NewSessionRequest("/workspace", Collections.emptyList())).block(TIMEOUT);
		client.prompt(new PromptRequest("try-read-session", Collections.singletonList(new TextContent("test")))).block(TIMEOUT);

		assertThat(resultPresent.get()).isFalse();

		client.closeGracefully().block(TIMEOUT);
		agent.closeGracefully();
	}

	// ========================================================================
	// askPermission() / askChoice() tests
	// ========================================================================

	@Test
	void askPermissionReturnsTrueWhenAllowed() throws Exception {
		AtomicReference<Boolean> permissionResult = new AtomicReference<>();

		AcpSyncAgent agent = AcpAgent.sync(transportPair.agentTransport())
			.requestTimeout(TIMEOUT)
			.initializeHandler(req -> InitializeResponse.ok())
			.newSessionHandler(req -> new NewSessionResponse("perm-session", null, null))
			.promptHandler((request, context) -> {
				boolean allowed = context.askPermission("Delete file?");
				permissionResult.set(allowed);
				return PromptResponse.endTurn();
			})
			.build();

		AcpAsyncClient client = AcpClient.async(transportPair.clientTransport())
			.requestTimeout(TIMEOUT)
			.requestPermissionHandler(req -> {
				assertThat(req.toolCall().title()).isEqualTo("Delete file?");
				return Mono.just(new RequestPermissionResponse(new PermissionSelected("allow")));
			})
			.build();

		agent.start();
		Thread.sleep(100);

		client.initialize(new InitializeRequest(1, new ClientCapabilities())).block(TIMEOUT);
		client.newSession(new NewSessionRequest("/workspace", Collections.emptyList())).block(TIMEOUT);
		client.prompt(new PromptRequest("perm-session", Collections.singletonList(new TextContent("test")))).block(TIMEOUT);

		assertThat(permissionResult.get()).isTrue();

		client.closeGracefully().block(TIMEOUT);
		agent.closeGracefully();
	}

	@Test
	void askPermissionReturnsFalseWhenDenied() throws Exception {
		AtomicReference<Boolean> permissionResult = new AtomicReference<>();

		AcpSyncAgent agent = AcpAgent.sync(transportPair.agentTransport())
			.requestTimeout(TIMEOUT)
			.initializeHandler(req -> InitializeResponse.ok())
			.newSessionHandler(req -> new NewSessionResponse("deny-session", null, null))
			.promptHandler((request, context) -> {
				boolean allowed = context.askPermission("Dangerous action?");
				permissionResult.set(allowed);
				return PromptResponse.endTurn();
			})
			.build();

		AcpAsyncClient client = AcpClient.async(transportPair.clientTransport())
			.requestTimeout(TIMEOUT)
			.requestPermissionHandler(req -> Mono.just(new RequestPermissionResponse(new PermissionSelected("deny"))))
			.build();

		agent.start();
		Thread.sleep(100);

		client.initialize(new InitializeRequest(1, new ClientCapabilities())).block(TIMEOUT);
		client.newSession(new NewSessionRequest("/workspace", Collections.emptyList())).block(TIMEOUT);
		client.prompt(new PromptRequest("deny-session", Collections.singletonList(new TextContent("test")))).block(TIMEOUT);

		assertThat(permissionResult.get()).isFalse();

		client.closeGracefully().block(TIMEOUT);
		agent.closeGracefully();
	}

	@Test
	void askChoiceReturnsSelectedOption() throws Exception {
		AtomicReference<String> choiceResult = new AtomicReference<>();

		AcpSyncAgent agent = AcpAgent.sync(transportPair.agentTransport())
			.requestTimeout(TIMEOUT)
			.initializeHandler(req -> InitializeResponse.ok())
			.newSessionHandler(req -> new NewSessionResponse("choice-session", null, null))
			.promptHandler((request, context) -> {
				String choice = context.askChoice("How to proceed?", "Overwrite", "Skip", "Cancel");
				choiceResult.set(choice);
				return PromptResponse.endTurn();
			})
			.build();

		AcpAsyncClient client = AcpClient.async(transportPair.clientTransport())
			.requestTimeout(TIMEOUT)
			.requestPermissionHandler(req -> {
				// Verify options are sent correctly
				assertThat(req.options()).hasSize(3);
				assertThat(req.options().get(0).name()).isEqualTo("Overwrite");
				assertThat(req.options().get(1).name()).isEqualTo("Skip");
				assertThat(req.options().get(2).name()).isEqualTo("Cancel");
				// Select "Skip" (index 1)
				return Mono.just(new RequestPermissionResponse(new PermissionSelected("1")));
			})
			.build();

		agent.start();
		Thread.sleep(100);

		client.initialize(new InitializeRequest(1, new ClientCapabilities())).block(TIMEOUT);
		client.newSession(new NewSessionRequest("/workspace", Collections.emptyList())).block(TIMEOUT);
		client.prompt(new PromptRequest("choice-session", Collections.singletonList(new TextContent("test")))).block(TIMEOUT);

		assertThat(choiceResult.get()).isEqualTo("Skip");

		client.closeGracefully().block(TIMEOUT);
		agent.closeGracefully();
	}

	// ========================================================================
	// execute() tests
	// ========================================================================

	@Test
	void executeRunsCommandAndReturnsResult() throws Exception {
		AtomicReference<CommandResult> cmdResult = new AtomicReference<>();

		AcpSyncAgent agent = AcpAgent.sync(transportPair.agentTransport())
			.requestTimeout(TIMEOUT)
			.initializeHandler(req -> InitializeResponse.ok())
			.newSessionHandler(req -> new NewSessionResponse("exec-session", null, null))
			.promptHandler((request, context) -> {
				CommandResult result = context.execute("echo", "hello");
				cmdResult.set(result);
				return PromptResponse.endTurn();
			})
			.build();

		AcpAsyncClient client = AcpClient.async(transportPair.clientTransport())
			.requestTimeout(TIMEOUT)
			.createTerminalHandler(req -> {
				assertThat(req.command()).isEqualTo("echo");
				assertThat(req.args()).containsExactly("hello");
				return Mono.just(new CreateTerminalResponse("term-123"));
			})
			.waitForTerminalExitHandler(req -> {
				assertThat(req.terminalId()).isEqualTo("term-123");
				return Mono.just(new WaitForTerminalExitResponse(0, null));
			})
			.terminalOutputHandler(req -> {
				assertThat(req.terminalId()).isEqualTo("term-123");
				return Mono.just(new TerminalOutputResponse("hello\n", false, null));
			})
			.releaseTerminalHandler(req -> Mono.just(new ReleaseTerminalResponse()))
			.build();

		agent.start();
		Thread.sleep(100);

		ClientCapabilities clientCaps = new ClientCapabilities(null, true);
		client.initialize(new InitializeRequest(1, clientCaps)).block(TIMEOUT);
		client.newSession(new NewSessionRequest("/workspace", Collections.emptyList())).block(TIMEOUT);
		client.prompt(new PromptRequest("exec-session", Collections.singletonList(new TextContent("test")))).block(TIMEOUT);

		assertThat(cmdResult.get()).isNotNull();
		assertThat(cmdResult.get().output()).isEqualTo("hello\n");
		assertThat(cmdResult.get().exitCode()).isEqualTo(0);
		assertThat(cmdResult.get().success()).isTrue();

		client.closeGracefully().block(TIMEOUT);
		agent.closeGracefully();
	}

	@Test
	void executeWithCommandBuilderWorks() throws Exception {
		AtomicReference<String> capturedCwd = new AtomicReference<>();

		AcpSyncAgent agent = AcpAgent.sync(transportPair.agentTransport())
			.requestTimeout(TIMEOUT)
			.initializeHandler(req -> InitializeResponse.ok())
			.newSessionHandler(req -> new NewSessionResponse("cmd-session", null, null))
			.promptHandler((request, context) -> {
				Command cmd = Command.of("make", "build").cwd("/project");
				context.execute(cmd);
				return PromptResponse.endTurn();
			})
			.build();

		AcpAsyncClient client = AcpClient.async(transportPair.clientTransport())
			.requestTimeout(TIMEOUT)
			.createTerminalHandler(req -> {
				capturedCwd.set(req.cwd());
				return Mono.just(new CreateTerminalResponse("term-456"));
			})
			.waitForTerminalExitHandler(req -> Mono.just(new WaitForTerminalExitResponse(0, null)))
			.terminalOutputHandler(req -> Mono.just(new TerminalOutputResponse("", false, null)))
			.releaseTerminalHandler(req -> Mono.just(new ReleaseTerminalResponse()))
			.build();

		agent.start();
		Thread.sleep(100);

		ClientCapabilities clientCaps = new ClientCapabilities(null, true);
		client.initialize(new InitializeRequest(1, clientCaps)).block(TIMEOUT);
		client.newSession(new NewSessionRequest("/workspace", Collections.emptyList())).block(TIMEOUT);
		client.prompt(new PromptRequest("cmd-session", Collections.singletonList(new TextContent("test")))).block(TIMEOUT);

		assertThat(capturedCwd.get()).isEqualTo("/project");

		client.closeGracefully().block(TIMEOUT);
		agent.closeGracefully();
	}

	@Test
	void executeReleasesTerminalBeforeReturning() throws Exception {
		// This test verifies the fix for the race condition where releaseTerminal
		// was called with fire-and-forget .subscribe() instead of being awaited
		AtomicBoolean releaseCalledBeforeReturn = new AtomicBoolean(false);
		AtomicBoolean executeReturned = new AtomicBoolean(false);

		AcpSyncAgent agent = AcpAgent.sync(transportPair.agentTransport())
			.requestTimeout(TIMEOUT)
			.initializeHandler(req -> InitializeResponse.ok())
			.newSessionHandler(req -> new NewSessionResponse("release-test", null, null))
			.promptHandler((request, context) -> {
				context.execute("echo", "test");
				// After execute() returns, releaseTerminal should have been called
				executeReturned.set(true);
				return PromptResponse.endTurn();
			})
			.build();

		AcpAsyncClient client = AcpClient.async(transportPair.clientTransport())
			.requestTimeout(TIMEOUT)
			.createTerminalHandler(req -> Mono.just(new CreateTerminalResponse("term-rel")))
			.waitForTerminalExitHandler(req -> Mono.just(new WaitForTerminalExitResponse(0, null)))
			.terminalOutputHandler(req -> Mono.just(new TerminalOutputResponse("", false, null)))
			.releaseTerminalHandler(req -> {
				// Release should be called BEFORE execute() returns
				if (!executeReturned.get()) {
					releaseCalledBeforeReturn.set(true);
				}
				return Mono.just(new ReleaseTerminalResponse());
			})
			.build();

		agent.start();
		Thread.sleep(100);

		ClientCapabilities clientCaps = new ClientCapabilities(null, true);
		client.initialize(new InitializeRequest(1, clientCaps)).block(TIMEOUT);
		client.newSession(new NewSessionRequest("/workspace", Collections.emptyList())).block(TIMEOUT);
		client.prompt(new PromptRequest("release-test", Collections.singletonList(new TextContent("test")))).block(TIMEOUT);

		// Verify that releaseTerminal was called before execute() returned
		assertThat(releaseCalledBeforeReturn.get())
			.as("releaseTerminal should be called before execute() returns (not fire-and-forget)")
			.isTrue();

		client.closeGracefully().block(TIMEOUT);
		agent.closeGracefully();
	}

	// ========================================================================
	// Factory method tests
	// ========================================================================

	@Test
	void promptResponseEndTurnCreatesCorrectResponse() {
		PromptResponse response = PromptResponse.endTurn();
		assertThat(response.stopReason()).isEqualTo(StopReason.END_TURN);
	}

	@Test
	void promptResponseTextCreatesEndTurnResponse() {
		PromptResponse response = PromptResponse.text("Hello");
		assertThat(response.stopReason()).isEqualTo(StopReason.END_TURN);
	}

	@Test
	void promptResponseRefusalCreatesRefusalResponse() {
		PromptResponse response = PromptResponse.refusal();
		assertThat(response.stopReason()).isEqualTo(StopReason.REFUSAL);
	}

	@Test
	void initializeResponseOkCreatesDefaultResponse() {
		InitializeResponse response = InitializeResponse.ok();
		assertThat(response.protocolVersion()).isEqualTo(1);
		assertThat(response.agentCapabilities()).isNotNull();
	}

	@Test
	void initializeResponseOkWithCapabilitiesWorks() {
		AgentCapabilities caps = new AgentCapabilities(true, null, null);
		InitializeResponse response = InitializeResponse.ok(caps);
		assertThat(response.protocolVersion()).isEqualTo(1);
		assertThat(response.agentCapabilities().loadSession()).isTrue();
	}

	// ========================================================================
	// Command and CommandResult tests
	// ========================================================================

	@Test
	void commandOfCreatesCorrectCommand() {
		Command cmd = Command.of("git", "status", "-s");
		assertThat(cmd.executable()).isEqualTo("git");
		assertThat(cmd.args()).containsExactly("status", "-s");
		assertThat(cmd.cwd()).isNull();
		assertThat(cmd.env()).isNull();
	}

	@Test
	void commandBuildersReturnNewInstances() {
		Command original = Command.of("make");
		Command withCwd = original.cwd("/project");
		Command withEnv = original.env(Collections.singletonMap("DEBUG", "true"));

		assertThat(withCwd).isNotSameAs(original);
		assertThat(withCwd.cwd()).isEqualTo("/project");
		assertThat(original.cwd()).isNull();

		assertThat(withEnv).isNotSameAs(original);
		assertThat(withEnv.env()).containsEntry("DEBUG", "true");
		assertThat(original.env()).isNull();
	}

	@Test
	void commandResultSuccessReturnsCorrectly() {
		CommandResult success = new CommandResult("output", 0);
		CommandResult failure = new CommandResult("error", 1);
		CommandResult timedOut = new CommandResult("partial", 0, true);

		assertThat(success.success()).isTrue();
		assertThat(failure.success()).isFalse();
		assertThat(timedOut.success()).isFalse();
	}

}
