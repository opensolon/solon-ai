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
package org.noear.solon.ai.llm.dialect.gemini;

import org.noear.snack4.ONode;
import org.noear.solon.Utils;
import org.noear.solon.ai.chat.ChatConfig;
import org.noear.solon.ai.chat.ChatOptions;
import org.noear.solon.ai.chat.ChatRole;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.message.ToolMessage;
import org.noear.solon.ai.chat.tool.FunctionTool;
import org.noear.solon.ai.chat.tool.ToolCall;
import org.noear.solon.ai.chat.tool.ToolCallBuilder;
import org.noear.solon.ai.llm.dialect.gemini.model.GenerationConfig;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Gemini 请求构建器
 * <p>
 * 负责构建符合 Gemini API 规范的请求 JSON，
 * 包括消息格式转换、工具定义等。
 *
 * @author cwdhf
 * @since 3.1
 */
public class GeminiRequestBuilder {

    /**
     * 构建请求 JSON
     *
     * @param config   聊天配置
     * @param options  聊天选项
     * @param messages 对话消息列表
     * @param isStream 是否使用流式模式
     * @return 符合 Gemini API 规范的 JSON 字符串
     */
    public String build(ChatConfig config, ChatOptions options, List<ChatMessage> messages, boolean isStream) {
        ONode root = new ONode();

        if (Utils.isNotEmpty(config.getModel())) {
            root.set("model", config.getModel());
        }

        ONode contentsNode = root.getOrNew("contents").asArray();
        for (ChatMessage m1 : messages) {
            if (m1.isThinking() == false) {
                contentsNode.add(buildMessageNode(m1));
            }
        }

        for (Map.Entry<String, Object> kv : options.options().entrySet()) {
            if ("stream".equals(kv.getKey())) {
                continue;
            }

            String key = kv.getKey();
            Object value = kv.getValue();

            if ("generationConfig".equals(key) && value instanceof Map) {
                GenerationConfig generationConfig = toGenerationConfig((Map<String, Object>) value);
                root.set("generationConfig", ONode.ofBean(generationConfig));
            } else {
                root.set(key, ONode.ofBean(value));
            }
        }

        buildToolsNode(root, config, options);

        return root.toJson();
    }

