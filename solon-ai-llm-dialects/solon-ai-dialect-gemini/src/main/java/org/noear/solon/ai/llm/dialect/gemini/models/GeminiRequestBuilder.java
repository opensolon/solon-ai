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
package org.noear.solon.ai.llm.dialect.gemini.models;

import org.noear.snack4.ONode;
import org.noear.solon.Utils;
import org.noear.solon.ai.chat.ChatConfig;
import org.noear.solon.ai.chat.ChatOptions;
import org.noear.solon.ai.chat.ChatResponseDefault;
import org.noear.solon.ai.chat.ChatRole;
import org.noear.solon.ai.chat.content.ContentBlock;
import org.noear.solon.ai.chat.content.ImageBlock;
import org.noear.solon.ai.chat.content.TextBlock;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.message.ToolMessage;
import org.noear.solon.ai.chat.message.UserMessage;
import org.noear.solon.ai.chat.tool.FunctionTool;
import org.noear.solon.ai.chat.tool.ToolCall;
import org.noear.solon.ai.chat.tool.ToolCallBuilder;
import org.noear.solon.ai.llm.dialect.gemini.models.model.GenerationConfig;
import org.noear.solon.ai.llm.dialect.gemini.models.model.ThinkingConfig;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Gemini 请求构建器
 * <p>
 * 负责构建符合 Gemini API 规范的请求 JSON，
 * 包括消息格式转换、工具定义等。
 * @author xujiaze
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
    public ONode build(ChatConfig config, ChatOptions options, List<ChatMessage> messages, boolean isStream) {
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

        Object reasoningEffort = null;
        for (Map.Entry<String, Object> kv : options.options().entrySet()) {
            if ("stream".equals(kv.getKey())) {
                continue;
            }
            
            String key = kv.getKey();
            Object value = kv.getValue();
            
            // 统一推理水平延后应用，避免被后续 generationConfig 整段覆盖
            if ("reasoning_effort".equals(key)) {
                reasoningEffort = value;
                continue;
            }
            
            if ("generationConfig".equals(key) && value instanceof Map) {
                GenerationConfig generationConfig = toGenerationConfig((Map<String, Object>) value);
                root.set("generationConfig", ONode.ofBean(generationConfig));
            } else {
                root.set(key, ONode.ofBean(value));
            }
        }
        
        // 最后合并 reasoning_effort，确保不被 generationConfig 反覆盖
        if (reasoningEffort != null) {
            applyReasoningEffortToGenerationConfig(root, reasoningEffort, options);
        }
    
        buildToolsNode(root, config, options);
    
        return root;
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
            buildAssistantToolCallMessageNode(node, (AssistantMessage) message);
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
    private void buildAssistantToolCallMessageNode(ONode node, AssistantMessage assistantMessage) {
        if (Utils.isNotEmpty(assistantMessage.getToolCalls())) {
            node.getOrNew("parts").asArray().then(n1 -> {
                boolean[] isFirst = {true};
                for (ToolCall call : assistantMessage.getToolCalls()) {
                    ONode partNode = n1.addNew();
                    partNode.getOrNew("functionCall").then(n2 -> {
                        n2.set("name", call.getName());
                        if (call.getArgumentsStr() != null) {
                            try {
                                ONode argsNode = ONode.ofJson(call.getArgumentsStr());
                                n2.set("args", argsNode);
                            } catch (Exception e) {
                                n2.set("args", ONode.ofBean(call.getArguments()));
                            }
                        } else {
                            n2.set("args", new ONode());
                        }
                    });
                    // thoughtSignature 位于 part 级别（functionCall 的同级），仅第一个 part 需要
                    if (isFirst[0] && Utils.isNotEmpty(call.getThoughtSignature())) {
                        partNode.set("thoughtSignature", call.getThoughtSignature());
                    }
                    isFirst[0] = false;
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
        if (message instanceof UserMessage && ((UserMessage) message).isMultiModal()) {
            ONode partsArr = node.getOrNew("parts").asArray();
            for (ContentBlock block : ((UserMessage) message).getBlocks()) {
                if (block instanceof TextBlock) {
                    partsArr.addNew().set("text", block.getContent());
                } else if (block instanceof ImageBlock) {
                    ImageBlock image = (ImageBlock) block;
                    ONode partNode = partsArr.addNew();
                    if (Utils.isNotEmpty(image.getData())) {
                        // base64 内联数据 → inline_data
                        partNode.getOrNew("inline_data").then(n -> {
                            n.set("mime_type", image.getMimeType());
                            n.set("data", image.getData());
                        });
                    } else if (Utils.isNotEmpty(image.getUrl())) {
                        // 文件 URI → file_data
                        partNode.getOrNew("file_data").then(n -> {
                            n.set("mime_type", image.getMimeType());
                            n.set("file_uri", image.getUrl());
                        });
                    }
                }
            }
        } else {
            String content = message.getContent();
            if (Utils.isNotEmpty(content)) {
                node.getOrNew("parts").asArray().addNew().set("text", content);
            }
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
        //Collection<FunctionTool> defaultTools = config.getDefaultTools();
        Collection<FunctionTool> tools = options.tools();

        if (Utils.isEmpty(tools)) {
            return;
        }

        root.getOrNew("tools").asArray().then(toolsNode -> {
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
    public ONode buildAssistantToolCallMessageNode(ChatResponseDefault resp, Map<String, ToolCallBuilder> toolCallBuilders) {
        ONode oNode = new ONode();
        oNode.set("role", "model");

        boolean[] isFirst = {true};
        oNode.getOrNew("parts").asArray().then(n1 -> {
            for (Map.Entry<String, ToolCallBuilder> kv : toolCallBuilders.entrySet()) {
                ToolCallBuilder builder = kv.getValue();
                ONode partNode = n1.addNew();
                partNode.getOrNew("functionCall").then(n2 -> {
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
                // 仅第一个 part 需要回传 thoughtSignature（并行调用时后续 part 不需要）
                if (isFirst[0] && Utils.isNotEmpty(resp.thinkingSignature)) {
                    partNode.set("thoughtSignature", resp.thinkingSignature);
                }
                isFirst[0] = false;
            }
        });

        return oNode;
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

        // 透传 thinkingConfig（若已存在）
        if (configMap.containsKey("thinkingConfig") && configMap.get("thinkingConfig") instanceof Map) {
            Map<String, Object> tcMap = (Map<String, Object>) configMap.get("thinkingConfig");
            ThinkingConfig thinkingConfig = new ThinkingConfig();
            Object includeThoughts = tcMap.get("includeThoughts");
            if (includeThoughts instanceof Boolean) {
                thinkingConfig.setIncludeThoughts((Boolean) includeThoughts);
            }
            Object budget = tcMap.get("thinkingBudget");
            if (budget instanceof Number) {
                thinkingConfig.setThinkingBudget(((Number) budget).intValue());
            }
            Object level = tcMap.get("thinkingLevel");
            if (level != null) {
                try {
                    thinkingConfig.setThinkingLevel(ThinkingConfig.ThinkingLevel.valueOf(String.valueOf(level).toUpperCase()));
                } catch (Exception ignored) {
                }
            }
            config.setThinkingConfig(thinkingConfig);
        }

        return config;
    }

    /**
     * 将统一 reasoning_effort 映射到 generationConfig.thinkingConfig。
     * <p>若用户已显式配置 thinkingConfig，则不覆盖。</p>
     * <p>仅设置 thinkingBudget（兼容 2.5 系）；不与 thinkingLevel 双写，
     * 避免部分模型拒识。Gemini 3.x 的 level 语义由 interactions 路径处理。</p>
     *
     * @since 4.0.4
     */
    @SuppressWarnings("unchecked")
    private void applyReasoningEffortToGenerationConfig(ONode root, Object value, ChatOptions options) {
        if (value == null) {
            return;
        }
        
        // 已有 generationConfig.thinkingConfig 时不覆盖
        Object genCfg = options.options().get("generationConfig");
        if (genCfg instanceof Map) {
            Object existing = ((Map<?, ?>) genCfg).get("thinkingConfig");
            if (existing != null) {
                return;
            }
        }
        if (root.hasKey("generationConfig")) {
            ONode genNode = root.get("generationConfig");
            if (genNode != null && genNode.hasKey("thinkingConfig")) {
                return;
            }
        }
        
        String effort = String.valueOf(value).trim().toLowerCase();
        int budget;
        switch (effort) {
            case "low":
                budget = 1024;
                break;
            case "medium":
                budget = 4096;
                break;
            case "high":
                budget = 8192;
                break;
            case "max":
                budget = 16384;
                break;
            default:
                return;
        }
            
        // 仅 budget：兼容 Gemini 2.5 thinkingBudget；避免与 thinkingLevel 双写冲突
        ThinkingConfig thinkingConfig = new ThinkingConfig()
                .setIncludeThoughts(true)
                .setThinkingBudget(budget);
                
        ONode genNode = root.getOrNew("generationConfig");
        genNode.set("thinkingConfig", ONode.ofBean(thinkingConfig));
    }
}
