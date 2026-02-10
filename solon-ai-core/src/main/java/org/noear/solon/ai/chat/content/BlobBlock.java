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
 * 二进制内容块
 *
 * @author noear
 * @since 3.9.2
 */
public class BlobBlock implements ContentBlock, ResourceBlock, MessageBlock {
    @ONodeAttr(name = "@type")
    private final String type = this.getClass().getName();

    private String blob; //base64
    private String mimeType;


    public static BlobBlock of(String blob, String mimeType) {
        BlobBlock tmp = new BlobBlock();
        tmp.blob = blob;
        tmp.mimeType = mimeType;

        return tmp;
    }

    public String getBlob() {
        return blob;
    }

    public String getContent() {
        return blob;
    }

    @Override
    public String getMimeType() {
        return mimeType;
    }

    protected Map<String, Object> metas;

    @Override
    public Map<String, Object> metas() {
        if (metas == null) {
            metas = new LinkedHashMap<>();
        }

        return metas;
    }


    @Override
    public String toDataString(boolean useMime) {
        if (useMime) {
            if (Utils.isNotEmpty(getMimeType())) {
                return "data:" + getMimeType() + ";base64," + getBlob();
            }
        }

        return getContent();
    }

    @Override
    public String toString() {
        return "BlobBlock{" +
                "blob='" + blob + '\'' +
                ", mimeType='" + mimeType + '\'' +
                '}';
    }
}