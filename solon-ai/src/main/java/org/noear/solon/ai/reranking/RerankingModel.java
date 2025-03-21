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
package org.noear.solon.ai.reranking;

import org.noear.solon.ai.AiModel;
import org.noear.solon.ai.rag.Document;
import org.noear.solon.ai.reranking.dialect.RerankingDialect;
import org.noear.solon.ai.reranking.dialect.RerankingDialectManager;
import org.noear.solon.lang.Preview;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 重排模型
 *
 * @author noear
 * @since 3.1
 */
@Preview("3.1")
public class RerankingModel implements AiModel {
    private final RerankingConfig config;
    private final RerankingDialect dialect;

    protected RerankingModel(RerankingConfig config) {
        this.dialect = RerankingDialectManager.select(config);
        this.config = config;
    }


    /**
     * 输入
     */
    public RerankingRequest input(String query ,List<Document> documents) {
        return new RerankingRequest(config, dialect,  query , documents);
    }


    /**
     * 构建
     */
    public static Builder of(RerankingConfig config) {
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
        private final RerankingConfig config;

        public Builder(String apiUrl) {
            this.config = new RerankingConfig();
            this.config.setApiUrl(apiUrl);
        }

        public Builder(RerankingConfig config) {
            this.config = config;
        }

        public Builder apiKey(String apiKey) {
            config.setApiKey(apiKey);
            return this;
        }

        public Builder provider(String provider) {
            config.setProvider(provider);
            return this;
        }

        public Builder model(String model) {
            config.setModel(model);
            return this;
        }

        public Builder headerSet(String key, String value) {
            config.setHeader(key, value);
            return this;
        }

        public Builder timeout(Duration timeout) {
            config.setTimeout(timeout);
            return this;
        }

        public RerankingModel build() {
            return new RerankingModel(config);
        }
    }
}