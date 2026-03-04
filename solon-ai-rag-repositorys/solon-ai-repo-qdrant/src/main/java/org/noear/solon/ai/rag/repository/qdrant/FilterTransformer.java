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
package org.noear.solon.ai.rag.repository.qdrant;

import io.qdrant.client.grpc.Common;
import io.qdrant.client.grpc.Points;
import io.qdrant.client.grpc.Common.Filter;

import org.noear.solon.Utils;
import org.noear.solon.expression.Expression;
import org.noear.solon.expression.Transformer;
import org.noear.solon.expression.snel.ComparisonNode;
import org.noear.solon.expression.snel.ConstantNode;
import org.noear.solon.expression.snel.LogicalNode;
import org.noear.solon.expression.snel.VariableNode;

import java.util.ArrayList;
import java.util.List;

import static io.qdrant.client.grpc.Common.Condition;
import static io.qdrant.client.grpc.Common.Range;

/**
 * 过滤转换器
 *
 * @author noear
 * @since 3.1
 */
public class FilterTransformer implements Transformer<Boolean, Filter> {
    private static FilterTransformer instance = new FilterTransformer();

    public static FilterTransformer getInstance() {
        return instance;
    }

    @Override
    public Filter transform(Expression<Boolean> source) {
        return this.transformDo(source);
    }

    protected Filter transformDo(Expression operand) {
        Filter.Builder context = Filter.newBuilder();
        List<Condition> mustClauses = new ArrayList<Condition>();
        List<Condition> shouldClauses = new ArrayList<Condition>();
        List<Condition> mustNotClauses = new ArrayList<Condition>();

        if (operand instanceof LogicalNode) {
            LogicalNode logicalNode = (LogicalNode) operand;

            switch (logicalNode.getOperator()) {
                case NOT:
                    mustNotClauses.add(io.qdrant.client.ConditionFactory.filter(transformDo(logicalNode.getLeft())));
                    break;
                case AND:
                    mustClauses.add(io.qdrant.client.ConditionFactory.filter(transformDo(logicalNode.getLeft())));
                    mustClauses.add(io.qdrant.client.ConditionFactory.filter(transformDo(logicalNode.getRight())));
                    break;
                case OR:
                    shouldClauses.add(io.qdrant.client.ConditionFactory.filter(transformDo(logicalNode.getLeft())));
                    shouldClauses.add(io.qdrant.client.ConditionFactory.filter(transformDo(logicalNode.getRight())));
                    break;
            }
        } else if (operand instanceof ComparisonNode) {
            ComparisonNode comparisonNode = (ComparisonNode) operand;

            if (comparisonNode.getRight() instanceof ConstantNode) {
                mustClauses.add(parseComparison(comparisonNode));
            } else {
                throw new RuntimeException("Non logical expression must have Value right argument!");
            }
        }

        return context.addAllMust(mustClauses).addAllShould(shouldClauses).addAllMustNot(mustNotClauses).build();
    }

    protected Condition parseComparison(ComparisonNode expr) {
        VariableNode left = (VariableNode) expr.getLeft();
        ConstantNode right = (ConstantNode) expr.getRight();

        switch (expr.getOperator()) {
            case eq:
                return buildEqCondition(left, right);
            case neq:
                return buildNeCondition(left, right);
            case gt:
                return buildGtCondition(left, right);
            case gte:
                return buildGteCondition(left, right);
            case lt:
                return buildLtCondition(left, right);
            case lte:
                return buildLteCondition(left, right);
            case in:
                return buildInCondition(left, right);
            case nin:
                return buildNInCondition(left, right);
            default:
                throw new RuntimeException("Unsupported expression type: " + expr.getOperator());
        }
    }

    protected Condition buildEqCondition(VariableNode key, ConstantNode value) {
        String identifier = key.getName();
        if (value.getValue() instanceof String) {
            String valueStr = value.getValue().toString();
            return io.qdrant.client.ConditionFactory.matchKeyword(identifier, valueStr);
        } else if (value.getValue() instanceof Number) {
            long lValue = ((Number) value.getValue()).longValue();
            return io.qdrant.client.ConditionFactory.match(identifier, lValue);
        }

        throw new IllegalArgumentException("Invalid value type for EQ. Can either be a string or Number");

    }

