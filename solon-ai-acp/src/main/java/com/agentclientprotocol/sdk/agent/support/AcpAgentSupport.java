/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.agent.support;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import com.agentclientprotocol.sdk.agent.AcpAgent;
import com.agentclientprotocol.sdk.agent.AcpSyncAgent;
import com.agentclientprotocol.sdk.agent.PromptContext;
import com.agentclientprotocol.sdk.agent.SyncPromptContext;
import com.agentclientprotocol.sdk.agent.support.handler.DirectResponseHandler;
import com.agentclientprotocol.sdk.agent.support.handler.MonoHandler;
import com.agentclientprotocol.sdk.agent.support.handler.ReturnValueHandler;
import com.agentclientprotocol.sdk.agent.support.handler.ReturnValueHandlerComposite;
import com.agentclientprotocol.sdk.agent.support.handler.StringToPromptResponseHandler;
import com.agentclientprotocol.sdk.agent.support.handler.VoidHandler;
import com.agentclientprotocol.sdk.agent.support.interceptor.AcpInterceptor;
import com.agentclientprotocol.sdk.agent.support.interceptor.InterceptorChain;
import com.agentclientprotocol.sdk.agent.support.resolver.ArgumentResolver;
import com.agentclientprotocol.sdk.agent.support.resolver.ArgumentResolverComposite;
import com.agentclientprotocol.sdk.agent.support.resolver.CancelNotificationResolver;
import com.agentclientprotocol.sdk.agent.support.resolver.CapabilitiesResolver;
import com.agentclientprotocol.sdk.agent.support.resolver.InitializeRequestResolver;
import com.agentclientprotocol.sdk.agent.support.resolver.LoadSessionRequestResolver;
import com.agentclientprotocol.sdk.agent.support.resolver.NewSessionRequestResolver;
import com.agentclientprotocol.sdk.agent.support.resolver.PromptContextResolver;
import com.agentclientprotocol.sdk.agent.support.resolver.PromptRequestResolver;
import com.agentclientprotocol.sdk.agent.support.resolver.SessionIdResolver;
import com.agentclientprotocol.sdk.agent.support.resolver.SetSessionModeRequestResolver;
import com.agentclientprotocol.sdk.agent.support.resolver.SetSessionModelRequestResolver;
import com.agentclientprotocol.sdk.annotation.Cancel;
import com.agentclientprotocol.sdk.annotation.Initialize;
import com.agentclientprotocol.sdk.annotation.LoadSession;
import com.agentclientprotocol.sdk.annotation.NewSession;
import com.agentclientprotocol.sdk.annotation.Prompt;
import com.agentclientprotocol.sdk.annotation.SetSessionMode;
import com.agentclientprotocol.sdk.annotation.SetSessionModel;
import com.agentclientprotocol.sdk.capabilities.NegotiatedCapabilities;
import com.agentclientprotocol.sdk.spec.AcpAgentTransport;
import com.agentclientprotocol.sdk.spec.AcpSchema.CancelNotification;
import com.agentclientprotocol.sdk.spec.AcpSchema.InitializeRequest;
import com.agentclientprotocol.sdk.spec.AcpSchema.InitializeResponse;
import com.agentclientprotocol.sdk.spec.AcpSchema.LoadSessionRequest;
import com.agentclientprotocol.sdk.spec.AcpSchema.LoadSessionResponse;
import com.agentclientprotocol.sdk.spec.AcpSchema.NewSessionRequest;
import com.agentclientprotocol.sdk.spec.AcpSchema.NewSessionResponse;
import com.agentclientprotocol.sdk.spec.AcpSchema.PromptRequest;
import com.agentclientprotocol.sdk.spec.AcpSchema.PromptResponse;
import com.agentclientprotocol.sdk.spec.AcpSchema.SetSessionModeRequest;
import com.agentclientprotocol.sdk.spec.AcpSchema.SetSessionModeResponse;
import com.agentclientprotocol.sdk.spec.AcpSchema.SetSessionModelRequest;
import com.agentclientprotocol.sdk.spec.AcpSchema.SetSessionModelResponse;

