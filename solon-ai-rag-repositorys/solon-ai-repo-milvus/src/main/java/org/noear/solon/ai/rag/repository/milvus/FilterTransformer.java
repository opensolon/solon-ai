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
package org.noear.solon.ai.rag.repository.milvus;

import org.noear.solon.expression.Expression;
import org.noear.solon.expression.Transformer;
import org.noear.solon.expression.snel.ComparisonNode;
import org.noear.solon.expression.snel.ComparisonOp;
import org.noear.solon.expression.snel.ConstantNode;
import org.noear.solon.expression.snel.LogicalNode;
import org.noear.solon.expression.snel.VariableNode;

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
        StringBuilder buf = new StringBuilder();
        parseFilterExpression(filterExpression, buf);
        return buf.toString();
    }

    private void parseFilterExpression(Expression<Boolean> filterExpression, StringBuilder buf) {
        if (filterExpression instanceof VariableNode) {
            buf.append("metadata[\"").append(((VariableNode) filterExpression).getName()).append("\"]");
        } else if (filterExpression instanceof ConstantNode) {
            Object value = ((ConstantNode) filterExpression).getValue();
            // 判断是否为Collection类型
            if (((ConstantNode) filterExpression).isCollection()) {
                buf.append("[");
                boolean first = true;
                for (Object item : (Iterable<?>) value) {
                    if (first == false) {
                        buf.append(", ");
                    }
                    first = false;

                    if (item instanceof String) {
                        buf.append("\"").append(item).append("\"");
                    } else {
                        buf.append(item);
                    }
                }
                buf.append("]");
            } else if (value instanceof String) {
                buf.append("\"").append(value).append("\"");
            } else {
                buf.append(value);
            }
        } else if (filterExpression instanceof ComparisonNode) {
            ComparisonNode compNode = (ComparisonNode) filterExpression;
            buf.append("(");
            parseFilterExpression(compNode.getLeft(), buf);
            buf.append(" ").append(operatorCode(compNode.getOperator())).append(" ");
            parseFilterExpression(compNode.getRight(), buf);
            buf.append(")");
        } else if (filterExpression instanceof LogicalNode) {
            LogicalNode opNode = (LogicalNode) filterExpression;
            buf.append("(");
            if (opNode.getRight() != null) {
                parseFilterExpression(opNode.getLeft(), buf);
                buf.append(" ").append(opNode.getOperator().getCode().toLowerCase()).append(" ");
                parseFilterExpression(opNode.getRight(), buf);
            } else {
                buf.append(opNode.getOperator().getCode()).append(" ");
                parseFilterExpression(opNode.getLeft(), buf);
            }
            buf.append(")");
        }
    }

    /**
     * 比较运算符在 Milvus 表达式里的写法
     *
     * <p>{@link ComparisonOp#nin} 的代号是 {@code "NO IN"}（上游笔误），直接小写输出会得到
     * {@code no in} —— Milvus 认的是 {@code not in}。</p>
     */
    private static String operatorCode(ComparisonOp operator) {
        if (operator == ComparisonOp.nin) {
            return "not in";
        }

        return operator.getCode().toLowerCase();
    }
}