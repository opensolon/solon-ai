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
package org.noear.solon.ai.llm.dialect.claude;

import org.noear.snack4.ONode;
import org.noear.solon.Utils;
import org.noear.solon.ai.chat.ChatResponseDefault;
import org.noear.solon.ai.chat.content.ContentBlock;
import org.noear.solon.ai.chat.ChatConfig;
import org.noear.solon.ai.chat.ChatOptions;
import org.noear.solon.ai.chat.ChatRole;
import org.noear.solon.ai.chat.message.*;
import org.noear.solon.ai.chat.tool.FunctionTool;
import org.noear.solon.ai.chat.tool.ToolCall;
import org.noear.solon.ai.chat.tool.ToolCallBuilder;
import org.noear.solon.ai.chat.content.ImageBlock;
import org.noear.solon.ai.chat.content.TextBlock;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Claude 请求构建器
 * @author oisin lu
 * @date 2026年1月27日
 */
public class ClaudeRequestBuilder {

    /**
     * 构建请求 JSON
     * @author oisin lu
     * @param config   聊天配置
     * @param options  聊天选项
     * @param messages 对话消息列表
     * @param isStream 是否使用流式模式
     * @return 符合 Messages API 规范的 JSON 字符串
     */
    public String build(ChatConfig config, ChatOptions options, List<ChatMessage> messages, boolean isStream) {
        ONode root = new ONode();

        if (Utils.isNotEmpty(config.getModel())) {
            root.set("model", config.getModel());
        }

        // Claude max_tokens 是必要参数
        // 默认最大输出token数，AWS 32000，ANTHROPIC 64000
        root.set("max_tokens", options.options().getOrDefault("max_tokens", 32000));

        // 提取系统消息
        String systemMessage = extractSystemMessage(messages);
        if (Utils.isNotEmpty(systemMessage)) {
            root.set("system", systemMessage);
        }

        // 构建消息数组，过滤掉系统消息
        ONode messagesNode = root.getOrNew("messages").asArray();
        for (ChatMessage m1 : messages) {
            if (!(m1 instanceof SystemMessage) && m1.isThinking() == false) {
                messagesNode.add(buildMessageNode(m1));
            }
        }

        // 设置流式模式参数
        if (isStream) {
            root.set("stream", true);
        }

        // 添加其他选项
        for (Map.Entry<String, Object> kv : options.options().entrySet()) {
            String key = kv.getKey();
            // 跳过Claude特有的字段，或已处理的字段
            if ("stream".equals(key) || "max_tokens".equals(key)) {
                continue;
            }

            // 处理思考模式配置
            if ("thinking".equals(key)) {
                buildThinkingNode(root, kv.getValue());
                continue;
            }

            root.set(key, ONode.ofBean(kv.getValue()));
        }

        buildToolsNode(root, options);

        return root.toJson();
    }

    /**
     * 构建思考模式配置
     * @author oisin lu
     * @date 2026年1月27日
     * Claude Extended Thinking 配置格式：
     * {
     *   "thinking": {
     *     "type": "enabled",
     *     "budget_tokens": 10000
     *   }
     * }
     * @param root 根节点
     * @param value 思考配置值
     */
    private void buildThinkingNode(ONode root, Object value) {
        if (value == null) {
            return;
        }

        ONode thinkingNode = root.getOrNew("thinking");

        if (value instanceof Map) {
            Map<String, Object> thinkingMap = (Map<String, Object>) value;

            // 检查是否启用思考模式
            Object enabled = thinkingMap.get("enabled");
            if (enabled != null && Boolean.TRUE.equals(enabled)) {
                thinkingNode.set("type", "enabled");
            } else if (thinkingMap.containsKey("type")) {
                thinkingNode.set("type", thinkingMap.get("type"));
            } else {
                thinkingNode.set("type", "enabled");
            }

            // 设置思考预算
            Object budgetTokens = thinkingMap.get("budget_tokens");
            if (budgetTokens == null) {
                budgetTokens = thinkingMap.get("budgetTokens");
            }
            if (budgetTokens == null) {
                budgetTokens = thinkingMap.get("thinkingBudget");
            }
            if (budgetTokens instanceof Number) {
                thinkingNode.set("budget_tokens", ((Number) budgetTokens).intValue());
            }
        } else if (value instanceof Boolean) {
            // 简化配置：thinking: true
            if (Boolean.TRUE.equals(value)) {
                thinkingNode.set("type", "enabled");
                thinkingNode.set("budget_tokens", 10000); // 默认预算必须要小于等于max_token
            }
        } else if (value instanceof Number) {
            // 简化配置：thinking: 10000 (直接指定预算)
            thinkingNode.set("type", "enabled");
            thinkingNode.set("budget_tokens", ((Number) value).intValue());
        }
    }

    /**
     * 提取系统消息
     * @author oisin lu
     * @date 2026年1月27日
     * @return 系统消息内容
     */
    private String extractSystemMessage(List<ChatMessage> messages) {
        StringBuilder systemMessage = new StringBuilder();
        for (ChatMessage message : messages) {
            if (message instanceof SystemMessage) {
                if (systemMessage.length() > 0) {
                    systemMessage.append("\n");
                }
                systemMessage.append(message.getContent());
            }
        }
        return systemMessage.toString();
    }