import lombok.var;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Bootstrap class for annotation-based ACP agents.
 *
 * <p>This class provides a fluent builder API to configure and run
 * annotation-based agents without requiring Spring or other frameworks.
 *
 * <p>Example usage:
 * <pre>{@code
 * @AcpAgent
 * class MyAgent {
 *     @Initialize
 *     InitializeResponse init(InitializeRequest req) {
 *         return InitializeResponse.ok();
 *     }
 *
 *     @Prompt
 *     PromptResponse prompt(PromptRequest req, SyncPromptContext ctx) {
 *         ctx.sendMessage("Hello!");
 *         return PromptResponse.endTurn();
 *     }
 * }
 *
 * // Bootstrap
 * AcpAgentSupport.create(new MyAgent())
 *     .transport(StdioAcpAgentTransport.create())
 *     .run();
 * }</pre>
 *
 * @author Mark Pollack
 * @since 1.0.0
 */
public class AcpAgentSupport {

	private static final Logger log = LoggerFactory.getLogger(AcpAgentSupport.class);

	private final Map<String, AcpHandlerMethod> handlers;

	private final ArgumentResolverComposite argumentResolvers;

	private final ReturnValueHandlerComposite returnValueHandlers;

	private final List<AcpInterceptor> interceptors;

	private final AcpSyncAgent agent;

	private AcpAgentSupport(Builder builder) {
		this.handlers = builder.handlers;
		this.argumentResolvers = builder.argumentResolvers;
		this.returnValueHandlers = builder.returnValueHandlers;
		this.interceptors = builder.interceptors;

		// Build the underlying sync agent
		var agentBuilder = AcpAgent.sync(builder.transport)
				.requestTimeout(builder.requestTimeout);

		// Wire discovered handlers to the agent builder
		wireHandlers(agentBuilder);

		this.agent = agentBuilder.build();
	}

	/**
	 * Create a new builder for an agent instance.
	 * @param agentInstance the annotated agent instance
	 * @return a new builder
	 */
	public static Builder create(Object agentInstance) {
		return new Builder().agent(agentInstance);
	}

	/**
	 * Create a new builder for an agent class (requires no-arg constructor).
	 * @param agentClass the annotated agent class
	 * @return a new builder
	 */
	public static Builder create(Class<?> agentClass) {
		return new Builder().agent(agentClass);
	}

	/**
	 * Create a new empty builder.
	 * @return a new builder
	 */
	public static Builder builder() {
		return new Builder();
	}

	/**
	 * Start the agent (non-blocking).
	 */
	public void start() {
		log.info("Starting annotation-based ACP agent");
		agent.start();
	}

	/**
	 * Run the agent (blocking until close).
	 */
	public void run() {
		start();
		agent.await();
	}

	/**
	 * Close the agent gracefully.
	 */
	public void close() {
		log.info("Closing annotation-based ACP agent");
		agent.closeGracefully();
	}

	/**
	 * Get the underlying sync agent.
	 * @return the sync agent
	 */
	public AcpSyncAgent getAgent() {
		return agent;
	}

	private void wireHandlers(AcpAgent.SyncAgentBuilder agentBuilder) {
		// Initialize handler
		AcpHandlerMethod initHandler = handlers.get("initialize");
		if (initHandler != null) {
			agentBuilder.initializeHandler(req -> invokeHandler(initHandler, req, null, null, null));
		}
		else {
			// Default initialize handler
			agentBuilder.initializeHandler(req -> InitializeResponse.ok());
		}

		// NewSession handler
		AcpHandlerMethod newSessionHandler = handlers.get("session/new");
		if (newSessionHandler != null) {
			agentBuilder.newSessionHandler(req -> invokeHandler(newSessionHandler, req, null, null, null));
		}
		else {
			// Default new session handler
			agentBuilder.newSessionHandler(req -> new NewSessionResponse(
					java.util.UUID.randomUUID().toString(), null, null));
		}

		// LoadSession handler
		AcpHandlerMethod loadSessionHandler = handlers.get("session/load");
		if (loadSessionHandler != null) {
			agentBuilder.loadSessionHandler(req -> invokeHandler(loadSessionHandler, req, req.sessionId(), null, null));
		}

		// Prompt handler
		AcpHandlerMethod promptHandler = handlers.get("session/prompt");
		if (promptHandler != null) {
			agentBuilder.promptHandler((req, syncContext) -> {
				NegotiatedCapabilities caps = syncContext.getClientCapabilities();
				return invokeHandler(promptHandler, req, req.sessionId(), syncContext, caps);
			});
		}

		// SetSessionMode handler
		AcpHandlerMethod setModeHandler = handlers.get("session/set_mode");
		if (setModeHandler != null) {
			agentBuilder.setSessionModeHandler(req -> invokeHandler(setModeHandler, req, req.sessionId(), null, null));
		}

		// SetSessionModel handler
		AcpHandlerMethod setModelHandler = handlers.get("session/set_model");
		if (setModelHandler != null) {
			agentBuilder.setSessionModelHandler(req -> invokeHandler(setModelHandler, req, req.sessionId(), null, null));
		}

		// Cancel handler
		AcpHandlerMethod cancelHandler = handlers.get("session/cancel");
		if (cancelHandler != null) {
			agentBuilder.cancelHandler(notification -> {
				invokeHandler(cancelHandler, notification, notification.sessionId(), null, null);
			});
		}
	}

