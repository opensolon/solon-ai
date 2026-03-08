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
 * Finish Step Part — 步骤结束
 * <p>
 * 表示一次 LLM API 调用步骤已完成。在后端多步工具调用场景下必需，
 * 前端 {@code useChat} 的 steps 机制依赖此 Part 正确拼接多轮 assistant 消息。
 * <p>
 * 格式：{@code {"type":"finish-step"}}
 *
 * @see StartStepPart
 * @see <a href="https://ai-sdk.dev/docs/ai-sdk-ui/stream-protocol#finish-step-part">Finish Step Part</a>
 * @since 3.9.5
 */
public class FinishStepPart extends AiSdkStreamPart {

    public FinishStepPart() {
    }

    @Override
    public String getType() {
        return "finish-step";
    }

    @Override
    protected void writeFields(ONode node) {
        // 无额外字段
    }
}
