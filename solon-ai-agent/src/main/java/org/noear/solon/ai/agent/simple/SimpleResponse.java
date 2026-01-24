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
package org.noear.solon.ai.agent.simple;

import org.noear.solon.ai.agent.AgentResponse;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.trace.Metrics;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.flow.FlowContext;

/**
 * 简单智能体交互响应
 *
 * @author noear
 * @since 3.8.4
 */
public class SimpleResponse implements AgentResponse {
    private final AgentSession session;
    private final SimpleTrace trace;
    private final AssistantMessage message;

    public SimpleResponse(AgentSession session, SimpleTrace trace, AssistantMessage message) {
        this.session = session;
        this.trace = trace;
        this.message = message;
    }

    public SimpleTrace getTrace() {
        return trace;
    }

    @Override
    public AgentSession getSession() {
        return session;
    }

    @Override
    public FlowContext getContext() {
        return session.getSnapshot();
    }

    @Override
    public Metrics getMetrics() {
        return trace.getMetrics();
    }

    @Override
    public AssistantMessage getMessage() {
        return message;
    }

    @Override
    public String getContent() {
        return message.getContent();
    }

    @Override
    public <T> T toBean(Class<T> type) {
        return message.toBean(type);
    }
}