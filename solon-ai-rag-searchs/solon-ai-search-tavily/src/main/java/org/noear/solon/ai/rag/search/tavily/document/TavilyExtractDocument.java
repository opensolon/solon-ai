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

import java.util.List;

/**
 * Tavily Extract 提取结果文档
 *
 * @author shaoerkuai
 * @since 3.9.5
 * @see <a href="https://docs.tavily.com/documentation/api-reference/introduction">API Reference</a>
 */
public class TavilyExtractDocument {
    private String url;
    private String rawContent;
    private List<String> images;
    private String favicon;

    public TavilyExtractDocument() {
    }

    // ==================== Getters ====================

    public String getUrl() {
        return url;
    }

    public String getRawContent() {
        return rawContent;
    }

    public List<String> getImages() {
        return images;
    }

    public String getFavicon() {
        return favicon;
    }

    // ==================== Setters ====================

    public void setUrl(String url) {
        this.url = url;
    }

    public void setRawContent(String rawContent) {
        this.rawContent = rawContent;
    }

    public void setImages(List<String> images) {
        this.images = images;
    }

    public void setFavicon(String favicon) {
        this.favicon = favicon;
    }

    /**
     * 转换为标准 Document
     */
    public Document toDocument() {
        Document doc = new Document(rawContent != null ? rawContent : "");
        doc.url(url);

        if (favicon != null) {
            doc.metadata("favicon", favicon);
        }
        if (images != null && !images.isEmpty()) {
            doc.metadata("images", images);
        }

        return doc;
    }
}
