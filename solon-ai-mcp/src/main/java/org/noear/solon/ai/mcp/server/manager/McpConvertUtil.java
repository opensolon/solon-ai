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

import io.modelcontextprotocol.spec.McpSchema;
import org.noear.snack4.ONode;
import org.noear.solon.Utils;
import org.noear.solon.ai.chat.ChatRole;
import org.noear.solon.ai.chat.content.*;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.message.UserMessage;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.chat.tool.FunctionTool;
import org.noear.solon.ai.chat.tool.ToolResult;
import org.noear.solon.ai.chat.tool.ToolSchemaUtil;
import org.noear.solon.ai.mcp.exception.McpException;
import org.noear.solon.ai.chat.resource.ResourceResult;
import org.noear.solon.ai.mcp.server.McpServerProperties;
import org.noear.solon.ai.chat.prompt.FunctionPrompt;
import org.noear.solon.ai.chat.resource.FunctionResource;
import reactor.core.publisher.MonoSink;

import java.util.*;

/**
 * Mcp 结果转换工具
 *
 * @author noear
 * @since 3.9.2
 */
public class McpConvertUtil {
    public static void toolResultConvert(MonoSink<McpSchema.CallToolResult> sink, McpServerProperties serverProps, FunctionTool fun, Object rst, Throwable err) {
        final McpSchema.CallToolResult result;

        if (err != null) {
            err = Utils.throwableUnwrap(err);
            result = new McpSchema.CallToolResult(Arrays.asList(new McpSchema.TextContent(err.getMessage())), true, fun.meta());
        } else {
            if (rst instanceof McpSchema.CallToolResult) {
                result = (McpSchema.CallToolResult) rst;
            } else if (rst instanceof McpSchema.Content) {
                result = new McpSchema.CallToolResult(Arrays.asList((McpSchema.Content) rst), false, fun.meta());
            } else if (rst instanceof ToolResult) {
                ToolResult toolResult = (ToolResult) rst;

                List<McpSchema.Content> contentList = new ArrayList<>();
                for (ContentBlock block1 : toolResult.getBlocks()) {
                    if (block1 instanceof TextBlock) {
                        contentList.add(new McpSchema.TextContent(null,
                                block1.getContent(),
                                block1.metas()));
                    } else if (block1 instanceof ImageBlock) {
                        contentList.add(new McpSchema.ImageContent(null,
                                block1.getContent(),
                                block1.getMimeType(),
                                block1.metas()));
                    } else if (block1 instanceof AudioBlock) {
                        contentList.add(new McpSchema.AudioContent(null,
                                block1.getContent(),
                                block1.getMimeType(),
                                block1.metas()));
                    }
                }

                result = new McpSchema.CallToolResult(contentList, false, fun.meta());
            } else {
                String rstStr = ToolSchemaUtil.resultConvert(fun, rst);

                if (serverProps.isEnableOutputSchema() && Utils.isNotEmpty(fun.outputSchema())) {
                    Map<String, Object> map = ONode.ofBean(rst).toBean(Map.class);
                    result = new McpSchema.CallToolResult(Arrays.asList(new McpSchema.TextContent(rstStr)), false, map, fun.meta());
                } else {
                    result = new McpSchema.CallToolResult(Arrays.asList(new McpSchema.TextContent(rstStr)), false, fun.meta());
                }
            }
        }

        sink.success(result);
    }

    public static void resourceResultConvert(MonoSink<McpSchema.ReadResourceResult> sink, McpServerProperties serverProps, McpSchema.ReadResourceRequest req, FunctionResource fun, Object rst, Throwable err) {
        if (err != null) {
            err = Utils.throwableUnwrap(err);
            sink.error(new McpException(err.getMessage(), err));
        } else {
            final McpSchema.ReadResourceResult result;

            if (rst instanceof McpSchema.ReadResourceResult) {
                result = (McpSchema.ReadResourceResult) rst;
            } else if (rst instanceof McpSchema.ResourceContents) {
                result = new McpSchema.ReadResourceResult(Arrays.asList((McpSchema.ResourceContents) rst), fun.meta());
            } else if (rst instanceof ResourceResult) {
                ResourceResult resourceResult = (ResourceResult) rst;

                List<McpSchema.ResourceContents> resourceList = new ArrayList<>();
                for (ResourceBlock block1 : resourceResult.getResources()) {
                    if (block1 instanceof TextBlock) {
                        resourceList.add(new McpSchema.TextResourceContents(req.uri(),
                                block1.getContent(),
                                block1.getMimeType(),
                                block1.metas()));
                    } else if (block1 instanceof BlobBlock) {
                        resourceList.add(new McpSchema.BlobResourceContents(req.uri(),
                                block1.getContent(),
                                block1.getMimeType(),
                                block1.metas()));
                    }
                }

                result = new McpSchema.ReadResourceResult(resourceList, fun.meta());
            } else if (rst instanceof TextBlock) {
                TextBlock res = (TextBlock) rst;
                result = new McpSchema.ReadResourceResult(Arrays.asList(new McpSchema.TextResourceContents(req.uri(),
                        fun.mimeType(),
                        res.getContent(),
                        res.metas())), fun.meta());
            } else if (rst instanceof BlobBlock) {
                BlobBlock res = (BlobBlock) rst;
                result = new McpSchema.ReadResourceResult(Arrays.asList(new McpSchema.BlobResourceContents(req.uri(),
                        fun.mimeType(),
                        res.getContent(),
                        res.metas())), fun.meta());
            } else if (rst instanceof byte[]) {
                String blob = Base64.getEncoder().encodeToString((byte[]) rst);

                result = new McpSchema.ReadResourceResult(Arrays.asList(new McpSchema.BlobResourceContents(req.uri(),
                        fun.mimeType(),
                        blob)), fun.meta());
            } else {
                String text = String.valueOf(rst);
                result = new McpSchema.ReadResourceResult(Arrays.asList(new McpSchema.TextResourceContents(req.uri(),
                        fun.mimeType(),
                        text)), fun.meta());
            }

            sink.success(result);
        }
    }

