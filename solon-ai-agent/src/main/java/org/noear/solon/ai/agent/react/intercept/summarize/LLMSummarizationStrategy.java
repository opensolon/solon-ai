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
    // 1. 系统指令：定义总结逻辑和约束
    private String systemInstruction = "## 角色定义\n" +
            "你是一个高效的任务进度分析员。请简要总结 AI Agent 的执行历史片段。\n\n" +
            "## 总结要点\n" +
            "1. **操作回顾**：已尝试的主要操作（工具调用）。\n" +
            "2. **关键发现**：获取到的核心信息或结论。\n" +
            "3. **当前进度**：目前处于任务的哪个阶段，还剩什么未完成。\n\n" +
            "## 输出规范\n" +
            "- 要求：精炼、准确，不超过 300 字。\n" +
            "- 严禁包含：无关的客套话或自我介绍。\n" +
            "- 若无可总结内容，请回复：(无显著进度)。";

    /**
     * @param chatModel 用于生成摘要的模型（建议使用廉价、快速的模型）
     */
    public LLMSummarizationStrategy(ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    public void setSystemInstruction(String systemInstruction) {
        this.systemInstruction = systemInstruction;
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
            String userData = "### 待总结历史片段\n" +
                    newHistoryText +
                    "\n\n" +
                    "### 任务指令\n" +
                    "请根据系统指令对上述执行过程进行语义总结：";

            String summary = AgentUtil.callWithRetry(() -> chatModel.prompt(userData)
                    .options(o -> o.systemPrompt(systemInstruction))
                    .call().getContent());

            if (Assert.isEmpty(summary) || summary.contains("(无显著进度)")) {
                return null;
            }

            // 3. 返回包含标记的消息
            return ChatMessage.ofSystem("--- [Execution Summary] ---\n" + summary)
                    .addMetadata(ReActAgent.META_SUMMARY, 1);

        } catch (Throwable e) {
            log.error("Failed to generate LLM summary", e);
            return null;
        }
    }
}