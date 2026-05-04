/*
 * Copyright 2025-2025 the original author or authors.
 */

package com.agentclientprotocol.sdk.error;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link AcpErrorCodes}.
 */
class AcpErrorCodesTest {

	@Test
	void verifyStandardJsonRpcErrorCodes() {
		// Standard JSON-RPC error codes
		assertThat(AcpErrorCodes.PARSE_ERROR).isEqualTo(-32700);
		assertThat(AcpErrorCodes.INVALID_REQUEST).isEqualTo(-32600);
		assertThat(AcpErrorCodes.METHOD_NOT_FOUND).isEqualTo(-32601);
		assertThat(AcpErrorCodes.INVALID_PARAMS).isEqualTo(-32602);
		assertThat(AcpErrorCodes.INTERNAL_ERROR).isEqualTo(-32603);
	}

	@Test
	void verifyAcpSpecificErrorCodes() {
		// ACP-specific error codes should be in the -32000 to -32099 range
		assertThat(AcpErrorCodes.CONCURRENT_PROMPT).isEqualTo(-32000);
		assertThat(AcpErrorCodes.CAPABILITY_NOT_SUPPORTED).isEqualTo(-32001);
		assertThat(AcpErrorCodes.SESSION_NOT_FOUND).isEqualTo(-32002);
		assertThat(AcpErrorCodes.NOT_INITIALIZED).isEqualTo(-32003);
		assertThat(AcpErrorCodes.AUTHENTICATION_REQUIRED).isEqualTo(-32004);
		assertThat(AcpErrorCodes.PERMISSION_DENIED).isEqualTo(-32005);
	}

	@Test
	void getDescriptionReturnsCorrectMessages() {
		assertThat(AcpErrorCodes.getDescription(AcpErrorCodes.PARSE_ERROR)).isEqualTo("Parse error");
		assertThat(AcpErrorCodes.getDescription(AcpErrorCodes.METHOD_NOT_FOUND)).isEqualTo("Method not found");
		assertThat(AcpErrorCodes.getDescription(AcpErrorCodes.CONCURRENT_PROMPT)).isEqualTo("Concurrent prompt");
		assertThat(AcpErrorCodes.getDescription(AcpErrorCodes.CAPABILITY_NOT_SUPPORTED))
			.isEqualTo("Capability not supported");
	}

	@Test
	void getDescriptionReturnsUnknownForUnrecognizedCodes() {
		assertThat(AcpErrorCodes.getDescription(-99999)).isEqualTo("Unknown error");
		assertThat(AcpErrorCodes.getDescription(0)).isEqualTo("Unknown error");
	}

}
