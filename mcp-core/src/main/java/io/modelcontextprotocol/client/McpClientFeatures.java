/*
 * Copyright 2024-2026 the original author or authors.
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
	 * @param clientInfo the client implementation information.
	 * @param clientCapabilities the client capabilities.
	 * @param roots the roots.
	 * @param toolsChangeConsumers the tools change consumers.
	 * @param resourcesChangeConsumers the resources change consumers.
	 * @param promptsChangeConsumers the prompts change consumers.
	 * @param loggingConsumers the logging consumers.
	 * @param progressConsumers the progress consumers.
	 * @param samplingHandler the sampling handler.
	 * @param formElicitationHandler the elicitation handler.
	 * @param enableCallToolSchemaCaching whether to enable call tool schema caching.
	 * @param applyElicitationDefaults whether the client should fill in missing fields of
	 * an accepted {@code ElicitResult.content} with the {@code default} values declared
	 * in the {@code requestedSchema}.
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

		private final List<Function<McpSchema.ElicitationCompleteNotification, Mono<Void>>> elicitationCompleteConsumers;

		private final Function<McpSchema.CreateMessageRequest, Mono<McpSchema.CreateMessageResult>> samplingHandler;

		private final Function<McpSchema.ElicitFormRequest, Mono<McpSchema.ElicitResult>> formElicitationHandler;

		private final Function<McpSchema.ElicitUrlRequest, Mono<McpSchema.ElicitResult>> urlElicitationHandler;

		private final boolean enableCallToolSchemaCaching;

		private final boolean applyElicitationDefaults;

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
		 * @param formElicitationHandler the elicitation handler.
		 * @param enableCallToolSchemaCaching whether to enable call tool schema caching.
		 * @param applyElicitationDefaults whether the client should fill in missing
		 * fields of an accepted {@code ElicitResult.content} with the {@code default}
		 * values declared in the {@code requestedSchema}.
		 */
		public Async(McpSchema.Implementation clientInfo, McpSchema.ClientCapabilities clientCapabilities,
				Map<String, McpSchema.Root> roots,
				List<Function<List<McpSchema.Tool>, Mono<Void>>> toolsChangeConsumers,
				List<Function<List<McpSchema.Resource>, Mono<Void>>> resourcesChangeConsumers,
				List<Function<List<McpSchema.ResourceContents>, Mono<Void>>> resourcesUpdateConsumers,
				List<Function<List<McpSchema.Prompt>, Mono<Void>>> promptsChangeConsumers,
				List<Function<McpSchema.LoggingMessageNotification, Mono<Void>>> loggingConsumers,
				List<Function<McpSchema.ProgressNotification, Mono<Void>>> progressConsumers,
				List<Function<McpSchema.ElicitationCompleteNotification, Mono<Void>>> elicitationCompleteConsumers,
				Function<McpSchema.CreateMessageRequest, Mono<McpSchema.CreateMessageResult>> samplingHandler,
				Function<McpSchema.ElicitFormRequest, Mono<McpSchema.ElicitResult>> formElicitationHandler,
				Function<McpSchema.ElicitUrlRequest, Mono<McpSchema.ElicitResult>> urlElicitationHandler,
				boolean enableCallToolSchemaCaching, boolean applyElicitationDefaults) {

			Assert.notNull(clientInfo, "Client info must not be null");
			this.clientInfo = clientInfo;
			this.clientCapabilities = (clientCapabilities != null) ? clientCapabilities
					: new McpSchema.ClientCapabilities(null,
							!Utils.isEmpty(roots) ? new McpSchema.ClientCapabilities.RootCapabilities(false) : null,
							samplingHandler != null ? new McpSchema.ClientCapabilities.Sampling() : null,
							elicitationCapabilities(formElicitationHandler, urlElicitationHandler));
			this.roots = roots != null ? new ConcurrentHashMap<>(roots) : new ConcurrentHashMap<>();

			this.toolsChangeConsumers = toolsChangeConsumers != null ? toolsChangeConsumers : Collections.emptyList();
			this.resourcesChangeConsumers = resourcesChangeConsumers != null ? resourcesChangeConsumers
					: Collections.emptyList();
			this.resourcesUpdateConsumers = resourcesUpdateConsumers != null ? resourcesUpdateConsumers
					: Collections.emptyList();
			this.promptsChangeConsumers = promptsChangeConsumers != null ? promptsChangeConsumers
					: Collections.emptyList();
			this.loggingConsumers = loggingConsumers != null ? loggingConsumers : Collections.emptyList();
			this.progressConsumers = progressConsumers != null ? progressConsumers : Collections.emptyList();
			this.elicitationCompleteConsumers = elicitationCompleteConsumers != null ? elicitationCompleteConsumers
					: Collections.emptyList();
			this.samplingHandler = samplingHandler;
			this.formElicitationHandler = formElicitationHandler;
			this.urlElicitationHandler = urlElicitationHandler;
			this.enableCallToolSchemaCaching = enableCallToolSchemaCaching;
			this.applyElicitationDefaults = applyElicitationDefaults;
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
				Function<McpSchema.ElicitFormRequest, Mono<McpSchema.ElicitResult>> elicitationHandler) {
			this(clientInfo, clientCapabilities, roots, toolsChangeConsumers, resourcesChangeConsumers,
					resourcesUpdateConsumers, promptsChangeConsumers, loggingConsumers, Collections.emptyList(),
					Collections.emptyList(), samplingHandler, elicitationHandler, null, false, false);
		}

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

		public List<Function<McpSchema.ElicitationCompleteNotification, Mono<Void>>> elicitationCompleteConsumers() {
			return this.elicitationCompleteConsumers;
		}

		public Function<McpSchema.CreateMessageRequest, Mono<McpSchema.CreateMessageResult>> samplingHandler() {
			return this.samplingHandler;
		}

		public Function<McpSchema.ElicitFormRequest, Mono<McpSchema.ElicitResult>> formElicitationHandler() {
			return this.formElicitationHandler;
		}

		public Function<McpSchema.ElicitUrlRequest, Mono<McpSchema.ElicitResult>> urlElicitationHandler() {
			return this.urlElicitationHandler;
		}

		public boolean enableCallToolSchemaCaching() {
			return this.enableCallToolSchemaCaching;
		}

		public boolean applyElicitationDefaults() {
			return this.applyElicitationDefaults;
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) {
				return true;
			}
			if (!(o instanceof Async)) {
				return false;
			}
			Async that = (Async) o;
			return this.enableCallToolSchemaCaching == that.enableCallToolSchemaCaching
					&& this.applyElicitationDefaults == that.applyElicitationDefaults
					&& Objects.equals(this.clientInfo, that.clientInfo)
					&& Objects.equals(this.clientCapabilities, that.clientCapabilities)
					&& Objects.equals(this.roots, that.roots)
					&& Objects.equals(this.toolsChangeConsumers, that.toolsChangeConsumers)
					&& Objects.equals(this.resourcesChangeConsumers, that.resourcesChangeConsumers)
					&& Objects.equals(this.resourcesUpdateConsumers, that.resourcesUpdateConsumers)
					&& Objects.equals(this.promptsChangeConsumers, that.promptsChangeConsumers)
					&& Objects.equals(this.loggingConsumers, that.loggingConsumers)
					&& Objects.equals(this.progressConsumers, that.progressConsumers)
					&& Objects.equals(this.elicitationCompleteConsumers, that.elicitationCompleteConsumers)
					&& Objects.equals(this.samplingHandler, that.samplingHandler)
					&& Objects.equals(this.formElicitationHandler, that.formElicitationHandler)
					&& Objects.equals(this.urlElicitationHandler, that.urlElicitationHandler);
		}

		@Override
		public int hashCode() {
			return Objects.hash(this.clientInfo, this.clientCapabilities, this.roots, this.toolsChangeConsumers,
					this.resourcesChangeConsumers, this.resourcesUpdateConsumers, this.promptsChangeConsumers,
					this.loggingConsumers, this.progressConsumers, this.elicitationCompleteConsumers,
					this.samplingHandler, this.formElicitationHandler, this.urlElicitationHandler,
					this.enableCallToolSchemaCaching, this.applyElicitationDefaults);
		}

		@Override
		public String toString() {
			return "Async[clientInfo=" + this.clientInfo + ", clientCapabilities=" + this.clientCapabilities
					+ ", roots=" + this.roots + ", toolsChangeConsumers=" + this.toolsChangeConsumers
					+ ", resourcesChangeConsumers=" + this.resourcesChangeConsumers + ", resourcesUpdateConsumers="
					+ this.resourcesUpdateConsumers + ", promptsChangeConsumers=" + this.promptsChangeConsumers
					+ ", loggingConsumers=" + this.loggingConsumers + ", progressConsumers=" + this.progressConsumers
					+ ", elicitationCompleteConsumers=" + this.elicitationCompleteConsumers + ", samplingHandler="
					+ this.samplingHandler + ", formElicitationHandler=" + this.formElicitationHandler
					+ ", urlElicitationHandler=" + this.urlElicitationHandler + ", enableCallToolSchemaCaching="
					+ this.enableCallToolSchemaCaching + ", applyElicitationDefaults=" + this.applyElicitationDefaults
					+ "]";
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

			List<Function<McpSchema.ElicitationCompleteNotification, Mono<Void>>> elicitationCompleteConsumers = new ArrayList<>();
			for (Consumer<McpSchema.ElicitationCompleteNotification> consumer : syncSpec
				.elicitationCompleteConsumers()) {
				elicitationCompleteConsumers.add(l -> Mono.<Void>fromRunnable(() -> consumer.accept(l))
					.subscribeOn(Schedulers.boundedElastic()));
			}

			Function<McpSchema.CreateMessageRequest, Mono<McpSchema.CreateMessageResult>> samplingHandler = r -> Mono
				.fromCallable(() -> syncSpec.samplingHandler().apply(r))
				.subscribeOn(Schedulers.boundedElastic());

			Function<McpSchema.ElicitFormRequest, Mono<McpSchema.ElicitResult>> formElicitationHandler = syncSpec
				.formElicitationHandler() != null
						? r -> Mono.fromCallable(() -> syncSpec.formElicitationHandler().apply(r))
							.subscribeOn(Schedulers.boundedElastic())
						: null;

			Function<McpSchema.ElicitUrlRequest, Mono<McpSchema.ElicitResult>> urlElicitationHandler = syncSpec
				.urlElicitationHandler() != null
						? r -> Mono.fromCallable(() -> syncSpec.urlElicitationHandler().apply(r))
							.subscribeOn(Schedulers.boundedElastic())
						: null;

			return new Async(syncSpec.clientInfo(), syncSpec.clientCapabilities(), syncSpec.roots(),
					toolsChangeConsumers, resourcesChangeConsumers, resourcesUpdateConsumers, promptsChangeConsumers,
					loggingConsumers, progressConsumers, elicitationCompleteConsumers, samplingHandler,
					formElicitationHandler, urlElicitationHandler, syncSpec.enableCallToolSchemaCaching(),
					syncSpec.applyElicitationDefaults());
		}

	}

	/**
	 * Synchronous client features specification providing the capabilities and request
	 * and notification handlers.
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
	 * @param formElicitationHandler the elicitation handler.
	 * @param enableCallToolSchemaCaching whether to enable call tool schema caching.
	 * @param applyElicitationDefaults whether the client should fill in missing fields of
	 * an accepted {@code ElicitResult.content} with the {@code default} values declared
	 * in the {@code requestedSchema}.
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

		private final List<Consumer<McpSchema.ElicitationCompleteNotification>> elicitationCompleteConsumers;

		private final Function<McpSchema.CreateMessageRequest, McpSchema.CreateMessageResult> samplingHandler;

		private final Function<McpSchema.ElicitFormRequest, McpSchema.ElicitResult> formElicitationHandler;

		private final Function<McpSchema.ElicitUrlRequest, McpSchema.ElicitResult> urlElicitationHandler;

		private final boolean enableCallToolSchemaCaching;

		private final boolean applyElicitationDefaults;

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
		 * @param formElicitationHandler the elicitation handler.
		 * @param enableCallToolSchemaCaching whether to enable call tool schema caching.
		 * @param applyElicitationDefaults whether the client should fill in missing
		 * fields of an accepted {@code ElicitResult.content} with the {@code default}
		 * values declared in the {@code requestedSchema}.
		 */
		public Sync(McpSchema.Implementation clientInfo, McpSchema.ClientCapabilities clientCapabilities,
				Map<String, McpSchema.Root> roots, List<Consumer<List<McpSchema.Tool>>> toolsChangeConsumers,
				List<Consumer<List<McpSchema.Resource>>> resourcesChangeConsumers,
				List<Consumer<List<McpSchema.ResourceContents>>> resourcesUpdateConsumers,
				List<Consumer<List<McpSchema.Prompt>>> promptsChangeConsumers,
				List<Consumer<McpSchema.LoggingMessageNotification>> loggingConsumers,
				List<Consumer<McpSchema.ProgressNotification>> progressConsumers,
				List<Consumer<McpSchema.ElicitationCompleteNotification>> elicitationCompleteConsumers,
				Function<McpSchema.CreateMessageRequest, McpSchema.CreateMessageResult> samplingHandler,
				Function<McpSchema.ElicitFormRequest, McpSchema.ElicitResult> formElicitationHandler,
				Function<McpSchema.ElicitUrlRequest, McpSchema.ElicitResult> urlElicitationHandler,
				boolean enableCallToolSchemaCaching, boolean applyElicitationDefaults) {

			Assert.notNull(clientInfo, "Client info must not be null");
			this.clientInfo = clientInfo;
			this.clientCapabilities = (clientCapabilities != null) ? clientCapabilities
					: new McpSchema.ClientCapabilities(null,
							!Utils.isEmpty(roots) ? new McpSchema.ClientCapabilities.RootCapabilities(false) : null,
							samplingHandler != null ? new McpSchema.ClientCapabilities.Sampling() : null,
							elicitationCapabilities(formElicitationHandler, urlElicitationHandler));
			this.roots = roots != null ? new HashMap<>(roots) : new HashMap<>();

			this.toolsChangeConsumers = toolsChangeConsumers != null ? toolsChangeConsumers : Collections.emptyList();
			this.resourcesChangeConsumers = resourcesChangeConsumers != null ? resourcesChangeConsumers
					: Collections.emptyList();
			this.resourcesUpdateConsumers = resourcesUpdateConsumers != null ? resourcesUpdateConsumers
					: Collections.emptyList();
			this.promptsChangeConsumers = promptsChangeConsumers != null ? promptsChangeConsumers
					: Collections.emptyList();
			this.loggingConsumers = loggingConsumers != null ? loggingConsumers : Collections.emptyList();
			this.progressConsumers = progressConsumers != null ? progressConsumers : Collections.emptyList();
			this.elicitationCompleteConsumers = elicitationCompleteConsumers != null ? elicitationCompleteConsumers
					: Collections.emptyList();
			this.samplingHandler = samplingHandler;
			this.formElicitationHandler = formElicitationHandler;
			this.urlElicitationHandler = urlElicitationHandler;
			this.enableCallToolSchemaCaching = enableCallToolSchemaCaching;
			this.applyElicitationDefaults = applyElicitationDefaults;
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
				Function<McpSchema.ElicitFormRequest, McpSchema.ElicitResult> formElicitationHandler,
				Function<McpSchema.ElicitUrlRequest, McpSchema.ElicitResult> urlElicitationHandler) {
			this(clientInfo, clientCapabilities, roots, toolsChangeConsumers, resourcesChangeConsumers,
					resourcesUpdateConsumers, promptsChangeConsumers, loggingConsumers, Collections.emptyList(),
					Collections.emptyList(), samplingHandler, formElicitationHandler, urlElicitationHandler, false,
					false);
		}

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

		public List<Consumer<McpSchema.ElicitationCompleteNotification>> elicitationCompleteConsumers() {
			return this.elicitationCompleteConsumers;
		}

		public Function<McpSchema.CreateMessageRequest, McpSchema.CreateMessageResult> samplingHandler() {
			return this.samplingHandler;
		}

		public Function<McpSchema.ElicitFormRequest, McpSchema.ElicitResult> formElicitationHandler() {
			return this.formElicitationHandler;
		}

		public Function<McpSchema.ElicitUrlRequest, McpSchema.ElicitResult> urlElicitationHandler() {
			return this.urlElicitationHandler;
		}

		public boolean enableCallToolSchemaCaching() {
			return this.enableCallToolSchemaCaching;
		}

		public boolean applyElicitationDefaults() {
			return this.applyElicitationDefaults;
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) {
				return true;
			}
			if (!(o instanceof Sync)) {
				return false;
			}
			Sync that = (Sync) o;
			return this.enableCallToolSchemaCaching == that.enableCallToolSchemaCaching
					&& this.applyElicitationDefaults == that.applyElicitationDefaults
					&& Objects.equals(this.clientInfo, that.clientInfo)
					&& Objects.equals(this.clientCapabilities, that.clientCapabilities)
					&& Objects.equals(this.roots, that.roots)
					&& Objects.equals(this.toolsChangeConsumers, that.toolsChangeConsumers)
					&& Objects.equals(this.resourcesChangeConsumers, that.resourcesChangeConsumers)
					&& Objects.equals(this.resourcesUpdateConsumers, that.resourcesUpdateConsumers)
					&& Objects.equals(this.promptsChangeConsumers, that.promptsChangeConsumers)
					&& Objects.equals(this.loggingConsumers, that.loggingConsumers)
					&& Objects.equals(this.progressConsumers, that.progressConsumers)
					&& Objects.equals(this.elicitationCompleteConsumers, that.elicitationCompleteConsumers)
					&& Objects.equals(this.samplingHandler, that.samplingHandler)
					&& Objects.equals(this.formElicitationHandler, that.formElicitationHandler)
					&& Objects.equals(this.urlElicitationHandler, that.urlElicitationHandler);
		}

		@Override
		public int hashCode() {
			return Objects.hash(this.clientInfo, this.clientCapabilities, this.roots, this.toolsChangeConsumers,
					this.resourcesChangeConsumers, this.resourcesUpdateConsumers, this.promptsChangeConsumers,
					this.loggingConsumers, this.progressConsumers, this.elicitationCompleteConsumers,
					this.samplingHandler, this.formElicitationHandler, this.urlElicitationHandler,
					this.enableCallToolSchemaCaching, this.applyElicitationDefaults);
		}

		@Override
		public String toString() {
			return "Sync[clientInfo=" + this.clientInfo + ", clientCapabilities=" + this.clientCapabilities
					+ ", roots=" + this.roots + ", toolsChangeConsumers=" + this.toolsChangeConsumers
					+ ", resourcesChangeConsumers=" + this.resourcesChangeConsumers + ", resourcesUpdateConsumers="
					+ this.resourcesUpdateConsumers + ", promptsChangeConsumers=" + this.promptsChangeConsumers
					+ ", loggingConsumers=" + this.loggingConsumers + ", progressConsumers=" + this.progressConsumers
					+ ", elicitationCompleteConsumers=" + this.elicitationCompleteConsumers + ", samplingHandler="
					+ this.samplingHandler + ", formElicitationHandler=" + this.formElicitationHandler
					+ ", urlElicitationHandler=" + this.urlElicitationHandler + ", enableCallToolSchemaCaching="
					+ this.enableCallToolSchemaCaching + ", applyElicitationDefaults=" + this.applyElicitationDefaults
					+ "]";
		}
	}

	private static McpSchema.ClientCapabilities.Elicitation elicitationCapabilities(
			Function<McpSchema.ElicitFormRequest, ?> formElicitationHandler,
			Function<McpSchema.ElicitUrlRequest, ?> urlElicitationHandler) {
		McpSchema.ClientCapabilities.Elicitation elicitationCapabilities = null;
		if (formElicitationHandler != null || urlElicitationHandler != null) {
			McpSchema.ClientCapabilities.Elicitation.Builder elicitationCapabilitiesBuilder = McpSchema.ClientCapabilities.Elicitation
				.builder();
			if (formElicitationHandler != null) {
				elicitationCapabilitiesBuilder.form(new McpSchema.ClientCapabilities.Elicitation.Form());
			}
			if (urlElicitationHandler != null) {
				elicitationCapabilitiesBuilder.url(new McpSchema.ClientCapabilities.Elicitation.Url());
			}
			elicitationCapabilities = elicitationCapabilitiesBuilder.build();
		}
		return elicitationCapabilities;
	}

}
