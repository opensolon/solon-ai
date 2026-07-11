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

import org.noear.snack4.Feature;
import org.noear.snack4.ONode;
import org.noear.snack4.Options;
import org.noear.snack4.json.JsonReader;
import org.noear.snack4.json.util.FormatUtil;
import org.noear.solon.Utils;
import org.noear.solon.ai.chat.content.ContentBlock;
import org.noear.solon.ai.chat.content.AudioBlock;
import org.noear.solon.ai.chat.*;
import org.noear.solon.ai.chat.tool.*;
import org.noear.solon.ai.chat.message.*;
import org.noear.solon.ai.chat.content.ImageBlock;
import org.noear.solon.ai.chat.content.TextBlock;
import org.noear.solon.ai.chat.content.VideoBlock;
import org.noear.solon.core.util.Assert;
import org.noear.solon.net.http.HttpUtils;
import org.noear.solon.net.http.impl.HttpSslSupplierAny;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * 聊天模型方言虚拟类
 *
 * @author noear
 * @since 3.1
 */
public abstract class AbstractChatDialect implements ChatDialect {
    private static final Logger LOG = LoggerFactory.getLogger(AbstractChatDialect.class);

    protected String getApiUrl(ChatConfig config){
        return config.getApiUrl();
    }

    @Override
    public HttpUtils createHttpUtils(ChatConfig config, boolean isStream) {
        HttpUtils httpUtils = HttpUtils.http(getApiUrl(config))
                .ssl(HttpSslSupplierAny.getInstance())
                .timeout((int) config.getTimeout().getSeconds());

        if (config.getProxy() != null) {
            httpUtils.proxy(config.getProxy());
        }

        if (Utils.isNotEmpty(config.getApiKey())) {
            httpUtils.header("Authorization", "Bearer " + config.getApiKey());
        }

        if (Utils.isNotEmpty(config.getUserAgent())) {
            httpUtils.userAgent(config.getUserAgent());
        }

        httpUtils.headers(config.getHeaders());

        return httpUtils;
    }

    @Override
    public void prepareOutputSchemaInstruction(String outputSchema, StringBuilder instructionBuilder) {
        instructionBuilder.append("\n\n## [IMPORTANT: OUTPUT FORMAT]\n")
                .append("Format your response as a JSON object strictly following this schema:\n")
                .append("<output_schema>\n").append(outputSchema).append("\n</output_schema>\n")
                .append("Output only the raw JSON, beginning with '{' and ending with '}'.");
    }

    @Override
    public void prepareOutputFormatOptions(ChatOptions options) {
        options.optionSet("response_format", Utils.asMap("type", "json_object"));
    }

    protected void buildAssistantMessageNodeDo(ChatConfig config, ONode oNode, AssistantMessage msg) {
        oNode.set("role", msg.getRole().name().toLowerCase());

        if (Utils.isNotEmpty(msg.getResultContent())) {
            oNode.set("content", msg.getResultContent());
        }

        //兼容 r1 的 tool-call(可以再优化，只在最后一条加)
        if (Utils.isNotEmpty(msg.getReasoningFieldName())) {
            oNode.set(msg.getReasoningFieldName(), msg.getReasoning());
        }

        if (Utils.isNotEmpty(msg.getToolCallsRaw())) {
            oNode.set("tool_calls", ONode.ofBean(msg.getToolCallsRaw()));
        }
    }

    protected void buildSystemMessageNodeDo(ChatConfig config, ONode oNode, SystemMessage msg) {
        oNode.set("role", msg.getRole().name().toLowerCase());
        oNode.set("content", msg.getContent());
    }

