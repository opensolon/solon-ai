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

import org.noear.solon.Utils;
import org.noear.solon.ai.agent.Agent;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.chat.ChatRole;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.session.InMemoryChatSession;
import org.noear.solon.flow.FlowContext;
import org.noear.solon.lang.Preview;

import java.util.*;

/**
 * 内存型智能体会话 (In-Memory Session)
 * * <p>管理单次协作的生命周期数据：
 * 1. 隔离的短期记忆：按 Agent 维度存储历史消息。
 * 2. 状态快照：承载业务流变量上下文。</p>
 */
@Preview("3.8.1")
public class InMemoryAgentSession extends InMemoryChatSession implements AgentSession {
    private volatile FlowContext snapshot;

    public static InMemoryAgentSession of() {
        return new InMemoryAgentSession("tmp");
    }

    public static InMemoryAgentSession of(String sessionId) {
        return new InMemoryAgentSession(sessionId);
    }

    public static InMemoryAgentSession of(String sessionId, int maxMessages) {
        return new InMemoryAgentSession(sessionId, maxMessages);
    }

    public static InMemoryAgentSession of(FlowContext context) {
        return new InMemoryAgentSession(context);
    }

    public InMemoryAgentSession(String sessionId) {
        super(sessionId == null ? "tmp" : sessionId);
        this.snapshot = FlowContext.of(getSessionId());
        this.snapshot.put(Agent.KEY_SESSION, this);
    }

    public InMemoryAgentSession(String sessionId, int maxMessages) {
        super(sessionId == null ? "tmp" : sessionId, maxMessages);
        this.snapshot = FlowContext.of(getSessionId());
        this.snapshot.put(Agent.KEY_SESSION, this);
    }
    public InMemoryAgentSession(FlowContext context) {
        super(context.getInstanceId(), 0);
        this.snapshot = context;
        this.snapshot.put(Agent.KEY_SESSION, this);
    }

    @Override
    public void addMessage(Collection<? extends ChatMessage> messages) {
        if (Utils.isNotEmpty(messages)) {
            for (ChatMessage m : messages) {
                if (m.getRole() != ChatRole.SYSTEM) {
                    //禁止加入 system 消息
                    this.messages.add(m);
                }
            }

            //处理最大消息数
            if (maxMessages > 0 && this.messages.size() > maxMessages) {
                //移除非SystemMessage
                removeNonSystemMessages(messages.size());
            }
        }
    }

    @Override
    public void updateSnapshot() {
    }

    @Override
    public FlowContext getSnapshot() {
        return snapshot;
    }
}