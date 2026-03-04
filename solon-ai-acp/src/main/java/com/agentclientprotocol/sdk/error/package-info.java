/*
 * Copyright 2025-2025 the original author or authors.
 */

/**
 * Exception types and error codes for the ACP SDK.
 *
 * <p>
 * This package provides a structured exception hierarchy for handling errors in ACP
 * communication:
 * </p>
 *
 * <ul>
 * <li>{@link com.agentclientprotocol.sdk.error.AcpException} - Base class for all ACP
 * errors</li>
 * <li>{@link com.agentclientprotocol.sdk.error.AcpProtocolException} - JSON-RPC protocol
 * errors</li>
 * <li>{@link com.agentclientprotocol.sdk.error.AcpCapabilityException} - Capability
 * negotiation errors</li>
 * <li>{@link com.agentclientprotocol.sdk.error.AcpConnectionException} - Transport/connection
 * errors</li>
 * </ul>
 *
 * <p>
 * Standard error codes are defined in {@link com.agentclientprotocol.sdk.error.AcpErrorCodes}.
 * </p>
 *
 * @see com.agentclientprotocol.sdk.error.AcpErrorCodes
 */
package com.agentclientprotocol.sdk.error;