    protected void buildToolMessageNodeDo(ChatConfig config, ONode oNode, ToolMessage msg) {
        oNode.set("role", msg.getRole().name().toLowerCase());

        if (Utils.isNotEmpty(msg.getName())) {
            oNode.set("name", msg.getName());
        }

        if (Utils.isNotEmpty(msg.getToolCallId())) {
            oNode.set("tool_call_id", msg.getToolCallId());
        }

        if (msg.isMultiModal() == false) {
            oNode.set("content", msg.getContent());
        } else {
            oNode.getOrNew("content").then(n1 -> {
                for (ContentBlock m1 : msg.getBlocks()) {
                    ONode m1Node = null;

                    if (m1 instanceof TextBlock) {
                        TextBlock m1Text = (TextBlock) m1;
                        n1.addNew().set("type", "text").set("text", m1Text.getContent());
                    } else if (m1 instanceof ImageBlock) {
                        m1Node = n1.addNew();

                        m1Node.set("type", "image_url");
                        m1Node.getOrNew("image_url").set("url", m1.toDataString(true));

                    } else if (m1 instanceof AudioBlock) {
                        m1Node = n1.addNew();

                        m1Node.set("type", "audio_url");
                        m1Node.getOrNew("audio_url").set("url", m1.toDataString(true));
                    } else if (m1 instanceof VideoBlock) {
                        m1Node = n1.addNew();

                        m1Node.set("type", "video_url");
                        m1Node.getOrNew("video_url").set("url", m1.toDataString(true));
                    }

                    if (m1Node != null) {
                        if (Utils.isNotEmpty(m1.metas())) {
                            for (Map.Entry<String, Object> entry : m1.metas().entrySet()) {
                                if (m1Node.hasKey(entry.getKey()) == false) {
                                    m1Node.set(entry.getKey(), ONode.ofBean(entry.getValue()));
                                }
                            }
                        }
                    }
                }
            });
        }
    }

    protected void buildUserMessageNodeDo(ChatConfig config, ONode oNode, UserMessage msg) {
        oNode.set("role", msg.getRole().name().toLowerCase());

        if (msg.isMultiModal() == false) {
            //单模态
            oNode.set("content", msg.getContent());
        } else {
            //多模态
            oNode.getOrNew("content").then(n1 -> {
                for (ContentBlock block1 : msg.getBlocks()) {
                    ONode oNode1 = null;

                    if (block1 instanceof TextBlock) {
                        TextBlock m1Text = (TextBlock) block1;
                        n1.addNew().set("type", "text").set("text", m1Text.getContent());
                    } else if (block1 instanceof ImageBlock) {
                        oNode1 = n1.addNew();

                        oNode1.set("type", "image_url");
                        oNode1.getOrNew("image_url").set("url", block1.toDataString(true));

                    } else if (block1 instanceof AudioBlock) {
                        oNode1 = n1.addNew();

                        oNode1.set("type", "audio_url");
                        oNode1.getOrNew("audio_url").set("url", block1.toDataString(true));
                    } else if (block1 instanceof VideoBlock) {
                        oNode1 = n1.addNew();

                        oNode1.set("type", "video_url");
                        oNode1.getOrNew("video_url").set("url", block1.toDataString(true));
                    }

                    if (oNode1 != null) {
                        if (Utils.isNotEmpty(block1.metas())) {
                            for (Map.Entry<String, Object> entry : block1.metas().entrySet()) {
                                if (oNode1.hasKey(entry.getKey()) == false) {
                                    oNode1.set(entry.getKey(), ONode.ofBean(entry.getValue()));
                                }
                            }
                        }
                    }
                }
            });
        }
    }


    public ONode buildChatMessageNode(ChatConfig config, ChatMessage chatMessage) {
        ONode oNode = new ONode();
        if (chatMessage instanceof AssistantMessage) {
            buildAssistantMessageNodeDo(config, oNode, (AssistantMessage) chatMessage);
        } else if (chatMessage instanceof SystemMessage) {
            buildSystemMessageNodeDo(config, oNode, (SystemMessage) chatMessage);
        } else if (chatMessage instanceof ToolMessage) {
            buildToolMessageNodeDo(config, oNode, (ToolMessage) chatMessage);
        } else if (chatMessage instanceof UserMessage) {
            buildUserMessageNodeDo(config, oNode, (UserMessage) chatMessage);
        } else {
            throw new IllegalArgumentException("Unsupported chat message type: " + chatMessage.getClass());
        }

        return oNode;
    }