    protected Condition buildNeCondition(VariableNode key, ConstantNode value) {
        String identifier = key.getName();
        if (value.getValue() instanceof String) {
            String valueStr = value.getValue().toString();

            return io.qdrant.client.ConditionFactory.filter(Filter.newBuilder()
                    .addMustNot(io.qdrant.client.ConditionFactory.matchKeyword(identifier, valueStr))
                    .build());
        } else if (value.getValue() instanceof Number) {
            long lValue = ((Number) value.getValue()).longValue();
            Condition condition = io.qdrant.client.ConditionFactory.match(identifier, lValue);
            return io.qdrant.client.ConditionFactory.filter(Filter.newBuilder().addMustNot(condition).build());
        }

        throw new IllegalArgumentException("Invalid value type for NEQ. Can either be a string or Number");

    }

    protected Condition buildGtCondition(VariableNode key, ConstantNode value) {
        String identifier = key.getName();
        if (value.getValue() instanceof Number) {
            Double dvalue = ((Number) value.getValue()).doubleValue();
            return io.qdrant.client.ConditionFactory.range(identifier, Range.newBuilder().setGt(dvalue).build());
        }
        throw new RuntimeException("Unsupported value type for GT condition. Only supports Number");

    }

    protected Condition buildLtCondition(VariableNode key, ConstantNode value) {
        String identifier = key.getName();
        if (value.getValue() instanceof Number) {
            Double dvalue = ((Number) value.getValue()).doubleValue();
            return io.qdrant.client.ConditionFactory.range(identifier, Range.newBuilder().setLt(dvalue).build());
        }
        throw new RuntimeException("Unsupported value type for LT condition. Only supports Number");

    }

    protected Condition buildGteCondition(VariableNode key, ConstantNode value) {
        String identifier = key.getName();
        if (value.getValue() instanceof Number) {
            Double dvalue = ((Number) value.getValue()).doubleValue();
            return io.qdrant.client.ConditionFactory.range(identifier, Range.newBuilder().setGte(dvalue).build());
        }
        throw new RuntimeException("Unsupported value type for GTE condition. Only supports Number");

    }

    protected Condition buildLteCondition(VariableNode key, ConstantNode value) {
        String identifier = key.getName();
        if (value.getValue() instanceof Number) {
            Double dvalue = ((Number) value.getValue()).doubleValue();
            return io.qdrant.client.ConditionFactory.range(identifier, Range.newBuilder().setLte(dvalue).build());
        }

        throw new RuntimeException("Unsupported value type for LTE condition. Only supports Number");

    }

    protected Condition buildInCondition(VariableNode key, ConstantNode value) {
        if (value.getValue() instanceof List) {
            List valueList = (List) value.getValue();

            if (Utils.isNotEmpty(valueList)) {
                Object firstValue = valueList.get(0);
                String identifier = key.getName();

                if (firstValue instanceof String) {
                    // If the first value is a string, then all values should be strings
                    List<String> stringValues = new ArrayList<String>();
                    for (Object valueObj : valueList) {
                        stringValues.add(valueObj.toString());
                    }
                    return io.qdrant.client.ConditionFactory.matchKeywords(identifier, stringValues);
                } else if (firstValue instanceof Number) {
                    // If the first value is a number, then all values should be numbers
                    List<Long> longValues = new ArrayList<Long>();
                    for (Object valueObj : valueList) {
                        Long longValue = Long.parseLong(valueObj.toString());
                        longValues.add(longValue);
                    }
                    return io.qdrant.client.ConditionFactory.matchValues(identifier, longValues);
                } else {
                    throw new RuntimeException("Unsupported value in IN value list. Only supports String or Number");
                }
            }
        }

        throw new RuntimeException(
                "Unsupported value type for IN condition. Only supports non-empty List of String or Number");

    }

    protected Condition buildNInCondition(VariableNode key, ConstantNode value) {
        if (value.getValue() instanceof List) {
            List valueList = (List) value.getValue();

            if (Utils.isNotEmpty(valueList)) {
                Object firstValue = valueList.get(0);
                String identifier = key.getName();

                if (firstValue instanceof String) {
                    // If the first value is a string, then all values should be strings
                    List<String> stringValues = new ArrayList<String>();
                    for (Object valueObj : valueList) {
                        stringValues.add(valueObj.toString());
                    }
                    return io.qdrant.client.ConditionFactory.matchExceptKeywords(identifier, stringValues);
                } else if (firstValue instanceof Number) {
                    // If the first value is a number, then all values should be numbers
                    List<Long> longValues = new ArrayList<Long>();
                    for (Object valueObj : valueList) {
                        Long longValue = Long.parseLong(valueObj.toString());
                        longValues.add(longValue);
                    }
                    return io.qdrant.client.ConditionFactory.matchExceptValues(identifier, longValues);
                } else {
                    throw new RuntimeException("Unsupported value in NIN value list. Only supports String or Number");
                }
            }
        }

        throw new RuntimeException(
                "Unsupported value type for NIN condition. Only supports non-empty List of String or Number");

    }
}