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
package org.noear.solon.expr.query;

/**
 * 条件树
 *
 * @author noear
 * @since 3.1
 */
public class ConditionTree {
    /**
     * 评估
     */
    public static boolean eval(String expr, QueryContext context) {
        return parse(expr).evaluate(context);
    }

    /**
     * 解析
     */
    private static ConditionNode parse(String expr) {
        return null;
    }

    /**
     * 打印
     */
    public static void printTree(ConditionNode node, String prefix) {
        if (node instanceof FieldNode) {
            System.out.println(prefix + "Field: " + ((FieldNode) node).getFieldName());
        } else if (node instanceof ValueNode) {
            System.out.println(prefix + "Value: " + ((ValueNode) node).getValue());
        } else if (node instanceof ComparisonNode) {
            ComparisonNode compNode = (ComparisonNode) node;
            System.out.println(prefix + "Comparison: " + compNode.getOperator());
            printTree(compNode.getField(), prefix + "  ");
            printTree(compNode.getValue(), prefix + "  ");
        } else if (node instanceof LogicalNode) {
            LogicalNode opNode = (LogicalNode) node;
            System.out.println(prefix + "Logical: " + opNode.getOperator());
            printTree(opNode.getLeft(), prefix + "  ");
            if (opNode.getRight() != null) {
                printTree(opNode.getRight(), prefix + "  ");
            }
        }
    }
}