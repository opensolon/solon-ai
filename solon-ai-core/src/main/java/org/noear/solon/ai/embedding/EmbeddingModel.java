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
package org.noear.solon.ai.embedding;

import org.noear.solon.ai.AiModel;
import org.noear.solon.ai.embedding.dialect.EmbeddingDialect;
import org.noear.solon.ai.embedding.dialect.EmbeddingDialectManager;
import org.noear.solon.ai.rag.Document;
import org.noear.solon.core.Props;
import org.noear.solon.core.util.Assert;
import org.noear.solon.lang.Preview;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

/**
 * 嵌入模型（相当于翻译器）
 *
 * @author noear
 * @since 3.1
 */
@Preview("3.1")
public class EmbeddingModel implements AiModel {
    private final EmbeddingConfig config;
    private final EmbeddingDialect dialect;

    public EmbeddingModel(Properties properties) {
        //支持直接注入
        this(Props.from(properties).bindTo(new EmbeddingConfig()));
    }

    public EmbeddingModel(EmbeddingConfig config) {
        Assert.notNull(config, "The config is required");
        Assert.notNull(config.getApiUrl(), "The config.apiUrl is required");
        Assert.notNull(config.getModel(), "The config.model is required");

        this.dialect = EmbeddingDialectManager.select(config);
        this.config = config;
    }

    /**
     * 快捷嵌入
     */
    public float[] embed(String text) throws IOException {
        EmbeddingResponse resp = input(text).call();
        if (resp.getError() != null) {
            throw resp.getError();
        }

        return resp.getData().get(0).getEmbedding();
    }

    /**
     * 嵌入批次大小
     */
    public int batchSize() {
        return config.getBatchSize();
    }

    /**
     * 维度
     */
    public int dimensions() throws IOException {
        return embed("test").length;
    }

    /**
     * 快捷嵌入
     */
    public void embed(List<Document> documents) throws IOException {
        List<String> texts = new ArrayList<>();
        documents.forEach(d -> texts.add(d.getContent()));

        EmbeddingResponse resp = input(texts).call();
        if (resp.getError() != null) {
            throw resp.getError();
        }

        List<Embedding> embeddings = resp.getData();

        if (embeddings.size() != documents.size()) {
            throw new EmbeddingException("The embedded data is not equal to the size of documents");
        }

        for (int i = 0; i < embeddings.size(); ++i) {
            Document doc = documents.get(i);
            doc.embedding(embeddings.get(i).getEmbedding());
        }
    }

    /**
     * 输入
     */
    public EmbeddingRequestDesc input(String... input) {
        return input(Arrays.asList(input));
    }

    /**
     * 输入
     */
    public EmbeddingRequestDesc input(List<String> input) {
        return new EmbeddingRequestDesc(config, dialect, input);
    }


    @Override
    public String toString() {
        return "EmbeddingModel{" +
                "config=" + config +
                ", dialect=" + dialect.getClass().getName() +
                '}';
    }

    /**
     * 构建
     */
    public static Builder of(EmbeddingConfig config) {
        return new Builder(config);
    }

    /**
     * 构建
     */
    public static Builder of(String apiUrl) {
        return new Builder(apiUrl);
    }

    /// /////////////

    /**
     * 嵌入模型构建器实现
     *
     * @author noear
     * @since 3.1
     */
    public static class Builder {
        private final EmbeddingConfig config;

        /**
         * @param apiUrl 接口地址
         */
        public Builder(String apiUrl) {
            this.config = new EmbeddingConfig();
            this.config.setApiUrl(apiUrl);
        }

        /**
         * @param config 配置
         */
        public Builder(EmbeddingConfig config) {
            this.config = config;
        }

        /**
         * 接口密钥
         */
        public Builder apiKey(String apiKey) {
            config.setApiKey(apiKey);
            return this;
        }

        /**
         * 服务提供者
         */
        public Builder provider(String provider) {
            config.setProvider(provider);
            return this;
        }


        /**
         * 使用模型
         */
        public Builder model(String model) {
            config.setModel(model);
            return this;
        }

        /**
         * 头信息设置
         */
        public Builder headerSet(String key, String value) {
            config.setHeader(key, value);
            return this;
        }

        /**
         *  User-Agent
         */
        public Builder userAgent(String userAgent){
            config.setUserAgent(userAgent);
            return this;
        }

        /**
         * 网络超时
         */
        public Builder timeout(Duration timeout) {
            config.setTimeout(timeout);
            return this;
        }

        /**
         * 网络代理
         */
        public Builder proxy(Proxy proxy) {
            config.setProxy(proxy);

            return this;
        }

        /**
         * 网络代理
         */
        public Builder proxy(String host, int port) {
            return proxy(new Proxy(Proxy.Type.HTTP, new InetSocketAddress(host, port)));
        }

        /**
         * 批次大小（一批文档大小）
         */
        public Builder batchSize(int batchSize) {
            config.setBatchSize(batchSize);
            return this;
        }

        public EmbeddingModel build() {
            return new EmbeddingModel(config);
        }
    }
}