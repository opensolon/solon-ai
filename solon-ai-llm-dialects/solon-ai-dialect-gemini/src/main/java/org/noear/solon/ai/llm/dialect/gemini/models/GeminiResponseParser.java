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
package org.noear.solon.ai.llm.dialect.gemini.models;

import org.noear.snack4.ONode;
import org.noear.solon.Utils;
import org.noear.solon.ai.AiUsage;
import org.noear.solon.ai.chat.ChatException;
import org.noear.solon.ai.chat.ChatAccumulator;
import org.noear.solon.ai.chat.message.AssistantMessage;

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

    public GeminiResponseParser() {
        this.thoughtProcessor = new GeminiThoughtProcessor();
    }

    /**
     * 解析响应 JSON
     *
     * @param acc  聊天响应对象
     * @param json  响应 JSON 字符串
     * @return 是否有有效的选择
     */
    public boolean parseResponse(ChatAccumulator acc, String json) {
        if (acc.isStream()) {
            return parseStreamResponse(acc, json);
        } else {
            return parseNonStreamResponse(acc, json);
        }
    }

    /**
     * 解析流式响应
     *
     * @param acc 聊天响应对象
     * @param json 响应 JSON 字符串
     * @return 是否有有效的选择
     */
    public boolean parseStreamResponse(ChatAccumulator acc, String json) {
        if (json == null || json.isEmpty()) {
            return false;
        }

        String[] lines = json.split("\n");
        boolean hasContent = false;

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
                if (acc.isFinished() == false) {
                    acc.addContentItem(new AssistantMessage(""));
                    acc.setFinished(true);
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
                acc.setError(new ChatException(errorMsg));
                return true;
            }

            if (oResp.hasKey("model")) {
                acc.setModel(oResp.get("model").getString());
            } else if (oResp.hasKey("modelVersion")) {
                acc.setModel(oResp.get("modelVersion").getString());
            }

            ONode oCandidates = oResp.getOrNull("candidates");
            if (oCandidates != null && oCandidates.isArray()) {
                for (ONode oChoice1 : oCandidates.getArray()) {
                    String finishReason = oChoice1.get("finishReason").getString();

                    if (Utils.isNotEmpty(finishReason)) {
                        acc.setFinished(true);
                        acc.lastFinishReason = finishReason;
                    }

                    ONode oContent = oChoice1.get("content");
                    List<AssistantMessage> messageList = thoughtProcessor.parse(acc, oContent);

                    for (AssistantMessage msg1 : messageList) {
                        acc.addContentItem(msg1);
                        hasContent = true;
                    }

                    // 若 finishReason 存在但 messageList 为空（如最后一帧仅含 thoughtSignature 而无文本），
                    // 仍需补充一个空消息，以确保 finished 状态能通过内容项正常传递给订阅者
                    if (Utils.isNotEmpty(finishReason) && messageList.isEmpty()) {
                        acc.addContentItem(new AssistantMessage(""));
                        hasContent = true;
                    }
                }
            }

            // prompt 被安全策略拦截时无 candidates 返回，需显式报错避免静默结束
            if (hasContent == false) {
                ONode oPromptFeedback = oResp.getOrNull("promptFeedback");
                if (oPromptFeedback != null) {
                    String blockReason = oPromptFeedback.get("blockReason").getString();
                    if (Utils.isNotEmpty(blockReason)) {
                        acc.setError(new ChatException("prompt blocked: " + blockReason));
                        return true;
                    }
                }
            }

            ONode oUsage = oResp.getOrNull("usageMetadata");
            if (oUsage != null && acc.isFinished()) {
                long promptTokens = oUsage.getOrNull("promptTokenCount") != null ? oUsage.get("promptTokenCount").getLong() : 0;
                long completionTokens = oUsage.getOrNull("candidatesTokenCount") != null ? oUsage.get("candidatesTokenCount").getLong() : 0;
                long totalTokens = oUsage.getOrNull("totalTokenCount") != null ? oUsage.get("totalTokenCount").getLong() : 0;

                long cachedContentTokens = oUsage.getOrNull("cachedContentTokenCount") != null ? oUsage.get("cachedContentTokenCount").getLong() : 0L;
                long thinkingTokens = oUsage.getOrNull("thoughtsTokenCount") != null ? oUsage.get("thoughtsTokenCount").getLong() : 0L;

                acc.setUsage(new AiUsage(promptTokens, thinkingTokens, completionTokens, totalTokens,
                        0L, cachedContentTokens, oUsage));
            }
        }

        return hasContent;
    }

    /**
     * 解析非流式响应
     *
     * @param acc 聊天响应对象
     * @param json 响应 JSON 字符串
     * @return 解析是否成功
     */
    public boolean parseNonStreamResponse(ChatAccumulator acc, String json) {
        if ("[DONE]".equals(json)) {
            if (acc.isFinished() == false) {
                acc.addContentItem(new AssistantMessage(""));
                acc.setFinished(true);
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
            acc.setError(new ChatException(errorMsg));
            return true;
        }

        if (oResp.hasKey("model")) {
            acc.setModel(oResp.get("model").getString());
        } else if (oResp.hasKey("modelVersion")) {
            acc.setModel(oResp.get("modelVersion").getString());
        }

        ONode oCandidates = oResp.getOrNull("candidates");
        if (oCandidates != null && oCandidates.isArray()) {

            for (ONode oChoice1 : oCandidates.getArray()) {
                String finishReason = oChoice1.get("finishReason").getString();

                if (Utils.isEmpty(finishReason)) {
                    finishReason = oChoice1.get("finish_reason").getString();
                }

                ONode oContent = oChoice1.get("content");
                List<AssistantMessage> messageList = thoughtProcessor.parse(acc, oContent);

                for (AssistantMessage msg1 : messageList) {
                    acc.addContentItem(msg1);
                }

                if (Utils.isNotEmpty(finishReason)) {
                    acc.setFinished(true);
                    acc.lastFinishReason = finishReason;
                }
            }
        }

        if (acc.isFinished()) {
            if (acc.hasContentItems() == false) {
                acc.addContentItem(new AssistantMessage(""));
            }
        }

        // prompt 被安全策略拦截时无 candidates 返回，需显式报错避免静默返回空响应
        if (acc.hasContentItems() == false) {
            ONode oPromptFeedback = oResp.getOrNull("promptFeedback");
            if (oPromptFeedback != null) {
                String blockReason = oPromptFeedback.get("blockReason").getString();
                if (Utils.isNotEmpty(blockReason)) {
                    acc.setError(new ChatException("prompt blocked: " + blockReason));
                    return true;
                }
            }
        }

        ONode oUsage = oResp.getOrNull("usageMetadata");
        if (oUsage != null) {
            long promptTokens = oUsage.get("promptTokenCount").getLong();
            long completionTokens = oUsage.get("candidatesTokenCount").getLong();
            long totalTokens = oUsage.get("totalTokenCount").getLong();

            long cachedContentTokens = oUsage.get("cachedContentTokenCount").getLong();
            long thinkingTokens = oUsage.get("thoughtsTokenCount").getLong();

            acc.setUsage(new AiUsage(promptTokens, thinkingTokens, completionTokens, totalTokens,
                    0L, cachedContentTokens, oUsage));
        }

        return true;
    }
}
