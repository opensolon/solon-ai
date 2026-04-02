/*
 * Copyright 2025-2025 the original author or authors.
 */

package com.agentclientprotocol.sdk.json;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;

/**
 * Jackson-based implementation of {@link McpJsonMapper}.
 * Provides JSON serialization/deserialization using Jackson ObjectMapper.
 *
 * @author ACP SDK
 */
public final class JacksonMcpJsonMapper implements McpJsonMapper {

	static final JacksonMcpJsonMapper INSTANCE = new JacksonMcpJsonMapper();

	private final ObjectMapper objectMapper;

	public JacksonMcpJsonMapper() {
		this(createDefaultObjectMapper());
	}

	public JacksonMcpJsonMapper(ObjectMapper objectMapper) {
		this.objectMapper = objectMapper;
	}

	private static ObjectMapper createDefaultObjectMapper() {
		ObjectMapper mapper = new ObjectMapper();
		mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
		mapper.configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false);
		return mapper;
	}

	public ObjectMapper getObjectMapper() {
		return objectMapper;
	}

	@Override
	public <T> T readValue(String json, Class<T> clazz) throws IOException {
		return objectMapper.readValue(json, clazz);
	}

	@Override
	public <T> T readValue(byte[] bytes, Class<T> clazz) throws IOException {
		return objectMapper.readValue(bytes, clazz);
	}

	@Override
	public <T> T readValue(String json, TypeRef<T> typeRef) throws IOException {
		JavaType javaType = objectMapper.getTypeFactory().constructType(typeRef.getType());
		return objectMapper.readValue(json, javaType);
	}

	@Override
	public <T> T readValue(byte[] bytes, TypeRef<T> typeRef) throws IOException {
		JavaType javaType = objectMapper.getTypeFactory().constructType(typeRef.getType());
		return objectMapper.readValue(bytes, javaType);
	}

	@Override
	public <T> T convertValue(Object fromValue, Class<T> toValueType) {
		return objectMapper.convertValue(fromValue, toValueType);
	}

	@Override
	public <T> T convertValue(Object fromValue, TypeRef<T> toValueTypeRef) {
		JavaType javaType = objectMapper.getTypeFactory().constructType(toValueTypeRef.getType());
		return objectMapper.convertValue(fromValue, javaType);
	}

	@Override
	public String writeValueAsString(Object value) throws IOException {
		return objectMapper.writeValueAsString(value);
	}

	@Override
	public byte[] writeValueAsBytes(Object value) throws IOException {
		return objectMapper.writeValueAsBytes(value);
	}

}
