package features.expr;

import org.junit.jupiter.api.Test;
import org.noear.solon.expression.ExpressionContextDefault;
import org.noear.solon.expression.ExpressionNode;
import org.noear.solon.expression.query.*;

/**
 * @author noear 2025/3/11 created
 */
public class Query2Test {

    @Test
    public void case2() {
        // 设置查询上下文
        ExpressionContextDefault context = new ExpressionContextDefault();
        context.put("age", 25);
        context.put("salary", 4000);
        context.put("isMarried", true);

        // 构建条件查询表达式树: (age > 18 AND salary < 5000) OR (NOT isMarried)
        QueryExpressionBuilder cb = new QueryExpressionBuilder();

        ConditionNode conditionNode = cb.or(
                cb.and(cb.gt("age", 18), cb.lt("salary", 5000)),
                cb.eq("isMarried", "false")
        );

        // 计算条件查询表达式的值
        boolean result = conditionNode.evaluate(context);
        System.out.println("Result: " + result);  // 输出: Result: true


        printTree2(conditionNode);
        System.out.println("");
    }

    static void printTree2(ExpressionNode node) {
        if (node instanceof FieldNode) {
            System.out.print(((FieldNode) node).getFieldName());
        } else if (node instanceof ValueNode) {
            System.out.print(((ValueNode) node).getValue());
        } else if (node instanceof ComparisonNode) {
            ComparisonNode compNode = (ComparisonNode) node;

            System.out.print("(");
            printTree2(compNode.getField());
            System.out.print(" " + compNode.getOperator().getCode() + " ");
            printTree2(compNode.getValue());
            System.out.print(")");

        } else if (node instanceof LogicalNode) {
            LogicalNode opNode = (LogicalNode) node;

            System.out.print("(");
            printTree2(opNode.getLeft());
            System.out.print(" " + opNode.getOperator().getCode() + " ");
            printTree2(opNode.getRight());
            System.out.print(")");
        }
    }
}