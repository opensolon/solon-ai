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
import org.noear.solon.ai.chat.ChatAccumulator;
import org.noear.solon.ai.chat.content.AbsMedia;
import org.noear.solon.ai.chat.content.AudioBlock;
import org.noear.solon.ai.chat.content.ContentBlock;
import org.noear.solon.ai.chat.content.ImageBlock;
import org.noear.solon.ai.chat.content.TextBlock;
import org.noear.solon.ai.chat.content.VideoBlock;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.message.SystemMessage;
import org.noear.solon.ai.chat.message.ToolMessage;
import org.noear.solon.ai.chat.message.UserMessage;
import org.noear.solon.ai.chat.tool.FunctionTool;
import org.noear.solon.ai.chat.tool.ToolCall;
import org.noear.solon.ai.chat.tool.ToolCallBuilder;
import org.noear.solon.ai.chat.tool.ToolCallJsonSanitizer;
import org.noear.solon.ai.llm.dialect.gemini.GeminiMessageStateSupport;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
    private static final Set<String> ROOT_OPTION_KEYS = new java.util.HashSet<>(java.util.Arrays.asList(
            "background", "previous_interaction_id", "service_tier", "webhook_config",
            "environment", "safety_settings", "labels"));

    /** 纯思考历史没有可安全回放的 Gemini 签名/正文，应用 metadata 与 foreign raw 不应阻止过滤。 */
    private boolean isSkippableThinkingOnlyMessage(ChatMessage message) {
        return message instanceof AssistantMessage
                && ((AssistantMessage) message).isThinkingOnly();
    }

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

        // 2. system_instruction：当前协议为字符串，合并全部系统消息。
        String systemInstruction = buildSystemInstruction(messages);
        if (Utils.isNotEmpty(systemInstruction)) {
            root.set("system_instruction", systemInstruction);
        }

        // 3. input[]: 构建 step 序列（跳过 system message 和 thinking 消息）
        ONode inputArr = root.getOrNew("input").asArray();
        for (ChatMessage msg : messages) {
            if (msg instanceof SystemMessage) {
                continue; // system_instruction 已在顶层处理
            }
            if (isSkippableThinkingOnlyMessage(msg)) {
                continue; // 仅跳过无扩展回放载体的纯思考消息
            }
            List<ONode> steps = buildStepsFromMessage(msg);
            for (ONode step : steps) {
                inputArr.add(step);
            }
        }

        // 4. generation_config
        ONode configNode = buildGenerationConfigNode(options);
        if (configNode != null && configNode.size() > 0) {
            root.set("generation_config", configNode);
        }

        // 5. stream
        root.set("stream", isStream);

        // 6. store: 调用方可覆盖；缺省采用无状态模式
        Object store = options.options().get("store");
        root.set("store", store == null ? false : ONode.ofBean(store));

        // 7. tools
        buildToolsNode(root, options);

        // 8. response_format
        buildResponseFormatNode(root, options);

        // 9. 仅透传当前 Interactions 请求契约允许的顶层扩展字段。
        for (Map.Entry<String, Object> kv : options.options().entrySet()) {
            if (ROOT_OPTION_KEYS.contains(kv.getKey()) && !root.hasKey(kv.getKey())) {
                root.set(kv.getKey(), ONode.ofBean(kv.getValue()));
            }
        }

        return root;
    }

    /**
     * 从消息列表中提取 system_instruction
     */
    private String buildSystemInstruction(List<ChatMessage> messages) {
        StringBuilder instruction = new StringBuilder();
        for (ChatMessage msg : messages) {
            if (msg instanceof SystemMessage && Utils.isNotEmpty(msg.getContent())) {
                if (instruction.length() > 0) instruction.append("\n\n");
                instruction.append(msg.getContent());
            }
        }
        return instruction.length() == 0 ? null : instruction.toString();
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
                ONode mediaNode = buildContentBlock(block);
                // Session 截断后空媒体不追加
                if (mediaNode != null && mediaNode.isObject() && mediaNode.getObject().size() > 0) {
                    contentArr.add(mediaNode);
                }
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

        List<ToolCall> toolCalls = ToolCallJsonSanitizer.resolveToolCalls(
                msg.getToolCalls(), msg.getToolCallsRaw());
        if (Utils.isNotEmpty(toolCalls)) {
            // 若有文本/媒体，先写 model_output
            if (msg.isMultiModal() || Utils.isNotEmpty(msg.getContent())) {
                ONode outputStep = new ONode();
                outputStep.set("type", "model_output");
                ONode contentArr = outputStep.getOrNew("content").asArray();
                appendAssistantContent(contentArr, msg);
                if (contentArr.getArray() != null && !contentArr.getArray().isEmpty()) {
                    steps.add(outputStep);
                }
            }
                
            ToolCall firstCall = toolCalls.get(0);
            String signature = GeminiMessageStateSupport.resolveSignature(msg,
                    GeminiMessageStateSupport.INTERACTIONS_PROTOCOL_ID, firstCall, 0);
            if (Utils.isNotEmpty(signature)) {
                ONode thought = new ONode();
                thought.set("type", "thought");
                thought.set("signature", signature);
                steps.add(thought);
            }
            for (ToolCall call : toolCalls) {
                ONode step = new ONode();
                step.set("type", "function_call");
                step.set("name", call.getName());
                // Interactions API: function_call step 使用 "id" 字段
                if (Utils.isNotEmpty(call.getId())) {
                    step.set("id", call.getId());
                } else {
                    step.set("id", call.getName() + "_" + System.currentTimeMillis());
                }
                // arguments（出站兜底净化：截断/双重编码的 arguments 禁止以字符串形态回传）
                String safeArgs = ToolCallJsonSanitizer.sanitizeArguments(call);
                try {
                    ONode argsNode = ONode.ofJson(safeArgs);
                    step.set("arguments", argsNode.isObject() ? argsNode : new ONode().asObject());
                } catch (Exception e) {
                    step.set("arguments", ONode.ofBean(call.getArguments()));
                }
                steps.add(step);
            }
        } else {
            // 文本 / 多模态响应
            ONode step = new ONode();
            step.set("type", "model_output");
            ONode contentArr = step.getOrNew("content").asArray();
            if (msg.isMultiModal()) {
                appendAssistantContent(contentArr, msg);
            } else {
                // 纯文本也剥离 think，与多模态路径一致
                String content = msg.getText();
                if (Utils.isNotEmpty(content)) {
                    contentArr.addNew().set("type", "text").set("text", content);
                }
            }
            steps.add(step);
        }
    
        return steps;
    }
    
    /**
     * Assistant blocks → Interactions content 数组。
     *
     * @since 3.9
     */
    private void appendAssistantContent(ONode contentArr, AssistantMessage msg) {
        boolean hasText = false;
        if (Utils.isNotEmpty(msg.getBlocks())) {
            for (ContentBlock block : msg.getBlocks()) {
                if (block instanceof TextBlock) {
                    String text = block.getContent();
                    if (Utils.isNotEmpty(text)) {
                        contentArr.addNew().set("type", "text").set("text", text);
                        hasText = true;
                    }
                } else {
                    ONode mediaNode = buildContentBlock(block);
                    // Session 截断后空媒体不追加
                    if (mediaNode != null && mediaNode.isObject() && mediaNode.getObject().size() > 0) {
                        contentArr.add(mediaNode);
                    }
                }
            }
        }
        if (!hasText && Utils.isNotEmpty(msg.getText())) {
            contentArr.addNew().set("type", "text").set("text", msg.getText());
        }
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
        } else if (block instanceof AbsMedia) {
            AbsMedia<?> media = (AbsMedia<?>) block;
            // Session 截断后 data/url 皆空时返回空节点，调用方跳过
            if (Utils.isEmpty(media.getData()) && Utils.isEmpty(media.getUrl())) {
                return node;
            }
                
            String mime = media.getMimeType();
            if (Utils.isEmpty(mime)) {
                if (media instanceof ImageBlock) {
                    mime = "image/jpeg";
                } else if (media instanceof AudioBlock) {
                    mime = "audio/mpeg";
                } else if (media instanceof VideoBlock) {
                    mime = "video/mp4";
                }
            }
            
            if (media instanceof ImageBlock) {
                node.set("type", "image");
            } else if (media instanceof AudioBlock) {
                node.set("type", "audio");
            } else if (media instanceof VideoBlock) {
                node.set("type", "video");
            } else {
                return new ONode();
            }
            node.set("mime_type", mime);
            if (Utils.isNotEmpty(media.getData())) {
                node.set("data", media.getData());
            } else {
                node.set("uri", media.getUrl());
            }
        }
        return node;
    }

    /** 构建当前 Interactions API 的 generation_config。 */
    @SuppressWarnings("unchecked")
    private ONode buildGenerationConfigNode(ChatOptions options) {
        Map<String, Object> opts = options.options();
        ONode config = new ONode();
        Object raw = opts.get("generation_config");
        if (!(raw instanceof Map)) raw = opts.get("generationConfig");
        if (raw instanceof Map) {
            Map<String, Object> source = (Map<String, Object>) raw;
            copyGenerationField(config, source, "image_config", "imageConfig");
            copyGenerationField(config, source, "max_output_tokens", "maxOutputTokens");
            copyGenerationField(config, source, "seed", "seed");
            copyGenerationField(config, source, "speech_config", "speechConfig");
            copyGenerationField(config, source, "stop_sequences", "stopSequences");
            copyGenerationField(config, source, "transcription_config", "transcriptionConfig");
            copyGenerationField(config, source, "video_config", "videoConfig");
            copyGenerationField(config, source, "thinking_level", "thinkingLevel");
            copyGenerationField(config, source, "thinking_summaries", "thinkingSummaries");
            copyGenerationField(config, source, "tool_choice", "toolChoice");

            Object thinking = source.get("thinkingConfig");
            if (thinking instanceof Map) {
                Map<String, Object> tc = (Map<String, Object>) thinking;
                Object include = tc.get("includeThoughts");
                if (include != null) config.set("thinking_summaries", asBoolean(include) ? "auto" : "none");
                Object level = tc.get("thinkingLevel");
                if (level != null) setThinkingLevel(config, level);
                else if (tc.get("thinkingBudget") != null) setThinkingLevelFromBudget(config, tc.get("thinkingBudget"));
            }
        }

        Object maxTokens = opts.get("max_completion_tokens");
        if (maxTokens == null) maxTokens = opts.get("max_tokens");
        if (maxTokens != null) config.set("max_output_tokens", integerNode(maxTokens));

        Object thinkingSwitch = opts.get("thinking");
        Object effort = opts.get("reasoning_effort");
        if (Boolean.FALSE.equals(thinkingSwitch)) {
            config.set("thinking_summaries", "none");
        } else {
            if (!config.hasKey("thinking_level") && effort != null) setThinkingLevel(config, effort);
            if (!config.hasKey("thinking_level") && Boolean.TRUE.equals(thinkingSwitch)) {
                config.set("thinking_level", "medium");
            }
            if ((effort != null || Boolean.TRUE.equals(thinkingSwitch)) && !config.hasKey("thinking_summaries")) {
                config.set("thinking_summaries", "auto");
            }
        }

        Object toolChoice = opts.get("tool_choice");
        if (toolChoice != null) config.set("tool_choice", buildToolChoice(toolChoice));
        return config.size() == 0 ? null : config;
    }

    private void copyGenerationField(ONode target, Map<String, Object> source, String snake, String camel) {
        Object value = source.containsKey(snake) ? source.get(snake) : source.get(camel);
        if (value == null) return;
        if ("max_output_tokens".equals(snake) || "seed".equals(snake)) target.set(snake, integerNode(value));
        else if ("thinking_level".equals(snake)) setThinkingLevel(target, value);
        else if ("thinking_summaries".equals(snake) && value instanceof Boolean) {
            target.set(snake, Boolean.TRUE.equals(value) ? "auto" : "none");
        } else target.set(snake, ONode.ofBean(value));
    }

    private ONode integerNode(Object value) {
        if (value instanceof String) {
            try { return ONode.ofBean(Integer.parseInt(((String) value).trim())); } catch (NumberFormatException ignored) {}
        }
        return ONode.ofBean(value);
    }

    private boolean asBoolean(Object value) {
        return value instanceof Boolean ? (Boolean) value : Boolean.parseBoolean(String.valueOf(value));
    }

    private void setThinkingLevelFromBudget(ONode config, Object value) {
        try {
            int budget = value instanceof Number ? ((Number) value).intValue() : Integer.parseInt(String.valueOf(value));
            config.set("thinking_level", budget > 8192 ? "high" : budget > 2048 ? "medium" : "low");
        } catch (NumberFormatException ignored) {}
    }

    private void setThinkingLevel(ONode config, Object value) {
        String effort = String.valueOf(value).trim().toLowerCase();
        if (effort.contains("minimal") || "min".equals(effort)) config.set("thinking_level", "minimal");
        else if (effort.contains("low")) config.set("thinking_level", "low");
        else if (effort.contains("medium")) config.set("thinking_level", "medium");
        else if (effort.contains("high") || "max".equals(effort)) config.set("thinking_level", "high");
    }

    private ONode buildToolChoice(Object value) {
        if (value instanceof String) {
            String choice = String.valueOf(value).toLowerCase();
            if ("required".equals(choice)) choice = "any";
            return ONode.ofBean(choice);
        }
        if (value instanceof Map) {
            Object function = ((Map<?, ?>) value).get("function");
            if (function instanceof Map && ((Map<?, ?>) function).get("name") != null) {
                ONode choice = new ONode();
                ONode allowed = choice.getOrNew("allowed_tools");
                allowed.set("mode", "any");
                allowed.getOrNew("tools").asArray().addNew().setValue(String.valueOf(((Map<?, ?>) function).get("name")));
                return choice;
            }
        }
        return ONode.ofBean(value);
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
                            ONode parameters = toolNode.getOrNew("parameters").asObject();
                            parameters.set("type", "object");
                            parameters.getOrNew("properties").asObject();
                        }
                    } else {
                        ONode parameters = toolNode.getOrNew("parameters").asObject();
                        parameters.set("type", "object");
                        parameters.getOrNew("properties").asObject();
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
            formatItem.set("type", "text");
            formatItem.set("mime_type", "application/json");
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
    public ONode buildAssistantToolCallMessageNode(ChatAccumulator acc,
                                                    Map<String, ToolCallBuilder> toolCallBuilders) {
        ONode arrNode = new ONode().asArray();

        if (Utils.isNotEmpty(acc.thinkingSignature)) {
            ONode thought = arrNode.addNew();
            thought.set("type", "thought");
            thought.set("signature", acc.thinkingSignature);
        }
        for (Map.Entry<String, ToolCallBuilder> kv : toolCallBuilders.entrySet()) {
            ToolCallBuilder builder = kv.getValue();
            ONode step = arrNode.addNew();
            step.set("type", "function_call");
            step.set("name", builder.nameBuilder.toString());
            // Interactions API: function_call step 使用 "id" 字段
            step.set("id", builder.idBuilder.toString());

            if (builder.argumentsBuilder.length() > 0) {
                // 流式聚合出口净化：截断损坏的 arguments 禁止以字符串形态写入 step
                String safeArgs = ToolCallJsonSanitizer.sanitizeArguments(
                        builder.argumentsBuilder.toString(), builder.nameBuilder.toString());
                try {
                    ONode argsNode = ONode.ofJson(safeArgs);
                    step.set("arguments", argsNode.isObject() ? argsNode : new ONode().asObject());
                } catch (Exception e) {
                    step.set("arguments", new ONode().asObject());
                }
            } else {
                step.set("arguments", new ONode().asObject());
            }

        }

        return arrNode;
    }
}
