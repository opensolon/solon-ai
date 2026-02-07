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

import org.noear.solon.ai.agent.AgentChunk;
import org.noear.solon.ai.agent.AgentRequest;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.lang.Preview;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

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
public class TeamRequest implements AgentRequest<TeamRequest, TeamResponse> {
    private static final Logger LOG = LoggerFactory.getLogger(TeamRequest.class);

    private final TeamAgent agent;
    private final Prompt prompt;
    private AgentSession session;
    private TeamOptions options;
    private Consumer<TeamOptionsAmend> optionsAdjustor;

    public TeamRequest(TeamAgent agent, Prompt prompt) {
        this.agent = agent;
        this.prompt = prompt;
    }

    /**
     * 绑定执行会话（用于持久化记忆或上下文关联）
     */
    @Override
    public TeamRequest session(AgentSession session) {
        this.session = session;
        return this;
    }

    /**
     * 修正运行时选项（如调整迭代次数、增加拦截器等）
     */
    public TeamRequest options(Consumer<TeamOptionsAmend> adjustor) {
        optionsAdjustor = adjustor;
        return this;
    }

    private void init() {
        if (options != null) {
            return; // 已经初始化过了，不再重复逻辑
        }

        if (session == null) {
            if (LOG.isDebugEnabled()) {
                LOG.debug("No session provided for TeamRequest, using temporary InMemoryAgentSession.");
            }
            session = InMemoryAgentSession.of();
        }

        TeamTrace trace = agent.getTrace(session);
        if (trace != null) {
            options = trace.getOptions();
        }

        if (options == null) {
            options = agent.getConfig().getDefaultOptions().copy();
        }

        if (optionsAdjustor != null) {
            optionsAdjustor.accept(new TeamOptionsAmend(options));
        }
    }

    /**
     * 发起同步调用
     *
     * @return AI 团队协作后的最终响应消息
     */
    @Override
    public TeamResponse call() throws Throwable {
        init();

        AssistantMessage message = agent.call(prompt, session, options);
        TeamTrace trace = agent.getTrace(session);

        return new TeamResponse(session, trace, message);
    }

    public Flux<AgentChunk> stream() {
        init();

        return Flux.<AgentChunk>create(sink -> {
            try {
                options.setStreamSink(sink);
                AssistantMessage message = agent.call(prompt, session, options);
                TeamTrace trace = agent.getTrace(session);

                TeamResponse resp = new TeamResponse(session, trace, message);

                sink.next(new TeamChunk(resp));
                sink.complete();
            } catch (Throwable e) {
                sink.error(e);
            }
        });
    }
}