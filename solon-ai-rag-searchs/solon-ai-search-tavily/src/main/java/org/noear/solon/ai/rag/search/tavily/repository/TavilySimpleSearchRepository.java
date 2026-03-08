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
package org.noear.solon.ai.rag.search.tavily.repository;

import org.noear.solon.ai.embedding.EmbeddingModel;
import org.noear.solon.ai.rag.Document;
import org.noear.solon.ai.rag.Repository;
import org.noear.solon.ai.rag.search.tavily.ClientBuilder;
import org.noear.solon.ai.rag.search.tavily.TavilyClient;
import org.noear.solon.ai.rag.search.tavily.condition.CrawlCondition;
import org.noear.solon.ai.rag.search.tavily.condition.ExtractCondition;
import org.noear.solon.ai.rag.search.tavily.condition.MapCondition;
import org.noear.solon.ai.rag.search.tavily.condition.SearchCondition;
import org.noear.solon.ai.rag.search.tavily.document.*;
import org.noear.solon.ai.rag.util.QueryCondition;
import org.noear.solon.ai.rag.util.SimilarityUtil;
import org.noear.solon.lang.Nullable;

import java.io.IOException;
import java.util.List;

/**
 * Tavily 搜索知识库（支持 Search、Extract、Crawl、Map 四种操作）
 *
 * <p>实现标准 Repository 接口用于搜索，同时提供 Tavily 特有的完整响应类型。</p>
 *
 * @author shaoerkuai
 * @since 3.9.5
 */
public class TavilySimpleSearchRepository implements Repository {
    private final TavilyClient client;
    private final @Nullable EmbeddingModel embeddingModel;

    public TavilySimpleSearchRepository(TavilyClient client) {
        this(client, null);
    }

    public TavilySimpleSearchRepository(TavilyClient client, @Nullable EmbeddingModel embeddingModel) {
        this.client = client;
        this.embeddingModel = embeddingModel;
    }

    /**
     * 获取内部 TavilyClient
     */
    public TavilyClient getClient() {
        return client;
    }

    // ==================== Repository 接口（标准搜索） ====================

    @Override
    public List<Document> search(QueryCondition condition) throws IOException {
        SearchCondition searchCondition = new SearchCondition(condition.getQuery())
                .maxResults(condition.getLimit());

        if (condition.getFreshness() != null) {
            searchCondition.timeRange(freshnessToTimeRange(condition.getFreshness()));
        }

        TavilySearchResponse response = client.search(searchCondition);
        List<Document> docs = response.toDocuments();

        if (embeddingModel != null) {
            embeddingModel.embed(docs);

            float[] queryEmbed = embeddingModel.embed(condition.getQuery());
            return SimilarityUtil.refilter(docs.stream()
                            .map(doc -> SimilarityUtil.score(doc, queryEmbed)),
                    condition);
        }

        return docs;
    }

    // ==================== Tavily 完整搜索 ====================

    /**
     * 搜索（完整 Tavily 参数，返回完整响应）
     *
     * @param condition Tavily 搜索条件
     * @return 包含 answer、images、results 等完整信息的搜索响应
     */
    public TavilySearchResponse search(SearchCondition condition) throws IOException {
        return client.search(condition);
    }

    // ==================== Extract ====================

    /**
     * 提取指定 URL 的内容
     *
     * @param condition 提取条件
     * @return 完整提取响应（包含成功和失败结果）
     */
    public TavilyExtractResponse extract(ExtractCondition condition) throws IOException {
        return client.extract(condition);
    }

    // ==================== Crawl ====================

    /**
     * 从根 URL 爬取网站内容
     *
     * @param condition 爬取条件
     * @return 完整爬取响应
     */
    public TavilyCrawlResponse crawl(CrawlCondition condition) throws IOException {
        return client.crawl(condition);
    }

    // ==================== Map ====================

    /**
     * 映射网站结构，返回发现的 URL 列表
     *
     * @param condition 映射条件
     * @return 完整映射响应
     */
    public TavilyMapResponse map(MapCondition condition) throws IOException {
        return client.map(condition);
    }

    // ==================== Builder ====================

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
        private EmbeddingModel embeddingModel;

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

        /**
         * 配置嵌入模型（用于相似度排序）
         */
        public Builder embeddingModel(EmbeddingModel embeddingModel) {
            this.embeddingModel = embeddingModel;
            return this;
        }

        public TavilySimpleSearchRepository build() {
            return new TavilySimpleSearchRepository(clientBuilder.build(), embeddingModel);
        }
    }

    // ==================== 内部工具 ====================

    private static String freshnessToTimeRange(org.noear.solon.ai.rag.util.Freshness freshness) {
        switch (freshness) {
            case ONE_DAY:
                return "day";
            case ONE_WEEK:
                return "week";
            case ONE_MONTH:
                return "month";
            case ONE_YEAR:
                return "year";
            default:
                return null;
        }
    }
}
