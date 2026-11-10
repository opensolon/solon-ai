package features.ai.repo.weaviate;

import org.junit.jupiter.api.Test;
import org.noear.solon.ai.rag.repository.weaviate.FilterTransformer;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Weaviate where 子句的 GraphQL 字面量序列化测试（纯转换，不依赖 Weaviate 服务）
 *
 * <p>回归点：{@code escapeString} 原先先转引号、再转反斜杠。引号转出来的 {@code \"} 会被后一步
 * 再翻一倍变成 {@code \\"}，反斜杠失去转义作用，字符串在 GraphQL 里提前闭合——值里只要有一个
 * 双引号，整条 where 子句就是非法 GraphQL（更远一步是字符串截断带来的注入面）。</p>
 *
 * @author noear
 */
public class WeaviateFilterTransformerTest {
    private String where(Object valueText) {
        Map<String, Object> where = new LinkedHashMap<>();
        where.put("path", Collections.singletonList("tag"));
        where.put("operator", "Equal");
        where.put("valueText", valueText);
        return FilterTransformer.getInstance().convertToGraphQLObject(where);
    }

    @Test
    public void quoteIsEscapedOnce() {
        assertEquals("{path: [\"tag\"], operator: Equal, valueText: \"a\\\"b\"}", where("a\"b"));
    }

    @Test
    public void chineseTextWithQuotes() {
        assertEquals("{path: [\"tag\"], operator: Equal, valueText: \"他说\\\"你好\\\"\"}", where("他说\"你好\""));
    }

    @Test
    public void backslashIsDoubled() {
        assertEquals("{path: [\"tag\"], operator: Equal, valueText: \"C:\\\\docs\\\\a.pdf\"}", where("C:\\docs\\a.pdf"));
    }

    @Test
    public void tabIsEscaped() {
        assertEquals("{path: [\"tag\"], operator: Equal, valueText: \"a\\tb\"}", where("a\tb"));
    }

    @Test
    public void plainValueIsNotTouched() {
        assertEquals("{path: [\"tag\"], operator: Equal, valueText: \"plain\"}", where("plain"));
    }
}
