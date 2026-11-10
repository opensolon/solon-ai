package features.ai.repo.opensearch;

import org.junit.jupiter.api.Test;
import org.noear.solon.ai.rag.repository.opensearch.FilterTransformer;
import org.noear.solon.expression.Expression;
import org.noear.solon.expression.snel.SnEL;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * OpenSearch 过滤转换器测试（纯转换，不依赖搜索服务）
 *
 * <p>回归点：{@code createWildcardQuery} 早就写好了却一次都没被调用，{@code lk} / {@code nlk}
 * 仍然落进 switch 的 default，而本类的兜底是「返回 match_all」。于是「模糊筛选」以前不是被忽略、
 * 而是被替换成「匹配所有文档」——查得越准的调用方，看到的无关结果越多，且 OpenSearch 不报错。</p>
 *
 * @author noear
 */
public class OpenSearchFilterTransformerTest {
    private Map<String, Object> query(String expression) {
        Expression filter = SnEL.parse(expression);
        return FilterTransformer.getInstance().transform(filter);
    }

    @Test
    public void like() {
        assertEquals("solon*", wildcardValue(query("title LIKE 'solon%'")));
    }

    @Test
    public void likeIsNotRenderedAsMatchAll() {
        Map<String, Object> query = query("title LIKE 'solon%'");

        assertFalse(query.containsKey("match_all"), "LIKE 不能被渲染成 match_all: " + query);
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
