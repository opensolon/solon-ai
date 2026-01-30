package demo.ai.skill.web;

import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.skills.web.WebCrawlerSkill;
import org.noear.solon.ai.skills.web.WebSearchSkill;

/**
 *
 * @author noear 2026/1/30 created
 *
 */
public class Demo {
    public void test() throws Exception {
        WebSearchSkill search = new WebSearchSkill(WebSearchSkill.SERPER, "serper_key");
        WebCrawlerSkill crawler = new WebCrawlerSkill(WebCrawlerSkill.FIRECRAWL, "jina_key");

        ChatModel agent = ChatModel.of("url..")
                .model("xxx")
                .defaultSkillAdd(search)
                .defaultSkillAdd(crawler)
                .build();

        // 对话逻辑：
        // 1. AI 意识到不知道“Solon AOT”是什么
        // 2. AI 调用 web_search("Solon AOT 机制原理")
        // 3. AI 拿到几个 Link，发现第一个官网 Link 很关键
        // 4. AI 调用 web_crawler("https://solon.noear.org/...") 深度阅读文档
        // 5. AI 给用户详细解答
        agent.prompt("详细解释一下 Solon 的 AOT 编译机制原理").call();
    }
}
