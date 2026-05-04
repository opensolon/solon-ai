/*
 * Copyright 2025-2025 the original author or authors.
 */

package com.agentclientprotocol.sdk.client;

import java.time.Duration;
import java.util.Map;
import java.util.function.Function;

import com.agentclientprotocol.sdk.MockAcpClientTransport;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.Collections;

/**
 * Tests for typed handler API with auto-unmarshalling.
 *
 * <p>These tests verify that:
 * <ul>
 * <li>Typed handlers correctly unmarshall JSON-RPC params to typed request objects</li>
 * <li>Handler responses are correctly serialized back to JSON-RPC responses</li>
 * <li>Both sync and async typed handlers work correctly</li>
 * </ul>
 *
 * @author Mark Pollack
 */
class TypedHandlerTest {

	private static final Duration TIMEOUT = Duration.ofSeconds(5);

	/**
	 * Verifies that typed readTextFileHandler receives a properly unmarshalled
	 * ReadTextFileRequest with the correct path.
	 */
	@Test
	void typedReadTextFileHandlerReceivesUnmarshalledRequest() throws InterruptedException {
		MockAcpClientTransport transport = new MockAcpClientTransport();
		String testPath = "/home/user/test.txt";
		String testContent = "file contents here";

		// Build client with typed handler - explicit type to avoid ambiguity
		Function<AcpSchema.ReadTextFileRequest, AcpSchema.ReadTextFileResponse> handler = req -> {
			// Verify the request is properly typed and unmarshalled
			assertThat(req).isNotNull();
			assertThat(req.path()).isEqualTo(testPath);
			return new AcpSchema.ReadTextFileResponse(testContent);
		};
		AcpSyncClient client = AcpClient.sync(transport)
			.readTextFileHandler(handler)
			.build();

		// Simulate incoming request from agent with Map params (as JSON would be parsed)
		AcpSchema.JSONRPCRequest request = new AcpSchema.JSONRPCRequest(
				AcpSchema.JSONRPC_VERSION,
				"test-id",
				AcpSchema.METHOD_FS_READ_TEXT_FILE,
				Collections.singletonMap("path", testPath)
		);
		transport.simulateIncomingMessage(request);

		// Give time for async processing
		Thread.sleep(200);

		// Verify response was sent back
		AcpSchema.JSONRPCMessage sentMessage = transport.getLastSentMessage();
		assertThat(sentMessage).isInstanceOf(AcpSchema.JSONRPCResponse.class);
		AcpSchema.JSONRPCResponse response = (AcpSchema.JSONRPCResponse) sentMessage;
		assertThat(response.error()).isNull();
		assertThat(response.result()).isNotNull();

		// MockTransport passes objects directly without JSON serialization,
		// so result is the actual ReadTextFileResponse object
		assertThat(response.result()).isInstanceOf(AcpSchema.ReadTextFileResponse.class);
		AcpSchema.ReadTextFileResponse result = (AcpSchema.ReadTextFileResponse) response.result();
		assertThat(result.content()).isEqualTo(testContent);

		client.close();
	}

