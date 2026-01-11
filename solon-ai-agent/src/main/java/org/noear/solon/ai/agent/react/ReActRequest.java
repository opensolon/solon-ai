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

import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.lang.NonSerializable;
import org.noear.solon.lang.Preview;

import java.util.function.Consumer;

/**
 * ReAct 模式请求
 *
 * @author noear
 * @since 3.8.1
 */
@Preview("3.8.1")
public class ReActRequest implements NonSerializable {
    private final ReActAgent agent;
    private final Prompt prompt;
    private AgentSession session;
    private ReActOptions options;

    public ReActRequest(ReActAgent agent, Prompt prompt) {
        this.agent = agent;
        this.prompt = prompt;
        this.options = agent.getConfig().getDefaultOptions().copy();
    }

    public ReActRequest session(AgentSession session) {
        this.session = session;
        return this;
    }

    public ReActRequest options(Consumer<ReActOptionsAmend> adjustor) {
        adjustor.accept(new ReActOptionsAmend(options));
        return this;
    }

    public AssistantMessage call() throws Throwable {
        if (session == null) {
            session = InMemoryAgentSession.of();
        }

        return agent.call(prompt, session, options);
    }
}
