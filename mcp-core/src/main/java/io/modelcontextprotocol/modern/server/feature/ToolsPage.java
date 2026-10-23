/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.modern.server.feature;

import io.modelcontextprotocol.modern.McpSchema.CacheScope;
import io.modelcontextprotocol.modern.McpSchema.Tool;
import io.modelcontextprotocol.util.Assert;

import java.util.List;
import java.util.Objects;

/**
 * A page of {@code tools/list}. {@code ttlMs}/{@code cacheScope} are optional; when
 * absent, {@code ToolsFeature} substitutes the server's cache defaults. Every page of one
 * listing must carry the same {@code cacheScope}.
 *
 * @author Dariusz Jędrzejczyk
 */
public final class ToolsPage {

	private final List<Tool> tools;

	private final String nextCursor;

	private final Long ttlMs;

	private final CacheScope cacheScope;

	public ToolsPage(List<Tool> tools, String nextCursor, Long ttlMs, CacheScope cacheScope) {
		Assert.isTrue(ttlMs == null || ttlMs >= 0, "ttlMs must not be negative");
		this.tools = tools;
		this.nextCursor = nextCursor;
		this.ttlMs = ttlMs;
		this.cacheScope = cacheScope;
	}

	public static ToolsPage of(List<Tool> tools) {
		return new ToolsPage(tools, null, null, null);
	}

	public List<Tool> tools() {
		return this.tools;
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
		if (!(o instanceof ToolsPage)) {
			return false;
		}
		ToolsPage that = (ToolsPage) o;
		return Objects.equals(this.tools, that.tools) && Objects.equals(this.nextCursor, that.nextCursor)
				&& Objects.equals(this.ttlMs, that.ttlMs) && Objects.equals(this.cacheScope, that.cacheScope);
	}

	@Override
	public int hashCode() {
		return Objects.hash(this.tools, this.nextCursor, this.ttlMs, this.cacheScope);
	}

	@Override
	public String toString() {
		return "ToolsPage[tools=" + this.tools + ", nextCursor=" + this.nextCursor + ", ttlMs=" + this.ttlMs
				+ ", cacheScope=" + this.cacheScope + "]";
	}

}
