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
package org.noear.solon.ai.llm.dialect.anthropic;

import org.noear.snack4.ONode;
import org.noear.solon.Utils;
import org.noear.solon.ai.chat.*;
import org.noear.solon.ai.chat.content.ContentBlock;
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
public class AnthropicRequestBuilder {

    /**
     * 构建请求 JSON
     * @author oisin lu
     * @param config   聊天配置
     * @param options  聊天选项
     * @param messages 对话消息列表
     * @param isStream 是否使用流式模式
     * @return 符合 Messages API 规范的 JSON 字符串
     */
    public ONode build(ChatConfig config, ChatOptions options, List<ChatMessage> messages, boolean isStream) {
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
            CacheControl cacheControl = options.cacheControl();
            if (cacheControl != null) {
                // 启用 Prompt Caching：system prompt 作为 content blocks 数组，最后一块添加 cache_control
                ONode systemNode = new ONode();
                systemNode.addNew().then(contentBlock->{
                    contentBlock.set("type", "text");
                    contentBlock.set("text", systemMessage);
                    contentBlock.getOrNew("cache_control").set("type", cacheControl.getType());
                });

                root.set("system", systemNode);
            } else {
                root.set("system", systemMessage);
            }
        }

        // 构建消息数组，过滤掉系统消息
        ONode messagesNode = root.getOrNew("messages").asArray();
        ONode pendingToolResultNode = null; // 用于合并连续的 ToolMessage
        for (ChatMessage message : messages) {
            if (message instanceof SystemMessage || message.isThinking()) {
                continue;
            }

            if (message instanceof ToolMessage) {
                if (pendingToolResultNode == null) {
                    pendingToolResultNode = new ONode();
                    pendingToolResultNode.set("role", "user");
                    pendingToolResultNode.getOrNew("content").asArray();
                }
                ToolMessage toolMessage = (ToolMessage) message;
                pendingToolResultNode.get("content").addNew()
                        .set("type", "tool_result")
                        .set("tool_use_id", toolMessage.getToolCallId())
                        .set("content", toolMessage.getContent());
            } else {
                if (pendingToolResultNode != null) {
                    messagesNode.add(pendingToolResultNode);
                    pendingToolResultNode = null;
                }
                messagesNode.add(buildMessageNode(message));
            }
        }
        if (pendingToolResultNode != null) {
            messagesNode.add(pendingToolResultNode);
        }

        // 设置流式模式参数
        if (isStream) {
            root.set("stream", true);
        }

        // 添加其他选项
        Object thinkingSwitch = null;
        for (Map.Entry<String, Object> kv : options.options().entrySet()) {
            String key = kv.getKey();
            // 跳过Claude特有的字段，或已处理的字段
            if ("stream".equals(key) || "max_tokens".equals(key)) {
                continue;
            }
            
            // 统一思考开关（Boolean）延后与 reasoning_effort 一起处理
            if ("thinking".equals(key) && kv.getValue() instanceof Boolean) {
                thinkingSwitch = kv.getValue();
                continue;
            }
            
            // 统一推理水平 → thinking.budget_tokens（若尚未显式配置 thinking）
            if ("reasoning_effort".equals(key)) {
                // 与 Boolean thinking 一起在循环后处理，避免顺序依赖
                continue;
            }
            
            // 处理思考模式配置（Map / Number 等供应商原生形态）
            if ("thinking".equals(key)) {
                buildThinkingNode(root, kv.getValue());
                continue;
            }
            
            // 处理tool_choice（需要从OpenAI格式转换为Anthropic格式）
            if ("tool_choice".equals(key)) {
                buildToolChoiceNode(root, kv.getValue());
                continue;
            }
        
            root.set(key, ONode.ofBean(kv.getValue()));
        }
        
        // 统一 thinking 开关 + reasoning_effort（显式 Map/Number thinking 优先）
        applyUnifiedThinkingOptions(root, config, options, thinkingSwitch);
        
