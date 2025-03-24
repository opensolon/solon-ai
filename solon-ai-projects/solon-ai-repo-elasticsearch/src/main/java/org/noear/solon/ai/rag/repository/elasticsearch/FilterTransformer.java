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
package org.noear.solon.ai.rag.repository.elasticsearch;

import org.noear.solon.expression.Expression;
import org.noear.solon.expression.Transformer;
import org.noear.solon.expression.snel.*;

import java.util.*;

/**
 * 过滤转换器
 *
 * @author noear
 * @since 3.1
 */
public class FilterTransformer implements Transformer<Boolean, Map<String, Object>> {
    private static FilterTransformer instance = new FilterTransformer();

    public static FilterTransformer getInstance() {
        return instance;
    }

    /**
     * 将过滤表达式转换为Elasticsearch查询
     *
     * @param filterExpression 过滤表达式
     * @return Elasticsearch查询对象
     */
    @Override
    public Map<String, Object> transform(Expression<Boolean> filterExpression) {
        if (filterExpression == null) {
            return null;
        }

        if (filterExpression instanceof VariableNode) {
            // 变量节点，获取字段名
            String fieldName = ((VariableNode) filterExpression).getName();
            Map<String, Object> exists = new HashMap<>();
            Map<String, Object> field = new HashMap<>();
            field.put("field", fieldName);
            exists.put("exists", field);
            return exists;
        } else if (filterExpression instanceof ConstantNode) {
            // 常量节点，根据值类型和是否为集合创建不同的查询
            ConstantNode node = (ConstantNode) filterExpression;
            Object value = node.getValue();
            Boolean isCollection = node.isCollection();

            if (Boolean.TRUE.equals(value)) {
                Map<String, Object> matchAll = new HashMap<>();
                matchAll.put("match_all", new HashMap<>());
                return matchAll;
            } else if (Boolean.FALSE.equals(value)) {
                Map<String, Object> boolQuery = new HashMap<>();
                Map<String, Object> mustNot = new HashMap<>();
                mustNot.put("match_all", new HashMap<>());
                boolQuery.put("must_not", mustNot);
                return boolQuery;
            }

            return null;
        } else if (filterExpression instanceof ComparisonNode) {
            // 比较节点，处理各种比较运算符
            ComparisonNode node = (ComparisonNode) filterExpression;
            ComparisonOp operator = node.getOperator();
            Expression left = node.getLeft();
            Expression right = node.getRight();

            // 获取字段名和值
            String fieldName = null;
            Object value = null;

            if (left instanceof VariableNode && right instanceof ConstantNode) {
                fieldName = ((VariableNode) left).getName();
                value = ((ConstantNode) right).getValue();
            } else if (right instanceof VariableNode && left instanceof ConstantNode) {
                fieldName = ((VariableNode) right).getName();
                value = ((ConstantNode) left).getValue();
                // 反转操作符
                operator = reverseOperator(operator);
            } else {
                // 不支持的比较节点结构
                return null;
            }

            // 根据操作符构建相应的查询
            switch (operator) {
                case eq:
                    return createTermQuery(fieldName, value);
                case neq:
                    return createMustNotQuery(createTermQuery(fieldName, value));
                case gt:
                    return createRangeQuery(fieldName, "gt", value);
                case gte:
                    return createRangeQuery(fieldName, "gte", value);
                case lt:
                    return createRangeQuery(fieldName, "lt", value);
                case lte:
                    return createRangeQuery(fieldName, "lte", value);
                case in:
                    if (value instanceof Collection) {
                        return createTermsQuery(fieldName, (Collection<?>) value);
                    }
                    return createTermQuery(fieldName, value);
                case nin:
                    if (value instanceof Collection) {
                        return createMustNotQuery(createTermsQuery(fieldName, (Collection<?>) value));
                    }
                    return createMustNotQuery(createTermQuery(fieldName, value));
                default:
                    return null;
            }
        } else if (filterExpression instanceof LogicalNode) {
            // 逻辑节点，处理AND, OR, NOT
            LogicalNode node = (LogicalNode) filterExpression;
            LogicalOp operator = node.getOperator();
            Expression left = node.getLeft();
            Expression right = node.getRight();

            if (right != null) {
                // 二元逻辑运算符 (AND, OR)
                Map<String, Object> leftQuery = transform(left);
                Map<String, Object> rightQuery = transform(right);

                if (leftQuery == null || rightQuery == null) {
                    return null;
                }

                Map<String, Object> boolQuery = new HashMap<>();
                List<Map<String, Object>> conditions = new ArrayList<>();
                conditions.add(leftQuery);
                conditions.add(rightQuery);

                switch (operator) {
                    case AND:
                        boolQuery.put("must", conditions);
                        break;
                    case OR:
                        boolQuery.put("should", conditions);
                        break;
                    default:
                        return null;
                }

                Map<String, Object> result = new HashMap<>();
                result.put("bool", boolQuery);
                return result;
            } else if (left != null) {
                // 一元逻辑运算符 (NOT)
                Map<String, Object> operandQuery = transform(left);

                if (operandQuery == null) {
                    return null;
                }

                if (operator == LogicalOp.NOT) {
                    return createMustNotQuery(operandQuery);
                }
            }
        }

        return null;
    }

    /**
     * 反转比较运算符
     *
     * @param op 原运算符
     * @return 反转后的运算符
     */
    private ComparisonOp reverseOperator(ComparisonOp op) {
        switch (op) {
            case gt:
                return ComparisonOp.lt;
            case gte:
                return ComparisonOp.lte;
            case lt:
                return ComparisonOp.gt;
            case lte:
                return ComparisonOp.gte;
            default:
                return op;
        }
    }

    /**
     * 创建term查询
     *
     * @param field 字段名
     * @param value 值
     * @return 查询对象
     */
    private Map<String, Object> createTermQuery(String field, Object value) {
        Map<String, Object> termValue = new HashMap<>();
        termValue.put("value", value);
        Map<String, Object> term = new HashMap<>();
        term.put(field, termValue);

        Map<String, Object> result = new HashMap<>();
        result.put("term", term);
        return result;
    }

    /**
     * 创建terms查询（适用于集合）
     *
     * @param field  字段名
     * @param values 值集合
     * @return 查询对象
     */
    private Map<String, Object> createTermsQuery(String field, Collection<?> values) {
        Map<String, Object> terms = new HashMap<>();
        terms.put(field, new ArrayList<>(values));

        Map<String, Object> result = new HashMap<>();
        result.put("terms", terms);
        return result;
    }

    /**
     * 创建范围查询
     *
     * @param field    字段名
     * @param operator 操作符(gt, gte, lt, lte)
     * @param value    值
     * @return 查询对象
     */
    private Map<String, Object> createRangeQuery(String field, String operator, Object value) {
        Map<String, Object> rangeValue = new HashMap<>();
        rangeValue.put(operator, value);

        Map<String, Object> range = new HashMap<>();
        range.put(field, rangeValue);

        Map<String, Object> result = new HashMap<>();
        result.put("range", range);
        return result;
    }

    /**
     * 创建must_not查询（NOT操作）
     *
     * @param query 要否定的查询
     * @return 查询对象
     */
    private Map<String, Object> createMustNotQuery(Map<String, Object> query) {
        if (query == null) {
            return null;
        }

        Map<String, Object> boolQuery = new HashMap<>();
        List<Map<String, Object>> mustNot = new ArrayList<>();
        mustNot.add(query);
        boolQuery.put("must_not", mustNot);

        Map<String, Object> result = new HashMap<>();
        result.put("bool", boolQuery);
        return result;
    }
}