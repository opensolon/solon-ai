/*
 * Copyright 2017-2025 noear.org and authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.noear.solon.ai.agent.react.intercept.summarize;

import org.noear.solon.ai.agent.react.ReActTrace;
import org.noear.solon.ai.agent.react.intercept.SummarizationStrategy;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 基于 LLM 的关键信息提取策略实现
 * 相比于全文总结，该策略更侧重于提取“事实、参数、结论”，过滤掉无用的思考过程。
 *
 * @author noear
 * @since 3.9.4
 */
public class KeyInfoExtractionStrategy implements SummarizationStrategy {
    private static final Logger log = LoggerFactory.getLogger(KeyInfoExtractionStrategy.class);

    private final ChatModel chatModel;
    private final String prompt;

    /**
     * @param chatModel 用于执行提取任务的模型
     */
    public KeyInfoExtractionStrategy(ChatModel chatModel) {
        this(chatModel, "你是一个信息审计专家。请从以下对话历史中提取核心关键信息。\n" +
                "提取重点包括：\n" +
                "1. 用户提及的特定参数、约束或偏好；\n" +
                "2. 已经通过工具获取到的确定性事实（如ID、数值、状态）；\n" +
                "3. 已验证为失败的尝试（以避免重复）。\n" +
                "要求：以简洁的列表形式输出，不含多余的修饰词。");
    }

    public KeyInfoExtractionStrategy(ChatModel chatModel, String prompt) {
        this.chatModel = chatModel;
        this.prompt = prompt;
    }

    @Override
    public ChatMessage summarize(ReActTrace trace, List<ChatMessage> messagesToSummarize) {
        if (messagesToSummarize == null || messagesToSummarize.isEmpty()) {
            return null;
        }

        try {
            // 1. 序列化对话历史，仅保留核心对话内容
            String historyText = messagesToSummarize.stream()
                    .map(m -> String.format("%s: %s", m.getRole(), m.getContent()))
                    .collect(Collectors.joining("\n"));

            // 2. 调用模型提取关键信息
            String requestText = new StringBuilder(prompt.length() + historyText.length() + 20)
                    .append(prompt).append("\n\n--- 对话历史 ---\n").append(historyText).toString();

            String keyInfo = chatModel.prompt(requestText).call().getContent();

            if (log.isDebugEnabled()) {
                log.debug("Key info extracted ({} messages)", messagesToSummarize.size());
            }

            // 3. 将提取到的“干货”作为系统信息注入
            return ChatMessage.ofSystem("--- [已确认的关键信息看板] ---\n" + keyInfo);

        } catch (Exception e) {
            log.error("Failed to extract key info", e);
            return null;
        }
    }
}