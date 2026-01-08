package org.noear.solon.ai.agent.react;

import org.noear.solon.ai.agent.AgentRequest;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.flow.FlowContext;

/**
 *
 * @author noear 2026/1/8 created
 *
 */
public class ReActRequestImpl implements AgentRequest {
    private final ReActAgent agent;
    private final Prompt prompt;
    private AgentSession session;

    public ReActRequestImpl(ReActAgent agent, Prompt prompt) {
        this.agent = agent;
        this.prompt = prompt;
    }

    @Override
    public AgentRequest session(AgentSession session) {
        this.session = session;
        return this;
    }

    @Override
    public AgentRequest session(FlowContext context) {
        this.session = InMemoryAgentSession.of(context);
        return this;
    }

    @Override
    public AssistantMessage call() throws Throwable {
        if (session == null) {
            session = InMemoryAgentSession.of();
        }

        return agent.call(session, prompt);
    }
}
