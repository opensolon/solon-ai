/*
 * Copyright 2017-2026 noear.org and authors
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
package org.noear.solon.ai.skills.memory;

import org.noear.solon.ai.rag.Document;
import org.noear.solon.ai.rag.RepositoryStorable;
import org.noear.solon.ai.rag.util.QueryCondition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 基于 RAG 存储库的记忆搜索供应商实现
 *
 * @author noear
 * @since 3.9.4
 */
public class MemSearchProviderRepositoryImpl implements MemSearchProvider {
    private static final Logger log = LoggerFactory.getLogger(MemSearchProviderRepositoryImpl.class);

    private final RepositoryStorable repository;

    public MemSearchProviderRepositoryImpl(RepositoryStorable repository) {
        this.repository = repository;
    }

    @Override
    public List<MemSearchResult> search(String userId, String query, int limit) {
        try {
            // 使用更严谨的表达式，并根据需要处理引号
            QueryCondition condition = new QueryCondition(query)
                    .filterExpression(String.format("user_id = '%s'", userId))
                    .limit(limit);

            List<Document> docs = repository.search(condition);
            return docs.stream()
                    .map(this::mapToResult)
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.error("MemSearchProvider search error: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    @Override
    public List<MemSearchResult> getHotMemories(String userId, int limit) {
        try {
            // 筛选重要度高且属于当前用户的认知
            QueryCondition condition = new QueryCondition("")
                    .filterExpression(String.format("user_id = '%s' && importance >= 5", userId))
                    .limit(limit);

            // 注意：如果底层支持，这里可以增加 .orderByDescending("time")

            List<Document> docs = repository.search(condition);
            return docs.stream()
                    .map(this::mapToResult)
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.error("MemSearchProvider getHotMemories error: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    @Override
    public void updateIndex(String userId, String key, String fact, int importance, String time) {
        try {
            Document doc = new Document(fact);
            doc.id(getDocId(userId, key));
            doc.metadata("user_id", userId);
            doc.metadata("mem_key", key);
            doc.metadata("importance", importance);
            doc.metadata("time", time);

            repository.save(doc);
        } catch (Exception e) {
            log.error("MemSearchProvider updateIndex error: {}", e.getMessage());
        }
    }

    @Override
    public void removeIndex(String userId, String key) {
        try {
            repository.deleteById(getDocId(userId, key));
        } catch (Exception e) {
            log.error("MemSearchProvider removeIndex error: {}", e.getMessage());
        }
    }

    protected String getDocId(String userId, String key) {
        return userId + ":" + key;
    }

    protected MemSearchResult mapToResult(Document doc) {
        String key = doc.getMetadataAs("mem_key");
        String time = doc.getMetadataAs("time");

        int importance = 0;
        Object impObj = doc.getMetadata("importance");
        if (impObj instanceof Number) {
            importance = ((Number) impObj).intValue();
        } else if (impObj instanceof String) {
            importance = Integer.parseInt((String) impObj);
        }

        return new MemSearchResult(key, doc.getContent(), importance, time);
    }
}