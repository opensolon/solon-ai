/*
 * Copyright 2024-2025 the original author or authors.
 */

package io.modelcontextprotocol.server;

import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.util.Assert;
import io.modelcontextprotocol.util.Utils;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.var;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
     */
    @Data
    @NoArgsConstructor
    public static class Async{
        McpSchema.Implementation serverInfo;
        McpSchema.ServerCapabilities serverCapabilities;
        List<McpStatelessServerFeatures.AsyncToolSpecification> tools;
        Map<String, AsyncResourceSpecification> resources;
        List<McpSchema.ResourceTemplate> resourceTemplates;
        Map<String, McpStatelessServerFeatures.AsyncPromptSpecification> prompts;
        Map<McpSchema.CompleteReference, McpStatelessServerFeatures.AsyncCompletionSpecification> completions;
        String instructions;

        /**
         * Create an instance and validate the arguments.
         * @param serverInfo The server implementation details
         * @param serverCapabilities The server capabilities
         * @param tools The list of tool specifications
         * @param resources The map of resource specifications
         * @param resourceTemplates The list of resource templates
         * @param prompts The map of prompt specifications
         * @param instructions The server instructions text
         */
         public Async(McpSchema.Implementation serverInfo, McpSchema.ServerCapabilities serverCapabilities,
                List<McpStatelessServerFeatures.AsyncToolSpecification> tools,
                Map<String, AsyncResourceSpecification> resources, List<McpSchema.ResourceTemplate> resourceTemplates,
                Map<String, McpStatelessServerFeatures.AsyncPromptSpecification> prompts,
                Map<McpSchema.CompleteReference, McpStatelessServerFeatures.AsyncCompletionSpecification> completions,
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

            this.tools = (tools != null) ? tools : new ArrayList<>();
            this.resources = (resources != null) ? resources : new HashMap<>();
            this.resourceTemplates = (resourceTemplates != null) ? resourceTemplates : new ArrayList<>();
            this.prompts = (prompts != null) ? prompts : new HashMap<>();
            this.completions = (completions != null) ? completions : new HashMap<>();
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
        public static Async fromSync(Sync syncSpec, boolean immediateExecution) {
            List<McpStatelessServerFeatures.AsyncToolSpecification> tools = new ArrayList<>();
            for (var tool : syncSpec.getTools()) {
                tools.add(AsyncToolSpecification.fromSync(tool, immediateExecution));
            }

            Map<String, AsyncResourceSpecification> resources = new HashMap<>();
            syncSpec.getResources().forEach((key, resource) -> {
                resources.put(key, AsyncResourceSpecification.fromSync(resource, immediateExecution));
            });

            Map<String, AsyncPromptSpecification> prompts = new HashMap<>();
            syncSpec.getPrompts().forEach((key, prompt) -> {
                prompts.put(key, AsyncPromptSpecification.fromSync(prompt, immediateExecution));
            });

            Map<McpSchema.CompleteReference, McpStatelessServerFeatures.AsyncCompletionSpecification> completions = new HashMap<>();
            syncSpec.getCompletions().forEach((key, completion) -> {
                completions.put(key, AsyncCompletionSpecification.fromSync(completion, immediateExecution));
            });

            return new Async(syncSpec.getServerInfo(), syncSpec.getServerCapabilities(), tools, resources,
                    syncSpec.getResourceTemplates(), prompts, completions, syncSpec.getInstructions());
        }
    }

    /**
     * Synchronous server features specification.
     */
    @Data
    @NoArgsConstructor
    public static class Sync {
        McpSchema.Implementation serverInfo;
        McpSchema.ServerCapabilities serverCapabilities;
        List<McpStatelessServerFeatures.SyncToolSpecification> tools;
        Map<String, McpStatelessServerFeatures.SyncResourceSpecification> resources;
        List<McpSchema.ResourceTemplate> resourceTemplates;
        Map<String, McpStatelessServerFeatures.SyncPromptSpecification> prompts;
        Map<McpSchema.CompleteReference, McpStatelessServerFeatures.SyncCompletionSpecification> completions;
        String instructions;

        /**
         * Create an instance and validate the arguments.
         * @param serverInfo The server implementation details
         * @param serverCapabilities The server capabilities
         * @param tools The list of tool specifications
         * @param resources The map of resource specifications
         * @param resourceTemplates The list of resource templates
         * @param prompts The map of prompt specifications
         * @param instructions The server instructions text
         */
        public Sync(McpSchema.Implementation serverInfo, McpSchema.ServerCapabilities serverCapabilities,
                List<McpStatelessServerFeatures.SyncToolSpecification> tools,
                Map<String, McpStatelessServerFeatures.SyncResourceSpecification> resources,
                List<McpSchema.ResourceTemplate> resourceTemplates,
                Map<String, McpStatelessServerFeatures.SyncPromptSpecification> prompts,
                Map<McpSchema.CompleteReference, McpStatelessServerFeatures.SyncCompletionSpecification> completions,
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
            this.resourceTemplates = (resourceTemplates != null) ? resourceTemplates : new ArrayList<>();
            this.prompts = (prompts != null) ? prompts : new HashMap<>();
            this.completions = (completions != null) ? completions : new HashMap<>();
            this.instructions = instructions;
        }

    }

    /**
     * Specification of a tool with its asynchronous handler function. Tools are the
     * primary way for MCP servers to expose functionality to AI models. Each tool
     * represents a specific capability.
     *
     * {@link CallToolRequest} and returning the result.
     */
    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public static class AsyncToolSpecification {
        McpSchema.Tool tool;
        BiFunction<McpTransportContext, CallToolRequest, Mono<McpSchema.CallToolResult>> callHandler;

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
                var toolResult = Mono.fromCallable(() -> syncToolSpec.getCallHandler().apply(ctx, req));
                return immediate ? toolResult : toolResult.subscribeOn(Schedulers.boundedElastic());
            };

            return new AsyncToolSpecification(syncToolSpec.getTool(), callHandler);
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
     * argument is a {@link McpSchema.ReadResourceRequest}.
     */
    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public static class AsyncResourceSpecification {
        McpSchema.Resource resource;
        BiFunction<McpTransportContext, McpSchema.ReadResourceRequest, Mono<McpSchema.ReadResourceResult>> readHandler;

        static AsyncResourceSpecification fromSync(SyncResourceSpecification resource, boolean immediateExecution) {
            // FIXME: This is temporary, proper validation should be implemented
            if (resource == null) {
                return null;
            }
            return new AsyncResourceSpecification(resource.getResource(), (ctx, req) -> {
                var resourceResult = Mono.fromCallable(() -> resource.getReadHandler().apply(ctx, req));
                return immediateExecution ? resourceResult : resourceResult.subscribeOn(Schedulers.boundedElastic());
            });
        }
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
     * formatted templates. The function's argument is a
     * {@link McpSchema.GetPromptRequest}.
     */
    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public static class AsyncPromptSpecification {
        McpSchema.Prompt prompt;
        BiFunction<McpTransportContext, McpSchema.GetPromptRequest, Mono<McpSchema.GetPromptResult>> promptHandler;

        static AsyncPromptSpecification fromSync(SyncPromptSpecification prompt, boolean immediateExecution) {
            // FIXME: This is temporary, proper validation should be implemented
            if (prompt == null) {
                return null;
            }
            return new AsyncPromptSpecification(prompt.getPrompt(), (ctx, req) -> {
                var promptResult = Mono.fromCallable(() -> prompt.getPromptHandler().apply(ctx, req));
                return immediateExecution ? promptResult : promptResult.subscribeOn(Schedulers.boundedElastic());
            });
        }
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
     * {@link McpSchema.CompleteRequest}.
     */
    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public static class AsyncCompletionSpecification {
        McpSchema.CompleteReference referenceKey;
        BiFunction<McpTransportContext, McpSchema.CompleteRequest, Mono<McpSchema.CompleteResult>> completionHandler;

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
            return new AsyncCompletionSpecification(completion.getReferenceKey(), (ctx, req) -> {
                var completionResult = Mono.fromCallable(() -> completion.getCompletionHandler().apply(ctx, req));
                return immediateExecution ? completionResult
                        : completionResult.subscribeOn(Schedulers.boundedElastic());
            });
        }
    }

    /**
     * Specification of a tool with its synchronous handler function. Tools are the
     * primary way for MCP servers to expose functionality to AI models.
     *
     * {@link CallToolRequest} and returning results.
     */
    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public static class SyncToolSpecification {
        McpSchema.Tool tool;
        BiFunction<McpTransportContext, CallToolRequest, McpSchema.CallToolResult> callHandler;

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
     * argument is a {@link McpSchema.ReadResourceRequest}.
     */
    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public static class SyncResourceSpecification {
        McpSchema.Resource resource;
        BiFunction<McpTransportContext, McpSchema.ReadResourceRequest, McpSchema.ReadResourceResult> readHandler;
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
     * formatted templates. The function's argument is a
     * {@link McpSchema.GetPromptRequest}.
     */
    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public static class SyncPromptSpecification {
        McpSchema.Prompt prompt;
        BiFunction<McpTransportContext, McpSchema.GetPromptRequest, McpSchema.GetPromptResult> promptHandler;
    }

    /**
     * Specification of a completion handler function with synchronous execution support.
     *
     * requests and returns results. The argument is a {@link McpSchema.CompleteRequest}.
     */
    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public static class SyncCompletionSpecification {
        McpSchema.CompleteReference referenceKey;
        BiFunction<McpTransportContext, McpSchema.CompleteRequest, McpSchema.CompleteResult> completionHandler;
    }
}