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

/**
 *
 * @author noear
 * @since 4.0.4
 */
public class TaskWrapEvent extends AbsAgentEvent {
    private final ReActTrace parentTrace;
    private final String taskId;
    private final TaskTalent.MultiTaskOp taskOp;
    private final boolean isMultitask;
    private final AgentEvent realEvent;

    public TaskWrapEvent(ReActTrace parentTrace, String taskId, TaskTalent.MultiTaskOp taskOp, boolean isMultitask, AgentEvent realEvent) {
        super(realEvent.getRunId(), realEvent.getAgentName(), realEvent.getSession(), realEvent.getMessage());
        this.parentTrace = parentTrace;
        this.taskId = taskId;
        this.taskOp = taskOp;
        this.isMultitask = isMultitask;
        this.realEvent = realEvent;
    }

    public String getParentRunId(){
        return parentTrace.getRunId();
    }

    public String getTaskId() {
        return taskId;
    }

    public int getTaskIndex() {
        return taskOp.index;
    }

    public String getTaskAgentName() {
        return taskOp.agent_name;
    }

    public String getTaskDescription() {
        return taskOp.description;
    }

    public boolean isMultitask() {
        return isMultitask;
    }

    public AgentEvent getRealEvent() {
        return realEvent;
    }
}