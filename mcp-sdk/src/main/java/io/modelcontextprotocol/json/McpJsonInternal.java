/*
 * Copyright 2025 - 2025 the original author or authors.
 */

package io.modelcontextprotocol.json;

import java.util.Iterator;
import java.util.ServiceLoader;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Utility class for creating a default {@link McpJsonMapper} instance. This class
 * provides a single method to create a default mapper using the {@link ServiceLoader}
 * mechanism.
 */
final class McpJsonInternal {

	private static volatile McpJsonMapper defaultJsonMapper = null;

	/**
	 * Returns the cached default {@link McpJsonMapper} instance. If the default mapper
	 * has not been created yet, it will be initialized using the
	 * {@link #createDefaultMapper()} method.
	 * @return the default {@link McpJsonMapper} instance
	 * @throws IllegalStateException if no default {@link McpJsonMapper} implementation is
	 * found
	 */
	static McpJsonMapper getDefaultMapper() {
		if (defaultJsonMapper == null) {
			synchronized (McpJsonInternal.class) {
				if (defaultJsonMapper == null) {
					defaultJsonMapper = McpJsonInternal.createDefaultMapper();
				}
			}
		}
		return defaultJsonMapper;
	}

	/**
	 * Creates a default {@link McpJsonMapper} instance using the {@link ServiceLoader}
	 * mechanism. The default mapper is resolved by loading the first available
	 * {@link McpJsonMapperSupplier} implementation on the classpath.
	 * @return the default {@link McpJsonMapper} instance
	 * @throws IllegalStateException if no default {@link McpJsonMapper} implementation is
	 * found
	 */
	static McpJsonMapper createDefaultMapper() {
		AtomicReference<IllegalStateException> ex = new AtomicReference<>();
		ServiceLoader<McpJsonMapperSupplier> loader = ServiceLoader.load(McpJsonMapperSupplier.class);
		Iterator<McpJsonMapperSupplier> iterator = loader.iterator();

		while (iterator.hasNext()) {
			McpJsonMapperSupplier supplier = null;
			try {
				supplier = iterator.next();
			} catch (Exception e) {
				addException(ex, e);
				continue;
			}

			if (supplier != null) {
				try {
					McpJsonMapper mapper = supplier.get();
					if (mapper != null) {
						return mapper;
					}
				} catch (Exception e) {
					addException(ex, e);
				}
			}
		}

		if (ex.get() != null) {
			throw ex.get();
		} else {
			throw new IllegalStateException("No default McpJsonMapper implementation found");
		}
	}

	private static void addException(AtomicReference<IllegalStateException> ref, Exception toAdd) {
		IllegalStateException existing = ref.get();
		if (existing == null) {
			ref.set(new IllegalStateException("Failed to initialize default McpJsonMapper", toAdd));
		} else {
			existing.addSuppressed(toAdd);
		}
	}

}