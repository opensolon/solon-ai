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
package org.noear.solon.ai.chat.dialect;

import org.noear.snack4.ONode;
import org.noear.solon.Utils;
import org.noear.solon.ai.AiMedia;
import org.noear.solon.ai.media.Audio;
import org.noear.solon.ai.chat.*;
import org.noear.solon.ai.chat.tool.*;
import org.noear.solon.ai.chat.message.*;
import org.noear.solon.ai.media.Image;
import org.noear.solon.ai.media.Video;

import java.util.*;

/**
 * 聊天模型方言虚拟类
 *
 * @author noear
 * @since 3.1
 */
public abstract class AbstractChatDialect implements ChatDialect {

    protected void buildChatMessageNodeDo(ONode oNode, AssistantMessage msg) {
        oNode.set("role", msg.getRole().name().toLowerCase());

        if (Utils.isNotEmpty(msg.getResultContent())) {
            oNode.set("content", msg.getResultContent());
        }

        if (Utils.isNotEmpty(msg.getToolCallsRaw())) {
            oNode.set("tool_calls", ONode.ofBean(msg.getToolCallsRaw()));
        }
    }

    protected void buildChatMessageNodeDo(ONode oNode, SystemMessage msg) {
        oNode.set("role", msg.getRole().name().toLowerCase());
        oNode.set("content", msg.getContent());
    }

    protected void buildChatMessageNodeDo(ONode oNode, ToolMessage msg) {
        oNode.set("role", msg.getRole().name().toLowerCase());
        oNode.set("content", msg.getContent());

        if (Utils.isNotEmpty(msg.getName())) {
            oNode.set("name", msg.getName());
        }

        if (Utils.isNotEmpty(msg.getToolCallId())) {
            oNode.set("tool_call_id", msg.getToolCallId());
        }
    }

    protected void buildChatMessageNodeDo(ONode oNode, UserMessage msg) {
        oNode.set("role", msg.getRole().name().toLowerCase());
        if (Utils.isEmpty(msg.getMedias())) {
            oNode.set("content", msg.getContent());
        } else {
            oNode.getOrNew("content").then(n1 -> {
                if (Utils.isNotEmpty(msg.getContent())) {
                    n1.addNew().set("type", "text").set("text", msg.getContent());
                }

                for (AiMedia m1 : msg.getMedias()) {
                    ONode m1Node = null;
                    if (m1 instanceof Image) {
                        m1Node = n1.addNew();

                        m1Node.set("type", "image_url");
                        m1Node.getOrNew("image_url").set("url", m1.toDataString(true));

                    } else if (m1 instanceof Audio) {
                        m1Node = n1.addNew();

                        m1Node.set("type", "audio_url");
                        m1Node.getOrNew("audio_url").set("url", m1.toDataString(true));
                    } else if (m1 instanceof Video) {
                        m1Node = n1.addNew();

                        m1Node.set("type", "video_url");
                        m1Node.getOrNew("video_url").set("url", m1.toDataString(true));
                    }

                    if (m1Node != null) {
                        if (Utils.isNotEmpty(m1.metas())) {
                            for (Map.Entry<String, Object> entry : m1.metas().entrySet()) {
                                if (m1Node.hasKey(entry.getKey())) {
                                    m1Node.set(entry.getKey(), ONode.ofBean(entry.getValue()));
                                }
                            }
                        }
                    }
                }
            });
        }
    }


    public ONode buildChatMessageNode(ChatMessage chatMessage) {
        ONode oNode = new ONode();
        if (chatMessage instanceof AssistantMessage) {
            buildChatMessageNodeDo(oNode, (AssistantMessage) chatMessage);
        } else if (chatMessage instanceof SystemMessage) {
            buildChatMessageNodeDo(oNode, (SystemMessage) chatMessage);
        } else if (chatMessage instanceof ToolMessage) {
            buildChatMessageNodeDo(oNode, (ToolMessage) chatMessage);
        } else if (chatMessage instanceof UserMessage) {
            buildChatMessageNodeDo(oNode, (UserMessage) chatMessage);
        } else {
            throw new IllegalArgumentException("Unsupported chat message type: " + chatMessage.getClass());
        }

        return oNode;
    }

    /**
     * 构建请求工具节点
     */
    protected void buildReqToolsNode(ONode n, ChatConfig config, ChatOptions options, ChatMessage lastMessage) {
        buildReqToolsNodeDo(n, config.getDefaultTools());
        buildReqToolsNodeDo(n, options.tools());
    }

    protected void buildReqToolsNodeDo(ONode n, Collection<FunctionTool> tools) {
        if (Utils.isEmpty(tools)) {
            return;
        }

        n.getOrNew("tools").then(n1 -> {
            for (FunctionTool func : tools) {
                n1.addNew().then(n2 -> {
                    n2.set("type", "function");
                    n2.getOrNew("function").then(toolNode -> {
                        toolNode.set("name", func.name());
                        toolNode.set("description", func.description());
                        toolNode.set("parameters", ONode.ofJson(func.inputSchema()));
                    });
                });
            }
        });
    }

