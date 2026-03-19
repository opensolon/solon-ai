package demo.ai.skills;

import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.skills.file.FileReadWriteSkill;
import org.noear.solon.ai.skills.sys.SystemClockSkill;
import org.noear.solon.ai.skills.crawler.WebCrawlerDriverSkill;
import org.noear.solon.ai.skills.search.WebSearchDriverSkill;


public class Demo {
    public void test() throws Exception {
        WebSearchDriverSkill search = new WebSearchDriverSkill(WebSearchDriverSkill.BAIDU, "serper_key");
        WebCrawlerDriverSkill crawler = new WebCrawlerDriverSkill(WebCrawlerDriverSkill.FIRECRAWL, "jina_key");
        FileReadWriteSkill disk = new FileReadWriteSkill("./ai_workspace");
        SystemClockSkill clock = new SystemClockSkill();

        // 构建 Agent 或 ChatModel
        ChatModel agent = ChatModel.of("url..")
                .model("xxx")
                .defaultSkillAdd(clock, disk, search, crawler)
                .build();

        agent.prompt("搜索 2026 年最流行的 Java 框架，选择前三名抓取正文，并分别保存为 markdown 文件。")
                .call();    }
}
