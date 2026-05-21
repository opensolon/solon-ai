/*
 * Copyright 2024-2025 the original author or authors.
 */

package io.modelcontextprotocol.server;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.util.Assert;
import io.modelcontextprotocol.util.Utils;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.*;
import java.util.function.BiFunction;

/**
 * MCP stateless server features specification that a particular server can choose to
 * support.
 *
 * @author Dariusz Jędrzejczyk
 * @author Christian Tzolov
 */
public class McpStatelessServerFeatures {

	static final class Async {
		private final McpSchema.Implementation serverInfo;
		private final McpSchema.ServerCapabilities serverCapabilities;
		private final List<AsyncToolSpecification> tools;
		private final Map<String, AsyncResourceSpecification> resources;
		private final Map<String, AsyncResourceTemplateSpecification> resourceTemplates;
		private final Map<String, AsyncPromptSpecification> prompts;
		private final Map<McpSchema.CompleteReference, AsyncCompletionSpecification> completions;
		private final String instructions;


		public McpSchema.Implementation serverInfo() {
			return this.serverInfo;
		}

		public McpSchema.ServerCapabilities serverCapabilities() {
			return this.serverCapabilities;
		}

		public List<AsyncToolSpecification> tools() {
			return this.tools;
		}

		public Map<String, AsyncResourceSpecification> resources() {
			return this.resources;
		}

		public Map<String, AsyncResourceTemplateSpecification> resourceTemplates() {
			return this.resourceTemplates;
		}

		public Map<String, AsyncPromptSpecification> prompts() {
			return this.prompts;
		}

		public Map<McpSchema.CompleteReference, AsyncCompletionSpecification> completions() {
			return this.completions;
		}

		public String instructions() {
			return this.instructions;
		}

/**
		 * Create an instance and validate the arguments.
		 * @param serverInfo The server implementation details
		 * @param serverCapabilities The server capabilities
		 * @param tools The list of tool specifications
		 * @param resources The map of resource specifications
		 * @param resourceTemplates The map of resource templates
		 * @param prompts The map of prompt specifications
		 * @param instructions The server instructions text
		 */
		Async(McpSchema.Implementation serverInfo, McpSchema.ServerCapabilities serverCapabilities,
				List<AsyncToolSpecification> tools,
				Map<String, AsyncResourceSpecification> resources,
				Map<String, AsyncResourceTemplateSpecification> resourceTemplates,
				Map<String, AsyncPromptSpecification> prompts,
				Map<McpSchema.CompleteReference, AsyncCompletionSpecification> completions,
				String instructions) {

			Assert.notNull(serverInfo, "Server info must not be null");

			this.serverInfo = serverInfo;
			this.serverCapabilities = (serverCapabilities != null) ? serverCapabilities
					: new McpSchema.ServerCapabilities(null, // completions
							null, // experimental
							null, // currently statless server doesn't support set logging
							!Utils.isEmpty(prompts) ? McpSchema.ServerCapabilities.PromptCapabilities.builder().build()
									: null,
							!Utils.isEmpty(resources)
									? McpSchema.ServerCapabilities.ResourceCapabilities.builder().build() : null,
							!Utils.isEmpty(tools) ? McpSchema.ServerCapabilities.ToolCapabilities.builder().build()
									: null);

			this.tools = (tools != null) ? tools : Collections.emptyList();
			this.resources = (resources != null) ? resources : Collections.emptyMap();
			this.resourceTemplates = (resourceTemplates != null) ? resourceTemplates : Collections.emptyMap();
			this.prompts = (prompts != null) ? prompts : Collections.emptyMap();
			this.completions = (completions != null) ? completions : Collections.emptyMap();
			this.instructions = instructions;
		}

