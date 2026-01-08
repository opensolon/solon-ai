package org.noear.solon.ai.agent;

import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.flow.FlowContext;

/**
 *
 * @author noear 2026/1/8 created
 *
 */
public interface AgentRequest {
    AgentRequest session(AgentSession session);

    AgentRequest session(FlowContext context);

    AssistantMessage call() throws Throwable;
}
