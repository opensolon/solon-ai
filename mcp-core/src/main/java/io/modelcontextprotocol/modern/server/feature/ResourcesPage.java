/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.modern.server.feature;

import io.modelcontextprotocol.modern.McpSchema.CacheScope;
import io.modelcontextprotocol.modern.McpSchema.Resource;
import io.modelcontextprotocol.util.Assert;

import java.util.List;
import java.util.Objects;

/**
 * A page of {@code resources/list}. See {@link ToolsPage} for the caching-hint
 * convention.
 *
 * @author Dariusz Jędrzejczyk
 */
public final class ResourcesPage {

	private final List<Resource> resources;

	private final String nextCursor;

	private final Long ttlMs;

	private final CacheScope cacheScope;

	public ResourcesPage(List<Resource> resources, String nextCursor, Long ttlMs, CacheScope cacheScope) {
		Assert.isTrue(ttlMs == null || ttlMs >= 0, "ttlMs must not be negative");
		this.resources = resources;
		this.nextCursor = nextCursor;
		this.ttlMs = ttlMs;
		this.cacheScope = cacheScope;
	}

	public static ResourcesPage of(List<Resource> resources) {
		return new ResourcesPage(resources, null, null, null);
	}

	public List<Resource> resources() {
		return this.resources;
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
		if (!(o instanceof ResourcesPage)) {
			return false;
		}
		ResourcesPage that = (ResourcesPage) o;
		return Objects.equals(this.resources, that.resources) && Objects.equals(this.nextCursor, that.nextCursor)
				&& Objects.equals(this.ttlMs, that.ttlMs) && Objects.equals(this.cacheScope, that.cacheScope);
	}

	@Override
	public int hashCode() {
		return Objects.hash(this.resources, this.nextCursor, this.ttlMs, this.cacheScope);
	}

	@Override
	public String toString() {
		return "ResourcesPage[resources=" + this.resources + ", nextCursor=" + this.nextCursor + ", ttlMs="
				+ this.ttlMs + ", cacheScope=" + this.cacheScope + "]";
	}

}
