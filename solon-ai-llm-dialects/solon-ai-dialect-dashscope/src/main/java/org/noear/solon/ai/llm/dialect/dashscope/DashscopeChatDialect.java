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
import org.noear.solon.ai.chat.media.ContentBlock;
import org.noear.solon.ai.AiUsage;
import org.noear.solon.ai.chat.media.AudioBlock;
import org.noear.solon.ai.chat.*;
import org.noear.solon.ai.chat.dialect.AbstractChatDialect;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.message.UserMessage;
import org.noear.solon.ai.chat.media.ImageBlock;

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
     * 匹配检测
     *
     * @param config 聊天配置
     */
    @Override
    public boolean matched(ChatConfig config) {
        if ("dashscope".equals(config.getProvider())) {
            return true;
        } else if (config.getApiUrl().startsWith(URL_PREFIX)) {
            return true;
        } else {
            return false;
        }
    }

    @Override
    public String buildRequestJson(ChatConfig config, ChatOptions options, List<ChatMessage> messages, boolean isStream) {
        return new ONode().then(n -> {
            if (Utils.isNotEmpty(config.getModel())) {
                n.set("model", config.getModel());
            }

            n.getOrNew("input").getOrNew("messages").then(n1 -> {
                for (ChatMessage m1 : messages) {
                    if (m1.isThinking() == false) {
                        n1.add(buildChatMessageNode(m1));
                    }
                }
            });

            n.set("stream", isStream);

            n.getOrNew("parameters").then(n1 -> {
                for (Map.Entry<String, Object> kv : options.options().entrySet()) {
                    n1.set(kv.getKey(), kv.getValue());
                }

                n1.set("result_format", "message");
                //buildReqToolsNodeDo(n1, config.getDefaultTools());
                buildReqToolsNodeDo(n1, options.tools());
            });
        }).toJson();
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
            for (ONode oChoice1 : oResp.get("output").get("choices").getArray()) {
                String finish_reason = oChoice1.get("finish_reason").getString();

                List<AssistantMessage> messageList = parseAssistantMessage(resp, oChoice1.get("message"));

                for (AssistantMessage msg1 : messageList) {
                    resp.addChoice(new ChatChoice(index, created, finish_reason, msg1));
                }

                if (Utils.isNotEmpty(finish_reason)) {
                    resp.setFinished(true);
                    resp.lastFinishReason = finish_reason;
                }

                index++;
            }

            if (resp.isFinished()) {
                if (resp.hasChoices() == false) {
                    resp.addChoice(new ChatChoice(0, created, resp.getLastFinishReasonNormalized(), new AssistantMessage("")));
                }
            }

            ONode oUsage = oResp.getOrNull("usage");
            if (oUsage != null) {
                long promptTokens = oUsage.get("input_tokens").getLong();
                long completionTokens = oUsage.get("output_tokens").getLong();
                long totalTokens = oUsage.get("total_tokens").getLong();

                resp.setUsage(new AiUsage(promptTokens, completionTokens, totalTokens, oUsage));
            }
        }

        return true;
    }

    @Override
    protected void buildUserMessageNodeDo(ONode oNode, UserMessage msg) {
        ONode contentNode = new ONode().then(n -> {
            for (ContentBlock block1 : msg.getBlocks()) {
                if (block1 instanceof ImageBlock) {
                    n.add(new ONode().then(n1 -> {
                        n1.set("image", block1.toDataString(true));
                    }));
                }else if (block1 instanceof AudioBlock) {
                    n.add(new ONode().then(n1 -> {
                        n1.set("audio", block1.toDataString(true));
                    }));
                }
            }

            if (Utils.isNotEmpty(msg.getContent())) {
                n.add(new ONode().then(n1 -> {
                    n1.set("text", msg.getContent());
                }));
            }
        });

        oNode.set("role", msg.getRole().name().toLowerCase());
        oNode.set("content", contentNode);
    }
}