/*
 * Copyright 2017-2026 noear.org and authors
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
package org.noear.solon.ai.harness.agent;

import org.noear.solon.ai.agent.AbsAgentEvent;
import org.noear.solon.ai.agent.AgentEvent;
import org.noear.solon.ai.agent.react.ReActTrace;
import org.noear.solon.lang.Nullable;

/**
 * 子代理（任务）事件包装：{@link TaskTalent} 委派出去的子代理每产生一个事件，都包成本事件转发到父代理的事件流。
 *
 * <p>宿主据此把子任务的输出挂到父轮次下展示：{@link #getParentRunId()} 定位父轮次，
 * {@link #getTaskId()} 区分各个子任务，
 * {@link #getParentCallId()} 区分同一子任务的各次调用（续跑沿用 taskId，但每次调用的父工具调用不同），
 * {@link #getRealEvent()} 是子代理原始事件。</p>
 *
 * <p>宿主分组建议以 {@code parentCallId + taskId} 为键：每次 task / task_start 调用各有自己的 parentCallId，
 * 同一子任务的多次续跑共用 taskId；parentCallId 为 null 时退回按 taskId 分组。</p>
 *
 * <p>{@link #isBackground()} 区分委派方式：同步 task 的事件都在父代理等待它的期间产生；后台 task_start 的事件与父代理
 * 自己的事件交错到达（发起它的工具调用早已返回），本轮结束时未取回的会被取消。</p>
 *
 * @author noear
 * @since 4.0.4
 */
public class TaskWrapEvent extends AbsAgentEvent {
    private final ReActTrace parentTrace;
    private final String parentCallId;
    private final String taskId;
    private final TaskTalent.SingleTaskOp taskOp;
    private final boolean background;
    private final AgentEvent realEvent;

    /**
     * @param parentCallId 发起本次委派的父 task / task_start 工具调用 id（取不到时为 null）
     * @param background   是否为后台任务（由 task_start 发起）
     */
    public TaskWrapEvent(ReActTrace parentTrace, @Nullable String parentCallId, String taskId, TaskTalent.SingleTaskOp taskOp,
                         boolean background, AgentEvent realEvent) {
        super(realEvent.getRunId(), realEvent.getAgentName(), realEvent.getSession());
        this.parentTrace = parentTrace;
        this.parentCallId = parentCallId;
        this.taskId = taskId;
        this.taskOp = taskOp;
        this.background = background;
        this.realEvent = realEvent;
    }

    public String getParentRunId(){
        return parentTrace.getRunId();
    }

    /**
     * 发起本次委派的父 task / task_start 工具调用 id（等于父流 tool.start 的 callId、父 ToolCall 的 uuid）；
     * 文本模式或测试直调时取不到，为 null。
     *
     * <p>委派开始时在调用线程里取定，不随父 trace 的执行状态变化（父 trace 的 actingCallId 只在工具执行期间有值）。</p>
     *
     * @since 4.2
     */
    public @Nullable String getParentCallId() {
        return parentCallId;
    }

    public String getTaskId() {
        return taskId;
    }

    public String getTaskAgentName() {
        return taskOp.agent_name;
    }

    public String getTaskDescription() {
        return taskOp.description;
    }

    /**
     * 是否为后台任务：由 task_start 发起为 true，由同步 task 发起为 false。
     *
     * <p>同一子任务续跑时可以换方式（先 task_start、再带 task_id 用 task 接着做），以本次调用为准。</p>
     *
     * @since 4.2.0
     */
    public boolean isBackground() {
        return background;
    }

    public AgentEvent getRealEvent() {
        return realEvent;
    }

    @Override
    public String getText() {
        return realEvent.getText();
    }
}