		/**
		 * Convert a synchronous specification into an asynchronous one and provide
		 * blocking code offloading to prevent accidental blocking of the non-blocking
		 * transport.
		 * @param syncSpec a potentially blocking, synchronous specification.
		 * @param immediateExecution when true, do not offload. Do NOT set to true when
		 * using a non-blocking transport.
		 * @return a specification which is protected from blocking calls specified by the
		 * user.
		 */
		static Async fromSync(Sync syncSpec, boolean immediateExecution) {
			List<AsyncToolSpecification> tools = new ArrayList<>();
			for (SyncToolSpecification tool : syncSpec.tools()) {
				tools.add(AsyncToolSpecification.fromSync(tool, immediateExecution));
			}

			Map<String, AsyncResourceSpecification> resources = new HashMap<>();
			syncSpec.resources().forEach((key, resource) -> {
				resources.put(key, AsyncResourceSpecification.fromSync(resource, immediateExecution));
			});

			Map<String, AsyncResourceTemplateSpecification> resourceTemplates = new HashMap<>();
			syncSpec.resourceTemplates().forEach((key, resource) -> {
				resourceTemplates.put(key, AsyncResourceTemplateSpecification.fromSync(resource, immediateExecution));
			});

			Map<String, AsyncPromptSpecification> prompts = new HashMap<>();
			syncSpec.prompts().forEach((key, prompt) -> {
				prompts.put(key, AsyncPromptSpecification.fromSync(prompt, immediateExecution));
			});

			Map<McpSchema.CompleteReference, AsyncCompletionSpecification> completions = new HashMap<>();
			syncSpec.completions().forEach((key, completion) -> {
				completions.put(key, AsyncCompletionSpecification.fromSync(completion, immediateExecution));
			});

			return new Async(syncSpec.serverInfo(), syncSpec.serverCapabilities(), tools, resources, resourceTemplates,
					prompts, completions, syncSpec.instructions());
		}

	}

	static final class Sync {
		private final McpSchema.Implementation serverInfo;
		private final McpSchema.ServerCapabilities serverCapabilities;
		private final List<SyncToolSpecification> tools;
		private final Map<String, SyncResourceSpecification> resources;
		private final Map<String, SyncResourceTemplateSpecification> resourceTemplates;
		private final Map<String, SyncPromptSpecification> prompts;
		private final Map<McpSchema.CompleteReference, SyncCompletionSpecification> completions;
		private final String instructions;

		public McpSchema.Implementation serverInfo() {
			return this.serverInfo;
		}

		public McpSchema.ServerCapabilities serverCapabilities() {
			return this.serverCapabilities;
		}

		public List<SyncToolSpecification> tools() {
			return this.tools;
		}

		public Map<String, SyncResourceSpecification> resources() {
			return this.resources;
		}

		public Map<String, SyncResourceTemplateSpecification> resourceTemplates() {
			return this.resourceTemplates;
		}

		public Map<String, SyncPromptSpecification> prompts() {
			return this.prompts;
		}

		public Map<McpSchema.CompleteReference, SyncCompletionSpecification> completions() {
			return this.completions;
		}

		public String instructions() {
			return this.instructions;
		}

/**
		 * Create an instance and validate the arguments.
		 * @param serverInfo The server implementation details
		 * @param serverCapabilities The server capabilities
		 * @param tools The list of tool specifications
		 * @param resources The map of resource specifications
		 * @param resourceTemplates The map of resource templates
		 * @param prompts The map of prompt specifications
		 * @param instructions The server instructions text
		 */
		Sync(McpSchema.Implementation serverInfo, McpSchema.ServerCapabilities serverCapabilities,
				List<SyncToolSpecification> tools,
				Map<String, SyncResourceSpecification> resources,
				Map<String, SyncResourceTemplateSpecification> resourceTemplates,
				Map<String, SyncPromptSpecification> prompts,
				Map<McpSchema.CompleteReference, SyncCompletionSpecification> completions,
				String instructions) {

			Assert.notNull(serverInfo, "Server info must not be null");

			this.serverInfo = serverInfo;
			this.serverCapabilities = (serverCapabilities != null) ? serverCapabilities
					: new McpSchema.ServerCapabilities(null, // completions
							null, // experimental
							new McpSchema.ServerCapabilities.LoggingCapabilities(), // Enable
																					// logging
																					// by
																					// default
							!Utils.isEmpty(prompts) ? McpSchema.ServerCapabilities.PromptCapabilities.builder().build()
									: null,
							!Utils.isEmpty(resources)
									? McpSchema.ServerCapabilities.ResourceCapabilities.builder().build() : null,
							!Utils.isEmpty(tools) ? McpSchema.ServerCapabilities.ToolCapabilities.builder().build()
									: null);

			this.tools = (tools != null) ? tools : new ArrayList<>();
			this.resources = (resources != null) ? resources : new HashMap<>();
			this.resourceTemplates = (resourceTemplates != null) ? resourceTemplates : Collections.emptyMap();
			this.prompts = (prompts != null) ? prompts : new HashMap<>();
			this.completions = (completions != null) ? completions : new HashMap<>();
			this.instructions = instructions;
		}

	}

	public static final class AsyncToolSpecification {
		private final McpSchema.Tool tool;
		private final BiFunction<McpTransportContext, CallToolRequest, Mono<McpSchema.CallToolResult>> callHandler;

