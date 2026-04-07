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

import org.noear.solon.ai.rag.Document;
import org.noear.solon.expression.Expression;

import java.io.IOException;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 相似度工具
 *
 * @author noear
 * @since 3.1
 */
public final class SimilarityUtil {
    /**
     * 再过滤（评分与数量并排序）
     */
    public static List<Document> refilter(Stream<Document> docs) {
        return refilter(docs, QueryCondition.DEFAULT_LIMIT);
    }

    /**
     * 再过滤（评分与数量并排序）
     */
    public static List<Document> refilter(Stream<Document> docs, int limit) {
        return refilter(docs, limit, QueryCondition.DEFAULT_SIMILARITY_THRESHOLD);
    }

    /**
     * 再过滤（评分与数量并排序）
     */
    public static List<Document> refilter(Stream<Document> docs, int limit, double similarityThreshold) {
        return docs.filter(doc -> similarityCheck(doc, similarityThreshold))
                .sorted(Comparator.comparing(Document::getScore).reversed())
                .limit(limit)
                .collect(Collectors.toList());
    }

    /**
     * 再过滤
     */
    public static List<Document> refilter(Stream<Document> docs, QueryCondition condition) throws IOException {
        if (condition.isDisableRefilter()) {
            return refilter(docs, condition.getLimit(), condition.getSimilarityThreshold());
        } else {
            return refilter(docs.filter(doc -> doFilter(condition.getFilterExpression(), doc)), condition.getLimit(), condition.getSimilarityThreshold());
        }
    }

    private static boolean doFilter(Expression<Boolean> filterExpression, Document doc) {
        if (filterExpression == null) {
            return true;
        } else {
            return filterExpression.eval(doc.getMetadata());
        }
    }

    /**
     * 对文档根据参照的向量进行相似度评分（余弦相似度）
     */
    public static Document score(Document doc, float[] queryEmbed) {
        //方便调试
        return doc.score(cosineSimilarity(queryEmbed, doc.getEmbedding()));
    }

    /**
     * 对文档根据参照的向量进行相似度评分（欧几里得距离，归一化到 0-1 相似度）
     */
    public static Document scoreByEuclidean(Document doc, float[] queryEmbed) {
        return doc.score(euclideanSimilarity(queryEmbed, doc.getEmbedding()));
    }

    /**
     * 对文档根据参照的向量进行相似度评分（点积）
     */
    public static Document scoreByDotProduct(Document doc, float[] queryEmbed) {
        return doc.score(dotProductSimilarity(queryEmbed, doc.getEmbedding()));
    }

    /**
     * 复制文档并评分
     */
    public static Document copyAndScore(Document doc, float[] queryEmbed) {
        //方便调试
        return new Document(doc.getId(),
                doc.getContent(),
                doc.getMetadata(),
                cosineSimilarity(queryEmbed, doc.getEmbedding()));
    }

    /**
     * 相似度检测
     */
    public static boolean similarityCheck(Document doc, double similarityThreshold) {
        //方便调试
        return doc.getScore() >= similarityThreshold;
    }


    /// //////////////////////////

    /**
     * 余弦相似度（返回 -1 到 1）
     *
     * @param embedA 嵌入矢量1
     * @param embedB 嵌入矢量2
     */
    public static double cosineSimilarity(float[] embedA, float[] embedB) {
        if (embedA != null && embedB != null) {
            if (embedA.length != embedB.length) {
                throw new IllegalArgumentException("Embed length must be equal");
            } else {
                float dotProduct = dotProduct(embedA, embedB);
                float normA = norm(embedA);
                float normB = norm(embedB);
                if (normA != 0.0F && normB != 0.0F) {
                    return dotProduct / (Math.sqrt(normA) * Math.sqrt(normB));
                } else {
                    throw new IllegalArgumentException("Embed cannot be zero norm");
                }
            }
        } else {
            throw new RuntimeException("Embed must not be null");
        }
    }

    /**
     * 欧几里得距离
     *
     * @param embedA 嵌入矢量1
     * @param embedB 嵌入矢量2
     * @return 距离值（>= 0），越小越相似
     */
    public static double euclideanDistance(float[] embedA, float[] embedB) {
        if (embedA == null || embedB == null) {
            throw new RuntimeException("Embed must not be null");
        }
        if (embedA.length != embedB.length) {
            throw new IllegalArgumentException("Embed length must be equal");
        }

        float sum = 0.0F;
        for (int i = 0; i < embedA.length; i++) {
            float diff = embedA[i] - embedB[i];
            sum += diff * diff;
        }
        return Math.sqrt(sum);
    }

    /**
     * 欧几里得相似度（将距离归一化到 0-1，越大越相似）
     *
     * <p>公式：{@code 1 / (1 + distance)}
     *
     * @param embedA 嵌入矢量1
     * @param embedB 嵌入矢量2
     */
    public static double euclideanSimilarity(float[] embedA, float[] embedB) {
        return 1.0 / (1.0 + euclideanDistance(embedA, embedB));
    }

    /**
     * 点积相似度
     *
     * @param embedA 嵌入矢量1
     * @param embedB 嵌入矢量2
     * @return 点积值，越大越相似（适用于归一化后的向量）
     */
    public static double dotProductSimilarity(float[] embedA, float[] embedB) {
        if (embedA == null || embedB == null) {
            throw new RuntimeException("Embed must not be null");
        }
        return dotProduct(embedA, embedB);
    }

    /**
     * 点积
     */
    public static float dotProduct(float[] embedA, float[] embedB) {
        if (embedA.length != embedB.length) {
            throw new IllegalArgumentException("Embed length must be equal");
        } else {
            float tmp = 0.0F;

            for (int i = 0; i < embedA.length; ++i) {
                tmp += embedA[i] * embedB[i];
            }

            return tmp;
        }
    }

    /**
     * 范数
     */
    private static float norm(float[] vector) {
        return dotProduct(vector, vector);
    }
}