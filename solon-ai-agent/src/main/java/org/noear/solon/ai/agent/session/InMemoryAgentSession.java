package org.noear.solon.ai.agent.session;

import org.noear.solon.ai.agent.Agent;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.flow.FlowContext;
import org.noear.solon.flow.FlowContextDefault;

import java.util.*;

/**
 *
 * @author noear 2026/1/8 created
 *
 */
public class InMemoryAgentSession implements AgentSession {
    public static AgentSession of() {
        return new InMemoryAgentSession("tmp");
    }

    public static AgentSession of(String instanceId) {
        return new InMemoryAgentSession(instanceId);
    }

    public static AgentSession of(FlowContext context) {
        return new InMemoryAgentSession(context);
    }


    private final String sessionId;
    private final Map<String, List<ChatMessage>> historyMessages = new LinkedHashMap<>();
    private FlowContext snapshot;

    public InMemoryAgentSession(String sessionId) {
        this.sessionId = sessionId;
        this.snapshot = FlowContext.of(sessionId);
        this.snapshot.put(Agent.ID_SESSION, this);
    }

    public InMemoryAgentSession(FlowContext context) {
        this.sessionId = context.getInstanceId();
        this.snapshot = context;
        this.snapshot.put(Agent.ID_SESSION, this);
    }

    @Override
    public String getSessionId() {
        return sessionId;
    }


    @Override
    public void addHistoryMessage(String agentName, ChatMessage message) {
        historyMessages.computeIfAbsent(agentName, k -> new ArrayList<>())
                .add(message);
    }

    @Override
    public void updateSnapshot(FlowContext snapshot) {
        this.snapshot = snapshot;
    }

    @Override
    public FlowContext getSnapshot() {
        return snapshot;
    }
}
