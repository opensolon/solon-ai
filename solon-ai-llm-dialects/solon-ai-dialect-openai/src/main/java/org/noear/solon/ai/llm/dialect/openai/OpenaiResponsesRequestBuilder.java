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
import org.noear.solon.ai.chat.CacheControl;
import org.noear.solon.ai.chat.ChatAccumulator;
import org.noear.solon.ai.chat.content.ContentBlock;
import org.noear.solon.ai.chat.ChatConfig;
import org.noear.solon.ai.chat.ChatOptions;
import org.noear.solon.ai.chat.content.TextBlock;
import org.noear.solon.ai.chat.message.*;
import org.noear.solon.ai.chat.tool.FunctionTool;
import org.noear.solon.ai.chat.tool.ToolSchemaUtil;
import org.noear.solon.ai.chat.tool.ToolCall;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.noear.solon.ai.chat.tool.ToolCallBuilder;
import org.noear.solon.ai.chat.tool.ToolCallJsonSanitizer;
import org.noear.solon.ai.chat.content.AudioBlock;
import org.noear.solon.ai.chat.content.BlobBlock;
import org.noear.solon.ai.chat.content.ImageBlock;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * OpenAI Responses API 请求构建器
 * @author oisin lu
 * @date 2026年1月28日
 */
public class OpenaiResponsesRequestBuilder {
    private static final Logger log = LoggerFactory.getLogger(OpenaiResponsesRequestBuilder.class);

    /**
     * Chat Completions 专属、Responses API 不接受的参数（官方端点会 400）。
     * <p>统一在构建期剔除，避免上层沿用同一份 ChatOptions 在两种协议间切换时报错。</p>
     *
     * @since 4.1
     */
    private static final Set<String> UNSUPPORTED_KEYS = new HashSet<>(Arrays.asList(
            "stop", "frequency_penalty", "presence_penalty",
            "logit_bias", "n", "seed"));

    /**
     * 无状态（{@code store=false}）多轮回放 reasoning 所需的 include 项。
     *
     * @since 4.1
     */
    private static final String INCLUDE_REASONING_ENCRYPTED = "reasoning.encrypted_content";

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
    public ONode build(ChatConfig config, ChatOptions options, List<ChatMessage> messages, boolean isStream) {
        ONode root = new ONode();
        if (Utils.isNotEmpty(config.getModel())) {
            root.set("model", config.getModel());
        }
        // Responses API: SystemMessage 提取到顶层 instructions 字段
        StringBuilder instructions = new StringBuilder();
        for (ChatMessage msg : messages) {
            if (msg instanceof SystemMessage && Utils.isNotEmpty(msg.getContent())) {
                if (instructions.length() > 0) {
                    instructions.append("\n\n");
                }
                instructions.append(msg.getContent());
            }
        }
        // 构建 input（将消息转为 input 数组，SystemMessage 已提取到 instructions）
        ONode inputArray = root.getOrNew("input").asArray();
        boolean allowInputAudio = Boolean.TRUE.equals(options.options().get("responses_input_audio_enabled"));
        boolean replayReasoning = isReasoningReplayEnabled(config.getModel(), options);
        for (ChatMessage msg : messages) {
            if (msg instanceof SystemMessage) {
                continue;
            }
            buildInputItem(inputArray, msg, allowInputAudio, replayReasoning);
        }
        root.set("stream", isStream);
        // 添加其他选项
        Object thinkingSwitch = null;
        Object promptCacheBreakpoint = null;
        Object promptCacheBreakpoints = null;
        String optionInstructions = null;
        for (Map.Entry<String, Object> kv : options.options().entrySet()) {
            String key = kv.getKey();
            // 跳过已处理的字段（response_format 不适用于 Responses API，使用 text.format 替代）
            if ("stream".equals(key) || "response_format".equals(key)) {
                continue;
            }
            // Responses 的 stream_options 与 Chat Completions 不是同一组字段；官方 SDK 当前支持
            // include_obfuscation，include_usage 属于 Chat Completions，不能原样转发。
            if ("stream_options".equals(key)) {
                if (isStream) {
                    ONode streamOptions = toNode(kv.getValue());
                    if (streamOptions.isObject() && streamOptions.hasKey("include_obfuscation")) {
                        root.getOrNew("stream_options")
                                .set("include_obfuscation", streamOptions.get("include_obfuscation").getBoolean());
                    }
                }
                continue;
            }
            // Chat Completions 专属参数：Responses API 不接受，剔除避免 400
            if (UNSUPPORTED_KEYS.contains(key)) {
                if (log.isDebugEnabled()) {
                    log.debug("OpenAI Responses: drop unsupported option '{}'", key);
                }
                continue;
            }
            // max_tokens / max_completion_tokens -> max_output_tokens 转换（已显式配置 max_output_tokens 时不覆盖）
            if ("max_tokens".equals(key) || "max_completion_tokens".equals(key)) {
                if (options.options().containsKey("max_output_tokens") == false) {
                    root.set("max_output_tokens", kv.getValue());
                }
                continue;
            }
            // instructions 与 SystemMessage 合并（延后处理，避免互相覆盖）
            if ("instructions".equals(key)) {
                optionInstructions = kv.getValue() == null ? null : String.valueOf(kv.getValue());
                continue;
            }
            if ("prompt_cache_breakpoint".equals(key)) {
                // SDK 将缓存断点放在 input content 上，而不是请求顶层；延后挂到最后一个可缓存内容项。
                promptCacheBreakpoint = kv.getValue();
                continue;
            }
            if ("prompt_cache_breakpoints".equals(key)) {
                // Solon 兼容扩展：按输入顺序将最多四个显式断点挂到末尾若干可缓存内容块。
                promptCacheBreakpoints = kv.getValue();
                continue;
            }
            if ("responses_reasoning_delta_mode".equals(key)
                    || "responses_input_audio_enabled".equals(key)
                    || "responses_reasoning_replay_enabled".equals(key)) {
                // 仅控制方言本地兼容行为，不得发送给服务端。
                continue;
            }
            if ("prompt_cache_options".equals(key)) {
                applyPromptCacheOptions(root, kv.getValue());
                continue;
            }
            if ("prompt_cache_retention".equals(key)) {
                applyPromptCacheRetention(root, kv.getValue());
                continue;
            }
            // 统一思考开关（Boolean）延后处理
            if ("thinking".equals(key) && kv.getValue() instanceof Boolean) {
                thinkingSwitch = kv.getValue();
                continue;
            }
            // 统一推理水平 → reasoning.effort（若尚未显式配置 reasoning）
            if ("reasoning_effort".equals(key)) {
                // 与 Boolean thinking 一起在循环后处理
                continue;
            }
            // 处理思考级别配置
            if ("reasoning".equals(key)) {
                buildReasoningNode(root, kv.getValue());
                continue;
            }
            // tool_choice：Chat Completions 的 {type:function,function:{name}} → Responses 的 {type:function,name}
            if ("tool_choice".equals(key)) {
                applyToolChoice(root, kv.getValue());
                continue;
            }

            root.set(key, toNode(kv.getValue()));
        }

        // 显式缓存断点挂到输入内容项（ResponseInputText/Image/File.prompt_cache_breakpoint）。
        if (promptCacheBreakpoints != null) {
            int applied = applyPromptCacheBreakpoints(inputArray, promptCacheBreakpoints);
            if (applied > 3 && root.hasKey("prompt_cache_options") == false) {
                root.getOrNew("prompt_cache_options").set("mode", "explicit");
            }
        } else {
            applyPromptCacheBreakpoint(inputArray, promptCacheBreakpoint);
        }

        // instructions：SystemMessage 优先在前，options 逃生舱追加在后
        if (Utils.isNotEmpty(optionInstructions)) {
            if (instructions.length() > 0) {
                instructions.append("\n\n");
            }
            instructions.append(optionInstructions);
        }
        if (instructions.length() > 0) {
            root.set("instructions", instructions.toString());
        }

        // 统一 thinking 开关 + reasoning_effort（显式 reasoning 优先）
        applyUnifiedReasoningOptions(root, options, thinkingSwitch, config.getModel());

        // store=false 时补 include，reasoning 的 encrypted_content 否则不会返回，多轮回放会断链
        applyReasoningInclude(root);

        // prompt_cache_key（官方 Responses API 独立的缓存路由提示字段）
        //    通过 ChatOptions.promptCacheKey() 传入，仅用于提升 KV cache 命中，不改变会话语义
        //    注意：与 previous_response_id（服务端会话续接）是两个不同字段；
        //    后者可经 options 直接透传（如 options.options().put("previous_response_id", ...)）
        CacheControl cacheControl = options.cacheControl();
        if (cacheControl != null && Utils.isNotEmpty(cacheControl.getPromptCacheKey())) {
            // CacheControl.type/ttl 是 Anthropic 消息级语义，不能解释为 Responses PromptCacheOptions。
            root.set("prompt_cache_key", cacheControl.getPromptCacheKey());
        }
        // 构建 tools
        buildToolsNode(root, options);
        return root;
    }