    /**
     * 构建消息节点
     *
     * @param message 消息
     * @return 消息节点
     */
    public ONode buildMessageNode(ChatMessage message) {
        ONode node = new ONode();

        ChatRole role = message.getRole();
        String roleStr = "user";
        if (role != null) {
            if (role == ChatRole.ASSISTANT) {
                roleStr = "model";
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
            buildAssistantMessageNode(node, (AssistantMessage) message);
        } else {
            buildNormalMessageNode(node, message);
        }

        return node;
    }

    /**
     * 构建工具消息节点
     *
     * @param node         父节点
     * @param toolMessage  工具消息
     */
    private void buildToolMessageNode(ONode node, ToolMessage toolMessage) {
        node.getOrNew("parts").asArray().addNew().getOrNew("functionResponse").then(n2 -> {
            n2.set("name", toolMessage.getName());
            try {
                ONode responseNode = ONode.ofJson(toolMessage.getContent());
                n2.set("response", responseNode);
            } catch (Exception e) {
                ONode responseNode = new ONode();
                responseNode.set("result", toolMessage.getContent());
                n2.set("response", responseNode);
            }
        });
    }

    /**
     * 构建助手消息节点
     *
     * @param node              父节点
     * @param assistantMessage  助手消息
     */
    private void buildAssistantMessageNode(ONode node, AssistantMessage assistantMessage) {
        if (Utils.isNotEmpty(assistantMessage.getToolCalls())) {
            node.getOrNew("parts").asArray().then(n1 -> {
                for (ToolCall call : assistantMessage.getToolCalls()) {
                    n1.addNew().getOrNew("functionCall").then(n2 -> {
                        n2.set("name", call.name());
                        if (call.argumentsStr() != null) {
                            try {
                                ONode argsNode = ONode.ofJson(call.argumentsStr());
                                n2.set("args", argsNode);
                            } catch (Exception e) {
                                n2.set("args", ONode.ofBean(call.arguments()));
                            }
                        } else {
                            n2.set("args", new ONode());
                        }
                    });
                }
            });
        } else {
            String content = assistantMessage.getContent();
            if (Utils.isNotEmpty(content)) {
                node.getOrNew("parts").asArray().addNew().set("text", content);
            }
        }
    }

    /**
     * 构建普通消息节点
     *
     * @param node    父节点
     * @param message 消息
     */
    private void buildNormalMessageNode(ONode node, ChatMessage message) {
        String content = message.getContent();
        if (Utils.isNotEmpty(content)) {
            node.getOrNew("parts").asArray().addNew().set("text", content);
        }
    }

    /**
     * 构建工具节点
     *
     * @param root    根节点
     * @param config  聊天配置
     * @param options 聊天选项
     */
    public void buildToolsNode(ONode root, ChatConfig config, ChatOptions options) {
        Collection<FunctionTool> defaultTools = config.getDefaultTools();
        Collection<FunctionTool> tools = options.tools();

        if (Utils.isEmpty(defaultTools) && Utils.isEmpty(tools)) {
            return;
        }

        root.getOrNew("tools").asArray().then(toolsNode -> {
            if (Utils.isNotEmpty(defaultTools)) {
                for (FunctionTool func : defaultTools) {
                    toolsNode.addNew().then(toolNode -> {
                        toolNode.getOrNew("functionDeclarations").asArray().addNew().then(funcNode -> {
                            funcNode.set("name", func.name());
                            funcNode.set("description", func.descriptionAndMeta());
                            String inputSchema = func.inputSchema();
                            if (Utils.isNotEmpty(inputSchema)) {
                                try {
                                    ONode schemaNode = ONode.ofJson(inputSchema);
                                    funcNode.set("parameters", schemaNode);
                                } catch (Exception e) {
                                    funcNode.getOrNew("parameters").asArray();
                                }
                            } else {
                                funcNode.getOrNew("parameters").asArray();
                            }
                        });
                    });
                }
            }

            if (Utils.isNotEmpty(tools)) {
                for (FunctionTool func : tools) {
                    toolsNode.addNew().then(toolNode -> {
                        toolNode.getOrNew("functionDeclarations").asArray().addNew().then(funcNode -> {
                            funcNode.set("name", func.name());
                            funcNode.set("description", func.descriptionAndMeta());
                            String inputSchema = func.inputSchema();
                            if (Utils.isNotEmpty(inputSchema)) {
                                try {
                                    ONode schemaNode = ONode.ofJson(inputSchema);
                                    funcNode.set("parameters", schemaNode);
                                } catch (Exception e) {
                                    funcNode.getOrNew("parameters").asArray();
                                }
                            } else {
                                funcNode.getOrNew("parameters").asArray();
                            }
                        });
                    });
                }
            }
        });
    }

    /**
     * 构建助手消息节点（用于工具调用）
     *
     * @param toolCallBuilders 工具调用构建器
     * @return 助手消息节点
     */
    public ONode buildAssistantMessageNode(Map<String, ToolCallBuilder> toolCallBuilders) {
        ONode oNode = new ONode();
        oNode.set("role", "model");

        oNode.getOrNew("parts").asArray().then(n1 -> {
            for (Map.Entry<String, ToolCallBuilder> kv : toolCallBuilders.entrySet()) {
                ToolCallBuilder builder = kv.getValue();
                n1.addNew().getOrNew("functionCall").then(n2 -> {
                    n2.set("name", builder.nameBuilder.toString());
                    if (builder.argumentsBuilder.length() > 0) {
                        String argsStr = builder.argumentsBuilder.toString();
                        try {
                            ONode argsNode = ONode.ofJson(argsStr);
                            n2.set("args", argsNode);
                        } catch (Exception e) {
                            n2.set("args", argsStr);
                        }
                    } else {
                        n2.set("args", new ONode());
                    }
                });
            }
        });

        return oNode;
    }

    /**
     * 构建助手消息（通过工具消息）
     *
     * @param toolMessages 工具消息列表
     * @return 助手消息
     */
    public AssistantMessage buildAssistantMessageByToolMessages(List<ToolMessage> toolMessages) {
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
     * 转换配置映射为 GenerationConfig 对象
     *
     * @param configMap 配置映射
     * @return GenerationConfig 对象
     */
    private GenerationConfig toGenerationConfig(Map<String, Object> configMap) {
        GenerationConfig config = new GenerationConfig();

        if (configMap.containsKey("temperature")) {
            Object temp = configMap.get("temperature");
            if (temp instanceof Number) {
                config.setTemperature(((Number) temp).doubleValue());
            } else if (temp instanceof String) {
                try {
                    config.setTemperature(Double.parseDouble((String) temp));
                } catch (NumberFormatException e) {
                }
            }
        }

        if (configMap.containsKey("topP")) {
            Object topP = configMap.get("topP");
            if (topP instanceof Number) {
                config.setTopP(((Number) topP).doubleValue());
            } else if (topP instanceof String) {
                try {
                    config.setTopP(Double.parseDouble((String) topP));
                } catch (NumberFormatException e) {
                }
            }
        }

        if (configMap.containsKey("maxOutputTokens")) {
            Object maxTokens = configMap.get("maxOutputTokens");
            if (maxTokens instanceof Number) {
                config.setMaxOutputTokens(((Number) maxTokens).intValue());
            } else if (maxTokens instanceof String) {
                try {
                    config.setMaxOutputTokens(Integer.parseInt((String) maxTokens));
                } catch (NumberFormatException e) {
                }
            }
        }

        return config;
    }
}
