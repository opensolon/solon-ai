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

import org.noear.solon.ai.agent.AbsAgentChunk;
import org.noear.solon.ai.agent.react.ReActTrace;
import org.noear.solon.ai.chat.ChatResponse;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.tool.ToolCall;
import org.noear.solon.core.util.Assert;
import org.noear.solon.lang.Preview;

import java.util.List;

/**
 * ReAct 思考块（Reasoning）：包含智能体对当前问题的逻辑分析流
 *
 * @author noear
 * @since 3.9.1
 */
@Preview("3.9.1")
public class ReasonChunk extends AbsAgentChunk {
    private final transient ReActTrace trace;
    private final transient ChatResponse response;
    private final transient AssistantMessage assistantMessage;

    public ReasonChunk(ReActTrace trace, ChatResponse response, AssistantMessage assistantMessage) {
        super(trace.getAgentName(), trace.getSession(), assistantMessage);
        this.trace = trace;
        this.response = response;
        this.assistantMessage = assistantMessage;
    }

    public ReActTrace getTrace() {
        return trace;
    }

    /**
     * 是否已完成
     */
    public boolean isFinished() {
        if (response == null) {
            return true;
        } else {
            return response.isFinished();
        }
    }

    /**
     * 是否异常结束
     */
    public boolean isAbnormal() {
        return response == null;
    }

    /**
     * 是否为工具调用
     */
    public boolean isToolCalls() {
        return Assert.isNotEmpty(assistantMessage.getToolCalls());
    }

    /**
     * 获取工具调用
     */
    public List<ToolCall> getToolCalls() {
        return assistantMessage.getToolCalls();
    }
}