    public static void promptResultConvert(MonoSink<McpSchema.GetPromptResult> sink, McpServerProperties serverProps, FunctionPrompt fun, Object rst, Throwable err) {
        if (err != null) {
            err = Utils.throwableUnwrap(err);
            sink.error(new McpException(err.getMessage(), err));
        } else {
            McpSchema.GetPromptResult result = null;
            List<McpSchema.PromptMessage> promptMessages = new ArrayList<>();

            if (rst instanceof McpSchema.GetPromptResult) {
                result = (McpSchema.GetPromptResult) rst;
            } else if (rst instanceof McpSchema.PromptMessage) {
                promptMessages.add((McpSchema.PromptMessage) rst);
            } else if (rst instanceof Prompt) {
                Prompt promptResult = (Prompt) rst;

                for (ChatMessage item : promptResult.getMessages()) {
                    getMcpMessage(item, promptMessages);
                }

                result = new McpSchema.GetPromptResult(fun.description(), promptMessages, promptResult.attrs());
            } else if (rst instanceof ChatMessage) {
                getMcpMessage((ChatMessage) rst, promptMessages);
            } else if (rst instanceof Collection) {
                for (Object item : (Collection) rst) {
                    if (item instanceof McpSchema.PromptMessage) {
                        promptMessages.add((McpSchema.PromptMessage) item);
                    } else if (item instanceof ChatMessage) {
                        getMcpMessage((ChatMessage) item, promptMessages);
                    }
                }
            } else {
                String text = rst.toString();
                promptMessages.add(new McpSchema.PromptMessage(McpSchema.Role.USER, new McpSchema.TextContent(text)));
            }

            if (result == null) {
                result = new McpSchema.GetPromptResult(fun.description(), promptMessages, fun.meta());
            }

            sink.success(result);
        }
    }

    private static void getMcpMessage(ChatMessage msg, List<McpSchema.PromptMessage> promptMessages) {
        if (msg.getRole() == ChatRole.ASSISTANT) {
            promptMessages.add(new McpSchema.PromptMessage(McpSchema.Role.ASSISTANT,
                    new McpSchema.TextContent(null,
                            msg.getContent(),
                            msg.getMetadata())));
        } else if (msg.getRole() == ChatRole.USER) {
            UserMessage userMessage = (UserMessage) msg;

            if (userMessage.isMultiModal() == false) {
                //单模态
                promptMessages.add(new McpSchema.PromptMessage(McpSchema.Role.USER,
                        new McpSchema.TextContent(null, msg.getContent(), msg.getMetadata())));
            } else {
                //多模态
                for (ContentBlock block1 : userMessage.getBlocks()) {
                    if (block1 instanceof TextBlock) {
                        //文本
                        TextBlock text = (TextBlock) block1;

                        promptMessages.add(new McpSchema.PromptMessage(McpSchema.Role.USER,
                                new McpSchema.TextContent(null,
                                        text.getContent(),
                                        text.metas())));
                    } else if (block1 instanceof ImageBlock) {
                        //图片
                        ImageBlock image = (ImageBlock) block1;

                        if (image.getData() != null) {
                            promptMessages.add(new McpSchema.PromptMessage(McpSchema.Role.USER,
                                    new McpSchema.ImageContent(null,
                                            image.getData(),
                                            image.getMimeType(),
                                            image.metas())));
                        } else {
                            promptMessages.add(new McpSchema.PromptMessage(McpSchema.Role.USER,
                                    new McpSchema.ImageContent(null,
                                            image.getUrl(),
                                            image.getMimeType(),
                                            image.metas())));
                        }
                    } else if (block1 instanceof AudioBlock) {
                        AudioBlock audio = (AudioBlock) block1;

                        if (audio.getData() != null) {
                            promptMessages.add(new McpSchema.PromptMessage(McpSchema.Role.USER,
                                    new McpSchema.AudioContent(null,
                                            audio.getData(),
                                            audio.getMimeType(),
                                            audio.metas())));
                        } else {
                            promptMessages.add(new McpSchema.PromptMessage(McpSchema.Role.USER,
                                    new McpSchema.AudioContent(null,
                                            audio.getUrl(),
                                            audio.getMimeType(),
                                            audio.metas())));
                        }
                    }
                }
            }
        }
    }
}