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
 *
 * @author noear
 * @since 4.1
 */
public class AbsReActEvent extends AbsAgentEvent {
    protected final transient ReActTrace trace;

    public AbsReActEvent(ReActTrace trace) {
        super(trace.getRunId(), trace.getAgentName(), trace.getSession());

        this.trace = trace;
    }

    public ReActTrace getTrace() {
        return trace;
    }
}