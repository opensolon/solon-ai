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
package org.noear.solon.ai.ui.aisdk.part.source;

import org.noear.snack4.ONode;
import org.noear.solon.ai.ui.aisdk.part.AiSdkStreamPart;

/**
 * Source Document Part — 文档来源引用
 * <p>
 * 格式：{@code {"type":"source-document","sourceId":"https://example.com","mediaType":"file","title":"Title"}}
 *
 * @see SourceUrlPart
 * @see <a href="https://ai-sdk.dev/docs/ai-sdk-ui/stream-protocol#source-document-part">Source Document Part</a>
 * @since 3.9.5
 */
public class SourceDocumentPart extends AiSdkStreamPart {
    private final String sourceId;
    private final String mediaType;
    private final String title;

    public SourceDocumentPart(String sourceId, String mediaType, String title) {
        this.sourceId = sourceId;
        this.mediaType = mediaType;
        this.title = title;
    }

    @Override
    public String getType() {
        return "source-document";
    }

    @Override
    protected void writeFields(ONode node) {
        node.set("sourceId", sourceId);
        node.set("mediaType", mediaType);
        if (title != null) {
            node.set("title", title);
        }
    }
}