    @Override
    public String buildRequestJson(ChatConfig config, ChatOptions options, List<ChatMessage> messages, boolean isStream) {
        return new ONode().then(n -> {
            if (Utils.isNotEmpty(config.getModel())) {
                n.set("model", config.getModel());
            }

            n.getOrNew("messages").then(n1 -> {
                for (ChatMessage m1 : messages) {
                    if (m1.isThinking() == false) {
                        n1.add(buildChatMessageNode(m1));
                    }
                }
            });

            n.set("stream", isStream);

            for (Map.Entry<String, Object> kv : options.options().entrySet()) {
                n.set(kv.getKey(), ONode.ofBean(kv.getValue()));
            }

            ChatMessage lastMessage = messages.get(messages.size() - 1);
            buildReqToolsNode(n, config, options, lastMessage);
        }).toJson();
    }

    @Override
    public ONode buildAssistantMessageNode(Map<Integer, ToolCallBuilder> toolCallBuilders) {
        ONode oNode = new ONode();
        oNode.set("role", "assistant");
        oNode.set("content", "");
        oNode.getOrNew("tool_calls").asArray().then(n1 -> {
            for (Map.Entry<Integer, ToolCallBuilder> kv : toolCallBuilders.entrySet()) {
                //有可能没有
                n1.addNew().set("id", kv.getValue().idBuilder.toString())
                        .set("type", "function")
                        .getOrNew("function").then(n2 -> {
                            n2.set("name", kv.getValue().nameBuilder.toString());
                            if (kv.getValue().argumentsBuilder.length() > 0) {
                                n2.set("arguments", kv.getValue().argumentsBuilder.toString());
                            } else {
                                // vllm 不能传空
                                n2.set("arguments", "{}");
                            }
                        });
            }
        });

        return oNode;
    }

    @Override
    public AssistantMessage buildAssistantMessageByToolMessages(List<ToolMessage> toolMessages) {
        //要求直接返回（转为新的响应消息）
        StringBuffer buf = new StringBuffer();
        for (ToolMessage toolMessage : toolMessages) {
            if (buf.length() > 0) {
                buf.append('\n');
            }
            buf.append(toolMessage.getContent());
        }

        return ChatMessage.ofAssistant(buf.toString());
    }

    /**
     * 解析工具调用
     */
    protected List<ToolCall> parseToolCalls(ONode toolCallsNode) {
        if (toolCallsNode == null) {
            return null;
        }

        List<ToolCall> toolCalls = new ArrayList<>();

        for (ONode n1 : toolCallsNode.getArray()) {
            toolCalls.add(parseToolCall(n1));
        }

        return toolCalls;
    }

    protected ToolCall parseToolCall(ONode n1) {
        int index = n1.get("index").getInt();
        String callId = n1.get("id").getString();

        ONode n1f = n1.get("function");
        String name = n1f.get("name").getString();
        ONode n1fArgs = n1f.get("arguments");
        String argStr = n1fArgs.getString();

        if (n1fArgs.isString()) {
            //有可能是 json string（如果不是不用管，可能只是流的中间消息）
            if (hasNestedJsonBlock(argStr)) {
                n1fArgs = ONode.ofJson(argStr);
            }
        }

        Map<String, Object> argMap = null;
        if (n1fArgs.isObject()) {
            argMap = n1fArgs.toBean(Map.class);
        }
        return new ToolCall(index, callId, name, argStr, argMap);
    }

    protected String parseAssistantMessageContent(ChatResponseDefault resp, ONode oContent) {
        if (oContent.isValue()) {
            //一般输出都是单值
            return oContent.getValueAs();
        } else {
            ONode contentItem = null;
            if (oContent.isArray()) {
                //有些输出会是列表（取第一个）
                if (oContent.getArrayUnsafe().size() > 0) {
                    contentItem = oContent.get(0);
                }
            } else if (oContent.isObject()) {
                //有些输出会是字典
                contentItem = oContent;
            }

            if (contentItem != null) {
                if (contentItem.isObject()) {
                    //优先取文本
                    if (contentItem.hasKey("text")) {
                        return contentItem.get("text").getValueAs();
                    } else if (contentItem.hasKey("image")) {
                        return contentItem.get("image").getValueAs();
                    } else if (contentItem.hasKey("audio")) {
                        return contentItem.get("audio").getValueAs();
                    } else if (contentItem.hasKey("video")) {
                        return contentItem.get("video").getValueAs();
                    }
                } else if (contentItem.isValue()) {
                    return contentItem.getValueAs();
                }
            }
        }

        return null;
    }