		public AsyncToolSpecification(McpSchema.Tool tool, BiFunction<McpTransportContext, CallToolRequest, Mono<McpSchema.CallToolResult>> callHandler) {
			this.tool = tool;
			this.callHandler = callHandler;
		}

		public McpSchema.Tool tool() {
			return this.tool;
		}

		public BiFunction<McpTransportContext, CallToolRequest, Mono<McpSchema.CallToolResult>> callHandler() {
			return this.callHandler;
		}

static AsyncToolSpecification fromSync(SyncToolSpecification syncToolSpec) {
			return fromSync(syncToolSpec, false);
		}

		static AsyncToolSpecification fromSync(SyncToolSpecification syncToolSpec, boolean immediate) {

			// FIXME: This is temporary, proper validation should be implemented
			if (syncToolSpec == null) {
				return null;
			}

			BiFunction<McpTransportContext, CallToolRequest, Mono<McpSchema.CallToolResult>> callHandler = (ctx,
					req) -> {
				Mono<McpSchema.CallToolResult> toolResult = Mono.fromCallable(() -> syncToolSpec.callHandler().apply(ctx, req));
				return immediate ? toolResult : toolResult.subscribeOn(Schedulers.boundedElastic());
			};

			return new AsyncToolSpecification(syncToolSpec.tool(), callHandler);
		}

		/**
		 * Builder for creating AsyncToolSpecification instances.
		 */
		public static class Builder {

			private McpSchema.Tool tool;

			private BiFunction<McpTransportContext, CallToolRequest, Mono<McpSchema.CallToolResult>> callHandler;

			/**
			 * Sets the tool definition.
			 * @param tool The tool definition including name, description, and parameter
			 * schema
			 * @return this builder instance
			 */
			public Builder tool(McpSchema.Tool tool) {
				this.tool = tool;
				return this;
			}

			/**
			 * Sets the call tool handler function.
			 * @param callHandler The function that implements the tool's logic
			 * @return this builder instance
			 */
			public Builder callHandler(
					BiFunction<McpTransportContext, CallToolRequest, Mono<McpSchema.CallToolResult>> callHandler) {
				this.callHandler = callHandler;
				return this;
			}

			/**
			 * Builds the AsyncToolSpecification instance.
			 * @return a new AsyncToolSpecification instance
			 * @throws IllegalArgumentException if required fields are not set
			 */
			public AsyncToolSpecification build() {
				Assert.notNull(tool, "Tool must not be null");
				Assert.notNull(callHandler, "Call handler function must not be null");

				return new AsyncToolSpecification(tool, callHandler);
			}

		}

		/**
		 * Creates a new builder instance.
		 * @return a new Builder instance
		 */
		public static Builder builder() {
			return new Builder();
		}

	}

	public static final class AsyncResourceSpecification {
		private final McpSchema.Resource resource;
		private final BiFunction<McpTransportContext, McpSchema.ReadResourceRequest, Mono<McpSchema.ReadResourceResult>> readHandler;

		public AsyncResourceSpecification(McpSchema.Resource resource, BiFunction<McpTransportContext, McpSchema.ReadResourceRequest, Mono<McpSchema.ReadResourceResult>> readHandler) {
			this.resource = resource;
			this.readHandler = readHandler;
		}

		public McpSchema.Resource resource() {
			return this.resource;
		}

		public BiFunction<McpTransportContext, McpSchema.ReadResourceRequest, Mono<McpSchema.ReadResourceResult>> readHandler() {
			return this.readHandler;
		}

static AsyncResourceSpecification fromSync(SyncResourceSpecification resource, boolean immediateExecution) {
			// FIXME: This is temporary, proper validation should be implemented
			if (resource == null) {
				return null;
			}
			return new AsyncResourceSpecification(resource.resource(), (ctx, req) -> {
				Mono<McpSchema.ReadResourceResult> resourceResult = Mono.fromCallable(() -> resource.readHandler().apply(ctx, req));
				return immediateExecution ? resourceResult : resourceResult.subscribeOn(Schedulers.boundedElastic());
			});
		}

	}

	public static final class AsyncResourceTemplateSpecification {
		private final McpSchema.ResourceTemplate resourceTemplate;
		private final BiFunction<McpTransportContext, McpSchema.ReadResourceRequest, Mono<McpSchema.ReadResourceResult>> readHandler;

		public AsyncResourceTemplateSpecification(McpSchema.ResourceTemplate resourceTemplate, BiFunction<McpTransportContext, McpSchema.ReadResourceRequest, Mono<McpSchema.ReadResourceResult>> readHandler) {
			this.resourceTemplate = resourceTemplate;
			this.readHandler = readHandler;
		}

