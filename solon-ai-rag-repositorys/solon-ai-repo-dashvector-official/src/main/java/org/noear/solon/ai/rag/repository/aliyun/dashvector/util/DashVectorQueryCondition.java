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
package org.noear.solon.ai.rag.repository.aliyun.dashvector.util;

import com.aliyun.dashvector.common.Constants;

import org.noear.solon.ai.rag.util.QueryCondition;
import org.noear.solon.expression.Expression;

import java.util.List;
import java.util.Map;

/**
 * DashVector 专用查询条件
 *
 * <p>在 {@link QueryCondition} 的基础上扩展 DashVector 官方 SDK 特有的查询参数：
 * <ul>
 *     <li>{@code partition} - 分区名称</li>
 *     <li>{@code outputFields} - 仅返回指定字段</li>
 *     <li>{@code includeVector} - 是否返回向量数据</li>
 *     <li>{@code sparseVector} - 稀疏向量（用于关键词权重检索）</li>
 *     <li>{@code id} - 使用主键检索（替代向量检索）</li>
 * </ul>
 *
 * @author 烧饵块
 */
public class DashVectorQueryCondition extends QueryCondition {
    private String partition = Constants.DEFAULT_PARTITION_NAME;
    private List<String> outputFields;
    private boolean includeVector = false;
    private Map<Long, Float> sparseVector;
    private String id;

    public DashVectorQueryCondition(String query) {
        super(query);
    }

    /// /////////////////

    public String getPartition() {
        return partition;
    }

    public List<String> getOutputFields() {
        return outputFields;
    }

    public boolean isIncludeVector() {
        return includeVector;
    }

    public Map<Long, Float> getSparseVector() {
        return sparseVector;
    }

    public String getId() {
        return id;
    }

    /// /////////////////

    /**
     * 配置分区名称
     */
    public DashVectorQueryCondition partition(String partition) {
        this.partition = partition;
        return this;
    }

    /**
     * 配置仅返回的字段列表
     */
    public DashVectorQueryCondition outputFields(List<String> outputFields) {
        this.outputFields = outputFields;
        return this;
    }

    /**
     * 配置是否返回向量数据
     */
    public DashVectorQueryCondition includeVector(boolean includeVector) {
        this.includeVector = includeVector;
        return this;
    }

    /**
     * 配置稀疏向量（用于关键词权重检索）
     */
    public DashVectorQueryCondition sparseVector(Map<Long, Float> sparseVector) {
        this.sparseVector = sparseVector;
        return this;
    }

    /**
     * 直接通过主键检索（不再使用 query 文本生成向量）
     */
    public DashVectorQueryCondition id(String id) {
        this.id = id;
        return this;
    }

    /// /////////////////
    // 协变返回，保证链式调用仍能拿到 DashVectorQueryCondition

    @Override
    public DashVectorQueryCondition limit(int limit) {
        super.limit(limit);
        return this;
    }

    @Override
    public DashVectorQueryCondition similarityThreshold(double similarityThreshold) {
        super.similarityThreshold(similarityThreshold);
        return this;
    }

    @Override
    public DashVectorQueryCondition disableRefilter(boolean disableRefilter) {
        super.disableRefilter(disableRefilter);
        return this;
    }

    @Override
    public DashVectorQueryCondition filterExpression(String filterExpression) {
        super.filterExpression(filterExpression);
        return this;
    }

    @Override
    public DashVectorQueryCondition filterExpression(Expression<Boolean> filterExpression) {
        super.filterExpression(filterExpression);
        return this;
    }
}