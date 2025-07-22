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

import org.noear.snack.ONode;
import org.noear.solon.ai.AiConfig;
import org.noear.solon.ai.embedding.EmbeddingModel;
import org.noear.solon.ai.rag.Document;
import org.noear.solon.ai.rag.Repository;
import org.noear.solon.ai.rag.util.QueryCondition;
import org.noear.solon.ai.rag.util.SimilarityUtil;
import org.noear.solon.lang.Nullable;
import org.noear.solon.net.http.HttpUtils;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 百度AI搜索Repository（支持基础搜索和AI搜索）
 * <p>
 * 基于百度AI搜索V2接口实现的Repository，支持两种搜索模式：
 * - 基础搜索：返回搜索结果列表，不传model参数
 * - AI搜索：结合大模型进行智能总结，传入model参数
 * </p>
 *
 * <p>
 * API文档参考：<a href="https://cloud.baidu.com/doc/AppBuilder/s/zm8pn5cju">API文档参考</a>
 * </p>
 *
 * @author yangbuyiya
 * @since 3.0
 */
public class BaiduWebSearchRepository implements Repository {
    
    private final AiConfig config;
    private final SearchType searchType;
    private final String model;
    private final @Nullable EmbeddingModel embeddingModel;
    
    /**
     * 私有构造函数，使用Builder模式创建实例
     */
    private BaiduWebSearchRepository(AiConfig config, SearchType searchType, String model, EmbeddingModel embeddingModel) {
        this.config = config;
        this.searchType = searchType != null ? searchType : SearchType.BASIC;
        this.model = model != null ? model : "ernie-3.5-8k";
        this.embeddingModel = embeddingModel;
    }
    
    /**
     * 创建基础搜索Builder实例
     */
    public static BaiduAiSearchRepositoryBuilder ofBasic() {
        return new BaiduAiSearchRepositoryBuilder(SearchType.BASIC);
    }
    
    /**
     * 创建AI搜索Builder实例
     */
    public static BaiduAiSearchRepositoryBuilder ofAI() {
        return new BaiduAiSearchRepositoryBuilder(SearchType.AI);
    }
    
    /**
     * 创建指定类型的Builder实例
     */
    public static BaiduAiSearchRepositoryBuilder of(SearchType searchType) {
        return new BaiduAiSearchRepositoryBuilder(searchType);
    }
    
    @Override
    public List<Document> search(QueryCondition condition) throws IOException {
        String query = condition.getQuery();
        if (query == null || query.trim().isEmpty()) {
            return new ArrayList<>();
        }
        
        // 创建HttpUtils实例
        HttpUtils httpUtils = config.createHttpUtils();
        
        // 构建请求体
        ONode requestBody = new ONode();
        
        // 构建messages（两种模式都需要）
        ONode messagesArray = requestBody.getOrNew("messages").asArray();
        ONode message = messagesArray.addNew();
        message.set("role", "user");
        message.set("content", query.trim());
        
        // 根据搜索类型添加不同参数
        if (searchType == SearchType.AI) {
            // AI搜索：需要传入model参数
            requestBody.set("model", model);
            requestBody.set("stream", false);
            requestBody.set("enable_corner_markers", true);
            requestBody.set("enable_deep_search", false);  // 默认不开启深搜索，后续扩展
            requestBody.set("enable_followup_queries", false);  // 默认不开启追问，后续扩展
        }
        // 基础搜索：不传model参数，将自动识别为基础搜索模式
        
        // 支持limit参数
        if (condition.getLimit() > 0) {
            ONode resourceTypeFilter = requestBody.getOrNew("resource_type_filter").asArray().addNew();
            resourceTypeFilter.set("type", "web");
            resourceTypeFilter.set("top_k", condition.getLimit());
        }
        
        // 设置认证头
        httpUtils.header("X-Appbuilder-Authorization", "Bearer " + config.getApiKey());
        httpUtils.header("Content-Type", "application/json");
        
        // 发送请求
        String respJson = httpUtils.bodyOfJson(requestBody.toJson())
                .post();
        
        return parseResponse(respJson, condition);
    }
    