    /**
     * 构建 input
     * @author oisin lu
     * @date 2026年1月28日
     */
    private void buildInputItem(ONode inputArray, ChatMessage message, boolean allowInputAudio,
                                boolean replayReasoning) {
        if (message instanceof ToolMessage) {
            buildToolMessageInputItem(inputArray, (ToolMessage) message);
            return;
        }

        if (message instanceof AssistantMessage) {
            AssistantMessage assistantMessage = (AssistantMessage) message;
            // 必须在任何 getText()/isThinkingOnly() 调用前捕获；旧数据的惰性解析会回填 text。
            boolean legacyThinkInline = assistantMessage.getTextRaw() == null;

            // 优先按 Responses 原始 output_index 回放完整 output item，避免按类型重排或字段降级。
            if (appendResponsesOutputItems(inputArray, assistantMessage, replayReasoning)) {
                return;
            }

            // 1) reasoning 项先行（官方要求 reasoning 在其后续项之前），与正文 / function_call 并列而非二选一：
        // 完整消息允许同时包含 text/thinking，不能按旧流式分片标记做消息分类。
            boolean reasoningEmitted = replayReasoning
                    && appendReasoningInputItem(inputArray, assistantMessage);
            boolean responseMessagesEmitted = appendResponseMessageItems(inputArray, assistantMessage);

            // 2) 纯思考消息：reasoning 已在上方回放，无正文 / 无工具 / 无媒体时不再补空 assistant 项。
            if (assistantMessage.isThinkingOnly() && Utils.isEmpty(assistantMessage.getText())) {
                return;
            }

            buildAssistantInputItems(inputArray, assistantMessage, reasoningEmitted,
                    responseMessagesEmitted, allowInputAudio, legacyThinkInline);
            return;
        }

        if (message instanceof UserMessage) {
            UserMessage userMessage = (UserMessage) message;
            ONode msgNode = inputArray.addNew()
                    .set("role", "user");
            if (userMessage.isMultiModal() == false) {
                //单模态
                msgNode.set("content", userMessage.getContent());
            } else {
                //多模态（用户文本不做 think 剔除，避免正常包含 think 标签字样的文本被清空）
                ONode contentArray = msgNode.getOrNew("content").asArray();
                for (ContentBlock block1 : userMessage.getBlocks()) {
                    appendResponsesInputContent(contentArray, block1, false, allowInputAudio);
                }
                // 全部媒体被截断时补文本投影，避免出站空 content 数组
                if (contentArray.getArray().isEmpty()) {
                    msgNode.set("content", userMessage.getContent() == null ? "" : userMessage.getContent());
                }
            }
            return;
        }

        // 其他类型消息
        String role = message.getRole() != null ? message.getRole().name().toLowerCase() : "user";
        inputArray.addNew()
                .set("role", role)
                .set("content", message.getContent() != null ? message.getContent() : "");
    }

