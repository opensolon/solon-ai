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
package org.noear.solon.ai.llm.dialect.ollama;

import org.noear.snack4.Feature;
import org.noear.snack4.ONode;
import org.noear.snack4.Options;
import org.noear.snack4.json.JsonReader;
import org.noear.solon.Utils;
import org.noear.solon.ai.chat.*;
import org.noear.solon.ai.chat.content.ContentBlock;
import org.noear.solon.ai.AiUsage;
import org.noear.solon.ai.chat.content.AudioBlock;
import org.noear.solon.ai.chat.ChatChoice;
import org.noear.solon.ai.chat.ChatConfig;
import org.noear.solon.ai.chat.ChatException;
import org.noear.solon.ai.chat.ChatResponseDefault;
import org.noear.solon.ai.chat.dialect.AbstractChatDialect;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.message.UserMessage;
import org.noear.solon.ai.chat.tool.ToolCall;
import org.noear.solon.ai.chat.tool.ToolCallBuilder;
import org.noear.solon.ai.chat.content.ImageBlock;
import org.noear.solon.ai.chat.content.TextBlock;
import org.noear.solon.ai.chat.content.VideoBlock;
import org.noear.solon.core.util.Assert;
import org.noear.solon.core.util.DateUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * Ollama 聊天模型方言
 *
 * @author noear
 * @since 3.1
 */
public class OllamaChatDialect extends AbstractChatDialect {

    private static final Logger LOG = LoggerFactory.getLogger(OllamaChatDialect.class);

    private static OllamaChatDialect instance = new OllamaChatDialect();

    public static OllamaChatDialect getInstance() {
        return instance;
    }

    /**
     * 匹配检测
     *
     * @param config 聊天配置
     */
    @Override
    public boolean matched(ChatConfig config) {
        String standard = config.getStandardOrProvider();

        return "ollama".equalsIgnoreCase(standard) ||
                (Assert.isEmpty(standard) && config.getApiUrl().endsWith("/api/chat"));
    }

    @Override
    protected String getApiUrl(ChatConfig config) {

        //处理后缀#
        int index = config.getApiUrl().indexOf('#');
        if (index > 0) {
            return config.getApiUrl().substring(0, index);
        }

        //自动补全地址
        if (config.getApiUrl().endsWith("/api/chat")) {
            return config.getApiUrl();
        } else {
            if (config.getApiUrl().endsWith("/")) {
                return config.getApiUrl() + "api/chat";
            } else {
                return config.getApiUrl() + "/api/chat";
            }
        }
    }

    @Override
    protected void buildUserMessageNodeDo(ChatConfig config, ONode oNode, UserMessage msg) {
        oNode.set("role", msg.getRole().name().toLowerCase());
        if (msg.isMultiModal() == false) {
            //单模态
            oNode.set("content", msg.getContent());
        } else {
            //多模态：content 为字符串，媒体走侧车数组
            oNode.set("content", msg.getContent());
            appendOllamaMediaArrays(oNode, msg.getBlocks());
        }
    }

    /**
     * Assistant 回传对齐 Ollama：content 字符串 + images/audios/videos，而非 OpenAI content 数组。
     *
     * @since 3.9
     */
    @Override
    protected void buildAssistantMessageNodeDo(ChatConfig config, ONode oNode, AssistantMessage msg) {
        oNode.set("role", msg.getRole().name().toLowerCase());

        if (Utils.isNotEmpty(msg.getResultContent())) {
            oNode.set("content", msg.getResultContent());
        } else {
            oNode.set("content", "");
        }

        if (msg.isMultiModal()) {
            appendOllamaMediaArrays(oNode, msg.getBlocks());
        }

        //兼容 r1 的 tool-call
        if (Utils.isNotEmpty(msg.getReasoningFieldName())) {
            oNode.set(msg.getReasoningFieldName(), msg.getReasoning());
        }

        if (Utils.isNotEmpty(msg.getToolCallsRaw())) {
            oNode.set("tool_calls", ONode.ofBean(msg.getToolCallsRaw()));
        }
    }

