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

	/**
	 * Asynchronous server features specification.
	 *
	 * @param serverInfo The server implementation details
	 * @param serverCapabilities The server capabilities
	 * @param tools The list of tool specifications
	 * @param resources The map of resource specifications
	 * @param resourceTemplates The map of resource templates
	 * @param prompts The map of prompt specifications
	 * @param instructions The server instructions text
	 */
	static final class Async {

		private final McpSchema.Implementation serverInfo;

		private final McpSchema.ServerCapabilities serverCapabilities;

		private final List<AsyncToolSpecification> tools;

		private final Map<String, AsyncResourceSpecification> resources;

		private final Map<String, AsyncResourceTemplateSpecification> resourceTemplates;

		private final Map<String, AsyncPromptSpecification> prompts;

		private final Map<McpSchema.CompleteReference, AsyncCompletionSpecification> completions;

		private final String instructions;

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
							!Utils.isEmpty(prompts) ? new McpSchema.ServerCapabilities.PromptCapabilities(false) : null,
							!Utils.isEmpty(resources)
									? new McpSchema.ServerCapabilities.ResourceCapabilities(false, false) : null,
							!Utils.isEmpty(tools) ? new McpSchema.ServerCapabilities.ToolCapabilities(false) : null);

			this.tools = (tools != null) ? tools : Collections.emptyList();
			this.resources = (resources != null) ? resources : Collections.emptyMap();
			this.resourceTemplates = (resourceTemplates != null) ? resourceTemplates : Collections.emptyMap();
			this.prompts = (prompts != null) ? prompts : Collections.emptyMap();
			this.completions = (completions != null) ? completions : Collections.emptyMap();
			this.instructions = instructions;
		}

		public McpSchema.Implementation serverInfo() { return this.serverInfo; }

		public McpSchema.ServerCapabilities serverCapabilities() { return this.serverCapabilities; }

		public List<AsyncToolSpecification> tools() { return this.tools; }

		public Map<String, AsyncResourceSpecification> resources() { return this.resources; }

		public Map<String, AsyncResourceTemplateSpecification> resourceTemplates() { return this.resourceTemplates; }

		public Map<String, AsyncPromptSpecification> prompts() { return this.prompts; }

		public Map<McpSchema.CompleteReference, AsyncCompletionSpecification> completions() { return this.completions; }

		public String instructions() { return this.instructions; }

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

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (!(o instanceof Async)) return false;
			Async that = (Async) o;
			return Objects.equals(serverInfo, that.serverInfo)
					&& Objects.equals(serverCapabilities, that.serverCapabilities)
					&& Objects.equals(tools, that.tools)
					&& Objects.equals(resources, that.resources)
					&& Objects.equals(resourceTemplates, that.resourceTemplates)
					&& Objects.equals(prompts, that.prompts)
					&& Objects.equals(completions, that.completions)
					&& Objects.equals(instructions, that.instructions);
		}

		@Override
		public int hashCode() {
			return Objects.hash(serverInfo, serverCapabilities, tools, resources, resourceTemplates, prompts, completions, instructions);
		}

		@Override
		public String toString() {
			return "Async[serverInfo=" + serverInfo + ", serverCapabilities=" + serverCapabilities
					+ ", tools=" + tools + ", resources=" + resources
					+ ", resourceTemplates=" + resourceTemplates + ", prompts=" + prompts
					+ ", completions=" + completions + ", instructions=" + instructions + "]";
		}
	}

	/**
	 * Synchronous server features specification.
	 *
	 * @param serverInfo The server implementation details
	 * @param serverCapabilities The server capabilities
	 * @param tools The list of tool specifications
	 * @param resources The map of resource specifications
	 * @param resourceTemplates The map of resource templates
	 * @param prompts The map of prompt specifications
	 * @param instructions The server instructions text
	 */
	static final class Sync {

		private final McpSchema.Implementation serverInfo;

		private final McpSchema.ServerCapabilities serverCapabilities;

		private final List<SyncToolSpecification> tools;

		private final Map<String, SyncResourceSpecification> resources;

		private final Map<String, SyncResourceTemplateSpecification> resourceTemplates;

		private final Map<String, SyncPromptSpecification> prompts;

		private final Map<McpSchema.CompleteReference, SyncCompletionSpecification> completions;

		private final String instructions;

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
							!Utils.isEmpty(prompts) ? new McpSchema.ServerCapabilities.PromptCapabilities(false) : null,
							!Utils.isEmpty(resources)
									? new McpSchema.ServerCapabilities.ResourceCapabilities(false, false) : null,
							!Utils.isEmpty(tools) ? new McpSchema.ServerCapabilities.ToolCapabilities(false) : null);

			this.tools = (tools != null) ? tools : new ArrayList<>();
			this.resources = (resources != null) ? resources : new HashMap<>();
			this.resourceTemplates = (resourceTemplates != null) ? resourceTemplates : Collections.emptyMap();
			this.prompts = (prompts != null) ? prompts : new HashMap<>();
			this.completions = (completions != null) ? completions : new HashMap<>();
			this.instructions = instructions;
		}

		public McpSchema.Implementation serverInfo() { return this.serverInfo; }

		public McpSchema.ServerCapabilities serverCapabilities() { return this.serverCapabilities; }

		public List<SyncToolSpecification> tools() { return this.tools; }

		public Map<String, SyncResourceSpecification> resources() { return this.resources; }

		public Map<String, SyncResourceTemplateSpecification> resourceTemplates() { return this.resourceTemplates; }

		public Map<String, SyncPromptSpecification> prompts() { return this.prompts; }

		public Map<McpSchema.CompleteReference, SyncCompletionSpecification> completions() { return this.completions; }

		public String instructions() { return this.instructions; }

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (!(o instanceof Sync)) return false;
			Sync that = (Sync) o;
			return Objects.equals(serverInfo, that.serverInfo)
					&& Objects.equals(serverCapabilities, that.serverCapabilities)
					&& Objects.equals(tools, that.tools)
					&& Objects.equals(resources, that.resources)
					&& Objects.equals(resourceTemplates, that.resourceTemplates)
					&& Objects.equals(prompts, that.prompts)
					&& Objects.equals(completions, that.completions)
					&& Objects.equals(instructions, that.instructions);
		}

		@Override
		public int hashCode() {
			return Objects.hash(serverInfo, serverCapabilities, tools, resources, resourceTemplates, prompts, completions, instructions);
		}

		@Override
		public String toString() {
			return "Sync[serverInfo=" + serverInfo + ", serverCapabilities=" + serverCapabilities
					+ ", tools=" + tools + ", resources=" + resources
					+ ", resourceTemplates=" + resourceTemplates + ", prompts=" + prompts
					+ ", completions=" + completions + ", instructions=" + instructions + "]";
		}

	}

	/**
	 * Specification of a tool with its asynchronous handler function. Tools are the
	 * primary way for MCP servers to expose functionality to AI models. Each tool
	 * represents a specific capability.
	 *
	 * @param tool The tool definition including name, description, and parameter schema
	 * @param callHandler The function that implements the tool's logic, receiving a
	 * {@link CallToolRequest} and returning the result.
	 */
	public static final class AsyncToolSpecification {

		private final McpSchema.Tool tool;

		private final BiFunction<McpTransportContext, CallToolRequest, Mono<McpSchema.CallToolResult>> callHandler;

		public AsyncToolSpecification(McpSchema.Tool tool,
				BiFunction<McpTransportContext, CallToolRequest, Mono<McpSchema.CallToolResult>> callHandler) {
			this.tool = tool;
			this.callHandler = callHandler;
		}

		public McpSchema.Tool tool() { return this.tool; }

		public BiFunction<McpTransportContext, CallToolRequest, Mono<McpSchema.CallToolResult>> callHandler() { return this.callHandler; }

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

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (!(o instanceof AsyncToolSpecification)) return false;
			AsyncToolSpecification that = (AsyncToolSpecification) o;
			return Objects.equals(tool, that.tool) && Objects.equals(callHandler, that.callHandler);
		}

		@Override
		public int hashCode() { return Objects.hash(tool, callHandler); }

		@Override
		public String toString() { return "AsyncToolSpecification[tool=" + tool + ", callHandler=" + callHandler + "]"; }
	}

	/**
	 * Specification of a resource with its asynchronous handler function. Resources
	 * provide context to AI models by exposing data such as:
	 * <ul>
	 * <li>File contents
	 * <li>Database records
	 * <li>API responses
	 * <li>System information
	 * <li>Application state
	 * </ul>
	 *
	 * @param resource The resource definition including name, description, and MIME type
	 * @param readHandler The function that handles resource read requests. The function's
	 * argument is a {@link McpSchema.ReadResourceRequest}.
	 */
	public static final class AsyncResourceSpecification {

		private final McpSchema.Resource resource;

		private final BiFunction<McpTransportContext, McpSchema.ReadResourceRequest, Mono<McpSchema.ReadResourceResult>> readHandler;

		public AsyncResourceSpecification(McpSchema.Resource resource,
				BiFunction<McpTransportContext, McpSchema.ReadResourceRequest, Mono<McpSchema.ReadResourceResult>> readHandler) {
			this.resource = resource;
			this.readHandler = readHandler;
		}

		public McpSchema.Resource resource() { return this.resource; }

		public BiFunction<McpTransportContext, McpSchema.ReadResourceRequest, Mono<McpSchema.ReadResourceResult>> readHandler() { return this.readHandler; }

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

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (!(o instanceof AsyncResourceSpecification)) return false;
			AsyncResourceSpecification that = (AsyncResourceSpecification) o;
			return Objects.equals(resource, that.resource) && Objects.equals(readHandler, that.readHandler);
		}

		@Override
		public int hashCode() { return Objects.hash(resource, readHandler); }

		@Override
		public String toString() { return "AsyncResourceSpecification[resource=" + resource + ", readHandler=" + readHandler + "]"; }
	}

	/**
	 * Specification of a resource template with its synchronous handler function.
	 * Resource templates allow servers to expose parameterized resources using URI
	 * templates: <a href=https://datatracker.ietf.org/doc/html/rfc6570> URI
	 * templates.</a>. Arguments may be auto-completed through <a href=
	 * "https://modelcontextprotocol.io/specification/2025-06-18/server/utilities/completion">the
	 * completion API</a>.
	 *
	 * Templates support:
	 * <ul>
	 * <li>Parameterized resource definitions
	 * <li>Dynamic content generation
	 * <li>Consistent resource formatting
	 * <li>Contextual data injection
	 * </ul>
	 *
	 * @param resourceTemplate The resource template definition including name,
	 * description, and parameter schema
	 * @param readHandler The function that handles resource read requests. The function's
	 * first argument is an {@link McpTransportContext} upon which the server can interact
	 * with the connected client. The second arguments is a
	 * {@link McpSchema.ReadResourceRequest}. {@link McpSchema.ResourceTemplate}
	 * {@link McpSchema.ReadResourceResult}
	 */
	public static final class AsyncResourceTemplateSpecification {

		private final McpSchema.ResourceTemplate resourceTemplate;

		private final BiFunction<McpTransportContext, McpSchema.ReadResourceRequest, Mono<McpSchema.ReadResourceResult>> readHandler;

		public AsyncResourceTemplateSpecification(McpSchema.ResourceTemplate resourceTemplate,
				BiFunction<McpTransportContext, McpSchema.ReadResourceRequest, Mono<McpSchema.ReadResourceResult>> readHandler) {
			this.resourceTemplate = resourceTemplate;
			this.readHandler = readHandler;
		}

		public McpSchema.ResourceTemplate resourceTemplate() { return this.resourceTemplate; }

		public BiFunction<McpTransportContext, McpSchema.ReadResourceRequest, Mono<McpSchema.ReadResourceResult>> readHandler() { return this.readHandler; }

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

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (!(o instanceof AsyncResourceTemplateSpecification)) return false;
			AsyncResourceTemplateSpecification that = (AsyncResourceTemplateSpecification) o;
			return Objects.equals(resourceTemplate, that.resourceTemplate) && Objects.equals(readHandler, that.readHandler);
		}

		@Override
		public int hashCode() { return Objects.hash(resourceTemplate, readHandler); }

		@Override
		public String toString() { return "AsyncResourceTemplateSpecification[resourceTemplate=" + resourceTemplate + ", readHandler=" + readHandler + "]"; }
	}

	/**
	 * Specification of a prompt template with its asynchronous handler function. Prompts
	 * provide structured templates for AI model interactions, supporting:
	 * <ul>
	 * <li>Consistent message formatting
	 * <li>Parameter substitution
	 * <li>Context injection
	 * <li>Response formatting
	 * <li>Instruction templating
	 * </ul>
	 *
	 * @param prompt The prompt definition including name and description
	 * @param promptHandler The function that processes prompt requests and returns
	 * formatted templates. The function's argument is a
	 * {@link McpSchema.GetPromptRequest}.
	 */
	public static final class AsyncPromptSpecification {

		private final McpSchema.Prompt prompt;

		private final BiFunction<McpTransportContext, McpSchema.GetPromptRequest, Mono<McpSchema.GetPromptResult>> promptHandler;

		public AsyncPromptSpecification(McpSchema.Prompt prompt,
				BiFunction<McpTransportContext, McpSchema.GetPromptRequest, Mono<McpSchema.GetPromptResult>> promptHandler) {
			this.prompt = prompt;
			this.promptHandler = promptHandler;
		}

		public McpSchema.Prompt prompt() { return this.prompt; }

		public BiFunction<McpTransportContext, McpSchema.GetPromptRequest, Mono<McpSchema.GetPromptResult>> promptHandler() { return this.promptHandler; }

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

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (!(o instanceof AsyncPromptSpecification)) return false;
			AsyncPromptSpecification that = (AsyncPromptSpecification) o;
			return Objects.equals(prompt, that.prompt) && Objects.equals(promptHandler, that.promptHandler);
		}

		@Override
		public int hashCode() { return Objects.hash(prompt, promptHandler); }

		@Override
		public String toString() { return "AsyncPromptSpecification[prompt=" + prompt + ", promptHandler=" + promptHandler + "]"; }
	}

	/**
	 * Specification of a completion handler function with asynchronous execution support.
	 * Completions generate AI model outputs based on prompt or resource references and
	 * user-provided arguments. This abstraction enables:
	 * <ul>
	 * <li>Customizable response generation logic
	 * <li>Parameter-driven template expansion
	 * <li>Dynamic interaction with connected clients
	 * </ul>
	 *
	 * @param referenceKey The unique key representing the completion reference.
	 * @param completionHandler The asynchronous function that processes completion
	 * requests and returns results. The function's argument is a
	 * {@link McpSchema.CompleteRequest}.
	 */
	public static final class AsyncCompletionSpecification {

		private final McpSchema.CompleteReference referenceKey;

		private final BiFunction<McpTransportContext, McpSchema.CompleteRequest, Mono<McpSchema.CompleteResult>> completionHandler;

		public AsyncCompletionSpecification(McpSchema.CompleteReference referenceKey,
				BiFunction<McpTransportContext, McpSchema.CompleteRequest, Mono<McpSchema.CompleteResult>> completionHandler) {
			this.referenceKey = referenceKey;
			this.completionHandler = completionHandler;
		}

		public McpSchema.CompleteReference referenceKey() { return this.referenceKey; }

		public BiFunction<McpTransportContext, McpSchema.CompleteRequest, Mono<McpSchema.CompleteResult>> completionHandler() { return this.completionHandler; }

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

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (!(o instanceof AsyncCompletionSpecification)) return false;
			AsyncCompletionSpecification that = (AsyncCompletionSpecification) o;
			return Objects.equals(referenceKey, that.referenceKey) && Objects.equals(completionHandler, that.completionHandler);
		}

		@Override
		public int hashCode() { return Objects.hash(referenceKey, completionHandler); }

		@Override
		public String toString() { return "AsyncCompletionSpecification[referenceKey=" + referenceKey + ", completionHandler=" + completionHandler + "]"; }
	}

	/**
	 * Specification of a tool with its synchronous handler function. Tools are the
	 * primary way for MCP servers to expose functionality to AI models.
	 *
	 * @param tool The tool definition including name, description, and parameter schema
	 * @param callHandler The function that implements the tool's logic, receiving a
	 * {@link CallToolRequest} and returning results.
	 */
	public static final class SyncToolSpecification {

		private final McpSchema.Tool tool;

		private final BiFunction<McpTransportContext, CallToolRequest, McpSchema.CallToolResult> callHandler;

		public SyncToolSpecification(McpSchema.Tool tool,
				BiFunction<McpTransportContext, CallToolRequest, McpSchema.CallToolResult> callHandler) {
			this.tool = tool;
			this.callHandler = callHandler;
		}

		public McpSchema.Tool tool() { return this.tool; }

		public BiFunction<McpTransportContext, CallToolRequest, McpSchema.CallToolResult> callHandler() { return this.callHandler; }

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

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (!(o instanceof SyncToolSpecification)) return false;
			SyncToolSpecification that = (SyncToolSpecification) o;
			return Objects.equals(tool, that.tool) && Objects.equals(callHandler, that.callHandler);
		}

		@Override
		public int hashCode() { return Objects.hash(tool, callHandler); }

		@Override
		public String toString() { return "SyncToolSpecification[tool=" + tool + ", callHandler=" + callHandler + "]"; }
	}

	/**
	 * Specification of a resource with its synchronous handler function. Resources
	 * provide context to AI models by exposing data such as:
	 * <ul>
	 * <li>File contents
	 * <li>Database records
	 * <li>API responses
	 * <li>System information
	 * <li>Application state
	 * </ul>
	 *
	 * @param resource The resource definition including name, description, and MIME type
	 * @param readHandler The function that handles resource read requests. The function's
	 * argument is a {@link McpSchema.ReadResourceRequest}.
	 */
	public static final class SyncResourceSpecification {

		private final McpSchema.Resource resource;

		private final BiFunction<McpTransportContext, McpSchema.ReadResourceRequest, McpSchema.ReadResourceResult> readHandler;

		public SyncResourceSpecification(McpSchema.Resource resource,
				BiFunction<McpTransportContext, McpSchema.ReadResourceRequest, McpSchema.ReadResourceResult> readHandler) {
			this.resource = resource;
			this.readHandler = readHandler;
		}

		public McpSchema.Resource resource() { return this.resource; }

		public BiFunction<McpTransportContext, McpSchema.ReadResourceRequest, McpSchema.ReadResourceResult> readHandler() { return this.readHandler; }

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (!(o instanceof SyncResourceSpecification)) return false;
			SyncResourceSpecification that = (SyncResourceSpecification) o;
			return Objects.equals(resource, that.resource) && Objects.equals(readHandler, that.readHandler);
		}

		@Override
		public int hashCode() { return Objects.hash(resource, readHandler); }

		@Override
		public String toString() { return "SyncResourceSpecification[resource=" + resource + ", readHandler=" + readHandler + "]"; }
	}

	/**
	 * Specification of a resource template with its synchronous handler function.
	 * Resource templates allow servers to expose parameterized resources using URI
	 * templates: <a href=https://datatracker.ietf.org/doc/html/rfc6570> URI
	 * templates.</a>. Arguments may be auto-completed through <a href=
	 * "https://modelcontextprotocol.io/specification/2025-06-18/server/utilities/completion">the
	 * completion API</a>.
	 *
	 * Templates support:
	 * <ul>
	 * <li>Parameterized resource definitions
	 * <li>Dynamic content generation
	 * <li>Consistent resource formatting
	 * <li>Contextual data injection
	 * </ul>
	 *
	 * @param resourceTemplate The resource template definition including name,
	 * description, and parameter schema
	 * @param readHandler The function that handles resource read requests. The function's
	 * first argument is an {@link McpTransportContext} upon which the server can interact
	 * with the connected client. The second arguments is a
	 * {@link McpSchema.ReadResourceRequest}. {@link McpSchema.ResourceTemplate}
	 * {@link McpSchema.ReadResourceResult}
	 */
	public static final class SyncResourceTemplateSpecification {

		private final McpSchema.ResourceTemplate resourceTemplate;

		private final BiFunction<McpTransportContext, McpSchema.ReadResourceRequest, McpSchema.ReadResourceResult> readHandler;

		public SyncResourceTemplateSpecification(McpSchema.ResourceTemplate resourceTemplate,
				BiFunction<McpTransportContext, McpSchema.ReadResourceRequest, McpSchema.ReadResourceResult> readHandler) {
			this.resourceTemplate = resourceTemplate;
			this.readHandler = readHandler;
		}

		public McpSchema.ResourceTemplate resourceTemplate() { return this.resourceTemplate; }

		public BiFunction<McpTransportContext, McpSchema.ReadResourceRequest, McpSchema.ReadResourceResult> readHandler() { return this.readHandler; }

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (!(o instanceof SyncResourceTemplateSpecification)) return false;
			SyncResourceTemplateSpecification that = (SyncResourceTemplateSpecification) o;
			return Objects.equals(resourceTemplate, that.resourceTemplate) && Objects.equals(readHandler, that.readHandler);
		}

		@Override
		public int hashCode() { return Objects.hash(resourceTemplate, readHandler); }

		@Override
		public String toString() { return "SyncResourceTemplateSpecification[resourceTemplate=" + resourceTemplate + ", readHandler=" + readHandler + "]"; }
	}

	/**
	 * Specification of a prompt template with its synchronous handler function. Prompts
	 * provide structured templates for AI model interactions, supporting:
	 * <ul>
	 * <li>Consistent message formatting
	 * <li>Parameter substitution
	 * <li>Context injection
	 * <li>Response formatting
	 * <li>Instruction templating
	 * </ul>
	 *
	 * @param prompt The prompt definition including name and description
	 * @param promptHandler The function that processes prompt requests and returns
	 * formatted templates. The function's argument is a
	 * {@link McpSchema.GetPromptRequest}.
	 */
	public static final class SyncPromptSpecification {

		private final McpSchema.Prompt prompt;

		private final BiFunction<McpTransportContext, McpSchema.GetPromptRequest, McpSchema.GetPromptResult> promptHandler;

		public SyncPromptSpecification(McpSchema.Prompt prompt,
				BiFunction<McpTransportContext, McpSchema.GetPromptRequest, McpSchema.GetPromptResult> promptHandler) {
			this.prompt = prompt;
			this.promptHandler = promptHandler;
		}

		public McpSchema.Prompt prompt() { return this.prompt; }

		public BiFunction<McpTransportContext, McpSchema.GetPromptRequest, McpSchema.GetPromptResult> promptHandler() { return this.promptHandler; }

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (!(o instanceof SyncPromptSpecification)) return false;
			SyncPromptSpecification that = (SyncPromptSpecification) o;
			return Objects.equals(prompt, that.prompt) && Objects.equals(promptHandler, that.promptHandler);
		}

		@Override
		public int hashCode() { return Objects.hash(prompt, promptHandler); }

		@Override
		public String toString() { return "SyncPromptSpecification[prompt=" + prompt + ", promptHandler=" + promptHandler + "]"; }
	}

	/**
	 * Specification of a completion handler function with synchronous execution support.
	 *
	 * @param referenceKey The unique key representing the completion reference.
	 * @param completionHandler The synchronous function that processes completion
	 * requests and returns results. The argument is a {@link McpSchema.CompleteRequest}.
	 */
	public static final class SyncCompletionSpecification {

		private final McpSchema.CompleteReference referenceKey;

		private final BiFunction<McpTransportContext, McpSchema.CompleteRequest, McpSchema.CompleteResult> completionHandler;

		public SyncCompletionSpecification(McpSchema.CompleteReference referenceKey,
				BiFunction<McpTransportContext, McpSchema.CompleteRequest, McpSchema.CompleteResult> completionHandler) {
			this.referenceKey = referenceKey;
			this.completionHandler = completionHandler;
		}

		public McpSchema.CompleteReference referenceKey() { return this.referenceKey; }

		public BiFunction<McpTransportContext, McpSchema.CompleteRequest, McpSchema.CompleteResult> completionHandler() { return this.completionHandler; }

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (!(o instanceof SyncCompletionSpecification)) return false;
			SyncCompletionSpecification that = (SyncCompletionSpecification) o;
			return Objects.equals(referenceKey, that.referenceKey) && Objects.equals(completionHandler, that.completionHandler);
		}

		@Override
		public int hashCode() { return Objects.hash(referenceKey, completionHandler); }

		@Override
		public String toString() { return "SyncCompletionSpecification[referenceKey=" + referenceKey + ", completionHandler=" + completionHandler + "]"; }
	}

}
