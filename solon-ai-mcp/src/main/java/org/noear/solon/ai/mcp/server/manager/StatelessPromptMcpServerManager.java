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

import io.modelcontextprotocol.server.*;
import io.modelcontextprotocol.spec.McpSchema;
import org.noear.solon.Utils;
import org.noear.solon.ai.AiMedia;
import org.noear.solon.ai.chat.ChatRole;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.message.UserMessage;
import org.noear.solon.ai.mcp.exception.McpException;
import org.noear.solon.ai.mcp.server.McpServerContext;
import org.noear.solon.ai.mcp.server.McpServerProperties;
import org.noear.solon.ai.mcp.server.prompt.FunctionPrompt;
import org.noear.solon.ai.media.Image;
import org.noear.solon.ai.util.ParamDesc;
import org.noear.solon.core.handle.ContextHolder;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * 提示词服务端管理
 *
 * @author noear
 * @since 3.8.0
 */
public class StatelessPromptMcpServerManager implements McpServerManager<FunctionPrompt> {
    private final Map<String, FunctionPrompt> promptsMap = new ConcurrentHashMap<>();

    private final Supplier<McpStatelessAsyncServer> serverSupplier;
    private final McpServer.StatelessAsyncSpecification mcpServerSpec;

    public StatelessPromptMcpServerManager(Supplier<McpStatelessAsyncServer> serverSupplier, McpServer.StatelessAsyncSpecification mcpServerSpec) {
        this.serverSupplier = serverSupplier;
        this.mcpServerSpec = mcpServerSpec;
    }


    @Override
    public int count() {
        return promptsMap.size();
    }

    @Override
    public Collection<FunctionPrompt> all() {
        return promptsMap.values();
    }

    @Override
    public boolean contains(String promptName) {
        return promptsMap.containsKey(promptName);
    }

    @Override
    public void remove(String promptName) {
        if (serverSupplier.get() != null) {
            serverSupplier.get().removePrompt(promptName).block();
            promptsMap.remove(promptName);
        }
    }

    @Override
    public void add(McpServerProperties mcpServerProps, FunctionPrompt functionPrompt) {
        try {
            promptsMap.put(functionPrompt.name(), functionPrompt);

            List<McpSchema.PromptArgument> promptArguments = new ArrayList<>();
            for (ParamDesc p1 : functionPrompt.params()) {
                promptArguments.add(new McpSchema.PromptArgument(p1.name(), p1.description(), p1.required()));
            }

            McpStatelessServerFeatures.AsyncPromptSpecification promptSpec = new McpStatelessServerFeatures.AsyncPromptSpecification(
                    new McpSchema.Prompt(functionPrompt.name(), functionPrompt.title(), functionPrompt.description(), promptArguments),
                    (exchange, request) -> {
                        return Mono.create(sink -> {
                            ContextHolder.currentWith(new McpServerContext( exchange), () -> {
                                functionPrompt.handleAsync(request.arguments()).whenComplete((prompts, err) -> {
                                    if (err != null) {
                                        err = Utils.throwableUnwrap(err);
                                        sink.error(new McpException(err.getMessage(), err));
                                    } else {
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

                                        sink.success(new McpSchema.GetPromptResult(functionPrompt.description(), promptMessages));
                                    }
                                });
                            });
                        });
                    });

            if (serverSupplier.get() != null) {
                serverSupplier.get().addPrompt(promptSpec).block();
            } else {
                mcpServerSpec.prompts(promptSpec).build();
            }
        } catch (Throwable ex) {
            throw new McpException("Prompt add failed, prompt: " + functionPrompt.name(), ex);
        }
    }
}