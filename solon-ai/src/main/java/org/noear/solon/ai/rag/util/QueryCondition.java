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
import org.noear.solon.expression.snel.SnelEvaluator;

/**
 * 查询条件
 *
 * @author noear
 * @since 3.1
 */
public class QueryCondition {
    private final String query;
    private Freshness freshness;
    private int limit = 4;
    private double similarityThreshold = 0.4D;
    private Expression<Boolean> filterExpression;

    public QueryCondition(String query) {
        this.query = query;
    }

    /// /////////////////

    /**
     * 获取查询字符串
     */
    public String getQuery() {
        return query;
    }

    /**
     * 获取热度（时间范围）
     */
    public Freshness getFreshness() {
        return freshness;
    }

    /**
     * 获取限制条数
     */
    public int getLimit() {
        return limit;
    }

    /**
     * 获取相似度阈值
     */
    public double getSimilarityThreshold() {
        return similarityThreshold;
    }

    /**
     * 获取过滤器（用于查询结果的二次过滤）
     */
    public Expression<Boolean> getFilterExpression() {
        return filterExpression;
    }

    /**
     * 过滤
     */
    public boolean doFilter(Document doc) {
        if (filterExpression == null) {
            return true;
        } else {
            return filterExpression.eval(doc.getMetadata());
        }
    }


    /// /////////////////

    /**
     * 热度（时间范围）
     */
    public QueryCondition freshness(Freshness freshness) {
        this.freshness = freshness;
        return this;
    }

    /**
     * 配置限制条数
     */
    public QueryCondition limit(int limit) {
        this.limit = limit;
        return this;
    }

    /**
     * 配置过滤表达式
     */
    public QueryCondition filterExpression(Expression<Boolean> filterExpression) {
        this.filterExpression = filterExpression;
        return this;
    }

    /**
     * 配置过滤表达式
     */
    public QueryCondition filterExpression(String filterExpression) {
        this.filterExpression = SnelEvaluator.getInstance().compile(filterExpression);
        return this;
    }

    /**
     * 配置相似度阈值
     */
    public QueryCondition similarityThreshold(double similarityThreshold) {
        this.similarityThreshold = similarityThreshold;
        return this;
    }
}