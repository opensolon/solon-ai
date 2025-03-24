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
package org.noear.solon.ai.rag.repository.chroma;

import org.noear.solon.expression.Expression;
import org.noear.solon.expression.Transformer;
import org.noear.solon.expression.snel.*;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

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
     * 解析QueryCondition中的过滤表达式，转换为Chroma支持的过滤条件格式
     *
     * @param filterExpr 查询条件
     * @return Chroma过滤表达式，格式为Map<String, Object>
     */
    @Override
    public Map<String, Object> transform(Expression<Boolean> filterExpr) {
        if (filterExpr == null) {
            return null;
        }

        try {
            // 处理Expression对象形式的过滤表达式
            Map<String, Object> filter = new HashMap<>();
            parseFilterExpression(filterExpr, filter);

            return filter.isEmpty() ? null : filter;
        } catch (Exception e) {
            System.err.println("Error processing filter expression: " + e.getMessage());
            e.printStackTrace();
            return null;
        }
    }

    /**
     * 解析Expression对象形式的过滤表达式
     * @param filterExpression 过滤表达式对象
     * @param result 存储解析结果的Map
     */
    private void parseFilterExpression(Expression<Boolean> filterExpression, Map<String, Object> result) {
        if (filterExpression == null) {
            return;
        }

        //用类型识别（用字符串，未来可能会变）
        if (filterExpression instanceof VariableNode) {
            // 获取字段名
            String fieldName = ((VariableNode) filterExpression).getName();
            result.put("$field", fieldName);
        } else if (filterExpression instanceof ConstantNode) {
            // 获取值
            Object value = ((ConstantNode) filterExpression).getValue();
            Boolean isCollection = ((ConstantNode) filterExpression).isCollection();
            if (isCollection) {
                result.put("$value", value);
                result.put("$isCollection", true);
            } else {
                result.put("$value", value);
            }
        } else if (filterExpression instanceof ComparisonNode) {
            // 获取比较操作符和左右子节点
            ComparisonOp operator = ((ComparisonNode) filterExpression).getOperator();
            Expression left = ((ComparisonNode) filterExpression).getLeft();
            Expression right = ((ComparisonNode) filterExpression).getRight();

            // 解析左右子节点
            Map<String, Object> leftMap = new HashMap<>();
            parseFilterExpression(left, leftMap);

            Map<String, Object> rightMap = new HashMap<>();
            parseFilterExpression(right, rightMap);

            // 提取字段名和值
            String fieldName2 = (String) leftMap.get("$field");
            Object value2 = rightMap.get("$value");

            if (fieldName2 != null && value2 != null) {
                switch (operator) {
                    case eq:
                        // 等于操作 - 直接设置字段值
                        result.put(fieldName2, value2);
                        break;
                    case neq:
                        // 不等于操作 - 使用$ne操作符
                        Map<String, Object> neMap = new HashMap<>();
                        neMap.put("$ne", value2);
                        result.put(fieldName2, neMap);
                        break;
                    case gt:
                        // 大于操作 - 使用$gt操作符
                        Map<String, Object> gtMap = new HashMap<>();
                        gtMap.put("$gt", value2);
                        result.put(fieldName2, gtMap);
                        break;
                    case gte:
                        // 大于等于操作 - 使用$gte操作符
                        Map<String, Object> gteMap = new HashMap<>();
                        gteMap.put("$gte", value2);
                        result.put(fieldName2, gteMap);
                        break;
                    case lt:
                        // 小于操作 - 使用$lt操作符
                        Map<String, Object> ltMap = new HashMap<>();
                        ltMap.put("$lt", value2);
                        result.put(fieldName2, ltMap);
                        break;
                    case lte:
                        // 小于等于操作 - 使用$lte操作符
                        Map<String, Object> lteMap = new HashMap<>();
                        lteMap.put("$lte", value2);
                        result.put(fieldName2, lteMap);
                        break;
                    case in:
                        // 包含操作 - 使用$in操作符
                        Map<String, Object> inMap = new HashMap<>();
                        inMap.put("$in", value2);
                        result.put(fieldName2, inMap);
                        break;
                    case nin:
                        // 不包含操作 - 使用$nin操作符
                        Map<String, Object> ninMap = new HashMap<>();
                        ninMap.put("$nin", value2);
                        result.put(fieldName2, ninMap);
                        break;
                    default:
                        // 未识别的操作符，忽略
                        break;
                }
            }
        } else if (filterExpression instanceof LogicalNode) {
            // 获取逻辑操作符和左右子节点
            LogicalOp logicalOp = ((LogicalNode) filterExpression).getOperator();
            Expression leftExpr = ((LogicalNode) filterExpression).getLeft();
            Expression rightExpr = ((LogicalNode) filterExpression).getRight();

            if (rightExpr != null) {
                // 二元逻辑操作符 (AND, OR)
                Map<String, Object> leftMap2 = new HashMap<>();
                parseFilterExpression(leftExpr, leftMap2);

                Map<String, Object> rightMap2 = new HashMap<>();
                parseFilterExpression(rightExpr, rightMap2);

                switch (logicalOp) {
                    case AND:
                        // AND操作 - 使用$and操作符
                        result.put("$and", Arrays.asList(leftMap2, rightMap2));
                        break;
                    case OR:
                        // OR操作 - 使用$or操作符
                        result.put("$or", Arrays.asList(leftMap2, rightMap2));
                        break;
                    default:
                        // 未识别的操作符，忽略
                        break;
                }
            } else if (leftExpr != null) {
                // 一元逻辑操作符 (NOT)
                Map<String, Object> leftMap2 = new HashMap<>();
                parseFilterExpression(leftExpr, leftMap2);

                switch (logicalOp) {
                    case NOT:
                        // NOT操作 - 使用$not操作符
                        result.put("$not", leftMap2);
                        break;
                    default:
                        // 未识别的操作符，忽略
                        break;
                }
            }
        }
    }
}