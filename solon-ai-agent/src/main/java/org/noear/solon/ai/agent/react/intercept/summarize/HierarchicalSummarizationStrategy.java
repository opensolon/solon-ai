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
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.ArrayList;
import java.util.stream.Collectors;

/**
 * 层级摘要策略实现（支持无限续航）
 * 核心逻辑：将“旧摘要”与“新过期的消息”进行递归合并，确保记忆链条永不断裂。
 *
 * @author noear
 * @since 3.9.4
 */
public class HierarchicalSummarizationStrategy implements SummarizationStrategy {
    private static final Logger log = LoggerFactory.getLogger(HierarchicalSummarizationStrategy.class);

    private final ChatModel chatModel;

    private String promptTemplate = "你是一个记忆管理专家。初始任务目标已在上下文中永久保留，请忽略它们。\n" +
            "请将『旧的执行摘要』与『新增的过期历史』合并，更新为一个精炼的『当前进度摘要』。\n" +
            "要求：\n" +
            "1. 重点提取已确认的数据、当前的逻辑位置和最终结论。\n" +
            "2. 移除重复的思考过程和已失效的尝试。\n" +
            "3. 保持长度在 500 字以内。\n\n" +
            "【旧摘要】：\n%s\n\n" +
            "【新增历史】：\n%s\n\n" +
            "请输出更新后的进度摘要：";

    private int maxSummaryLength = 800;    // 增加长度硬性保护

    private static final String SUMMARY_PREFIX = "--- [全局进度滚动摘要 (层级压缩)] ---";
    private static final String STRATEGY_LASTSUMMARY_KEY = "agent:summary:hierarchical";

    public HierarchicalSummarizationStrategy(ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    public void setPromptTemplate(String promptTemplate) {
        this.promptTemplate = promptTemplate;
    }

    public void setMaxSummaryLength(int maxSummaryLength) {
        this.maxSummaryLength = maxSummaryLength;
    }

    @Override
    public ChatMessage summarize(ReActTrace trace, List<ChatMessage> messagesToSummarize) {
        String lastSummary = trace.getExtraAs(STRATEGY_LASTSUMMARY_KEY);
        if (lastSummary == null) {
            lastSummary = "";
        }

        // 过滤初心，只总结“中间增量”
        List<ChatMessage> pureExpired = (messagesToSummarize == null) ? new ArrayList<>() :
                messagesToSummarize.stream()
                        .filter(m -> !m.hasMetadata(ReActAgent.META_FIRST))
                        .collect(Collectors.toList());

        if (pureExpired.isEmpty()) {
            return buildMessage(lastSummary);
        }

        try {
            // 1. 提取新过期的流水账
            String newHistoryText = pureExpired.stream()
                    .map(m -> String.format("%s: %s", m.getRole().name(), m.getContent()))
                    .collect(Collectors.joining("\n"));

            // 2. 构造指令（lastSummary 可能已经包含了之前的进度）
            String prompt = String.format(promptTemplate,
                    (lastSummary.isEmpty() ? "（暂无）" : lastSummary),
                    newHistoryText
            );

            // 3. 调用模型生成增量摘要
            lastSummary = chatModel.prompt(prompt).call().getContent();

            if (lastSummary != null && lastSummary.length() > maxSummaryLength) {
                lastSummary = lastSummary.substring(0, maxSummaryLength) + "...[Truncated]";
            }

            // 4. 更新内部状态
            trace.setExtra(STRATEGY_LASTSUMMARY_KEY, lastSummary);

            return buildMessage(lastSummary);
        } catch (Exception e) {
            log.error("Hierarchical summarization failed", e);
            return buildMessage(lastSummary);
        }
    }

    private ChatMessage buildMessage(String content) {
        if (content == null || content.isEmpty()) {
            return null;
        }
        return ChatMessage.ofSystem(SUMMARY_PREFIX + "\n" + content);
    }
}