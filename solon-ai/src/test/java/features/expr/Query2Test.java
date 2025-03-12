package features.expr;

import org.junit.jupiter.api.Test;
import org.noear.solon.expr.Expression;
import org.noear.solon.expr.tree.ConditionBuilder;

import java.util.HashMap;
import java.util.Map;

/**
 * @author noear 2025/3/11 created
 */
public class Query2Test {
    @Test
    public void case1() {
        // 设置查询上下文
        Map<String,Object> context = new HashMap<>();
        context.put("age", 25);
        context.put("salary", 4000);
        context.put("isMarried", true);

        // 构建条件查询表达式树: (age > 18 AND salary < 5000) OR (NOT isMarried)
        ConditionBuilder cb = new ConditionBuilder();

        Expression<Boolean> conditionNode = cb.or(
                cb.and(cb.gt("age", 18), cb.lt("salary", 5000)),
                cb.eq("isMarried", "false")
        );

        // 计算条件查询表达式的值
        boolean result = conditionNode.eval(context);
        System.out.println("Result: " + result);  // 输出: Result: true
        assert result;

        PrintUtil.printTree2(conditionNode);
    }
}