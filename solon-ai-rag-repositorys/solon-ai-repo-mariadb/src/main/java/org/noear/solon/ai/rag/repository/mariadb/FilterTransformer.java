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
package org.noear.solon.ai.rag.repository.mariadb;

import org.noear.solon.expression.Expression;
import org.noear.solon.expression.Transformer;
import org.noear.solon.expression.snel.*;

import java.util.Collection;

/**
 * MariaDB 过滤转换器，将表达式转换为 SQL WHERE 子句
 *
 * @author chw
 */
public class FilterTransformer implements Transformer<Boolean, String> {
    private static final FilterTransformer instance = new FilterTransformer();

    public static FilterTransformer getInstance() {
        return instance;
    }

    @Override
    public String transform(Expression<Boolean> filterExpression) {
        if (filterExpression == null) {
            return null;
        }

        try {
            StringBuilder buf = new StringBuilder();
            parseFilterExpression(filterExpression, buf);

            if (buf.length() == 0) {
                return null;
            }

            return buf.toString();
        } catch (Exception e) {
            System.err.println("Error processing filter expression: " + e.getMessage());
            return null;
        }
    }

    private void parseFilterExpression(Expression<Boolean> filterExpression, StringBuilder buf) {
        if (filterExpression == null) {
            return;
        }

        if (filterExpression instanceof VariableNode) {
            String name = ((VariableNode) filterExpression).getName();
            buf.append("`").append(name).append("`");
        } else if (filterExpression instanceof ConstantNode) {
            ConstantNode node = (ConstantNode) filterExpression;
            Object value = node.getValue();

            if (node.isCollection()) {
                buf.append("(");
                boolean first = true;
                for (Object item : (Collection<?>) value) {
                    if (!first) {
                        buf.append(", ");
                    }
                    if (item instanceof String) {
                        buf.append("'").append(item.toString().replace("'", "''")).append("'");
                    } else {
                        buf.append(item);
                    }
                    first = false;
                }
                buf.append(")");
            } else if (value instanceof String) {
                buf.append("'").append(value.toString().replace("'", "''")).append("'");
            } else {
                buf.append(value);
            }
        } else if (filterExpression instanceof ComparisonNode) {
            ComparisonNode node = (ComparisonNode) filterExpression;
            ComparisonOp operator = node.getOperator();
            Expression left = node.getLeft();
            Expression right = node.getRight();

            switch (operator) {
                case eq:
                    parseFilterExpression(left, buf);
                    buf.append(" = ");
                    parseFilterExpression(right, buf);
                    break;
                case neq:
                    parseFilterExpression(left, buf);
                    buf.append(" != ");
                    parseFilterExpression(right, buf);
                    break;
                case gt:
                    parseFilterExpression(left, buf);
                    buf.append(" > ");
                    parseFilterExpression(right, buf);
                    break;
                case gte:
                    parseFilterExpression(left, buf);
                    buf.append(" >= ");
                    parseFilterExpression(right, buf);
                    break;
                case lt:
                    parseFilterExpression(left, buf);
                    buf.append(" < ");
                    parseFilterExpression(right, buf);
                    break;
                case lte:
                    parseFilterExpression(left, buf);
                    buf.append(" <= ");
                    parseFilterExpression(right, buf);
                    break;
                case in:
                    parseFilterExpression(left, buf);
                    buf.append(" IN ");
                    parseFilterExpression(right, buf);
                    break;
                case nin:
                    parseFilterExpression(left, buf);
                    buf.append(" NOT IN ");
                    parseFilterExpression(right, buf);
                    break;
                default:
                    parseFilterExpression(left, buf);
                    buf.append(" = ");
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
                parseFilterExpression(left, buf);

                switch (operator) {
                    case AND:
                        buf.append(" AND ");
                        break;
                    case OR:
                        buf.append(" OR ");
                        break;
                    default:
                        buf.append(" AND ");
                        break;
                }

                parseFilterExpression(right, buf);
            } else {
                switch (operator) {
                    case NOT:
                        buf.append("NOT ");
                        break;
                    default:
                        break;
                }
                parseFilterExpression(left, buf);
            }

            buf.append(")");
        }
    }
}
