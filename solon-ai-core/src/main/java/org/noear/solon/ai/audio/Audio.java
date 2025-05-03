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
package org.noear.solon.ai.audio;


import org.noear.solon.Utils;
import org.noear.solon.ai.AiMedia;

import java.util.Base64;

/**
 * 音频
 *
 * @author noear
 * @since 3.1
 */
public class Audio implements AiMedia {
    private String url;
    private String b64_json; //就是 base64-str
    private String mime;

    /**
     * 由 url 构建
     */
    public static Audio ofUrl(String url) {
        Audio tmp = new Audio();
        tmp.url = url;
        return tmp;
    }

    /**
     * 获取 url
     */
    public String getUrl() {
        return url;
    }

    @Override
    public String toString() {
        return "Audio{" +
                "url='" + url + '\'' +
                ", b64_json='" + b64_json + '\'' +
                ", mime='" + mime + '\'' +
                '}';
    }

    /**
     * 由 base64String 构建
     */
    public static Audio ofBase64(String base64String) {
        Audio tmp = new Audio();
        tmp.b64_json = base64String;
        return tmp;
    }

    /**
     * 由 base64 构建
     */
    public static Audio ofBase64(String base64String, String mime) {
        Audio tmp = new Audio();
        tmp.b64_json = base64String;
        tmp.mime = mime;
        return tmp;
    }

    /**
     * 由 base64 构建
     */
    public static Audio ofBase64(byte[] base64, String mime) {
        Audio tmp = new Audio();
        tmp.b64_json = Base64.getEncoder().encodeToString(base64);
        tmp.mime = mime;
        return tmp;
    }

    /**
     * 由 base64 构建
     */
    public static Audio ofBase64(byte[] base64) {
        Audio tmp = new Audio();
        tmp.b64_json = Base64.getEncoder().encodeToString(base64);
        return tmp;
    }

    /**
     * 转为数据字符串
     */
    @Override
    public String toDataString(boolean useMime) {
        if (Utils.isEmpty(b64_json)) {
            return url;
        } else {
            if (useMime) {
                if (mime != null) {
                    return "data:" + mime + ";base64," + b64_json;
                } else {
                    return "data:audio/mpeg;base64," + b64_json;
                }
            } else {
                return b64_json;
            }
        }
    }
}