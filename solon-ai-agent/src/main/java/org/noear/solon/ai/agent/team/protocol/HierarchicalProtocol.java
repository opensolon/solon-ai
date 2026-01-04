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

import java.util.Locale;

/**
 *
 * @author noear
 * @since 3.8.1
 */
public class HierarchicalProtocol extends TeamProtocolBase {
    @Override
    public String name() {
        return "HIERARCHICAL";
    }

    @Override
    public void buildGraph(TeamConfig config, GraphSpec spec) {
        String traceKey = "__" + config.getName();

        spec.addStart(Agent.ID_START).linkAdd(Agent.ID_SUPERVISOR);

        spec.addExclusive(new SupervisorTask(config)).then(ns -> {
            linkAgents(config, ns, traceKey);
        }).linkAdd(Agent.ID_END);

        config.getAgentMap().values().forEach(a ->
                spec.addActivity(a).linkAdd(Agent.ID_SUPERVISOR));

        spec.addEnd(Agent.ID_END);
    }

    @Override
    public void prepareInstruction(FlowContext context, TeamTrace trace, StringBuilder sb){
        sb.append("\n=== Hierarchical Context ===\nTotal agents available: ");
        sb.append(trace.getConfig().getAgentMap().size());
    }

    @Override
    public void injectInstruction(TeamConfig config, Locale locale, StringBuilder sb) {
        if (Locale.CHINA.getLanguage().equals(locale.getLanguage())) {
            sb.append("1. **指挥调度**：你是最高指挥官。请将任务拆解为具体步骤，并按序指派 Agent。\n");
            sb.append("2. **质量把控**：监督每个成员的产出，确保其符合整体任务需求。");
        } else {
            sb.append("1. **Command & Control**: You are the Lead Supervisor. Decompose the task and assign Agents sequentially.\n");
            sb.append("2. **Quality Assurance**: Review each output to ensure it aligns with the project goal.");
        }
    }
}
