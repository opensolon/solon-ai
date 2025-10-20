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

import org.noear.snack4.ONode;
import org.noear.solon.Utils;
import org.noear.solon.ai.AiMedia;
import org.noear.solon.ai.AiUsage;
import org.noear.solon.ai.media.Audio;
import org.noear.solon.ai.chat.ChatChoice;
import org.noear.solon.ai.chat.ChatConfig;
import org.noear.solon.ai.chat.ChatException;
import org.noear.solon.ai.chat.ChatResponseDefault;
import org.noear.solon.ai.chat.dialect.AbstractChatDialect;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.message.UserMessage;
import org.noear.solon.ai.chat.tool.ToolCall;
import org.noear.solon.ai.chat.tool.ToolCallBuilder;
import org.noear.solon.ai.media.Image;
import org.noear.solon.ai.media.Video;
import org.noear.solon.core.util.DateUtil;

import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Ollama 聊天模型方言
 *
 * @author noear
 * @since 3.1
 */
public class OllamaChatDialect extends AbstractChatDialect {
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
        return "ollama".equals(config.getProvider());
    }

    @Override
    protected void buildChatMessageNodeDo(ONode oNode, UserMessage msg) {
        oNode.set("role", msg.getRole().name().toLowerCase());
        if (Utils.isEmpty(msg.getMedias())) {
            oNode.set("content", msg.getContent());
        } else {
            oNode.set("content", msg.getContent());

            AiMedia demo = msg.getMedias().get(0);
            if (demo instanceof Image) {
                oNode.set("images", msg.getMedias().stream().map(i -> i.toDataString(false)).collect(Collectors.toList()));
            } else if (demo instanceof Audio) {
                oNode.set("audios", msg.getMedias().stream().map(i -> i.toDataString(false)).collect(Collectors.toList()));
            } else if (demo instanceof Video) {
                oNode.set("videos", msg.getMedias().stream().map(i -> i.toDataString(false)).collect(Collectors.toList()));
            }
        }
    }

    @Override
    public ONode buildAssistantMessageNode(Map<Integer, ToolCallBuilder> toolCallBuilders) {
        ONode oNode = new ONode();
        oNode.set("role", "assistant");
        oNode.set("content", "");
        oNode.getOrNew("tool_calls").asArray().then(n1 -> {
            for (Map.Entry<Integer, ToolCallBuilder> kv : toolCallBuilders.entrySet()) {
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

            if (resp.isFinished()) {
                long promptTokens = oResp.get("prompt_eval_count").getLong();
                long completionTokens = oResp.get("eval_count").getLong();
                long totalTokens = promptTokens + completionTokens;

                resp.setUsage(new AiUsage(promptTokens, completionTokens, totalTokens));

                if(resp.hasChoices() == false) {
                    resp.addChoice(new ChatChoice(0, created, "stop", new AssistantMessage("")));
                }
            }
        }

        return true;
    }

    @Override
    protected ToolCall parseToolCall(ONode n1) {
        int index = -1; //n1.get("index").getInt();它是没有值的
        String callId = n1.get("id").getString();

        ONode n1f = n1.get("function");
        String name = n1f.get("name").getString();
        ONode n1fArgs = n1f.get("arguments");
        String argStr = n1fArgs.getString();

        index = name.hashCode();

        if (n1fArgs.isValue()) {
            //有可能是 json string
            n1fArgs = ONode.ofJson(argStr);
        }

        Map<String, Object> argMap = null;
        if (n1fArgs.isObject()) {
            argMap = n1fArgs.toBean(Map.class);
        }
        return new ToolCall(index, callId, name, argStr, argMap);
    }
}