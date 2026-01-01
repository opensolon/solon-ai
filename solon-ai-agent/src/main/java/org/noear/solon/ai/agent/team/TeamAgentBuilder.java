/*
 * Copyright 2017-2025 noear.org and authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.noear.solon.ai.agent.team;

import org.noear.solon.ai.agent.Agent;
import org.noear.solon.ai.agent.team.task.ContractNetBiddingTask;
import org.noear.solon.ai.agent.team.task.SupervisorTask;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.flow.Graph;
import org.noear.solon.flow.GraphSpec;
import org.noear.solon.flow.NodeSpec;

import java.util.function.Consumer;

/**
 * Team 智能体构建器
 *
 * @author noear
 * @since 3.8.1
 * */
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
    public TeamAgentBuilder graphAdjuster(Consumer<GraphSpec> graphBuilder) {   config.setGraphAdjuster(graphBuilder);    return this; }

    public TeamAgent build() {
        if (config.getName() == null) {
            config.setName("team_agent");
        }

        if (config.getDescription() == null) {
            config.setDescription(config.getName());
        }

        if (config.getAgentMap().isEmpty() && config.getGraphAdjuster() == null) {
            throw new IllegalStateException("The agent or graphBuilder is required");
        }

        return new TeamAgent(createGraph(), config.getName(), config.getDescription());
    }

    private Graph createGraph() {
        return Graph.create(config.getName(), spec -> {
            switch (config.getStrategy()) {
                case SWARM: buildSwarmGraph(spec); break;
                case CONTRACT_NET: buildContractNetGraph(spec); break;
                case SEQUENTIAL:
                case BLACKBOARD:
                case MARKET_BASED:
                case HIERARCHICAL:
                default: buildHubAndSpokeGraph(spec); break;
            }
            if (config.getGraphAdjuster() != null) config.getGraphAdjuster().accept(spec);
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
        spec.addStart(Agent.ID_START).linkAdd(Agent.ID_SUPERVISOR);

        spec.addExclusive(Agent.ID_SUPERVISOR).task(new SupervisorTask(config)).then(ns -> {
            linkAgents(ns, traceKey);
        }).linkAdd(Agent.ID_END);

        config.getAgentMap().values().forEach(a -> spec.addActivity(a).linkAdd(Agent.ID_SUPERVISOR));
        spec.addEnd(Agent.ID_END);
    }

    private void buildSwarmGraph(GraphSpec spec) {
        String traceKey = "__" + config.getName();
        String firstAgent = config.getAgentMap().keySet().iterator().next();
        spec.addStart(Agent.ID_START).linkAdd(firstAgent); // 调整点：直接切入第一个 Agent

        config.getAgentMap().values().forEach(a -> spec.addActivity(a).linkAdd(Agent.ID_SUPERVISOR));

        spec.addExclusive(Agent.ID_SUPERVISOR).task(new SupervisorTask(config)).then(ns -> {
            linkAgents(ns, traceKey);
        }).linkAdd(Agent.ID_END);
        spec.addEnd(Agent.ID_END);
    }

    private void buildContractNetGraph(GraphSpec spec) {
        String traceKey = "__" + config.getName();
        spec.addStart(Agent.ID_START).linkAdd(Agent.ID_SUPERVISOR);
        spec.addExclusive(Agent.ID_SUPERVISOR).task(new SupervisorTask(config)).then(ns -> {
            ns.linkAdd(Agent.ID_BIDDING, l -> l.when(ctx -> Agent.ID_BIDDING.equals(ctx.<TeamTrace>getAs(traceKey).getRoute())));
            linkAgents(ns, traceKey);
        }).linkAdd(Agent.ID_END);

        spec.addActivity(Agent.ID_BIDDING).task(new ContractNetBiddingTask(config)).linkAdd(Agent.ID_SUPERVISOR);
        config.getAgentMap().values().forEach(a -> spec.addActivity(a).linkAdd(Agent.ID_SUPERVISOR));
        spec.addEnd(Agent.ID_END);
    }
}