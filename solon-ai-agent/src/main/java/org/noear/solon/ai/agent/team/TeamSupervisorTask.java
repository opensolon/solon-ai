package org.noear.solon.ai.agent.team;

import org.noear.solon.ai.agent.Agent;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.flow.FlowContext;
import org.noear.solon.flow.Node;
import org.noear.solon.flow.TaskComponent;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

class TeamSupervisorTask implements TaskComponent {
    private final TeamConfig config;

    public TeamSupervisorTask(TeamConfig config) { this.config = config; }

    @Override
    public void run(FlowContext context, Node node) throws Throwable {
        Prompt prompt = context.getAs(Agent.KEY_PROMPT);
        TeamTrace trace = context.getAs(context.getAs(Agent.KEY_CURRENT_TRACE_KEY));

        if (trace.iterationsCount() >= config.getMaxTotalIterations()) {
            trace.setRoute(Agent.ID_END); return;
        }

        StringBuilder protocol = new StringBuilder("\n[Strategy: ").append(config.getStrategy()).append("]\n");
        switch (config.getStrategy()) {
            case CONTRACT_NET:
                String bids = context.getAs("active_bids");
                if (bids == null) {
                    protocol.append("Current phase: BIDDING. Analyze the requirement and output 'BIDDING' to solicit agent proposals.");
                } else {
                    protocol.append("Current phase: AWARDING. We received bids:\n").append(bids)
                            .append("\nSelect the best agent name based on their proposals.");
                    context.remove("active_bids");
                }
                break;
            case SWARM:
                protocol.append("Handoff mode: Look at the last agent's conclusion and decide the next specific agent or FINISH.");
                break;
            case BLACKBOARD:
                protocol.append("Blackboard mode: Review the shared knowledge. Identify gaps and pick an agent to contribute new insights.");
                break;
            default:
                protocol.append("Hierarchical mode: Act as the lead coordinator. Assign tasks to agents.");
                break;
        }

        String systemPrompt = config.getSystemPrompt(prompt) + protocol.toString();
        String decision = config.getChatModel().prompt(Arrays.asList(
                ChatMessage.ofSystem(systemPrompt),
                ChatMessage.ofUser("Progress:\n" + trace.getFormattedHistory())
        )).call().getResultContent().trim();

        parseDecision(trace, decision);
        trace.nextIterations();
    }

    private void parseDecision(TeamTrace trace, String decision) {
        String next = Agent.ID_END;
        String upper = decision.toUpperCase();
        if (upper.contains("BIDDING") && config.getStrategy() == TeamStrategy.CONTRACT_NET) {
            next = "BIDDING";
        } else if (!upper.contains(config.getFinishMarker().toUpperCase())) {
            for (String name : config.getAgentMap().keySet()) {
                if (upper.contains(name.toUpperCase())) { next = name; break; }
            }
        }
        trace.setRoute(next);
    }
}