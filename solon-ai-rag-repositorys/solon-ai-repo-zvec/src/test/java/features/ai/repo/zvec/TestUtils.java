package features.ai.repo.zvec;

import org.noear.solon.ai.embedding.EmbeddingModel;
import org.noear.solon.ai.embedding.EmbeddingConfig;

import java.util.List;

/**
 * 测试用的“本地嵌入模型”（按词袋哈希生成向量，无需外部服务）
 *
 * @author noear
 */
public class TestUtils {
    public static final int DIMENSIONS = 64;

    public static EmbeddingModel getEmbeddingModel() {
        EmbeddingConfig config = new EmbeddingConfig();
        config.setApiUrl("http://127.0.0.1:0/embed");
        config.setProvider("test");
        config.setModel("test-bow");

        return new EmbeddingModel(config) {
            @Override
            public float[] embed(String text) {
                return bow(text);
            }

            @Override
            public void embed(List<org.noear.solon.ai.rag.Document> documents) {
                for (org.noear.solon.ai.rag.Document doc : documents) {
                    doc.embedding(bow(doc.getContent()));
                }
            }

            @Override
            public int dimensions() {
                return DIMENSIONS;
            }

            @Override
            public int batchSize() {
                return 10;
            }
        };
    }

    /**
     * 词袋哈希（小写、按空白切词）
     */
    public static float[] bow(String text) {
        float[] vector = new float[DIMENSIONS];

        if (text == null || text.length() == 0) {
            return vector;
        }

        for (String word : text.toLowerCase().split("[\\s,，。;；:：!！?？()（）\\[\\]{}\"'`]+")) {
            if (word.length() == 0) {
                continue;
            }

            int hash = Math.abs(word.hashCode()) % DIMENSIONS;
            vector[hash] += 1.0f;
        }

        return vector;
    }
}
