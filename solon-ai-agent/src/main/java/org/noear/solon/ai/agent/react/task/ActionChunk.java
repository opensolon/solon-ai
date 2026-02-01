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
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.flow.Node;
import org.noear.solon.lang.Preview;

/**
 * ReAct 动作块（Acting）：标识智能体正在调用外部工具或执行特定指令
 *
 * @author noear
 * @since 3.9.1
 */
@Preview("3.9.1")
public class ActionChunk extends AbsAgentChunk {
    private final Node node;
    private final ReActTrace trace;

    public ActionChunk(Node node, ReActTrace trace, ChatMessage message) {
        super(trace.getAgentName(), trace.getSession(), message);

        this.node = node;
        this.trace = trace;
    }

    public Node getNode() {
        return node;
    }

    public ReActTrace getTrace() {
        return trace;
    }
}