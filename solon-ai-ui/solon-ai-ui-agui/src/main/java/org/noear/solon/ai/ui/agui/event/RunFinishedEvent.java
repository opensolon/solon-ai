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
package org.noear.solon.ai.ui.agui.event;

import org.jspecify.annotations.Nullable;
import org.noear.solon.ai.ui.agui.EventType;
import org.noear.solon.ai.ui.agui.RunOutcome;

/**
 * AG-UI 运行完成事件，表示 Agent 运行结束（正常完成或中断暂停）
 *
 * @author shaoerkuai
 * @since 3.10.5
 * @see <a href="https://docs.ag-ui.com/concepts/events#runfinished">AG-UI RunFinished</a>
 * @see <a href="https://docs.ag-ui.com/concepts/interrupts#run-outcomes">AG-UI Run Outcomes</a>
 */
public class RunFinishedEvent extends Event {
    /** 线程标识 */
    private String threadId;
    /** 运行标识 */
    private String runId;
    /** 运行结果（向后兼容保留） */
    private Object result;
    /** 运行结局（可选，省略视为正常完成） */
    @Nullable
    private RunOutcome outcome;

    public RunFinishedEvent() {
        super(EventType.RUN_FINISHED);
    }

    public String getThreadId() {
        return threadId;
    }

    public void setThreadId(String threadId) {
        this.threadId = threadId;
    }

    public String getRunId() {
        return runId;
    }

    public void setRunId(String runId) {
        this.runId = runId;
    }

    public Object getResult() {
        return result;
    }

    public void setResult(Object result) {
        this.result = result;
    }

    public RunOutcome getOutcome() {
        return outcome;
    }

    public void setOutcome(RunOutcome outcome) {
        this.outcome = outcome;
    }
}
