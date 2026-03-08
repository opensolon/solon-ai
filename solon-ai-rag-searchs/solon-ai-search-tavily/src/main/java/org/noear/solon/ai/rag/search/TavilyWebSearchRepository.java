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
package org.noear.solon.ai.rag.search;

import org.noear.solon.ai.rag.Document;
import org.noear.solon.ai.rag.Repository;
import org.noear.solon.ai.rag.search.tavily.ClientBuilder;
import org.noear.solon.ai.rag.search.tavily.TavilyClient;
import org.noear.solon.ai.rag.search.tavily.repository.TavilySimpleSearchRepository;
import org.noear.solon.ai.rag.util.QueryCondition;

import java.io.IOException;
import java.util.List;

/**
 * Tavily 联网搜索知识库（简化版，仅实现 Repository 搜索接口）
 *
 * <p>如需完整功能（extract、crawl、map 以及 Tavily 特有的搜索响应），
 * 请使用 {@link TavilySimpleSearchRepository}。</p>
 *
 * @author shaoerkuai
 * @since 3.9.5
 */
public class TavilyWebSearchRepository implements Repository {
    private final TavilySimpleSearchRepository delegate;

    public TavilyWebSearchRepository(TavilyClient client) {
        this.delegate = new TavilySimpleSearchRepository(client);
    }

    @Override
    public List<Document> search(QueryCondition condition) throws IOException {
        return delegate.search(condition);
    }

    /**
     * 获取完整功能的 TavilySearchRepository
     */
    public TavilySimpleSearchRepository getFullRepository() {
        return delegate;
    }

    /**
     * 创建构建器
     *
     * @param apiKey API 密钥（必传）
     */
    public static Builder of(String apiKey) {
        return new Builder(apiKey);
    }

    /**
     * 构建器
     */
    public static class Builder {
        private final ClientBuilder clientBuilder;

        public Builder(String apiKey) {
            this.clientBuilder = new ClientBuilder(apiKey);
        }

        /**
         * 配置 API 端点（默认 https://api.tavily.com）
         */
        public Builder apiBase(String apiBase) {
            clientBuilder.apiBase(apiBase);
            return this;
        }

        public TavilyWebSearchRepository build() {
            return new TavilyWebSearchRepository(clientBuilder.build());
        }
    }
}