	/**
	 * Verifies that typed writeTextFileHandler receives a properly unmarshalled
	 * WriteTextFileRequest with path and content.
	 */
	@Test
	void typedWriteTextFileHandlerReceivesUnmarshalledRequest() throws InterruptedException {
		MockAcpClientTransport transport = new MockAcpClientTransport();
		String testPath = "/home/user/output.txt";
		String testContent = "content to write";

		// Track what the handler receives
		StringBuilder receivedPath = new StringBuilder();
		StringBuilder receivedContent = new StringBuilder();

		// Build client with typed handler - explicit type to avoid ambiguity
		Function<AcpSchema.WriteTextFileRequest, AcpSchema.WriteTextFileResponse> handler = req -> {
			receivedPath.append(req.path());
			receivedContent.append(req.content());
			return new AcpSchema.WriteTextFileResponse();
		};
		AcpSyncClient client = AcpClient.sync(transport)
			.writeTextFileHandler(handler)
			.build();

		// Simulate incoming request from agent
		AcpSchema.JSONRPCRequest request = new AcpSchema.JSONRPCRequest(
				AcpSchema.JSONRPC_VERSION,
				"test-id",
				AcpSchema.METHOD_FS_WRITE_TEXT_FILE,
				Collections.unmodifiableMap(new java.util.LinkedHashMap<String, Object>() {{ put("path", testPath); put("content", testContent); }})
		);
		transport.simulateIncomingMessage(request);

		// Give time for async processing
		Thread.sleep(200);

		// Verify handler received correct values
		assertThat(receivedPath.toString()).isEqualTo(testPath);
		assertThat(receivedContent.toString()).isEqualTo(testContent);

		// Verify response was sent back
		AcpSchema.JSONRPCMessage sentMessage = transport.getLastSentMessage();
		assertThat(sentMessage).isInstanceOf(AcpSchema.JSONRPCResponse.class);
		AcpSchema.JSONRPCResponse response = (AcpSchema.JSONRPCResponse) sentMessage;
		assertThat(response.error()).isNull();

		client.close();
	}

	/**
	 * Verifies that typed requestPermissionHandler receives a properly unmarshalled
	 * RequestPermissionRequest.
	 */
	@Test
	void typedRequestPermissionHandlerReceivesUnmarshalledRequest() throws InterruptedException {
		MockAcpClientTransport transport = new MockAcpClientTransport();
		String testSessionId = "session-123";

		// Track what the handler receives
		StringBuilder receivedSessionId = new StringBuilder();

		// Build client with typed handler - explicit type to avoid ambiguity
		Function<AcpSchema.RequestPermissionRequest, AcpSchema.RequestPermissionResponse> handler = req -> {
			receivedSessionId.append(req.sessionId());
			return new AcpSchema.RequestPermissionResponse(
					new AcpSchema.PermissionSelected("option-1"));
		};
		AcpSyncClient client = AcpClient.sync(transport)
			.requestPermissionHandler(handler)
			.build();

		// Simulate incoming request from agent - toolCall is a nested structure
		Map<String, Object> toolCall = Collections.unmodifiableMap(new java.util.LinkedHashMap<String, Object>() {{ put("title", "Read File"); put("description", "Reading test.txt"); }});
		AcpSchema.JSONRPCRequest request = new AcpSchema.JSONRPCRequest(
				AcpSchema.JSONRPC_VERSION,
				"test-id",
				AcpSchema.METHOD_SESSION_REQUEST_PERMISSION,
				Collections.unmodifiableMap(new java.util.LinkedHashMap<String, Object>() {{ put("sessionId", testSessionId); put("toolCall", toolCall); }})
		);
		transport.simulateIncomingMessage(request);

		// Give time for async processing
		Thread.sleep(200);

		// Verify handler received correct session ID
		assertThat(receivedSessionId.toString()).isEqualTo(testSessionId);

		// Verify response was sent back
		AcpSchema.JSONRPCMessage sentMessage = transport.getLastSentMessage();
		assertThat(sentMessage).isInstanceOf(AcpSchema.JSONRPCResponse.class);
		AcpSchema.JSONRPCResponse response = (AcpSchema.JSONRPCResponse) sentMessage;
		assertThat(response.error()).isNull();

		client.close();
	}

