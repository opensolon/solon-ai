/*
 * Copyright 2025-2025 the original author or authors.
 */

package com.agentclientprotocol.sdk.json;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Jackson-based supplier of {@link AcpJsonMapper} instances. Registered via
 * {@link java.util.ServiceLoader} as the default implementation.
 *
 * @author Mark Pollack
 */
public class JacksonAcpJsonMapperSupplier implements AcpJsonMapperSupplier {

	@Override
	public AcpJsonMapper get() {
		return new JacksonAcpJsonMapper(new ObjectMapper());
	}

}
