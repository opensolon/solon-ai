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
package org.noear.solon.ai.llm.dialect.dashscope;

import org.noear.snack4.ONode;
import org.noear.solon.Utils;
import org.noear.solon.ai.chat.content.ContentBlock;
import org.noear.solon.ai.AiUsage;
import org.noear.solon.ai.chat.content.AudioBlock;
import org.noear.solon.ai.chat.*;
import org.noear.solon.ai.chat.dialect.AbstractChatDialect;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.message.UserMessage;
import org.noear.solon.ai.chat.content.ImageBlock;
import org.noear.solon.ai.chat.content.TextBlock;
import org.noear.solon.ai.chat.content.VideoBlock;
import org.noear.solon.net.http.HttpUtils;

import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * DashScope 聊天模型方言（阿里云产品）
 *
 * @author shoukaiseki
 * @author noear
 * @since 3.1
 */

public class DashscopeChatDialect extends AbstractChatDialect {
    //https://help.aliyun.com/zh/model-studio/developer-reference

    private static final String URL_PREFIX = "https://dashscope.aliyuncs.com/api/v1/services/";

    private static DashscopeChatDialect instance = new DashscopeChatDialect();

    public static DashscopeChatDialect getInstance() {
        return instance;
    }
    /**
     * DashScope 流式输出由请求头控制，见官方文档：
     * <a href="https://help.aliyun.com/zh/model-studio/stream">流式输出</a>
     * cURL 需设置 Header 参数 X-DashScope-SSE 为 enable。
     */
    private static final String HEADER_DASHSCOPE_SSE = "X-DashScope-SSE";

    @Override
    public HttpUtils createHttpUtils(ChatConfig config, boolean isStream) {
        HttpUtils httpUtils = super.createHttpUtils(config, isStream);
        if (isStream) {
            httpUtils.header(HEADER_DASHSCOPE_SSE, "enable");
        }
        return httpUtils;
    }
    /**
     * 匹配检测
     *
     * @param config 聊天配置
     */
    @Override
    public boolean matched(ChatConfig config) {
        String standard = config.getStandardOrProvider();

        if ("dashscope".equalsIgnoreCase(standard)) {
            return true;
        } else {
            return config.getApiUrl().startsWith(URL_PREFIX);
        }
    }

    @Override
    public ONode buildRequestJson(ChatConfig config, ChatOptions options, List<ChatMessage> messages, boolean isStream) {
        return new ONode().then(n -> {
            if (Utils.isNotEmpty(config.getModel())) {
                n.set("model", config.getModel());
            }

            n.getOrNew("input").getOrNew("messages").then(n1 -> {
                for (ChatMessage m1 : messages) {
                    if (m1.isThinking() == false) {
                        n1.add(buildChatMessageNode(config, m1));
                    }
                }
            });

            n.set("stream", isStream);

            n.getOrNew("parameters").then(n1 -> {
                Object thinkingSwitch = null;
                boolean hasReasoningEffort = false;
                    
                for (Map.Entry<String, Object> kv : options.options().entrySet()) {
                    String key = kv.getKey();
                    Object value = kv.getValue();
                    // 统一 thinking 开关 → DashScope 原生 parameters.enable_thinking
                    if ("thinking".equals(key) && value instanceof Boolean) {
                        thinkingSwitch = value;
                        n1.set("enable_thinking", value);
                        continue;
                    }
                    // 统一 reasoning_effort 不透传为 parameters 字段；仅作“开启思考”信号
                    if ("reasoning_effort".equals(key)) {
                        if (value != null) {
                            String e = String.valueOf(value).trim().toLowerCase();
                            if (!e.isEmpty() && !"auto".equals(e) && !"none".equals(e)) {
                                hasReasoningEffort = true;
                            }
                        }
                        continue;
                    }
                    n1.set(key, ONode.ofBean(value));
                }
            
                // 对齐 OpenCode：仅设 reasoning_effort 时隐式开启 enable_thinking；
                // thinking(false) 优先；已写出 enable_thinking 不覆盖
                if (hasReasoningEffort
                        && !Boolean.FALSE.equals(thinkingSwitch)
                        && !n1.hasKey("enable_thinking")) {
                    n1.set("enable_thinking", true);
                }
            
                n1.set("result_format", "message");
                //buildReqToolsNodeDo(n1, config.getDefaultTools());
                buildReqToolsNodeDo(n1, options.tools());
            });
        });
    }


