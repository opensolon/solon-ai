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
package org.noear.solon.ai.llm.dialect.gemini.interactions;

import org.noear.snack4.ONode;
import org.noear.solon.Utils;
import org.noear.solon.ai.chat.ChatConfig;
import org.noear.solon.ai.chat.ChatOptions;
import org.noear.solon.ai.chat.ChatResponseDefault;
import org.noear.solon.ai.chat.content.ContentBlock;
import org.noear.solon.ai.chat.content.ImageBlock;
import org.noear.solon.ai.chat.content.TextBlock;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.message.SystemMessage;
import org.noear.solon.ai.chat.message.ToolMessage;
import org.noear.solon.ai.chat.message.UserMessage;
import org.noear.solon.ai.chat.tool.FunctionTool;
import org.noear.solon.ai.chat.tool.ToolCall;
import org.noear.solon.ai.chat.tool.ToolCallBuilder;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Gemini Interactions API 请求构建器
 * <p>
 * 负责构建符合 Gemini Interactions API 规范的请求 JSON。
 * Interactions API 使用 step 序列（input[]）替代 Generate Content API
 * 的 contents[] 格式，支持有状态（previous_interaction_id）和无状态两种对话模式。
 *
 * @since 3.1
 */
public class GeminiInteractionsRequestBuilder {

    /**
     * 构建请求 JSON
     *
     * @param config   聊天配置
     * @param options  聊天选项
     * @param messages 对话消息列表
     * @param isStream 是否使用流式模式
     * @return 符合 Interactions API 规范的 ONode
     */
    public ONode build(ChatConfig config, ChatOptions options,
                        List<ChatMessage> messages, boolean isStream) {
        ONode root = new ONode();

        // 1. model
        if (Utils.isNotEmpty(config.getModel())) {
            root.set("model", config.getModel());
        }

        // 2. system_instruction: 从消息中提取第一个 SystemMessage
        ONode sysInstNode = buildSystemInstruction(messages);
        if (sysInstNode != null) {
            root.set("system_instruction", sysInstNode);
        }

        // 3. input[]: 构建 step 序列（跳过 system message 和 thinking 消息）
        ONode inputArr = root.getOrNew("input").asArray();
        for (ChatMessage msg : messages) {
            if (msg instanceof SystemMessage) {
                continue; // system_instruction 已在顶层处理
            }
            if (msg.isThinking()) {
                continue; // 跳过思考标记消息
            }
            List<ONode> steps = buildStepsFromMessage(msg);
            for (ONode step : steps) {
                inputArr.add(step);
            }
        }

        // 4. config: 从 options.options() 映射
        ONode configNode = buildConfigNode(options);
        if (configNode != null && configNode.size() > 0) {
            root.set("config", configNode);
        }

        // 5. stream
        root.set("stream", isStream);

        // 6. store: 默认 false（无状态模式）
        root.set("store", false);

        // 7. tools
        buildToolsNode(root, options);

        // 8. response_format
        buildResponseFormatNode(root, options);

        // 9. 额外 options（跳过已处理的 key）
        for (Map.Entry<String, Object> kv : options.options().entrySet()) {
            String key = kv.getKey();
            if ("stream".equals(key)
                    || "generationConfig".equals(key)
                    || "response_format".equals(key)
                    || "reasoning_effort".equals(key)) {
                continue;
            }
            if (!root.hasKey(key)) {
                root.set(key, ONode.ofBean(kv.getValue()));
            }
        }

        return root;
    }

    /**
     * 从消息列表中提取 system_instruction
     */
    private ONode buildSystemInstruction(List<ChatMessage> messages) {
        for (ChatMessage msg : messages) {
            if (msg instanceof SystemMessage) {
                String content = msg.getContent();
                if (Utils.isNotEmpty(content)) {
                    ONode node = new ONode();
                    // system_instruction 在 Interactions API 中是 string 或 Content[]
                    // 使用字符串形式更简单
                    node.set("text", content);
                    return node;
                }
            }
        }
        return null;
    }

