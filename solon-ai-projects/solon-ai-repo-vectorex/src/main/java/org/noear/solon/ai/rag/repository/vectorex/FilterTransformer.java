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
package org.noear.solon.ai.rag.repository.vectorex;

import io.github.javpower.vectorexclient.builder.QueryBuilder;
import org.noear.solon.expression.Expression;
import org.noear.solon.expression.Transformer;
import org.noear.solon.expression.snel.ComparisonNode;
import org.noear.solon.expression.snel.ConstantNode;
import org.noear.solon.expression.snel.LogicalNode;
import org.noear.solon.expression.snel.VariableNode;

/**
 * @author noear
 * @since 3.1
 */
public class FilterTransformer implements Transformer<Boolean, QueryBuilder> {
    private String collectionName;

    public FilterTransformer(String collectionName) {
        this.collectionName = collectionName;
    }

    @Override
    public QueryBuilder transform(Expression<Boolean> source) {
        QueryBuilder builder = transformDo(source);
        if (builder == null) {
            return QueryBuilder.lambda(collectionName);
        } else {
            return builder;
        }
    }

    private String getName(Expression node) {
        if (node instanceof VariableNode) {
            return ((VariableNode) node).getName();
        }

        throw new IllegalStateException("can't get name of " + node);
    }

    private Object getValue(Expression node) {
        if (node instanceof ConstantNode) {
            return ((ConstantNode) node).getValue();
        }

        throw new IllegalStateException("can't get value of " + node);
    }

    private QueryBuilder transformDo(Expression<Boolean> node) {
        if (node == null) {
            return null;
        }

        if (node instanceof ComparisonNode) {
            ComparisonNode compNode = (ComparisonNode) node;
            QueryBuilder compQb = QueryBuilder.lambda(collectionName);
            String name = getName(compNode.getLeft());
            Object value = getValue(compNode.getRight());

            switch (compNode.getOperator()) {
                case eq:
                    return compQb.eq(name, value);
                case gt:
                    return compQb.gt(name, (Comparable) value);
                case gte:
                    return compQb.ge(name, (Comparable) value);
                case lt:
                    return compQb.lt(name, (Comparable) value);
                case lte:
                    return compQb.le(name, (Comparable) value);
                case lk:
                    return compQb.like(name, (String) value);
                default: {
                    throw new IllegalStateException("not support " + compNode.getOperator());
                }
            }

        } else if (node instanceof LogicalNode) {
            LogicalNode opNode = (LogicalNode) node;

            switch (opNode.getOperator()) {
                case and: {
                    QueryBuilder leftQb = transformDo(opNode.getLeft());
                    QueryBuilder rightQb = transformDo(opNode.getRight());
                    return leftQb.and(rightQb);
                }
                case or: {
                    QueryBuilder leftQb = transformDo(opNode.getLeft());
                    QueryBuilder rightQb = transformDo(opNode.getRight());
                    return leftQb.or(rightQb);
                }
                default: {
                    throw new IllegalStateException("not support " + opNode.getOperator());
                }
            }
        }

        return null;
    }
}
