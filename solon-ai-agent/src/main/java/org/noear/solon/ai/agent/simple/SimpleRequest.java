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
package org.noear.solon.ai.agent.simple;

import org.noear.solon.ai.agent.AgentChunk;
import org.noear.solon.ai.agent.AgentRequest;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.chat.ChatOptions;
import org.noear.solon.ai.chat.ModelOptionsAmend;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.lang.Preview;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

import java.util.function.Consumer;

/**
 * 简单智能体交互请求（Fluent API 包装器）
 *
 * <p>提供链式配置能力，负责绑定 {@link AgentSession} 记忆上下文并支持运行时 {@link ChatOptions} 的动态修正。</p>
 *
 * @author noear
 * @since 3.8.1
 */
@Preview("3.8.1")
public class SimpleRequest implements AgentRequest<SimpleRequest, SimpleResponse> {
    private static final Logger LOG = LoggerFactory.getLogger(SimpleRequest.class);

    private final SimpleAgent agent;
    private final Prompt prompt;
    private AgentSession session;
    private ModelOptionsAmend<?, SimpleInterceptor> options;

    public SimpleRequest(SimpleAgent agent, Prompt prompt) {
        this.agent = agent;
        this.prompt = prompt;
        this.options = new ModelOptionsAmend<>();
        this.options.putAll(agent.getConfig().getDefaultOptions());
    }

    /**
     * 关联执行会话（用于持久化短期记忆与上下文快照）
     */
    @Override
    public SimpleRequest session(AgentSession session) {
        this.session = session;
        return this;
    }

    /**
     * 配置运行时选项（如调整 Temperature、MaxTokens 等参数）
     */
    public SimpleRequest options(Consumer<ModelOptionsAmend<?, SimpleInterceptor>> chatOptionsAdjustor) {
        chatOptionsAdjustor.accept(options);
        return this;
    }

    /**
     * 启动智能体调用流程
     */
    @Override
    public SimpleResponse call() throws Throwable {
        if (session == null) {
            if (LOG.isDebugEnabled()) {
                LOG.debug("No session provided for SimpleRequest, using temporary InMemoryAgentSession.");
            }
            // 自动降级为临时的内存会话
            session = InMemoryAgentSession.of();
        }

        AssistantMessage message = agent.call(prompt, session, options);
        SimpleTrace trace = session.getSnapshot().getAs(agent.getConfig().getTraceKey());

        return new SimpleResponse(session, trace, message);
    }

    public Flux<AgentChunk> stream() {
        return null;
    }
}