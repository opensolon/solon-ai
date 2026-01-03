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
import org.noear.solon.ai.agent.team.task.ContractNetBiddingTask;
import org.noear.solon.ai.agent.team.task.SupervisorTask;
import org.noear.solon.flow.FlowContext;
import org.noear.solon.flow.GraphSpec;

import java.util.Locale;

/**
 *
 * @author noear
 * @since 3.8.1
 */
public class ContractNetProtocol extends TeamProtocolBase {
    @Override
    public String name() {
        return "CONTRACT_NET";
    }

    @Override
    public void buildGraph(TeamConfig config, GraphSpec spec) {
        String traceKey = "__" + config.getName();

        spec.addStart(Agent.ID_START).linkAdd(Agent.ID_SUPERVISOR);

        spec.addExclusive(new SupervisorTask(config)).then(ns -> {
            ns.linkAdd(Agent.ID_BIDDING, l -> l.when(ctx -> Agent.ID_BIDDING.equals(ctx.<TeamTrace>getAs(traceKey).getRoute())));
            linkAgents(config, ns, traceKey);
        }).linkAdd(Agent.ID_END);

        spec.addActivity(new ContractNetBiddingTask(config)).linkAdd(Agent.ID_SUPERVISOR);

        config.getAgentMap().values().forEach(a ->
                spec.addActivity(a).linkAdd(Agent.ID_SUPERVISOR));

        spec.addEnd(Agent.ID_END);
    }

    @Override
    public void injectInstruction(TeamConfig config, Locale locale, StringBuilder sb) {
        if (Locale.CHINA.getLanguage().equals(locale.getLanguage())) {
            sb.append("- 遵循'招标-定标'流程。如果需要多个方案，先输出 'BIDDING' 启动招标。\n");
            sb.append("- 收到标书后，对比方案优劣，选出一人执行。");
        } else {
            sb.append("- Follow 'Bidding-Awarding' protocol. If multiple approaches needed, output 'BIDDING' first.\n");
            sb.append("- After receiving bids, compare approaches and select one winner to execute.");
        }
    }

    @Override
    public boolean interceptRouting(FlowContext context, TeamTrace trace, String decision) {
        if (decision.toUpperCase().contains(Agent.ID_BIDDING.toUpperCase())) {
            trace.setRoute(Agent.ID_BIDDING);
            return true;
        }

        return false;
    }

    @Override
    public void prepareInstruction(FlowContext context, TeamTrace trace, StringBuilder sb) {
        String bids = context.getAs("active_bids");
        if (bids != null) {
            sb.append("\n=== Bids Context ===\n").append(bids);
        }
    }

    @Override
    public void onFinished(FlowContext context, TeamTrace trace) {
        context.remove("active_bids");
    }
}
