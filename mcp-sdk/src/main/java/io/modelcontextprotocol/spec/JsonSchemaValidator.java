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
 */
public interface JsonSchemaValidator {

	/**
	 * Represents the result of a validation operation.
	 *
	 * validation was successful, otherwise null.
	 */
	public static class ValidationResponse {
		private boolean valid;
		private String errorMessage;
		private String jsonStructuredOutput;

		/**
		 * @param valid Indicates whether the validation was successful.
		 * @param errorMessage An error message if the validation failed, otherwise null.
		 * @param jsonStructuredOutput The text structured content in JSON format if the
		 * */
		public ValidationResponse(boolean valid, String errorMessage, String jsonStructuredOutput) {
			this.valid = valid;
			this.errorMessage = errorMessage;
			this.jsonStructuredOutput = jsonStructuredOutput;
		}

		public boolean valid() {
			return valid;
		}

		public String errorMessage() {
			return errorMessage;
		}

		public String jsonStructuredOutput() {
			return jsonStructuredOutput;
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
