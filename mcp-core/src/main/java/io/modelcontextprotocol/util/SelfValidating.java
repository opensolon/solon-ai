/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.util;

/**
 * Marker for schema value objects whose canonical constructor validates its arguments.
 * Unlike records, plain classes can be instantiated by JSON mappers (Gson uses
 * {@code Unsafe} and skips constructors entirely) without running that validation. This
 * interface lets the deserialization boundary replay it, restoring record semantics
 * regardless of the mapper in use.
 *
 * @since 2.2.0
 */
public interface SelfValidating {

	/**
	 * Re-runs the canonical constructor's validation on the current field values. Must
	 * throw {@link IllegalArgumentException} (as {@link Assert} does) when they violate
	 * the constructor contract; implementations must be idempotent and side-effect free.
	 */
	void validate();

}
