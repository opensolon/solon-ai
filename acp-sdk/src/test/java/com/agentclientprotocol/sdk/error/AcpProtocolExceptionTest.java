/*
 * Copyright 2025-2025 the original author or authors.
 */

package com.agentclientprotocol.sdk.error;

import com.agentclientprotocol.sdk.spec.AcpSchema;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link AcpProtocolException}.
 */
class AcpProtocolExceptionTest {

	@Test
	void constructFromJsonRpcError() {
		AcpSchema.JSONRPCError error = new AcpSchema.JSONRPCError(-32601, "Method not found", "session/unknown");

		AcpProtocolException exception = new AcpProtocolException(error);

		assertThat(exception.getCode()).isEqualTo(-32601);
		assertThat(exception.getMessage()).contains("-32601");
		assertThat(exception.getMessage()).contains("Method not found");
		assertThat(exception.getData()).isEqualTo("session/unknown");
	}

	@Test
	void constructWithCodeAndMessage() {
		AcpProtocolException exception = new AcpProtocolException(AcpErrorCodes.CONCURRENT_PROMPT,
				"Already processing");

		assertThat(exception.getCode()).isEqualTo(-32000);
		assertThat(exception.getMessage()).contains("-32000");
		assertThat(exception.getMessage()).contains("Already processing");
		assertThat(exception.getData()).isNull();
	}

	@Test
	void constructWithCodeMessageAndData() {
		AcpProtocolException exception = new AcpProtocolException(AcpErrorCodes.INVALID_PARAMS, "Missing field",
				"sessionId");

		assertThat(exception.getCode()).isEqualTo(-32602);
		assertThat(exception.getMessage()).contains("Missing field");
		assertThat(exception.getData()).isEqualTo("sessionId");
	}

	@Test
	void toJsonRpcErrorConvertsCorrectly() {
		AcpProtocolException exception = new AcpProtocolException(AcpErrorCodes.INTERNAL_ERROR, "Unexpected failure",
				null);

		AcpSchema.JSONRPCError error = exception.toJsonRpcError();

		assertThat(error.code()).isEqualTo(-32603);
		assertThat(error.message()).contains("Unexpected failure");
		assertThat(error.data()).isNull();
	}

	@Test
	void isMethodNotFoundReturnsTrueForCorrectCode() {
		AcpProtocolException methodNotFound = new AcpProtocolException(AcpErrorCodes.METHOD_NOT_FOUND, "Unknown method");
		AcpProtocolException otherError = new AcpProtocolException(AcpErrorCodes.INTERNAL_ERROR, "Internal error");

		assertThat(methodNotFound.isMethodNotFound()).isTrue();
		assertThat(otherError.isMethodNotFound()).isFalse();
	}

	@Test
	void isConcurrentPromptReturnsTrueForCorrectCode() {
		AcpProtocolException concurrent = new AcpProtocolException(AcpErrorCodes.CONCURRENT_PROMPT, "Already running");
		AcpProtocolException otherError = new AcpProtocolException(AcpErrorCodes.INTERNAL_ERROR, "Internal error");

		assertThat(concurrent.isConcurrentPrompt()).isTrue();
		assertThat(otherError.isConcurrentPrompt()).isFalse();
	}

	@Test
	void isInvalidParamsReturnsTrueForCorrectCode() {
		AcpProtocolException invalidParams = new AcpProtocolException(AcpErrorCodes.INVALID_PARAMS, "Bad params");
		AcpProtocolException otherError = new AcpProtocolException(AcpErrorCodes.INTERNAL_ERROR, "Internal error");

		assertThat(invalidParams.isInvalidParams()).isTrue();
		assertThat(otherError.isInvalidParams()).isFalse();
	}

	@Test
	void isInternalErrorReturnsTrueForCorrectCode() {
		AcpProtocolException internal = new AcpProtocolException(AcpErrorCodes.INTERNAL_ERROR, "Server crash");
		AcpProtocolException otherError = new AcpProtocolException(AcpErrorCodes.METHOD_NOT_FOUND, "Unknown method");

		assertThat(internal.isInternalError()).isTrue();
		assertThat(otherError.isInternalError()).isFalse();
	}

	@Test
	void exceptionHierarchyIsCorrect() {
		AcpProtocolException exception = new AcpProtocolException(AcpErrorCodes.INTERNAL_ERROR, "Test");

		assertThat(exception).isInstanceOf(AcpException.class);
		assertThat(exception).isInstanceOf(RuntimeException.class);
	}

}
