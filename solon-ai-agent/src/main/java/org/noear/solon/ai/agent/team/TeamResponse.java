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
package org.noear.solon.ai.agent.team;

import org.noear.solon.ai.agent.AgentResponse;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.trace.Metrics;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.flow.FlowContext;

/**
 * 团队协作响应
 *
 * @author noear
 * @since 3.8.4
 */
public class TeamResponse implements AgentResponse {
    private final AgentSession session;
    private final TeamTrace trace;
    private final AssistantMessage message;

    public TeamResponse(AgentSession session, TeamTrace trace, AssistantMessage message) {
        this.session = session;
        this.trace = trace;
        this.message = message;
    }

    public AgentSession getSession() {
        return session;
    }

    public FlowContext getContext(){
        return session.getSnapshot();
    }

    public TeamTrace getTrace() {
        return trace;
    }

    public Metrics getMetrics() {
        return trace.getMetrics();
    }

    public AssistantMessage getMessage() {
        return message;
    }

    public String getContent() {
        return message.getContent();
    }

    public <T> T toBean(Class<T> type) {
        return message.toBean(type);
    }
}
