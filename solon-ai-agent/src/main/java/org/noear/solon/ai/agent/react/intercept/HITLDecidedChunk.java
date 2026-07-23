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
 * HITL 决策生效块：人工审批结果已应用（批准 / 拒绝 / 跳过）
 *
 * <p>由 {@link HITLInterceptor} 在决策生效时推送，
 * 方便前端通过类型直接关闭或更新审批卡片，并按决策结果上色展示。
 *
 * @author noear
 * @since 4.0.4
 */
@Preview("4.0.4")
public class HITLDecidedChunk extends AbsAgentChunk {
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
     * 生效参数快照（批准时可能含修正后的参数）
     */
    private final Map<String, Object> args;
    /**
     * 审批备注
     */
    private final @Nullable String comment;
    /**
     * 审批决策（非空）
     */
    private final HITLDecision decision;

    public HITLDecidedChunk(ReActTrace trace,
                            @Nullable String callId,
                            String toolName,
                            Map<String, Object> args,
                            HITLDecision decision) {
        super(trace.getRunId(),
                trace.getAgentName(),
                trace.getSession(),
                ChatMessage.ofAssistant(""));

        this.trace = trace;
        this.callId = callId;
        this.toolName = toolName;
        // 独立浅拷贝：避免与 toolExchanger.args 共享底层 Map，保证 chunk 参数快照不被后续链路修改
        if (args == null || args.isEmpty()) {
            this.args = Collections.emptyMap();
        } else {
            this.args = Collections.unmodifiableMap(new LinkedHashMap<>(args));
        }
        this.comment = decision.getComment();
        this.decision = decision;
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
     * 获取生效参数快照
     */
    public Map<String, Object> getArgs() {
        return args;
    }

    /**
     * 获取审批备注
     */
    public @Nullable String getComment() {
        return comment;
    }

    /**
     * 获取审批决策
     */
    public HITLDecision getDecision() {
        return decision;
    }

    /**
     * 是否已批准
     */
    public boolean isApproved() {
        return decision.isApproved();
    }

    /**
     * 是否已拒绝
     */
    public boolean isRejected() {
        return decision.isRejected();
    }

    /**
     * 是否已跳过
     */
    public boolean isSkipped() {
        return decision.isSkipped();
    }
}
