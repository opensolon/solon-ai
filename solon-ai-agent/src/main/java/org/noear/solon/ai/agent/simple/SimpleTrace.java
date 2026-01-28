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

import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.AgentTrace;
import org.noear.solon.ai.agent.team.TeamProtocol;
import org.noear.solon.ai.agent.trace.Metrics;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.flow.FlowContext;

/**
 * Simple 运行轨迹记录器 (状态机上下文)
 * <p>负责维护智能体推理过程中的短期记忆、执行路由、消息序列及上下文压缩。</p>
 *
 * @author noear
 * @since 3.8.4
 */
public class SimpleTrace implements AgentTrace {
    private transient SimpleAgentConfig config;
    private transient AgentSession session;
    private transient TeamProtocol protocol;

    private Prompt originalPrompt;
    private final Metrics metrics = new Metrics();

    public SimpleTrace() {
        this.originalPrompt = null;
    }

    public SimpleTrace(Prompt originalPrompt) {
        this.originalPrompt = originalPrompt;
    }

    protected void prepare(SimpleAgentConfig config, AgentSession session, TeamProtocol protocol) {
        this.config = config;
        this.session = session;
        this.protocol = protocol;
    }


    public SimpleAgentConfig getConfig() {
        return config;
    }

    public AgentSession getSession() {
        return session;
    }

    public TeamProtocol getProtocol() {
        return protocol;
    }

    public FlowContext getContext() {
        if (session != null) {
            return session.getSnapshot();
        } else {
            return null;
        }
    }

    protected void setOriginalPrompt(Prompt prompt) {
        this.originalPrompt = prompt;
    }

    public Prompt getOriginalPrompt() {
        return originalPrompt;
    }

    @Override
    public Metrics getMetrics() {
        return metrics;
    }
}
