package features;

import org.junit.jupiter.api.Test;
import org.noear.solon.ai.chat.dialect.ChatDialectManager;
import org.noear.solon.ai.embedding.dialect.EmbeddingDialectManager;
import org.noear.solon.ai.image.dialect.ImageDialectManager;
import org.noear.solon.ai.reranking.dialect.RerankingDialectManager;

/**
 * @author noear 2025/5/7 created
 */
public class LoadTest {
    @Test
    public void case1() {
        ChatDialectManager.register(null);
        ImageDialectManager.register(null);
        EmbeddingDialectManager.register(null);
        RerankingDialectManager.register(null);
    }
}
