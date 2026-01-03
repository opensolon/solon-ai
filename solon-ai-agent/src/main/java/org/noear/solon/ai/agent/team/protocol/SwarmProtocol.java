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
package org.noear.solon.ai.agent.team.protocol;

import org.noear.solon.ai.agent.Agent;
import org.noear.solon.ai.agent.team.TeamConfig;
import org.noear.solon.ai.agent.team.TeamTrace;
import org.noear.solon.ai.agent.team.task.SupervisorTask;
import org.noear.solon.flow.FlowContext;
import org.noear.solon.flow.GraphSpec;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 *
 * @author noear
 * @since 3.8.1
 */
public class SwarmProtocol extends TeamProtocolBase {
    @Override
    public String name() {
        return "SWARM";
    }

    @Override
    public void buildGraph(TeamConfig config, GraphSpec spec) {
        String traceKey = "__" + config.getName();
        String firstAgent = config.getAgentMap().keySet().iterator().next();

        spec.addStart(Agent.ID_START).linkAdd(firstAgent); // 调整点：直接切入第一个 Agent

        config.getAgentMap().values().forEach(a ->
                spec.addActivity(a).linkAdd(Agent.ID_SUPERVISOR));

        spec.addExclusive(new SupervisorTask(config)).then(ns -> {
            linkAgents(config, ns, traceKey);
        }).linkAdd(Agent.ID_END);

        spec.addEnd(Agent.ID_END);
    }

    @Override
    public void updateContext(FlowContext context, TeamTrace trace, String nextAgent) {
        Map<String, Integer> usage = (Map<String, Integer>) trace.getStrategyContext().computeIfAbsent("agent_usage", k -> new HashMap<>());
        usage.put(nextAgent, usage.getOrDefault(nextAgent, 0) + 1);
    }

    @Override
    public void prepareProtocolInfo(FlowContext context, TeamTrace trace, StringBuilder sb) {
        Map<String, Integer> usage = (Map<String, Integer>) trace.getStrategyContext().get("agent_usage");

        if (usage != null && !usage.isEmpty()) {
            sb.append("\n=== Agent Usage Statistics ===\n");
            usage.forEach((name, count) -> {
                sb.append("- ").append(name).append(": ").append(count).append(" times\n");
            });
            sb.append("Note: If an agent has been called multiple times without progressing the goal, consider switching to another expert or finishing the task.\n");
        }
    }

    @Override
    public void injectInstruction(TeamConfig config, Locale locale, StringBuilder sb) {
        if (Locale.CHINA.getLanguage().equals(locale.getLanguage())) {
            sb.append("- 你是动态路由器。Agent 之间是平等的接力关系。\n");
            sb.append("- 根据上一个 Agent 的结果，判断谁最适合处理下一棒。");
        } else {
            sb.append("- You are a Dynamic Router. Agents operate in peer-to-peer relay fashion.\n");
            sb.append("- Based on previous Agent's result, determine who is best for the next 'baton'.");
        }
    }
}
