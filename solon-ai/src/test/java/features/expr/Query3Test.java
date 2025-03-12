package features.expr;

import org.junit.jupiter.api.Test;
import org.noear.solon.expr.Expression;
import org.noear.solon.expr.ExpressionContextDefault;
import org.noear.solon.expr.ExpressionParser;
import org.noear.solon.expr.DefaultExpressionParser;

/**
 * @author noear 2025/3/12 created
 */
public class Query3Test {
    ExpressionParser parser = new DefaultExpressionParser();

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
        Expression root = parser.parse(expression);

        // 打印表达式树
        System.out.println("Expression Tree: " + root);

        // 计算表达式结果
        Object result = root.evaluate(context);
        System.out.println("Result: " + result); // Output: Result: true
        assert result instanceof Boolean;

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

        Expression root = parser.parse(expression);

        // 打印表达式树
        System.out.println("Expression Tree: " + root);

        // 计算表达式结果
        Object result = root.evaluate(context);
        System.out.println("Result: " + result); // Output: Result: true
        assert ((Boolean) result) == true;

        PrintUtil.printTree2(root);
    }

    @Test
    public void case3() {
        ExpressionContextDefault context = new ExpressionContextDefault();
        context.put("age", 25);
        context.put("salary", 4000);
        context.put("isMarried", false);
        context.put("label", "aa");
        context.put("title", "ee");
        context.put("vip", "l3");

        String expression = "((age > 18 OR salary < 5000) AND (isMarried == false) AND label IN ['aa','bb'] AND title NOT IN ['cc','dd']) OR vip=='l3'";
        Expression root = parser.parse(expression);

        // 打印表达式树
        System.out.println("Expression Tree: " + root);

        // 计算表达式结果
        Object result = root.evaluate(context);
        System.out.println("Result: " + result); // Output: Result: true
        assert ((Boolean) result) == true;

        PrintUtil.printTree2(root);
    }

    @Test
    public void case4() {
        ExpressionContextDefault context = new ExpressionContextDefault();
        context.put("age", 25);
        context.put("salary", 4000);
        context.put("salaryV", 5000);
        context.put("isMarried", false);
        context.put("label", "aa");
        context.put("title", "ee");
        context.put("vip", "l3");

        String expression = "((age > 18 OR salary < salaryV) AND (isMarried == false) AND label IN ['aa','bb'] AND title NOT IN ['cc','dd']) OR vip=='l3'";
        Expression root = parser.parse(expression);

        // 打印表达式树
        System.out.println("Expression Tree: " + root);

        // 计算表达式结果
        Object result = root.evaluate(context);
        System.out.println("Result: " + result); // Output: Result: true
        assert ((Boolean) result) == true;

        PrintUtil.printTree2(root);
    }

    @Test
    public void case5() {
        // 数学运算 (Long)
        Integer result = (Integer) parser.evaluate("1+2+3");
        System.out.println(result); // 6
        assert 6 == result;

        // 数学运算 (Double)
        Double result2 = (Double) parser.evaluate("1.1+2.2+3.3");
        System.out.println(result2); // 6.6
        assert 6.6D == result2;

        // 包含关系运算和逻辑运算
        Boolean result3 = (Boolean) parser.evaluate("(1>0||0<1)&&1!=0");
        System.out.println(result3); // true
        assert result3 == true;

        // 三元运算
        //String result4 = (String) parser.evaluate("4 > 3 ? \"4 > 3\" : 999");
        //System.out.println(result4); // 4 > 3

    }
}