	@SuppressWarnings("unchecked")
	private <T> T invokeHandler(AcpHandlerMethod handler, Object request, String sessionId,
			SyncPromptContext syncContext, NegotiatedCapabilities capabilities) {

		AcpInvocationContext context = AcpInvocationContext.builder()
				.acpMethod(handler.getAcpMethod())
				.request(request)
				.sessionId(sessionId)
				.syncPromptContext(syncContext)
				.capabilities(capabilities)
				.build();

		InterceptorChain chain = new InterceptorChain(interceptors);

		try {
			// Pre-invoke
			if (!chain.applyPreInvoke(context)) {
				return null;
			}

			// Resolve arguments
			Object[] args = resolveArguments(handler, context);

			// Invoke
			Object result = handler.invoke(args);

			// Post-invoke
			result = chain.applyPostInvoke(context, result);

			// Handle return value
			return (T) returnValueHandlers.handleReturnValue(result, handler.getReturnType(), context);

		}
		catch (Exception e) {
			// On-error
			Object replacement = chain.applyOnError(context, e);
			if (replacement != null) {
				return (T) replacement;
			}
			if (e instanceof RuntimeException) {
				throw (RuntimeException)e;
			}
			throw new RuntimeException(e);
		}
		finally {
			chain.triggerAfterCompletion(context, null);
		}
	}

	private Object[] resolveArguments(AcpHandlerMethod handler, AcpInvocationContext context) {
		AcpMethodParameter[] params = handler.getParameters();
		Object[] args = new Object[params.length];
		for (int i = 0; i < params.length; i++) {
			args[i] = argumentResolvers.resolveArgument(params[i], context);
		}
		return args;
	}

	// ========== BUILDER ==========

	/**
	 * Builder for AcpAgentSupport.
	 */
	public static class Builder {

		private final Map<String, AcpHandlerMethod> handlers = new HashMap<>();

		private final ArgumentResolverComposite argumentResolvers = new ArgumentResolverComposite();

		private final ReturnValueHandlerComposite returnValueHandlers = new ReturnValueHandlerComposite();

		private final List<AcpInterceptor> interceptors = new ArrayList<>();

		private AcpAgentTransport transport;

		private Duration requestTimeout = Duration.ofSeconds(30);

		/**
		 * Register an agent instance.
		 * @param agentInstance the annotated agent instance
		 * @return this builder
		 */
		public Builder agent(Object agentInstance) {
			discoverHandlers(agentInstance.getClass(), () -> agentInstance);
			return this;
		}

		/**
		 * Register an agent class (must have no-arg constructor).
		 * @param agentClass the annotated agent class
		 * @return this builder
		 */
		public Builder agent(Class<?> agentClass) {
			discoverHandlers(agentClass, () -> {
				try {
					return agentClass.getDeclaredConstructor().newInstance();
				}
				catch (Exception e) {
					throw new RuntimeException("Cannot instantiate " + agentClass, e);
				}
			});
			return this;
		}

		/**
		 * Register an agent class with factory.
		 * @param agentClass the annotated agent class
		 * @param factory supplier for agent instances
		 * @param <T> the agent type
		 * @return this builder
		 */
		public <T> Builder agent(Class<T> agentClass, Supplier<T> factory) {
			discoverHandlers(agentClass, factory::get);
			return this;
		}

		/**
		 * Set the transport.
		 * @param transport the agent transport
		 * @return this builder
		 */
		public Builder transport(AcpAgentTransport transport) {
			this.transport = transport;
			return this;
		}

		/**
		 * Set the request timeout.
		 * @param timeout the timeout duration
		 * @return this builder
		 */
		public Builder requestTimeout(Duration timeout) {
			this.requestTimeout = timeout;
			return this;
		}

		/**
		 * Add an interceptor.
		 * @param interceptor the interceptor
		 * @return this builder
		 */
		public Builder interceptor(AcpInterceptor interceptor) {
			this.interceptors.add(interceptor);
			return this;
		}