    /**
     * 构建函数工具输出。
     * <p>官方 {@code FunctionCallOutput.output} 支持字符串或
     * {@code ResponseFunctionCallOutputItem} 数组；后者仅包含
     * {@code input_text / input_image / input_file}。</p>
     *
     * @since 4.1
     */
    private void buildToolMessageInputItem(ONode inputArray, ToolMessage toolMessage) {
        ONode item = inputArray.addNew()
                .set("type", "function_call_output")
                .set("call_id", toolMessage.getToolCallId());

        if (toolMessage.isMultiModal() == false) {
            item.set("output", toolMessage.getContent() == null ? "" : toolMessage.getContent());
            return;
        }

        ONode outputArray = new ONode().asArray();
        for (ContentBlock block : toolMessage.getBlocks()) {
            appendFunctionCallOutputContent(outputArray, block);
        }

        if (outputArray.getArray().isEmpty()) {
            // output 为必填字段；不支持或已截断的媒体回退到文本投影，最终以空串占位。
            item.set("output", toolMessage.getContent() == null ? "" : toolMessage.getContent());
        } else {
            item.set("output", outputArray);
        }
    }

    /**
     * 追加函数工具输出内容项。
     *
     * @since 4.1
     */
    private void appendFunctionCallOutputContent(ONode outputArray, ContentBlock block) {
        if (block == null) {
            return;
        }

        if (block instanceof TextBlock) {
            String text = block.getContent();
            if (Utils.isNotEmpty(text)) {
                outputArray.addNew().set("type", "input_text").set("text", text);
            }
            return;
        }

        if (block instanceof ImageBlock) {
            String imageUrl = block.toDataString(true);
            if (Utils.isNotEmpty(imageUrl) && !imageUrl.startsWith("image-generation://")) {
                outputArray.addNew()
                        .set("type", "input_image")
                        .set("image_url", imageUrl)
                        .set("detail", "auto");
            }
            return;
        }

        if (block instanceof BlobBlock) {
            String fileData = ((BlobBlock) block).getBlob();
            if (Utils.isNotEmpty(fileData)) {
                outputArray.addNew()
                        .set("type", "input_file")
                        .set("file_data", fileData);
            }
        }
    }

    /**
     * 输出 reasoning input item。
     * <p>官方 OpenAI：多轮回放 reasoning 项须携带服务端返回的 id 或 encrypted_content；
     * 纯 {@code reasoning_text} 输入项是 DeepSeek Responses 的私有扩展（无状态回传）。</p>
     *
     * @return 是否已输出 reasoning 项
     * @since 4.1
     */
    @SuppressWarnings("unchecked")
    private boolean appendResponsesOutputItems(ONode inputArray, AssistantMessage message,
                                               boolean replayReasoning) {
        Map<String, Object> protocolData = OpenaiResponsesMessageStateSupport.resolveData(message);
        if (protocolData == null) return false;
        Object value = protocolData.get(OpenaiResponsesMessageStateSupport.OUTPUT_ITEMS);
        if (!(value instanceof Collection)) return false;

        List<Map<String, Object>> wrappers = new ArrayList<>();
        for (Object wrapper : (Collection<?>) value) {
            if (wrapper instanceof Map && ((Map<?, ?>) wrapper).get("item") instanceof Map) {
                wrappers.add((Map<String, Object>) wrapper);
            }
        }
        Collections.sort(wrappers, new Comparator<Map<String, Object>>() {
            @Override
            public int compare(Map<String, Object> left, Map<String, Object> right) {
                return Integer.compare(replayOutputIndex(left), replayOutputIndex(right));
            }
        });
        boolean emitted = false;
        for (Map<String, Object> wrapper : wrappers) {
            ONode item = toNode(wrapper.get("item"));
            String type = item.get("type").getString();
            if (!replayReasoning && "reasoning".equals(type)) {
                continue;
            }
            if ("function_call".equals(type)) {
                item.set("arguments", ToolCallJsonSanitizer.sanitizeArguments(
                        item.get("arguments").getString(), item.get("name").getString()));
            }
            inputArray.add(item);
            emitted = true;
        }
        return emitted;
    }

    private boolean isReasoningReplayEnabled(String model, ChatOptions options) {
        Object configured = options.options().get("responses_reasoning_replay_enabled");
        if (configured instanceof Boolean) {
            return (Boolean) configured;
        }

        // GLM 的 Responses 兼容层会把 input item 转成 Anthropic messages，
        // 但 reasoning item 没有 message.content，回放后会被其内部校验拒绝。
        return Utils.isEmpty(model) || !model.toLowerCase().startsWith("glm-");
    }

    private int replayOutputIndex(Map<String, Object> wrapper) {
        Object value = wrapper.get("output_index");
        return value instanceof Number ? ((Number) value).intValue() : Integer.MAX_VALUE;
    }

