/*
 * Copyright 2025-2025 the original author or authors.
 */

package com.agentclientprotocol.sdk.json;

import java.io.IOException;

/**
 * JSON serialization/deserialization interface for ACP protocol messages.
 * This is a local replacement for the MCP SDK's McpJsonMapper that is compatible with Java 8.
 *
 * @author ACP SDK
 */
public interface McpJsonMapper {

	/**
	 * Read a value from a JSON string using a Class type.
	 */
	<T> T readValue(String json, Class<T> clazz) throws IOException;

	/**
	 * Read a value from a byte array using a Class type.
	 */
	<T> T readValue(byte[] bytes, Class<T> clazz) throws IOException;

	/**
	 * Read a value from a JSON string using a TypeRef.
	 */
	<T> T readValue(String json, TypeRef<T> typeRef) throws IOException;

	/**
	 * Read a value from a byte array using a TypeRef.
	 */
	<T> T readValue(byte[] bytes, TypeRef<T> typeRef) throws IOException;

	/**
	 * Convert a value from one type to another (e.g., Map to a POJO).
	 */
	<T> T convertValue(Object fromValue, Class<T> toValueType);

	/**
	 * Convert a value using a TypeRef for generic types.
	 */
	<T> T convertValue(Object fromValue, TypeRef<T> toValueTypeRef);

	/**
	 * Write a value as a JSON string.
	 */
	String writeValueAsString(Object value) throws IOException;

	/**
	 * Write a value as a JSON byte array.
	 */
	byte[] writeValueAsBytes(Object value) throws IOException;

	/**
	 * Get the default singleton instance.
	 */
	static McpJsonMapper getDefault() {
		return JacksonMcpJsonMapper.INSTANCE;
	}

	/**
	 * Create a new default instance.
	 */
	static McpJsonMapper createDefault() {
		return new JacksonMcpJsonMapper();
	}

}
