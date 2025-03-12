package features.expr;

import org.junit.jupiter.api.Test;
import org.noear.solon.expression.Expression;
import org.noear.solon.expression.ExpressionContextDefault;
import org.noear.solon.expression.ExpressionParser;
import org.noear.solon.expression.query.QueryExpressionParser;

/**
 * @author noear 2025/3/12 created
 */
public class Query3Test {
    @Test
    public void case1() {
        ExpressionContextDefault context = new ExpressionContextDefault();
        context.put("age", 25);
        context.put("salary", 4000);
        context.put("isMarried", false);
        context.put("label", "aa");
        context.put("title", "ee");
        context.put("vip", "l3");

        String expression = "(((age > 18 AND salary < 5000) OR (NOT isMarried)) AND label IN ['aa','bb'] AND title NOT IN ['cc','dd']) OR vip=='l3'";
        ExpressionParser parser = new QueryExpressionParser(expression);
        Expression root = parser.parse();

        // 打印表达式树
        System.out.println("Expression Tree: " + root);

        // 计算表达式结果
        Object result = root.evaluate(context);
        System.out.println("Result: " + result); // Output: Result: true

        PrintUtil.printTree2(root);
    }

    @Test
    public void case2() {
        ExpressionContextDefault context = new ExpressionContextDefault();
        context.put("age", 25);
        context.put("salary", 4000);
        context.put("isMarried", false);
        context.put("label", "aa");
        context.put("title", "ee");
        context.put("vip", "l3");

        String expression = "((age > 18 OR salary < 5000) AND (NOT isMarried) AND label IN ['aa','bb'] AND title NOT IN ['cc','dd']) OR vip=='l3'";
        ExpressionParser parser = new QueryExpressionParser(expression);
        Expression root = parser.parse();

        // 打印表达式树
        System.out.println("Expression Tree: " + root);

        // 计算表达式结果
        Object result = root.evaluate(context);
        System.out.println("Result: " + result); // Output: Result: true

        PrintUtil.printTree2(root);
    }
}
