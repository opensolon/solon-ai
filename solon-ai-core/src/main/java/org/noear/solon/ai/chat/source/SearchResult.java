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
package org.noear.solon.ai.chat.source;

import org.noear.solon.lang.Preview;

import java.io.Serializable;

/**
 * 搜索结果的跨协议语义投影。
 * <p>只承载可在不同 LLM 方言之间稳定持久化的公共字段；供应商专属状态应保留在消息协议状态中。</p>
 *
 * @author noear
 * @since 4.1
 */
@Preview("4.1")
public class SearchResult implements Serializable {
    private static final long serialVersionUID = 1L;

    private Integer index;
    private String id;
    private String title;
    private String url;
    private String snippet;

    public SearchResult() {
        // 用于序列化
    }

    public Integer getIndex() {
        return index;
    }

    public void setIndex(Integer index) {
        this.index = index;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getSnippet() {
        return snippet;
    }

    public void setSnippet(String snippet) {
        this.snippet = snippet;
    }

    public SearchResult index(Integer index) {
        this.index = index;
        return this;
    }

    public SearchResult id(String id) {
        this.id = id;
        return this;
    }

    public SearchResult title(String title) {
        this.title = title;
        return this;
    }

    public SearchResult url(String url) {
        this.url = url;
        return this;
    }

    public SearchResult snippet(String snippet) {
        this.snippet = snippet;
        return this;
    }

    @Override
    public String toString() {
        return "SearchResult{" +
                "index=" + index +
                ", id='" + id + '\'' +
                ", title='" + title + '\'' +
                ", url='" + url + '\'' +
                '}';
    }
}
