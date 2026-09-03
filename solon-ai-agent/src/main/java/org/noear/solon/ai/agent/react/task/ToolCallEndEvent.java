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
package org.noear.solon.ai.agent.react.task;

import org.noear.solon.ai.agent.react.ReActTrace;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.lang.Nullable;
import org.noear.solon.lang.Preview;

import java.util.Map;

/**
 * ReAct 观察块（Observation）：标识智能体调用外部工具后的观察结果（含成功和异常）
 *
 * @author noear
 * @since 4.0.4
 */
@Preview("4.0.4")
public class ToolCallEndEvent extends AbsToolCallEvent {
    private final Throwable error;
    private final long durationMs;
    private final ChatMessage result;

    public ToolCallEndEvent(ReActTrace trace, String callId, String toolName, Map<String, Object> args, @Nullable ChatMessage result, @Nullable Throwable error, long durationMs) {
        super(trace, callId, toolName, args);

        this.error = error;
        this.durationMs = durationMs;
        this.result = result;
    }

    @Override
    public String getCallId() {
        return super.getCallId();
    }

    public ChatMessage getResult() {
        return result;
    }

    @Override
    public String getText() {
        if (result == null) {
            return "";
        }

        return result.getContent();
    }

    public @Nullable Throwable getError() {
        return error;
    }

    public long getDurationMs() {
        return durationMs;
    }
}