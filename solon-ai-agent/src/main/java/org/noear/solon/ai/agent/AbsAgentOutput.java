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
package org.noear.solon.ai.agent;

import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.lang.Preview;

/**
 * 智能体输出基类
 *
 * @author noear
 * @since 3.9.1
 */
@Preview("3.9.1")
public abstract class AbsAgentOutput implements AgentOutput {
    protected final String nodeId;
    protected final String agentName;
    protected final AgentSession session;
    protected final ChatMessage message;

    public AbsAgentOutput(String nodeId, String agentName, AgentSession session, ChatMessage message) {
        this.nodeId = nodeId;
        this.agentName = agentName;
        this.session = session;
        this.message = message;
    }

    @Override
    public String getNodeId() {
        return nodeId;
    }

    @Override
    public String getAgentName() {
        return agentName;
    }

    @Override
    public AgentSession getSession() {
        return session;
    }

    @Override
    public ChatMessage getMessage() {
        return message;
    }
}
