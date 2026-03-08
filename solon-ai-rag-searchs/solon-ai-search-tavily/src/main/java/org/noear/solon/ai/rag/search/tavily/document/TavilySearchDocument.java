/*
 * Copyright 2017-2025 noear.org and authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.noear.solon.ai.rag.search.tavily.document;

import org.noear.solon.ai.rag.Document;

/**
 * Tavily Search 搜索结果文档
 *
 * @author shaoerkuai
 * @since 3.9.5
 * @see <a href="https://docs.tavily.com/documentation/api-reference/introduction">API Reference</a>
 */
public class TavilySearchDocument {
    private String title;
    private String url;
    private String content;
    private double score;
    private String rawContent;
    private String favicon;

    public TavilySearchDocument() {
    }

    // ==================== Getters ====================

    public String getTitle() {
        return title;
    }

    public String getUrl() {
        return url;
    }

    public String getContent() {
        return content;
    }

    public double getScore() {
        return score;
    }

    public String getRawContent() {
        return rawContent;
    }

    public String getFavicon() {
        return favicon;
    }

    // ==================== Setters ====================

    public void setTitle(String title) {
        this.title = title;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public void setScore(double score) {
        this.score = score;
    }

    public void setRawContent(String rawContent) {
        this.rawContent = rawContent;
    }

    public void setFavicon(String favicon) {
        this.favicon = favicon;
    }

    /**
     * 转换为标准 Document
     */
    public Document toDocument() {
        Document doc = new Document(content);
        doc.title(title);
        doc.url(url);
        doc.score(score);

        if (rawContent != null) {
            doc.metadata("rawContent", rawContent);
        }
        if (favicon != null) {
            doc.metadata("favicon", favicon);
        }

        return doc;
    }
}