    /**
     * 将单条聊天消息转换为一个或多个 step（用于 input[]）
     */
    private List<ONode> buildStepsFromMessage(ChatMessage msg) {
        List<ONode> steps = new ArrayList<>();

        if (msg instanceof UserMessage) {
            steps.add(buildUserInputStep((UserMessage) msg));
        } else if (msg instanceof AssistantMessage) {
            steps.addAll(buildAssistantSteps((AssistantMessage) msg));
        } else if (msg instanceof ToolMessage) {
            steps.add(buildFunctionResultStep((ToolMessage) msg));
        } else {
            // 兜底：作为 user_input 处理
            ONode step = new ONode();
            step.set("type", "user_input");
            ONode contentArr = step.getOrNew("content").asArray();
            contentArr.addNew().set("type", "text").set("text", msg.getContent());
            steps.add(step);
        }

        return steps;
    }

    /**
     * 构建 user_input step
     */
    private ONode buildUserInputStep(UserMessage msg) {
        ONode step = new ONode();
        step.set("type", "user_input");

        ONode contentArr = step.getOrNew("content").asArray();
        if (msg.isMultiModal()) {
            for (ContentBlock block : msg.getBlocks()) {
                contentArr.add(buildContentBlock(block));
            }
        } else {
            String content = msg.getContent();
            if (Utils.isNotEmpty(content)) {
                contentArr.addNew().set("type", "text").set("text", content);
            }
        }

        return step;
    }

    /**
     * 构建助手消息 step 列表
     * <p>
     * 如果 AssistantMessage 包含 tool_calls，则为每个工具调用生成一个
     * function_call step；否则生成一个 model_output step。
     */
    private List<ONode> buildAssistantSteps(AssistantMessage msg) {
        List<ONode> steps = new ArrayList<>();

        if (Utils.isNotEmpty(msg.getToolCalls())) {
            boolean isFirst = true;
            for (ToolCall call : msg.getToolCalls()) {
                ONode step = new ONode();
                step.set("type", "function_call");
                step.set("name", call.getName());
                // Interactions API: function_call step 使用 "id" 字段
                if (Utils.isNotEmpty(call.getId())) {
                    step.set("id", call.getId());
                } else {
                    step.set("id", call.getName() + "_" + System.currentTimeMillis());
                }
                // arguments
                if (call.getArgumentsStr() != null) {
                    try {
                        ONode argsNode = ONode.ofJson(call.getArgumentsStr());
                        step.set("arguments", argsNode);
                    } catch (Exception e) {
                        step.set("arguments", call.getArgumentsStr());
                    }
                } else {
                    step.set("arguments", new ONode());
                }
                // thought signature 仅放在第一个 function_call step
                if (isFirst && Utils.isNotEmpty(call.getThoughtSignature())) {
                    step.set("thought_signature", call.getThoughtSignature());
                }
                isFirst = false;
                steps.add(step);
            }
        } else {
            // 纯文本响应
            ONode step = new ONode();
            step.set("type", "model_output");
            String content = msg.getContent();
            if (Utils.isNotEmpty(content)) {
                ONode contentArr = step.getOrNew("content").asArray();
                contentArr.addNew().set("type", "text").set("text", content);
            }
            steps.add(step);
        }

        return steps;
    }

    /**
     * 构建 function_result step（工具调用结果）
     */
    private ONode buildFunctionResultStep(ToolMessage msg) {
        ONode step = new ONode();
        step.set("type", "function_result");

        if (Utils.isNotEmpty(msg.getName())) {
            step.set("name", msg.getName());
        }
        if (Utils.isNotEmpty(msg.getToolCallId())) {
            step.set("call_id", msg.getToolCallId());
        }

        // result[] — 直接数组，每个元素是 FunctionResultSubcontent
        ONode resultArr = step.getOrNew("result").asArray();
        resultArr.addNew().set("type", "text").set("text", msg.getContent());

        return step;
    }

    /**
     * 构建多模态内容块
     */
    private ONode buildContentBlock(ContentBlock block) {
        ONode node = new ONode();
        if (block instanceof TextBlock) {
            node.set("type", "text");
            node.set("text", block.getContent());
        } else if (block instanceof ImageBlock) {
            ImageBlock image = (ImageBlock) block;
            if (Utils.isNotEmpty(image.getData())) {
                // base64 内联数据
                node.set("type", "inline_data");
                node.set("mime_type", image.getMimeType());
                node.set("data", image.getData());
            } else if (Utils.isNotEmpty(image.getUrl())) {
                // 文件 URI
                node.set("type", "file_data");
                node.set("mime_type", image.getMimeType());
                node.set("file_uri", image.getUrl());
            }
        }
        // AudioBlock, VideoBlock 等可根据需要扩展
        return node;
    }

