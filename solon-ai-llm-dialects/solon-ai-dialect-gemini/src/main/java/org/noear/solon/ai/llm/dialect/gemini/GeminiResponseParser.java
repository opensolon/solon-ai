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
package org.noear.solon.ai.llm.dialect.gemini;

import org.noear.snack4.ONode;
import org.noear.solon.Utils;
import org.noear.solon.ai.AiUsage;
import org.noear.solon.ai.chat.ChatChoice;
import org.noear.solon.ai.chat.ChatException;
import org.noear.solon.ai.chat.ChatResponseDefault;
import org.noear.solon.ai.chat.message.AssistantMessage;

import java.util.Date;
import java.util.List;

/**
 * Gemini 响应解析器
 * <p>
 * 负责解析 Gemini API 返回的流式和非流式响应，
 * 将 JSON 格式的响应转换为内部的消息模型。
 *
 * @author cwdhf
 * @since 3.1
 */
public class GeminiResponseParser {

    private final GeminiThoughtProcessor thoughtProcessor;
    private final boolean logEnabled;
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(GeminiResponseParser.class);

    public GeminiResponseParser() {
        this.thoughtProcessor = new GeminiThoughtProcessor();
        this.logEnabled = log.isDebugEnabled();
    }

    /**
     * 解析响应 JSON
     *
     * @param resp  聊天响应对象
     * @param json  响应 JSON 字符串
     * @return 是否有有效的选择
     */
    public boolean parseResponse(ChatResponseDefault resp, String json) {
        if (resp.isStream()) {
            return parseStreamResponse(resp, json);
        } else {
            return parseNonStreamResponse(resp, json);
        }
    }

    /**
     * 解析流式响应
     *
     * @param resp 聊天响应对象
     * @param json 响应 JSON 字符串
     * @return 是否有有效的选择
     */
    public boolean parseStreamResponse(ChatResponseDefault resp, String json) {
        if (json == null || json.isEmpty()) {
            return false;
        }

        if (logEnabled) {
            log.debug("Gemini stream raw response: {}", json);
        }

        String[] lines = json.split("\n");
        boolean hasChoices = false;

        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty()) {
                continue;
            }

            String jsonData = line;

            if (line.startsWith("data:")) {
                jsonData = line.substring(5).trim();
            }

            if (jsonData.isEmpty()) {
                continue;
            }

            if ("[DONE]".equals(jsonData)) {
                if (resp.isFinished() == false) {
                    resp.addChoice(new ChatChoice(0, new Date(), resp.getLastFinishReasonNormalized(), new AssistantMessage("")));
                    resp.setFinished(true);
                }
                return true;
            }

            ONode oResp = ONode.ofJson(jsonData);

            if (oResp.isObject() == false) {
                continue;
            }

            if (oResp.hasKey("error")) {
                ONode oError = oResp.get("error");
                String errorMsg = oError.get("message").getString();
                if (Utils.isEmpty(errorMsg)) {
                    errorMsg = oError.getString();
                }
                resp.setError(new ChatException(errorMsg));
                return true;
            }

            if (oResp.hasKey("model")) {
                resp.setModel(oResp.get("model").getString());
            } else if (oResp.hasKey("modelVersion")) {
                resp.setModel(oResp.get("modelVersion").getString());
            }

            Date created = new Date();
            if (oResp.hasKey("createTime")) {
                String createTime = oResp.get("createTime").getString();
                if (createTime != null && createTime.length() >= 19) {
                    try {
                        created = java.sql.Timestamp.valueOf(createTime.replace("T", " ").substring(0, 19));
                    } catch (Exception e) {
                    }
                }
            }

