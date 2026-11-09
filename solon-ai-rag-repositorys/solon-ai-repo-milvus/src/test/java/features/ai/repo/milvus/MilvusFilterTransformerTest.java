package features.ai.repo.milvus;

import org.junit.jupiter.api.Test;
import org.noear.solon.ai.rag.repository.milvus.FilterTransformer;
import org.noear.solon.expression.Expression;
import org.noear.solon.expression.snel.SnEL;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Milvus 过滤表达式转换测试（纯转换，不依赖 Milvus 服务）
 *
 * <p>回归点三处：</p>
 * <ul>
 *   <li>集合字面量用「先追加、再截掉 1 个字符」收尾，只截掉了空格，逗号留着 → {@code ["a", "b",]}；</li>
 *   <li>同一个截断在空集合上会把已写入的 {@code [} 一起删掉 → {@code in ]}；</li>
 *   <li>{@code NOT IN} 直接输出了 {@code ComparisonOp.nin} 的代号 {@code "NO IN"} → {@code no in}，
 *       而 Milvus 认的是 {@code not in}。</li>
 * </ul>
 *
 * @author noear
 */
public class MilvusFilterTransformerTest {
    private String where(String expression) {
        Expression filter = SnEL.parse(expression);
        return FilterTransformer.getInstance().transform(filter);
    }

    @Test
    public void inTwoItems() {
        assertEquals("(metadata[\"tag\"] in [\"a\", \"b\"])", where("tag IN ['a','b']"));
    }

    @Test
    public void inSingleItem() {
        assertEquals("(metadata[\"tag\"] in [\"a\"])", where("tag IN ['a']"));
    }

    @Test
    public void inEmptyList() {
        assertEquals("(metadata[\"tag\"] in [])", where("tag IN []"));
    }

    @Test
    public void notIn() {
        assertEquals("(metadata[\"tag\"] not in [\"a\", \"b\"])", where("tag NOT IN ['a','b']"));
    }

    @Test
    public void inInsideLogicalExpression() {
        assertEquals("((metadata[\"age\"] > 18) and (metadata[\"tag\"] in [\"a\", \"b\"]))",
                where("age > 18 AND tag IN ['a','b']"));
    }
}