    private boolean appendReasoningInputItem(ONode inputArray, AssistantMessage assistantMessage) {
        Map<String, Object> protocolData = OpenaiResponsesMessageStateSupport.resolveData(assistantMessage);
        if (protocolData != null) {
            Object replayItems = protocolData.get(OpenaiResponsesMessageStateSupport.REASONING_ITEMS);
            boolean emitted = appendReasoningReplayItems(inputArray, replayItems);
            if (emitted) {
                return true;
            }
            Object reasoningId = protocolData.get(OpenaiResponsesMessageStateSupport.REASONING_ITEM_ID);
            Object encryptedContent = protocolData.get(OpenaiResponsesMessageStateSupport.REASONING_ENCRYPTED_CONTENT);
            String idStr = reasoningId == null ? null : String.valueOf(reasoningId);
            String encStr = encryptedContent == null ? null : String.valueOf(encryptedContent);

            if (Utils.isNotEmpty(idStr) || Utils.isNotEmpty(encStr)) {
                ONode reasoningItem = newReasoningItem(inputArray);
                if (Utils.isNotEmpty(idStr)) {
                    reasoningItem.set("id", idStr);
                }
                if (Utils.isNotEmpty(encStr)) {
                    reasoningItem.set("encrypted_content", encStr);
                }
                return true;
            }
        }

        // 无官方元数据：退化为 reasoning_text（DeepSeek 扩展）。
        // 4.1 起 thinking 与 text 已分离，直接取 thinking；旧数据（content 内嵌 think 标签）由 getThinking() 自行提取
        String thinkText = assistantMessage.getThinking();
        if (Utils.isNotEmpty(thinkText)) {
            ONode reasoningItem = newReasoningItem(inputArray);
            reasoningItem.getOrNew("content").asArray()
                    .addNew().set("type", "reasoning_text").set("text", thinkText);
            return true;
        }

        return false;
    }

    @SuppressWarnings("unchecked")
    private boolean appendReasoningReplayItems(ONode inputArray, Object value) {
        if (!(value instanceof Collection)) {
            return false;
        }
        boolean emitted = false;
        Set<String> identities = new HashSet<>();
        for (Object itemObj : (Collection<?>) value) {
            if (!(itemObj instanceof Map)) continue;
            Map<String, Object> item = (Map<String, Object>) itemObj;
            String id = item.get("id") == null ? null : String.valueOf(item.get("id"));
            String encrypted = item.get("encrypted_content") == null
                    ? null : String.valueOf(item.get("encrypted_content"));
            String identity = Utils.isNotEmpty(id) ? "id:" + id : "encrypted:" + encrypted;
            if ((Utils.isEmpty(id) && Utils.isEmpty(encrypted)) || !identities.add(identity)) continue;
            ONode reasoningItem = newReasoningItem(inputArray);
            if (Utils.isNotEmpty(id)) reasoningItem.set("id", id);
            if (Utils.isNotEmpty(encrypted)) reasoningItem.set("encrypted_content", encrypted);
            emitted = true;
        }
        return emitted;
    }

    @SuppressWarnings("unchecked")
    private boolean appendResponseMessageItems(ONode inputArray, AssistantMessage message) {
        Object value = OpenaiResponsesMessageStateSupport.get(message,
                OpenaiResponsesMessageStateSupport.MESSAGE_ITEMS);
        if (!(value instanceof Collection) || ((Collection<?>) value).size() <= 1) return false;
        boolean emitted = false;
        for (Object itemObj : (Collection<?>) value) {
            if (!(itemObj instanceof Map)) continue;
            Map<String, Object> item = (Map<String, Object>) itemObj;
            ONode node = inputArray.addNew()
                    .set("role", "assistant")
                    .set("content", item.get("text") == null ? "" : String.valueOf(item.get("text")));
            Object phase = item.get("phase");
            if (phase != null && ("commentary".equals(String.valueOf(phase))
                    || "final_answer".equals(String.valueOf(phase)))) {
                node.set("phase", String.valueOf(phase));
            }
            emitted = true;
        }
        return emitted;
    }

    /**
     * 新建 reasoning input item。
     * <p>官方 {@code ResponseReasoningItem} 的 {@code summary} 为必填（数组，可为空），
     * 缺失会被官方端点 400；此处统一补位。</p>
     *
     * @since 4.1
     */
    private ONode newReasoningItem(ONode inputArray) {
        ONode reasoningItem = inputArray.addNew().set("type", "reasoning");
        reasoningItem.getOrNew("summary").asArray();
        return reasoningItem;
    }

    /**
     * 追加 Responses 规范的输入内容项。
     * <p>官方 {@code ResponseInputContent} 仅允许 {@code input_text / input_image / input_file}；
     * 音频为 {@code {type:input_audio, input_audio:{data,format}}} 嵌套形态（部分厂商扩展支持），
     * 仅 URL 无 base64 时降级为说明文本，避免把 URL 塞进 data。</p>
     *
     * @param stripThink 是否剔除 {@code <think>} 标签（仅旧版 assistant 历史需要）
     * @since 4.1
     */
    private void appendResponsesInputContent(ONode contentArray, ContentBlock block,
                                             boolean stripThink, boolean allowInputAudio) {
        if (block == null) {
            return;
        }

        if (block instanceof TextBlock) {
            String text = block.getContent();
            if (stripThink) {
                text = AssistantMessage.stripThinkTags(text);
            }
            if (Utils.isNotEmpty(text)) {
                contentArray.addNew().set("type", "input_text").set("text", text);
            }
            return;
        }

        if (block instanceof ImageBlock) {
            // Session 截断后 data/url 皆空时跳过
            String imageUrl = block.toDataString(true);
            if (Utils.isNotEmpty(imageUrl) && !imageUrl.startsWith("image-generation://")) {
                contentArray.addNew()
                        .set("type", "input_image")
                        .set("image_url", imageUrl)
                        .set("detail", "auto");
            }
            return;
        }

        if (block instanceof AudioBlock) {
            if (!allowInputAudio) {
                throw new IllegalArgumentException("OpenAI Responses ResponseInputContent does not support input_audio; "
                        + "set responses_input_audio_enabled=true only for a compatible gateway");
            }
            AudioBlock audio = (AudioBlock) block;
            if (Utils.isNotEmpty(audio.getData())) {
                ONode audioNode = contentArray.addNew().set("type", "input_audio");
                ONode inputAudio = audioNode.getOrNew("input_audio");
                inputAudio.set("data", audio.getData());
                String mimeType = audio.getMimeType();
                if (Utils.isNotEmpty(mimeType) && mimeType.startsWith("audio/")) {
                    inputAudio.set("format", mimeType.substring(6));
                }
            } else if (Utils.isNotEmpty(audio.getUrl())
                    && !audio.getUrl().startsWith("audio://")) {
                contentArray.addNew()
                        .set("type", "input_text")
                        .set("text", "[audio]" + audio.getUrl());
            }
        }
    }

