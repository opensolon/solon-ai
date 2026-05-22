/*
 * Copyright 2025 - 2025 the original author or authors.
 */

package io.modelcontextprotocol.json.schema;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.ServiceLoader;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Internal utility class for creating a default {@link JsonSchemaValidator} instance.
 * This class uses the {@link ServiceLoader} to discover and instantiate a
 * {@link JsonSchemaValidatorSupplier} implementation.
 */
final class JsonSchemaInternal {

	private static JsonSchemaValidator defaultValidator = null;

	/**
	 * Returns the default {@link JsonSchemaValidator} instance. If the default validator
	 * has not been initialized, it will be created using the {@link ServiceLoader} to
	 * discover and instantiate a {@link JsonSchemaValidatorSupplier} implementation.
	 * @return The default {@link JsonSchemaValidator} instance.
	 * @throws IllegalStateException If no {@link JsonSchemaValidatorSupplier}
	 * implementation exists on the classpath or if an error occurs during instantiation.
	 */
	static JsonSchemaValidator getDefaultValidator() {
		if (defaultValidator == null) {
			synchronized (JsonSchemaInternal.class) {
				if (defaultValidator == null) {
					defaultValidator = JsonSchemaInternal.createDefaultValidator();
				}
			}
		}
		return defaultValidator;
	}

	/**
	 * Creates a default {@link JsonSchemaValidator} instance by loading a
	 * {@link JsonSchemaValidatorSupplier} implementation using the {@link ServiceLoader}.
	 * @return A default {@link JsonSchemaValidator} instance.
	 * @throws IllegalStateException If no {@link JsonSchemaValidatorSupplier}
	 * implementation is found or if an error occurs during instantiation.
	 */
	static JsonSchemaValidator createDefaultValidator() {
		AtomicReference<IllegalStateException> ex = new AtomicReference<>();
		List<JsonSchemaValidator> validators = new ArrayList<>();

		ServiceLoader<JsonSchemaValidatorSupplier> loader = ServiceLoader.load(JsonSchemaValidatorSupplier.class);
		Iterator<JsonSchemaValidatorSupplier> iterator = loader.iterator();

		while (iterator.hasNext()) {
			JsonSchemaValidatorSupplier supplier = null;
			try {
				supplier = iterator.next();
			} catch (Exception e) {
				addException(ex, e);
				continue;
			}

			if (supplier != null) {
				try {
					JsonSchemaValidator validator = supplier.get();
					if (validator != null) {
						validators.add(validator);
						// 找到第一个有效的就返回
						return validator;
					}
				} catch (Exception e) {
					addException(ex, e);
				}
			}
		}

		if (!validators.isEmpty()) {
			return validators.get(0);
		}

		if (ex.get() != null) {
			throw ex.get();
		} else {
			throw new IllegalStateException("No default JsonSchemaValidatorSupplier implementation found");
		}
	}

	private static void addException(AtomicReference<IllegalStateException> ref, Exception toAdd) {
		IllegalStateException existing = ref.get();
		if (existing == null) {
			ref.set(new IllegalStateException("Failed to initialize default JsonSchemaValidatorSupplier", toAdd));
		} else {
			existing.addSuppressed(toAdd);
		}
	}

}