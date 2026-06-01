package demo.ai.skills;

import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.talents.file.FileReadWriteTalent;
import org.noear.solon.ai.talents.sys.SystemClockTalent;
import org.noear.solon.ai.talents.crawler.WebCrawlerDriverTalent;
import org.noear.solon.ai.talents.search.WebSearchDriverTalent;


public class Demo {
    public void test() throws Exception {
        WebSearchDriverTalent search = new WebSearchDriverTalent(WebSearchDriverTalent.BAIDU, "serper_key");
        WebCrawlerDriverTalent crawler = new WebCrawlerDriverTalent(WebCrawlerDriverTalent.FIRECRAWL, "jina_key");
        FileReadWriteTalent disk = new FileReadWriteTalent("./ai_workspace");
        SystemClockTalent clock = new SystemClockTalent();

        // 构建 Agent 或 ChatModel
        ChatModel agent = ChatModel.of("url..")
                .model("xxx")
                .defaultTalentAdd(clock, disk, search, crawler)
                .build();

        agent.prompt("搜索 2026 年最流行的 Java 框架，选择前三名抓取正文，并分别保存为 markdown 文件。")
                .call();    }
}
