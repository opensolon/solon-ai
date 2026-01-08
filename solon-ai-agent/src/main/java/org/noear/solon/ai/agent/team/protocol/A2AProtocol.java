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
import org.noear.solon.flow.FlowContext;
import org.noear.solon.flow.GraphSpec;
import java.util.Locale;

/**
 * A2A (Agent-to-Agent) 协议适配
 * 强调 Agent 之间的直接移交，减少 Supervisor 的中心化干预
 */
public class A2AProtocol extends TeamProtocolBase {
    @Override
    public String name() {
        return "A2A";
    }

    @Override
    public void buildGraph(TeamConfig config, GraphSpec spec) {
        String firstAgent = config.getAgentMap().keySet().iterator().next();

        // 1. 起始节点指向第一个 Agent
        spec.addStart(Agent.ID_START).linkAdd(firstAgent);

        // 2. 每个 Agent 执行完后，不再强行回到 Supervisor，而是进入一个“分发器”
        // 或者直接让 Agent 互相连接
        config.getAgentMap().values().forEach(a -> {
            spec.addActivity(a).linkAdd("__A2A_DISPATCHER__");
        });

        // 3. 增加一个轻量级的 A2A 分发逻辑，解析 Agent 返回的移交指令
        spec.addExclusive("__A2A_DISPATCHER__").then(ns -> {
            linkAgents(config, ns, "__" + config.getName());
        }).linkAdd(Agent.ID_END);

        spec.addEnd(Agent.ID_END);
    }

    @Override
    public void injectInstruction(TeamConfig config, Locale locale, StringBuilder sb) {
        if (Locale.CHINA.getLanguage().equals(locale.getLanguage())) {
            sb.append("1. **直接移交**：你可以通过返回 'Transfer to [Agent Name]' 或使用移交工具来切换专家。\n");
            sb.append("2. **自主权**：当前 Agent 负责决定任务是否完成，或需要哪位专家协作。");
        } else {
            sb.append("1. **Direct Handoff**: You can transfer control by returning 'Transfer to [Agent Name]' or using handoff tools.\n");
            sb.append("2. **Autonomy**: The current agent decides if the task is done or which expert to call next.");
        }
    }

    @Override
    public boolean interceptRouting(FlowContext context, TeamTrace trace, String decision) {
        // 在这里解析 Agent 的输出内容
        // 如果识别到 "Transfer to WorkerB"，则通过 trace.setNextAgent("WorkerB") 并返回 true
        if (decision.contains("Transfer to")) {
            // 解析逻辑...
            return true;
        }
        return false;
    }
}