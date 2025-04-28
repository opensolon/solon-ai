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

import org.noear.solon.ai.AiUsage;
import org.noear.solon.lang.Nullable;

import java.util.Collections;
import java.util.List;

/**
 * 重排响应
 *
 * @author noear
 * @since 3.1
 */
public class RerankingResponse {
    private final String model;
    private final RerankingException error;
    private final List<Reranking> results;
    private final AiUsage usage;

    public RerankingResponse(String model, RerankingException error, List<Reranking> results, AiUsage usage) {
        this.model = model;
        this.error = error;
        this.results = results;
        this.usage = usage;

        if (results != null) {
            Collections.sort(this.results);
        }
    }

    /**
     * 获取模型
     */
    public String getModel() {
        return model;
    }

    /**
     * 获取错误
     */
    @Nullable
    public RerankingException getError() {
        return error;
    }

    /**
     * 获取数据
     */
    public List<Reranking> getResults() {
        return results;
    }

    /**
     * 获取使用情况
     */
    public AiUsage getUsage() {
        return usage;
    }

    @Override
    public String toString() {
        return "{" +
                "model='" + model + '\'' +
                ", results=" + results +
                ", usage=" + usage +
                '}';
    }
}