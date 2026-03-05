/*
 * Copyright 2024-2024 the original author or authors.
 */

package io.modelcontextprotocol.client;

import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.util.Assert;
import io.modelcontextprotocol.util.Utils;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Representation of features and capabilities for Model Context Protocol (MCP) clients.
 * This class provides two record types for managing client features:
 * <ul>
 * <li>{@link Async} for non-blocking operations with Project Reactor's Mono responses
 * <li>{@link Sync} for blocking operations with direct responses
 * </ul>
 *
 * <p>
 * Each feature specification includes:
 * <ul>
 * <li>Client implementation information and capabilities
 * <li>Root URI mappings for resource access
 * <li>Change notification handlers for tools, resources, and prompts
 * <li>Logging message consumers
 * <li>Message sampling handlers for request processing
 * </ul>
 *
 * <p>
 * The class supports conversion between synchronous and asynchronous specifications
 * through the {@link Async#fromSync} method, which ensures proper handling of blocking
 * operations in non-blocking contexts by scheduling them on a bounded elastic scheduler.
 *
 * @author Dariusz Jędrzejczyk
 * @see McpClient
 * @see McpSchema.Implementation
 * @see McpSchema.ClientCapabilities
 */
class McpClientFeatures {

	/**
	 * Asynchronous client features specification providing the capabilities and request
	 * and notification handlers.
	 *
	 */
	public static class Async {
		private McpSchema.Implementation clientInfo;
		private McpSchema.ClientCapabilities clientCapabilities;
		private Map<String, McpSchema.Root> roots;
		private List<Function<List<McpSchema.Tool>, Mono<Void>>> toolsChangeConsumers;
		private List<Function<List<McpSchema.Resource>, Mono<Void>>> resourcesChangeConsumers;
		private List<Function<List<McpSchema.ResourceContents>, Mono<Void>>> resourcesUpdateConsumers;
		private List<Function<List<McpSchema.Prompt>, Mono<Void>>> promptsChangeConsumers;
		private List<Function<McpSchema.LoggingMessageNotification, Mono<Void>>> loggingConsumers;
		private List<Function<McpSchema.ProgressNotification, Mono<Void>>> progressConsumers;
		private Function<McpSchema.CreateMessageRequest, Mono<McpSchema.CreateMessageResult>> samplingHandler;
		private Function<McpSchema.ElicitRequest, Mono<McpSchema.ElicitResult>> elicitationHandler;
		private boolean enableCallToolSchemaCaching;

		/**
		 * Create an instance and validate the arguments.

		 * @param clientInfo the client implementation information.
		 * @param clientCapabilities the client capabilities.
		 * @param roots the roots.
		 * @param toolsChangeConsumers the tools change consumers.
		 * @param resourcesChangeConsumers the resources change consumers.
		 * @param promptsChangeConsumers the prompts change consumers.
		 * @param loggingConsumers the logging consumers.
		 * @param progressConsumers the progress consumers.
		 * @param samplingHandler the sampling handler.
		 * @param elicitationHandler the elicitation handler.
		 * @param enableCallToolSchemaCaching whether to enable call tool schema caching.
		 */
		public Async(McpSchema.Implementation clientInfo, McpSchema.ClientCapabilities clientCapabilities,
					 Map<String, McpSchema.Root> roots,
					 List<Function<List<McpSchema.Tool>, Mono<Void>>> toolsChangeConsumers,
					 List<Function<List<McpSchema.Resource>, Mono<Void>>> resourcesChangeConsumers,
					 List<Function<List<McpSchema.ResourceContents>, Mono<Void>>> resourcesUpdateConsumers,
					 List<Function<List<McpSchema.Prompt>, Mono<Void>>> promptsChangeConsumers,
					 List<Function<McpSchema.LoggingMessageNotification, Mono<Void>>> loggingConsumers,
					 List<Function<McpSchema.ProgressNotification, Mono<Void>>> progressConsumers,
					 Function<McpSchema.CreateMessageRequest, Mono<McpSchema.CreateMessageResult>> samplingHandler,
					 Function<McpSchema.ElicitRequest, Mono<McpSchema.ElicitResult>> elicitationHandler,
					 boolean enableCallToolSchemaCaching) {

			Assert.notNull(clientInfo, "Client info must not be null");
			this.clientInfo = clientInfo;
			this.clientCapabilities = (clientCapabilities != null) ? clientCapabilities
					: new McpSchema.ClientCapabilities(null,
					!Utils.isEmpty(roots) ? new McpSchema.ClientCapabilities.RootCapabilities(false) : null,
					samplingHandler != null ? new McpSchema.ClientCapabilities.Sampling() : null,
					elicitationHandler != null ? new McpSchema.ClientCapabilities.Elicitation() : null);
			this.roots = roots != null ? new ConcurrentHashMap<>(roots) : new ConcurrentHashMap<>();

			this.toolsChangeConsumers = toolsChangeConsumers != null ? toolsChangeConsumers : new ArrayList<>();
			this.resourcesChangeConsumers = resourcesChangeConsumers != null ? resourcesChangeConsumers : new ArrayList<>();
			this.resourcesUpdateConsumers = resourcesUpdateConsumers != null ? resourcesUpdateConsumers : new ArrayList<>();
			this.promptsChangeConsumers = promptsChangeConsumers != null ? promptsChangeConsumers : new ArrayList<>();
			this.loggingConsumers = loggingConsumers != null ? loggingConsumers : new ArrayList<>();
			this.progressConsumers = progressConsumers != null ? progressConsumers : new ArrayList<>();
			this.samplingHandler = samplingHandler;
			this.elicitationHandler = elicitationHandler;
			this.enableCallToolSchemaCaching = enableCallToolSchemaCaching;
		}

		public McpSchema.Implementation clientInfo() {
			return clientInfo;
		}

		public McpSchema.ClientCapabilities clientCapabilities() {
			return clientCapabilities;
		}

		public Map<String, McpSchema.Root> roots() {
			return roots;
		}

		public List<Function<List<McpSchema.Tool>, Mono<Void>>> toolsChangeConsumers() {
			return toolsChangeConsumers;
		}

		public List<Function<List<McpSchema.Resource>, Mono<Void>>> resourcesChangeConsumers() {
			return resourcesChangeConsumers;
		}

		public List<Function<List<McpSchema.ResourceContents>, Mono<Void>>> resourcesUpdateConsumers() {
			return resourcesUpdateConsumers;
		}

		public List<Function<List<McpSchema.Prompt>, Mono<Void>>> promptsChangeConsumers() {
			return promptsChangeConsumers;
		}

		public List<Function<McpSchema.LoggingMessageNotification, Mono<Void>>> loggingConsumers() {
			return loggingConsumers;
		}

		public List<Function<McpSchema.ProgressNotification, Mono<Void>>> progressConsumers() {
			return progressConsumers;
		}

		public Function<McpSchema.CreateMessageRequest, Mono<McpSchema.CreateMessageResult>> samplingHandler() {
			return samplingHandler;
		}

		public Function<McpSchema.ElicitRequest, Mono<McpSchema.ElicitResult>> elicitationHandler() {
			return elicitationHandler;
		}

		public boolean enableCallToolSchemaCaching() {
			return enableCallToolSchemaCaching;
		}

		/**
		 * @deprecated Only exists for backwards-compatibility purposes.
		 */
		public Async(McpSchema.Implementation clientInfo, McpSchema.ClientCapabilities clientCapabilities,
					 Map<String, McpSchema.Root> roots,
					 List<Function<List<McpSchema.Tool>, Mono<Void>>> toolsChangeConsumers,
					 List<Function<List<McpSchema.Resource>, Mono<Void>>> resourcesChangeConsumers,
					 List<Function<List<McpSchema.ResourceContents>, Mono<Void>>> resourcesUpdateConsumers,
					 List<Function<List<McpSchema.Prompt>, Mono<Void>>> promptsChangeConsumers,
					 List<Function<McpSchema.LoggingMessageNotification, Mono<Void>>> loggingConsumers,
					 Function<McpSchema.CreateMessageRequest, Mono<McpSchema.CreateMessageResult>> samplingHandler,
					 Function<McpSchema.ElicitRequest, Mono<McpSchema.ElicitResult>> elicitationHandler) {
			this(clientInfo, clientCapabilities, roots, toolsChangeConsumers, resourcesChangeConsumers,
					resourcesUpdateConsumers, promptsChangeConsumers, loggingConsumers, Arrays.asList(), samplingHandler,
					elicitationHandler, false);
		}

		/**
		 * Convert a synchronous specification into an asynchronous one and provide
		 * blocking code offloading to prevent accidental blocking of the non-blocking
		 * transport.
		 * @param syncSpec a potentially blocking, synchronous specification.
		 * @return a specification which is protected from blocking calls specified by the
		 * user.
		 */
		public static Async fromSync(Sync syncSpec) {
			List<Function<List<McpSchema.Tool>, Mono<Void>>> toolsChangeConsumers = new ArrayList<>();
			for (Consumer<List<McpSchema.Tool>> consumer : syncSpec.toolsChangeConsumers()) {
				toolsChangeConsumers.add(t -> Mono.<Void>fromRunnable(() -> consumer.accept(t))
						.subscribeOn(Schedulers.boundedElastic()));
			}

			List<Function<List<McpSchema.Resource>, Mono<Void>>> resourcesChangeConsumers = new ArrayList<>();
			for (Consumer<List<McpSchema.Resource>> consumer : syncSpec.resourcesChangeConsumers()) {
				resourcesChangeConsumers.add(r -> Mono.<Void>fromRunnable(() -> consumer.accept(r))
						.subscribeOn(Schedulers.boundedElastic()));
			}

			List<Function<List<McpSchema.ResourceContents>, Mono<Void>>> resourcesUpdateConsumers = new ArrayList<>();
			for (Consumer<List<McpSchema.ResourceContents>> consumer : syncSpec.resourcesUpdateConsumers()) {
				resourcesUpdateConsumers.add(r -> Mono.<Void>fromRunnable(() -> consumer.accept(r))
						.subscribeOn(Schedulers.boundedElastic()));
			}

			List<Function<List<McpSchema.Prompt>, Mono<Void>>> promptsChangeConsumers = new ArrayList<>();
			for (Consumer<List<McpSchema.Prompt>> consumer : syncSpec.promptsChangeConsumers()) {
				promptsChangeConsumers.add(p -> Mono.<Void>fromRunnable(() -> consumer.accept(p))
						.subscribeOn(Schedulers.boundedElastic()));
			}

			List<Function<McpSchema.LoggingMessageNotification, Mono<Void>>> loggingConsumers = new ArrayList<>();
			for (Consumer<McpSchema.LoggingMessageNotification> consumer : syncSpec.loggingConsumers()) {
				loggingConsumers.add(l -> Mono.<Void>fromRunnable(() -> consumer.accept(l))
						.subscribeOn(Schedulers.boundedElastic()));
			}

			List<Function<McpSchema.ProgressNotification, Mono<Void>>> progressConsumers = new ArrayList<>();
			for (Consumer<McpSchema.ProgressNotification> consumer : syncSpec.progressConsumers()) {
				progressConsumers.add(l -> Mono.<Void>fromRunnable(() -> consumer.accept(l))
						.subscribeOn(Schedulers.boundedElastic()));
			}

			Function<McpSchema.CreateMessageRequest, Mono<McpSchema.CreateMessageResult>> samplingHandler = r -> Mono
					.fromCallable(() -> syncSpec.samplingHandler().apply(r))
					.subscribeOn(Schedulers.boundedElastic());

			Function<McpSchema.ElicitRequest, Mono<McpSchema.ElicitResult>> elicitationHandler = r -> Mono
					.fromCallable(() -> syncSpec.elicitationHandler().apply(r))
					.subscribeOn(Schedulers.boundedElastic());

			return new Async(syncSpec.clientInfo(), syncSpec.clientCapabilities(), syncSpec.roots(),
					toolsChangeConsumers, resourcesChangeConsumers, resourcesUpdateConsumers, promptsChangeConsumers,
					loggingConsumers, progressConsumers, samplingHandler, elicitationHandler,
					syncSpec.enableCallToolSchemaCaching);
		}
	}

	/**
	 * Synchronous client features specification providing the capabilities and request
	 * and notification handlers.
	 *
	 */
	public static class Sync {
		private McpSchema.Implementation clientInfo;
		private McpSchema.ClientCapabilities clientCapabilities;
		private Map<String, McpSchema.Root> roots;
		private List<Consumer<List<McpSchema.Tool>>> toolsChangeConsumers;
		private List<Consumer<List<McpSchema.Resource>>> resourcesChangeConsumers;
		private List<Consumer<List<McpSchema.ResourceContents>>> resourcesUpdateConsumers;
		private List<Consumer<List<McpSchema.Prompt>>> promptsChangeConsumers;
		private List<Consumer<McpSchema.LoggingMessageNotification>> loggingConsumers;
		private List<Consumer<McpSchema.ProgressNotification>> progressConsumers;
		private Function<McpSchema.CreateMessageRequest, McpSchema.CreateMessageResult> samplingHandler;
		private Function<McpSchema.ElicitRequest, McpSchema.ElicitResult> elicitationHandler;
		private boolean enableCallToolSchemaCaching;

		/**
		 * Create an instance and validate the arguments.
		 *
		 * @param clientInfo the client implementation information.
		 * @param clientCapabilities the client capabilities.
		 * @param roots the roots.
		 * @param toolsChangeConsumers the tools change consumers.
		 * @param resourcesChangeConsumers the resources change consumers.
		 * @param promptsChangeConsumers the prompts change consumers.
		 * @param loggingConsumers the logging consumers.
		 * @param progressConsumers the progress consumers.
		 * @param samplingHandler the sampling handler.
		 * @param elicitationHandler the elicitation handler.
		 * @param enableCallToolSchemaCaching whether to enable call tool schema caching.
		 */
		public Sync(McpSchema.Implementation clientInfo, McpSchema.ClientCapabilities clientCapabilities,
					Map<String, McpSchema.Root> roots, List<Consumer<List<McpSchema.Tool>>> toolsChangeConsumers,
					List<Consumer<List<McpSchema.Resource>>> resourcesChangeConsumers,
					List<Consumer<List<McpSchema.ResourceContents>>> resourcesUpdateConsumers,
					List<Consumer<List<McpSchema.Prompt>>> promptsChangeConsumers,
					List<Consumer<McpSchema.LoggingMessageNotification>> loggingConsumers,
					List<Consumer<McpSchema.ProgressNotification>> progressConsumers,
					Function<McpSchema.CreateMessageRequest, McpSchema.CreateMessageResult> samplingHandler,
					Function<McpSchema.ElicitRequest, McpSchema.ElicitResult> elicitationHandler,
					boolean enableCallToolSchemaCaching) {

			Assert.notNull(clientInfo, "Client info must not be null");
			this.clientInfo = clientInfo;
			this.clientCapabilities = (clientCapabilities != null) ? clientCapabilities
					: new McpSchema.ClientCapabilities(null,
					!Utils.isEmpty(roots) ? new McpSchema.ClientCapabilities.RootCapabilities(false) : null,
					samplingHandler != null ? new McpSchema.ClientCapabilities.Sampling() : null,
					elicitationHandler != null ? new McpSchema.ClientCapabilities.Elicitation() : null);
			this.roots = roots != null ? new HashMap<>(roots) : new HashMap<>();

			this.toolsChangeConsumers = toolsChangeConsumers != null ? toolsChangeConsumers : new ArrayList<>();
			this.resourcesChangeConsumers = resourcesChangeConsumers != null ? resourcesChangeConsumers : new ArrayList<>();
			this.resourcesUpdateConsumers = resourcesUpdateConsumers != null ? resourcesUpdateConsumers : new ArrayList<>();
			this.promptsChangeConsumers = promptsChangeConsumers != null ? promptsChangeConsumers : new ArrayList<>();
			this.loggingConsumers = loggingConsumers != null ? loggingConsumers : new ArrayList<>();
			this.progressConsumers = progressConsumers != null ? progressConsumers : new ArrayList<>();
			this.samplingHandler = samplingHandler;
			this.elicitationHandler = elicitationHandler;
			this.enableCallToolSchemaCaching = enableCallToolSchemaCaching;
		}

		public McpSchema.Implementation clientInfo(){
			return clientInfo;
		}

		public McpSchema.ClientCapabilities clientCapabilities(){
			return clientCapabilities;
		}

		public Map<String, McpSchema.Root> roots(){
			return roots;
		}

		public List<Consumer<List<McpSchema.Tool>>> toolsChangeConsumers(){
			return toolsChangeConsumers;
		}

		public List<Consumer<List<McpSchema.Resource>>> resourcesChangeConsumers(){
			return resourcesChangeConsumers;
		}

		public List<Consumer<List<McpSchema.ResourceContents>>> resourcesUpdateConsumers(){
			return resourcesUpdateConsumers;
		}

		public List<Consumer<List<McpSchema.Prompt>>> promptsChangeConsumers(){
			return promptsChangeConsumers;
		}

		public List<Consumer<McpSchema.LoggingMessageNotification>> loggingConsumers(){
			return loggingConsumers;
		}

		public List<Consumer<McpSchema.ProgressNotification>> progressConsumers(){
			return progressConsumers;
		}

		public Function<McpSchema.CreateMessageRequest, McpSchema.CreateMessageResult> samplingHandler(){
			return samplingHandler;
		}

		public Function<McpSchema.ElicitRequest, McpSchema.ElicitResult> elicitationHandler(){
			return elicitationHandler;
		}

		public boolean enableCallToolSchemaCaching(){
			return enableCallToolSchemaCaching;
		}

		/**
		 * @deprecated Only exists for backwards-compatibility purposes.
		 */
		public Sync(McpSchema.Implementation clientInfo, McpSchema.ClientCapabilities clientCapabilities,
					Map<String, McpSchema.Root> roots, List<Consumer<List<McpSchema.Tool>>> toolsChangeConsumers,
					List<Consumer<List<McpSchema.Resource>>> resourcesChangeConsumers,
					List<Consumer<List<McpSchema.ResourceContents>>> resourcesUpdateConsumers,
					List<Consumer<List<McpSchema.Prompt>>> promptsChangeConsumers,
					List<Consumer<McpSchema.LoggingMessageNotification>> loggingConsumers,
					Function<McpSchema.CreateMessageRequest, McpSchema.CreateMessageResult> samplingHandler,
					Function<McpSchema.ElicitRequest, McpSchema.ElicitResult> elicitationHandler) {
			this(clientInfo, clientCapabilities, roots, toolsChangeConsumers, resourcesChangeConsumers,
					resourcesUpdateConsumers, promptsChangeConsumers, loggingConsumers, new ArrayList<>(), samplingHandler,
					elicitationHandler, false);
		}
	}

}
