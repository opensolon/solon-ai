package features.expr;

import org.junit.jupiter.api.Test;
import org.noear.solon.expression.ExpressionContext;
import org.noear.solon.expression.ExpressionContextDefault;
import org.noear.solon.expression.query.*;

import java.util.HashMap;
import java.util.Map;

/**
 * @author noear 2025/3/11 created
 */
public class QueryTest {
    @Test
    public void case1() {
        // 设置查询上下文
        ExpressionContextDefault context = new ExpressionContextDefault();
        context.put("age", 25);
        context.put("salary", 4000);
        context.put("isMarried", true);

        // 构建条件查询表达式树: (age > 18 AND salary < 5000) OR (NOT isMarried)
        FieldNode ageField = new FieldNode("age");
        ValueNode ageValue = new ValueNode(18);
        ComparisonNode ageComparison = new ComparisonNode(ComparisonOp.gt, ageField, ageValue);

        FieldNode salaryField = new FieldNode("salary");
        ValueNode salaryValue = new ValueNode(5000);
        ComparisonNode salaryComparison = new ComparisonNode(ComparisonOp.lt, salaryField, salaryValue);

        LogicalNode andNode = new LogicalNode(LogicalOp.and, ageComparison, salaryComparison);

        FieldNode notMarriedField = new FieldNode("isMarried");
        ValueNode notMarriedValue = new ValueNode(false);
        ComparisonNode notMarriedComparison = new ComparisonNode(ComparisonOp.eq, notMarriedField, notMarriedValue);

        LogicalNode orNode = new LogicalNode(LogicalOp.or, andNode, notMarriedComparison);

        // 计算条件查询表达式的值
        boolean result = orNode.evaluate(context);
        System.out.println("Result: " + result);  // 输出: Result: true


        printTree(orNode);
    }

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


        printTree(conditionNode);
    }

    /**
     * 打印
     */
    static void printTree(ConditionNode n1) {
        QueryExpressionBuilder.visit(n1, (node, level) -> {
            if (node instanceof FieldNode) {
                System.out.println(prefix(level) + "Field: " + ((FieldNode) node).getFieldName());
            } else if (node instanceof ValueNode) {
                System.out.println(prefix(level) + "Value: " + ((ValueNode) node).getValue());
            } else if (node instanceof ComparisonNode) {
                ComparisonNode compNode = (ComparisonNode) node;
                System.out.println(prefix(level) + "Comparison: " + compNode.getOperator());
            } else if (node instanceof LogicalNode) {
                LogicalNode opNode = (LogicalNode) node;
                System.out.println(prefix(level) + "Logical: " + opNode.getOperator());
            }
        });
    }

    static String prefix(int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            sb.append("  ");
        }

        return sb.toString();
    }
}
