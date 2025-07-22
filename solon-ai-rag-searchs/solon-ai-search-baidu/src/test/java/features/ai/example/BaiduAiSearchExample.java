package features.ai.example;

import features.ai.TestUtils;
import org.noear.solon.ai.rag.Document;
import org.noear.solon.ai.rag.search.BaiduWebSearchRepository;

import java.util.List;

/**
 * 百度AI搜索Repository使用示例
 *
 * @author yangbuyiya
 * @date 2025/07/22
 */
public class BaiduAiSearchExample {
    
    static String apiUrl = "https://qianfan.baidubce.com/v2/ai_search/chat/completions";
    
    public static void main(String[] args) throws Exception {
        // 你的百度AppBuilder API Key https://console.bce.baidu.com/iam/#/iam/apikey/list
        String apiKey = TestUtils.apiKey;
        
        // 示例1：基础搜索
        basicSearchExample(apiKey);
        
        // 示例2：AI搜索
        aiSearchExample(apiKey);
        
        // 示例3：自定义配置
        customConfigExample(apiKey);
    }
    
    /**
     * 基础搜索示例
     */
    public static void basicSearchExample(String apiKey) throws Exception {
        System.out.println("=== 基础搜索示例 ===");
        
        // 创建基础搜索Repository
        BaiduWebSearchRepository repository = BaiduWebSearchRepository.ofBasic()
                .apiKey(apiKey)
                .apiUrl(apiUrl)
                .build();
        
        // 执行搜索
        String query = "今日上海闵行区天气";
        List<Document> results = repository.search(query);
        
        // 输出结果
        System.out.println("搜索查询: " + query);
        System.out.println("结果数量: " + results.size());
        
        results.forEach(doc -> {
            System.out.println("标题: " + doc.getTitle());
            System.out.println("内容: " + doc.getContent().substring(0, Math.min(100, doc.getContent().length())) + "...");
            System.out.println("URL: " + doc.getUrl());
            System.out.println("类型: " + doc.getMetadata().get("type"));
            System.out.println("来源: " + doc.getMetadata().get("source"));
            System.out.println("---");
        });
    }
    
    /**
     * AI搜索示例
     */
    public static void aiSearchExample(String apiKey) throws Exception {
        System.out.println("\n=== AI搜索示例 ===");
        
        // 创建AI搜索Repository
        BaiduWebSearchRepository repository = BaiduWebSearchRepository.ofAI()
                .apiKey(apiKey)
                .apiUrl(apiUrl)
                .model("ernie-3.5-8k")  // 可选择其他模型
                .build();
        
        // 执行搜索
        String query = "如何解决交通拥堵问题？";
        List<Document> results = repository.search(query);
        
        // 输出结果
        System.out.println("搜索查询: " + query);
        System.out.println("结果数量: " + results.size());
        
        results.forEach(doc -> {
            String type = (String) doc.getMetadata().get("type");
            System.out.println("类型: " + type);
            System.out.println("标题: " + doc.getTitle());
            
            if ("ai_answer".equals(type)) {
                // AI回答通常较长，完整显示
                System.out.println("AI回答: " + doc.getContent());
            } else {
                // 搜索结果显示摘要
                System.out.println("内容: " + doc.getContent().substring(0, Math.min(200, doc.getContent().length())) + "...");
                System.out.println("URL: " + doc.getUrl());
            }
            System.out.println("来源: " + doc.getMetadata().get("source"));
            System.out.println("---");
        });
    }
    
    /**
     * 自定义配置示例
     */
    public static void customConfigExample(String apiKey) throws Exception {
        System.out.println("\n=== 自定义配置示例 ===");
        
        // 创建自定义配置的Repository
        BaiduWebSearchRepository repository = BaiduWebSearchRepository.of(BaiduWebSearchRepository.SearchType.AI)
                .apiKey(apiKey)
                .apiUrl(apiUrl)
                .model("ernie-4.0-turbo-8k")  // 使用showSupportedModels里面的模型
                .build();
        
        // 执行搜索
        String query = "DeepSeek-R1模型的特点和优势";
        List<Document> results = repository.search(query);
        
        // 输出结果
        System.out.println("搜索查询: " + query);
        System.out.println("使用模型: deepseek-r1");
        System.out.println("结果数量: " + results.size());
        
        results.forEach(doc -> {
            System.out.println("类型: " + doc.getMetadata().get("type"));
            System.out.println("标题: " + doc.getTitle());
            System.out.println("内容: " + doc.getContent().substring(0, Math.min(300, doc.getContent().length())) + "...");
            System.out.println("---");
        });
    }
    
    /**
     * 支持的模型列表示例
     */
    public static void showSupportedModels() {
        System.out.println("\n=== 支持的模型列表 ===");
        System.out.println("1. ernie-3.5-8k (默认)");
        System.out.println("2. ernie-4.0-turbo-8k (支持图文混排)");
        System.out.println("3. ernie-4.0-turbo-128k (支持图文混排)");
        System.out.println("4. ernie-4.0-8k-preview");
        System.out.println("5. deepseek-r1");
        System.out.println("6. deepseek-v3");
        System.out.println("7. ernie-4.5-turbo-32k");
        System.out.println("8. ernie-4.5-turbo-128k");
    }
    
    /**
     * API Key获取说明
     */
    public static void showApiKeyGuide() {
        System.out.println("\n=== API Key获取指南 ===");
        System.out.println("1. 访问百度AppBuilder控制台");
        System.out.println("2. 点击API Key进行创建");
        System.out.println("3. 服务选择：千帆AppBuilder");
        System.out.println("4. 确定即可获得API Key");
        System.out.println("5. 格式：Bearer+<AppBuilder API Key>");
    }
}
