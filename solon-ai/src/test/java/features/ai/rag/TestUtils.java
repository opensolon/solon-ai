package features.ai.rag;

import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.embedding.EmbeddingModel;
import org.noear.solon.ai.rag.repository.WebSearchRepository;
import org.noear.solon.ai.reranking.RerankingModel;

/**
 * @author noear 2025/2/19 created
 */
public class TestUtils {
    public static ChatModel getChatModel() {
        final String apiUrl = "http://127.0.0.1:11434/api/chat";
        final String provider = "ollama";
        final String model = "llama3.2";//"DeepSeek-V3"; //deepseek-reasoner//deepseek-chat

        return ChatModel.of(apiUrl).provider(provider).model(model).build(); //4.初始化语言模型
    }

    public static WebSearchRepository getWebSearchRepository() {
        String apiUrl = "https://api.bochaai.com/v1/web-search";
        String apiKey = "sk-5d36eae2c4a54e2596c7625d9888a9d8";

        return WebSearchRepository.of(apiUrl).apiKey(apiKey).build();
    }

    public static EmbeddingModel getEmbeddingModel() {
        String apiUrl = "https://dashscope.aliyuncs.com/api/v1/services/embeddings/text-embedding/text-embedding";
        String apiKey = "sk-1ffe449611a74e61ad8e71e1b35a9858";
        String provider = "dashscope";
        String model = "text-embedding-v3";//"llama3.2"; //deepseek-r1:1.5b;

        return EmbeddingModel.of(apiUrl).apiKey(apiKey).provider(provider).model(model).batchSize(10).build();
    }
}
