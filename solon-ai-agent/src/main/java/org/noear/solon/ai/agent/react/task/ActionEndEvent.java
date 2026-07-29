package org.noear.solon.ai.agent.react.task;

import org.noear.solon.ai.agent.AbsAgentEvent;
import org.noear.solon.ai.agent.react.ReActTrace;
import org.noear.solon.ai.chat.message.ChatMessage;

/**
 *
 * @author noear 2026/7/23 created
 *
 */
public class ActionEndEvent extends AbsAgentEvent {
    private final ReActTrace trace;

    public ActionEndEvent(ReActTrace trace) {
        super(trace.getRunId(), trace.getAgentName(), trace.getSession(), ChatMessage.ofAssistant(""));
        this.trace = trace;
    }

    public ReActTrace getTrace() {
        return trace;
    }
}
