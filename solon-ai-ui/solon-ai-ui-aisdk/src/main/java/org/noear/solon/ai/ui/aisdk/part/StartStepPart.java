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
 * Start Step Part — 步骤开始
 * <p>
 * 表示一次 LLM API 调用步骤的开始。在后端多步工具调用（tool-calls → re-prompt）场景下，
 * 每次 LLM 调用前都应发送此 Part，配合 {@link FinishStepPart} 使用。
 * <p>
 * 格式：{@code {"type":"start-step"}}
 *
 * @see FinishStepPart
 * @see <a href="https://ai-sdk.dev/docs/ai-sdk-ui/stream-protocol#start-step-part">Start Step Part</a>
 * @since 3.9.5
 */
public class StartStepPart extends AiSdkStreamPart {

    public StartStepPart() {
    }

    @Override
    public String getType() {
        return "start-step";
    }

    @Override
    protected void writeFields(ONode node) {
        // 无额外字段
    }
}