    /**
     * 构建 config 节点（从 options 中的 generationConfig 映射）
     * <p>
     * 将 Generate Content API 的 generationConfig 字段映射为 Interactions API 的 config 字段：
     * <ul>
     *   <li>temperature → config.temperature</li>
     *   <li>topP → config.top_p</li>
     *   <li>maxOutputTokens → config.max_output_tokens</li>
     *   <li>stopSequences → config.stop_sequences</li>
     *   <li>thinkingConfig.includeThoughts → config.thinking_summaries</li>
     *   <li>thinkingConfig.thinkingBudget → config.thinking_level (low/medium/high)</li>
     *   <li>thinkingConfig.thinkingLevel → config.thinking_level</li>
     *   <li>responseModalities → config.response_modalities</li>
     * </ul>
     */
    private ONode buildConfigNode(ChatOptions options) {
        Map<String, Object> opts = options.options();
        if (opts.isEmpty()) {
            return null;
        }

        ONode config = new ONode();

        // 从 generationConfig 提取配置
        Map<String, Object> genConfig = null;
        if (opts.containsKey("generationConfig") && opts.get("generationConfig") instanceof Map) {
            genConfig = (Map<String, Object>) opts.get("generationConfig");
        }

        if (genConfig != null) {
            // temperature
            if (genConfig.containsKey("temperature")) {
                config.set("temperature", ONode.ofBean(genConfig.get("temperature")));
            }
            // top_p
            if (genConfig.containsKey("topP")) {
                config.set("top_p", ONode.ofBean(genConfig.get("topP")));
            }
            // max_output_tokens
            if (genConfig.containsKey("maxOutputTokens")) {
                config.set("max_output_tokens", ONode.ofBean(genConfig.get("maxOutputTokens")));
            }
            // stop_sequences
            if (genConfig.containsKey("stopSequences")) {
                config.set("stop_sequences", ONode.ofBean(genConfig.get("stopSequences")));
            }
            // response_modalities
            if (genConfig.containsKey("responseModalities")) {
                config.set("response_modalities", ONode.ofBean(genConfig.get("responseModalities")));
            }

            // 思考配置
            if (genConfig.containsKey("thinkingConfig") && genConfig.get("thinkingConfig") instanceof Map) {
                Map<String, Object> tc = (Map<String, Object>) genConfig.get("thinkingConfig");

                // thinking_summaries
                if (tc.containsKey("includeThoughts")) {
                    config.set("thinking_summaries", ONode.ofBean(tc.get("includeThoughts")));
                }

                // thinking_level: 优先使用显式指定的 thinkingLevel
                if (tc.containsKey("thinkingLevel")) {
                    Object level = tc.get("thinkingLevel");
                    if (level != null) {
                        String levelStr = level.toString();
                        // 处理枚举值 "THINKING_LEVEL_UNSPECIFIED", "LOW", "HIGH" 等
                        if (levelStr.contains("LOW") || "low".equalsIgnoreCase(levelStr)) {
                            config.set("thinking_level", "low");
                        } else if (levelStr.contains("HIGH") || "high".equalsIgnoreCase(levelStr)) {
                            config.set("thinking_level", "high");
                        } else if ("medium".equalsIgnoreCase(levelStr)) {
                            config.set("thinking_level", "medium");
                        }
                        // UNSPECIFIED 不设置
                    }
                } else if (tc.containsKey("thinkingBudget")) {
                    // 通过 thinkingBudget 推断 thinking_level
                    Object budget = tc.get("thinkingBudget");
                    if (budget instanceof Number) {
                        int b = ((Number) budget).intValue();
                        if (b > 8192) {
                            config.set("thinking_level", "high");
                        } else if (b > 2048) {
                            config.set("thinking_level", "medium");
                        } else {
                            config.set("thinking_level", "low");
                        }
                    }
                }
            }
        }

        // 统一 reasoning_effort → thinking_level（无 thinkingConfig 时生效）
        Object effortObj = opts.get("reasoning_effort");
        if (effortObj != null && !config.hasKey("thinking_level")) {
            String effort = String.valueOf(effortObj).trim().toLowerCase();
            if ("low".equals(effort)) {
                config.set("thinking_level", "low");
            } else if ("medium".equals(effort)) {
                config.set("thinking_level", "medium");
            } else if ("high".equals(effort) || "max".equals(effort)) {
                config.set("thinking_level", "high");
            }
            if (config.hasKey("thinking_level") && !config.hasKey("thinking_summaries")) {
                config.set("thinking_summaries", true);
            }
        }

        return config.size() > 0 ? config : null;
    }

