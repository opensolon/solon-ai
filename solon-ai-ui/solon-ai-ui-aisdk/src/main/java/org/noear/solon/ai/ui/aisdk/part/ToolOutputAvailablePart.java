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
 * Tool Output Available Part — 工具输出完成
 * <p>
 * 格式：{@code {"type":"tool-output-available","toolCallId":"tc_xxx","output":{...}}}
 *
 * @see <a href="https://ai-sdk.dev/docs/ai-sdk-ui/stream-protocol#tool-output-available-part">Tool Output Available Part</a>
 * @since 3.9.5
 */
public class ToolOutputAvailablePart extends AiSdkStreamPart {
    private final String toolCallId;
    private final Object output;

    public ToolOutputAvailablePart(String toolCallId, Object output) {
        this.toolCallId = toolCallId;
        this.output = output;
    }

    @Override
    public String getType() {
        return "tool-output-available";
    }

    @Override
    protected void writeFields(ONode node) {
        node.set("toolCallId", toolCallId);
        if (output != null) {
            node.set("output", ONode.ofBean(output));
        }
    }
}