            ONode oCandidates = oResp.getOrNull("candidates");
            if (oCandidates != null && oCandidates.isArray()) {
                for (ONode oChoice1 : oCandidates.getArray()) {
                    int index = oChoice1.get("index").getInt();
                    String finishReason = oChoice1.get("finishReason").getString();

                    if (Utils.isNotEmpty(finishReason)) {
                        resp.setFinished(true);
                        resp.lastFinishReason = finishReason;
                    }

                    ONode oContent = oChoice1.get("content");
                    List<AssistantMessage> messageList = thoughtProcessor.parse(resp, oContent);

                    for (AssistantMessage msg1 : messageList) {
                        resp.addChoice(new ChatChoice(index, created, finishReason, msg1));
                        hasChoices = true;
                    }
                }
            }

            ONode oUsage = oResp.getOrNull("usageMetadata");
            if (oUsage != null && resp.isFinished()) {
                long promptTokens = oUsage.getOrNull("promptTokenCount") != null ? oUsage.get("promptTokenCount").getLong() : 0;
                long completionTokens = oUsage.getOrNull("candidatesTokenCount") != null ? oUsage.get("candidatesTokenCount").getLong() : 0;
                long totalTokens = oUsage.getOrNull("totalTokenCount") != null ? oUsage.get("totalTokenCount").getLong() : 0;

                resp.setUsage(new AiUsage(promptTokens, completionTokens, totalTokens, oUsage));
            }
        }

        return hasChoices;
    }

    /**
     * 解析非流式响应
     *
     * @param resp 聊天响应对象
     * @param json 响应 JSON 字符串
     * @return 解析是否成功
     */
    public boolean parseNonStreamResponse(ChatResponseDefault resp, String json) {
        if ("[DONE]".equals(json)) {
            if (resp.isFinished() == false) {
                resp.addChoice(new ChatChoice(0, new Date(), resp.getLastFinishReasonNormalized(), new AssistantMessage("")));
                resp.setFinished(true);
            }
            return true;
        }

        ONode oResp = ONode.ofJson(json);

        if (oResp.isObject() == false) {
            return false;
        }

        if (oResp.hasKey("error")) {
            ONode oError = oResp.get("error");
            String errorMsg = oError.get("message").getString();
            if (Utils.isEmpty(errorMsg)) {
                errorMsg = oError.getString();
            }
            resp.setError(new ChatException(errorMsg));
            return true;
        }

        if (oResp.hasKey("model")) {
            resp.setModel(oResp.get("model").getString());
        } else if (oResp.hasKey("modelVersion")) {
            resp.setModel(oResp.get("modelVersion").getString());
        }

        Date created = new Date();
        if (oResp.hasKey("created")) {
            created = new Date(oResp.get("created").getLong() * 1000);
        }

        ONode oCandidates = oResp.getOrNull("candidates");
        if (oCandidates != null && oCandidates.isArray()) {

            for (ONode oChoice1 : oCandidates.getArray()) {
                int index = oChoice1.get("index").getInt();
                String finishReason = oChoice1.get("finishReason").getString();

                if (Utils.isEmpty(finishReason)) {
                    finishReason = oChoice1.get("finish_reason").getString();
                }

                ONode oContent = oChoice1.get("content");
                List<AssistantMessage> messageList = thoughtProcessor.parse(resp, oContent);

                for (AssistantMessage msg1 : messageList) {
                    resp.addChoice(new ChatChoice(index, created, finishReason, msg1));
                }

                if (Utils.isNotEmpty(finishReason)) {
                    resp.setFinished(true);
                    resp.lastFinishReason = finishReason;
                }
            }
        }

        if (resp.isFinished()) {
            if (resp.hasChoices() == false) {
                resp.addChoice(new ChatChoice(0, created, resp.getLastFinishReasonNormalized(), new AssistantMessage("")));
            }
        }

        ONode oUsage = oResp.getOrNull("usageMetadata");
        if (oUsage != null) {
            long promptTokens = oUsage.get("promptTokenCount").getLong();
            long completionTokens = oUsage.get("candidatesTokenCount").getLong();
            long totalTokens = oUsage.get("totalTokenCount").getLong();

            resp.setUsage(new AiUsage(promptTokens, completionTokens, totalTokens, oUsage));
        }

        return true;
    }
}
