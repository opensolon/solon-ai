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

import org.noear.solon.ai.agent.AbsAgentEvent;
import org.noear.solon.ai.agent.react.ReActTrace;
import org.noear.solon.ai.chat.ChatResponse;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.tool.ToolCall;
import org.noear.solon.core.util.Assert;

import java.util.List;

/**
 * 思考运行结束块
 *
 * @author noear
 * @since 4.0.4
 */
public class ReasonEndEvent extends AbsAgentEvent {
    private final ReActTrace trace;
    private final ChatResponse response;
    private final AssistantMessage message;
    private final long durationMs;
    private final String reasonId;

    public ReasonEndEvent(ReActTrace trace, ChatResponse response, AssistantMessage message, long durationMs) {
        super(trace.getRunId(), trace.getAgentName(), trace.getSession());

        this.trace = trace;
        this.response = response;
        this.message = message;
        this.durationMs = durationMs;
        this.reasonId = trace.getCurrentReasonId();
    }

    public ReActTrace getTrace() {
        return trace;
    }

    public ChatResponse getResponse() {
        return response;
    }

    public AssistantMessage getMessage() {
        return message;
    }

    @Override
    public String getText() {
        return message.getContent();
    }

    public long getDurationMs() {
        return durationMs;
    }

    public String getReasonId() {
        return reasonId;
    }

    public boolean isToolCalls() {
        return message.isToolCalls();
    }

    public List<ToolCall> getToolCalls() {
        return message.getToolCalls();
    }
}
