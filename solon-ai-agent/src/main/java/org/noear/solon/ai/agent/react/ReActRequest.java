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
package org.noear.solon.ai.agent.react;

import org.noear.solon.ai.agent.AgentRequest;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.lang.NonSerializable;
import org.noear.solon.lang.Preview;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Consumer;

/**
 * ReAct 模式推理请求
 * <p>采用 Fluent API 风格，封装了 Agent 调用的完整参数（Prompt、Session、Options）</p>
 *
 * @author noear
 * @since 3.8.1
 */
@Preview("3.8.1")
public class ReActRequest implements AgentRequest<ReActRequest, ReActResponse> {
    private static final Logger log = LoggerFactory.getLogger(ReActRequest.class);

    private final ReActAgent agent;
    private final Prompt prompt;
    private AgentSession session;
    private ReActOptions options;

    public ReActRequest(ReActAgent agent, Prompt prompt) {
        this.agent = agent;
        this.prompt = prompt;
        // 初始拷贝 Agent 的默认配置，实现请求级别的隔离
        this.options = agent.getConfig().getDefaultOptions().copy();
    }

    /**
     * 绑定会话实例
     */
    @Override
    public ReActRequest session(AgentSession session) {
        this.session = session;
        return this;
    }

    /**
     * 修改当前请求的运行选项
     */
    public ReActRequest options(Consumer<ReActOptionsAmend> adjustor) {
        adjustor.accept(new ReActOptionsAmend(options));
        return this;
    }

    /**
     * 执行同步调用
     */
    @Override
    public ReActResponse call() throws Throwable {
        if (session == null) {
            if (log.isDebugEnabled()) {
                log.debug("No session provided for ReActRequest, using temporary InMemoryAgentSession.");
            }
            session = InMemoryAgentSession.of();
        }

        AssistantMessage message = agent.call(prompt, session, options);
        ReActTrace trace = session.getSnapshot().getAs(agent.getConfig().getTraceKey());

        return new ReActResponse(session, trace, message);
    }
}