    /**
     * 构建请求工具节点
     */
    protected void buildReqToolsNode(ONode n, ChatConfig config, ChatOptions options, ChatMessage lastMessage) {
        //buildReqToolsNodeDo(n, config.getDefaultTools());
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
                        toolNode.set("description", func.descriptionAndMeta());
                        toolNode.set("parameters", ONode.ofJson(func.inputSchema()));
                    });
                });
            }
        });
    }

    @Override
    public ONode buildRequestJson(ChatConfig config, ChatOptions options, List<ChatMessage> messages, boolean isStream) {
        return new ONode().then(n -> {
            if (Utils.isNotEmpty(config.getModel())) {
                n.set("model", config.getModel());
            }

            n.getOrNew("messages").then(n1 -> {
                for (ChatMessage m1 : messages) {
                    if (m1.isThinking() == false || m1.isToolCalls()) {
                        n1.add(buildChatMessageNode(config, m1));
                    }
                }
            });

            n.set("stream", isStream);

            // ⭐ 支持 prompt_cache_key（OpenAI 兼容协议字段，DeepSeek 等提供商使用此键复用前缀缓存）
            CacheControl cacheControl = options.cacheControl();
            if (cacheControl != null && Utils.isNotEmpty(cacheControl.getPromptCacheKey())) {
                n.set("prompt_cache_key", cacheControl.getPromptCacheKey());
            }

            for (Map.Entry<String, Object> kv : options.options().entrySet()) {
                String key = kv.getKey();
                Object value = kv.getValue();
            
                // 统一推理水平 → 顶层 reasoning_effort（Chat Completions / 兼容协议）
                // 仅归一化本字段后写出；非法值不落库，避免污染请求
                // 国产模型族（qwen/kimi/glm/minimax）默认不写顶层 effort（对齐 OpenCode variants 空）
                // OpenRouter：嵌套 reasoning.effort
                // 注意：effort 字段本身不在此开启 thinking；循环结束后按 OpenCode 语义隐式开启
                if ("reasoning_effort".equals(key)) {
                    if (shouldSkipChatCompletionsReasoningEffort(config)) {
                        continue;
                    }
                    String effort = clampChatCompletionsEffort(value, config);
                    if (effort != null) {
                        if (isOpenRouterEndpoint(config)) {
                            n.getOrNew("reasoning").set("effort", effort);
                        } else {
                            n.set("reasoning_effort", effort);
                        }
                    }
                    continue;
                }
                
                // 统一思考开关：按 model（辅以 provider/apiUrl）单写对应字段，避免双写触发严格网关 400
                // 非 Boolean（Map 等）仍按原 key 透传，供供应商原生配置与逃生舱使用
                if ("thinking".equals(key)) {
                    if (value instanceof Boolean) {
                        applyChatCompletionsThinkingSwitch(n, config, (Boolean) value);
                    } else if (value != null) {
                        n.set(key, ONode.ofBean(value));
                    }
                    continue;
                }
                
                n.set(key, ONode.ofBean(value));
            }
            
            // 对齐 OpenCode variants：选了 effort 档位时，对需要显式开关的模型隐式开启 thinking
            // thinking(false) / 显式 Map thinking 优先，不覆盖
            maybeEnableThinkingFromReasoningEffort(n, config, options);
            
            ChatMessage lastMessage = messages.get(messages.size() - 1);
            buildReqToolsNode(n, config, options, lastMessage);
        });
    }

    @Override
    public ONode buildAssistantToolCallMessageNode(ChatResponseDefault resp, Map<String, ToolCallBuilder> toolCallBuilders) {
        ONode oNode = new ONode();
        oNode.set("role", "assistant");
        oNode.set("content", resp.getAggregationContent());
        oNode.getOrNew("tool_calls").asArray().then(n1 -> {
            for (Map.Entry<String, ToolCallBuilder> kv : toolCallBuilders.entrySet()) {
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
    public AssistantMessage buildAssistantMessageByToolMessages(AssistantMessage toolCallMessage, List<ToolMessage> toolMessages) {
        //要求直接返回（转为新的响应消息）
        StringBuffer buf = new StringBuffer();
        for (ToolMessage toolMessage : toolMessages) {
            if (buf.length() > 0) {
                buf.append('\n');
            }
            buf.append(toolMessage.getContent());
        }

        AssistantMessage assistantMessage = ChatMessage.ofAssistant(buf.toString())
                .addMetadata("reason", "tool")
                .addMetadata("source", toolCallMessage.getResultContent());

        for (ToolMessage toolMessage : toolMessages) {
            assistantMessage.addMetadata(toolMessage.getMetadata());
        }

        return assistantMessage;
    }

    /**
     * 解析工具调用
     */
    protected List<ToolCall> parseToolCalls(ChatResponseDefault resp, ONode toolCallsNode) {
        if (toolCallsNode == null) {
            return null;
        }

        List<ToolCall> toolCalls = new ArrayList<>();

        for (ONode n1 : toolCallsNode.getArray()) {
            toolCalls.add(parseToolCall(resp, n1));
        }

        return toolCalls;
    }

    protected ToolCall parseToolCall(ChatResponseDefault resp, ONode n1) {
        String callId = n1.get("id").getString();

        if (Utils.isNotEmpty(callId)) {
            resp.lastToolCallId = callId;
        }

        String index = resp.lastToolCallId;

        ONode n1f = n1.get("function");
        String name = n1f.get("name").getString(); //可能是空的
        ONode n1fArgs = n1f.get("arguments");
        String argStr = n1fArgs.getString();

        if (n1fArgs.isString()) {
            //有可能是 json string（还可能只是流的中间消息）
            if (hasNestedJsonBlock(argStr)) {
                JsonReader reader = new JsonReader(argStr, Options.of(Feature.Read_AutoRepair));
                n1fArgs = reader.readLast();

                if (n1fArgs == null) {
                    LOG.warn("Parse tool arguments failed: {}", argStr);
                }
            }
        }

        Map<String, Object> argMap = new HashMap<>();
        if (n1fArgs != null) {
            if (n1fArgs.isObject()) {
                argMap = n1fArgs.toBean(Map.class);
            }
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
        List<ToolCall> toolCalls = parseToolCalls(resp, toolCallsNode);
        List<Map> searchResultsRaw = null;

        if (Utils.isNotEmpty(toolCalls)) {
            toolCallsRaw = toolCallsNode.toBean(List.class);
            if (resp.in_thinking && resp.isStream()) {
                //说明是思考结束立刻调用了工具，需要添加思考的结束标识
                messageList.add(new AssistantMessage("</think>", true).reasoningFieldName(resp.reasoning_field_name));
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

        final String reasoning_content;
        if (oMessage.hasKey("reasoning_content")) {
            reasoning_content = oMessage.get("reasoning_content").getValueAs();
            resp.reasoning_field_name = "reasoning_content";
        } else if (oMessage.hasKey("reasoning")) {
            reasoning_content = oMessage.get("reasoning").getValueAs();
            resp.reasoning_field_name = "reasoning";
        } else {
            reasoning_content = null;
        }

        if (Utils.isNotEmpty(reasoning_content)) {
            resp.has_reasoning_field = true;
            //有思考专属内容的协议
            if (resp.isStream()) {
                //如果是流返回（可能要拆成多条流消息）
                if (Utils.isEmpty(content)) {
                    if (resp.in_thinking == false) {
                        //说明是第一次
                        messageList.add(new AssistantMessage("<think>", true).reasoningFieldName(resp.reasoning_field_name));
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
                        messageList.add(new AssistantMessage("</think>", true).reasoningFieldName(resp.reasoning_field_name));
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
                        messageList.add(new AssistantMessage("</think>", true).reasoningFieldName(resp.reasoning_field_name));
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
                                messageList.add(new AssistantMessage(content, true).reasoningFieldName(resp.reasoning_field_name));
                                return messageList;
                            }
                        }
                    }
                }
            }
        }

        if (content != null || toolCallsRaw != null) {
            Object contentRaw = oContent.toBean();
            AssistantMessage message = new AssistantMessage(content, resp.in_thinking, contentRaw, toolCallsRaw, toolCalls, searchResultsRaw)
                    .reasoningFieldName(resp.reasoning_field_name);

            messageList.add(message);
        }

        return messageList;
    }


    protected boolean hasNestedJsonBlock(String str) {
        return FormatUtil.hasNestedJsonBlock(str);
    }

    /**
     * Chat Completions 思考开关写出形态（按模型族单写，避免多余字段）。
     *
     * @since 4.0.4
     */
    protected enum ThinkingSwitchWire {
        /** Qwen / DashScope 兼容 / 多数中转：{@code enable_thinking} */
        ENABLE_THINKING,
        /** DeepSeek / Kimi / 火山等：{@code thinking.type=enabled|disabled} */
        THINKING_TYPE,
        /** 智谱：{@code thinking.type} + {@code clear_thinking=false}（开启时） */
        THINKING_TYPE_CLEAR,
        /** MiniMax：开启 {@code adaptive}，关闭 {@code disabled} */
        THINKING_TYPE_ADAPTIVE,
        /** 无标准布尔开关（如 OpenAI 官方）：不写出，仅保留 Map 逃生舱 */
        NONE
    }
                
    /**
     * 按 model 为主、provider/apiUrl 为辅，解析思考开关写出形态。
     * <p>中转站（DashScope / ModelScope / SiliconFlow 等）即使模型名是 deepseek，
     * 也优先走 {@code enable_thinking}。</p>
     *
     * @since 4.0.4
     */
    protected ThinkingSwitchWire resolveThinkingSwitchWire(ChatConfig config) {
        String model = config == null || config.getModel() == null ? "" : config.getModel().toLowerCase(Locale.ROOT);
        String provider = config == null || config.getProvider() == null ? "" : config.getProvider().toLowerCase(Locale.ROOT);
        String apiUrl = config == null || config.getApiUrl() == null ? "" : config.getApiUrl().toLowerCase(Locale.ROOT);
        String hint = model + ' ' + provider + ' ' + apiUrl;
        
        // 已知“enable_thinking 中转站”：同一 deepseek 模型在中转口也走布尔开关
        if (containsAny(hint, "dashscope", "modelscope", "aliyuncs", "aliyun", "bailian",
                "siliconflow", "together.ai", "together.xyz")) {
            if (containsAny(model, "minimax")) {
                return ThinkingSwitchWire.THINKING_TYPE_ADAPTIVE;
            }
            return ThinkingSwitchWire.ENABLE_THINKING;
        }

        // 模型族（主信号）
        if (containsAny(model, "minimax") || containsAny(provider, "minimax")) {
            return ThinkingSwitchWire.THINKING_TYPE_ADAPTIVE;
        }
        if (containsAny(model, "glm", "zhipu", "zai-") || containsAny(provider, "zhipu", "zai", "glm")) {
            return ThinkingSwitchWire.THINKING_TYPE_CLEAR;
        }
        if (containsAny(model, "deepseek", "kimi", "moonshot", "doubao", "seed-")
                || containsAny(provider, "deepseek", "moonshot", "kimi", "volc", "doubao", "ark")) {
            return ThinkingSwitchWire.THINKING_TYPE;
        }
        if (containsAny(model, "qwen", "qwq") || containsAny(provider, "qwen", "dashscope")) {
            return ThinkingSwitchWire.ENABLE_THINKING;
        }
        // OpenAI 官方系列：无 enable_thinking / thinking.type 标准开关
        if (containsAny(model, "gpt-", "gpt4", "gpt5", "chatgpt", "o1-", "o1", "o3-", "o3", "o4-", "o4")
                || "openai".equals(provider)) {
            return ThinkingSwitchWire.NONE;
        }

        // 未知模型：不臆造字段，避免严格网关 400；用户可用 optionSet 显式配置
        return ThinkingSwitchWire.NONE;
    }

    /**
     * 将统一 {@code thinking(Boolean)} 映射为 Chat Completions 供应商字段（单写）。
     *
     * @since 4.0.4
     */
    protected void applyChatCompletionsThinkingSwitch(ONode n, ChatConfig config, boolean enabled) {
        ThinkingSwitchWire wire = resolveThinkingSwitchWire(config);
        switch (wire) {
            case ENABLE_THINKING:
                n.set("enable_thinking", enabled);
                break;
            case THINKING_TYPE:
                n.getOrNew("thinking").set("type", enabled ? "enabled" : "disabled");
                break;
            case THINKING_TYPE_CLEAR:
                n.getOrNew("thinking").then(t -> {
                    t.set("type", enabled ? "enabled" : "disabled");
                    if (enabled) {
                        // 智谱官方示例常带 clear_thinking=false
                        t.set("clear_thinking", false);
                    }
                });
                break;
            case THINKING_TYPE_ADAPTIVE:
                n.getOrNew("thinking").set("type", enabled ? "adaptive" : "disabled");
                break;
            case NONE:
            default:
                // 无标准布尔开关：不写出，保留 optionSet("thinking", map) 逃生舱
                break;
        }
    }

    /**
     * 对齐 OpenCode：用户设置 {@code reasoning_effort} 时，对需要显式思考开关的模型隐式开启 thinking。
     * <p>优先级：{@code thinking(false)} 关闭优先；显式 Map/对象 {@code thinking} 不覆盖；
     * 已写出 {@code enable_thinking}/{@code thinking.type} 时不重复写。</p>
     * <p>OpenAI 系（wire=NONE）effort 本身即推理控制，无需额外开关。</p>
     *
     * @since 4.0.4
     */
    protected void maybeEnableThinkingFromReasoningEffort(ONode n, ChatConfig config, ChatOptions options) {
        if (n == null || options == null) {
            return;
        }
        Object effortObj = options.options().get("reasoning_effort");
        if (effortObj == null) {
            return;
        }
        // 无有效 effort 档位时不隐式开启（auto/空/非法已被 API 层移除或 clamp 为 null）
        String effort = String.valueOf(effortObj).trim().toLowerCase(Locale.ROOT);
        if (effort.isEmpty() || "auto".equals(effort) || "none".equals(effort)) {
            return;
        }
     
        Object thinkingOpt = options.options().get("thinking");
        // 显式关闭优先
        if (Boolean.FALSE.equals(thinkingOpt)) {
            return;
        }
        // 显式 Map/对象 thinking 逃生舱：不覆盖
        if (thinkingOpt != null && !(thinkingOpt instanceof Boolean)) {
            return;
        }
        // 已有开关字段则不重复写（thinking(true) 或 Map 已写出）
        if (n.hasKey("enable_thinking")) {
            return;
        }
        if (n.hasKey("thinking")) {
            return;
        }
     
        ThinkingSwitchWire wire = resolveThinkingSwitchWire(config);
        if (wire == ThinkingSwitchWire.NONE) {
            // OpenAI 等：reasoning_effort 本身即控制，无需 enable 位
            return;
        }
        // 仅对需要显式开关的模型族开启（qwen/deepseek/kimi/glm/minimax/中转）
        applyChatCompletionsThinkingSwitch(n, config, true);
    }
     
    /**
     * 是否抑制 Chat Completions 顶层 reasoning_effort。
     * <p>对齐 OpenCode：qwen / deepseek / kimi / glm / minimax 等不做 effort 变体，
     * 仅靠 thinking 开关；显式 optionSet 其它供应商字段仍可用。</p>
     * <p>例外：DeepSeek 官方认 high/max，仍写出；GLM-5.2 支持 high/max effort 变体，仍写出；
     * OpenRouter 走嵌套不走本抑制。</p>
     *
     * @since 4.0.4
     */
    protected boolean shouldSkipChatCompletionsReasoningEffort(ChatConfig config) {
        if (config == null) {
            return false;
        }
        // OpenRouter 单独嵌套写出，不在此抑制
        if (isOpenRouterEndpoint(config)) {
            return false;
        }
        // DeepSeek 官方支持 reasoning_effort high/max，保留
        if (isDeepSeekOfficialEffort(config)) {
            return false;
        }
        // GLM-5.2：OpenCode variants 有 high/max（openai-compatible / openrouter），不抑制
        if (isGlm52EffortModel(config)) {
            return false;
        }

        String model = config.getModel() == null ? "" : config.getModel().toLowerCase(Locale.ROOT);
        String provider = config.getProvider() == null ? "" : config.getProvider().toLowerCase(Locale.ROOT);
        String apiUrl = config.getApiUrl() == null ? "" : config.getApiUrl().toLowerCase(Locale.ROOT);
        String hint = model + ' ' + provider + ' ' + apiUrl;

        // 与 OpenCode variants() 空表一致：这些模型族不写顶层 effort（glm-5.2 已在上方豁免）
        return containsAny(model, "qwen", "qwq", "minimax", "glm", "zhipu", "kimi", "moonshot", "k2p",
                "deepseek", "doubao", "seed-")
                || containsAny(provider, "qwen", "dashscope", "minimax", "zhipu", "zai", "glm",
                "moonshot", "kimi", "deepseek", "volc", "doubao", "ark")
                || containsAny(hint, "dashscope", "modelscope", "aliyuncs", "aliyun", "bailian",
                "siliconflow");
    }

    /**
     * GLM-5.2 支持 effort 变体（对齐 OpenCode：high/max 或 OpenRouter 的 high/xhigh）。
     *
     * @since 4.0.4
     */
    protected boolean isGlm52EffortModel(ChatConfig config) {
        if (config == null) {
            return false;
        }
        String model = config.getModel() == null ? "" : config.getModel().toLowerCase(Locale.ROOT);
        // glm-5.2 / glm-5-2 / glm-5p2
        return containsAny(model, "glm-5.2", "glm-5-2", "glm-5p2");
    }

    /**
     * 规范化 Chat Completions 顶层 reasoning_effort。
     * <p>默认保留官方常见档位；统一语义 {@code max} → {@code xhigh}，{@code min} → {@code low}。
     * DeepSeek 官方形态仅认 {@code high}/{@code max}（{@code max} 不转 xhigh）。
     * GLM-5.2：high/max（OpenRouter 上将 max 映为 xhigh）。
     * 无法识别时返回 null（不写出，避免污染请求）。</p>
     *
     * @since 4.0.4
     */
    protected String clampChatCompletionsEffort(Object value, ChatConfig config) {
        if (value == null) {
            return null;
        }
        String effort = String.valueOf(value).trim().toLowerCase(Locale.ROOT);
        if (effort.isEmpty() || "auto".equals(effort)) {
            return null;
        }

        // DeepSeek 官方：high / max；low/medium → high；xhigh → max
        if (isDeepSeekOfficialEffort(config)) {
            if ("max".equals(effort) || "xhigh".equals(effort)) {
                return "max";
            }
            if ("high".equals(effort) || "medium".equals(effort) || "low".equals(effort)
                    || "minimal".equals(effort) || "min".equals(effort)) {
                return "high";
            }
            if ("none".equals(effort)) {
                return null; // 关闭请用 thinking(false)
            }
            return null;
        }

        // GLM-5.2：openai-compatible 用 high/max；OpenRouter 用 high/xhigh
        if (isGlm52EffortModel(config)) {
            if ("high".equals(effort) || "medium".equals(effort) || "low".equals(effort)
                    || "minimal".equals(effort) || "min".equals(effort)) {
                return "high";
            }
            if ("max".equals(effort) || "xhigh".equals(effort)) {
                // OpenRouter 将 xhigh 映射到 GLM-5.2 的 max；嵌套写出时用 xhigh
                return isOpenRouterEndpoint(config) ? "xhigh" : "max";
            }
            if ("none".equals(effort)) {
                return null;
            }
            return null;
        }

        if ("none".equals(effort)
                || "minimal".equals(effort)
                || "low".equals(effort)
                || "medium".equals(effort)
                || "high".equals(effort)
                || "xhigh".equals(effort)) {
            return effort;
        }
        if ("max".equals(effort)) {
            return "xhigh";
        }
        if ("min".equals(effort)) {
            return "low";
        }
        // 非法值不写出
        return null;
    }

    /**
     * DeepSeek 官方 effort 语义（非 DashScope/SiliconFlow 等 enable_thinking 中转）。
     *
     * @since 4.0.4
     */
    protected boolean isDeepSeekOfficialEffort(ChatConfig config) {
        if (config == null) {
            return false;
        }
        String model = config.getModel() == null ? "" : config.getModel().toLowerCase(Locale.ROOT);
        String provider = config.getProvider() == null ? "" : config.getProvider().toLowerCase(Locale.ROOT);
        String apiUrl = config.getApiUrl() == null ? "" : config.getApiUrl().toLowerCase(Locale.ROOT);
        String hint = model + ' ' + provider + ' ' + apiUrl;

        if (!containsAny(model, "deepseek") && !containsAny(provider, "deepseek") && !containsAny(apiUrl, "deepseek")) {
            return false;
        }
        // 中转站走通用 OpenAI effort（或忽略），不按官方 high/max 改写
        if (containsAny(hint, "dashscope", "modelscope", "aliyuncs", "aliyun", "bailian",
                "siliconflow", "together.ai", "together.xyz")) {
            return false;
        }
        return true;
    }

    /**
     * OpenRouter：effort 应写在 {@code reasoning.effort} 嵌套对象，而非顶层 reasoning_effort。
     *
     * @since 4.0.4
     */
    protected boolean isOpenRouterEndpoint(ChatConfig config) {
        if (config == null) {
            return false;
        }
        String provider = config.getProvider() == null ? "" : config.getProvider().toLowerCase(Locale.ROOT);
        String apiUrl = config.getApiUrl() == null ? "" : config.getApiUrl().toLowerCase(Locale.ROOT);
        return containsAny(provider, "openrouter") || containsAny(apiUrl, "openrouter");
    }

    /**
     * @since 4.0.4
     */
    protected static boolean containsAny(String text, String... tokens) {
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
     * @deprecated 使用 {@link #clampChatCompletionsEffort(Object, ChatConfig)}
     * @since 4.0.4
     */
    protected String clampChatCompletionsEffort(Object value) {
        return clampChatCompletionsEffort(value, null);
    }
}