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
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.rag.Document;
import org.noear.solon.ai.rag.RepositoryStorable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 向量检索增强总结策略 (RAG-based Memory Strategy)
 * 核心逻辑：将裁减的消息持久化到向量库，并提取关键词或最新摘要作为上下文索引。
 *
 * @author noear
 * @since 3.9.4
 */
public class VectorStoreSummarizationStrategy implements SummarizationStrategy {
    private static final Logger log = LoggerFactory.getLogger(VectorStoreSummarizationStrategy.class);

    private final RepositoryStorable vectorRepository;

    public VectorStoreSummarizationStrategy(RepositoryStorable vectorRepository) {
        this.vectorRepository = vectorRepository;
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
            doc.metadata("traceKey", trace.getConfig().getTraceKey());
            doc.metadata("agentName", trace.getAgentName());
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