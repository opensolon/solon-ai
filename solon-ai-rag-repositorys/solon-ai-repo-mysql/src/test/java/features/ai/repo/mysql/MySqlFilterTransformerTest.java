package features.ai.repo.mysql;

import org.junit.jupiter.api.Test;
import org.noear.solon.ai.rag.repository.mysql.FilterTransformer;
import org.noear.solon.expression.Expression;
import org.noear.solon.expression.snel.SnEL;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * MySQL 过滤转换器测试（纯转换，不依赖数据库）
 *
 * <p>回归点：{@code LIKE} / {@code NOT LIKE} 曾落进 switch 的 default 分支被改写成 {@code =}。
 * 其中 {@code NOT LIKE} 被改写成 {@code =} 之后语义完全反转——本该被排除的行反而成了唯一匹配项，
 * 而 SQL 不报错，调用方只会看到「筛选后没有结果」或「筛出了不该有的结果」。</p>
 *
 * @author noear
 */
public class MySqlFilterTransformerTest {
    private String where(String expression) {
        Expression filter = SnEL.parse(expression);
        return FilterTransformer.getInstance().transform(filter);
    }

    @Test
    public void like() {
        assertEquals("`title` LIKE 'solon%'", where("title LIKE 'solon%'"));
    }

    @Test
    public void notLike() {
        assertEquals("`title` NOT LIKE 'solon%'", where("title NOT LIKE 'solon%'"));
    }

    @Test
    public void likeInLogicalExpression() {
        assertEquals("(`age` > 18 AND `title` LIKE 'solon%')", where("age > 18 AND title LIKE 'solon%'"));
    }

    @Test
    public void notLikeIsNotRenderedAsEquality() {
        String sql = where("title NOT LIKE 'solon%'");
        assertFalse(sql.contains(" = "), "NOT LIKE 不能被渲染成等号: " + sql);
    }

    @Test
    public void equalityIsNotAffected() {
        assertEquals("`title` = 'solon'", where("title == 'solon'"));
    }
}
