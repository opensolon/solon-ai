package features.ai;

import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.embedding.EmbeddingModel;
import org.noear.solon.ai.rag.search.BaiduWebSearchRepository;

/**
 * 测试工具
 *
 * @author yangbuyiya
 * @date 2025/07/22
 */
public class TestUtils {
    
    public static String apiKey = ""; // 需要替换为实际的API Key
    public static String apiUrl = "https://qianfan.baidubce.com/v2/ai_search/chat/completions";
    
    /**
     * 获取聊天模型
     *
     * @return {@link ChatModel }
     */
    public static ChatModel getChatModel() {
        final String apiUrl = "http://127.0.0.1:11434/api/chat";
        final String provider = "ollama";
        final String model = "llama3.2";//"DeepSeek-V3"; //deepseek-reasoner//deepseek-chat
        
        return ChatModel.of(apiUrl).provider(provider).model(model).build(); //4.初始化语言模型
    }
    
    /**
     * 获取百度AI基础搜索Repository
     */
    public static BaiduWebSearchRepository getBaiduBasicSearchRepository() {
        
        return BaiduWebSearchRepository.ofBasic()
                .apiKey(apiKey)
                .apiUrl(apiUrl)
                .build();
    }
    
    /**
     * 获取百度AI搜索Repository（智能总结模式）
     */
    public static BaiduWebSearchRepository getBaiduAiSearchRepository() {
        
        return BaiduWebSearchRepository.ofAI()
                .apiKey(apiKey)
                .apiUrl(apiUrl)
                .model("ernie-3.5-8k")  // 可选择其他模型如 deepseek-r1, ernie-4.0-turbo-8k 等
                .build();
    }
    
    
    /**
     * 获取嵌入模型
     *
     * @return {@link EmbeddingModel }
     */
    public static EmbeddingModel getEmbeddingModel() {
        String apiUrl = "https://dashscope.aliyuncs.com/api/v1/services/embeddings/text-embedding/text-embedding";
        String apiKey = "sk-1ffe449611a74e61ad8e71e1b35a9858";
        String provider = "dashscope";
        String model = "text-embedding-v3";//"llama3.2"; //deepseek-r1:1.5b;
        
        return EmbeddingModel.of(apiUrl).apiKey(apiKey).provider(provider).model(model).batchSize(10).build();
    }
}
