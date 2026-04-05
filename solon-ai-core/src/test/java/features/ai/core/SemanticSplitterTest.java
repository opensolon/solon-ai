package features.ai.core;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.embedding.EmbeddingModel;
import org.noear.solon.ai.embedding.dialect.EmbeddingDialectManager;
import org.noear.solon.ai.rag.Document;
import org.noear.solon.ai.rag.splitter.SemanticSplitter;
import org.noear.solon.test.SolonTest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * @author 烧饵块
 * @since 3.10.1
 */
@SolonTest
public class SemanticSplitterTest {
    private static final Logger log = LoggerFactory.getLogger(SemanticSplitterTest.class);

    @BeforeAll
    static void registerDialect() {
        // 手动注册测试用的方言
        EmbeddingDialectManager.register(new TestOpenaiEmbeddingDialect());
    }

    private static final String apiUrl = "https://ai.gitee.com/v1/embeddings";
    private static final String apiKey = "";
    private static final String model = "Qwen3-Embedding-0.6B";

    public EmbeddingModel buildEmbeddingModel() {
        return EmbeddingModel.of(apiUrl)
                .apiKey(apiKey)
                .model(model)
                .provider("giteeai")
                .batchSize(10)
                .build();
    }

    @Test
    @DisplayName("测试语义分割英语")
    public void testSplitEnglish() {
        EmbeddingModel embeddingModel = buildEmbeddingModel();

        SemanticSplitter splitter = new SemanticSplitter(
                embeddingModel,
                0.5,
                512,
                2,
                1,
                SemanticSplitter.ENGLISH_DELIM
        );

        String text = "Machine learning is a subset of artificial intelligence. " +
                "It focuses on building systems that learn from data. " +
                "Deep learning is a further subset of machine learning. " +
                "The weather today is sunny and warm. " +
                "Tomorrow it might rain in the afternoon. " +
                "Neural networks are inspired by the human brain. " +
                "They consist of layers of interconnected nodes. ";

        List<Document> chunks = splitter.split(text);

        log.info("英文分割结果，共 {} 个 chunk：", chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            log.info("Chunk {}: {}", i, chunks.get(i).getContent());
        }
    }

    @Test
    public void testSplitChinese() {
        EmbeddingModel embeddingModel = buildEmbeddingModel();

        SemanticSplitter splitter = new SemanticSplitter(
                embeddingModel,
                0.5,
                512,
                2,
                1,
                SemanticSplitter.CHINESE_DELIM
        );

        String text = "人工智能正在改变世界。机器学习是人工智能的一个重要分支。" +
                "深度学习在图像识别领域取得了巨大突破。" +
                "今天的天气非常好，阳光明媚。明天可能会下雨。" +
                "自然语言处理让计算机能够理解人类语言。" +
                "大语言模型是自然语言处理的最新进展。";

        List<Document> chunks = splitter.split(text);

        log.info("中文分割结果，共 {} 个 chunk：", chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            log.info("Chunk {}: {}", i, chunks.get(i).getContent());
        }
    }

    @Test
    public void testSplitMixed() {
        EmbeddingModel embeddingModel = buildEmbeddingModel();

        SemanticSplitter splitter = new SemanticSplitter(
                embeddingModel,
                0.5,
                256,
                2,
                1,
                SemanticSplitter.ALL_COMMON_DELIM
        );

        String text = "Solon is a lightweight Java framework. It is designed for cloud-native applications. " +
                "Solon 是一个轻量级的 Java 框架。它专为云原生应用设计。" +
                "RAG stands for Retrieval-Augmented Generation. " +
                "RAG 即检索增强生成，是一种结合检索与生成的技术。" +
                "The stock market rose by 2% today. " +
                "今天股市上涨了百分之二。";

        List<Document> chunks = splitter.split(text);

        log.info("中英文混合分割结果，共 {} 个 chunk：", chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            log.info("Chunk {}: {}", i, chunks.get(i).getContent());
        }
    }

    @Test
    public void testDefaultParams() {
        EmbeddingModel embeddingModel = buildEmbeddingModel();

        // 使用默认参数
        SemanticSplitter splitter = new SemanticSplitter(embeddingModel);

        String text = "人工智能正在改变世界。机器学习是人工智能的一个重要分支。" +
                "深度学习在图像识别领域取得了巨大突破。" +
                "今天的天气非常好，阳光明媚。明天可能会下雨。" +
                "自然语言处理让计算机能够理解人类语言。";

        List<Document> chunks = splitter.split(text);

        log.info("默认参数分割结果，共 {} 个 chunk：", chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            log.info("Chunk {}: {}", i, chunks.get(i).getContent());
        }
    }

    @Test
    public void testFewSentences() {
        EmbeddingModel embeddingModel = buildEmbeddingModel();

        SemanticSplitter splitter = new SemanticSplitter(
                embeddingModel, 0.5, 512, 3, 1, SemanticSplitter.CHINESE_DELIM
        );

        // 句子数少于窗口大小，应作为单个 chunk 返回
        String text = "人工智能正在改变世界。机器学习是重要分支。";

        List<Document> chunks = splitter.split(text);

        log.info("少句测试，共 {} 个 chunk：", chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            log.info("Chunk {}: {}", i, chunks.get(i).getContent());
        }
    }

    @Test
    public void testMinSentencesPerChunk() {
        EmbeddingModel embeddingModel = buildEmbeddingModel();

        // 设置最小句子数为 3
        SemanticSplitter splitter = new SemanticSplitter(
                embeddingModel, 0.5, 512, 2, 3, SemanticSplitter.CHINESE_DELIM
        );

        String text = "人工智能正在改变世界。机器学习是人工智能的一个重要分支。" +
                "深度学习在图像识别领域取得了巨大突破。" +
                "今天天气非常好。明天可能会下雨。后天会放晴。" +
                "自然语言处理让计算机能够理解人类语言。\n\n自然语言处理不是简单的正则匹配；自然语言在NLP的加持下可以给我们更多机会。" +
                "大语言模型是自然语言处理的最新进展。" +
                "向量数据库是 RAG 系统的核心组件。";

        List<Document> chunks = splitter.split(text);

        log.info("最小句子数=3 分割结果，共 {} 个 chunk：", chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            log.info("Chunk {}: {}", i, chunks.get(i).getContent());
        }
    }
}
