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
package org.noear.solon.ai.chat.media;

import org.noear.solon.Utils;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 文本内容块
 *
 * @author noear
 * @since 3.1
 */
public class TextBlock implements ContentBlock {
    private String content;
    private boolean isBase64;
    private String mimeType;

    public static TextBlock of(boolean isBase64, String content) {
        TextBlock tmp = new TextBlock();
        tmp.isBase64 = isBase64;
        tmp.content = content;
        tmp.mimeType = null;

        return tmp;
    }

    public static TextBlock of(boolean isBase64, String content, String mimeType) {
        TextBlock tmp = new TextBlock();
        tmp.isBase64 = isBase64;
        tmp.content = content;
        tmp.mimeType = mimeType;

        return tmp;
    }

    public boolean isBase64() {
        return isBase64;
    }

    public String getContent() {
        return content;
    }

    public String getMimeType() {
        return mimeType;
    }

    protected Map<String, Object> metadata;

    @Override
    public Map<String, Object> metas() {
        if (metadata == null) {
            metadata = new LinkedHashMap<>();
        }

        return metadata;
    }


    @Override
    public String toDataString(boolean useMime) {
        if (useMime) {
            if (Utils.isNotEmpty(getMimeType())) {
                return "data:" + getMimeType() + ";base64," + getContent();
            }
        }

        return getContent();
    }

    @Override
    public Map<String, Object> toData(boolean useMime) {
        if (useMime) {
            if (Utils.isNotEmpty(getMimeType())) {
                return Utils.asMap("mimeType", getMimeType(), "data", getContent());
            }
        }

        return Utils.asMap("data", getContent());
    }

    @Override
    public String toString() {
        return "Text{" +
                "content='" + content + '\'' +
                ", isBase64=" + isBase64 +
                ", mimeType='" + mimeType + '\'' +
                '}';
    }

    /// ///////////////

    public ImageBlock toImage() {
        if (isBase64()) {
            return ImageBlock.ofBase64(getContent(), getMimeType());
        } else {
            return ImageBlock.ofUrl(getContent());
        }
    }

    public AudioBlock toAudio() {
        if (isBase64()) {
            return AudioBlock.ofBase64(getContent(), getMimeType());
        } else {
            return AudioBlock.ofUrl(getContent());
        }
    }

    public VideoBlock toVideo() {
        if (isBase64()) {
            return VideoBlock.ofBase64(getContent(), getMimeType());
        } else {
            return VideoBlock.ofUrl(getContent());
        }
    }
}