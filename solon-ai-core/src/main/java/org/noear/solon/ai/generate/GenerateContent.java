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
    private String b64_json; //就是 base64-str
    private String url;
    private String mimeType;

    public GenerateContent() {
        //用于序列化
    }

    public GenerateContent(String text, String b64_json, String url, String mimeType) {
        this.text = text;
        this.b64_json = b64_json;
        this.url = url;
        this.mimeType = mimeType;
    }

    public String getText() {
        return text;
    }

    public String getB64Json() {
        return b64_json;
    }

    public String getUrl() {
        return url;
    }

    public String getMimeType() {
        return mimeType;
    }

    @Override
    public String toString() {
        return "{" +
                "text='" + text + '\'' +
                ", b64_json='" + b64_json + '\'' +
                ", url='" + url + '\'' +
                ", mimeType='" + mimeType + '\'' +
                '}';
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String text;
        private String b64_json;
        private String url;
        private String mimeType;

        public Builder text(String text) {
            this.text = text;
            return this;
        }

        public Builder b64_json(String b64_json) {
            this.b64_json = b64_json;
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
            return new GenerateContent(text, b64_json, url, mimeType);
        }
    }
}