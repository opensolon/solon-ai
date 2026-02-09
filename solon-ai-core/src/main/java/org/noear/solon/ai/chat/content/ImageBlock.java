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

import org.noear.snack4.annotation.ONodeAttr;
import org.noear.solon.Utils;

import java.util.Base64;

/**
 * 图像内容块
 *
 * @author noear
 * @since 3.1
 */
public class ImageBlock extends AbsMedia<ImageBlock> implements MediaBlock {
    @ONodeAttr(name = "@type")
    private final String type = this.getClass().getName();

    @Override
    public String getMimeType() {
        if (Utils.isEmpty(mimeType)) {
            return "image/jpeg";
        } else {
            return mimeType;
        }
    }

    /**
     * 由 url 构建
     */
    public static ImageBlock ofUrl(String url) {
        ImageBlock tmp = new ImageBlock();
        tmp.url = url;
        return tmp;
    }

    /**
     * 由 base64String 构建
     */
    public static ImageBlock ofBase64(String base64String) {
        ImageBlock tmp = new ImageBlock();
        tmp.b64_json = base64String;
        return tmp;
    }

    /**
     * 由 base64 构建
     */
    public static ImageBlock ofBase64(String base64String, String mimeType) {
        ImageBlock tmp = new ImageBlock();
        tmp.b64_json = base64String;
        tmp.mimeType = mimeType;
        return tmp;
    }

    /**
     * 由 base64 构建
     */
    public static ImageBlock ofBase64(byte[] base64, String mimeType) {
        ImageBlock tmp = new ImageBlock();
        tmp.b64_json = Base64.getEncoder().encodeToString(base64);
        tmp.mimeType = mimeType;
        return tmp;
    }

    /**
     * 由 base64 构建
     */
    public static ImageBlock ofBase64(byte[] base64) {
        ImageBlock tmp = new ImageBlock();
        tmp.b64_json = Base64.getEncoder().encodeToString(base64);
        return tmp;
    }
}