    /**
     * 构建消息
     * @author oisin lu
     * @date 2026年1月27日
     * @param message 消息
     * @return 消息节点
     */
    public ONode buildMessageNode(ChatMessage message) {
        ONode node = new ONode();

        ChatRole role = message.getRole();
        String roleStr = "user";
        if (role != null) {
            if (role == ChatRole.ASSISTANT) {
                roleStr = "assistant";
            } else if (role == ChatRole.SYSTEM) {
                roleStr = "user"; // 系统消息已经在顶层处理
            } else if (role == ChatRole.TOOL) {
                roleStr = "user";
            } else {
                roleStr = role.toString().toLowerCase();
            }
        }

        node.set("role", roleStr);

        if (message instanceof ToolMessage) {
            buildToolMessageNode(node, (ToolMessage) message);
        } else if (message instanceof AssistantMessage) {
            buildAssistantToolCallMessageNode(node, (AssistantMessage) message);
        } else {
            buildNormalMessageNode(node, message);
        }

        return node;
    }

    /**
     * 构建工具消息
     * @author oisin lu
     * @date 2026年1月27日
     * @param node 父节点
     * @param toolMessage 工具消息
     */
    private void buildToolMessageNode(ONode node, ToolMessage toolMessage) {
        // Claude使用tool_result格式
        ONode contentArray = node.getOrNew("content").asArray();
        contentArray.addNew()
            .set("type", "tool_result")
            .set("tool_use_id", toolMessage.getToolCallId())
            .set("content", toolMessage.getContent());
    }

    /**
     * 构建助手消息
     * @author oisin lu
     * @date 2026年1月27日
     * @param node 父节点
     * @param assistantMessage 助手消息
     */
    private void buildAssistantToolCallMessageNode(ONode node, AssistantMessage assistantMessage) {
        if (Utils.isNotEmpty(assistantMessage.getToolCalls())) {
            ONode contentArray = node.getOrNew("content").asArray();
            // 添加文本内容（如果有）
            if (Utils.isNotEmpty(assistantMessage.getContent())) {
                contentArray.addNew()
                    .set("type", "text")
                    .set("text", assistantMessage.getContent());
            }
            // 添加工具调用
            for (ToolCall call : assistantMessage.getToolCalls()) {
                contentArray.addNew()
                    .set("type", "tool_use")
                    .set("id", call.getId())
                    .set("name", call.getName())
                    .set("input", ONode.ofBean(call.getArguments()));
            }
        } else {
            String content = assistantMessage.getContent();
            if (Utils.isNotEmpty(content)) {
                node.set("content", content);
            } else {
                node.getOrNew("content").asArray(); // Claude需要content字段，即使是空数组
            }
        }
    }

    /**
     * 构建普通消息
     * @author oisin lu
     * @date 2026年1月27日
     * @param node 父节点
     * @param message 消息
     */
    private void buildNormalMessageNode(ONode node, ChatMessage message) {
        if (message instanceof UserMessage) {
            UserMessage userMessage = (UserMessage) message;
            if (userMessage.isMultiModal() == false) {
                //单模态
                node.set("content", userMessage.getContent());
            } else {
                //多模态
                ONode contentArray = node.getOrNew("content").asArray();
                // Claude支持图像上传
                for (ContentBlock block1 : userMessage.getBlocks()) {
                    if (block1 instanceof TextBlock) {
                        TextBlock text = (TextBlock) block1;
                        contentArray.addNew()
                                .set("type", "text")
                                .set("text", text.getContent());
                    } else if (block1 instanceof ImageBlock) {
                        ImageBlock image = (ImageBlock) block1;

                        // 从Image对象获取实际的媒体类型
                        String mediaType = image.getMimeType();

                        contentArray.addNew()
                                .set("type", "image")
                                .set("source", new ONode()
                                        .set("type", "base64")
                                        .set("media_type", mediaType)
                                        .set("data", image.toDataString(false)));
                    }
                }
            }
        } else {
            String content = message.getContent();
            if (Utils.isNotEmpty(content)) {
                node.set("content", content);
            } else {
                node.getOrNew("content").asArray();
            }
        }
    }

    /**
     * 构建工具
     * @author oisin lu
     * @date 2026年1月27日
     * @param root 根节点
     * @param options 聊天选项
     */
    public void buildToolsNode(ONode root, ChatOptions options) {
        Collection<FunctionTool> tools = options.tools();

        if (Utils.isEmpty(tools)) {
            return;
        }

        ONode toolsNode = root.getOrNew("tools").asArray();
        for (FunctionTool func : tools) {
            toolsNode.addNew().then(toolNode -> {
                toolNode.set("name", func.name());
                toolNode.set("description", func.descriptionAndMeta());
                
                String inputSchema = func.inputSchema();
                if (Utils.isNotEmpty(inputSchema)) {
                    try {
                        ONode schemaNode = ONode.ofJson(inputSchema);
                        toolNode.set("input_schema", schemaNode);
                    } catch (Exception e) {
                        // 如果JSON解析失败，创建一个基本的schema
                        toolNode.getOrNew("input_schema")
                            .set("type", "object")
                            .getOrNew("properties").set("", new ONode());
                    }
                } else {
                    toolNode.getOrNew("input_schema")
                        .set("type", "object")
                        .getOrNew("properties").set("", new ONode());
                }
            });
        }
    }

    /**
     * 构建助手消息（用于工具调用）
     * @author oisin lu
     * @date 2026年1月27日
     * @param toolCallBuilders 工具调用构建器
     * @return 助手消息
     */
    public ONode buildAssistantToolCallMessageNode(ChatResponseDefault resp, Map<String, ToolCallBuilder> toolCallBuilders) {
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
                // 如果解析失败，使用空对象
                inputObject = new HashMap<>();
            }

            contentArray.addNew()
                .set("type", "tool_use")
                .set("id", builder.idBuilder.toString())
                .set("name", builder.nameBuilder.toString())
                .set("input", ONode.ofBean(inputObject));
        }

        return node;
    }
}