package features.ai.repo.zvec;

import org.junit.jupiter.api.Test;
import org.noear.solon.ai.rag.repository.zvec.FilterTransformer;
import org.noear.solon.expression.snel.ConditionBuilder;
import org.noear.solon.expression.snel.SnEL;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 过滤表达式转换测试（不依赖原生库）
 *
 * @author noear
 */
public class FilterTransformerTest {
    private final FilterTransformer transformer = FilterTransformer.getInstance();
    private final Set<String> columns = new HashSet<>(Arrays.asList("category", "year", "flag", "score"));

    @Test
    public void case1_singleton() {
        assertSame(FilterTransformer.getInstance(), FilterTransformer.getInstance());
    }

    @Test
    public void case2_nullExpression() {
        assertNull(transformer.transform(null, columns));
        assertNull(transformer.transform(null));
    }

    @Test
    public void case3_comparison() {
        assertEquals("(category = 'rpc')", transformer.transform(SnEL.parse("category == 'rpc'"), columns));
        assertEquals("(category != 'rpc')", transformer.transform(SnEL.parse("category != 'rpc'"), columns));
        assertEquals("(year > 2015)", transformer.transform(SnEL.parse("year > 2015"), columns));
        assertEquals("(year >= 2015)", transformer.transform(SnEL.parse("year >= 2015"), columns));
        assertEquals("(year < 2015)", transformer.transform(SnEL.parse("year < 2015"), columns));
        assertEquals("(year <= 2015)", transformer.transform(SnEL.parse("year <= 2015"), columns));
    }

    @Test
    public void case4_logical() {
        assertEquals("((category = 'rpc') AND (year > 2015))",
                transformer.transform(SnEL.parse("category == 'rpc' && year > 2015"), columns));
        assertEquals("((category = 'rpc') OR (year > 2015))",
                transformer.transform(SnEL.parse("category == 'rpc' || year > 2015"), columns));
    }

    @Test
    public void case5_in() {
        assertEquals("(category in ('rpc', 'database'))",
                transformer.transform(new ConditionBuilder().in("category", "rpc", "database"), columns));
        assertEquals("(category in ('rpc'))",
                transformer.transform(new ConditionBuilder().in("category", "rpc"), columns));
    }

    @Test
    public void case6_unknownColumnNotPushedDown() {
        //表达式里有未声明的列时，整体不下推（返回 null，由内存二次过滤兜底）
        assertNull(transformer.transform(SnEL.parse("tags == 'search'"), columns));
        assertNull(transformer.transform(SnEL.parse("category == 'rpc' && tags == 'search'"), columns));
    }

    @Test
    public void case7_likeNotPushedDown() {
        //zvec 的 like 通配符语义与 SnEL 不一致，不下推
        assertNull(transformer.transform(SnEL.parse("category LIKE 'rp'"), columns));
        assertNull(transformer.transform(SnEL.parse("category NOT LIKE 'rp'"), columns));
    }

    @Test
    public void case8_notNotPushedDown() {
        //zvec 过滤语法里没有 NOT
        assertNull(transformer.transform(SnEL.parse("!(category == 'rpc')"), columns));
    }

    @Test
    public void case9_booleanValue() {
        assertEquals("(flag = true)", transformer.transform(SnEL.parse("flag == true"), columns));
        assertEquals("(flag = false)", transformer.transform(SnEL.parse("flag == false"), columns));
    }

    @Test
    public void case10_nullValueNotPushedDown() {
        assertNull(transformer.transform(new ConditionBuilder().eq("category", null), columns));
    }

    @Test
    public void case11_doubleValue() {
        assertEquals("(score > 1.5)", transformer.transform(SnEL.parse("score > 1.5"), columns));
    }

    @Test
    public void case12_noColumnCheck() {
        //不传 columns 时不校验列名（保持与 Milvus 一致的行为）
        assertEquals("(anything = 'x')", transformer.transform(SnEL.parse("anything == 'x'")));
    }

    @Test
    public void case13_quoteEscaped() {
        //值里的单引号需转义，否则会拼出非法过滤语句
        String filter = transformer.transform(new ConditionBuilder().eq("category", "o'x"), columns);
        assertEquals("(category = 'o\\'x')", filter);
    }

    @Test
    public void case15_unknownNodeNotPushedDown() {
        //表达式里出现无法识别的节点类型时不下推（而不是抛给调用方）
        org.noear.solon.expression.Expression<Boolean> unknown = new org.noear.solon.expression.Expression<Boolean>() {
            @Override
            public Boolean eval(java.util.function.Function context) {
                return Boolean.TRUE;
            }
        };
        assertNull(transformer.transform(unknown, columns));
    }

    @Test
    public void case16_notIn() {
        //nin 直接构造比较节点（SnEL 字符串语法里没有 NOT IN）
        org.noear.solon.expression.snel.ComparisonNode node =
                new org.noear.solon.expression.snel.ComparisonNode(
                        org.noear.solon.expression.snel.ComparisonOp.nin,
                        new org.noear.solon.expression.snel.VariableNode("category"),
                        new org.noear.solon.expression.snel.ConstantNode(
                                java.util.Arrays.asList("x", "y")));
        assertEquals("(category not in ('x', 'y'))", transformer.transform(node, columns));
    }

    @Test
    public void case14_emptyColumns() {
        assertNull(transformer.transform(SnEL.parse("category == 'rpc'"), Collections.<String>emptySet()));
    }
}
