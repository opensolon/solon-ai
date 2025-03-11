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
package org.noear.solon.expression.query;

import org.noear.solon.expression.ExpressionContext;

/**
 * 比较运算节点（如 >, <, ==）
 *
 * @author noear
 * @since 3.1
 */
public class ComparisonNode implements ConditionNode {
    private ComparisonOp operator; // 比较运算符，如 ">", "<", "=="
    private FieldNode field;
    private ValueNode value;

    /**
     * 获取操作符
     */
    public ComparisonOp getOperator() {
        return operator;
    }

    /**
     * 获取字段
     */
    public FieldNode getField() {
        return field;
    }

    /**
     * 获取值
     */
    public ValueNode getValue() {
        return value;
    }

    public ComparisonNode(ComparisonOp operator, FieldNode field, ValueNode value) {
        this.operator = operator;
        this.field = field;
        this.value = value;
    }

    @Override
    public Boolean evaluate(ExpressionContext context) {
        Object fieldValue = context.get(field.getFieldName());
        Object conditionValue = value.getValue();

        switch (operator) {
            case gt:
                return ((Comparable) fieldValue).compareTo(conditionValue) > 0;
            case lt:
                return ((Comparable) fieldValue).compareTo(conditionValue) < 0;
            case eq:
                return fieldValue.equals(conditionValue);
            case neq:
                return !fieldValue.equals(conditionValue);
            default:
                throw new IllegalArgumentException("Unknown operator: " + operator);
        }
    }
}
