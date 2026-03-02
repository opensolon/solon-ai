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

import org.noear.solon.ai.agent.react.ReActAgent;
import org.noear.solon.ai.agent.react.ReActTrace;
import org.noear.solon.ai.agent.react.intercept.SummarizationStrategy;
import org.noear.solon.ai.agent.util.AgentUtil;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.message.ToolMessage;
import org.noear.solon.core.util.Assert;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 基于 LLM 的语义总结策略实现
 *
 * @author noear
 * @since 3.9.4
 */
public class LLMSummarizationStrategy implements SummarizationStrategy {
    private static final Logger log = LoggerFactory.getLogger(LLMSummarizationStrategy.class);

    private final ChatModel chatModel;
    private final String prompt;

    /**
     * @param chatModel 用于生成摘要的模型（建议使用廉价、快速的模型）
     */
    public LLMSummarizationStrategy(ChatModel chatModel) {
        this(chatModel, "请简要总结以下 AI Agent 的执行历史。说明已尝试的操作、获取的关键信息以及当前的进度。要求：精炼、准确，不超过 300 字。");
    }

    public LLMSummarizationStrategy(ChatModel chatModel, String prompt) {
        this.chatModel = chatModel;
        this.prompt = prompt;
    }

    @Override
    public ChatMessage summarize(ReActTrace trace, List<ChatMessage> messagesToSummarize) {
        if (messagesToSummarize == null || messagesToSummarize.isEmpty()) {
            return null;
        }

        try {
            // 1. 过滤初心，只看发生了什么
            String newHistoryText = messagesToSummarize.stream()
                    .filter(m -> !m.hasMetadata(ReActAgent.META_FIRST))
                    .map(m -> {
                        if (m instanceof AssistantMessage && Assert.isNotEmpty(((AssistantMessage) m).getToolCalls())) {
                            return "[Action]: 调用工具 " + ((AssistantMessage) m).getToolCalls().get(0).getName();
                        }
                        if (m instanceof ToolMessage) {
                            String content = m.getContent();
                            if (content != null && content.length() > 2000) {
                                content = content.substring(0, 2000) + "...[内容过长已截断]";
                            }
                            return "[Observation]: 得到结果 " + content;
                        }
                        return m.getRole().name() + ": " + m.getContent();
                    })
                    .collect(Collectors.joining("\n"));

            if (Assert.isEmpty(newHistoryText)) return null;

            // 2. 构建提示词
            String requestText = new StringBuilder(prompt.length() + newHistoryText.length() + 20)
                    .append(prompt)
                    .append("\n\n--- 执行过程 ---\n")
                    .append(newHistoryText)
                    .toString();

            String summary = AgentUtil.callWithRetry(() -> chatModel.prompt(requestText).call().getContent());

            // 3. 返回包含标记的消息
            return ChatMessage.ofSystem("--- [Execution Summary] ---\n" + summary);

        } catch (Throwable e) {
            log.error("Failed to generate LLM summary", e);
            return null;
        }
    }
}