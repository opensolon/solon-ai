/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.modern.server.feature;

import io.modelcontextprotocol.modern.McpSchema.CacheScope;
import io.modelcontextprotocol.modern.McpSchema.Prompt;
import io.modelcontextprotocol.util.Assert;

import java.util.List;
import java.util.Objects;

/**
 * A page of {@code prompts/list}.
 *
 * @author Dariusz Jędrzejczyk
 */
public final class PromptsPage {

	private final List<Prompt> prompts;

	private final String nextCursor;

	private final Long ttlMs;

	private final CacheScope cacheScope;

	public PromptsPage(List<Prompt> prompts, String nextCursor, Long ttlMs, CacheScope cacheScope) {
		Assert.isTrue(ttlMs == null || ttlMs >= 0, "ttlMs must not be negative");
		this.prompts = prompts;
		this.nextCursor = nextCursor;
		this.ttlMs = ttlMs;
		this.cacheScope = cacheScope;
	}

	public static PromptsPage of(List<Prompt> prompts) {
		return new PromptsPage(prompts, null, null, null);
	}

	public List<Prompt> prompts() {
		return this.prompts;
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
		if (!(o instanceof PromptsPage)) {
			return false;
		}
		PromptsPage that = (PromptsPage) o;
		return Objects.equals(this.prompts, that.prompts) && Objects.equals(this.nextCursor, that.nextCursor)
				&& Objects.equals(this.ttlMs, that.ttlMs) && Objects.equals(this.cacheScope, that.cacheScope);
	}

	@Override
	public int hashCode() {
		return Objects.hash(this.prompts, this.nextCursor, this.ttlMs, this.cacheScope);
	}

	@Override
	public String toString() {
		return "PromptsPage[prompts=" + this.prompts + ", nextCursor=" + this.nextCursor + ", ttlMs=" + this.ttlMs
				+ ", cacheScope=" + this.cacheScope + "]";
	}

}
