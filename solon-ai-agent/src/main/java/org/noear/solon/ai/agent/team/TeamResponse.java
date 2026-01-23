package org.noear.solon.ai.agent.team;

import org.noear.solon.ai.agent.trace.Metrics;
import org.noear.solon.ai.chat.message.AssistantMessage;

/**
 * 团队协作响应
 *
 * @author noear
 * @since 3.8.4
 */
public class TeamResponse {
    private final TeamTrace trace;
    private final AssistantMessage message;

    public TeamResponse(TeamTrace trace, AssistantMessage message) {
        this.trace = trace;
        this.message = message;
    }

    public TeamTrace getTrace() {
        return trace;
    }

    public Metrics getMetrics() {
        return trace.getMetrics();
    }

    public AssistantMessage getMessage() {
        return message;
    }

    public String getContent() {
        if (message == null) {
            return "";
        }

        return message.getContent();
    }
}
