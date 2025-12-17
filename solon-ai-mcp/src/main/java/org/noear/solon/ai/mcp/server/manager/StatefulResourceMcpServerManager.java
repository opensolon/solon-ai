/*
 * Copyright 2017-2025 noear.org and authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.noear.solon.ai.mcp.server.manager;

import io.modelcontextprotocol.server.McpAsyncServer;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import org.noear.solon.Utils;
import org.noear.solon.ai.mcp.exception.McpException;
import org.noear.solon.ai.mcp.server.McpServerContext;
import org.noear.solon.ai.mcp.server.McpServerProperties;
import org.noear.solon.ai.mcp.server.resource.FunctionResource;
import org.noear.solon.core.handle.ContextHolder;
import reactor.core.publisher.Mono;

import java.util.Arrays;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * 资源服务端管理
 *
 * @author noear
 * @since 3.2
 */
public class StatefulResourceMcpServerManager implements McpServerManager<FunctionResource> {
    private final Map<String, FunctionResource> resourcesMap = new ConcurrentHashMap<>();

    private final Supplier<McpAsyncServer> serverSupplier;
    private final McpServer.AsyncSpecification mcpServerSpec;

    public StatefulResourceMcpServerManager(Supplier<McpAsyncServer> serverSupplier, McpServer.AsyncSpecification mcpServerSpec) {
        this.serverSupplier = serverSupplier;
        this.mcpServerSpec = mcpServerSpec;
    }

    @Override
    public int count() {
        return resourcesMap.size();
    }

    @Override
    public Collection<FunctionResource> all() {
        return resourcesMap.values();
    }

    @Override
    public boolean contains(String resourceUri) {
        return resourcesMap.containsKey(resourceUri);
    }

    @Override
    public void remove(String resourceUri) {
        if (serverSupplier.get() != null) {
            if (resourceUri.indexOf('{') < 0) {
                serverSupplier.get().removeResource(resourceUri).block();
            } else {
                serverSupplier.get().removeResourceTemplate(resourceUri).block();
            }

            resourcesMap.remove(resourceUri);
        }
    }

    @Override
    public void add(McpServerProperties mcpServerProps, FunctionResource functionResource) {
        resourcesMap.put(functionResource.uri(), functionResource);

        if (functionResource.uri().indexOf('{') < 0) {
            addRef(mcpServerProps, functionResource);
        } else {
            addTml(mcpServerProps, functionResource);
        }
    }

    private void addRef(McpServerProperties mcpServerProps, FunctionResource functionResource) {
        try {
            //resourceSpec
            McpServerFeatures.AsyncResourceSpecification resourceSpec = new McpServerFeatures.AsyncResourceSpecification(
                    McpSchema.Resource.builder()
                            .uri(functionResource.uri())
                            .name(functionResource.name()).title(functionResource.title()).description(functionResource.description())
                            .mimeType(functionResource.mimeType()).build(),
                    (exchange, request) -> {
                        return Mono.create(sink -> {
                            ContextHolder.currentWith(new McpServerContext(exchange.transportContext()), () -> {
                                functionResource.handleAsync(request.uri()).whenComplete((res, err) -> {

                                    if (err != null) {
                                        err = Utils.throwableUnwrap(err);
                                        sink.error(new McpException(err.getMessage(), err));
                                    } else {
                                        final McpSchema.ReadResourceResult result;
                                        if (res.isBase64()) {
                                            result = new McpSchema.ReadResourceResult(Arrays.asList(new McpSchema.BlobResourceContents(
                                                    request.uri(),
                                                    functionResource.mimeType(),
                                                    res.getContent())));
                                        } else {
                                            result = new McpSchema.ReadResourceResult(Arrays.asList(new McpSchema.TextResourceContents(
                                                    request.uri(),
                                                    functionResource.mimeType(),
                                                    res.getContent())));
                                        }
                                        sink.success(result);
                                    }
                                });

                            });
                        });
                    });

            if (serverSupplier.get() != null) {
                serverSupplier.get().addResource(resourceSpec).block();
            } else {
                mcpServerSpec.resources(resourceSpec).build();
            }
        } catch (Throwable ex) {
            throw new McpException("Resource add failed, resource: " + functionResource.uri(), ex);
        }
    }

    private void addTml(McpServerProperties mcpServerProps, FunctionResource functionResource) {
        try {
            //resourceSpec
            McpServerFeatures.AsyncResourceTemplateSpecification resourceSpec = new McpServerFeatures.AsyncResourceTemplateSpecification(
                    McpSchema.ResourceTemplate.builder()
                            .uriTemplate(functionResource.uri())
                            .name(functionResource.name()).title(functionResource.title()).description(functionResource.description())
                            .mimeType(functionResource.mimeType()).build(),
                    (exchange, request) -> {
                        return Mono.create(sink -> {
                            ContextHolder.currentWith(new McpServerContext(exchange.transportContext()), () -> {
                                functionResource.handleAsync(request.uri()).whenComplete((res, err) -> {

                                    if (err != null) {
                                        err = Utils.throwableUnwrap(err);
                                        sink.error(new McpException(err.getMessage(), err));
                                    } else {
                                        final McpSchema.ReadResourceResult result;
                                        if (res.isBase64()) {
                                            result = new McpSchema.ReadResourceResult(Arrays.asList(new McpSchema.BlobResourceContents(
                                                    request.uri(),
                                                    functionResource.mimeType(),
                                                    res.getContent())));
                                        } else {
                                            result = new McpSchema.ReadResourceResult(Arrays.asList(new McpSchema.TextResourceContents(
                                                    request.uri(),
                                                    functionResource.mimeType(),
                                                    res.getContent())));
                                        }
                                        sink.success(result);
                                    }
                                });

                            });
                        });
                    });

            if (serverSupplier.get() != null) {
                serverSupplier.get().addResourceTemplate(resourceSpec).block();
            } else {
                mcpServerSpec.resourceTemplates(resourceSpec).build();
            }
        } catch (Throwable ex) {
            throw new McpException("ResourceTemplate add failed, resource: " + functionResource.uri(), ex);
        }
    }
}