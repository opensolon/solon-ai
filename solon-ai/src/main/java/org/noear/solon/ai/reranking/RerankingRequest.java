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

import org.noear.solon.ai.rag.Document;
import org.noear.solon.ai.reranking.dialect.RerankingDialect;
import org.noear.solon.net.http.HttpUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;
import java.util.function.Consumer;

/**
 * 重排请求
 *
 * @author noear
 * @since 3.1
 */
public class RerankingRequest {
    private static final Logger log = LoggerFactory.getLogger(RerankingRequest.class);
    private static final RerankingOptions OPTIONS_DEFAULT = new RerankingOptions();

    private final RerankingConfig config;
    private final RerankingDialect dialect;
    private final String query;
    private final List<Document> documents;
    private RerankingOptions options;

    protected RerankingRequest(RerankingConfig config, RerankingDialect dialect, String query ,List<Document> documents) {
        this.config = config;
        this.dialect = dialect;
        this.query = query;
        this.documents = documents;
        this.options = OPTIONS_DEFAULT;
    }

    /**
     * 选项
     */
    public RerankingRequest options(RerankingOptions options) {
        if (options != null) {
            this.options = options;
        }

        return this;
    }

    /**
     * 选项
     */
    public RerankingRequest options(Consumer<RerankingOptions> optionsBuilder) {
        this.options = RerankingOptions.of();
        optionsBuilder.accept(options);
        return this;
    }

    /**
     * 调用
     */
    public RerankingResponse call() throws IOException {
        HttpUtils httpUtils = config.createHttpUtils();

        String reqJson = dialect.buildRequestJson(config, options, query, documents);

        if (log.isTraceEnabled()) {
            log.trace("ai-request: {}", reqJson);
        }

        String respJson = httpUtils.bodyOfJson(reqJson).post();

        if (log.isTraceEnabled()) {
            log.trace("ai-response: {}", respJson);
        }

        RerankingResponse resp = dialect.parseResponseJson(config, respJson);

        return resp;
    }
}