    /**
     * 构建 tools 节点
     * <p>
     * Interactions API 的 tools 结构：
     * <pre>
     * tools: [
     *   { "type": "function", "name": "...", "description": "...", "parameters": {...} }
     * ]
     * </pre>
     * 与 Generate Content API（functionDeclarations[] 包装）不同，
     * Interactions API 采用扁平化结构，每个工具元素通过 type 字段做多态鉴别。
     */
    private void buildToolsNode(ONode root, ChatOptions options) {
        Collection<FunctionTool> tools = options.tools();
        if (Utils.isEmpty(tools)) {
            return;
        }

        root.getOrNew("tools").asArray().then(toolsNode -> {
            for (FunctionTool func : tools) {
                toolsNode.addNew().then(toolNode -> {
                    // Interactions API: 扁平结构 + type 鉴别器
                    toolNode.set("type", func.type());
                    toolNode.set("name", func.name());
                    toolNode.set("description", func.descriptionAndMeta());

                    String inputSchema = func.inputSchema();
                    if (Utils.isNotEmpty(inputSchema)) {
                        try {
                            ONode schemaNode = ONode.ofJson(inputSchema);
                            toolNode.set("parameters", schemaNode);
                        } catch (Exception e) {
                            toolNode.getOrNew("parameters").asArray();
                        }
                    } else {
                        toolNode.getOrNew("parameters").asArray();
                    }
                });
            }
        });
    }

    /**
     * 构建 response_format 节点
     * <p>
     * Interactions API 使用 response_format 数组替代 Generate Content API 的 response_mime_type。
     * 当设置了 outputSchema 时，使用 JSON 格式。
     */
    private void buildResponseFormatNode(ONode root, ChatOptions options) {
        String outputSchema = options.outputSchema();
        if (Utils.isNotEmpty(outputSchema)) {
            ONode formatArr = root.getOrNew("response_format").asArray();
            ONode formatItem = formatArr.addNew();
            formatItem.set("type", "json");
            try {
                formatItem.set("schema", ONode.ofJson(outputSchema));
            } catch (Exception e) {
                formatItem.set("schema", outputSchema);
            }
        }
        // 不设置 response_format 时，API 默认使用 text
    }

    /**
     * 构建助手工具调用消息节点
     * <p>
     * 当框架在工具调用循环中需要将 AssistantMessage 表达为 API 格式时调用。
     * Interactions API 使用 function_call steps 数组格式。
     *
     * @return ONode 数组，每个元素是一个 function_call step
     */
    public ONode buildAssistantToolCallMessageNode(ChatResponseDefault resp,
                                                    Map<String, ToolCallBuilder> toolCallBuilders) {
        ONode arrNode = new ONode().asArray();

        boolean isFirst = true;
        for (Map.Entry<String, ToolCallBuilder> kv : toolCallBuilders.entrySet()) {
            ToolCallBuilder builder = kv.getValue();
            ONode step = arrNode.addNew();
            step.set("type", "function_call");
            step.set("name", builder.nameBuilder.toString());
            // Interactions API: function_call step 使用 "id" 字段
            step.set("id", builder.idBuilder.toString());

            if (builder.argumentsBuilder.length() > 0) {
                String argsStr = builder.argumentsBuilder.toString();
                try {
                    ONode argsNode = ONode.ofJson(argsStr);
                    step.set("arguments", argsNode);
                } catch (Exception e) {
                    step.set("arguments", argsStr);
                }
            } else {
                step.set("arguments", new ONode());
            }

            // 仅第一个 step 回传 thoughtSignature
            if (isFirst && Utils.isNotEmpty(resp.thinkingSignature)) {
                step.set("thought_signature", resp.thinkingSignature);
            }
            isFirst = false;
        }

        return arrNode;
    }
}
