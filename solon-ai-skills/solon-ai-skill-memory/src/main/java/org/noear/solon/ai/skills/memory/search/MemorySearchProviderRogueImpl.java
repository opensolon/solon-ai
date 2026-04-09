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
package org.noear.solon.ai.skills.memory.search;

import com.yomahub.roguemap.memory.MemoryResult;
import com.yomahub.roguemap.memory.RogueMemory;
import com.yomahub.roguemap.memory.SearchMode;
import com.yomahub.roguemap.memory.SearchOptions;
import org.noear.solon.ai.skills.memory.MemorySearchProvider;
import org.noear.solon.ai.skills.memory.MemorySearchResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 基于 RAG 存储库的记忆搜索供应商实现
 *
 * @author noear
 * @since 3.9.4
 */
public class MemorySearchProviderRogueImpl implements MemorySearchProvider {
    private static final Logger log = LoggerFactory.getLogger(MemorySearchProviderRogueImpl.class);

    private final RogueMemory rogueMemory;

    public MemorySearchProviderRogueImpl(String filePath) {
        this.rogueMemory = RogueMemory.mmap()
                .persistent("data/mem")
                .searchMode(SearchMode.KEYWORD_ONLY)          // 关键词检索
                .build();
    }

    public MemorySearchProviderRogueImpl(RogueMemory rogueMemory) {
        this.rogueMemory = rogueMemory;
    }

    @Override
    public List<MemorySearchResult> search(String userId, String query, int limit) {
        try {
            List<MemoryResult> docs = rogueMemory.search(query, limit, SearchOptions.builder().namespace(userId).build());
            return docs.stream()
                    .map(this::mapToResult)
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.error("MemSearchProvider search error: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    @Override
    public List<MemorySearchResult> getHotMemories(String userId, int limit) {
        try {
            List<MemoryResult> docs = rogueMemory.search("", limit, SearchOptions.builder().namespace(userId).build());
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
            Map<String, String> metadata = new LinkedHashMap<>();
            metadata.put("user_id", userId);
            metadata.put("mem_key", key);
            metadata.put("importance", String.valueOf(importance));
            metadata.put("time", time);

            rogueMemory.add(fact, metadata, userId);
        } catch (Exception e) {
            log.error("MemSearchProvider updateIndex error: {}", e.getMessage());
        }
    }

    @Override
    public void removeIndex(String userId, String key) {
        try {
            rogueMemory.delete(key);
        } catch (Exception e) {
            log.error("MemSearchProvider removeIndex error: {}", e.getMessage());
        }
    }

    protected String getDocId(String userId, String key) {
        return userId + ":" + key;
    }

    protected MemorySearchResult mapToResult(MemoryResult doc) {
        String key = doc.getMetadata().get("mem_key");
        String time = doc.getMetadata().get("time");

        double importance = 0;
        Object impObj = doc.getMetadata().get("importance");
        if (impObj instanceof Number) {
            importance = ((Number) impObj).doubleValue();
        } else if (impObj instanceof String) {
            importance = Double.parseDouble((String) impObj);
        }

        return new MemorySearchResult(key, doc.getContent(), importance, time);
    }
}