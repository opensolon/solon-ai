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
import org.noear.solon.flow.Node;
import org.noear.solon.lang.Preview;

/**
 * ReAct 计划块（Planning）：包含智能体生成的任务拆解或步骤规划
 *
 * @author noear
 * @since 3.9.1
 */
@Preview("3.9.1")
public class PlanChunk extends AbsAgentChunk {
    private final transient Node node;
    private final transient ReActTrace trace;
    private final transient ChatResponse response;

    public PlanChunk(Node node, ReActTrace trace, ChatResponse response) {
        super(trace.getAgentName(), trace.getSession(), response.getMessage());

        this.node = node;
        this.trace = trace;
        this.response = response;
    }

    public Node getNode() {
        return node;
    }

    public ReActTrace getTrace() {
        return trace;
    }

    public ChatResponse getResponse() {
        return response;
    }
}