	/**
	 * Verifies that async typed handlers work correctly with Mono return types.
	 */
	@Test
	void asyncTypedReadTextFileHandlerWorksWithMono() throws InterruptedException {
		MockAcpClientTransport transport = new MockAcpClientTransport();
		String testPath = "/async/test.txt";
		String testContent = "async content";

		// Build async client with typed handler - explicit type to avoid ambiguity
		Function<AcpSchema.ReadTextFileRequest, Mono<AcpSchema.ReadTextFileResponse>> handler = req -> {
			assertThat(req.path()).isEqualTo(testPath);
			return Mono.just(new AcpSchema.ReadTextFileResponse(testContent));
		};
		AcpAsyncClient client = AcpClient.async(transport)
			.readTextFileHandler(handler)
			.build();

		// Simulate incoming request from agent
		AcpSchema.JSONRPCRequest request = new AcpSchema.JSONRPCRequest(
				AcpSchema.JSONRPC_VERSION,
				"test-id",
				AcpSchema.METHOD_FS_READ_TEXT_FILE,
				Collections.singletonMap("path", testPath)
		);
		transport.simulateIncomingMessage(request);

		// Give time for async processing
		Thread.sleep(200);

		// Verify response
		AcpSchema.JSONRPCMessage sentMessage = transport.getLastSentMessage();
		assertThat(sentMessage).isInstanceOf(AcpSchema.JSONRPCResponse.class);
		AcpSchema.JSONRPCResponse response = (AcpSchema.JSONRPCResponse) sentMessage;
		assertThat(response.error()).isNull();

		// MockTransport passes objects directly without JSON serialization
		assertThat(response.result()).isInstanceOf(AcpSchema.ReadTextFileResponse.class);
		AcpSchema.ReadTextFileResponse result = (AcpSchema.ReadTextFileResponse) response.result();
		assertThat(result.content()).isEqualTo(testContent);

		client.closeGracefully().block();
	}

	/**
	 * Verifies that handler errors are properly propagated as JSON-RPC errors.
	 */
	@Test
	void typedHandlerErrorPropagatesToJsonRpcError() throws InterruptedException {
		MockAcpClientTransport transport = new MockAcpClientTransport();
		String errorMessage = "File not found";

		// Build client with handler that throws - explicit type to avoid ambiguity
		Function<AcpSchema.ReadTextFileRequest, AcpSchema.ReadTextFileResponse> handler = req -> {
			throw new RuntimeException(errorMessage);
		};
		AcpSyncClient client = AcpClient.sync(transport)
			.readTextFileHandler(handler)
			.build();

		// Simulate incoming request
		AcpSchema.JSONRPCRequest request = new AcpSchema.JSONRPCRequest(
				AcpSchema.JSONRPC_VERSION,
				"test-id",
				AcpSchema.METHOD_FS_READ_TEXT_FILE,
				Collections.singletonMap("path", "/nonexistent")
		);
		transport.simulateIncomingMessage(request);

		// Give time for async processing
		Thread.sleep(200);

		// Verify error response
		AcpSchema.JSONRPCMessage sentMessage = transport.getLastSentMessage();
		assertThat(sentMessage).isInstanceOf(AcpSchema.JSONRPCResponse.class);
		AcpSchema.JSONRPCResponse response = (AcpSchema.JSONRPCResponse) sentMessage;
		assertThat(response.error()).isNotNull();
		assertThat(response.error().code()).isEqualTo(-32603); // Internal error
		assertThat(response.error().message()).isEqualTo(errorMessage);

		client.close();
	}

	// ========================================================================
	// Terminal Handler Tests
	// ========================================================================

	/**
	 * Verifies that typed createTerminalHandler receives a properly unmarshalled
	 * CreateTerminalRequest.
	 */
	@Test
	void typedCreateTerminalHandlerReceivesUnmarshalledRequest() throws InterruptedException {
		MockAcpClientTransport transport = new MockAcpClientTransport();
		String testSessionId = "session-123";
		String testCommand = "/bin/bash";
		String terminalId = "terminal-456";

		StringBuilder receivedSessionId = new StringBuilder();
		StringBuilder receivedCommand = new StringBuilder();

		Function<AcpSchema.CreateTerminalRequest, AcpSchema.CreateTerminalResponse> handler = req -> {
			receivedSessionId.append(req.sessionId());
			receivedCommand.append(req.command());
			return new AcpSchema.CreateTerminalResponse(terminalId);
		};
		AcpSyncClient client = AcpClient.sync(transport)
			.createTerminalHandler(handler)
			.build();

		AcpSchema.JSONRPCRequest request = new AcpSchema.JSONRPCRequest(
				AcpSchema.JSONRPC_VERSION,
				"test-id",
				AcpSchema.METHOD_TERMINAL_CREATE,
				Collections.unmodifiableMap(new java.util.LinkedHashMap<String, Object>() {{ put("sessionId", testSessionId); put("command", testCommand); }})
		);
		transport.simulateIncomingMessage(request);

		Thread.sleep(200);

		assertThat(receivedSessionId.toString()).isEqualTo(testSessionId);
		assertThat(receivedCommand.toString()).isEqualTo(testCommand);

		AcpSchema.JSONRPCMessage sentMessage = transport.getLastSentMessage();
		assertThat(sentMessage).isInstanceOf(AcpSchema.JSONRPCResponse.class);
		AcpSchema.JSONRPCResponse response = (AcpSchema.JSONRPCResponse) sentMessage;
		assertThat(response.error()).isNull();

		client.close();
	}