    @Override
    public boolean parseResponseJson(ChatConfig config, ChatResponseDefault resp, String json) {
        if ("[DONE]".equals(json)) { //不是数据结构
            if(resp.isFinished() == false) {
                resp.addChoice(new ChatChoice(0, new Date(), resp.getLastFinishReasonNormalized(), new AssistantMessage("")));
                resp.setFinished(true);
            }
            return true;
        }

        //解析
        ONode oResp = ONode.ofJson(json);

        if (oResp.isObject() == false) {
            return false;
        }

        if (oResp.hasKey("code") && !Utils.isEmpty(oResp.get("code").getString())) {
            resp.setError(new ChatException(oResp.get("code").getString() + ": " + oResp.get("message").getString()));
        } else {
            resp.setModel(config.getModel());

            int index = 0;
            Date created = null;
            ONode oOutput = oResp.get("output");
            // 百炼联网搜索：search_info 在 output 层级，需注入到 message 供 AbstractChatDialect 解析
            ONode oSearchInfo = oOutput != null ? oOutput.getOrNull("search_info") : null;
            ONode oSearchResults = (oSearchInfo != null && oSearchInfo.hasKey("search_results"))
                    ? oSearchInfo.get("search_results") : null;
            if (oOutput != null) {
                for (ONode oChoice1 : oOutput.get("choices").getArray()) {
                    String finish_reason = oChoice1.get("finish_reason").getString();
                    ONode oMessage = oChoice1.get("message");
                    if (oSearchResults != null) {
                        oMessage.set("search_results", oSearchResults);
                    }
                    List<AssistantMessage> messageList = parseAssistantMessage(resp, oMessage);

                    for (AssistantMessage msg1 : messageList) {
                        resp.addChoice(new ChatChoice(index, created, finish_reason, msg1));
                    }

                    if (Utils.isNotEmpty(finish_reason)) {
                        resp.setFinished(true);
                        resp.lastFinishReason = finish_reason;
                    }

                    index++;
                }
            }

            if (resp.isFinished()) {
                if (resp.hasChoices() == false) {
                    resp.addChoice(new ChatChoice(0, created, resp.getLastFinishReasonNormalized(), new AssistantMessage("")));
                }
            }

            ONode oUsage = oResp.getOrNull("usage");
            if (oUsage != null) {
                long promptTokens = oUsage.get("input_tokens").getLong();
                long thinkTokens = oUsage.get("think_tokens").getLong();
                long completionTokens = oUsage.get("output_tokens").getLong();
                long totalTokens = oUsage.get("total_tokens").getLong();

                resp.setUsage(new AiUsage(promptTokens, thinkTokens, completionTokens, totalTokens, oUsage));
            }
        }

        return true;
    }

    @Override
    protected void buildUserMessageNodeDo(ChatConfig config, ONode oNode, UserMessage msg) {
        oNode.set("role", msg.getRole().name().toLowerCase());

        // 与 Assistant 对齐：多模态才用原生 content 数组；纯文本保持 string（兼容文本模型）
        if (msg.isMultiModal()) {
            ONode contentNode = new ONode().then(n -> {
                appendDashscopeContentBlocks(n, msg.getBlocks(), msg.getContent());
            });
            // Session 截断后媒体全不可播时，避免写出空 content 数组
            if (contentNode.getArray() != null && contentNode.getArray().isEmpty()) {
                oNode.set("content", msg.getContent() != null ? msg.getContent() : "");
            } else {
                oNode.set("content", contentNode);
            }
        } else {
            oNode.set("content", msg.getContent() != null ? msg.getContent() : "");
        }
    }

