package features.expr;

import org.junit.jupiter.api.Test;
import org.noear.solon.expr.query.*;

import java.util.HashMap;
import java.util.Map;

/**
 * @author noear 2025/3/11 created
 */
public class QueryTest {
    @Test
    public void case1() {
        // 设置查询上下文
        Map<String, Object> context = new HashMap<>();
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

        FieldNode isMarriedField = new FieldNode("isMarried");
        ValueNode isMarriedValue = new ValueNode(true);
        ComparisonNode isMarriedComparison = new ComparisonNode(ComparisonOp.eq, isMarriedField, isMarriedValue);
        LogicalNode notNode = new LogicalNode(LogicalOp.not, isMarriedComparison, null);

        LogicalNode orNode = new LogicalNode(LogicalOp.or, andNode, notNode);

        // 计算条件查询表达式的值
        boolean result = orNode.evaluate(context::get);
        System.out.println("Result: " + result);  // 输出: Result: true


        ExprNode.printTree(orNode, "");
    }

    @Test
    public void case2() {
        // 设置查询上下文
        Map<String, Object> context = new HashMap<>();
        context.put("age", 25);
        context.put("salary", 4000);
        context.put("isMarried", true);

        // 构建条件查询表达式树: (age > 18 AND salary < 5000) OR (NOT isMarried)
        ExprBuilder cb = new ExprBuilder();

        ConditionNode conditionNode = cb.or(
                cb.and(cb.gt("age", 18), cb.lt("salary", 5000)),
                cb.not("isMarried")
        );

        // 计算条件查询表达式的值
        boolean result = conditionNode.evaluate(context::get);
        System.out.println("Result: " + result);  // 输出: Result: true


        ExprNode.printTree(conditionNode, "");
    }
}
