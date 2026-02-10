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

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 文本内容块
 *
 * @author noear
 * @since 3.1
 */
public class TextBlock implements ContentBlock, ResourceBlock, MessageBlock {
    @ONodeAttr(name = "@type")
    private final String type = this.getClass().getName();

    private String text;
    private String mimeType;

    public static TextBlock of(String text) {
        TextBlock tmp = new TextBlock();
        tmp.text = text;
        tmp.mimeType = null;

        return tmp;
    }

    public static TextBlock of(String text, String mimeType) {
        TextBlock tmp = new TextBlock();
        tmp.text = text;
        tmp.mimeType = mimeType;

        return tmp;
    }

    public String getContent() {
        return text;
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
    public String toString() {
        return "TextBlock{" +
                "text='" + text + '\'' +
                ", mimeType='" + mimeType + '\'' +
                '}';
    }
}