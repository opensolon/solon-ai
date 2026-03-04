package features.ai.skills.web;

import demo.ai.skills.LlmUtil;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.agent.simple.SimpleAgent;
import org.noear.solon.ai.agent.simple.SimpleResponse;
import org.noear.solon.ai.skills.crawler.WebCrawlerSkill;
import org.noear.solon.ai.skills.search.WebSearchSkill;

/**
 * 联网搜索与网页抓取集成测试
 */
public class WebSearchAndCrawlerTests {

    // 建议从环境变量获取 Key，或者手动填入进行本地临时测试
    private final String serperKey = "c120c7b6ac668f7ffcc5f6cb26c0c43c8f055074";
    private final String jinaKey = null;

    @Test
    public void testSearchLogic() {
        // 1. 基础搜索功能测试 (使用 Serper 驱动)
        WebSearchSkill searchSkill = new WebSearchSkill(WebSearchSkill.SERPER, serperKey);

        String result = searchSkill.search("Solon AI 框架最新进展");

        System.out.println("[搜索结果]:\n" + result);
        Assertions.assertNotNull(result);
        Assertions.assertTrue(result.contains("http"), "搜索结果应包含链接");
    }

    @Test
    public void testCrawlerLogic() {
        // 2. 基础抓取功能测试 (使用 Jina 驱动)
        WebCrawlerSkill crawlerSkill = new WebCrawlerSkill(WebCrawlerSkill.JINA, jinaKey);

        String result = crawlerSkill.crawl("https://solon.noear.org/article/about");

        System.out.println("[抓取内容]:\n" + (result.length() > 500 ? result.substring(0, 500) + "..." : result));
        Assertions.assertNotNull(result);
        Assertions.assertTrue(result.contains("Solon"), "抓取内容应包含关键字");
    }

    /**
     * Agent 驱动测试：模拟“联网搜索 -> 选择链接 -> 抓取内容 -> 总结回答”的完整链路
     */
    @Test
    public void testAgentSearchAndResearch() throws Throwable {
        if (serperKey == null) return; // 没 Key 跳过集成测试

        // 1. 组合技能
        WebSearchSkill searchSkill = new WebSearchSkill(WebSearchSkill.SERPER, serperKey);
        WebCrawlerSkill crawlerSkill = new WebCrawlerSkill(WebCrawlerSkill.JINA, jinaKey);

        SimpleAgent agent = SimpleAgent.of(LlmUtil.getChatModel())
                .role("研究助理")
                .defaultSkillAdd(searchSkill)
                .defaultSkillAdd(crawlerSkill)
                .build();

        // 2. 复杂 query
        String query = "请帮我搜索关于 '2026年AI Agent的发展趋势'，" +
                "并点击其中一个最有价值的搜索结果链接进行深度抓取，最后给我一个 300 字以内的总结。";

        System.out.println("[Agent 正在联网调研中...]");
        SimpleResponse resp = agent.prompt(query).call();

        System.out.println("[AI 最终报告]:\n" + resp.getContent());

        // 3. 验证
        Assertions.assertTrue(resp.getContent().length() > 50, "回答内容过短，可能未成功执行任务");
        // 验证过程中是否触发了多个工具调用
        // 注意：可以通过日志观察到 Agent 先调用 search，再根据返回的 link 调用 crawl_url
    }
}