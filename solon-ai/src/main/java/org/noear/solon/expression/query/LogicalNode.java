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
 * 逻辑运算节点（如 AND, OR, NOT）
 *
 * @author noear
 * @since 3.1
 */
public class LogicalNode implements ConditionNode {
    private LogicalOp operator; // 逻辑运算符，如 "AND", "OR"
    private ConditionNode left;  // 左子节点
    private ConditionNode right; // 右子节点

    /**
     * 获取操作符
     */
    public LogicalOp getOperator() {
        return operator;
    }

    /**
     * 获取左节点
     */
    public ConditionNode getLeft() {
        return left;
    }

    /**
     * 获取右节点
     */
    public ConditionNode getRight() {
        return right;
    }

    public LogicalNode(LogicalOp operator, ConditionNode left, ConditionNode right) {
        this.operator = operator;
        this.left = left;
        this.right = right;
    }

    @Override
    public Boolean evaluate(ExpressionContext context) {
        switch (operator) {
            case and:
                return left.evaluate(context) && right.evaluate(context);
            case or:
                return left.evaluate(context) || right.evaluate(context);
            case not:
                return left.evaluate(context) == false;
            default:
                throw new IllegalArgumentException("Unknown operator: " + operator);
        }
    }
}