	/**
	 * Verifies that typed terminalOutputHandler receives a properly unmarshalled
	 * TerminalOutputRequest.
	 */
	@Test
	void typedTerminalOutputHandlerReceivesUnmarshalledRequest() throws InterruptedException {
		MockAcpClientTransport transport = new MockAcpClientTransport();
		String testSessionId = "session-123";
		String testTerminalId = "terminal-456";
		String testOutput = "command output here";

		StringBuilder receivedTerminalId = new StringBuilder();

		Function<AcpSchema.TerminalOutputRequest, AcpSchema.TerminalOutputResponse> handler = req -> {
			receivedTerminalId.append(req.terminalId());
			return new AcpSchema.TerminalOutputResponse(testOutput, false, null);
		};
		AcpSyncClient client = AcpClient.sync(transport)
			.terminalOutputHandler(handler)
			.build();

		AcpSchema.JSONRPCRequest request = new AcpSchema.JSONRPCRequest(
				AcpSchema.JSONRPC_VERSION,
				"test-id",
				AcpSchema.METHOD_TERMINAL_OUTPUT,
				Collections.unmodifiableMap(new java.util.LinkedHashMap<String, Object>() {{ put("sessionId", testSessionId); put("terminalId", testTerminalId); }})
		);
		transport.simulateIncomingMessage(request);

		Thread.sleep(200);

		assertThat(receivedTerminalId.toString()).isEqualTo(testTerminalId);

		AcpSchema.JSONRPCMessage sentMessage = transport.getLastSentMessage();
		assertThat(sentMessage).isInstanceOf(AcpSchema.JSONRPCResponse.class);
		AcpSchema.JSONRPCResponse response = (AcpSchema.JSONRPCResponse) sentMessage;
		assertThat(response.error()).isNull();

		client.close();
	}

	/**
	 * Verifies that typed releaseTerminalHandler receives a properly unmarshalled
	 * ReleaseTerminalRequest.
	 */
	@Test
	void typedReleaseTerminalHandlerReceivesUnmarshalledRequest() throws InterruptedException {
		MockAcpClientTransport transport = new MockAcpClientTransport();
		String testTerminalId = "terminal-456";

		StringBuilder receivedTerminalId = new StringBuilder();

		Function<AcpSchema.ReleaseTerminalRequest, AcpSchema.ReleaseTerminalResponse> handler = req -> {
			receivedTerminalId.append(req.terminalId());
			return new AcpSchema.ReleaseTerminalResponse();
		};
		AcpSyncClient client = AcpClient.sync(transport)
			.releaseTerminalHandler(handler)
			.build();

		AcpSchema.JSONRPCRequest request = new AcpSchema.JSONRPCRequest(
				AcpSchema.JSONRPC_VERSION,
				"test-id",
				AcpSchema.METHOD_TERMINAL_RELEASE,
				Collections.unmodifiableMap(new java.util.LinkedHashMap<String, Object>() {{ put("sessionId", "session-123"); put("terminalId", testTerminalId); }})
		);
		transport.simulateIncomingMessage(request);

		Thread.sleep(200);

		assertThat(receivedTerminalId.toString()).isEqualTo(testTerminalId);

		AcpSchema.JSONRPCMessage sentMessage = transport.getLastSentMessage();
		assertThat(sentMessage).isInstanceOf(AcpSchema.JSONRPCResponse.class);
		AcpSchema.JSONRPCResponse response = (AcpSchema.JSONRPCResponse) sentMessage;
		assertThat(response.error()).isNull();

		client.close();
	}