    /**
     * 解析 Assistant：补读 Ollama 侧车 images/audios/videos，以及 thinking 字段。
     *
     * @since 3.9
     */
    @Override
    public List<AssistantMessage> parseAssistantMessage(ChatResponseDefault resp, ONode oMessage) {
        // Ollama think 模式字段为 thinking，映射到通用 reasoning 管线
        if (oMessage != null
                && !oMessage.hasKey("reasoning")
                && !oMessage.hasKey("reasoning_content")
                && oMessage.hasKey("thinking")) {
            String thinking = oMessage.get("thinking").getString();
            if (Utils.isNotEmpty(thinking)) {
                oMessage.set("reasoning", thinking);
            }
        }

        List<AssistantMessage> messageList = super.parseAssistantMessage(resp, oMessage);
        List<ContentBlock> mediaBlocks = parseOllamaMediaSidecars(oMessage);
        if (Utils.isEmpty(mediaBlocks)) {
            return messageList;
        }

        resp.addMediaBlocks(mediaBlocks);

        List<AssistantMessage> result = new ArrayList<>(messageList.size());
        boolean mediaMerged = false;
        for (AssistantMessage msg : messageList) {
            // 仅将媒体合并到非 thinking、非纯 tool_calls 消息
            if (!mediaMerged
                    && !msg.isThinking()
                    && Utils.isEmpty(msg.getToolCalls())) {
                List<ContentBlock> blocks = new ArrayList<>();
                if (Utils.isNotEmpty(msg.getContent())) {
                    blocks.add(TextBlock.of(msg.getContent()));
                }
                // 保留已有 blocks （若有）再追加侧车媒体
                if (Utils.isNotEmpty(msg.getBlocks())) {
                    for (ContentBlock b : msg.getBlocks()) {
                        if (!(b instanceof TextBlock)) {
                            blocks.add(b);
                        }
                    }
                }
                blocks.addAll(mediaBlocks);
                result.add(new AssistantMessage(
                        msg.getContent(),
                        msg.isThinking(),
                        msg.getContentRaw(),
                        msg.getToolCallsRaw(),
                        msg.getToolCalls(),
                        msg.getSearchResultsRaw(),
                        blocks).reasoningFieldName(msg.getReasoningFieldName()));
                mediaMerged = true;
            } else {
                result.add(msg);
            }
        }

        // 若只有 thinking/tool 消息，补一条带媒体的空文本消息
        if (!mediaMerged) {
            result.add(new AssistantMessage("", false, null, null, null, null, mediaBlocks));
        }

        return result;
    }

    /**
     * 将 blocks 拆为 Ollama images/audios/videos 侧车数组。
     */
    private void appendOllamaMediaArrays(ONode oNode, List<ContentBlock> blocks) {
        if (Utils.isEmpty(blocks)) {
            return;
        }
        for (ContentBlock block1 : blocks) {
            // Session 截断后空媒体跳过，避免写出 null/空串侧车
            if (!isMediaBlockPlayable(block1)) {
                continue;
            }
            String data = block1.toDataString(false);
            if (Utils.isEmpty(data)) {
                continue;
            }
            if (block1 instanceof ImageBlock) {
                oNode.getOrNew("images").add(data);
            } else if (block1 instanceof AudioBlock) {
                oNode.getOrNew("audios").add(data);
            } else if (block1 instanceof VideoBlock) {
                oNode.getOrNew("videos").add(data);
            }
        }
    }

    /**
     * 解析 Ollama message 侧车媒体数组。
     */
    private List<ContentBlock> parseOllamaMediaSidecars(ONode oMessage) {
        List<ContentBlock> mediaBlocks = new ArrayList<>();
        if (oMessage == null) {
            return mediaBlocks;
        }

        appendSidecarMedia(mediaBlocks, oMessage.getOrNull("images"), "image");
        appendSidecarMedia(mediaBlocks, oMessage.getOrNull("audios"), "audio");
        appendSidecarMedia(mediaBlocks, oMessage.getOrNull("videos"), "video");
        return mediaBlocks;
    }

