/*
 * Copyright 2025-2025 the original author or authors.
 */

package com.agentclientprotocol.sdk.json;

import java.util.function.Supplier;

/**
 * Strategy interface for providing an {@link AcpJsonMapper} implementation.
 *
 * <p>
 * Implementations are discovered at runtime via {@link java.util.ServiceLoader}.
 * To register an implementation, create a file at
 * {@code META-INF/services/com.agentclientprotocol.sdk.json.AcpJsonMapperSupplier}
 * containing the fully qualified class name of the supplier.
 * </p>
 *
 * @author Mark Pollack
 * @see AcpJsonMapper#createDefault()
 */
public interface AcpJsonMapperSupplier extends Supplier<AcpJsonMapper> {

}
