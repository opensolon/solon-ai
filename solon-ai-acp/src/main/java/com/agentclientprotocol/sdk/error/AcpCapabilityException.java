/*
 * Copyright 2025-2025 the original author or authors.
 */

package com.agentclientprotocol.sdk.error;

/**
 * Exception thrown when attempting to use a capability that the peer does not support.
 *
 * <p>
 * This exception is thrown during capability negotiation when:
 * <ul>
 * <li>An agent tries to call {@code fs/read_text_file} but the client didn't advertise
 * {@code fs.readTextFile} capability</li>
 * <li>An agent tries to use terminal features but the client didn't advertise
 * {@code terminal} capability</li>
 * <li>A client tries to send image content but the agent didn't advertise
 * {@code promptCapabilities.image} capability</li>
 * </ul>
 *
 * <p>
 * Example usage:
 * <pre>{@code
 * try {
 *     agent.readTextFile(request);
 * } catch (AcpCapabilityException e) {
 *     logger.warn("Client doesn't support file reading: {}", e.getCapability());
 * }
 * }</pre>
 *
 * @author Mark Pollack
 * @see AcpErrorCodes#CAPABILITY_NOT_SUPPORTED
 */
public class AcpCapabilityException extends AcpException {

	private final String capability;

	/**
	 * Constructs a new capability exception for the specified capability.
	 * @param capability the capability that is not supported (e.g., "fs.readTextFile")
	 */
	public AcpCapabilityException(String capability) {
		super(formatMessage(capability));
		this.capability = capability;
	}

	/**
	 * Constructs a new capability exception with a custom message.
	 * @param capability the capability that is not supported
	 * @param message a custom error message
	 */
	public AcpCapabilityException(String capability, String message) {
		super(message);
		this.capability = capability;
	}

	/**
	 * Returns the name of the unsupported capability.
	 * @return the capability name (e.g., "fs.readTextFile", "terminal")
	 */
	public String getCapability() {
		return capability;
	}

	/**
	 * Converts this exception to a JSON-RPC protocol exception.
	 * @return an AcpProtocolException with the appropriate error code
	 */
	public AcpProtocolException toProtocolException() {
		return new AcpProtocolException(AcpErrorCodes.CAPABILITY_NOT_SUPPORTED, getMessage(), capability);
	}

	private static String formatMessage(String capability) {
		return String.format("Capability not supported by peer: %s", capability);
	}

}
