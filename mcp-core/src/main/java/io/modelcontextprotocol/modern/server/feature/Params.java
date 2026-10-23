/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.modern.server.feature;

import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.modern.McpException;
import io.modelcontextprotocol.util.SelfValidating;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

final class Params {

	private static final Logger logger = LoggerFactory.getLogger(Params.class);

	private Params() {
	}

	static <T> Mono<T> decode(McpJsonMapper jsonMapper, Object params, Class<T> type) {
		try {
			T decoded = jsonMapper.convertValue(params, type);
			// Mappers that bypass constructors (e.g. Gson instantiates via Unsafe) skip the
			// canonical constructor's validation, so replay it here.
			if (decoded instanceof SelfValidating) {
				((SelfValidating) decoded).validate();
			}
			return Mono.just(decoded);
		}
		catch (RuntimeException ex) {
			logger.debug("Malformed params for {}", type.getSimpleName(), ex);
			return Mono.error(McpException.invalidParams("Malformed " + type.getSimpleName()));
		}
	}

}
