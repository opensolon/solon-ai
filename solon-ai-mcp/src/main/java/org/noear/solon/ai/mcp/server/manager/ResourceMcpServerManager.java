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

import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.spec.McpSchema;
import org.noear.solon.Utils;
import org.noear.solon.ai.mcp.exception.McpException;
import org.noear.solon.ai.mcp.server.McpServerContext;
import org.noear.solon.ai.mcp.server.McpServerProperties;
import org.noear.solon.ai.mcp.server.resource.FunctionResource;
import org.noear.solon.ai.media.Text;
import org.noear.solon.core.handle.ContextHolder;

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
    public void remove(McpSyncServer server, String resourceUri) {
        if (server != null) {
            if (resourceUri.indexOf('{') < 0) {
                server.removeResource(resourceUri);
            } else {
                server.removeResourceTemplate(resourceUri);
            }

            resourcesMap.remove(resourceUri);
        }
    }

    @Override
    public void add(McpSyncServer server, McpServer.SyncSpecification mcpServerSpec, McpServerProperties mcpServerProps, FunctionResource functionResource) {
        resourcesMap.put(functionResource.uri(), functionResource);

        if (functionResource.uri().indexOf('{') < 0) {
            //resourceSpec
            McpServerFeatures.SyncResourceSpecification resourceSpec = new McpServerFeatures.SyncResourceSpecification(
                    new McpSchema.Resource(functionResource.uri(), functionResource.name(), functionResource.description(), functionResource.mimeType(), null),
                    (exchange, request) -> {
                        try {
                            ContextHolder.currentSet(new McpServerContext(exchange));

                            Text res = functionResource.handle(request.getUri());

                            if (res.isBase64()) {
                                return new McpSchema.ReadResourceResult(Arrays.asList(new McpSchema.BlobResourceContents(
                                        request.getUri(),
                                        functionResource.mimeType(),
                                        res.getContent())));
                            } else {
                                return new McpSchema.ReadResourceResult(Arrays.asList(new McpSchema.TextResourceContents(
                                        request.getUri(),
                                        functionResource.mimeType(),
                                        res.getContent())));
                            }
                        } catch (Throwable ex) {
                            ex = Utils.throwableUnwrap(ex);
                            throw new McpException(ex.getMessage(), ex);
                        } finally {
                            ContextHolder.currentRemove();
                        }
                    });

            if (server != null) {
                server.addResource(resourceSpec);
            } else {
                mcpServerSpec.resources(resourceSpec);
            }
        } else {
            //resourceTemplates
            McpSchema.Annotations annotations = null;//new McpSchema.Annotations(Arrays.asList(McpSchema.Role.USER), 0.5);

            McpServerFeatures.SyncResourceTemplateSpecification resourceSpec = new McpServerFeatures.SyncResourceTemplateSpecification(
                    new McpSchema.ResourceTemplate(functionResource.uri(),
                            functionResource.name(),
                            functionResource.description(),
                            functionResource.mimeType(),
                            annotations),
                    (exchange, request) -> {
                        try {
                            ContextHolder.currentSet(new McpServerContext(exchange));

                            Text res = functionResource.handle(request.getUri());

                            if (res.isBase64()) {
                                return new McpSchema.ReadResourceResult(Arrays.asList(new McpSchema.BlobResourceContents(
                                        request.getUri(),
                                        functionResource.mimeType(),
                                        res.getContent())));
                            } else {
                                return new McpSchema.ReadResourceResult(Arrays.asList(new McpSchema.TextResourceContents(
                                        request.getUri(),
                                        functionResource.mimeType(),
                                        res.getContent())));
                            }
                        } catch (Throwable ex) {
                            ex = Utils.throwableUnwrap(ex);
                            throw new McpException(ex.getMessage(), ex);
                        } finally {
                            ContextHolder.currentRemove();
                        }
                    });


            if (server != null) {
                server.addResourceTemplate(resourceSpec);
            } else {
                mcpServerSpec.resourceTemplates(resourceSpec);
            }
        }
    }
}