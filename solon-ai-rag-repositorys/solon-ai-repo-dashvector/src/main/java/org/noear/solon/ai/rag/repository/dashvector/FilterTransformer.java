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
package org.noear.solon.ai.rag.repository.dashvector;

import java.util.Collection;
import java.util.StringJoiner;

import org.noear.solon.expression.Expression;
import org.noear.solon.expression.Transformer;
import org.noear.solon.expression.snel.ComparisonNode;
import org.noear.solon.expression.snel.ConstantNode;
import org.noear.solon.expression.snel.LogicalNode;
import org.noear.solon.expression.snel.LogicalOp;
import org.noear.solon.expression.snel.VariableNode;

/**
 * DashVector过滤转换器
 *
 * @author 小奶奶花生米
 */
public class FilterTransformer implements Transformer<Boolean, String> {
    private static FilterTransformer instance = new FilterTransformer();

    public static FilterTransformer getInstance() {
        return instance;
    }

    /**
     * 解析QueryCondition中的过滤表达式，转换为DashVector支持的过滤条件格式
     *
     * @param filterExpr 查询条件
     * @return DashVector过滤表达式，格式为字符串，如：age > 18 and weight > 65.0 and male = true
     */
    @Override
    public String transform(Expression<Boolean> filterExpr) {
        if (filterExpr == null) {
            return null;
        }

        try {
            return parseFilterExpression(filterExpr);
        } catch (Exception e) {
            System.err.println("Error processing filter expression: " + e.getMessage());
            e.printStackTrace();
            return null;
        }
    }

    /**
     * 解析Expression对象形式的过滤表达式
     * @param filterExpression 过滤表达式对象
     * @return DashVector过滤表达式字符串
     */
    private String parseFilterExpression(Expression<Boolean> filterExpression) {
        if (filterExpression == null) {
            return "";
        }

        if (filterExpression instanceof ComparisonNode) {
            ComparisonNode compNode = (ComparisonNode) filterExpression;
            String fieldName = getFieldName(compNode.getLeft());
            String value = formatValue(((ConstantNode) compNode.getRight()).getValue());

            switch (compNode.getOperator()) {
                case eq:
                    return fieldName + " = " + value;
                case neq:
                    return fieldName + " != " + value;
                case gt:
                    return fieldName + " > " + value;
                case gte:
                    return fieldName + " >= " + value;
                case lt:
                    return fieldName + " < " + value;
                case lte:
                    return fieldName + " <= " + value;
                case in:
                    return fieldName + " in " + value;
                case nin:
                    return fieldName + " not in " + value;
                default:
                    throw new IllegalArgumentException("Unsupported operator: " + compNode.getOperator());
            }
        } else if (filterExpression instanceof LogicalNode) {
            LogicalNode logicalNode = (LogicalNode) filterExpression;
            String left = parseFilterExpression(logicalNode.getLeft());
            
            if (logicalNode.getOperator() == LogicalOp.NOT) {
                return "not (" + left + ")";
            }
            
            String right = parseFilterExpression(logicalNode.getRight());
            String op = logicalNode.getOperator() == LogicalOp.AND ? " and " : " or ";
            
            return "(" + left + op + right + ")";
        }

        throw new IllegalArgumentException("Unsupported expression type: " + filterExpression.getClass());
    }

    /**
     * 获取字段名
     */
    private String getFieldName(Expression<?> expr) {
        if (expr instanceof VariableNode) {
            return ((VariableNode) expr).getName();
        }
        throw new IllegalArgumentException("Expected field name, got: " + expr.getClass());
    }

    /**
     * 格式化值
     */
    private String formatValue(Object value) {
        if (value == null) {
            return "null";
        }

        if (value instanceof String) {
            return "'" + ((String) value).replace("'", "\\'") + "'";
        }

        if (value instanceof Collection<?>) {
            StringJoiner joiner = new StringJoiner(", ", "(", ")");
            for (Object item : (Collection<?>) value) {
                joiner.add(formatValue(item));
            }
            return joiner.toString();
        }

        if (value instanceof Boolean || value instanceof Number) {
            return value.toString();
        }

        throw new IllegalArgumentException("Unsupported value type: " + value.getClass());
    }
}
