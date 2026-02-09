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
package org.noear.solon.ai.llm.dialect.openai;

import org.noear.snack4.ONode;
import org.noear.solon.Utils;
import org.noear.solon.ai.chat.media.ContentBlock;
import org.noear.solon.ai.chat.ChatConfig;
import org.noear.solon.ai.chat.ChatOptions;
import org.noear.solon.ai.chat.message.*;
import org.noear.solon.ai.chat.tool.FunctionTool;
import org.noear.solon.ai.chat.tool.ToolCall;
import org.noear.solon.ai.chat.tool.ToolCallBuilder;
import org.noear.solon.ai.chat.media.AudioBlock;
import org.noear.solon.ai.chat.media.ImageBlock;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * OpenAI Responses API 请求构建器
 * @author oisin lu
 * @date 2026年1月28日
 */
public class OpenaiResponsesRequestBuilder {

    /**
     * 构建请求 JSON
     * @author oisin lu
     * @date 2026年1月28日
     * @param config   聊天配置
     * @param options  聊天选项
     * @param messages 对话消息列表
     * @param isStream 是否使用流式模式
     * @return 符合 Responses JSON 字符串
     */
    public String build(ChatConfig config, ChatOptions options, List<ChatMessage> messages, boolean isStream) {
        ONode root = new ONode();
        if (Utils.isNotEmpty(config.getModel())) {
            root.set("model", config.getModel());
        }
        // 构建 input（将消息转为 input 数组）
        ONode inputArray = root.getOrNew("input").asArray();
        for (ChatMessage msg : messages) {
            if (msg.isThinking()) {
                continue; // 跳过思考消息
            }
            buildInputItem(inputArray, msg);
        }
        // 设置流式模式参数
        if (isStream) {
            root.set("stream", true);
        }
        // 添加其他选项
        for (Map.Entry<String, Object> kv : options.options().entrySet()) {
            String key = kv.getKey();
            // 跳过已处理的字段
            if ("stream".equals(key)) {
                continue;
            }
            // max_tokens -> max_output_tokens 转换
            if ("max_tokens".equals(key)) {
                root.set("max_output_tokens", kv.getValue());
                continue;
            }
            // 处理思考级别配置
            if ("reasoning".equals(key)) {
                buildReasoningNode(root, kv.getValue());
                continue;
            }
            root.set(key, ONode.ofBean(kv.getValue()));
        }
        // 构建 tools
        buildToolsNode(root, options);
        return root.toJson();
    }

    /**
     * 构建 input
     * @author oisin lu
     * @date 2026年1月28日
     */
    private void buildInputItem(ONode inputArray, ChatMessage message) {
        if (message instanceof SystemMessage) {
            // 系统消息: {"type": "message", "role": "system", "content": "..."}
            inputArray.addNew()
                    .set("type", "message")
                    .set("role", "system")
                    .set("content", message.getContent() != null ? message.getContent() : "");
        } else if (message instanceof ToolMessage) {
            // 工具结果: {"type": "function_call_output", "call_id": "xxx", "output": "..."}
            ToolMessage toolMessage = (ToolMessage) message;
            inputArray.addNew()
                    .set("type", "function_call_output")
                    .set("call_id", toolMessage.getToolCallId())
                    .set("output", toolMessage.getContent());
        } else if (message instanceof AssistantMessage) {
            AssistantMessage assistantMessage = (AssistantMessage) message;
            if (Utils.isNotEmpty(assistantMessage.getToolCalls())) {
                // 带工具调用的助手消息，需要拆分
                // 先添加文本内容（如果存在）
                if (Utils.isNotEmpty(assistantMessage.getContent())) {
                    inputArray.addNew()
                            .set("type", "message")
                            .set("role", "assistant")
                            .set("content", assistantMessage.getContent());
                }
                // 添加工具调用
                for (ToolCall call : assistantMessage.getToolCalls()) {
                    inputArray.addNew()
                            .set("type", "function_call")
                            .set("call_id", call.id())
                            .set("name", call.name())
                            .set("arguments", call.argumentsStr());
                }
            } else {
                // 普通助手消息
                inputArray.addNew()
                        .set("type", "message")
                        .set("role", "assistant")
                        .set("content", assistantMessage.getContent() != null ? assistantMessage.getContent() : "");
            }
        } else if (message instanceof UserMessage) {
            UserMessage userMessage = (UserMessage) message;
            ONode msgNode = inputArray.addNew()
                    .set("type", "message")
                    .set("role", "user");
            if (userMessage.isMultiModal() == false) {
                // 纯文本消息
                msgNode.set("content", userMessage.getContent());
            } else {
                // 多模态消息
                ONode contentArray = msgNode.getOrNew("content").asArray();
                // 添加文本内容（如果存在）
                if (Utils.isNotEmpty(userMessage.getContent())) {
                    contentArray.addNew()
                            .set("type", "input_text")
                            .set("text", userMessage.getContent());
                }
                // 添加媒体内容（图像、音频等）
                for (ContentBlock block1 : userMessage.getBlocks()) {
                    if (block1 instanceof ImageBlock) {
                        ImageBlock image = (ImageBlock) block1;
                        contentArray.addNew()
                                .set("type", "input_image")
                                .set("image_url", image.toDataString(true));
                    } else if (block1 instanceof AudioBlock) {
                        AudioBlock audio = (AudioBlock) block1;
                        ONode audioNode = contentArray.addNew()
                                .set("type", "input_audio");
                        // Responses API 音频格式：data (base64) 和 format
                        audioNode.set("data", audio.getB64Json());
                        // 从 mimeType 提取格式，如 audio/mp3 -> mp3
                        String mimeType = audio.getMimeType();
                        if (Utils.isNotEmpty(mimeType) && mimeType.startsWith("audio/")) {
                            audioNode.set("format", mimeType.substring(6));
                        }
                    }
                }
            }
        } else {
            // 其他类型消息
            String role = message.getRole() != null ? message.getRole().name().toLowerCase() : "user";
            inputArray.addNew()
                    .set("type", "message")
                    .set("role", role)
                    .set("content", message.getContent() != null ? message.getContent() : "");
        }
    }

