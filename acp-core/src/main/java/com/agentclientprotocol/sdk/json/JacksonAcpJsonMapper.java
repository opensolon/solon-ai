/*
 * Copyright 2025-2025 the original author or authors.
 */

package com.agentclientprotocol.sdk.json;

import java.io.IOException;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Jackson-based implementation of {@link AcpJsonMapper}. Wraps a Jackson
 * {@link ObjectMapper} but keeps the SDK decoupled from Jackson at the API level.
 *
 * @author Mark Pollack
 */
public final class JacksonAcpJsonMapper implements AcpJsonMapper {

	private final ObjectMapper objectMapper;

	/**
	 * Constructs a new JacksonAcpJsonMapper with the given ObjectMapper.
	 * @param objectMapper the ObjectMapper to use. Must not be null.
	 * @throws IllegalArgumentException if the provided ObjectMapper is null.
	 */
	public JacksonAcpJsonMapper(ObjectMapper objectMapper) {
		if (objectMapper == null) {
			throw new IllegalArgumentException("ObjectMapper must not be null");
		}
		this.objectMapper = objectMapper;
	}

	/**
	 * Returns the underlying Jackson {@link ObjectMapper}.
	 * @return the ObjectMapper instance
	 */
	public ObjectMapper getObjectMapper() {
		return objectMapper;
	}

	@Override
	public <T> T readValue(String content, Class<T> type) throws IOException {
		return objectMapper.readValue(content, type);
	}

	@Override
	public <T> T readValue(byte[] content, Class<T> type) throws IOException {
		return objectMapper.readValue(content, type);
	}

	@Override
	public <T> T readValue(String content, TypeRef<T> type) throws IOException {
		JavaType javaType = objectMapper.getTypeFactory().constructType(type.getType());
		return objectMapper.readValue(content, javaType);
	}

	@Override
	public <T> T readValue(byte[] content, TypeRef<T> type) throws IOException {
		JavaType javaType = objectMapper.getTypeFactory().constructType(type.getType());
		return objectMapper.readValue(content, javaType);
	}

	@Override
	public <T> T convertValue(Object fromValue, Class<T> type) {
		return objectMapper.convertValue(fromValue, type);
	}

	@Override
	public <T> T convertValue(Object fromValue, TypeRef<T> type) {
		JavaType javaType = objectMapper.getTypeFactory().constructType(type.getType());
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
