/*
 * Copyright 2025-2025 the original author or authors.
 */

package com.agentclientprotocol.sdk.json;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;

/**
 * A type reference that captures generic type information at runtime.
 * Used for Jackson deserialization of generic types.
 *
 * <p>Usage:
 * <pre>{@code
 * TypeRef<List<String>> ref = new TypeRef<List<String>>() {};
 * }</pre>
 *
 * @param <T> the referenced type
 * @author ACP SDK
 */
public abstract class TypeRef<T> {

	private final Type type;

	protected TypeRef() {
		Type superClass = getClass().getGenericSuperclass();
		if (superClass instanceof ParameterizedType) {
			this.type = ((ParameterizedType) superClass).getActualTypeArguments()[0];
		}
		else {
			throw new IllegalArgumentException("TypeRef must be created with actual type parameters");
		}
	}

	public Type getType() {
		return type;
	}

}