    /**
     * 构建思考级别配置
     * @author oisin lu
     * @date 2026年1月28日
     * OpenAI Responses reasoning 配置格式：
     * {
     *   "reasoning": {
     *     "effort": "low" | "medium" | "high"
     *   }
     * }
     */
    @SuppressWarnings("unchecked")
    private void buildReasoningNode(ONode root, Object value) {
        if (value == null) {
            return;
        }
        ONode reasoningNode = root.getOrNew("reasoning");
        if (value instanceof Map) {
            Map<String, Object> reasoningMap = (Map<String, Object>) value;
            Object effort = reasoningMap.get("effort");
            if (effort != null) {
                reasoningNode.set("effort", effort.toString());
            }
            // 支持 summary 配置（reasoning summary）
            Object summary = reasoningMap.get("summary");
            if (summary != null) {
                reasoningNode.set("summary", summary.toString());
            }
        } else if (value instanceof String) {
            // 简化配置：reasoning: "high"
            reasoningNode.set("effort", value.toString());
        }
    }

    /**
     * 构建工具
     * @author oisin lu
     * @date 2026年1月28日
     */
    public void buildToolsNode(ONode root, ChatOptions options) {
        Collection<FunctionTool> tools = options.tools();
        if (Utils.isEmpty(tools)) {
            return;
        }
        ONode toolsNode = root.getOrNew("tools").asArray();
        for (FunctionTool func : tools) {
            toolsNode.addNew().then(toolNode -> {
                toolNode.set("type", "function");
                toolNode.set("name", func.name());
                toolNode.set("description", func.descriptionAndMeta());
                String inputSchema = func.inputSchema();
                if (Utils.isNotEmpty(inputSchema)) {
                    try {
                        ONode schemaNode = ONode.ofJson(inputSchema);
                        toolNode.set("parameters", schemaNode);
                    } catch (Exception e) {
                        // 如果 JSON 解析失败，创建一个基本的 schema
                        toolNode.getOrNew("parameters")
                                .set("type", "object")
                                .getOrNew("properties").set("", new ONode());
                    }
                } else {
                    toolNode.getOrNew("parameters")
                            .set("type", "object")
                            .getOrNew("properties").set("", new ONode());
                }
            });
        }
    }

    /**
     * 构建助手消息（用于工具调用后的多轮对话）
     * @author oisin lu
     * @date 2026年1月28日
     */
    public ONode buildAssistantMessageNode(Map<String, ToolCallBuilder> toolCallBuilders) {
        // Responses API 中工具调用不需要显式的助手消息节点
        // 而是直接在 input 中添加 function_call 项
        // 这里返回一个兼容的结构
        ONode node = new ONode();
        node.set("role", "assistant");
        ONode contentArray = node.getOrNew("content").asArray();
        for (Map.Entry<String, ToolCallBuilder> kv : toolCallBuilders.entrySet()) {
            ToolCallBuilder builder = kv.getValue();
            // 解析参数 JSON 字符串为对象
            Object inputObject;
            String argsStr = builder.argumentsBuilder.toString();
            try {
                if (Utils.isNotEmpty(argsStr)) {
                    ONode argsNode = ONode.ofJson(argsStr);
                    inputObject = argsNode.toBean(Map.class);
                } else {
                    inputObject = new HashMap<>();
                }
            } catch (Exception e) {
                inputObject = new HashMap<>();
            }
            contentArray.addNew()
                    .set("type", "function_call")
                    .set("call_id", builder.idBuilder.toString())
                    .set("name", builder.nameBuilder.toString())
                    .set("arguments", argsStr);
        }
        return node;
    }

    /**
     * 构建助手消息（通过工具消息）
     * @author oisin lu
     * @date 2026年1月28日
     */
    public AssistantMessage buildAssistantMessageByToolMessages(List<ToolMessage> toolMessages) {
        StringBuilder buf = new StringBuilder();
        for (ToolMessage toolMessage : toolMessages) {
            if (buf.length() > 0) {
                buf.append('\n');
            }
            buf.append(toolMessage.getContent());
        }
        return ChatMessage.ofAssistant(buf.toString());
    }
}
