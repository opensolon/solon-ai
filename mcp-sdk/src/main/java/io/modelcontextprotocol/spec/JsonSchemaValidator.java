/*
 * Copyright 2024-2024 the original author or authors.
 */

package io.modelcontextprotocol.spec;

import java.util.Map;

/**
 * Interface for validating structured content against a JSON schema. This interface
 * defines a method to validate structured content based on the provided output schema.
 *
 * @author Christian Tzolov
 * @deprecated Use {@link io.modelcontextprotocol.json.schema.JsonSchemaValidator}
 */
@Deprecated
public interface JsonSchemaValidator {

	public final class ValidationResponse {
		private final boolean valid;
		private final String errorMessage;
		private final String jsonStructuredOutput;

		public ValidationResponse(boolean valid, String errorMessage, String jsonStructuredOutput) {
			this.valid = valid;
			this.errorMessage = errorMessage;
			this.jsonStructuredOutput = jsonStructuredOutput;
		}

		public boolean valid() {
			return this.valid;
		}

		public String errorMessage() {
			return this.errorMessage;
		}

		public String jsonStructuredOutput() {
			return this.jsonStructuredOutput;
		}

public static ValidationResponse asValid(String jsonStructuredOutput) {
			return new ValidationResponse(true, null, jsonStructuredOutput);
		}

		public static ValidationResponse asInvalid(String message) {
			return new ValidationResponse(false, message, null);
		}

	}

	/**
	 * Validates the structured content against the provided JSON schema.
	 * @param schema The JSON schema to validate against.
	 * @param structuredContent The structured content to validate.
	 * @return A ValidationResponse indicating whether the validation was successful or
	 * not.
	 */
	ValidationResponse validate(Map<String, Object> schema, Object structuredContent);

}
