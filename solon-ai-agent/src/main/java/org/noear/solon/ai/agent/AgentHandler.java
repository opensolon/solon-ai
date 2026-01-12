package org.noear.solon.ai.agent;

import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.prompt.Prompt;

/**
 *
 * @author noear 2026/1/12 created
 *
 */
public interface AgentHandler {
    AssistantMessage call(Prompt prompt, AgentSession session) throws Throwable;
}
