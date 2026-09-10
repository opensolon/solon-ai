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
 * 回答引用的跨协议语义投影。
 * <p>协议坐标、加密内容和输出项顺序等供应商专属数据不在此建模，由事件属性或消息协议状态保留。</p>
 *
 * @author noear
 * @since 4.1
 */
@Preview("4.1")
public class Citation implements Serializable {
    private static final long serialVersionUID = 1L;

    private String type;
    private String title;
    private String url;
    private String citedText;

    public Citation() {
        // 用于序列化
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
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

    public String getCitedText() {
        return citedText;
    }

    public void setCitedText(String citedText) {
        this.citedText = citedText;
    }

    public Citation type(String type) {
        this.type = type;
        return this;
    }

    public Citation title(String title) {
        this.title = title;
        return this;
    }

    public Citation url(String url) {
        this.url = url;
        return this;
    }

    public Citation citedText(String citedText) {
        this.citedText = citedText;
        return this;
    }

    @Override
    public String toString() {
        return "Citation{" +
                "type='" + type + '\'' +
                ", title='" + title + '\'' +
                ", url='" + url + '\'' +
                '}';
    }
}
