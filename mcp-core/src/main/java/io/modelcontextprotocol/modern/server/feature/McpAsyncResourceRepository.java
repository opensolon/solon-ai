/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.modern.server.feature;

import io.modelcontextprotocol.modern.McpException;
import io.modelcontextprotocol.modern.McpSchema.ReadResourceOutcome;
import io.modelcontextprotocol.modern.McpSchema.ReadResourceRequest;
import io.modelcontextprotocol.modern.server.McpAsyncResponse;
import io.modelcontextprotocol.modern.server.McpRequestContext;
import reactor.core.publisher.Mono;

import java.util.Collections;

/**
 * User-implemented catalogue of resources and resource templates.
 *
 * @author Dariusz Jędrzejczyk
 */
public interface McpAsyncResourceRepository {

	/**
	 * List (a page of) the resources. An unrecognized cursor is an
	 * {@link McpException#invalidParams(String) invalid-params error}; the same holds for
	 * {@link #listTemplates}.
	 */
	Mono<ResourcesPage> list(McpRequestContext ctx, String cursor);

	default Mono<ResourceTemplatesPage> listTemplates(McpRequestContext ctx, String cursor) {
		return Mono.just(ResourceTemplatesPage.of(Collections.emptyList()));
	}

	/**
	 * Answer a {@code resources/read}. An unknown resource is an
	 * {@link McpException#invalidParams(String, Object) invalid-params error} carrying
	 * {@code {"uri": ...}} as data.
	 */
	Mono<McpAsyncResponse<ReadResourceOutcome>> read(McpRequestContext ctx, ReadResourceRequest request);

	/**
	 * Whether {@code resources/updated} is reported for individual resources, advertised
	 * as {@code resources.subscribe} when {@code subscriptions/listen} is registered.
	 */
	default boolean supportsSubscribe() {
		return false;
	}

}
