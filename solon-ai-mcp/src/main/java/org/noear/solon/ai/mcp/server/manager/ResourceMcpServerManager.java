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

/**
 * 资源服务端管理
 *
 * @author noear
 * @since 3.2
 */
public class ResourceMcpServerManager implements McpServerManager<FunctionResource> {
    private final Map<String, FunctionResource> resourcesMap = new ConcurrentHashMap<>();

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
    public void remove(McpAsyncServer server, String resourceUri) {
        if (server != null) {
            server.removeResource(resourceUri).block();
            resourcesMap.remove(resourceUri);
        }
    }

    @Override
    public void add(McpAsyncServer server, McpServer.AsyncSpecification mcpServerSpec, McpServerProperties mcpServerProps, FunctionResource functionResource) {
        try {
            resourcesMap.put(functionResource.uri(), functionResource);

            //resourceSpec
            McpServerFeatures.AsyncResourceSpecification resourceSpec = new McpServerFeatures.AsyncResourceSpecification(
                    McpSchema.Resource.builder()
                            .uri(functionResource.uri())
                            .name(functionResource.name()).title(functionResource.title()).description(functionResource.description())
                            .mimeType(functionResource.mimeType()).build(),
                    (exchange, request) -> {
                        return Mono.create(sink -> {
                            ContextHolder.currentWith(new McpServerContext(exchange), () -> {
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

            if (server != null) {
                server.addResource(resourceSpec).block();
            } else {
                mcpServerSpec.resources(resourceSpec).build();
            }
        } catch (Throwable ex) {
            throw new McpException("Resource add failed, resource: " + functionResource.uri(), ex);
        }
    }
}