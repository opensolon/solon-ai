/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.agent.support;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import com.agentclientprotocol.sdk.agent.PromptContext;
import com.agentclientprotocol.sdk.agent.SyncPromptContext;
import com.agentclientprotocol.sdk.capabilities.NegotiatedCapabilities;

/**
 * Context for a single method invocation, holding request data and
 * runtime information needed by argument resolvers and interceptors.
 *
 * @author Mark Pollack
 * @since 1.0.0
 */
public final class AcpInvocationContext {

	private final String acpMethod;

	private final Object request;

	private final String sessionId;

	private final PromptContext promptContext;

	private final SyncPromptContext syncPromptContext;

	private final NegotiatedCapabilities capabilities;

	private final Map<String, Object> attributes = new HashMap<>();

	private AcpInvocationContext(Builder builder) {
		this.acpMethod = builder.acpMethod;
		this.request = builder.request;
		this.sessionId = builder.sessionId;
		this.promptContext = builder.promptContext;
		this.syncPromptContext = builder.syncPromptContext;
		this.capabilities = builder.capabilities;
	}

	/**
	 * Create a new builder.
	 * @return a new builder
	 */
	public static Builder builder() {
		return new Builder();
	}

	/**
	 * Get the ACP method name (e.g., "initialize", "session/prompt").
	 * @return the method name
	 */
	public String getAcpMethod() {
		return acpMethod;
	}

	/**
	 * Get the request object.
	 * @return the request
	 */
	public Object getRequest() {
		return request;
	}

	/**
	 * Get the request cast to a specific type.
	 * @param type the expected type
	 * @param <T> the type
	 * @return the typed request
	 * @throws ClassCastException if the request is not of the expected type
	 */
	@SuppressWarnings("unchecked")
	public <T> T getRequest(Class<T> type) {
		return (T) request;
	}

	/**
	 * Get the session ID if available.
	 * @return optional session ID
	 */
	public Optional<String> getSessionId() {
		return Optional.ofNullable(sessionId);
	}

	/**
	 * Get the async prompt context if available.
	 * Only present for prompt handlers.
	 * @return optional prompt context
	 */
	public Optional<PromptContext> getPromptContext() {
		return Optional.ofNullable(promptContext);
	}

	/**
	 * Get the sync prompt context if available.
	 * Only present for prompt handlers.
	 * @return optional sync prompt context
	 */
	public Optional<SyncPromptContext> getSyncPromptContext() {
		return Optional.ofNullable(syncPromptContext);
	}

	/**
	 * Get the negotiated capabilities.
	 * @return optional capabilities
	 */
	public Optional<NegotiatedCapabilities> getCapabilities() {
		return Optional.ofNullable(capabilities);
	}

	/**
	 * Set a custom attribute.
	 * @param name attribute name
	 * @param value attribute value
	 */
	public void setAttribute(String name, Object value) {
		attributes.put(name, value);
	}

	/**
	 * Get a custom attribute.
	 * @param name attribute name
	 * @return optional attribute value
	 */
	public Optional<Object> getAttribute(String name) {
		return Optional.ofNullable(attributes.get(name));
	}

	/**
	 * Get a typed custom attribute.
	 * @param name attribute name
	 * @param type expected type
	 * @param <T> the type
	 * @return optional typed attribute value
	 */
	@SuppressWarnings("unchecked")
	public <T> Optional<T> getAttribute(String name, Class<T> type) {
		Object value = attributes.get(name);
		if (value != null && type.isInstance(value)) {
			return Optional.of((T) value);
		}
		return Optional.empty();
	}

	/**
	 * Builder for AcpInvocationContext.
	 */
	public static class Builder {

		private String acpMethod;

		private Object request;

		private String sessionId;

		private PromptContext promptContext;

		private SyncPromptContext syncPromptContext;

		private NegotiatedCapabilities capabilities;

		public Builder acpMethod(String acpMethod) {
			this.acpMethod = acpMethod;
			return this;
		}

		public Builder request(Object request) {
			this.request = request;
			return this;
		}

		public Builder sessionId(String sessionId) {
			this.sessionId = sessionId;
			return this;
		}

		public Builder promptContext(PromptContext promptContext) {
			this.promptContext = promptContext;
			return this;
		}

		public Builder syncPromptContext(SyncPromptContext syncPromptContext) {
			this.syncPromptContext = syncPromptContext;
			return this;
		}

		public Builder capabilities(NegotiatedCapabilities capabilities) {
			this.capabilities = capabilities;
			return this;
		}

		public AcpInvocationContext build() {
			return new AcpInvocationContext(this);
		}

	}

}
