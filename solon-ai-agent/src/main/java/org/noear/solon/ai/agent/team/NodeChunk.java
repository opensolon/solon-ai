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
package org.noear.solon.ai.agent.team;

import org.noear.solon.ai.agent.AbsAgentChunk;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.flow.Node;

/**
 * 协作节点片段块（图执行状态块）
 * <p>用于在团队计算图（Graph）执行过程中，实时传递当前执行节点的状态、消息增量及团队轨迹</p>
 *
 * @author noear
 * @since 3.9.1
 */
public class NodeChunk extends AbsAgentChunk {
    private final TeamTrace trace;
    private final Node node;

    public NodeChunk(Node node, TeamTrace trace, ChatMessage message) {
        super(trace.getAgentName(), trace.getSession(), message);
        this.trace = trace;
        this.node = node;
    }

    /**
     * 获取关联的团队执行轨迹
     */
    public TeamTrace getTrace() {
        return trace;
    }

    /**
     * 获取当前正在执行的流程节点信息
     */
    public Node getNode() {
        return node;
    }
}