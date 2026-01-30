/*
 * Copyright 2017-2025 noear.org and authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.noear.solon.ai.skills.web;

import org.noear.snack4.ONode;
import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.ai.chat.skill.AbsSkill;
import org.noear.solon.annotation.Param;
import org.noear.solon.net.http.HttpUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 网页抓取技能：支持 Jina, Firecrawl 等多驱动
 *
 * @author noear
 * @since 3.9.1
 */
public class WebCrawlerSkill extends AbsSkill {
    private static final Logger LOG = LoggerFactory.getLogger(WebCrawlerSkill.class);

    private final String apiKey;
    private final CrawlerDriver driver;

    public WebCrawlerSkill(CrawlerDriver driver, String apiKey) {
        this.driver = driver;
        this.apiKey = apiKey;
    }

    @Override
    public String name() { return "web_crawler"; }

    @Override
    public String description() { return "阅读助手：抓取网页内容并转换为 LLM 友好的 Markdown。"; }

    @ToolMapping(name = "crawl_url", description = "读取并分析指定 URL 网页的详细内容")
    public String crawl(@Param("url") String url) {
        if (url == null || !url.startsWith("http")) return "错误：URL 无效。";
        try {
            return driver.crawl(url, apiKey);
        } catch (Exception e) {
            LOG.error("Crawl failed [{}]: {}", driver, url, e);
            return "抓取失败: " + e.getMessage();
        }
    }

    // --- 驱动接口 ---
    public interface CrawlerDriver {
        String crawl(String url, String apiKey) throws Exception;
    }

    // --- 驱动实现：Jina Reader (极简 GET) ---
    public static final CrawlerDriver JINA = (url, key) -> {
        HttpUtils http = HttpUtils.http("https://r.jina.ai/" + url);

        // Jina 支持通过 Header 明确要求返回格式
        http.header("X-Return-Format", "markdown");
        if (key != null) {
            http.header("Authorization", "Bearer " + key);
        }

        return http.get();
    };

    // --- 驱动实现：Firecrawl (标准 POST) ---
    public static final CrawlerDriver FIRECRAWL = (url, key) -> {
        // Firecrawl v1/v2 统一接口，明确要求 markdown 格式
        String json = HttpUtils.http("https://api.firecrawl.dev/v1/scrape")
                .header("Authorization", "Bearer " + key)
                .header("Content-Type", "application/json")
                .bodyJson(new ONode()
                        .set("url", url)
                        .set("formats", new ONode().add("markdown"))
                        .set("onlyMainContent", true) // 默认剔除导航和广告
                        .toJson())
                .post();

        ONode res = ONode.ofJson(json);

        // 增加安全校验：Firecrawl 的标准结构是 { success: true, data: { markdown: "..." } }
        if (res.get("success").getBoolean()) {
            return res.get("data").get("markdown").getString();
        } else {
            throw new RuntimeException(res.get("error").getString());
        }
    };
}