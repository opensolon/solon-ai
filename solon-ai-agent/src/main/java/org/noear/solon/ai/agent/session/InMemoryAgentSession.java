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
package org.noear.solon.ai.agent.session;

import org.noear.solon.ai.agent.Agent;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.flow.FlowContext;

import java.util.*;

/**
 * 内存智能体会话适配
 *
 * @author noear
 * @since 3.8.1
 */
public class InMemoryAgentSession implements AgentSession {
    public static AgentSession of() {
        return new InMemoryAgentSession("tmp");
    }

    public static AgentSession of(String instanceId) {
        return new InMemoryAgentSession(instanceId);
    }

    public static AgentSession of(FlowContext context) {
        return new InMemoryAgentSession(context);
    }


    private final String sessionId;
    private final Map<String, List<ChatMessage>> historyMessages = new LinkedHashMap<>();
    private FlowContext snapshot;

    public InMemoryAgentSession(String sessionId) {
        this.sessionId = sessionId;
        this.snapshot = FlowContext.of(sessionId);
        this.snapshot.put(Agent.KEY_SESSION, this);
    }

    public InMemoryAgentSession(FlowContext context) {
        this.sessionId = context.getInstanceId();
        this.snapshot = context;
        this.snapshot.put(Agent.KEY_SESSION, this);
    }

    @Override
    public String getSessionId() {
        return sessionId;
    }


    @Override
    public void addHistoryMessage(String agentName, ChatMessage message) {
        historyMessages.computeIfAbsent(agentName, k -> new ArrayList<>())
                .add(message);
    }

    @Override
    public Collection<ChatMessage> getHistoryMessages(String agentName, int last) {
        List<ChatMessage> list = historyMessages.get(agentName);

        if (list != null) {
            if (list.size() > last) {
                return list.subList(list.size() - last, list.size());
            } else {
                return list;
            }
        }

        return Collections.emptyList();
    }

    @Override
    public void updateSnapshot(FlowContext snapshot) {
        this.snapshot = snapshot;
    }

    @Override
    public FlowContext getSnapshot() {
        return snapshot;
    }
}