    /**
     * 解析响应结果
     */
    private List<Document> parseResponse(String respJson, QueryCondition condition) throws IOException {
        ONode respNode = ONode.load(respJson);
        
        // 检查错误
        if (respNode.contains("code")) {
            int code = respNode.get("code").getInt();
            String message = respNode.get("message").getString();
            if (code != 0) {
                throw new IOException("BaiduAiSearch error (code=" + code + "): " + message);
            }
        }
        
        List<Document> docs = new ArrayList<>();
        
        // AI搜索：先处理choices中的AI回答
        if (searchType == SearchType.AI && respNode.contains("choices")) {
            ONode choicesArray = respNode.get("choices");
            if (choicesArray.isArray() && choicesArray.count() > 0) {
                ONode firstChoice = choicesArray.get(0);
                if (firstChoice.contains("message")) {
                    String aiContent = firstChoice.get("message").get("content").getString();
                    if (aiContent != null && !aiContent.trim().isEmpty()) {
                        docs.add(new Document(aiContent.trim())
                                .title("Solon AI智能回答")
                                .metadata("type", "ai_answer")
                                .metadata("source", "baidu_ai_search"));
                    }
                }
            }
        }
        
        // 处理references中的搜索结果（基础搜索和AI搜索都有）
        if (respNode.contains("references")) {
            ONode referencesArray = respNode.get("references");
            if (referencesArray.isArray()) {
                for (ONode ref : referencesArray.ary()) {
                    String content = ref.get("content").getString();
                    String title = ref.get("title").getString();
                    String url = ref.get("url").getString();
                    
                    if (content != null && !content.trim().isEmpty()) {
                        Document doc = new Document(content.trim());
                        
                        if (title != null && !title.trim().isEmpty()) {
                            doc.title(title.trim());
                        }
                        
                        if (url != null && !url.trim().isEmpty()) {
                            doc.url(url.trim());
                        }
                        
                        // 添加百度搜索相关元数据
                        if (ref.contains("id")) {
                            doc.metadata("id", ref.get("id").getString());
                        }
                        if (ref.contains("date") && ref.get("date").getString() != null) {
                            doc.metadata("date", ref.get("date").getString());
                        }
                        if (ref.contains("type")) {
                            doc.metadata("type", ref.get("type").getString());
                        } else {
                            doc.metadata("type", "web");  // 默认为web类型
                        }
                        if (ref.contains("web_anchor")) {
                            doc.metadata("web_anchor", ref.get("web_anchor").getString());
                        }
                        if (ref.contains("icon")) {
                            doc.metadata("icon", ref.get("icon").getString());
                        }
                        
                        // 标记来源
                        doc.metadata("source", "baidu_search");
                        
                        docs.add(doc);
                    }
                }
            }
        }
        
        // 如果没有解析到任何内容，抛出异常
        if (docs.isEmpty()) {
            throw new IOException("No content found in BaiduAiSearch response: " + respJson.substring(0, Math.min(200, respJson.length())));
        }
        
        // 使用嵌入模型进行相似度排序
        if (embeddingModel != null) {
            //如果有嵌入模型设置，则做相互度排序和二次过滤
            embeddingModel.embed(docs);
            
            float[] queryEmbed = embeddingModel.embed(condition.getQuery());
            return SimilarityUtil.refilter(docs.stream()
                            .map(doc -> SimilarityUtil.score(doc, queryEmbed)),
                    condition);
        } else {
            return docs;
        }
    }
    
    /**
     * 搜索类型枚举
     */
    public enum SearchType {
        /**
         * 基础搜索：仅返回搜索结果，不传model参数
         */
        BASIC,
        /**
         * AI搜索：结合大模型进行智能总结，传入model参数
         */
        AI
    }
    
    /**
     * Builder模式构建器
     */
    public static class BaiduAiSearchRepositoryBuilder {
        private final SearchType searchType;
        private AiConfig config = new AiConfig();
        private String model;
        private EmbeddingModel embeddingModel;
        
        private BaiduAiSearchRepositoryBuilder(SearchType searchType) {
            this.searchType = searchType;
        }
        
        /**
         * 设置百度AppBuilder API Key（必填）
         */
        public BaiduAiSearchRepositoryBuilder apiKey(String apiKey) {
            config.setApiKey(apiKey);
            return this;
        }
        
        /**
         * 设置API URL（必填 百度官方地址）
         */
        public BaiduAiSearchRepositoryBuilder apiUrl(String apiUrl) {
            config.setApiUrl(apiUrl);
            return this;
        }
        
        /**
         * 设置大模型名称（仅AI搜索模式有效，可选，默认为ernie-3.5-8k）
         * <p>
         * 支持的模型包括：
         * - ernie-3.5-8k
         * - ernie-4.0-turbo-8k（支持图文混排场景）
         * - ernie-4.0-turbo-128k（支持图文混排场景）
         * - deepseek-r1
         * - deepseek-v3
         * 等
         */
        public BaiduAiSearchRepositoryBuilder model(String model) {
            this.model = model;
            return this;
        }
        
        /**
         * 设置嵌入模型（可选，用于相似度排序）
         */
        public BaiduAiSearchRepositoryBuilder embeddingModel(EmbeddingModel embeddingModel) {
            this.embeddingModel = embeddingModel;
            return this;
        }
        
        /**
         * 构建BaiduAiSearchRepository实例
         */
        public BaiduWebSearchRepository build() {
            return new BaiduWebSearchRepository(config, searchType, model, embeddingModel);
        }
    }
}
