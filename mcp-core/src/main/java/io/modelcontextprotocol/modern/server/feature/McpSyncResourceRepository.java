/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.modern.server.feature;

import io.modelcontextprotocol.modern.McpSchema.ReadResourceOutcome;
import io.modelcontextprotocol.modern.McpSchema.ReadResourceRequest;
import io.modelcontextprotocol.modern.server.McpRequestContext;
import io.modelcontextprotocol.modern.server.McpSyncResponse;

import java.util.Collections;

/**
 * The blocking counterpart of {@link McpAsyncResourceRepository}.
 *
 * @author Dariusz Jędrzejczyk
 */
public interface McpSyncResourceRepository {

	ResourcesPage list(McpRequestContext ctx, String cursor);

	default ResourceTemplatesPage listTemplates(McpRequestContext ctx, String cursor) {
		return ResourceTemplatesPage.of(Collections.emptyList());
	}

	McpSyncResponse<ReadResourceOutcome> read(McpRequestContext ctx, ReadResourceRequest request);

	/** See {@link McpAsyncResourceRepository#supportsSubscribe()}. */
	default boolean supportsSubscribe() {
		return false;
	}

}