		/**
		 * Add a custom argument resolver.
		 * @param resolver the resolver
		 * @return this builder
		 */
		public Builder argumentResolver(ArgumentResolver resolver) {
			this.argumentResolvers.addResolver(resolver);
			return this;
		}

		/**
		 * Add a custom return value handler.
		 * @param handler the handler
		 * @return this builder
		 */
		public Builder returnValueHandler(ReturnValueHandler handler) {
			this.returnValueHandlers.addHandler(handler);
			return this;
		}

		/**
		 * Build the AcpAgentSupport instance.
		 * @return the configured instance
		 */
		public AcpAgentSupport build() {
			if (transport == null) {
				throw new IllegalStateException("Transport must be configured");
			}

			// Add default resolvers (custom ones added first take precedence)
			addDefaultResolvers();
			addDefaultReturnValueHandlers();

			return new AcpAgentSupport(this);
		}

		private void discoverHandlers(Class<?> agentClass, Supplier<Object> beanSupplier) {
			if (!agentClass.isAnnotationPresent(
					com.agentclientprotocol.sdk.annotation.AcpAgent.class)) {
				throw new IllegalArgumentException(
						"Class must be annotated with @AcpAgent: " + agentClass.getName());
			}

			for (Method method : agentClass.getDeclaredMethods()) {
				if (method.isAnnotationPresent(Initialize.class)) {
					handlers.put("initialize", new AcpHandlerMethod(beanSupplier, method, "initialize"));
					log.debug("Discovered @Initialize handler: {}", method.getName());
				}
				if (method.isAnnotationPresent(NewSession.class)) {
					handlers.put("session/new", new AcpHandlerMethod(beanSupplier, method, "session/new"));
					log.debug("Discovered @NewSession handler: {}", method.getName());
				}
				if (method.isAnnotationPresent(LoadSession.class)) {
					handlers.put("session/load", new AcpHandlerMethod(beanSupplier, method, "session/load"));
					log.debug("Discovered @LoadSession handler: {}", method.getName());
				}
				if (method.isAnnotationPresent(Prompt.class)) {
					handlers.put("session/prompt", new AcpHandlerMethod(beanSupplier, method, "session/prompt"));
					log.debug("Discovered @Prompt handler: {}", method.getName());
				}
				if (method.isAnnotationPresent(SetSessionMode.class)) {
					handlers.put("session/set_mode", new AcpHandlerMethod(beanSupplier, method, "session/set_mode"));
					log.debug("Discovered @SetSessionMode handler: {}", method.getName());
				}
				if (method.isAnnotationPresent(SetSessionModel.class)) {
					handlers.put("session/set_model", new AcpHandlerMethod(beanSupplier, method, "session/set_model"));
					log.debug("Discovered @SetSessionModel handler: {}", method.getName());
				}
				if (method.isAnnotationPresent(Cancel.class)) {
					handlers.put("session/cancel", new AcpHandlerMethod(beanSupplier, method, "session/cancel"));
					log.debug("Discovered @Cancel handler: {}", method.getName());
				}
			}
		}

		private void addDefaultResolvers() {
			// Built-in resolvers (order matters - first match wins)
			// Custom resolvers added via builder go first
			argumentResolvers.addResolver(new InitializeRequestResolver());
			argumentResolvers.addResolver(new NewSessionRequestResolver());
			argumentResolvers.addResolver(new LoadSessionRequestResolver());
			argumentResolvers.addResolver(new PromptRequestResolver());
			argumentResolvers.addResolver(new SetSessionModeRequestResolver());
			argumentResolvers.addResolver(new SetSessionModelRequestResolver());
			argumentResolvers.addResolver(new CancelNotificationResolver());
			argumentResolvers.addResolver(new PromptContextResolver());
			argumentResolvers.addResolver(new SessionIdResolver());
			argumentResolvers.addResolver(new CapabilitiesResolver());
		}

		private void addDefaultReturnValueHandlers() {
			// Built-in handlers (order matters - first match wins)
			// Custom handlers added via builder go first
			returnValueHandlers.addHandler(new DirectResponseHandler());
			returnValueHandlers.addHandler(new StringToPromptResponseHandler());
			returnValueHandlers.addHandler(new VoidHandler());

			// Async handlers (Reactor is available since acp-core depends on it)
			returnValueHandlers.addHandler(new MonoHandler());
		}

	}

}
