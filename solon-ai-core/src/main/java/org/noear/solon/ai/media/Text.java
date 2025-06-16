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

import java.util.Map;

/**
 * 文本媒体
 *
 * @author noear
 * @since 3.1
 */
public class Text implements AiMedia {
    private String content;
    private boolean isBase64;
    private String mimeType;

    public static Text of(boolean isBase64, String content) {
        Text tmp = new Text();
        tmp.isBase64 = isBase64;
        tmp.content = content;
        tmp.mimeType = null;

        return tmp;
    }

    public static Text of(boolean isBase64, String content, String mimeType) {
        Text tmp = new Text();
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

    public Image toImage() {
        if (isBase64()) {
            return Image.ofBase64(getContent(), getMimeType());
        } else {
            return Image.ofUrl(getContent());
        }
    }

    public Audio toAudio() {
        if (isBase64()) {
            return Audio.ofBase64(getContent(), getMimeType());
        } else {
            return Audio.ofUrl(getContent());
        }
    }

    public Video toVideo() {
        if (isBase64()) {
            return Video.ofBase64(getContent(), getMimeType());
        } else {
            return Video.ofUrl(getContent());
        }
    }
}