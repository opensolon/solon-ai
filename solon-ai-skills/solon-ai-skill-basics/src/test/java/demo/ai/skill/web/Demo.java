package demo.ai.skill.web;

import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.skills.file.FileStorageSkill;
import org.noear.solon.ai.skills.system.SystemClockSkill;
import org.noear.solon.ai.skills.web.WebCrawlerSkill;
import org.noear.solon.ai.skills.web.WebSearchSkill;


public class Demo {
    public void test() throws Exception {
        WebSearchSkill search = new WebSearchSkill(WebSearchSkill.BAIDU, "serper_key");
        WebCrawlerSkill crawler = new WebCrawlerSkill(WebCrawlerSkill.FIRECRAWL, "jina_key");
        FileStorageSkill disk = new FileStorageSkill("./ai_workspace");
        SystemClockSkill clock = new SystemClockSkill();

        // 构建 Agent 或 ChatModel
        ChatModel agent = ChatModel.of("url..")
                .model("xxx")
                .defaultSkillAdd(clock, disk, search, crawler)
                .build();

        agent.prompt("搜索 2026 年最流行的 Java 框架，选择前三名抓取正文，并分别保存为 markdown 文件。")
                .call();    }
}
