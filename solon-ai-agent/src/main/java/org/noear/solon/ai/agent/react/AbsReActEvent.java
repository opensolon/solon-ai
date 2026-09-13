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
package org.noear.solon.ai.agent.react;

import org.noear.solon.ai.agent.AbsAgentEvent;

/**
 * ReAct 事件基类
 *
 * <p>除了携带运行轨迹 {@link ReActTrace} 外，还在构造时对 {@code trace} 的当前回合Id
 * 做一次快照，使每个事件都能稳定回答“我属于哪个推理回合”，且不受后续回合切换影响。</p>
 *
 * @author noear
 * @since 4.1
 */
public class AbsReActEvent extends AbsAgentEvent {
    protected final transient ReActTrace trace;
    private final String turnId;

    public AbsReActEvent(ReActTrace trace) {
        super(trace.getRunId(), trace.getAgentName(), trace.getSession());

        this.trace = trace;
        this.turnId = trace.getCurrentTurnId();
    }

    public ReActTrace getTrace() {
        return trace;
    }

    /**
     * 获取事件所属的回合Id（构造时的快照）
     */
    public String getTurnId() {
        return turnId;
    }

    /**
     * @deprecated 4.1 请改用 {@link #getTurnId()}
     */
    @Deprecated
    public String getReasonId() {
        return turnId;
    }
}