    /**
     * 构建 Assistant 历史 input items（含多模态与 image_generation_call 回传）。
     *
     * @param reasoningEmitted 本轮是否已输出 reasoning 项（决定空正文是否还需补位）
     * @since 3.9
     */
    private void buildAssistantInputItems(ONode inputArray, AssistantMessage assistantMessage,
                                           boolean reasoningEmitted, boolean responseMessagesEmitted,
                                           boolean allowInputAudio, boolean legacyThinkInline) {
        // 1) 先回传 image_generation_call 历史项（按官方多轮约定）
        if (Utils.isNotEmpty(assistantMessage.getBlocks())) {
            for (ContentBlock block : assistantMessage.getBlocks()) {
                if (!(block instanceof ImageBlock) || !isImageGenerationBlock(block)) {
                    continue;
                }
                String genId = getImageGenerationId(block);
                if (Utils.isNotEmpty(genId)) {
                    inputArray.addNew()
                            .set("type", "image_generation_call")
                            .set("id", genId);
                }
            }
        }
     
        // 2) 文本 / 多模态 content；多 Responses message 已按各自 phase 原样回放时不再聚合重复写入。
        List<ToolCall> toolCalls = ToolCallJsonSanitizer.resolveToolCalls(
                assistantMessage.getToolCalls(), assistantMessage.getToolCallsRaw());
        boolean hasToolCalls = Utils.isNotEmpty(toolCalls);
        boolean multiModal = assistantMessage.isMultiModal();

        if (responseMessagesEmitted == false) {
        if (multiModal) {
            // 官方 input item 约束：EasyInputMessage(role=assistant) 的 content 仅接受
            // input_text / input_image / input_file；output_text 只能出现在带 id 的 output message 项里。
            // 这里统一用 input_* 形态回传，避免与 input_image 混排导致 400。
            ONode msgNode = inputArray.addNew().set("role", "assistant");
            applyAssistantPhase(msgNode, assistantMessage);
            ONode contentArray = msgNode.getOrNew("content").asArray();

            // 4.1 起 thinking 与 text 已物理分离，TextBlock 里不再内嵌 think 标签；
            // legacyThinkInline 已在任何惰性 getText() 调用前捕获，避免旧数据特征被回填覆盖。

            for (ContentBlock block : assistantMessage.getBlocks()) {
                if (block instanceof ImageBlock && isImageGenerationBlock(block)) {
                    // 已以 image_generation_call id 回传的跳过 data 再写
                    continue;
                }
                appendResponsesInputContent(contentArray, block, legacyThinkInline, allowInputAudio);
            }

            if (contentArray.getArray().isEmpty()) {
                // 兜底：用去 think 后的文本投影
                String text = assistantMessage.getText();
                contentArray.addNew()
                        .set("type", "input_text")
                        .set("text", text != null ? text : "");
            }
        } else {
            // 纯文本：与 getText() 对齐；有 tool_calls 或已输出 reasoning 项时，仅非空才写（避免多余空 assistant 项）
            String plain = assistantMessage.getText();
            if (hasToolCalls || reasoningEmitted) {
                if (Utils.isNotEmpty(plain)) {
                    ONode assistantNode = inputArray.addNew()
                            .set("role", "assistant")
                            .set("content", plain);
                    applyAssistantPhase(assistantNode, assistantMessage);
                }
            } else {
                ONode assistantNode = inputArray.addNew()
                        .set("role", "assistant")
                        .set("content", plain != null ? plain : "");
                applyAssistantPhase(assistantNode, assistantMessage);
            }
            }
        }

        // 3) 工具调用 items（出站兜底净化：截断/双重编码的 arguments 禁止原样回传）
        if (hasToolCalls) {
            for (ToolCall call : toolCalls) {
                inputArray.addNew()
                        .set("type", "function_call")
                        .set("call_id", call.getId())
                        .set("name", call.getName())
                        .set("arguments", ToolCallJsonSanitizer.sanitizeArguments(call));
            }
        }
    }

    /**
     * 是否为 image_generation_call 产出的图像块（多轮回传靠 id 而非 data）。
     *
     * @since 4.1
     */
    private boolean isImageGenerationBlock(ContentBlock block) {
        Map<String, Object> metas = block.metas();
        if (metas == null) {
            return false;
        }
        if ("image_generation_call".equals(metas.get("source_type"))) {
            return true;
        }
        return metas.containsKey("image_generation_id");
    }

    /**
     * 读取 image_generation_call 的服务端 id。
     *
     * @since 4.1
     */
    private String getImageGenerationId(ContentBlock block) {
        Map<String, Object> metas = block.metas();
        if (metas == null) {
            return null;
        }
        Object genId = metas.get("image_generation_id");
        if (genId == null) {
            genId = metas.get("id");
        }
        return genId == null ? null : String.valueOf(genId);
    }

    private void applyAssistantPhase(ONode assistantNode, AssistantMessage message) {
        if (assistantNode == null || message == null) return;
        Object phase = OpenaiResponsesMessageStateSupport.get(message,
                OpenaiResponsesMessageStateSupport.PHASE);
        if (phase != null) {
            String value = String.valueOf(phase).trim();
            if ("commentary".equals(value) || "final_answer".equals(value)) {
                assistantNode.set("phase", value);
            }
        }
    }

