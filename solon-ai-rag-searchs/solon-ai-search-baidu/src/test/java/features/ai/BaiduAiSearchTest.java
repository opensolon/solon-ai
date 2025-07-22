package features.ai;

import org.junit.jupiter.api.Test;
import org.noear.solon.ai.chat.ChatResponse;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.rag.Document;
import org.noear.solon.ai.rag.search.BaiduAiSearchRepository;
import org.noear.solon.test.SolonTest;

import java.util.List;

/**
 * 百度AI搜索Repository测试
 *
 * @author Yangbuyi
 * @date 2025/07/22
 */
@SolonTest
public class BaiduAiSearchTest {
    
    /**
     * 测试百度基础搜索 + ChatModel增强回答
     */
    @Test
    public void basicSearchWithChatModel() throws Exception {
        BaiduAiSearchRepository repository = TestUtils.getBaiduBasicSearchRepository();
        String query = "今日上海天气如何？";

        List<Document> context = repository.search(query);
        
        //打印搜索结果
        System.out.println("=== 百度基础搜索结果 ===");
        context.forEach(doc -> {
            System.out.println("标题: " + doc.getTitle());
            System.out.println("内容: " + doc.getContent().substring(0, Math.min(100, doc.getContent().length())) + "...");
            System.out.println("URL: " + doc.getUrl());
            System.out.println("---");
        });
        
        //打印AI回答
        ChatResponse resp = TestUtils.getChatModel()
                .prompt(ChatMessage.ofUserAugment(query, context))
                .call();
        System.out.println("\n=== ChatModel增强回答 ===");
        System.out.println(resp.getMessage());
    }

    /**
     * 测试百度基础搜索 + 模板方式
     */
    @Test
    public void basicSearchWithTemplate() throws Exception {
        BaiduAiSearchRepository repository = TestUtils.getBaiduBasicSearchRepository();
        String query = "solon框架是谁开发的？";

        List<Document> context = repository.search(query);

        ChatResponse resp = TestUtils.getChatModel()
                .prompt(ChatMessage.ofUserTmpl("${query} \n\n 请参考以下内容回答：${context}")
                        .paramAdd("query", query)
                        .paramAdd("context", context)
                        .generate())
                .call();

        //打印搜索结果
        System.out.println("=== 百度基础搜索结果 ===");
        context.forEach(doc -> {
            System.out.println("标题: " + doc.getTitle());
            System.out.println("内容: " + doc.getContent().substring(0, Math.min(100, doc.getContent().length())) + "...");
            System.out.println("来源: " + doc.getMetadata().get("source"));
            System.out.println("---");
        });
        
        //打印AI回答
        System.out.println("\n=== ChatModel增强回答 ===");
        System.out.println(resp.getMessage());
    }

    /**
     * 测试百度AI搜索（直接返回智能总结）
     */
    @Test
    public void aiSearchDirect() throws Exception {
        BaiduAiSearchRepository repository = TestUtils.getBaiduAiSearchRepository();
        String query = "如何解决交通拥堵问题？";

        List<Document> results = repository.search(query);

        //打印AI搜索结果
        System.out.println("=== 百度AI搜索结果 ===");
        results.forEach(doc -> {
            System.out.println("类型: " + doc.getMetadata().get("type"));
            System.out.println("标题: " + doc.getTitle());
            System.out.println("内容: " + doc.getContent());
            if (doc.getUrl() != null) {
                System.out.println("URL: " + doc.getUrl());
            }
            System.out.println("来源: " + doc.getMetadata().get("source"));
            System.out.println("---");
        });
    }

    /**
     * 测试百度AI搜索 + 进一步ChatModel处理
     */
    @Test
    public void aiSearchWithChatModel() throws Exception {
        BaiduAiSearchRepository repository = TestUtils.getBaiduAiSearchRepository();
        String query = "deepseek-r1模型的特点是什么？";

        List<Document> aiResults = repository.search(query);

        // 从AI搜索结果中提取AI回答作为基础，进一步处理
        String aiAnswer = aiResults.stream()
                .filter(doc -> "ai_answer".equals(doc.getMetadata().get("type")))
                .map(Document::getContent)
                .findFirst()
                .orElse("");

        if (!aiAnswer.isEmpty()) {
            ChatResponse resp = TestUtils.getChatModel()
                    .prompt(ChatMessage.ofUserTmpl("基于以下AI搜索结果，请总结要点并补充说明：\n\n${aiAnswer}")
                            .paramAdd("aiAnswer", aiAnswer)
                            .generate())
                    .call();

            System.out.println("=== 百度AI搜索原始回答 ===");
            System.out.println(aiAnswer);
            
            System.out.println("\n=== ChatModel进一步处理 ===");
            System.out.println(resp.getMessage());
        }

        // 打印所有搜索结果
        System.out.println("\n=== 完整搜索结果 ===");
        aiResults.forEach(doc -> {
            System.out.println("类型: " + doc.getMetadata().get("type"));
            System.out.println("标题: " + doc.getTitle());
            System.out.println("内容: " + doc.getContent().substring(0, Math.min(200, doc.getContent().length())) + "...");
            System.out.println("---");
        });
    }

    /**
     * 测试对比：基础搜索 vs AI搜索
     */
    @Test
    public void compareBasicVsAiSearch() throws Exception {
        String query = "最新的AI技术发展趋势";

        // 基础搜索
        BaiduAiSearchRepository basicRepo = TestUtils.getBaiduBasicSearchRepository();
        List<Document> basicResults = basicRepo.search(query);

        // AI搜索
        BaiduAiSearchRepository aiRepo = TestUtils.getBaiduAiSearchRepository();
        List<Document> aiResults = aiRepo.search(query);

        System.out.println("=== 基础搜索结果 (" + basicResults.size() + "条) ===");
        basicResults.forEach(doc -> {
            System.out.println("标题: " + doc.getTitle());
            System.out.println("内容: " + doc.getContent().substring(0, Math.min(100, doc.getContent().length())) + "...");
            System.out.println("---");
        });

        System.out.println("\n=== AI搜索结果 (" + aiResults.size() + "条) ===");
        aiResults.forEach(doc -> {
            System.out.println("类型: " + doc.getMetadata().get("type"));
            System.out.println("标题: " + doc.getTitle());
            System.out.println("内容: " + doc.getContent().substring(0, Math.min(150, doc.getContent().length())) + "...");
            System.out.println("---");
        });
    }
}
