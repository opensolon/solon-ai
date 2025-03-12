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
package org.noear.solon.expr.tree;

import org.noear.solon.expr.Expression;
import org.noear.solon.expr.ExpressionContext;

import java.util.Collection;

/**
 * 比较运算节点（如 >, <, ==）
 *
 * @author noear
 * @since 3.1
 */
public class ComparisonNode implements ConditionNode {
    private ComparisonOp operator; // 比较运算符，如 ">", "<", "=="
    private Expression left;
    private Expression right;

    /**
     * 获取操作符
     */
    public ComparisonOp getOperator() {
        return operator;
    }

    /**
     * 获取左侧
     */
    public Expression getLeft() {
        return left;
    }

    /**
     * 获取右侧
     */
    public Expression getRight() {
        return right;
    }

    public ComparisonNode(ComparisonOp operator, Expression left, Expression right) {
        this.operator = operator;
        this.left = left;
        this.right = right;
    }

    @Override
    public Boolean evaluate(ExpressionContext context) {
        Object leftValue = left.evaluate(context);
        Object rightValue = right.evaluate(context);

        switch (operator) {
            case gt:
                return ((Comparable) leftValue).compareTo(rightValue) > 0;
            case gte:
                return ((Comparable) leftValue).compareTo(rightValue) >= 0;
            case lt:
                return ((Comparable) leftValue).compareTo(rightValue) < 0;
            case lte:
                return ((Comparable) leftValue).compareTo(rightValue) <= 0;
            case eq:
                return leftValue.equals(rightValue);
            case neq:
                return !leftValue.equals(rightValue);
            case in:
                return ((Collection) rightValue).contains(leftValue);
            case nin:
                return ((Collection) rightValue).contains(leftValue) == false;
            default:
                throw new IllegalArgumentException("Unknown operator: " + operator);
        }
    }
}
