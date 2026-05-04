/*
 * Copyright 2025-2025 the original author or authors.
 */

package com.agentclientprotocol.sdk.test;

import java.util.function.BiConsumer;

import com.agentclientprotocol.sdk.spec.AcpAgentTransport;
import com.agentclientprotocol.sdk.spec.AcpClientTransport;

/**
 * Interface for creating connected transport pairs for testing.
 *
 * <p>
 * This interface abstracts transport setup, enabling the same test logic to run
 * against multiple transport implementations. The pattern is inspired by the
 * Kotlin ACP SDK's ProtocolDriver.
 * </p>
 *
 * <p>
 * Implementations provide specific transport types:
 * </p>
 * <ul>
 * <li>{@link InMemoryProtocolDriver} - In-memory transports for unit tests</li>
 * <li>StdioProtocolDriver - Real stdio transport (future)</li>
 * <li>WebSocketProtocolDriver - WebSocket transport (future)</li>
 * </ul>
 *
 * <p>
 * Usage pattern:
 * </p>
 * <pre>{@code
 * public abstract class AbstractProtocolTest {
 *     private final ProtocolDriver driver;
 *
 *     protected AbstractProtocolTest(ProtocolDriver driver) {
 *         this.driver = driver;
 *     }
 *
 *     @Test
 *     void simpleRequestReturnsResult() {
 *         driver.runWithTransports((clientTransport, agentTransport) -> {
 *             // Set up message handlers
 *             // Send message from client
 *             // Verify it arrives at agent
 *         });
 *     }
 * }
 *
 * // Concrete test class uses specific driver
 * class InMemoryProtocolTest extends AbstractProtocolTest {
 *     InMemoryProtocolTest() {
 *         super(new InMemoryProtocolDriver());
 *     }
 * }
 * }</pre>
 *
 * @author Mark Pollack
 */
public interface ProtocolDriver {

	/**
	 * Runs a test with connected client and agent transports.
	 *
	 * <p>
	 * The implementation sets up the transport pair, runs the test block,
	 * and cleans up resources after the test completes.
	 * </p>
	 * @param testBlock the test code to run with the connected transports
	 */
	void runWithTransports(BiConsumer<AcpClientTransport, AcpAgentTransport> testBlock);

}