    /**
     * 将调用方通过 optionSet("prompt_cache_breakpoint", ...) 指定的断点挂到最后一个输入内容项。
     * Responses 官方断点的 mode 是协议值 explicit；after_tools 等只表示上层的挂载策略。
     */
    private void applyPromptCacheBreakpoint(ONode inputArray, Object value) {
        if (!isValidPromptCacheBreakpoint(value) || inputArray == null || !inputArray.isArray()) return;
        List<ONode> contents = cacheableInputContents(inputArray);
        if (!contents.isEmpty()) {
            contents.get(contents.size() - 1)
                    .set("prompt_cache_breakpoint", new ONode().set("mode", "explicit"));
        }
    }

    private int applyPromptCacheBreakpoints(ONode inputArray, Object value) {
        if (value == null || inputArray == null || !inputArray.isArray()) return 0;
        ONode requested = toNode(value);
        int count = 0;
        if (requested.isArray()) {
            for (ONode descriptor : requested.getArray()) {
                if (isValidPromptCacheBreakpoint(descriptor)) count++;
            }
        } else if (isValidPromptCacheBreakpoint(requested)) {
            count = 1;
        }
        count = Math.min(4, count);
        List<ONode> contents = cacheableInputContents(inputArray);
        count = Math.min(count, contents.size());
        for (int i = contents.size() - count; i < contents.size(); i++) {
            contents.get(i).set("prompt_cache_breakpoint", new ONode().set("mode", "explicit"));
        }
        return count;
    }

    private boolean isValidPromptCacheBreakpoint(Object value) {
        if (value == null) return false;
        if (value instanceof Boolean) return (Boolean) value;
        ONode node = value instanceof ONode ? (ONode) value : toNode(value);
        if (node.isObject()) return "explicit".equals(node.get("mode").getString());
        if (node.isValue()) {
            String text = node.getString();
            return "explicit".equals(text) || "after_tools".equals(text);
        }
        return false;
    }

    private List<ONode> cacheableInputContents(ONode inputArray) {
        List<ONode> result = new ArrayList<>();
        for (ONode item : inputArray.getArray()) {
            ONode content = item.getOrNull("content");
            if (content == null) continue;
            if (content.isArray()) {
                for (ONode part : content.getArray()) {
                    String type = part.get("type").getString();
                    if ("input_text".equals(type) || "input_image".equals(type) || "input_file".equals(type)) {
                        result.add(part);
                    }
                }
            } else if (content.isValue()) {
                String text = content.getString();
                ONode contentArray = new ONode().asArray();
                ONode part = contentArray.addNew().set("type", "input_text").set("text", text == null ? "" : text);
                item.set("content", contentArray);
                result.add(part);
            }
        }
        return result;
    }

    private void applyPromptCacheOptions(ONode root, Object value) {
        ONode source = toNode(value);
        if (!source.isObject()) return;
        ONode target = new ONode();
        for (Map.Entry<String, ONode> entry : source.getObject().entrySet()) {
            String key = entry.getKey();
            ONode option = entry.getValue();
            if ("mode".equals(key)) {
                String mode = option.getString();
                if ("implicit".equals(mode) || "explicit".equals(mode)) target.set(key, mode);
            } else if ("ttl".equals(key)) {
                if ("30m".equals(option.getString())) target.set(key, "30m");
            } else {
                log.debug("Ignoring unknown OpenAI Responses prompt_cache_options field: {}", key);
            }
        }
        if (!target.getObject().isEmpty()) root.set("prompt_cache_options", target);
    }

    private void applyPromptCacheRetention(ONode root, Object value) {
        if (value == null) return;
        String retention = String.valueOf(value).trim();
        if ("in_memory".equals(retention) || "24h".equals(retention)) {
            root.set("prompt_cache_retention", retention);
        } else if (Utils.isNotEmpty(retention)) {
            log.debug("Ignoring invalid OpenAI Responses prompt_cache_retention: {}", retention);
        }
    }

    /**
     * 将选项值转为 ONode。
     * <p>已是 ONode（如 {@code prepareOutputFormatOptions} 写入的 {@code text}）时直接复用，
     * 避免走 {@code ofBean} 把 ONode 当普通 Bean 二次序列化。</p>
     *
     * @since 4.1
     */
    private ONode toNode(Object value) {
        if (value instanceof ONode) {
            return (ONode) value;
        }
        return ONode.ofBean(value);
    }

    /**
     * tool_choice 归一。
     * <p>Responses API 的函数强制形态是扁平的 {@code {type:"function", name:"x"}}，
     * 而 Chat Completions 是嵌套的 {@code {type:"function", function:{name:"x"}}}；
     * 上层沿用同一份 ChatOptions 时需转写，否则官方端点 400。
     * 字符串形态（none/auto/required）与其它类型（allowed_tools/mcp 等）原样透传。</p>
     *
     * @since 4.1
     */
    @SuppressWarnings("unchecked")
    private void applyToolChoice(ONode root, Object value) {
        if (value == null) {
            return;
        }

        ONode node = toNode(value);
        if (node.isObject() && "function".equals(node.get("type").getString())) {
            ONode funcNode = node.getOrNull("function");
            if (funcNode != null && funcNode.isObject()) {
                String name = funcNode.get("name").getString();
                ONode flat = new ONode().set("type", "function");
                if (Utils.isNotEmpty(name)) {
                    flat.set("name", name);
                }
                root.set("tool_choice", flat);
                return;
            }
        }

        root.set("tool_choice", node);
    }
     
