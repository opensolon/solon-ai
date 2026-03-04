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
package org.noear.solon.ai.generate;

import java.io.Serializable;

/**
 * 生成内容
 *
 * @author noear
 * @since 3.5
 */
public class GenerateContent implements Serializable {
    private String text;
    private String data; //就是 base64-str
    private String url;
    private String mimeType;

    public GenerateContent() {
        //用于序列化
    }

    public GenerateContent(String text, String data, String url, String mimeType) {
        this.text = text;
        this.data = data;
        this.url = url;
        this.mimeType = mimeType;
    }

    public String getText() {
        return text;
    }

    public String getData() {
        return data;
    }

    public String getUrl() {
        return url;
    }

    public String getValue() {
        if (url != null) {
            return url;
        } else if (data != null) {
            return data;
        } else {
            return text;
        }
    }

    public String getMimeType() {
        return mimeType;
    }

    @Override
    public String toString() {
        return "{" +
                "text='" + text + '\'' +
                ", data='" + data + '\'' +
                ", url='" + url + '\'' +
                ", mimeType='" + mimeType + '\'' +
                '}';
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String text;
        private String data;
        private String url;
        private String mimeType;

        public Builder text(String text) {
            this.text = text;
            return this;
        }

        public Builder data(String base64String) {
            this.data = base64String;
            return this;
        }

        public Builder url(String url) {
            this.url = url;
            return this;
        }

        public Builder mimeType(String mimeType) {
            this.mimeType = mimeType;
            return this;
        }

        public GenerateContent build() {
            return new GenerateContent(text, data, url, mimeType);
        }
    }
}