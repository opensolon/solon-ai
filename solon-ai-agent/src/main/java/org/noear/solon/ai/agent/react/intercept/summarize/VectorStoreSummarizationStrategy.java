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
import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.chat.skill.AbsSkill;
import org.noear.solon.ai.rag.Document;
import org.noear.solon.ai.rag.RepositoryStorable;
import org.noear.solon.ai.rag.util.QueryCondition;
import org.noear.solon.annotation.Param;
import org.noear.solon.core.util.Assert;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 向量检索增强总结策略 (RAG-based Memory Strategy)
 * 核心逻辑：将裁减的消息持久化到向量库，并提取关键词或最新摘要作为上下文索引。
 *
 * @author noear
 * @since 3.9.4
 */
public class VectorStoreSummarizationStrategy extends AbsSkill implements SummarizationStrategy {
    private static final Logger log = LoggerFactory.getLogger(VectorStoreSummarizationStrategy.class);

    private final RepositoryStorable vectorRepository;

    public VectorStoreSummarizationStrategy(RepositoryStorable vectorRepository) {
        this.vectorRepository = vectorRepository;
    }

    // --- Skill 接口增强（负责“引导”） ---


    @Override
    public String description() {
        return "历史记忆回溯";
    }

    @Override
    public String getInstruction(Prompt prompt) {
        String sessionId = prompt.attrAs("sessionId");

        return "- 当你看到内容包含 '[Content Truncated]' 或 '[已归档至向量库]' 标记时，说明部分细节已存入冷记忆。\n" +
                "- 你可以调用 recall_history 工具来按关键词找回这些明细。";
    }

    // --- Tool 实现（负责“取”） ---
    @ToolMapping(name = "recall_history", description = "回溯本会话中早前已归档的长记忆、历史细节或事实事实")
    public String recall(@Param(name = "query", description = "检索关键词或核心短语") String query,
                         @Param(name = "limit", description = "返回记忆片段的数量", defaultValue = "3") int limit,
                         String __sessionId) {

        if (Assert.isEmpty(query)) {
            return "请提供具体的关键词以进行历史回溯。";
        }

        QueryCondition condition = new QueryCondition(query)
                .filterExpression("sessionId = '" + __sessionId + "'")
                .limit(limit);

        try {
            List<Document> docs = vectorRepository.search(condition);

            if (docs.isEmpty()) {
                return "记忆库中暂未找到关于 '" + query + "' 的相关记录。可能是该话题未被归档，或关键词不匹配。";
            }

            return docs.stream()
                    .map(d -> {
                        String time = d.getMetadataAs("timestamp");
                        return (time != null ? "[" + time + "] " : "") + d.getContent();
                    })
                    .collect(Collectors.joining("\n\n---\n\n"));

        } catch (Exception e) {
            log.error("Recall history failed. SessionId: {}, Query: {}", __sessionId, query, e);
            return "[系统通知] 历史记忆访问受阻。请尝试基于当前已知对话继续执行。";
        }
    }

    @Override
    public ChatMessage summarize(ReActTrace trace, List<ChatMessage> messagesToSummarize) {
        if (messagesToSummarize == null || messagesToSummarize.isEmpty()) {
            return null;
        }

        try {
            // 1. 结构化历史记录
            String contentToStore = messagesToSummarize.stream()
                    .map(m -> String.format("%s: %s", m.getRole().name(), m.getContent()))
                    .collect(Collectors.joining("\n"));

            // 2. 异步持久化到向量数据库 (冷记忆存入)
            // 在实际工程中，这里可以关联当前的 traceId 方便后续按对话隔离检索
            Document doc = new Document(contentToStore);
            doc.metadata("sessionId", trace.getSession().getSessionId());
            doc.metadata("agentName", trace.getAgentName());
            doc.metadata("timestamp", OffsetDateTime.now().toString());
            doc.metadata("type", "historical_context");
            vectorRepository.save(doc);

            // 3. 返回一个引导性提示
            // 告知模型：旧细节已存入库，你可以通过特定意图触发检索（如果 Agent 具备 RAG 工具）
            // 或者简单返回一个标记，配合外部的 RAG 拦截器使用
            return ChatMessage.ofSystem("--- [历史明细已归档至向量库] ---\n" +
                    "注：若需回溯更早的执行细节，请调用知识库检索工具。");

        } catch (Exception e) {
            log.error("Failed to archive messages to vector store", e);
            return null;
        }
    }
}