    /**
     * 构建思考级别配置
     * @author oisin lu
     * @date 2026年1月28日
     * OpenAI Responses reasoning 配置格式：
     * {
     *   "reasoning": {
     *     "effort": "low" | "medium" | "high",
     *     "summary": "auto" | "concise" | "detailed"
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
            for (Map.Entry<String, Object> kv : reasoningMap.entrySet()) {
                String key = kv.getKey();
                Object val = kv.getValue();
                if (key == null || val == null) {
                    continue;
                }

                if ("effort".equals(key)) {
                    // 用户显式 reasoning：已知档位归一化，未知值透传
                    String normalized = normalizeResponsesEffort(val, true);
                    if (normalized != null) {
                        reasoningNode.set("effort", normalized);
                    }
                } else if ("summary".equals(key) || "generate_summary".equals(key)) {
                    // 官方 Reasoning.summary 是字符串枚举（auto/concise/detailed），不是数组；
                    // 数组形态只属于输出侧 ReasoningItem.summary，写成数组会被官方端点 400
                    String summary = normalizeReasoningSummary(val);
                    if (Utils.isNotEmpty(summary)) {
                        reasoningNode.set(key, summary);
                    }
                } else if ("context".equals(key) || "mode".equals(key)) {
                    // 当前官方 Reasoning 还包含 context / mode；其他未知字段默认不出站。
                    reasoningNode.set(key, toNode(val));
                } else {
                    log.debug("Ignoring unknown OpenAI Responses reasoning field: {}", key);
                }
            }
        } else if (value instanceof String) {
            // 简化配置：reasoning: "high"
            String normalized = normalizeResponsesEffort(value, true);
            if (normalized != null) {
                reasoningNode.set("effort", normalized);
            }
        }
    }

    /**
     * 归一 reasoning.summary 为官方要求的字符串形态。
     * <p>兼容上层传入集合 / 数组 / {@code "[detailed]"} 等历史写法：取首个非空值。</p>
     *
     * @since 4.1
     */
    private String normalizeReasoningSummary(Object value) {
        if (value == null) {
            return null;
        }

        if (value instanceof Collection) {
            for (Object s : (Collection<?>) value) {
                String v = s == null ? null : String.valueOf(s).trim();
                if (Utils.isNotEmpty(v)) {
                    return knownReasoningSummary(v);
                }
            }
            return null;
        }

        if (value.getClass().isArray()) {
            for (Object s : (Object[]) value) {
                String v = s == null ? null : String.valueOf(s).trim();
                if (Utils.isNotEmpty(v)) {
                    return knownReasoningSummary(v);
                }
            }
            return null;
        }

        String s = String.valueOf(value).trim();
        if (s.startsWith("[") && s.endsWith("]")) {
            for (String part : s.substring(1, s.length() - 1).split(",")) {
                String v = part.trim().replace("\"", "");
                if (Utils.isNotEmpty(v)) {
                    return knownReasoningSummary(v);
                }
            }
            return null;
        }

        return knownReasoningSummary(s);
    }

    private String knownReasoningSummary(String value) {
        if (Utils.isEmpty(value)) return null;
        return "auto".equals(value) || "concise".equals(value) || "detailed".equals(value) ? value : null;
    }

    /**
     * 统一 thinking 开关 + reasoning_effort。
     * <p>显式 {@code reasoning} 优先；统一选项仅对 GPT-5 和已知 o-series 模型自动映射：
     * {@code thinking(false)} → effort=none；{@code reasoning_effort} 映射 effort；
     * {@code thinking(true)} 不强制改 effort，但会请求 {@code summary=auto}，使模型返回可展示的
     * 推理摘要（不会暴露原始推理 token）。未知或非推理模型不自动发送 reasoning，
     * 避免严格端点因不支持该字段而拒绝请求。</p>
     *
     * <p>调用方仍可通过 {@code optionSet("reasoning", ...)} 完全接管请求体。</p>
     *
     * @since 4.0.4
     */
    private void applyUnifiedReasoningOptions(ONode root, ChatOptions options, Object thinkingSwitch, String model) {
        // 已有显式 reasoning 则不覆盖，也不受模型名能力判断限制。
        if (root.hasKey("reasoning")) {
            return;
        }

        if (supportsReasoningOptions(model) == false) {
            return;
        }

        if (Boolean.FALSE.equals(thinkingSwitch)) {
            root.getOrNew("reasoning").set("effort", "none");
            return;
        }

        Object effortObj = options == null ? null : options.options().get("reasoning_effort");
        String effort = null;
        if (effortObj != null) {
            effort = normalizeResponsesEffort(effortObj, false);
            if (effort != null) {
                root.getOrNew("reasoning").set("effort", effort);
            }
        }

        // OpenAI Responses 默认只返回 reasoning item 的 id / encrypted_content，不会返回可展示的摘要。
        // 只有显式 thinking(true) 才表示调用方要求可观察的推理；reasoning_effort 单独设置时
        // 保持原有语义，避免无意向不支持 summary 的兼容网关发送额外字段。
        if (Boolean.TRUE.equals(thinkingSwitch)) {
            root.getOrNew("reasoning").set("summary", "auto");
        }
    }

    /**
     * 判断是否可安全自动发送 OpenAI reasoning 配置。
     * <p>与 Chat Completions 的 developer 角色判断共享同一能力模型族清单（
     * {@link OpenaiDialectSupport#isReasoningCapableModel}），保证两个方言对同一模型名结论一致。</p>
     *
     * @since 4.1
     */
    private boolean supportsReasoningOptions(String model) {
        return OpenaiDialectSupport.isReasoningCapableModel(model);
    }

    /**
     * 无状态场景补 {@code include}。
     * <p>官方规范：{@code encrypted_content} 仅在 {@code include} 显式请求时返回；
     * {@code store=false}（或 ZDR）时又必须靠它才能把 reasoning 项回放给下一轮，
     * 因此此处自动补上（已存在则不重复添加）。</p>
     *
     * @since 4.1
     */
    private void applyReasoningInclude(ONode root) {
        ONode storeNode = root.getOrNull("store");
        if (storeNode == null || storeNode.getBoolean()) {
            // 默认 store=true：服务端保留 reasoning 项，靠 id 即可回放
            return;
        }

        ONode includeNode = root.getOrNull("include");
        if (includeNode == null || includeNode.isArray() == false) {
            includeNode = root.getOrNew("include").asArray();
        } else {
            for (ONode item : includeNode.getArray()) {
                if (INCLUDE_REASONING_ENCRYPTED.equals(item.getString())) {
                    return;
                }
            }
        }

        includeNode.add(INCLUDE_REASONING_ENCRYPTED);
    }

