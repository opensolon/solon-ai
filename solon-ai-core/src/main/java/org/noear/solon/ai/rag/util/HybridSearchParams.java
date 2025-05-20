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
package org.noear.solon.ai.rag.util;

/**
 * 混合检索参数
 *
 * @author orz_zsy
 * @since 3.3
 */
public class HybridSearchParams {
    /**
     * 默认的混合检索参数 (向量检索权重 0.5, 全文检索权重 0.5)
     */
    public static final HybridSearchParams DEFAULT = new HybridSearchParams(0.5, 0.5);
    private final double vectorWeight;
    private final double fullTextWeight;

    private HybridSearchParams() {
        this(0.5, 0.5);
    }

    private HybridSearchParams(double vectorWeight, double fullTextWeight) {
        this.vectorWeight = vectorWeight;
        this.fullTextWeight = fullTextWeight;
    }

    /**
     * 矢量权重
     */
    public double getVectorWeight() {
        return vectorWeight;
    }

    /**
     * 全文权重
     */
    public double getFullTextWeight() {
        return fullTextWeight;
    }



    /**
     * 创建混合检索参数，优先设置向量检索权重，并自动调整全文检索权重，确保两者权重总和为 1.0
     *
     * @param vectorWeight 向量权重
     * @return 混合检索参数对象
     */
    public static HybridSearchParams of(double vectorWeight) {
        // 限制 vectorWeight 在 0 到 1 之间
        double effectiveVectorWeight = Math.max(0.0, Math.min(1.0, vectorWeight));
        return new HybridSearchParams(effectiveVectorWeight, 1.0 - effectiveVectorWeight);
    }
}