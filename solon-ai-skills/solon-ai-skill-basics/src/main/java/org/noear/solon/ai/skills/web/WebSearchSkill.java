package org.noear.solon.ai.skills.web;

import org.noear.snack4.ONode;
import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.ai.chat.skill.AbsSkill;
import org.noear.solon.annotation.Param;
import org.noear.solon.net.http.HttpException;
import org.noear.solon.net.http.HttpUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 网络搜索技能：基于 Serper(Google) 提供实时联网搜索能力
 *
 * @author noear
 */
public class WebSearchSkill extends AbsSkill {
    private static final Logger log = LoggerFactory.getLogger(WebSearchSkill.class);

    private final String apiKey;
    private int maxResults = 5;

    public WebSearchSkill(String apiKey) {
        this.apiKey = apiKey;
    }

    public WebSearchSkill maxResults(int maxResults) {
        this.maxResults = maxResults;
        return this;
    }

    @Override
    public String name() {
        return "web_search";
    }

    @Override
    public String description() {
        return "联网搜索专家：能够通过互联网查询最新的新闻、技术文档、实时资讯。";
    }

    @ToolMapping(name = "search", description = "根据关键词进行互联网搜索，返回网页标题、链接和内容摘要")
    public String search(@Param("query") String query) {
        try {
            // Serper.dev API 地址 (Google Search)
            String json = HttpUtils.http("https://google.serper.dev/search")
                    .header("X-API-KEY", apiKey)
                    .header("Content-Type", "application/json")
                    .bodyJson(new ONode().set("q", query).set("num", maxResults).toJson())
                    .post();

            ONode resNode = ONode.ofJson(json);
            StringBuilder sb = new StringBuilder();

            // 解析有机搜索结果 (Organic results)
            resNode.get("organic").getArrayUnsafe().forEach(item -> {
                sb.append("### ").append(item.get("title").getString()).append("\n")
                        .append("- Link: ").append(item.get("link").getString()).append("\n")
                        .append("- Snippet: ").append(item.get("snippet").getString()).append("\n\n");
            });

            if (sb.length() == 0) return "未找到相关搜索结果。";
            return sb.toString();

        } catch (HttpException e) {
            log.error("Web search failed for query: {}", query, e);
            return "搜索服务暂时不可用: " + e.getMessage();
        }
    }
}