package org.noear.solon.ai.chat.dialect;

import org.noear.snack.ONode;
import org.noear.solon.Utils;
import org.noear.solon.ai.AiMedia;
import org.noear.solon.ai.AiUsage;
import org.noear.solon.ai.audio.Audio;
import org.noear.solon.ai.chat.*;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.message.UserMessage;
import org.noear.solon.ai.image.Image;

import java.util.Arrays;
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
        return "dashscope".equals(config.getProvider());

    }

    @Override
    public String buildRequestJson(ChatConfig config, ChatOptions options, List<ChatMessage> messages, boolean isStream) {
        return new ONode().build(n -> {
            n.set("stream", isStream);

            if (Utils.isNotEmpty(config.getModel())) {
                n.set("model", config.getModel());
            }

            n.getOrNew("input").getOrNew("messages").build(n1 -> {
                for (ChatMessage m1 : messages) {
                    if (m1.isThinking() == false) {
                        n1.add(buildChatMessageNode(m1));
                    }
                }
            });

            n.getOrNew("parameters").build(n1 -> {
                for (Map.Entry<String, Object> kv : options.options().entrySet()) {
                    n1.set(kv.getKey(), kv.getValue());
                }

                n1.set("result_format", "message");
                buildReqFunctionsNodeDo(n1, config.getGlobalFunctions());
                buildReqFunctionsNodeDo(n1, options.functions());
            });
        }).toJson();
    }


    @Override
    public boolean parseResponseJson(ChatConfig config, ChatResponseDefault resp, String json) {
        if (json.startsWith("data:")) {
            json = json.substring(6);

            if ("[DONE]".equals(json)) { //不是数据结构
                resp.setFinished(true);
                return true;
            }
        }

        //解析
        ONode oResp = ONode.load(json);

        if (oResp.isObject() == false) {
            return false;
        }

        if (oResp.contains("code") && !Utils.isEmpty(oResp.get("code").getString())) {
            resp.setError(new ChatException(oResp.get("code").getString() + ": " + oResp.get("message").getString()));
        } else {
            resp.setModel(config.getModel());

            int index = 0;
            Date created = null;
            for (ONode oChoice1 : oResp.get("output").get("choices").ary()) {
                String finish_reason = oChoice1.get("finish_reason").getString();

                if ("stop".equals(finish_reason)) {
                    resp.setFinished(true);
                }

                List<AssistantMessage> messageList;
                if (oChoice1.contains("delta")) {  //object=chat.completion.chunk
                    messageList = parseAssistantMessage(resp, oChoice1.get("delta"));
                } else { //object=chat.completion
                    messageList = parseAssistantMessage(resp, oChoice1.get("message"));
                }

                for (AssistantMessage msg1 : messageList) {
                    resp.addChoice(new ChatChoice(index, created, finish_reason, msg1));
                }

                index++;
            }

            ONode oUsage = oResp.getOrNull("usage");
            if (oUsage != null) {
                long promptTokens = oUsage.get("input_tokens").getLong();
                long completionTokens = oUsage.get("output_tokens").getLong();
                long totalTokens = oUsage.get("total_tokens").getLong();

                resp.setUsage(new AiUsage(promptTokens, completionTokens, totalTokens));
            }
        }

        return true;
    }

    @Override
    protected List<AssistantMessage> parseAssistantMessage(ChatResponseDefault resp, ONode oMessage) {
        String content = oMessage.get("content").getString();
        if (oMessage.get("content").isArray()) {
            ONode contentArray = oMessage.get("content");
            if (contentArray.ary().size() > 0) {
                content = contentArray.get(0).get("text").getString();
            }
        }
        oMessage.set("content", content);
        return super.parseAssistantMessage(resp, oMessage);
    }

    @Override
    protected void buildChatMessageNodeDo(ONode oNode, UserMessage msg) {
        List<AiMedia> medias = msg.getMedias();
        String content = msg.getContent();

        if (medias == null) {
            medias = Arrays.asList();
        }
        final List<AiMedia> finalMedias = medias;
        ONode contentNode = new ONode().build(n -> {
            for (AiMedia media : finalMedias) {
                if (media instanceof Image) {
                    n.add(new ONode().build(n1 -> {
                        n1.set("image", media.toDataString(true));
                    }));
                }else if (media instanceof Audio) {
                    n.add(new ONode().build(n1 -> {
                        n1.set("image", media.toDataString(true));
                    }));
                }
            }

            if (Utils.isNotEmpty(content)) {
                n.add(new ONode().build(n1 -> {
                    n1.set("text", content);
                }));
            }
        });

        oNode.set("role", msg.getRole().name().toLowerCase());
        oNode.set("content", contentNode);
    }
}