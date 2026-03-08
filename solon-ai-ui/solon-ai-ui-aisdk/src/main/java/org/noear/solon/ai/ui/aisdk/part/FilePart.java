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
package org.noear.solon.ai.ui.aisdk.part;

import org.noear.snack4.ONode;

/**
 * File Part — 文件
 * <p>
 * 表示流中传输的文件（如图片、PDF 等），前端可通过 {@code message.parts} 中
 * type 为 {@code "file"} 的条目获取文件 URL 和 MIME 类型。
 * <p>
 * 格式：{@code {"type":"file","url":"https://...","mediaType":"image/png"}}
 *
 * @see <a href="https://ai-sdk.dev/docs/ai-sdk-ui/stream-protocol#file-part">File Part</a>
 * @since 3.9.5
 */
public class FilePart extends AiSdkStreamPart {
    private final String url;
    private final String mediaType;

    public FilePart(String url, String mediaType) {
        this.url = url;
        this.mediaType = mediaType;
    }

    @Override
    public String getType() {
        return "file";
    }

    @Override
    protected void writeFields(ONode node) {
        node.set("url", url);
        node.set("mediaType", mediaType);
    }
}
