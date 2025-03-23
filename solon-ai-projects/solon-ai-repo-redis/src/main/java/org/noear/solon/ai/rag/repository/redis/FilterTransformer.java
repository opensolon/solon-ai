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
package org.noear.solon.ai.rag.repository.redis;

import org.noear.solon.expression.Expression;
import org.noear.solon.expression.Transformer;
import org.noear.solon.expression.snel.*;

import java.util.Collection;

/**
 * 过滤转换器
 *
 * @author noear
 * @since 3.1
 */
public class FilterTransformer implements Transformer<Boolean, String> {
    private static FilterTransformer instance = new FilterTransformer();

    public static FilterTransformer getInstance() {
        return instance;
    }

    @Override
    public String transform(Expression<Boolean> filterExpression) {
        if (filterExpression == null) {
            return "*";
        }

        try {
            StringBuilder buf = new StringBuilder();
            parseFilterExpression(filterExpression, buf);

            if (buf.length() == 0) {
                return "*";
            }

            return buf.toString();
        } catch (Exception e) {
            System.err.println("Error processing filter expression: " + e.getMessage());
            return "*";
        }
    }

    /**
     * 解析QueryCondition中的filterExpression，转换为Redis Search语法
     *
     * @param filterExpression
     * @param buf
     */
    private void parseFilterExpression(Expression<Boolean> filterExpression, StringBuilder buf) {
        if (filterExpression == null) {
            return;
        }

        if (filterExpression instanceof VariableNode) {
            // 变量节点，获取字段名 - 为Redis添加@前缀
            String name = ((VariableNode) filterExpression).getName();
            buf.append("@").append(name);
        } else if (filterExpression instanceof ConstantNode) {
            ConstantNode node = (ConstantNode) filterExpression;
            // 常量节点，获取值
            Object value = node.getValue();

            if (node.isCollection()) {
                // 集合使用Redis的OR语法 {val1|val2|val3}
                buf.append("{");
                boolean first = true;
                for (Object item : (Collection<?>) value) {
                    if (!first) {
                        buf.append("|"); // Redis 使用 | 分隔OR条件
                    }
                    buf.append(item);
                    first = false;
                }
                buf.append("}");
            } else if (value instanceof String) {
                // 字符串值使用大括号
                buf.append("{").append(value).append("}");
            } else {
                buf.append(value);
            }
        } else if (filterExpression instanceof ComparisonNode) {
            ComparisonNode node = (ComparisonNode) filterExpression;
            ComparisonOp operator = node.getOperator();
            Expression left = node.getLeft();
            Expression right = node.getRight();

            // 比较节点
            switch (operator) {
                case eq:
                    parseFilterExpression(left, buf);
                    buf.append(":");
                    parseFilterExpression(right, buf);
                    break;
                case neq:
                    buf.append("-");
                    parseFilterExpression(left, buf);
                    buf.append(":");
                    parseFilterExpression(right, buf);
                    break;
                case gt:
                    parseFilterExpression(left, buf);
                    buf.append(":[");
                    parseFilterExpression(right, buf);
                    buf.append(" +inf]");
                    break;
                case gte:
                    parseFilterExpression(left, buf);
                    buf.append(":[");
                    parseFilterExpression(right, buf);
                    buf.append(" +inf]");
                    break;
                case lt:
                    parseFilterExpression(left, buf);
                    buf.append(":[-inf ");
                    parseFilterExpression(right, buf);
                    buf.append("]");
                    break;
                case lte:
                    parseFilterExpression(left, buf);
                    buf.append(":[-inf ");
                    parseFilterExpression(right, buf);
                    buf.append("]");
                    break;
                case in:
                    parseFilterExpression(left, buf);
                    buf.append(":");
                    parseFilterExpression(right, buf);
                    break;
                case nin:
                    buf.append("-");
                    parseFilterExpression(left, buf);
                    buf.append(":");
                    parseFilterExpression(right, buf);
                    break;
                default:
                    parseFilterExpression(left, buf);
                    buf.append(":");
                    parseFilterExpression(right, buf);
                    break;
            }
        } else if (filterExpression instanceof LogicalNode) {
            LogicalNode node = (LogicalNode) filterExpression;
            LogicalOp operator = node.getOperator();
            Expression left = node.getLeft();
            Expression right = node.getRight();

            buf.append("(");

            if (right != null) {
                // 二元操作符 (AND, OR)
                parseFilterExpression(left, buf);

                switch (operator) {
                    case and:
                        buf.append(" "); // Redis Search 使用空格表示 AND
                        break;
                    case or:
                        buf.append(" | "); // Redis Search 使用 | 表示 OR
                        break;
                    default:
                        // 其他操作符，默认用空格
                        buf.append(" ");
                        break;
                }

                parseFilterExpression(right, buf);
            } else {
                // 一元操作符 (NOT)
                switch (operator) {
                    case not:
                        buf.append("-"); // Redis Search 使用 - 表示 NOT
                        break;
                    default:
                        // 其他一元操作符，不添加前缀
                        break;
                }
                parseFilterExpression(left, buf);
            }

            buf.append(")");
        }
    }
}