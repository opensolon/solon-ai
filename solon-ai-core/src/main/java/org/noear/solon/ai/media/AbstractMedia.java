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
package org.noear.solon.ai.media;

import org.noear.solon.Utils;
import org.noear.solon.ai.AiMedia;

/**
 * 虚拟媒体类型
 *
 * @author noear
 * @since 3.2
 */
public abstract class AbstractMedia implements AiMedia {
    protected String b64_json; //就是 base64-str
    protected String url;
    protected String mimeType;

    /**
     * 获取 base64
     */
    public String getB64Json() {
        return b64_json;
    }

    /**
     * 获取 url
     */
    public String getUrl() {
        return url;
    }

    /**
     * 获取 mimeType
     */
    public String getMimeType() {
        return mimeType;
    }

    /**
     * 转为数据字符串
     */
    @Override
    public String toDataString(boolean useMime) {
        if (Utils.isEmpty(getB64Json())) {
            return getUrl();
        } else {
            if (useMime) {
                if (Utils.isNotEmpty(getMimeType())) {
                    return "data:" + mimeType + ";base64," + b64_json;
                }
            }

            return b64_json;
        }
    }

    @Override
    public String toString() {
        return this.getClass().getSimpleName() + "{" +
                "url='" + getUrl() + '\'' +
                ", b64_json='" + getB64Json() + '\'' +
                ", mimeType='" + getMimeType() + '\'' +
                '}';
    }
}