        // 如果用户未显式设置 tool_choice 但有 tools，默认使用 auto 语义（由API自行决定）
        if (!options.options().containsKey("tool_choice") && !Utils.isEmpty(options.tools())) {
            // Anthropic 默认行为等同于 auto，无需显式设置
        }

        buildToolsNode(root, options);

        return root;
    }

    /**
     * 构建 tool_choice 节点（转换为Anthropic格式）
     *
     * OpenAI 格式 → Anthropic 格式：
     *   "auto"       → {"type": "auto"}
     *   "required"   → {"type": "any"}
     *   "none"       → {"type": "none"}  (仅 Anthropic 特定客户端支持)
     *   {"type":"function",...} → {"type":"tool","name":"..."}
     */
    @SuppressWarnings("unchecked")
    private void buildToolChoiceNode(ONode root, Object toolChoice) {
        ONode tcNode = root.getOrNew("tool_choice");
        if (toolChoice instanceof Map) {
            Map<String, Object> choiceMap = (Map<String, Object>) toolChoice;
            String type = (String) choiceMap.get("type");
            if ("function".equals(type) && choiceMap.containsKey("function")) {
                Map<String, Object> funcMap = (Map<String, Object>) choiceMap.get("function");
                String name = (String) funcMap.get("name");
                tcNode.set("type", "tool");
                if (Utils.isNotEmpty(name)) {
                    tcNode.set("name", name);
                }
            } else {
                tcNode.set("type", "auto");
            }
        } else if (toolChoice instanceof String) {
            String choice = (String) toolChoice;
            switch (choice) {
                case "required":
                    tcNode.set("type", "any");
                    break;
                case "none":
                    tcNode.set("type", "none");
                    break;
                case "auto":
                default:
                    tcNode.set("type", "auto");
                    break;
            }
        }
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
            // 统一开关 / 简化配置：thinking: true|false
            if (Boolean.TRUE.equals(value)) {
                thinkingNode.set("type", "enabled");
                thinkingNode.set("budget_tokens", 10000); // 默认预算必须要小于等于max_token
            } else {
                thinkingNode.set("type", "disabled");
            }
        } else if (value instanceof Number) {
            // 简化配置：thinking: 10000 (直接指定预算)
            thinkingNode.set("type", "enabled");
            thinkingNode.set("budget_tokens", ((Number) value).intValue());
        }
    }
     
    /**
     * 统一 thinking 开关 + reasoning_effort 映射。
     * <p>优先级：供应商原生 Map/Number thinking &gt; Boolean thinking(false) 关闭
     * &gt; reasoning_effort 开启并设档 &gt; Boolean thinking(true) 默认开启。</p>
     * <p>Claude adaptive（4.6 / 4.7+ / sonnet-5+）：{@code thinking.type=adaptive} + 顶层 {@code effort}；
     * 经典模型：{@code type=enabled} + {@code budget_tokens}。</p>
     *
     * @since 4.0.4
     */
    private void applyUnifiedThinkingOptions(ONode root, ChatConfig config, ChatOptions options, Object thinkingSwitch) {
        // 已有显式 thinking 节点（Map/Number 路径）则不覆盖
        if (root.hasKey("thinking")) {
            return;
        }

        if (Boolean.FALSE.equals(thinkingSwitch)) {
            buildThinkingNode(root, Boolean.FALSE);
            return;
        }

        Object effortObj = options == null ? null : options.options().get("reasoning_effort");
        if (effortObj != null) {
            buildThinkingFromEffort(root, config, effortObj, options);
            if (root.hasKey("thinking")) {
                return;
            }
        }

        if (Boolean.TRUE.equals(thinkingSwitch)) {
            if (isAnthropicAdaptiveModel(config)) {
                // adaptive 默认 medium effort
                buildAdaptiveThinking(root, "medium", config);
            } else {
                buildThinkingNode(root, Boolean.TRUE);
                // 默认预算同样钳制到 max_tokens
                clampThinkingBudgetToMaxTokens(root, options);
            }
        }
    }

    /**
     * 将统一 reasoning_effort 映射为 Claude thinking。
     * <p>adaptive 模型 → adaptive + 顶层 effort；经典 → enabled + budget_tokens。</p>
     * <p>Anthropic 经典路径要求 budget_tokens 严格小于 max_tokens。
     * 当档位预算不小于 max_tokens 时，压到 {@code max_tokens - 1}（至少为 1）；
     * 若 max_tokens &lt;= 1，无法满足约束则跳过 thinking。
     * 小 max_tokens 下语义从“档位预算”退化为“尽量占满输出预算”。</p>
     *
     * @since 4.0.4
     */
    private void buildThinkingFromEffort(ONode root, ChatConfig config, Object value, ChatOptions options) {
        if (value == null) {
            return;
        }
        String effort = String.valueOf(value).trim().toLowerCase();

        if (isAnthropicAdaptiveModel(config)) {
            String adaptiveEffort = clampAnthropicAdaptiveEffort(effort, config);
            if (adaptiveEffort == null) {
                return;
            }
            buildAdaptiveThinking(root, adaptiveEffort, config);
            return;
        }

        int budget;
        switch (effort) {
            case "low":
                budget = 4000;
                break;
            case "medium":
                budget = 10000;
                break;
            case "high":
                // 向 OpenCode 靠拢：high≈16k
                budget = 16000;
                break;
            case "max":
                // 取较大预算；若默认 max_tokens=32000 会落到 31999
                budget = 32000;
                break;
            default:
                return;
        }

        int maxTokens = resolveMaxTokens(root, options);
        // Anthropic 要求 budget_tokens < max_tokens，且预算至少为 1 才有意义
        if (maxTokens <= 1) {
            return;
        }
        if (budget >= maxTokens) {
            budget = maxTokens - 1;
        }

        Map<String, Object> thinking = new HashMap<String, Object>();
        thinking.put("type", "enabled");
        thinking.put("budget_tokens", budget);
        buildThinkingNode(root, thinking);
    }

    /**
     * Claude adaptive thinking：type=adaptive + 顶层 effort（无 budget_tokens）。
     * <p>opus-4.7+ / sonnet-5+ 等默认 display=omitted，需强制 summarized 才能拿到思考摘要
     * （对齐 OpenCode anthropicOmitsThinking）。</p>
     *
     * @since 4.0.4
     */
    private void buildAdaptiveThinking(ONode root, String effort, ChatConfig config) {
        ONode thinkingNode = root.getOrNew("thinking");
        thinkingNode.set("type", "adaptive");
        if (isAnthropicOmitsThinkingModel(config)) {
            // 新模型默认 display=omitted 会返回空 thinking 块
            thinkingNode.set("display", "summarized");
        }
        // 顶层 effort 为 Anthropic adaptive 协议字段
        root.set("effort", effort);
    }

    /**
     * 是否 Anthropic adaptive 模型族（按 model 名启发，对齐 OpenCode）。
     * <p>覆盖：opus/sonnet 4.6；opus-4.7+；sonnet-5+；fable-5；
     * 以及 SAP 等倒置命名 {@code claude-4.7-opus} / {@code claude-5-sonnet}。</p>
     *
     * @since 4.0.4
     */
    private boolean isAnthropicAdaptiveModel(ChatConfig config) {
        if (config == null || config.getModel() == null) {
            return false;
        }
        String model = config.getModel().toLowerCase();
        if (model.contains("fable-5")) {
            return true;
        }
        if (isAnthropicOpus47OrLater(model) || isAnthropicSonnet5OrLater(model)) {
            return true;
        }
        // 4.6 系列（含倒置 4.6-opus / 4.6-sonnet）
        return containsAny(model,
                "opus-4-6", "opus-4.6", "4-6-opus", "4.6-opus",
                "sonnet-4-6", "sonnet-4.6", "4-6-sonnet", "4.6-sonnet");
    }

    /**
     * 新 adaptive 模型默认 display=omitted，需写 summarized（对齐 OpenCode）。
     *
     * @since 4.0.4
     */
    private boolean isAnthropicOmitsThinkingModel(ChatConfig config) {
        if (config == null || config.getModel() == null) {
            return false;
        }
        String model = config.getModel().toLowerCase();
        return model.contains("fable-5")
                || isAnthropicOpus47OrLater(model)
                || isAnthropicSonnet5OrLater(model);
    }

    /**
     * opus-4.7+ 或 claude-4.7-opus 倒置命名（对齐 OpenCode anthropicOpus47OrLater）。
     *
     * @since 4.0.4
     */
    private boolean isAnthropicOpus47OrLater(String model) {
        if (model == null || model.isEmpty()) {
            return false;
        }
        // opus-4.7 / opus-4-7 / opus-4.8 ...
        java.util.regex.Matcher m1 = java.util.regex.Pattern
                .compile("opus-(\\d+)[.-](\\d+)(?:[.@-]|$)", java.util.regex.Pattern.CASE_INSENSITIVE)
                .matcher(model);
        if (m1.find()) {
            int major = Integer.parseInt(m1.group(1));
            int minor = Integer.parseInt(m1.group(2));
            return major > 4 || (major == 4 && minor >= 7);
        }
        // claude-4.7-opus / claude-4-7-opus（SAP 等倒置）
        java.util.regex.Matcher m2 = java.util.regex.Pattern
                .compile("claude-(\\d+)[.-](\\d+)-opus(?:[.@-]|$)", java.util.regex.Pattern.CASE_INSENSITIVE)
                .matcher(model);
        if (m2.find()) {
            int major = Integer.parseInt(m2.group(1));
            int minor = Integer.parseInt(m2.group(2));
            return major > 4 || (major == 4 && minor >= 7);
        }
        return false;
    }

    /**
     * sonnet-5+ 或 claude-5-sonnet 倒置命名（对齐 OpenCode anthropicSonnet5OrLater）。
     *
     * @since 4.0.4
     */
    private boolean isAnthropicSonnet5OrLater(String model) {
        if (model == null || model.isEmpty()) {
            return false;
        }
        java.util.regex.Matcher m1 = java.util.regex.Pattern
                .compile("sonnet-(\\d+)(?:[.@-]|$)", java.util.regex.Pattern.CASE_INSENSITIVE)
                .matcher(model);
        if (m1.find()) {
            return Integer.parseInt(m1.group(1)) >= 5;
        }
        java.util.regex.Matcher m2 = java.util.regex.Pattern
                .compile("claude-(\\d+)-sonnet(?:[.@-]|$)", java.util.regex.Pattern.CASE_INSENSITIVE)
                .matcher(model);
        if (m2.find()) {
            return Integer.parseInt(m2.group(1)) >= 5;
        }
        return false;
    }

    private static boolean containsAny(String text, String... tokens) {
        if (text == null || text.isEmpty() || tokens == null) {
            return false;
        }
        for (String token : tokens) {
            if (token != null && !token.isEmpty() && text.contains(token)) {
                return true;
            }
        }
        return false;
    }

    /**
     * adaptive 档位：4.6 为 low/medium/high/max；4.7+/sonnet-5+ 额外支持 xhigh。
     * 统一 API 的 max 保持 max；非法值返回 null。
     *
     * @since 4.0.4
     */
    private String clampAnthropicAdaptiveEffort(String effort, ChatConfig config) {
        if (effort == null || effort.isEmpty() || "auto".equals(effort)) {
            return null;
        }
        String model = config == null || config.getModel() == null ? "" : config.getModel().toLowerCase();
        // 4.7+ / sonnet-5+ / fable-5 支持 xhigh
        boolean supportsXhigh = isAnthropicOmitsThinkingModel(config)
                || model.contains("4-7") || model.contains("4.7");

        if ("low".equals(effort) || "medium".equals(effort) || "high".equals(effort) || "max".equals(effort)) {
            return effort;
        }
        if ("xhigh".equals(effort) || "min".equals(effort) || "minimal".equals(effort)) {
            if ("xhigh".equals(effort)) {
                return supportsXhigh ? "xhigh" : "max";
            }
            // min/minimal → low
            return "low";
        }
        if ("none".equals(effort)) {
            return null;
        }
        return null;
    }

    /**
     * 将已写出的 thinking.budget_tokens 钳制到 max_tokens - 1。
     *
     * @since 4.0.4
     */
    private void clampThinkingBudgetToMaxTokens(ONode root, ChatOptions options) {
        if (root == null || !root.hasKey("thinking")) {
            return;
        }
        ONode thinkingNode = root.get("thinking");
        if (thinkingNode == null || !thinkingNode.hasKey("budget_tokens")) {
            return;
        }
        int maxTokens = resolveMaxTokens(root, options);
        if (maxTokens <= 1) {
            root.remove("thinking");
            return;
        }
        int budget = thinkingNode.get("budget_tokens").getInt();
        if (budget >= maxTokens) {
            thinkingNode.set("budget_tokens", maxTokens - 1);
        }
    }

    private int resolveMaxTokens(ONode root, ChatOptions options) {
        int maxTokens = 32000;
        Object maxTokensObj = options == null ? null : options.options().get("max_tokens");
        if (maxTokensObj instanceof Number) {
            maxTokens = ((Number) maxTokensObj).intValue();
        } else if (root != null && root.hasKey("max_tokens")) {
            maxTokens = root.get("max_tokens").getInt();
        }
        return maxTokens;
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

            // 提取 reasoning（思考）内容，放入 thinking 块
            String reasoning = assistantMessage.getReasoning();
            if (Utils.isNotEmpty(reasoning)) {
                // 尝试从 contentRaw 中获取 signature
                String signature = "";
                Object contentRaw = assistantMessage.getContentRaw();
                if (contentRaw instanceof Map) {
                    Object sig = ((Map<?, ?>) contentRaw).get("thinkingSignature");
                    if (sig instanceof String && Utils.isNotEmpty((String) sig)) {
                        signature = (String) sig;
                    }
                }
                contentArray.addNew()
                    .set("type", "thinking")
                    .set("thinking", reasoning)
                    .set("signature", signature);
            }

            // 添加文本内容（如果有，排除 <think>...</think> 部分）
            String resultContent = assistantMessage.getResultContent();
            if (Utils.isNotEmpty(resultContent)) {
                contentArray.addNew()
                    .set("type", "text")
                    .set("text", resultContent);
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

        CacheControl cacheControl = options.cacheControl();

        ONode toolsNode = root.getOrNew("tools").asArray();
        int toolCount = 0;
        int totalTools = tools.size();
        for (FunctionTool func : tools) {
            toolCount++;
            final boolean isLast = (toolCount == totalTools);
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

                // ⭐ 在最后一个工具定义上添加 cache_control (Anthropic Prompt Caching)
                if (isLast && cacheControl != null) {
                    toolNode.getOrNew("cache_control").set("type", cacheControl.getType());
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

        // 当开启思考模式时，需要在 tool_use 之前添加 thinking 块
        String thinkingContent = resp.reasoningBuilder.toString();
        if (Utils.isNotEmpty(thinkingContent)) {
            ONode thinkingBlock = contentArray.addNew();
            thinkingBlock.set("type", "thinking");
            thinkingBlock.set("thinking", thinkingContent);
            // signature 是 Claude 思考签名，某些兼容接口要求回传
            if (Utils.isNotEmpty(resp.thinkingSignature)) {
                thinkingBlock.set("signature", resp.thinkingSignature);
            }
        }

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