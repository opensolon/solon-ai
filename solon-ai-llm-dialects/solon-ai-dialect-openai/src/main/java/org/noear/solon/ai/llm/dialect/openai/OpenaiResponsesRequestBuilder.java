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
import org.noear.solon.ai.chat.ChatResponseDefault;
import org.noear.solon.ai.chat.content.ContentBlock;
import org.noear.solon.ai.chat.ChatConfig;
import org.noear.solon.ai.chat.ChatOptions;
import org.noear.solon.ai.chat.content.TextBlock;
import org.noear.solon.ai.chat.message.*;
import org.noear.solon.ai.chat.tool.FunctionTool;
import org.noear.solon.ai.chat.tool.ToolCall;
import org.noear.solon.ai.chat.tool.ToolCallBuilder;
import org.noear.solon.ai.chat.tool.ToolCallJsonSanitizer;
import org.noear.solon.ai.chat.content.AudioBlock;
import org.noear.solon.ai.chat.content.BlobBlock;
import org.noear.solon.ai.chat.content.ImageBlock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.Collection;
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
            "stop", "stream_options", "frequency_penalty", "presence_penalty",
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
        for (ChatMessage msg : messages) {
            if (msg instanceof SystemMessage) {
                continue;
            }
            buildInputItem(inputArray, msg);
        }
        root.set("stream", isStream);
        // 添加其他选项
        Object thinkingSwitch = null;
        String optionInstructions = null;
        for (Map.Entry<String, Object> kv : options.options().entrySet()) {
            String key = kv.getKey();
            // 跳过已处理的字段（response_format 不适用于 Responses API，使用 text.format 替代）
            if ("stream".equals(key) || "response_format".equals(key)) {
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

        // ⭐ prompt_cache_key（官方 Responses API 独立的缓存路由提示字段）
        //    通过 ChatOptions.promptCacheKey() 传入，仅用于提升 KV cache 命中，不改变会话语义
        //    注意：与 previous_response_id（服务端会话续接）是两个不同字段；
        //    后者可经 options 直接透传（如 options.options().put("previous_response_id", ...)）
        CacheControl cacheControl = options.cacheControl();
        if (cacheControl != null && Utils.isNotEmpty(cacheControl.getPromptCacheKey())) {
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
    private void buildInputItem(ONode inputArray, ChatMessage message) {
        if (message instanceof ToolMessage) {
            buildToolMessageInputItem(inputArray, (ToolMessage) message);
            return;
        }

        if (message instanceof AssistantMessage) {
            AssistantMessage assistantMessage = (AssistantMessage) message;

            // 1) reasoning 项先行（官方要求 reasoning 在其后续项之前），与正文 / function_call 并列而非二选一：
            //    4.1 后非流式解析产出的是 text/thinking 合并的单条消息（isThinking=false），
            //    不能再用 isThinking() 做消息分类，否则 thinking 与 reasoning 元数据会整体丢弃
            boolean reasoningEmitted = appendReasoningInputItem(inputArray, assistantMessage);

            // 2) 纯思考分片（流式 thinking 消息）：无正文 / 无工具调用时不再补空 assistant 项
            if (assistantMessage.isThinking()
                    && Utils.isEmpty(assistantMessage.getTextRaw())
                    && assistantMessage.isToolCalls() == false
                    && assistantMessage.isMultiModal() == false) {
                return;
            }

            buildAssistantInputItems(inputArray, assistantMessage, reasoningEmitted);
            return;
        }

        if (message.isThinking()) {
            // 非 AssistantMessage 的思考消息（历史兼容）：退化为 reasoning_text
            String thinkText = message.getContent();
            if (Utils.isNotEmpty(thinkText)) {
                ONode reasoningItem = newReasoningItem(inputArray);
                reasoningItem.getOrNew("content").asArray()
                        .addNew().set("type", "reasoning_text").set("text", thinkText);
            }
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
                    appendResponsesInputContent(contentArray, block1, false);
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
    private boolean appendReasoningInputItem(ONode inputArray, AssistantMessage assistantMessage) {
        if (assistantMessage.hasMetadata()) {
            Map<String, Object> metas = assistantMessage.getMetadata();
            Object reasoningId = metas.get("reasoning_item_id");
            Object encryptedContent = metas.get("reasoning_encrypted_content");
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
    private void appendResponsesInputContent(ONode contentArray, ContentBlock block, boolean stripThink) {
        if (block == null) {
            return;
        }

        if (block instanceof TextBlock) {
            String text = block.getContent();
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
    private void buildAssistantInputItems(ONode inputArray, AssistantMessage assistantMessage, boolean reasoningEmitted) {
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
     
        // 2) 文本 / 多模态 content
        boolean hasToolCalls = Utils.isNotEmpty(assistantMessage.getToolCalls());
        boolean multiModal = assistantMessage.isMultiModal();

        if (multiModal) {
            // 官方 input item 约束：EasyInputMessage(role=assistant) 的 content 仅接受
            // input_text / input_image / input_file；output_text 只能出现在带 id 的 output message 项里。
            // 这里统一用 input_* 形态回传，避免与 input_image 混排导致 400。
            ONode msgNode = inputArray.addNew().set("role", "assistant");
            ONode contentArray = msgNode.getOrNew("content").asArray();

            // 4.1 起 thinking 与 text 已物理分离，TextBlock 里不再内嵌 think 标签；
            // 仅旧数据（反序列化自 content、textRaw 为 null）才需剔除，
            // 否则正文恰以 think 标签开头的合法文本会被整段清空
            boolean legacyThinkInline = assistantMessage.getTextRaw() == null;

            for (ContentBlock block : assistantMessage.getBlocks()) {
                if (block instanceof ImageBlock && isImageGenerationBlock(block)) {
                    // 已以 image_generation_call id 回传的跳过 data 再写
                    continue;
                }
                appendResponsesInputContent(contentArray, block, legacyThinkInline);
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
                    inputArray.addNew()
                            .set("role", "assistant")
                            .set("content", plain);
                }
            } else {
                inputArray.addNew()
                        .set("role", "assistant")
                        .set("content", plain != null ? plain : "");
            }
        }
     
        // 3) 工具调用 items（出站兜底净化：截断/双重编码的 arguments 禁止原样回传）
        if (hasToolCalls) {
            for (ToolCall call : assistantMessage.getToolCalls()) {
                inputArray.addNew()
                        .set("type", "function_call")
                        .set("call_id", call.getId())
                        .set("name", call.getName())
                        .set("arguments", ToolCallJsonSanitizer.sanitizeArguments(
                                call.getArgumentsStr(), call.getName()));
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
                } else {
                    // mode / context 等官方字段原样透传（含厂商扩展）
                    reasoningNode.set(key, toNode(val));
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
                    return v;
                }
            }
            return null;
        }

        if (value.getClass().isArray()) {
            for (Object s : (Object[]) value) {
                String v = s == null ? null : String.valueOf(s).trim();
                if (Utils.isNotEmpty(v)) {
                    return v;
                }
            }
            return null;
        }

        String s = String.valueOf(value).trim();
        if (s.startsWith("[") && s.endsWith("]")) {
            for (String part : s.substring(1, s.length() - 1).split(",")) {
                String v = part.trim().replace("\"", "");
                if (Utils.isNotEmpty(v)) {
                    return v;
                }
            }
            return null;
        }

        return Utils.isEmpty(s) ? null : s;
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
     * <p>官方 SDK 将该配置限定于 GPT-5 和 o-series；未知模型保持保守，
     * 需要时可通过显式 reasoning 绕过自动判断。</p>
     *
     * @since 4.1
     */
    private boolean supportsReasoningOptions(String model) {
        if (Utils.isEmpty(model)) {
            return false;
        }

        String modelName = model.trim().toLowerCase();
        return isModelFamily(modelName, "gpt-5")
                || isModelFamily(modelName, "o1")
                || isModelFamily(modelName, "o3")
                || isModelFamily(modelName, "o4");
    }

    private boolean isModelFamily(String model, String family) {
        if (matchesModelFamily(model, family)) {
            return true;
        }

        // 一些兼容网关把 gpt-5 写成 gpt5；不改写出站 model，只放宽能力判断。
        return "gpt-5".equals(family) && matchesModelFamily(model, "gpt5");
    }

    private boolean matchesModelFamily(String model, String family) {
        int fromIndex = 0;
        while (fromIndex < model.length()) {
            int start = model.indexOf(family, fromIndex);
            if (start < 0) {
                return false;
            }

            int end = start + family.length();
            boolean validPrefix = start == 0 || isModelTokenBoundary(model.charAt(start - 1));
            boolean validSuffix = end == model.length()
                    || model.charAt(end) == '-'
                    || model.charAt(end) == '.';
            if (validPrefix && validSuffix) {
                return true;
            }
            fromIndex = start + 1;
        }
        return false;
    }

    private boolean isModelTokenBoundary(char ch) {
        return ch == '/' || ch == '.' || ch == ':' || ch == '_' || ch == '-';
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
                        newEmptyParameters(toolNode);
                    }
                } else {
                    newEmptyParameters(toolNode);
                }
            });
        }
    }

    /**
     * 空参数 schema 补位（{@code properties:{}}，而非空字符串属性名）。
     *
     * @since 4.1
     */
    private void newEmptyParameters(ONode toolNode) {
        toolNode.getOrNew("parameters")
                .set("type", "object")
                .getOrNew("properties").asObject();
    }

    /**
     * 构建助手消息（用于工具调用后的多轮对话）
     * <p>注：该节点不直接出站，而是回嗂给 {@code parseAssistantMessage} 重建会话消息，
     * 因此沿用 Chat Completions 形态；相比父类额外带上 {@code reasoning_content}，
     * 避免工具调用轮的思考内容丢失。</p>
     * @author oisin lu
     * @date 2026年1月28日
     */
    public ONode buildAssistantToolCallMessageNode(ChatResponseDefault resp, Map<String, ToolCallBuilder> toolCallBuilders) {
        ONode oNode = new ONode();
        oNode.set("role", "assistant");
        oNode.set("content", resp.getAggregationText());
        // 思考内容回嗂（父类 parseAssistantMessage 读 reasoning_content）
        String thinking = resp.getAggregationThinking();
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
        return oNode;
    }
}
