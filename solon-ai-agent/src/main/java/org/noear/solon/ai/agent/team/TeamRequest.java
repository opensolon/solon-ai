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

import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.lang.Preview;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Consumer;

/**
 * 团队协作请求（Fluent API 包装器）
 *
 * <p>核心职责：为 {@link TeamAgent} 的调用提供流式配置能力，包括会话绑定与运行时选项修正。</p>
 *
 * @author noear
 * @since 3.8.1
 */
@Preview("3.8.1")
public class TeamRequest {
    private static final Logger LOG = LoggerFactory.getLogger(TeamRequest.class);

    private final TeamAgent agent;
    private final Prompt prompt;
    private AgentSession session;
    private TeamOptions options;

    public TeamRequest(TeamAgent agent, Prompt prompt) {
        this.agent = agent;
        this.prompt = prompt;
        // 拷贝默认配置，确保当前请求的选项修改不影响全局配置
        this.options = agent.getConfig().getDefaultOptions().copy();
    }

    /**
     * 绑定执行会话（用于持久化记忆或上下文关联）
     */
    public TeamRequest session(AgentSession session) {
        this.session = session;
        return this;
    }

    /**
     * 修正运行时选项（如调整迭代次数、增加拦截器等）
     */
    public TeamRequest options(Consumer<TeamOptionsAmend> optionsAmend) {
        optionsAmend.accept(new TeamOptionsAmend(options));
        return this;
    }

    /**
     * 发起同步调用
     *
     * @return AI 团队协作后的最终响应消息
     */
    public TeamResponse call() throws Throwable {
        if (session == null) {
            if (LOG.isDebugEnabled()) {
                LOG.debug("No session provided for TeamRequest, using temporary InMemoryAgentSession.");
            }
            session = InMemoryAgentSession.of();
        }

        AssistantMessage message = agent.call(prompt, session, options);
        TeamTrace trace = session.getSnapshot().getAs(agent.getConfig().getTraceKey());

        return new TeamResponse(trace, message);
    }
}