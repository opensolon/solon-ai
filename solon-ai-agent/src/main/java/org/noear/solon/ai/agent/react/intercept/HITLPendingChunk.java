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
package org.noear.solon.ai.agent.react.intercept;

import org.noear.solon.ai.agent.AbsAgentChunk;
import org.noear.solon.ai.agent.react.ReActTrace;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.lang.Nullable;
import org.noear.solon.lang.Preview;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * HITL 挂起审查块：工具调用被拦截、会话进入 pending，等待人工决策
 *
 * <p>由 {@link HITLInterceptor} 在首次拦截挂起时推送，
 * 方便前端通过类型直接渲染审批卡片（工具名、参数、拦截理由）。
 *
 * @author noear
 * @since 4.0.4
 */
@Preview("4.0.4")
public class HITLPendingChunk extends AbsAgentChunk {
    private final transient ReActTrace trace;
    /**
     * 关联的工具调用 ID（可与 ActionChunk/ToolStartChunk 对齐）
     */
    private final @Nullable String callId;
    /**
     * 拟调用的工具名
     */
    private final String toolName;
    /**
     * 拟调用的参数快照
     */
    private final Map<String, Object> args;
    /**
     * 拦截理由
     */
    private final @Nullable String comment;
    /**
     * 挂起任务快照（非空）
     */
    private final HITLTask task;

    public HITLPendingChunk(ReActTrace trace, @Nullable String callId, HITLTask task) {
        super(trace.getRunId(),
                trace.getAgentName(),
                trace.getSession(),
                ChatMessage.ofAssistant(""));

        this.trace = trace;
        this.callId = callId;
        this.toolName = task.getToolName();
        // 独立浅拷贝：与 task.args 解耦，避免业务改 task 时污染 chunk 快照
        if (task.getArgs() == null || task.getArgs().isEmpty()) {
            this.args = Collections.emptyMap();
        } else {
            this.args = Collections.unmodifiableMap(new LinkedHashMap<>(task.getArgs()));
        }
        this.comment = task.getComment();
        this.task = task;
    }

    public ReActTrace getTrace() {
        return trace;
    }

    /**
     * 获取关联的工具调用 ID
     */
    public @Nullable String getCallId() {
        return callId;
    }

    /**
     * 获取工具名
     */
    public String getToolName() {
        return toolName;
    }

    /**
     * 获取参数快照
     */
    public Map<String, Object> getArgs() {
        return args;
    }

    /**
     * 获取拦截理由
     */
    public @Nullable String getComment() {
        return comment;
    }

    /**
     * 获取挂起任务快照
     */
    public HITLTask getTask() {
        return task;
    }
}
