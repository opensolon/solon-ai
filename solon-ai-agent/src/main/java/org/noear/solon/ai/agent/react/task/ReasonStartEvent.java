/*
 * Copyright 2017-2025 noear.org and authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.noear.solon.ai.agent.react.task;

import org.noear.solon.ai.agent.react.AbsReActEvent;
import org.noear.solon.ai.agent.react.ReActTrace;
import org.noear.solon.ai.chat.prompt.Prompt;

/**
 * ReAct 推理开始
 *
 * @author noear
 * @since 4.0.4
 */
public class ReasonStartEvent extends AbsReActEvent {
    private final String systemPrompt;
    private final String reasonId;

    public ReasonStartEvent(ReActTrace trace, String systemPrompt) {
        super(trace);

        this.reasonId = trace.getCurrentReasonId();
        this.systemPrompt = systemPrompt;
    }

    public String getSystemPrompt() {
        return systemPrompt;
    }

    public Prompt getWorkingMemory() {
        return trace.getWorkingMemory();
    }

    public String getReasonId() {
        return reasonId;
    }
}