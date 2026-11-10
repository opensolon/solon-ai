package features.ai.repo.elasticsearch;

import org.junit.jupiter.api.Test;
import org.noear.solon.ai.rag.repository.elasticsearch.FilterTransformer;
import org.noear.solon.expression.Expression;
import org.noear.solon.expression.snel.SnEL;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Elasticsearch 过滤转换器测试（纯转换，不依赖搜索服务）
 *
 * <p>回归点：{@code lk} / {@code nlk}（表达式里的 {@code LIKE} / {@code NOT LIKE}）没有对应的
 * case，落进 switch 的 default 直接返回 null。独立使用时，repository 看到 null 就不加过滤条件，
 * 于是「模糊筛选」变成「不筛选」；夹在 AND / OR 里更糟——{@link #likeInsideConjunctionDoesNotDropTheWholeFilter()}
 * 这一类表达式以前整个变成 match_all，连同一侧那个本来正确的条件一起消失。两种情况下 ES 都不报错，
 * 调用方只会看到多出来的结果。</p>
 *
 * @author noear
 */
public class ElasticsearchFilterTransformerTest {
    private Map<String, Object> query(String expression) {
        Expression filter = SnEL.parse(expression);
        return FilterTransformer.getInstance().transform(filter);
    }

    @Test
    public void like() {
        assertEquals("solon*", wildcardValue(query("title LIKE 'solon%'")));
    }

    @Test
    public void notLike() {
        Map<String, Object> query = query("title NOT LIKE 'solon%'");

        Map<String, Object> bool = asMap(query.get("bool"));
        assertNotNull(bool, "NOT LIKE 应该渲染成 bool.must_not: " + query);

        List<?> mustNot = (List<?>) bool.get("must_not");
        assertEquals("solon*", wildcardValue(asMap(mustNot.get(0))));
    }

    @Test
    public void singleCharWildcard() {
        assertEquals("sol?n", wildcardValue(query("title LIKE 'sol_n'")));
    }

    @Test
    public void literalStarIsEscaped() {
        // LIKE 里的 * 是字面量，到了 wildcard 查询里是通配符，必须转义
        assertEquals("a\\*b", wildcardValue(query("title LIKE 'a*b'")));
    }

    @Test
    public void likeInsideConjunctionDoesNotDropTheWholeFilter() {
        Map<String, Object> query = query("age > 18 AND title LIKE 'solon%'");

        Map<String, Object> bool = asMap(query.get("bool"));
        assertNotNull(bool, "AND 表达式应该渲染成 bool: " + query);

        List<?> must = (List<?>) bool.get("must");
        assertEquals(2, must.size(), "AND 的两个条件都要在: " + query);

        boolean hasRange = false;
        boolean hasWildcard = false;
        for (Object item : must) {
            Map<String, Object> condition = asMap(item);
            hasRange |= condition.containsKey("range");
            hasWildcard |= condition.containsKey("wildcard");
        }

        assertTrue(hasRange, "范围条件不能丢: " + query);
        assertTrue(hasWildcard, "LIKE 条件不能丢: " + query);
    }

    @Test
    public void likeIsNotRenderedAsMatchAll() {
        Map<String, Object> query = query("title LIKE 'solon%'");

        assertFalse(query.containsKey("match_all"), "LIKE 不能被渲染成 match_all: " + query);
    }

    @Test
    public void equalityIsNotAffected() {
        Map<String, Object> query = query("title == 'solon'");
        Map<String, Object> term = asMap(query.get("term"));

        assertEquals("solon", asMap(term.get("title")).get("value"));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object value) {
        return (Map<String, Object>) value;
    }

    /**
     * 取出 wildcard 查询里的模式串（形如 {@code {"wildcard": {"title": {"value": "..."}}}}）
     */
    private String wildcardValue(Map<String, Object> query) {
        Map<String, Object> wildcard = asMap(query.get("wildcard"));
        assertNotNull(wildcard, "应该渲染成 wildcard 查询: " + query);

        Map<String, Object> body = asMap(wildcard.get("title"));
        assertNotNull(body, "wildcard 查询要带上字段名: " + query);

        return (String) body.get("value");
    }
}
