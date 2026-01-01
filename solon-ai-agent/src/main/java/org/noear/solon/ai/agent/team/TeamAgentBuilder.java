package org.noear.solon.ai.agent.team;

import org.noear.solon.ai.agent.Agent;
import org.noear.solon.ai.agent.team.task.ContractNetBiddingTask;
import org.noear.solon.ai.agent.team.task.MediatorTask;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.flow.Graph;
import org.noear.solon.flow.GraphSpec;
import org.noear.solon.flow.NodeSpec;

import java.util.function.Consumer;

public class TeamAgentBuilder {
    private final TeamConfig config;

    public TeamAgentBuilder(ChatModel chatModel) {
        this.config = new TeamConfig(chatModel);
        // 默认使用中文支持全协议的 Provider
        this.config.setPromptProvider(TeamPromptProviderCn.getInstance());
    }

    public TeamAgentBuilder name(String name) {  config.setName( name);  return this;  }
    public TeamAgentBuilder description(String description) {   config.setDescription(description);   return this;  }
    public TeamAgentBuilder addAgent(Agent agent) { config.addAgent(agent); return this; }
    public TeamAgentBuilder promptProvider(TeamPromptProvider promptProvider) {  config.setPromptProvider(promptProvider); return this; }
    public TeamAgentBuilder finishMarker(String finishMarker) { config.setFinishMarker(finishMarker);  return this; }
    public TeamAgentBuilder maxTotalIterations(int maxTotalIterations) { config.setMaxTotalIterations(maxTotalIterations);  return this;}
    public TeamAgentBuilder strategy(TeamStrategy strategy) { config.setStrategy(strategy); return this; }
    public TeamAgentBuilder graph(Consumer<GraphSpec> graphBuilder) {   config.setGraphBuilder(graphBuilder);    return this; }

    public TeamAgent build() {
        if(config.getName() == null){
            config.setName("team_agent");
        }

        if(config.getAgentMap().isEmpty() || config.getGraphBuilder() == null){
            throw new IllegalStateException("The agent or graphBuilder is required");
        }

        return new TeamAgent(createGraph(), config.getName(), config.getDescription());
    }

    private Graph createGraph() {
        return Graph.create(config.getName(), spec -> {
            switch (config.getStrategy()) {
                case SWARM: buildSwarmGraph(spec); break;
                case CONTRACT_NET: buildContractNetGraph(spec); break;
                case BLACKBOARD:
                case MARKET_BASED:
                case HIERARCHICAL:
                default: buildHubAndSpokeGraph(spec); break;
            }
            if (config.getGraphBuilder() != null) config.getGraphBuilder().accept(spec);
        });
    }

    private void linkAgents(NodeSpec ns, String traceKey) {
        for (String agentName : config.getAgentMap().keySet().stream()
                .sorted((a, b) -> Integer.compare(b.length(), a.length())) // 调整点：长名优先匹配
                .toArray(String[]::new)) {
            ns.linkAdd(agentName, l -> l.when(ctx -> agentName.equalsIgnoreCase(ctx.<TeamTrace>getAs(traceKey).getRoute())));
        }
    }

    /**
     * 中心化拓扑（适用于 HIERARCHICAL, BLACKBOARD, MARKET_BASED）
     */
    private void buildHubAndSpokeGraph(GraphSpec spec) {
        String traceKey = "__" + config.getName();
        spec.addStart(Agent.ID_START).linkAdd(Agent.ID_ROUTER);

        spec.addExclusive(Agent.ID_ROUTER).task(new MediatorTask(config)).then(ns -> {
            linkAgents(ns, traceKey);
        }).linkAdd(Agent.ID_END);

        config.getAgentMap().values().forEach(a -> spec.addActivity(a).linkAdd(Agent.ID_ROUTER));
        spec.addEnd(Agent.ID_END);
    }

    private void buildSwarmGraph(GraphSpec spec) {
        String traceKey = "__" + config.getName();
        String firstAgent = config.getAgentMap().keySet().iterator().next();
        spec.addStart(Agent.ID_START).linkAdd(firstAgent); // 调整点：直接切入第一个 Agent

        config.getAgentMap().values().forEach(a -> spec.addActivity(a).linkAdd(Agent.ID_ROUTER));

        spec.addExclusive(Agent.ID_ROUTER).task(new MediatorTask(config)).then(ns -> {
            linkAgents(ns, traceKey);
        }).linkAdd(Agent.ID_END);
        spec.addEnd(Agent.ID_END);
    }

    private void buildContractNetGraph(GraphSpec spec) {
        String traceKey = "__" + config.getName();
        spec.addStart(Agent.ID_START).linkAdd(Agent.ID_ROUTER);
        spec.addExclusive(Agent.ID_ROUTER).task(new MediatorTask(config)).then(ns -> {
            ns.linkAdd(Agent.ID_BIDDING, l -> l.when(ctx -> Agent.ID_BIDDING.equals(ctx.<TeamTrace>getAs(traceKey).getRoute())));
            linkAgents(ns, traceKey);
        }).linkAdd(Agent.ID_END);

        spec.addActivity(Agent.ID_BIDDING).task(new ContractNetBiddingTask(config)).linkAdd(Agent.ID_ROUTER);
        config.getAgentMap().values().forEach(a -> spec.addActivity(a).linkAdd(Agent.ID_ROUTER));
        spec.addEnd(Agent.ID_END);
    }
}