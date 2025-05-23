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

import java.util.Base64;

/**
 * 音频
 *
 * @author noear
 * @since 3.1
 */
public class Audio extends AbstractMedia implements AiMedia {
    @Override
    public String getMimeType() {
        if (Utils.isEmpty(mimeType)) {
            return "audio/mpeg";
        } else {
            return mimeType;
        }
    }

    /**
     * 由 url 构建
     */
    public static Audio ofUrl(String url) {
        Audio tmp = new Audio();
        tmp.url = url;
        return tmp;
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
    public static Audio ofBase64(String base64String, String mimeType) {
        Audio tmp = new Audio();
        tmp.b64_json = base64String;
        tmp.mimeType = mimeType;
        return tmp;
    }

    /**
     * 由 base64 构建
     */
    public static Audio ofBase64(byte[] base64, String mimeType) {
        Audio tmp = new Audio();
        tmp.b64_json = Base64.getEncoder().encodeToString(base64);
        tmp.mimeType = mimeType;
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
}