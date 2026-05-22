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
	 */
	static final class Async {

		private final McpSchema.Implementation clientInfo;
		private final McpSchema.ClientCapabilities clientCapabilities;
		private final Map<String, McpSchema.Root> roots;
		private final List<Function<List<McpSchema.Tool>, Mono<Void>>> toolsChangeConsumers;
		private final List<Function<List<McpSchema.Resource>, Mono<Void>>> resourcesChangeConsumers;
		private final List<Function<List<McpSchema.ResourceContents>, Mono<Void>>> resourcesUpdateConsumers;
		private final List<Function<List<McpSchema.Prompt>, Mono<Void>>> promptsChangeConsumers;
		private final List<Function<McpSchema.LoggingMessageNotification, Mono<Void>>> loggingConsumers;
		private final List<Function<McpSchema.ProgressNotification, Mono<Void>>> progressConsumers;
		private final Function<McpSchema.CreateMessageRequest, Mono<McpSchema.CreateMessageResult>> samplingHandler;
		private final Function<McpSchema.ElicitRequest, Mono<McpSchema.ElicitResult>> elicitationHandler;
		private final boolean enableCallToolSchemaCaching;

		public McpSchema.Implementation clientInfo() {
			return this.clientInfo;
		}

		public McpSchema.ClientCapabilities clientCapabilities() {
			return this.clientCapabilities;
		}

		public Map<String, McpSchema.Root> roots() {
			return this.roots;
		}

		public List<Function<List<McpSchema.Tool>, Mono<Void>>> toolsChangeConsumers() {
			return this.toolsChangeConsumers;
		}

		public List<Function<List<McpSchema.Resource>, Mono<Void>>> resourcesChangeConsumers() {
			return this.resourcesChangeConsumers;
		}

		public List<Function<List<McpSchema.ResourceContents>, Mono<Void>>> resourcesUpdateConsumers() {
			return this.resourcesUpdateConsumers;
		}

		public List<Function<List<McpSchema.Prompt>, Mono<Void>>> promptsChangeConsumers() {
			return this.promptsChangeConsumers;
		}

		public List<Function<McpSchema.LoggingMessageNotification, Mono<Void>>> loggingConsumers() {
			return this.loggingConsumers;
		}

		public List<Function<McpSchema.ProgressNotification, Mono<Void>>> progressConsumers() {
			return this.progressConsumers;
		}

		public Function<McpSchema.CreateMessageRequest, Mono<McpSchema.CreateMessageResult>> samplingHandler() {
			return this.samplingHandler;
		}

		public Function<McpSchema.ElicitRequest, Mono<McpSchema.ElicitResult>> elicitationHandler() {
			return this.elicitationHandler;
		}

		public boolean enableCallToolSchemaCaching() {
			return this.enableCallToolSchemaCaching;
		}

		/**
		 * Create an instance and validate the arguments.
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

			this.toolsChangeConsumers = toolsChangeConsumers != null ? toolsChangeConsumers : Collections.emptyList();
			this.resourcesChangeConsumers = resourcesChangeConsumers != null ? resourcesChangeConsumers : Collections.emptyList();
			this.resourcesUpdateConsumers = resourcesUpdateConsumers != null ? resourcesUpdateConsumers : Collections.emptyList();
			this.promptsChangeConsumers = promptsChangeConsumers != null ? promptsChangeConsumers : Collections.emptyList();
			this.loggingConsumers = loggingConsumers != null ? loggingConsumers : Collections.emptyList();
			this.progressConsumers = progressConsumers != null ? progressConsumers : Collections.emptyList();
			this.samplingHandler = samplingHandler;
			this.elicitationHandler = elicitationHandler;
			this.enableCallToolSchemaCaching = enableCallToolSchemaCaching;
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
					resourcesUpdateConsumers, promptsChangeConsumers, loggingConsumers, Collections.emptyList(), samplingHandler,
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
					syncSpec.enableCallToolSchemaCaching());
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (!(o instanceof Async)) return false;
			Async that = (Async) o;
			return Objects.equals(clientInfo, that.clientInfo)
				&& Objects.equals(clientCapabilities, that.clientCapabilities)
				&& Objects.equals(roots, that.roots)
				&& Objects.equals(toolsChangeConsumers, that.toolsChangeConsumers)
				&& Objects.equals(resourcesChangeConsumers, that.resourcesChangeConsumers)
				&& Objects.equals(resourcesUpdateConsumers, that.resourcesUpdateConsumers)
				&& Objects.equals(promptsChangeConsumers, that.promptsChangeConsumers)
				&& Objects.equals(loggingConsumers, that.loggingConsumers)
				&& Objects.equals(progressConsumers, that.progressConsumers)
				&& Objects.equals(samplingHandler, that.samplingHandler)
				&& Objects.equals(elicitationHandler, that.elicitationHandler)
				&& enableCallToolSchemaCaching == that.enableCallToolSchemaCaching;
		}

		@Override
		public int hashCode() {
			return Objects.hash(clientInfo, clientCapabilities, roots, toolsChangeConsumers, resourcesChangeConsumers,
					resourcesUpdateConsumers, promptsChangeConsumers, loggingConsumers, progressConsumers,
					samplingHandler, elicitationHandler, enableCallToolSchemaCaching);
		}

		@Override
		public String toString() {
			return "Async[clientInfo=" + clientInfo + ", clientCapabilities=" + clientCapabilities
				+ ", roots=" + roots + ", toolsChangeConsumers=" + toolsChangeConsumers
				+ ", resourcesChangeConsumers=" + resourcesChangeConsumers
				+ ", resourcesUpdateConsumers=" + resourcesUpdateConsumers
				+ ", promptsChangeConsumers=" + promptsChangeConsumers
				+ ", loggingConsumers=" + loggingConsumers
				+ ", progressConsumers=" + progressConsumers
				+ ", samplingHandler=" + samplingHandler
				+ ", elicitationHandler=" + elicitationHandler
				+ ", enableCallToolSchemaCaching=" + enableCallToolSchemaCaching + "]";
		}
	}

	/**
	 * Synchronous client features specification providing the capabilities and request
	 * and notification handlers.
	 */
	public static final class Sync {

		private final McpSchema.Implementation clientInfo;
		private final McpSchema.ClientCapabilities clientCapabilities;
		private final Map<String, McpSchema.Root> roots;
		private final List<Consumer<List<McpSchema.Tool>>> toolsChangeConsumers;
		private final List<Consumer<List<McpSchema.Resource>>> resourcesChangeConsumers;
		private final List<Consumer<List<McpSchema.ResourceContents>>> resourcesUpdateConsumers;
		private final List<Consumer<List<McpSchema.Prompt>>> promptsChangeConsumers;
		private final List<Consumer<McpSchema.LoggingMessageNotification>> loggingConsumers;
		private final List<Consumer<McpSchema.ProgressNotification>> progressConsumers;
		private final Function<McpSchema.CreateMessageRequest, McpSchema.CreateMessageResult> samplingHandler;
		private final Function<McpSchema.ElicitRequest, McpSchema.ElicitResult> elicitationHandler;
		private final boolean enableCallToolSchemaCaching;

		public McpSchema.Implementation clientInfo() {
			return this.clientInfo;
		}

		public McpSchema.ClientCapabilities clientCapabilities() {
			return this.clientCapabilities;
		}

		public Map<String, McpSchema.Root> roots() {
			return this.roots;
		}

		public List<Consumer<List<McpSchema.Tool>>> toolsChangeConsumers() {
			return this.toolsChangeConsumers;
		}

		public List<Consumer<List<McpSchema.Resource>>> resourcesChangeConsumers() {
			return this.resourcesChangeConsumers;
		}

		public List<Consumer<List<McpSchema.ResourceContents>>> resourcesUpdateConsumers() {
			return this.resourcesUpdateConsumers;
		}

		public List<Consumer<List<McpSchema.Prompt>>> promptsChangeConsumers() {
			return this.promptsChangeConsumers;
		}

		public List<Consumer<McpSchema.LoggingMessageNotification>> loggingConsumers() {
			return this.loggingConsumers;
		}

		public List<Consumer<McpSchema.ProgressNotification>> progressConsumers() {
			return this.progressConsumers;
		}

		public Function<McpSchema.CreateMessageRequest, McpSchema.CreateMessageResult> samplingHandler() {
			return this.samplingHandler;
		}

		public Function<McpSchema.ElicitRequest, McpSchema.ElicitResult> elicitationHandler() {
			return this.elicitationHandler;
		}

		public boolean enableCallToolSchemaCaching() {
			return this.enableCallToolSchemaCaching;
		}

		/**
		 * Create an instance and validate the arguments.
		 * @param clientInfo the client implementation information.
		 * @param clientCapabilities the client capabilities.
		 * @param roots the roots.
		 * @param toolsChangeConsumers the tools change consumers.
		 * @param resourcesChangeConsumers the resources change consumers.
		 * @param resourcesUpdateConsumers the resource update consumers.
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

			this.toolsChangeConsumers = toolsChangeConsumers != null ? toolsChangeConsumers : Collections.emptyList();
			this.resourcesChangeConsumers = resourcesChangeConsumers != null ? resourcesChangeConsumers : Collections.emptyList();
			this.resourcesUpdateConsumers = resourcesUpdateConsumers != null ? resourcesUpdateConsumers : Collections.emptyList();
			this.promptsChangeConsumers = promptsChangeConsumers != null ? promptsChangeConsumers : Collections.emptyList();
			this.loggingConsumers = loggingConsumers != null ? loggingConsumers : Collections.emptyList();
			this.progressConsumers = progressConsumers != null ? progressConsumers : Collections.emptyList();
			this.samplingHandler = samplingHandler;
			this.elicitationHandler = elicitationHandler;
			this.enableCallToolSchemaCaching = enableCallToolSchemaCaching;
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
					resourcesUpdateConsumers, promptsChangeConsumers, loggingConsumers, Collections.emptyList(), samplingHandler,
					elicitationHandler, false);
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (!(o instanceof Sync)) return false;
			Sync that = (Sync) o;
			return Objects.equals(clientInfo, that.clientInfo)
				&& Objects.equals(clientCapabilities, that.clientCapabilities)
				&& Objects.equals(roots, that.roots)
				&& Objects.equals(toolsChangeConsumers, that.toolsChangeConsumers)
				&& Objects.equals(resourcesChangeConsumers, that.resourcesChangeConsumers)
				&& Objects.equals(resourcesUpdateConsumers, that.resourcesUpdateConsumers)
				&& Objects.equals(promptsChangeConsumers, that.promptsChangeConsumers)
				&& Objects.equals(loggingConsumers, that.loggingConsumers)
				&& Objects.equals(progressConsumers, that.progressConsumers)
				&& Objects.equals(samplingHandler, that.samplingHandler)
				&& Objects.equals(elicitationHandler, that.elicitationHandler)
				&& enableCallToolSchemaCaching == that.enableCallToolSchemaCaching;
		}

		@Override
		public int hashCode() {
			return Objects.hash(clientInfo, clientCapabilities, roots, toolsChangeConsumers, resourcesChangeConsumers,
					resourcesUpdateConsumers, promptsChangeConsumers, loggingConsumers, progressConsumers,
					samplingHandler, elicitationHandler, enableCallToolSchemaCaching);
		}

		@Override
		public String toString() {
			return "Sync[clientInfo=" + clientInfo + ", clientCapabilities=" + clientCapabilities
				+ ", roots=" + roots + ", toolsChangeConsumers=" + toolsChangeConsumers
				+ ", resourcesChangeConsumers=" + resourcesChangeConsumers
				+ ", resourcesUpdateConsumers=" + resourcesUpdateConsumers
				+ ", promptsChangeConsumers=" + promptsChangeConsumers
				+ ", loggingConsumers=" + loggingConsumers
				+ ", progressConsumers=" + progressConsumers
				+ ", samplingHandler=" + samplingHandler
				+ ", elicitationHandler=" + elicitationHandler
				+ ", enableCallToolSchemaCaching=" + enableCallToolSchemaCaching + "]";
		}
	}

}
