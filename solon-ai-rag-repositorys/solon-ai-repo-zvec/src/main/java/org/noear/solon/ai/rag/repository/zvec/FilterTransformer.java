/*
 * Copyright 2017-2026 noear.org and authors
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
package org.noear.solon.ai.rag.repository.zvec;

import org.noear.solon.expression.Expression;
import org.noear.solon.expression.Transformer;
import org.noear.solon.expression.snel.ComparisonNode;
import org.noear.solon.expression.snel.ComparisonOp;
import org.noear.solon.expression.snel.ConstantNode;
import org.noear.solon.expression.snel.LogicalNode;
import org.noear.solon.expression.snel.VariableNode;

import java.util.Set;

/**
 * Zvec 过滤转换器（转换为 Zvec 的 SQL 风格过滤表达式）
 *
 * <p>Zvec 的过滤表达式只支持已声明为标量列（含倒排索引）的字段，且语法为
 * <code>column operator value</code>（字符串用单引号、布尔用小写真值）。</p>
 *
 * @author noear
 * @since 4.2
 */
public class FilterTransformer implements Transformer<Boolean, String> {
    private static final FilterTransformer instance = new FilterTransformer();

    public static FilterTransformer getInstance() {
        return instance;
    }

    /**
     * 转换（不做字段校验）
     */
    @Override
    public String transform(Expression<Boolean> filterExpression) {
        return transform(filterExpression, null);
    }

    /**
     * 转换
     *
     * @param filterExpression 过滤表达式
     * @param columns          可下推的列名（null 表示不校验）；表达式里出现未知列时返回 null（由上层二次过滤）
     */
    public String transform(Expression<Boolean> filterExpression, Set<String> columns) {
        if (filterExpression == null) {
            return null;
        }

        StringBuilder buf = new StringBuilder();

        try {
            parseFilterExpression(filterExpression, columns, buf);
        } catch (UnsupportedFilterException e) {
            return null;
        }

        if (buf.length() == 0) {
            return null;
        }

        return buf.toString();
    }

    private void parseFilterExpression(Expression<Boolean> filterExpression, Set<String> columns, StringBuilder buf) {
        if (filterExpression instanceof VariableNode) {
            String name = ((VariableNode) filterExpression).getName();

            if (columns != null && columns.contains(name) == false) {
                // Zvec 遇到未知列会直接报错，这里改为不下推（由上层内存二次过滤）
                throw new UnsupportedFilterException();
            }

            buf.append(name);
        } else if (filterExpression instanceof ConstantNode) {
            ConstantNode node = (ConstantNode) filterExpression;
            Object value = node.getValue();

            if (node.isCollection()) {
                buf.append("(");
                boolean first = true;
                for (Object item : (Iterable<?>) value) {
                    if (first == false) {
                        buf.append(", ");
                    }
                    first = false;
                    appendValue(item, buf);
                }
                buf.append(")");
            } else {
                appendValue(value, buf);
            }
        } else if (filterExpression instanceof ComparisonNode) {
            ComparisonNode node = (ComparisonNode) filterExpression;

            buf.append("(");
            parseFilterExpression(node.getLeft(), columns, buf);
            buf.append(" ").append(operatorCode(node.getOperator())).append(" ");
            parseFilterExpression(node.getRight(), columns, buf);
            buf.append(")");
        } else if (filterExpression instanceof LogicalNode) {
            LogicalNode node = (LogicalNode) filterExpression;

            buf.append("(");
            if (node.getRight() != null) {
                parseFilterExpression(node.getLeft(), columns, buf);
                buf.append(" ").append(node.getOperator().getCode()).append(" ");
                parseFilterExpression(node.getRight(), columns, buf);
            } else {
                //zvec 的过滤语法没有 NOT，不下推
                throw new UnsupportedFilterException();
            }
            buf.append(")");
        } else {
            throw new UnsupportedFilterException();
        }
    }

    private static void appendValue(Object value, StringBuilder buf) {
        if (value instanceof String) {
            buf.append("'").append(((String) value).replace("'", "\\'")).append("'");
        } else if (value instanceof Boolean) {
            buf.append(value.toString().toLowerCase());
        } else if (value instanceof Enum) {
            buf.append("'").append(value).append("'");
        } else if (value == null) {
            throw new UnsupportedFilterException();
        } else {
            buf.append(value);
        }
    }

    /**
     * 比较运算符在 Zvec 表达式里的写法
     */
    private static String operatorCode(ComparisonOp operator) {
        switch (operator) {
            case eq:
                return "=";
            case neq:
                return "!=";
            case gt:
                return ">";
            case gte:
                return ">=";
            case lt:
                return "<";
            case lte:
                return "<=";
            case in:
                return "in";
            case nin:
                return "not in";
            case lk:
            case nlk:
                //zvec 的 like 通配符语义（% 还是 *）在不同版本间不一致，不下推
                throw new UnsupportedFilterException();
            default:
                //其余运算符不支持下推
                throw new UnsupportedFilterException();
        }
    }

    /**
     * 不可下推的过滤表达式
     */
    private static class UnsupportedFilterException extends RuntimeException {
        UnsupportedFilterException() {
            super(null, null, false, false);
        }
    }
}
