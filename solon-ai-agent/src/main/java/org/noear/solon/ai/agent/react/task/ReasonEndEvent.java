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
import org.noear.solon.ai.chat.ChatResponse;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.tool.ToolCall;

import java.util.List;

/**
 * ReAct 推理完成（可能同时有思考、文本、工具混合输出）
 *
 * @author noear
 * @since 4.0.4
 */
public class ReasonEndEvent extends AbsReActEvent {
    private final ChatResponse response;
    private final AssistantMessage message;
    private final long durationMs;

    public ReasonEndEvent(ReActTrace trace, ChatResponse response, AssistantMessage message, long durationMs) {
        super(trace);

        this.response = response;
        this.message = message;
        this.durationMs = durationMs;
    }

    public long getDurationMs() {
        return durationMs;
    }

    public ChatResponse getResponse() {
        return response;
    }

    public AssistantMessage getMessage() {
        return message;
    }

    /**
     * 获取思考
     */
    public String getThinking() {
        return message.getThinking();
    }

    /**
     * 获取文本
     */
    @Override
    public String getText() {
        return message.getText();
    }

    public boolean isToolCalls() {
        return message.isToolCalls();
    }

    /**
     * 获取工具
     */
    public List<ToolCall> getToolCalls() {
        return message.getToolCalls();
    }
}