		public McpSchema.ResourceTemplate resourceTemplate() {
			return this.resourceTemplate;
		}

		public BiFunction<McpTransportContext, McpSchema.ReadResourceRequest, Mono<McpSchema.ReadResourceResult>> readHandler() {
			return this.readHandler;
		}

static AsyncResourceTemplateSpecification fromSync(SyncResourceTemplateSpecification resource,
				boolean immediateExecution) {
			// FIXME: This is temporary, proper validation should be implemented
			if (resource == null) {
				return null;
			}
			return new AsyncResourceTemplateSpecification(resource.resourceTemplate(), (ctx, req) -> {
				Mono<McpSchema.ReadResourceResult> resourceResult = Mono.fromCallable(() -> resource.readHandler().apply(ctx, req));
				return immediateExecution ? resourceResult : resourceResult.subscribeOn(Schedulers.boundedElastic());
			});
		}

	}

	public static final class AsyncPromptSpecification {
		private final McpSchema.Prompt prompt;
		private final BiFunction<McpTransportContext, McpSchema.GetPromptRequest, Mono<McpSchema.GetPromptResult>> promptHandler;

		public AsyncPromptSpecification(McpSchema.Prompt prompt, BiFunction<McpTransportContext, McpSchema.GetPromptRequest, Mono<McpSchema.GetPromptResult>> promptHandler) {
			this.prompt = prompt;
			this.promptHandler = promptHandler;
		}

		public McpSchema.Prompt prompt() {
			return this.prompt;
		}

		public BiFunction<McpTransportContext, McpSchema.GetPromptRequest, Mono<McpSchema.GetPromptResult>> promptHandler() {
			return this.promptHandler;
		}

static AsyncPromptSpecification fromSync(SyncPromptSpecification prompt, boolean immediateExecution) {
			// FIXME: This is temporary, proper validation should be implemented
			if (prompt == null) {
				return null;
			}
			return new AsyncPromptSpecification(prompt.prompt(), (ctx, req) -> {
				Mono<McpSchema.GetPromptResult> promptResult = Mono.fromCallable(() -> prompt.promptHandler().apply(ctx, req));
				return immediateExecution ? promptResult : promptResult.subscribeOn(Schedulers.boundedElastic());
			});
		}

	}

	public static final class AsyncCompletionSpecification {
		private final McpSchema.CompleteReference referenceKey;
		private final BiFunction<McpTransportContext, McpSchema.CompleteRequest, Mono<McpSchema.CompleteResult>> completionHandler;

		public AsyncCompletionSpecification(McpSchema.CompleteReference referenceKey, BiFunction<McpTransportContext, McpSchema.CompleteRequest, Mono<McpSchema.CompleteResult>> completionHandler) {
			this.referenceKey = referenceKey;
			this.completionHandler = completionHandler;
		}

		public McpSchema.CompleteReference referenceKey() {
			return this.referenceKey;
		}

		public BiFunction<McpTransportContext, McpSchema.CompleteRequest, Mono<McpSchema.CompleteResult>> completionHandler() {
			return this.completionHandler;
		}

/**
		 * Converts a synchronous {@link SyncCompletionSpecification} into an
		 * {@link AsyncCompletionSpecification} by wrapping the handler in a bounded
		 * elastic scheduler for safe non-blocking execution.
		 * @param completion the synchronous completion specification
		 * @return an asynchronous wrapper of the provided sync specification, or
		 * {@code null} if input is null
		 */
		static AsyncCompletionSpecification fromSync(SyncCompletionSpecification completion,
				boolean immediateExecution) {
			if (completion == null) {
				return null;
			}
			return new AsyncCompletionSpecification(completion.referenceKey(), (ctx, req) -> {
				Mono<McpSchema.CompleteResult> completionResult = Mono.fromCallable(() -> completion.completionHandler().apply(ctx, req));
				return immediateExecution ? completionResult
						: completionResult.subscribeOn(Schedulers.boundedElastic());
			});
		}

	}

	public static final class SyncToolSpecification {
		private final McpSchema.Tool tool;
		private final BiFunction<McpTransportContext, CallToolRequest, McpSchema.CallToolResult> callHandler;

		public SyncToolSpecification(McpSchema.Tool tool, BiFunction<McpTransportContext, CallToolRequest, McpSchema.CallToolResult> callHandler) {
			this.tool = tool;
			this.callHandler = callHandler;
		}

		public McpSchema.Tool tool() {
			return this.tool;
		}