    public List<AssistantMessage> parseAssistantMessage(ChatResponseDefault resp, ONode oMessage) {
        List<AssistantMessage> messageList = new ArrayList<>();

        ONode oContent = oMessage.get("content");

        String content = parseAssistantMessageContent(resp, oContent);
        ONode toolCallsNode = oMessage.getOrNull("tool_calls");
        ONode searchResultsNode = oMessage.getOrNull("search_results");

        List<Map> toolCallsRaw = null;
        List<ToolCall> toolCalls = parseToolCalls(toolCallsNode);
        List<Map> searchResultsRaw = null;

        if (Utils.isNotEmpty(toolCalls)) {
            toolCallsRaw = toolCallsNode.toBean(List.class);
            if (resp.in_thinking && resp.isStream()) {
                //说明是思考结束立刻调用了工具，需要添加思考的结束标识
                messageList.add(new AssistantMessage("</think>", true));
                messageList.add(new AssistantMessage("\n\n", false));
            }
            resp.in_thinking = false; //重置状态
        }

        if (searchResultsNode != null) {
            searchResultsRaw = searchResultsNode.toBean(List.class);
        }

        /**
         * 情况：
         * 有可能一直有：reasoning_content 或 reasoning
         * 有可能时有时无：reasoning_content 或 reasoning
         * 有可能一直无：...
         * 也可能和内容都为空: ...
         * */

        if (Utils.isEmpty(toolCallsRaw) && resp.hasToolCallBuilders() == false) {
            //如果没有工具调用（且没有工具构建）
            String reasoning_content = oMessage.get("reasoning_content").getValueAs();
            if (reasoning_content == null) {
                reasoning_content = oMessage.get("reasoning").getValueAs();
            }

            if (Utils.isNotEmpty(reasoning_content)) {
                resp.has_reasoning_field = true;
                //有思考专属内容的协议
                if (resp.isStream()) {
                    //如果是流返回（可能要拆成多条流消息）
                    if (Utils.isEmpty(content)) {
                        if (resp.in_thinking == false) {
                            //说明是第一次
                            messageList.add(new AssistantMessage("<think>", true));
                            messageList.add(new AssistantMessage("\n\n", true));
                            if (Utils.isNotEmpty(reasoning_content)) {
                                content = reasoning_content;
                            }
                        } else {
                            content = reasoning_content;
                        }

                        resp.in_thinking = true;
                    } else {
                        if (resp.in_thinking) {
                            //说明是最后一次
                            messageList.add(new AssistantMessage("</think>", true));
                            messageList.add(new AssistantMessage("\n\n", false));
                        }

                        resp.in_thinking = false;
                    }
                } else {
                    //如查是单次返回
                    if (Utils.isNotEmpty(reasoning_content)) {
                        content = "<think>\n\n" + reasoning_content + "</think>\n\n" + content;
                    }
                }
            } else if (Utils.isNotEmpty(content)) {
                if (resp.has_reasoning_field) { //有些情况，后面就没字段了
                    //有推理字段的
                    if (resp.in_thinking) {
                        if (resp.isStream()) {
                            //说明是最后一次
                            messageList.add(new AssistantMessage("</think>", true));
                            messageList.add(new AssistantMessage("\n\n", false));
                        }

                        resp.in_thinking = false;
                    }
                } else {
                    //分析 think 状态（无推理字段的）
                    if (resp.isStream()) {
                        //如果是流返回
                        if (content.startsWith("<think>")) {
                            resp.in_thinking = true;
                        } else {
                            if (resp.in_thinking) {
                                int thinkEnd = content.indexOf("</think>");
                                if (thinkEnd >= 0) { //可能是个开始符
                                    resp.in_thinking = false;
                                    messageList.add(new AssistantMessage(content, true));
                                    return messageList;
                                }
                            }
                        }
                    }
                }
            }
        }

        if (content != null || toolCallsRaw != null) {
            Object contentRaw = oContent.toBean();
            messageList.add(new AssistantMessage(content, resp.in_thinking, contentRaw, toolCallsRaw, toolCalls, searchResultsRaw));
        }

        return messageList;
    }


    protected boolean hasNestedJsonBlock(String str) {
        if (str == null || str.isEmpty()) {
            return false;
        }

        int start = 0;
        int end = str.length() - 1;

        // 跳过开头空白
        while (start <= end && Character.isWhitespace(str.charAt(start))) {
            start++;
        }

        // 跳过结尾空白
        while (end >= start && Character.isWhitespace(str.charAt(end))) {
            end--;
        }

        // 检查有效长度
        if (start >= end) {
            return false;
        }

        char first = str.charAt(start);
        char last = str.charAt(end);

        return (first == '{' && last == '}') || (first == '[' && last == ']');
    }
}