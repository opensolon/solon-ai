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
package org.noear.solon.ai.ui.aisdk.part.text;

import org.noear.snack4.ONode;
import org.noear.solon.ai.ui.aisdk.part.AiSdkStreamPart;

/**
 * Text Start Part — 文本开始
 * <p>
 * 格式：{@code {"type":"text-start","id":"txt_xxx"}}
 *
 * @see TextDeltaPart
 * @see TextEndPart
 * @see <a href="https://ai-sdk.dev/docs/ai-sdk-ui/stream-protocol#text-start-part">Text Start Part</a>
 * @since 3.9.5
 */
public class TextStartPart extends AiSdkStreamPart {
    private final String id;

    public TextStartPart(String id) {
        this.id = id;
    }

    @Override
    public String getType() {
        return "text-start";
    }

    @Override
    protected void writeFields(ONode node) {
        node.set("id", id);
    }
}