    /**
     * Assistant 回传对齐 DashScope 原生 content：
     * 多模态用 [{image|audio|video|text}]；单模态保持 string（兼容文本模型）。
     *
     * @since 3.9
     */
    @Override
    protected void buildAssistantMessageNodeDo(ChatConfig config, ONode oNode, AssistantMessage msg) {
        oNode.set("role", msg.getRole().name().toLowerCase());

        if (msg.isMultiModal()) {
            ONode contentNode = new ONode().then(n -> {
                appendDashscopeContentBlocks(n, msg.getBlocks(), msg.getResultContent());
                // 若块全为空，补文本投影避免空 content
                if (n.getArray() != null && n.getArray().isEmpty()
                        && Utils.isNotEmpty(msg.getResultContent())) {
                    n.addNew().set("text", msg.getResultContent());
                }
            });
            oNode.set("content", contentNode);
        } else {
            // 单模态：保持原 string 行为
            if (Utils.isNotEmpty(msg.getResultContent())) {
                oNode.set("content", msg.getResultContent());
            }
        }

        // 默认不回传 reasoning_content（百炼多轮建议）；
        // 仅当 options 显式需要时由上层注入到消息 metadata。
        // 这里不写 reasoning，与父类 OpenAI 风格区分。

        if (Utils.isNotEmpty(msg.getToolCallsRaw())) {
            oNode.set("tool_calls", ONode.ofBean(msg.getToolCallsRaw()));
        }
    }

    /**
     * 按 DashScope 原生结构追加 content 块（image/audio/video/text）。
     */
    private void appendDashscopeContentBlocks(ONode contentArray, List<ContentBlock> blocks, String textFallback) {
        boolean hasTextBlock = false;

        if (Utils.isNotEmpty(blocks)) {
            for (ContentBlock block1 : blocks) {
                if (block1 instanceof ImageBlock) {
                    if (!isMediaBlockPlayable(block1)) {
                        continue;
                    }
                    String image = block1.toDataString(true);
                    if (Utils.isNotEmpty(image)) {
                        contentArray.addNew().set("image", image);
                    }
                } else if (block1 instanceof AudioBlock) {
                    if (!isMediaBlockPlayable(block1)) {
                        continue;
                    }
                    String audio = block1.toDataString(true);
                    if (Utils.isNotEmpty(audio)) {
                        contentArray.addNew().set("audio", audio);
                    }
                } else if (block1 instanceof VideoBlock) {
                    if (!isMediaBlockPlayable(block1)) {
                        continue;
                    }
                    String video = block1.toDataString(true);
                    if (Utils.isEmpty(video)) {
                        continue;
                    }
                    ONode videoNode = contentArray.addNew();
                    videoNode.set("video", video);
                    // 可选 VL 参数
                    if (block1.metas() != null) {
                        Object fps = block1.metas().get("fps");
                        if (fps != null) {
                            videoNode.set("fps", ONode.ofBean(fps));
                        }
                        Object maxFrames = block1.metas().get("max_frames");
                        if (maxFrames != null) {
                            videoNode.set("max_frames", ONode.ofBean(maxFrames));
                        }
                    }
                } else if (block1 instanceof TextBlock) {
                    // Assistant 回传时剥离 think；User 一般无 think 标签，剥离也无侧作用
                    String text = AssistantMessage.stripThinkTags(block1.getContent());
                    if (Utils.isNotEmpty(text)) {
                        contentArray.addNew().set("text", text);
                        hasTextBlock = true;
                    }
                }
            }
        }

        // 文本投影：若 blocks 中无 TextBlock，补 fallback
        if (!hasTextBlock && Utils.isNotEmpty(textFallback)) {
            contentArray.addNew().set("text", textFallback);
        }
    }
}
