/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.modern.server.feature;

import io.modelcontextprotocol.modern.McpSchema.CacheScope;
import io.modelcontextprotocol.modern.McpSchema.ResourceTemplate;
import io.modelcontextprotocol.util.Assert;

import java.util.List;
import java.util.Objects;

/**
 * A page of {@code resources/templates/list}.
 *
 * @author Dariusz Jędrzejczyk
 */
public final class ResourceTemplatesPage {

	private final List<ResourceTemplate> resourceTemplates;

	private final String nextCursor;

	private final Long ttlMs;

	private final CacheScope cacheScope;

	public ResourceTemplatesPage(List<ResourceTemplate> resourceTemplates, String nextCursor, Long ttlMs,
			CacheScope cacheScope) {
		Assert.isTrue(ttlMs == null || ttlMs >= 0, "ttlMs must not be negative");
		this.resourceTemplates = resourceTemplates;
		this.nextCursor = nextCursor;
		this.ttlMs = ttlMs;
		this.cacheScope = cacheScope;
	}

	public static ResourceTemplatesPage of(List<ResourceTemplate> resourceTemplates) {
		return new ResourceTemplatesPage(resourceTemplates, null, null, null);
	}

	public List<ResourceTemplate> resourceTemplates() {
		return this.resourceTemplates;
	}

	public String nextCursor() {
		return this.nextCursor;
	}

	public Long ttlMs() {
		return this.ttlMs;
	}

	public CacheScope cacheScope() {
		return this.cacheScope;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		if (!(o instanceof ResourceTemplatesPage)) {
			return false;
		}
		ResourceTemplatesPage that = (ResourceTemplatesPage) o;
		return Objects.equals(this.resourceTemplates, that.resourceTemplates)
				&& Objects.equals(this.nextCursor, that.nextCursor) && Objects.equals(this.ttlMs, that.ttlMs)
				&& Objects.equals(this.cacheScope, that.cacheScope);
	}

	@Override
	public int hashCode() {
		return Objects.hash(this.resourceTemplates, this.nextCursor, this.ttlMs, this.cacheScope);
	}

	@Override
	public String toString() {
		return "ResourceTemplatesPage[resourceTemplates=" + this.resourceTemplates + ", nextCursor=" + this.nextCursor
				+ ", ttlMs=" + this.ttlMs + ", cacheScope=" + this.cacheScope + "]";
	}

}
