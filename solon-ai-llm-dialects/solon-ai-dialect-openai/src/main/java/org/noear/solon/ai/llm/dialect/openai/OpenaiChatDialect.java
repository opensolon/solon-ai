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

import org.noear.snack.ONode;
import org.noear.solon.Utils;
import org.noear.solon.ai.AiUsage;
import org.noear.solon.ai.chat.*;
import org.noear.solon.ai.chat.dialect.AbstractChatDialect;
import org.noear.solon.ai.chat.message.AssistantMessage;

import java.util.Date;
import java.util.List;

/**
 * Openai 聊天模型方言
 *
 * @author noear
 * @since 3.1
 */
public class OpenaiChatDialect extends AbstractChatDialect {
    private static final OpenaiChatDialect instance = new OpenaiChatDialect();

    public static OpenaiChatDialect getInstance() {
        return instance;
    }

    /**
     * 是否为默认
     */
    @Override
    public boolean isDefault() {
        return true;
    }

    /**
     * 匹配检测
     *
     * @param config 聊天配置
     */
    @Override
    public boolean matched(ChatConfig config) {
        return false;
    }

    @Override
    public boolean parseResponseJson(ChatConfig config, ChatResponseDefault resp, String json) {
        if ("[DONE]".equals(json)) { //不是数据结构
            if(resp.isFinished() == false) {
                resp.addChoice(new ChatChoice(0, new Date(), "stop", new AssistantMessage("")));
                resp.setFinished(true);
            }
            return true;
        }

        //解析
        ONode oResp = ONode.load(json);

        if (oResp.isObject() == false) {
            return false;
        }

        if ("error".equals(oResp.get("object").getString())) {
            resp.setError(new ChatException(oResp.get("message").getString()));
        } else if (oResp.contains("error")) {
            resp.setError(new ChatException(oResp.get("error").getString()));
        } else {
            resp.setModel(oResp.get("model").getString());

            Date created = new Date(oResp.get("created").getLong() * 1000);

            for (ONode oChoice1 : oResp.get("choices").ary()) {
                int index = oChoice1.get("index").getInt();
                String finish_reason = oChoice1.get("finish_reason").getString();

                List<AssistantMessage> messageList;
                if (resp.isStream()) {   //object=chat.completion.chunk
                    messageList = parseAssistantMessage(resp, oChoice1.get("delta"));
                } else {
                    //object=chat.completion
                    messageList = parseAssistantMessage(resp, oChoice1.get("message"));
                }

                for (AssistantMessage msg1 : messageList) {
                    resp.addChoice(new ChatChoice(index, created, finish_reason, msg1));
                }

                if (Utils.isNotEmpty(finish_reason)) {
                    resp.setFinished(true);
                }
            }

            if (resp.isFinished()) {
                if (resp.hasChoices() == false) {
                    resp.addChoice(new ChatChoice(0, created, "stop", new AssistantMessage("")));
                }
            }

            ONode oUsage = oResp.getOrNull("usage");
            if (oUsage != null) {
                long promptTokens = oUsage.get("prompt_tokens").getLong();
                long completionTokens = oUsage.get("completion_tokens").getLong();
                long totalTokens = oUsage.get("total_tokens").getLong();

                resp.setUsage(new AiUsage(promptTokens, completionTokens, totalTokens));
            }
        }

        return true;
    }
}