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
package org.noear.solon.ai.chat.content;

import org.noear.solon.Utils;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 虚拟媒体类型
 *
 * @author noear
 * @since 3.2
 */
public abstract class AbsMedia<T extends AbsMedia> implements MediaBlock {
    protected String b64_json; //就是 base64-str
    protected String url;
    protected String mimeType;
    protected Map<String, Object> metadata;

    @Override
    public Map<String, Object> metas() {
        if (metadata == null) {
            metadata = new LinkedHashMap<>();
        }

        return metadata;
    }

    /**
     * 添加元信息
     *
     */
    public T metaAdd(String key, Object value) {
        metas().put(key, value);
        return (T) this;
    }

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
                    return "data:" + getMimeType() + ";base64," + getB64Json();
                }
            }

            return getB64Json();
        }
    }

    @Override
    public Map<String, Object> toData(boolean useMime) {
        if (Utils.isEmpty(getB64Json())) {
            return Utils.asMap("url", getUrl());
        } else {
            if (useMime) {
                if (Utils.isNotEmpty(getMimeType())) {
                    return Utils.asMap("mimeType", getMimeType(), "data", getB64Json());
                }
            }

            return Utils.asMap("data", getB64Json());
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