		public BiFunction<McpTransportContext, CallToolRequest, McpSchema.CallToolResult> callHandler() {
			return this.callHandler;
		}

public static Builder builder() {
			return new Builder();
		}

		/**
		 * Builder for creating SyncToolSpecification instances.
		 */
		public static class Builder {

			private McpSchema.Tool tool;

			private BiFunction<McpTransportContext, CallToolRequest, McpSchema.CallToolResult> callHandler;

			/**
			 * Sets the tool definition.
			 * @param tool The tool definition including name, description, and parameter
			 * schema
			 * @return this builder instance
			 */
			public Builder tool(McpSchema.Tool tool) {
				this.tool = tool;
				return this;
			}

			/**
			 * Sets the call tool handler function.
			 * @param callHandler The function that implements the tool's logic
			 * @return this builder instance
			 */
			public Builder callHandler(
					BiFunction<McpTransportContext, CallToolRequest, McpSchema.CallToolResult> callHandler) {
				this.callHandler = callHandler;
				return this;
			}

			/**
			 * Builds the SyncToolSpecification instance.
			 * @return a new SyncToolSpecification instance
			 * @throws IllegalArgumentException if required fields are not set
			 */
			public SyncToolSpecification build() {
				Assert.notNull(tool, "Tool must not be null");
				Assert.notNull(callHandler, "CallTool function must not be null");

				return new SyncToolSpecification(tool, callHandler);
			}

		}

	}

	public static final class SyncResourceSpecification {
		private final McpSchema.Resource resource;
		private final BiFunction<McpTransportContext, McpSchema.ReadResourceRequest, McpSchema.ReadResourceResult> readHandler;

		public SyncResourceSpecification(McpSchema.Resource resource, BiFunction<McpTransportContext, McpSchema.ReadResourceRequest, McpSchema.ReadResourceResult> readHandler) {
			this.resource = resource;
			this.readHandler = readHandler;
		}

		public McpSchema.Resource resource() {
			return this.resource;
		}

		public BiFunction<McpTransportContext, McpSchema.ReadResourceRequest, McpSchema.ReadResourceResult> readHandler() {
			return this.readHandler;
		}

	}

	public static final class SyncResourceTemplateSpecification {
		private final McpSchema.ResourceTemplate resourceTemplate;
		private final BiFunction<McpTransportContext, McpSchema.ReadResourceRequest, McpSchema.ReadResourceResult> readHandler;

		public SyncResourceTemplateSpecification(McpSchema.ResourceTemplate resourceTemplate, BiFunction<McpTransportContext, McpSchema.ReadResourceRequest, McpSchema.ReadResourceResult> readHandler) {
			this.resourceTemplate = resourceTemplate;
			this.readHandler = readHandler;
		}

		public McpSchema.ResourceTemplate resourceTemplate() {
			return this.resourceTemplate;
		}

		public BiFunction<McpTransportContext, McpSchema.ReadResourceRequest, McpSchema.ReadResourceResult> readHandler() {
			return this.readHandler;
		}

	}

	public static final class SyncPromptSpecification {
		private final McpSchema.Prompt prompt;
		private final BiFunction<McpTransportContext, McpSchema.GetPromptRequest, McpSchema.GetPromptResult> promptHandler;

		public SyncPromptSpecification(McpSchema.Prompt prompt, BiFunction<McpTransportContext, McpSchema.GetPromptRequest, McpSchema.GetPromptResult> promptHandler) {
			this.prompt = prompt;
			this.promptHandler = promptHandler;
		}

		public McpSchema.Prompt prompt() {
			return this.prompt;
		}

		public BiFunction<McpTransportContext, McpSchema.GetPromptRequest, McpSchema.GetPromptResult> promptHandler() {
			return this.promptHandler;
		}

	}

	public static final class SyncCompletionSpecification {
		private final McpSchema.CompleteReference referenceKey;
		private final BiFunction<McpTransportContext, McpSchema.CompleteRequest, McpSchema.CompleteResult> completionHandler;

		public SyncCompletionSpecification(McpSchema.CompleteReference referenceKey, BiFunction<McpTransportContext, McpSchema.CompleteRequest, McpSchema.CompleteResult> completionHandler) {
			this.referenceKey = referenceKey;
			this.completionHandler = completionHandler;
		}

		public McpSchema.CompleteReference referenceKey() {
			return this.referenceKey;
		}

		public BiFunction<McpTransportContext, McpSchema.CompleteRequest, McpSchema.CompleteResult> completionHandler() {
			return this.completionHandler;
		}

	}

}