    /**
     * 规范化 Responses API reasoning.effort。
     * <p>保留官方支持档位：none/minimal/low/medium/high/xhigh/max；
     * 官方 ReasoningEffort 枚举原生含 {@code max}，{@code min} 统一为 {@code low}。</p>
     * <ul>
     *   <li>统一 {@code reasoning_effort}：严格映射，null/auto/非法值返回 null（不写出）</li>
     *   <li>用户显式 {@code reasoning}：未知值可透传（兼容厂商扩展）</li>
     * </ul>
     *
     * @param passthroughUnknown 是否对未知值原样透传
     * @since 4.0.4
     */
    private String normalizeResponsesEffort(Object value, boolean passthroughUnknown) {
        if (value == null) {
            return null;
        }
        String effort = String.valueOf(value).trim().toLowerCase();
        if (effort.isEmpty() || "auto".equals(effort)) {
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
            // 官方 ReasoningEffort 枚举原生含 max，不再改写为 xhigh（xhigh 仅部分模型接受，改写反而会 400）
            return "max";
        }
        if ("min".equals(effort)) {
            return "low";
        }
        return passthroughUnknown ? effort : null;
    }

    /**
     * 构建工具
     * @author oisin lu
     * @date 2026年1月28日
     */
    void buildToolsNode(ONode root, ChatOptions options) {
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
                ONode schemaNode = null;
                if (Utils.isNotEmpty(inputSchema)) {
                    try {
                        ONode candidate = ONode.ofJson(inputSchema);
                        if (candidate.isObject()) {
                            schemaNode = candidate;
                        }
                    } catch (Exception ignored) {
                        // 下方统一回退空参数 schema
                    }
                }
                if (schemaNode == null) {
                    schemaNode = newEmptyParameters();
                }

                Boolean strict = func.strict();
                if (Boolean.TRUE.equals(strict)) {
                    ToolSchemaUtil.validateOpenAiStrictSchema(schemaNode, func.name());
                }
                toolNode.set("parameters", schemaNode);
                // openai-java 4.52.0 Responses FunctionTool 将 strict 定义为必填字段。
                toolNode.set("strict", strict == null ? false : strict);

                String outputSchema = func.outputSchema();
                if (Utils.isNotEmpty(outputSchema)) {
                    ONode outputSchemaNode = null;
                    try {
                        ONode candidate = ONode.ofJson(outputSchema);
                        if (candidate.isObject()) {
                            outputSchemaNode = candidate;
                        }
                    } catch (Exception ignored) {
                        // 非法 output schema 不应污染请求；输入 schema 仍可正常使用
                    }
                    if (outputSchemaNode != null) {
                        if (Boolean.TRUE.equals(strict)) {
                            ToolSchemaUtil.validateOpenAiStrictSchema(outputSchemaNode, func.name() + " output");
                        }
                        toolNode.set("output_schema", outputSchemaNode);
                    }
                }
            });
        }
    }

    /**
     * 空参数 schema 补位（{@code properties:{}}，而非空字符串属性名）。
     *
     * @since 4.1
     */
    private ONode newEmptyParameters() {
        ONode schema = new ONode();
        schema.set("type", "object");
        schema.getOrNew("properties").asObject();
        return schema;
    }

    /**
     * 构建助手消息（用于工具调用后的多轮对话）
     * <p>注：该节点不直接出站，而是回传给 {@code parseAssistantMessage} 重建会话消息，
     * 因此沿用 Chat Completions 形态；相比父类额外带上 {@code reasoning_content}，
     * 避免工具调用轮的思考内容丢失。</p>
     * @author oisin lu
     * @date 2026年1月28日
     */
    public ONode buildAssistantToolCallMessageNode(ChatAccumulator acc, Map<String, ToolCallBuilder> toolCallBuilders) {
        ONode oNode = new ONode();
        oNode.set("role", "assistant");
        oNode.set("content", acc.getAggregationText());
        // 思考内容回传（父类 parseAssistantMessage 读 reasoning_content）
        String thinking = acc.getAggregationThinking();
        if (Utils.isNotEmpty(thinking)) {
            oNode.set("reasoning_content", thinking);
        }
        oNode.getOrNew("tool_calls").asArray().then(n1 -> {
            for (Map.Entry<String, ToolCallBuilder> kv : toolCallBuilders.entrySet()) {
                ToolCallBuilder builder = kv.getValue();
                String argsStr = builder.argumentsBuilder.toString();
                n1.addNew().set("id", builder.idBuilder.toString())
                        .set("type", "function")
                        .getOrNew("function").then(n2 -> {
                            n2.set("name", builder.nameBuilder.toString());
                            // 流式聚合出口净化：截断损坏的 arguments 禁止原样入历史（空串也兜底为 "{}"）
                            n2.set("arguments", ToolCallJsonSanitizer.sanitizeArguments(
                                    argsStr, builder.nameBuilder.toString()));
                        });
            }
        });
        ONode toolNode = oNode;
        String phase = acc.getAggregationMetadata().get(OpenaiResponsesMessageStateSupport.AGGREGATION_PHASE) == null
                ? null : String.valueOf(acc.getAggregationMetadata().get(OpenaiResponsesMessageStateSupport.AGGREGATION_PHASE));
        if ("commentary".equals(phase) || "final_answer".equals(phase)) {
            toolNode.set("phase", phase);
        }
        return toolNode;
    }
}