    private void appendSidecarMedia(List<ContentBlock> mediaBlocks, ONode arrayNode, String mediaType) {
        if (arrayNode == null || !arrayNode.isArray()) {
            return;
        }
        for (ONode item : arrayNode.getArray()) {
            String value = item.getString();
            if (Utils.isEmpty(value)) {
                continue;
            }
            // value 可能是纯 base64、data URL 或 http(s) URL
            ContentBlock block;
            if (value.startsWith("http://") || value.startsWith("https://") || value.startsWith("data:")) {
                block = createMediaBlock(mediaType, value, null, null);
            } else {
                block = createMediaBlock(mediaType, null, value, null);
            }
            if (block != null) {
                mediaBlocks.add(block);
            }
        }
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
                            n2.set("arguments", ONode.ofJson(kv.getValue().argumentsBuilder.toString()));
                        });
            }
        });

        return oNode;
    }

    @Override
    public boolean parseResponseJson(ChatConfig config, ChatResponseDefault resp, String json) {
        //解析
        ONode oResp = ONode.ofJson(json);

        if (oResp.isObject() == false) {
            return false;
        }

        if (oResp.hasKey("error")) {
            resp.setError(new ChatException(oResp.get("error").getString()));
        } else {
            resp.setModel(oResp.get("model").getString());
            resp.setFinished(oResp.get("done").getBoolean());
            String done_reason = oResp.get("done_reason").getString();

            String createdStr = oResp.get("created_at").getString();
            if (createdStr != null) {
                createdStr = createdStr.substring(0, createdStr.indexOf(".") + 4);
            }
            Date created = DateUtil.parseTry(createdStr);
            List<AssistantMessage> messageList = parseAssistantMessage(resp, oResp.get("message"));
            for (AssistantMessage msg1 : messageList) {
                resp.addChoice(new ChatChoice(0, created, done_reason, msg1));
            }

            if (Utils.isNotEmpty(done_reason)) {
                resp.lastFinishReason = done_reason;
            }

            if (resp.isFinished()) {
                long promptTokens = oResp.get("prompt_eval_count").getLong();
                long completionTokens = oResp.get("eval_count").getLong();
                long totalTokens = promptTokens + completionTokens;

                resp.setUsage(new AiUsage(promptTokens, 0L, completionTokens, totalTokens, oResp));

                if (resp.hasChoices() == false) {
                    resp.addChoice(new ChatChoice(0, created, resp.getLastFinishReasonNormalized(), new AssistantMessage("")));
                }
            }
        }

        return true;
    }

    @Override
    protected ToolCall parseToolCall(ChatResponseDefault resp, ONode n1) {
        String callId = n1.get("id").getString();//可能是空的

        ONode n1f = n1.get("function");
        String name = n1f.get("name").getString();
        ONode n1fArgs = n1f.get("arguments");
        String argStr = n1fArgs.getString();

        String index = name;

        if (n1fArgs.isValue()) {
            //有可能是 json string（还可能只是流的中间消息）
            if (hasNestedJsonBlock(argStr)) {
                JsonReader reader = new JsonReader(argStr, Options.of(Feature.Read_AutoRepair));
                n1fArgs = reader.readLast();

                if (n1fArgs == null) {
                    LOG.warn("Parse tool arguments failed: {}", argStr);
                }
            }
        }

        Map<String, Object> argMap = null;
        if (n1fArgs != null) {
            if (n1fArgs.isObject()) {
                argMap = n1fArgs.toBean(Map.class);
            }
        }

        return new ToolCall(index, callId, name, argStr, argMap);
    }

    @Override
    public ONode buildRequestJson(ChatConfig config, ChatOptions options, List<ChatMessage> messages, boolean isStream) {
        return new ONode().then(n -> {
            // 复用父类的通用逻辑
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

            CacheControl cacheControl = options.cacheControl();

            // ⭐ Ollama 模型驻留时间控制（keep_alive 决定 KV Cache 的保留时间）
            //    默认 5 分钟，确保后续请求能复用缓存
            Object keepAlive = options.option("keep_alive");
            if (keepAlive != null) {
                n.set("keep_alive", keepAlive);
            } else {
                n.set("keep_alive", cacheControl.getTtl()); // 5m
            }

            // ⭐ 支持 prompt_cache_key (OpenAI 兼容模式下的 Prompt Caching)
            if (cacheControl != null && Utils.isNotEmpty(cacheControl.getPromptCacheKey())) {
                n.set("prompt_cache_key", cacheControl.getPromptCacheKey());
            }

            for (Map.Entry<String, Object> kv : options.options().entrySet()) {
                n.set(kv.getKey(), ONode.ofBean(kv.getValue()));
            }

            ChatMessage lastMessage = messages.get(messages.size() - 1);
            buildReqToolsNode(n, config, options, lastMessage);
        });
    }
}