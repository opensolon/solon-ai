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

import org.noear.solon.ai.agent.AbsAgentChunk;
import org.noear.solon.ai.agent.react.ReActTrace;
import org.noear.solon.ai.chat.ChatResponse;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.tool.ToolCall;
import org.noear.solon.core.util.Assert;
import org.noear.solon.lang.Nullable;
import org.noear.solon.lang.Preview;

import java.util.List;

/**
 * ReAct 思考聚合块
 *
 * @author noear
 * @since 3.9.7
 * @deprecated 4.0.4 {@link ReasonEndChunk}
 */
@Deprecated
@Preview("3.9.7")
public class ThoughtChunk extends AbsAgentChunk {
    private final transient ReActTrace trace;
    private final transient @Nullable ChatResponse response;
    private final transient String thoughtContent;
    private final transient AssistantMessage assistantMessage;
    private final String reasonId;

    public ThoughtChunk(ReActTrace trace, @Nullable ChatResponse response, AssistantMessage message, String thoughtContent) {
        super(trace.getRunId(), trace.getAgentName(), trace.getSession(), message);
        this.trace = trace;
        this.response = response;
        this.thoughtContent = thoughtContent;
        this.assistantMessage = message;
        this.reasonId = trace.getCurrentReasonId();
    }

    public ReActTrace getTrace() {
        return trace;
    }

    public ChatResponse getResponse() {
        return response;
    }

    public String getThoughtContent() {
        return thoughtContent;
    }

    public AssistantMessage getAssistantMessage() {
        return assistantMessage;
    }

    public String getReasonId() {
        return reasonId;
    }

    public boolean isToolCalls() {
        return Assert.isNotEmpty(assistantMessage.getToolCalls());
    }

    public List<ToolCall> getToolCalls() {
        return assistantMessage.getToolCalls();
    }
}
