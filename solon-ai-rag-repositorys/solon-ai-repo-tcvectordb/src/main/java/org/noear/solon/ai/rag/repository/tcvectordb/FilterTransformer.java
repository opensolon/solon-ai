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
package org.noear.solon.ai.rag.repository.tcvectordb;

import com.tencent.tcvectordb.model.param.dml.Filter;
import org.noear.solon.Utils;
import org.noear.solon.expression.Expression;
import org.noear.solon.expression.Transformer;
import org.noear.solon.expression.snel.*;

/**
 * 过滤转换器
 *
 * @author noear
 * @since 3.1
 */
public class FilterTransformer implements Transformer<Boolean,Filter> {
    private static FilterTransformer instance = new FilterTransformer();

    public static FilterTransformer getInstance() {
        return instance;
    }

    /**
     * 将Expression对象转换为腾讯云向量数据库的Filter对象
     * 支持的逻辑运算符：and、or、not
     * 支持的字符串操作符: =, !=, in, not in
     * 支持的数值操作符: >, >=, =, <, <=, !=, in, not in
     *
     * @param filterExpr 过滤表达式对象
     * @return 腾讯云向量数据库的Filter对象
     */
    @Override
    public Filter transform(Expression<Boolean> filterExpr) {
        if (filterExpr == null) {
            return null;
        }

        try {
            // 将Expression转换为VectorDB支持的过滤表达式字符串
            String filterString = toFilterString(filterExpr);
            if (Utils.isEmpty(filterString)) {
                return null;
            }

            // 创建Filter对象，使用字符串表达式
            return new Filter(filterString);
        } catch (Exception e) {
            System.err.println("Error processing filter expression: " + e.getMessage());
            e.printStackTrace();
            return null;
        }
    }

    /**
     * 将Expression对象转换为字符串形式的过滤表达式
     * 根据腾讯云向量数据库过滤表达式语法要求进行转换
     *
     * @param filterExpr 过滤表达式对象
     * @return 符合腾讯云向量数据库语法的过滤表达式字符串
     */
    private String toFilterString(Expression<Boolean> filterExpr) {
        if (filterExpr == null) {
            return null;
        }
        StringBuilder buf = new StringBuilder();
        parseFilterExpressionToString(filterExpr, buf);
        return buf.toString();
    }



    /**
     * 递归解析表达式，并将其转换为符合腾讯云向量数据库语法的字符串
     * 支持以下表达式类型：
     * 1. 逻辑运算表达式：and、or、not
     * 2. 字符串类型表达式：in、not in、=、!=（字符串值需要用双引号括起来）
     * 3. 数值类型表达式：>、>=、=、<、<=、!=、in、not in
     * <p>
     * 示例格式：
     * - game_tag = "Robert" and (video_tag = "dance" or video_tag = "music")
     * - game_tag in("Detective","Action Roguelike","Party-Based RPG","1980s")
     * - expired_time > 1623388524
     *
     * @param filterExpression 过滤表达式
     * @param buf              字符串构建器
     */
    private void parseFilterExpressionToString(Expression<Boolean> filterExpression, StringBuilder buf) {
        if (filterExpression == null) {
            return;
        }

        if (filterExpression instanceof VariableNode) {
            // 处理变量节点
            String fieldName = ((VariableNode) filterExpression).getName();
            buf.append(fieldName);
        } else if (filterExpression instanceof ConstantNode) {
            // 处理常量节点
            Object value = ((ConstantNode) filterExpression).getValue();
            if (value instanceof String) {
                buf.append("\"").append(value).append("\"");
            } else if (((ConstantNode) filterExpression).isCollection()) {
                buf.append("(");
                for (Object item : (Iterable<?>) value) {
                    if (item instanceof String) {
                        buf.append("\"").append(item).append("\"");
                    } else {
                        buf.append(item);
                    }
                    buf.append(", ");
                }
                if (buf.charAt(buf.length() - 2) == ',') {
                    buf.setLength(buf.length() - 2);
                }
                buf.append(")");
            } else {
                buf.append(value);
            }
        } else if (filterExpression instanceof ComparisonNode) {
            // 处理比较节点
            ComparisonNode compNode = (ComparisonNode) filterExpression;
            ComparisonOp operator = compNode.getOperator();
            Expression left = compNode.getLeft();
            Expression right = compNode.getRight();

            // 可能需要括号来处理优先级
            boolean needParentheses = (left instanceof LogicalNode) || (right instanceof LogicalNode);

            if (needParentheses) {
                buf.append("(");
            }

            // 处理左侧表达式
            parseFilterExpressionToString(left, buf);

            // 处理操作符
            switch (operator) {
                case eq:
                    buf.append(" = ");
                    break;
                case neq:
                    buf.append(" != ");
                    break;
                case gt:
                    buf.append(" > ");
                    break;
                case gte:
                    buf.append(" >= ");
                    break;
                case lt:
                    buf.append(" < ");
                    break;
                case lte:
                    buf.append(" <= ");
                    break;
                case in:
                    buf.append(" in ");
                    break;
                case nin:
                    buf.append(" not in ");
                    break;
                default:
                    buf.append(" = ");
                    break;
            }

            // 处理右侧表达式
            parseFilterExpressionToString(right, buf);

            if (needParentheses) {
                buf.append(")");
            }
        } else if (filterExpression instanceof LogicalNode) {
            // 处理逻辑节点
            LogicalNode logicalNode = (LogicalNode) filterExpression;
            LogicalOp logicalOp = logicalNode.getOperator();
            Expression leftExpr = logicalNode.getLeft();
            Expression rightExpr = logicalNode.getRight();

            if (rightExpr != null) {
                // 二元逻辑操作符 (AND, OR)
                boolean needParentheses = !(buf.length() == 0);

                if (needParentheses) {
                    buf.append("(");
                }

                // 处理左侧表达式
                parseFilterExpressionToString(leftExpr, buf);

                // 处理操作符
                switch (logicalOp) {
                    case AND:
                        buf.append(" and ");
                        break;
                    case OR:
                        buf.append(" or ");
                        break;
                    default:
                        buf.append(" and ");
                        break;
                }

                // 处理右侧表达式
                parseFilterExpressionToString(rightExpr, buf);

                if (needParentheses) {
                    buf.append(")");
                }
            } else if (leftExpr != null && logicalOp == LogicalOp.NOT) {
                // 一元逻辑操作符 (NOT)
                buf.append("not(");
                parseFilterExpressionToString(leftExpr, buf);
                buf.append(")");
            }
        }
    }
}