	/**
	 * Verifies that typed waitForTerminalExitHandler receives a properly unmarshalled
	 * WaitForTerminalExitRequest.
	 */
	@Test
	void typedWaitForTerminalExitHandlerReceivesUnmarshalledRequest() throws InterruptedException {
		MockAcpClientTransport transport = new MockAcpClientTransport();
		String testTerminalId = "terminal-456";

		StringBuilder receivedTerminalId = new StringBuilder();

		Function<AcpSchema.WaitForTerminalExitRequest, AcpSchema.WaitForTerminalExitResponse> handler = req -> {
			receivedTerminalId.append(req.terminalId());
			return new AcpSchema.WaitForTerminalExitResponse(0, null);
		};
		AcpSyncClient client = AcpClient.sync(transport)
			.waitForTerminalExitHandler(handler)
			.build();

		AcpSchema.JSONRPCRequest request = new AcpSchema.JSONRPCRequest(
				AcpSchema.JSONRPC_VERSION,
				"test-id",
				AcpSchema.METHOD_TERMINAL_WAIT_FOR_EXIT,
				Collections.unmodifiableMap(new java.util.LinkedHashMap<String, Object>() {{ put("sessionId", "session-123"); put("terminalId", testTerminalId); }})
		);
		transport.simulateIncomingMessage(request);

		Thread.sleep(200);

		assertThat(receivedTerminalId.toString()).isEqualTo(testTerminalId);

		AcpSchema.JSONRPCMessage sentMessage = transport.getLastSentMessage();
		assertThat(sentMessage).isInstanceOf(AcpSchema.JSONRPCResponse.class);
		AcpSchema.JSONRPCResponse response = (AcpSchema.JSONRPCResponse) sentMessage;
		assertThat(response.error()).isNull();

		client.close();
	}

	/**
	 * Verifies that typed killTerminalHandler receives a properly unmarshalled
	 * KillTerminalCommandRequest.
	 */
	@Test
	void typedKillTerminalHandlerReceivesUnmarshalledRequest() throws InterruptedException {
		MockAcpClientTransport transport = new MockAcpClientTransport();
		String testTerminalId = "terminal-456";

		StringBuilder receivedTerminalId = new StringBuilder();

		Function<AcpSchema.KillTerminalCommandRequest, AcpSchema.KillTerminalCommandResponse> handler = req -> {
			receivedTerminalId.append(req.terminalId());
			return new AcpSchema.KillTerminalCommandResponse();
		};
		AcpSyncClient client = AcpClient.sync(transport)
			.killTerminalHandler(handler)
			.build();

		AcpSchema.JSONRPCRequest request = new AcpSchema.JSONRPCRequest(
				AcpSchema.JSONRPC_VERSION,
				"test-id",
				AcpSchema.METHOD_TERMINAL_KILL,
				Collections.unmodifiableMap(new java.util.LinkedHashMap<String, Object>() {{ put("sessionId", "session-123"); put("terminalId", testTerminalId); }})
		);
		transport.simulateIncomingMessage(request);

		Thread.sleep(200);

		assertThat(receivedTerminalId.toString()).isEqualTo(testTerminalId);

		AcpSchema.JSONRPCMessage sentMessage = transport.getLastSentMessage();
		assertThat(sentMessage).isInstanceOf(AcpSchema.JSONRPCResponse.class);
		AcpSchema.JSONRPCResponse response = (AcpSchema.JSONRPCResponse) sentMessage;
		assertThat(response.error()).isNull();

		client.close();
	}

}
