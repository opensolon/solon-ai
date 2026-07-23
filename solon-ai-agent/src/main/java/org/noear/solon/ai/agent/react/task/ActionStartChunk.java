package org.noear.solon.ai.agent.react.task;

import org.noear.solon.ai.agent.AbsAgentChunk;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.react.ReActTrace;
import org.noear.solon.ai.chat.message.ChatMessage;

import java.util.Collection;

/**
 *
 * @author noear 2026/7/23 created
 *
 */
public class ActionStartChunk extends AbsAgentChunk {
    private final ReActTrace trace;
    private final Collection<ToolExchanger> toolCalls;

    public ActionStartChunk(ReActTrace trace, Collection<ToolExchanger> toolCalls) {
        super(trace.getRunId(), trace.getAgentName(), trace.getSession(), ChatMessage.ofAssistant(""));

        this.trace = trace;
        this.toolCalls = toolCalls;
    }

    public ReActTrace getTrace() {
        return trace;
    }

    public Collection<ToolExchanger> getToolCalls() {
        return toolCalls;
    }
}
