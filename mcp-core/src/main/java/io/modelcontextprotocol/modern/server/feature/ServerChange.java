/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.modern.server.feature;

import java.util.Objects;

/**
 * A change a {@link McpChangePublisher} may emit for {@code subscriptions/listen} to
 * forward. Types it does not recognize are not forwarded.
 *
 * @author Dariusz Jędrzejczyk
 */
public interface ServerChange {

	final class ToolsListChanged implements ServerChange {

		@Override
		public boolean equals(Object o) {
			if (this == o) {
				return true;
			}
			return o instanceof ToolsListChanged;
		}

		@Override
		public int hashCode() {
			return Objects.hash();
		}

		@Override
		public String toString() {
			return "ToolsListChanged[]";
		}

	}

	final class PromptsListChanged implements ServerChange {

		@Override
		public boolean equals(Object o) {
			if (this == o) {
				return true;
			}
			return o instanceof PromptsListChanged;
		}

		@Override
		public int hashCode() {
			return Objects.hash();
		}

		@Override
		public String toString() {
			return "PromptsListChanged[]";
		}

	}

	final class ResourcesListChanged implements ServerChange {

		@Override
		public boolean equals(Object o) {
			if (this == o) {
				return true;
			}
			return o instanceof ResourcesListChanged;
		}

		@Override
		public int hashCode() {
			return Objects.hash();
		}

		@Override
		public String toString() {
			return "ResourcesListChanged[]";
		}

	}

	final class ResourceUpdated implements ServerChange {

		private final String uri;

		public ResourceUpdated(String uri) {
			this.uri = uri;
		}

		public String uri() {
			return this.uri;
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) {
				return true;
			}
			if (!(o instanceof ResourceUpdated)) {
				return false;
			}
			ResourceUpdated that = (ResourceUpdated) o;
			return Objects.equals(this.uri, that.uri);
		}

		@Override
		public int hashCode() {
			return Objects.hash(this.uri);
		}

		@Override
		public String toString() {
			return "ResourceUpdated[uri=" + this.uri + "]";
		}

	}

}
