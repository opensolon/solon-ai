package org.noear.solon.ai.agent.react.task;

import org.noear.solon.ai.agent.AbsAgentChunk;
import org.noear.solon.ai.agent.react.ReActTrace;
import org.noear.solon.ai.chat.message.ChatMessage;

import java.util.Collection;

/**
 *
 * @author noear 2026/7/23 created
 *
 */
public class ActionEndChunk extends AbsAgentChunk {
    private final ReActTrace trace;

    public ActionEndChunk(ReActTrace trace) {
        super(trace.getRunId(), trace.getAgentName(), trace.getSession(), ChatMessage.ofAssistant(""));
        this.trace = trace;
    }

    public ReActTrace getTrace() {
        return trace;
    }
}
