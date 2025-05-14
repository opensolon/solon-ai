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
import org.noear.solon.ai.AiMedia;
import org.noear.solon.ai.chat.ChatRole;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.message.UserMessage;
import org.noear.solon.ai.mcp.exception.McpException;
import org.noear.solon.ai.mcp.server.McpServerContext;
import org.noear.solon.ai.mcp.server.prompt.FunctionPrompt;
import org.noear.solon.ai.media.Image;
import org.noear.solon.ai.util.ParamDesc;
import org.noear.solon.core.handle.ContextHolder;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 提示词服务端管理
 *
 * @author noear
 * @since 3.2
 */
public class PromptMcpServerManager implements McpServerManager<FunctionPrompt> {
    private final Map<String, FunctionPrompt> promptsMap = new ConcurrentHashMap<>();

    @Override
    public int count() {
        return promptsMap.size();
    }

    @Override
    public Collection<FunctionPrompt> all() {
        return promptsMap.values();
    }

    @Override
    public void remove(McpSyncServer server, String promptName) {
        if (server != null) {
            server.removePrompt(promptName);
            promptsMap.remove(promptName);
        }
    }

    @Override
    public void add(McpSyncServer server, McpServer.SyncSpecification mcpServerSpec, FunctionPrompt functionPrompt) {
        promptsMap.put(functionPrompt.name(), functionPrompt);

        List<McpSchema.PromptArgument> promptArguments = new ArrayList<>();
        for (ParamDesc p1 : functionPrompt.params()) {
            promptArguments.add(new McpSchema.PromptArgument(p1.name(), p1.description(), p1.required()));
        }

        McpServerFeatures.SyncPromptSpecification promptSpec = new McpServerFeatures.SyncPromptSpecification(
                new McpSchema.Prompt(functionPrompt.name(), functionPrompt.description(), promptArguments),
                (exchange, request) -> {
                    try {
                        ContextHolder.currentSet(new McpServerContext(exchange));

                        Collection<ChatMessage> prompts = functionPrompt.handle(request.getArguments());
                        List<McpSchema.PromptMessage> promptMessages = new ArrayList<>();
                        for (ChatMessage msg : prompts) {
                            if (msg.getRole() == ChatRole.ASSISTANT) {
                                promptMessages.add(new McpSchema.PromptMessage(McpSchema.Role.ASSISTANT, new McpSchema.TextContent(msg.getContent())));
                            } else if (msg.getRole() == ChatRole.USER) {
                                UserMessage userMessage = (UserMessage) msg;

                                if (Utils.isEmpty(userMessage.getMedias())) {
                                    //如果没有媒体
                                    promptMessages.add(new McpSchema.PromptMessage(McpSchema.Role.USER,
                                            new McpSchema.TextContent(msg.getContent())));
                                } else {
                                    //如果有，分解消息

                                    //1.先转媒体（如果是图片）
                                    for (AiMedia media : userMessage.getMedias()) {
                                        if (media instanceof Image) {
                                            Image mediaImage = (Image) media;
                                            if (mediaImage.getB64Json() != null) {
                                                promptMessages.add(new McpSchema.PromptMessage(McpSchema.Role.USER,
                                                        new McpSchema.ImageContent(null, null,
                                                                mediaImage.getB64Json(),
                                                                mediaImage.getMimeType())));
                                            } else {
                                                promptMessages.add(new McpSchema.PromptMessage(McpSchema.Role.USER,
                                                        new McpSchema.ImageContent(null, null,
                                                                mediaImage.getUrl(),
                                                                mediaImage.getMimeType())));
                                            }
                                        }
                                    }

                                    //2.再转文本
                                    if (Utils.isNotEmpty(msg.getContent())) {
                                        promptMessages.add(new McpSchema.PromptMessage(McpSchema.Role.USER,
                                                new McpSchema.TextContent(msg.getContent())));
                                    }
                                }
                            }
                        }

                        return new McpSchema.GetPromptResult(functionPrompt.description(), promptMessages);
                    } catch (Throwable ex) {
                        ex = Utils.throwableUnwrap(ex);
                        throw new McpException(ex.getMessage(), ex);
                    } finally {
                        ContextHolder.currentRemove();
                    }
                });

        if (server != null) {
            server.addPrompt(promptSpec);
        } else {
            mcpServerSpec.prompts(promptSpec);
        }
    }
}