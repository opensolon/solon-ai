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
import org.noear.solon.ai.AiUsage;

/**
 * Finish Part — 流结束
 * <p>
 * 含 usage 格式：{@code {"type":"finish","finishReason":"stop","usage":{...}}}
 * <br>
 * 无 usage 格式：{@code {"type":"finish","finishReason":"stop"}}
 *
 * @see <a href="https://ai-sdk.dev/docs/ai-sdk-ui/stream-protocol#finish-part">Finish Part</a>
 * @since 3.9.5
 */
public class FinishPart extends AiSdkStreamPart {
    private final String finishReason;
    private final AiUsage usage;

    public FinishPart() {
        this("stop", null);
    }

    public FinishPart(String finishReason) {
        this(finishReason, null);
    }

    public FinishPart(String finishReason, AiUsage usage) {
        this.finishReason = finishReason;
        this.usage = usage;
    }

    @Override
    public String getType() {
        return "finish";
    }

    @Override
    protected void writeFields(ONode node) {
        node.set("finishReason", finishReason);
        if (usage != null) {
            node.getOrNew("usage")
                    .set("promptTokens", usage.promptTokens())
                    .set("completionTokens", usage.completionTokens())
                    .set("totalTokens", usage.totalTokens());
            if (usage.thinkTokens() > 0) {
                node.get("usage").set("reasoningTokens", usage.thinkTokens());
            }
        }
    }
}
