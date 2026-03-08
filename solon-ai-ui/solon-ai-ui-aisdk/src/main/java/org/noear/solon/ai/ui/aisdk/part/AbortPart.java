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
 * Abort Part — 流中止
 * <p>
 * 表示流被主动中止（如用户点击停止按钮、超时等）。
 * <p>
 * 格式：{@code {"type":"abort"}} 或 {@code {"type":"abort","reason":"user_cancelled"}}
 *
 * @see <a href="https://ai-sdk.dev/docs/ai-sdk-ui/stream-protocol#abort-part">Abort Part</a>
 * @since 3.9.5
 */
public class AbortPart extends AiSdkStreamPart {
    private final String reason;

    public AbortPart() {
        this(null);
    }

    public AbortPart(String reason) {
        this.reason = reason;
    }

    @Override
    public String getType() {
        return "abort";
    }

    @Override
    protected void writeFields(ONode node) {
        if (reason != null && !reason.isEmpty()) {
            node.set("reason", reason);
        }
    }
}
