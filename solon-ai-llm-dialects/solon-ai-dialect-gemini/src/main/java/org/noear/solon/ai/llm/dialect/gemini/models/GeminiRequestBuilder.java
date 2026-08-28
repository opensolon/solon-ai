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

        // cachedContent: Google Gemini 显式 Context Caching（针对超长上下文/系统知识库）
        // 允许通过 options.optionSet("cachedContent", "cachedContents/xxx") 或 context_cache_id 传入
        Object cachedContent = options.options().get("cachedContent");
        if (cachedContent == null) {
            cachedContent = options.options().get("context_cache_id");
        }
        if (cachedContent != null && Utils.isNotEmpty(cachedContent.toString())) {
            root.set("cachedContent", cachedContent.toString());
        }

        // system_instruction：提取 SystemMessage 到顶层（Gemini 的 contents[].role 仅接受 user/model，
        // 写入 role="system" 会被 API 400 拒绝；官方格式为 system_instruction.parts[].text）
        // 注：若已挂载 cachedContent，通常 system_instruction 已内嵌在缓存资源中，仍允许追加
        ONode sysInst = buildSystemInstructionNode(messages);
        if (sysInst != null) {
            root.set("system_instruction", sysInst);
        }

        ONode contentsNode = root.getOrNew("contents").asArray();
        for (ChatMessage m1 : messages) {
            if (m1 instanceof SystemMessage) {
                continue; // 已在顶层 system_instruction 处理
            }
            if (m1.isThinking() == false) {
                contentsNode.add(buildMessageNode(m1));
            }
        }

        Object reasoningEffort = null;
        Object thinkingSwitch = null;
        for (Map.Entry<String, Object> kv : options.options().entrySet()) {
            if ("stream".equals(kv.getKey())) {
                continue;
            }
            
            String key = kv.getKey();
            Object value = kv.getValue();
            
            // 统一推理水平 / 思考开关延后应用，避免被后续 generationConfig 整段覆盖
            if ("reasoning_effort".equals(key)) {
                reasoningEffort = value;
                continue;
            }
            if ("thinking".equals(key) && value instanceof Boolean) {
                thinkingSwitch = value;
                continue;
            }
                
            if ("generationConfig".equals(key) && value instanceof Map) {
                GenerationConfig generationConfig = toGenerationConfig((Map<String, Object>) value);
                root.set("generationConfig", ONode.ofBean(generationConfig));
            } else {
                root.set(key, ONode.ofBean(value));
            }
        }
        
        // 最后合并 thinking / reasoning_effort，确保不被 generationConfig 反覆盖
        applyUnifiedThinkingOptions(root, config, options, thinkingSwitch, reasoningEffort);
    
        buildToolsNode(root, config, options);
    
        return root;
    }

    /**
     * 从消息列表中提取 system_instruction 节点（Generate Content API 格式：parts[].text）。
     * <p>多个 SystemMessage 合并为一段文本；无 SystemMessage 时返回 null。</p>
     *
     * @param messages 对话消息列表
     * @return system_instruction 节点，无则 null
     */
    private ONode buildSystemInstructionNode(List<ChatMessage> messages) {
        StringBuilder systemPrompt = new StringBuilder();
        for (ChatMessage m1 : messages) {
            if (m1 instanceof SystemMessage) {
                String content = m1.getContent();
                if (Utils.isNotEmpty(content)) {
                    if (systemPrompt.length() > 0) {
                        systemPrompt.append("\n\n");
                    }
                    systemPrompt.append(content);
                }
            }
        }

        if (systemPrompt.length() == 0) {
            return null;
        }

        ONode node = new ONode();
        node.getOrNew("parts").asArray().addNew().set("text", systemPrompt.toString());
        return node;
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
            // Gemini 3+ 官方规范：functionResponse 需回传与 functionCall 相同的 id。
            // 仅当 id 为服务端真实 id（即与 name 不同）时写出，兼容 Gemini 2.5 / 代理端（不识别 id 字段）。
            String callId = toolMessage.getToolCallId();
            if (Utils.isNotEmpty(callId) && !callId.equals(toolMessage.getName())) {
                n2.set("id", callId);
            }
            // 官方规范：response 必须是键值对 JSON Object；数组/标量/纯文本一律包装为 {"result": ...}。
            try {
                ONode responseNode = ONode.ofJson(toolMessage.getContent());
                if (responseNode.isObject()) {
                    n2.set("response", responseNode);
                } else {
                    ONode wrapper = new ONode().asObject();
                    wrapper.set("result", responseNode);
                    n2.set("response", wrapper);
                }
            } catch (Exception e) {
                ONode responseNode = new ONode().asObject();
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
                // 文本 / 媒体 parts（若有）
                appendAssistantContentParts(n1, assistantMessage);
                    
                boolean[] isFirst = {true};
                for (ToolCall call : assistantMessage.getToolCalls()) {
                    ONode partNode = n1.addNew();
                    partNode.getOrNew("functionCall").then(n2 -> {
                        n2.set("name", call.getName());
                        // Gemini 3+ 官方规范：回传的 functionCall 需携带服务端生成的 id（仅真实 id，fallback 的 name 不写）
                        if (Utils.isNotEmpty(call.getId()) && !call.getId().equals(call.getName())) {
                            n2.set("id", call.getId());
                        }
                        // 出站兜底净化：截断/双重编码的 arguments 禁止原样回传（args 必须是 object）
                        String safeArgs = ToolCallJsonSanitizer.sanitizeArguments(call.getArgumentsStr(), call.getName());
                        try {
                            ONode argsNode = ONode.ofJson(safeArgs);
                            n2.set("args", argsNode.isObject() ? argsNode : new ONode().asObject());
                        } catch (Exception e) {
                            n2.set("args", ONode.ofBean(call.getArguments()));
                        }
                    });
                    // thoughtSignature 位于 part 级别（functionCall 的同级），仅第一个 part 需要
                    if (isFirst[0] && Utils.isNotEmpty(call.getThoughtSignature())) {
                        partNode.set("thoughtSignature", call.getThoughtSignature());
                    }
                    isFirst[0] = false;
                }
            });
        } else if (assistantMessage.isMultiModal()) {
            ONode parts = node.getOrNew("parts").asArray();
            appendAssistantContentParts(parts, assistantMessage);
            if (parts.getArray() != null && parts.getArray().isEmpty()
                    && Utils.isNotEmpty(assistantMessage.getText())) {
                parts.addNew().set("text", assistantMessage.getText());
            }
        } else {
            // 与多模态路径对齐：纯文本也剥离 think 标签
            String content = assistantMessage.getText();
            if (Utils.isNotEmpty(content)) {
                node.getOrNew("parts").asArray().addNew().set("text", content);
            }
        }
    }
    
    /**
     * 将 Assistant 文本/媒体写入 Gemini parts。
     *
     * @since 3.9
     */
    private void appendAssistantContentParts(ONode partsArr, AssistantMessage assistantMessage) {
        if (Utils.isNotEmpty(assistantMessage.getBlocks())) {
            boolean hasText = false;
            for (ContentBlock block : assistantMessage.getBlocks()) {
                if (block instanceof TextBlock) {
                    String text = block.getContent();
                    if (Utils.isNotEmpty(text)) {
                        partsArr.addNew().set("text", text);
                        hasText = true;
                    }
                } else if (block instanceof AbsMedia) {
                    appendMediaPart(partsArr, (AbsMedia<?>) block);
                }
            }
            if (!hasText && Utils.isNotEmpty(assistantMessage.getText())) {
                partsArr.addNew().set("text", assistantMessage.getText());
            }
        } else if (Utils.isNotEmpty(assistantMessage.getText())) {
            partsArr.addNew().set("text", assistantMessage.getText());
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
                } else if (block instanceof AbsMedia) {
                    appendMediaPart(partsArr, (AbsMedia<?>) block);
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
     * AbsMedia → Gemini part（inline_data / file_data），支持 image/audio/video。
     *
     * @since 3.9
     */
    private void appendMediaPart(ONode partsArr, AbsMedia<?> media) {
        if (media == null) {
            return;
        }
        
        // Session 截断后 data/url 皆空时跳过，避免写出空 part
        if (Utils.isEmpty(media.getData()) && Utils.isEmpty(media.getUrl())) {
            return;
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
                
        ONode partNode = partsArr.addNew();
        if (Utils.isNotEmpty(media.getData())) {
            final String finalMime = mime;
            partNode.getOrNew("inline_data").then(n -> {
                n.set("mime_type", finalMime);
                n.set("data", media.getData());
            });
        } else {
            final String finalMime = mime;
            partNode.getOrNew("file_data").then(n -> {
                n.set("mime_type", finalMime);
                n.set("file_uri", media.getUrl());
            });
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

        // Gemini 官方规范：toolConfig.functionCallingConfig.mode 支持 AUTO/ANY/NONE。
        // 对齐上层 tool_choice 配置：none→NONE、required→ANY、指定函数→ANY+allowedFunctionNames、auto→AUTO（默认值，省略）。
        Object toolChoice = options == null ? null : options.options().get("tool_choice");
        if (toolChoice != null) {
            if ("none".equals(toolChoice)) {
                ONode tc = new ONode();
                tc.getOrNew("functionCallingConfig").set("mode", "NONE");
                root.set("toolConfig", tc);
            } else if ("required".equals(toolChoice)) {
                ONode tc = new ONode();
                tc.getOrNew("functionCallingConfig").set("mode", "ANY");
                root.set("toolConfig", tc);
            } else if (toolChoice instanceof Map) {
                // 指定函数名：ANY + allowedFunctionNames
                Object functionObj = ((Map<?, ?>) toolChoice).get("function");
                if (functionObj instanceof Map) {
                    Object name = ((Map<?, ?>) functionObj).get("name");
                    if (name != null) {
                        ONode tc = new ONode();
                        ONode fcc = tc.getOrNew("functionCallingConfig");
                        fcc.set("mode", "ANY");
                        fcc.getOrNew("allowedFunctionNames").asArray().addNew().setValue(name.toString());
                        root.set("toolConfig", tc);
                    }
                }
            }
            // "auto" 是 Gemini 默认模式，无需显式写出（保持请求简洁）
        }
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
                    // Gemini 3+ 官方规范：回传的 functionCall 需携带服务端生成的 id（仅真实 id，fallback 的 name 不写）
                    if (builder.idBuilder.length() > 0
                            && !builder.idBuilder.toString().contentEquals(builder.nameBuilder)) {
                        n2.set("id", builder.idBuilder.toString());
                    }
                    if (builder.argumentsBuilder.length() > 0) {
                        // Gemini 流式 functionCall.args 是累积完整 JSON（非 OpenAI 增量片段）：
                        // 同一 functionCall 跨 chunk 重复发送时 append 会产生 {..}{..} 拼接，
                        // 此处取第一个完整 JSON 对象（Gemini 完整值语义下首帧即完整参数）
                        String rawArgs = takeFirstJsonObject(builder.argumentsBuilder.toString());
                        // 流式聚合出口净化：截断损坏的 arguments 禁止以字符串形态写入 args（会被服务端拒绝）
                        String safeArgs = ToolCallJsonSanitizer.sanitizeArguments(rawArgs, builder.nameBuilder.toString());
                        try {
                            ONode argsNode = ONode.ofJson(safeArgs);
                            n2.set("args", argsNode.isObject() ? argsNode : new ONode().asObject());
                        } catch (Exception e) {
                            n2.set("args", new ONode().asObject());
                        }
                    } else {
                        n2.set("args", new ONode().asObject());
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
     * Gemini 完整值语义下，argumentsBuilder 可能因同一 functionCall 跨 chunk 重复发送而拼接出
     * 多个 JSON 对象（{..}{..}）。取第一个完整对象；单对象或非法串原样返回交给净化器兜底。
     *
     * @since 4.0.6
     */
    private String takeFirstJsonObject(String args) {
        if (args == null || args.isEmpty()) {
            return args;
        }
        String trimmed = args.trim();
        int firstClose = trimmed.indexOf('}');
        if (firstClose > 0 && firstClose < trimmed.length() - 1) {
            // 多个 JSON 对象拼接（如 {"a":1}{"a":1}）→ 取第一个完整对象
            return trimmed.substring(0, firstClose + 1);
        }
        return trimmed;
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
     * 统一 thinking 开关 + reasoning_effort → generationConfig.thinkingConfig。
     * <p>显式 thinkingConfig 优先；按 model 分流：
     * Gemini 2.5 → {@code thinkingBudget}（关=0）；
     * Gemini 3.x → {@code thinkingLevel}（关=minimal）。</p>
     *
     * @since 4.0.4
     */
    @SuppressWarnings("unchecked")
    private void applyUnifiedThinkingOptions(ONode root, ChatConfig config, ChatOptions options,
                                             Object thinkingSwitch, Object reasoningEffort) {
        // 已有 generationConfig.thinkingConfig 时不覆盖
        if (hasExplicitThinkingConfig(root, options)) {
            return;
        }

        boolean useBudget = usesThinkingBudget(config);

        if (Boolean.FALSE.equals(thinkingSwitch)) {
            ONode tc = new ONode();
            tc.set("includeThoughts", false);
            if (useBudget) {
                tc.set("thinkingBudget", 0);
            } else {
                // Gemini 3.x：minimal 表示关闭/极低思考（对齐 OpenCode smallOptions）
                tc.set("thinkingLevel", "minimal");
            }
            root.getOrNew("generationConfig").set("thinkingConfig", tc);
            return;
        }

        if (reasoningEffort != null) {
            applyReasoningEffortToGenerationConfig(root, config, reasoningEffort, options);
            if (root.hasKey("generationConfig")
                    && root.get("generationConfig").hasKey("thinkingConfig")) {
                return;
            }
        }

        if (Boolean.TRUE.equals(thinkingSwitch)) {
            // 开启且无 effort：2.5 用 medium 预算；3.x 默认 high（对齐 OpenCode options）
            String defaultEffort = useBudget ? "medium" : "high";
            applyReasoningEffortToGenerationConfig(root, config, defaultEffort, options);
        }
    }

    /**
     * 将统一 reasoning_effort 映射到 generationConfig.thinkingConfig。
     * <p>若用户已显式配置 thinkingConfig，则不覆盖。</p>
     * <p>Gemini 2.5 仅写 {@code thinkingBudget}；Gemini 3.x 仅写 {@code thinkingLevel}，
     * 避免双写冲突（对齐 OpenCode ProviderTransform）。</p>
     *
     * @since 4.0.4
     */
    @SuppressWarnings("unchecked")
    private void applyReasoningEffortToGenerationConfig(ONode root, ChatConfig config,
                                                        Object value, ChatOptions options) {
        if (value == null) {
            return;
        }

        if (hasExplicitThinkingConfig(root, options)) {
            return;
        }

        String effort = String.valueOf(value).trim().toLowerCase();
        boolean useBudget = usesThinkingBudget(config);

        ONode tc = new ONode();
        tc.set("includeThoughts", true);

        if (useBudget) {
            Integer budget = mapEffortToThinkingBudget(effort, config);
            if (budget == null) {
                return;
            }
            tc.set("thinkingBudget", budget);
        } else {
            String level = mapEffortToThinkingLevelName(effort, config);
            if (level == null) {
                return;
            }
            // API 侧常用小写 level，避免 enum 名全大写序列化
            tc.set("thinkingLevel", level);
        }

        ONode genNode = root.getOrNew("generationConfig");
        genNode.set("thinkingConfig", tc);
    }

    /**
     * Gemini 2.5 使用 thinkingBudget；其余（含 Gemini 3 / 3.1）使用 thinkingLevel。
     *
     * @since 4.0.4
     */
    private boolean usesThinkingBudget(ChatConfig config) {
        String model = config == null || config.getModel() == null ? "" : config.getModel().toLowerCase();
        // 2.5 / 2-5 → budget；其它默认 level（与 OpenCode 一致）
        return model.contains("2.5") || model.contains("2-5");
    }

    private boolean hasExplicitThinkingConfig(ONode root, ChatOptions options) {
        Object genCfg = options == null ? null : options.options().get("generationConfig");
        if (genCfg instanceof Map) {
            Object existing = ((Map<?, ?>) genCfg).get("thinkingConfig");
            if (existing != null) {
                return true;
            }
        }
        if (root != null && root.hasKey("generationConfig")) {
            ONode genNode = root.get("generationConfig");
            if (genNode != null && genNode.hasKey("thinkingConfig")) {
                return true;
            }
        }
        return false;
    }

    /**
     * reasoning_effort → thinkingBudget（Gemini 2.5）。
     * <p>对齐 OpenCode：high=16k；max 对 2.5 pro=32768，其余 2.5（含 flash）=24576。</p>
     */
    private Integer mapEffortToThinkingBudget(String effort, ChatConfig config) {
        String model = config == null || config.getModel() == null ? "" : config.getModel().toLowerCase();
        // 2.5 pro（非 flash）max 预算更高
        boolean is25Pro = (model.contains("2.5") || model.contains("2-5"))
                && model.contains("pro") && !model.contains("flash");
        switch (effort) {
            case "low":
                return 1024;
            case "medium":
                return 4096;
            case "high":
                return 16000;
            case "max":
                return is25Pro ? 32768 : 24576;
            default:
                return null;
        }
    }

    /**
     * reasoning_effort → thinkingLevel 字符串（Gemini 3.x）。
     * <p>对齐 OpenCode googleThinkingLevelEfforts：
     * flash → minimal/low/medium/high；pro → low/medium/high；
     * 统一 API 的 max → high；非 flash 的 minimal → low。</p>
     */
    private String mapEffortToThinkingLevelName(String effort, ChatConfig config) {
        String model = config == null || config.getModel() == null ? "" : config.getModel().toLowerCase();
        boolean isFlash = model.contains("flash");
        // Gemini 3 族（含 3.1）普遍支持 medium；非 3 的 google 模型仅 low/high
        boolean isGemini3 = model.contains("gemini-3") || model.contains("gemini3")
                || model.contains("3.1") || model.contains("3-1")
                || (model.contains("gemini") && model.contains("3"));
        boolean supportsMedium = isGemini3 || model.contains("3.1") || model.contains("3-1");
        boolean supportsMinimal = isFlash; // flash 支持 minimal 关闭/极低

        switch (effort) {
            case "minimal":
            case "min":
                return supportsMinimal ? "minimal" : "low";
            case "low":
                return "low";
            case "medium":
                return supportsMedium ? "medium" : "high";
            case "high":
            case "max":
                return "high";
            default:
                return null;
        }
    }
}
