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

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 *
 * @author noear
 * @since 3.8.1
 */
public class SequentialProtocol extends HierarchicalProtocol {
    public SequentialProtocol(TeamConfig config) {
        super(config);
    }

    @Override
    public String name() {
        return "SEQUENTIAL";
    }

    @Override
    public void injectSupervisorInstruction(Locale locale, StringBuilder sb) {
        if (Locale.CHINA.getLanguage().equals(locale.getLanguage())) {
            sb.append("\n## 协作协议：").append(config.getProtocol().name()).append("\n");
            sb.append("1. **严谨接力**：任务必须按预设的 Agent 顺序执行，严禁跳步或逆向指派。\n");
            sb.append("2. **单向推进**：每一步只需确认当前 Agent 完成产出，并指派列表中的下一位成员。");
        } else {
            sb.append("\n## Collaboration Protocol: ").append(config.getProtocol().name()).append("\n");
            sb.append("1. **Strict Relay**: Tasks MUST follow the predefined Agent sequence. No skipping or backtracking.\n");
            sb.append("2. **Forward Progression**: Confirm the current output and assign the NEXT member in the predefined list.");
        }
    }

    @Override
    public boolean interceptSupervisorExecute(FlowContext context, TeamTrace trace) throws Exception {
        List<String> agentNames = new ArrayList<>(trace.getConfig().getAgentMap().keySet());
        int nextIndex = trace.getIterationsCount();

        if (nextIndex < agentNames.size()) {
            String nextAgent = agentNames.get(nextIndex);
            trace.setRoute(nextAgent);
        } else {
            trace.setRoute(Agent.ID_END);
            trace.setFinalAnswer("Sequential task completed.");
        }
        trace.nextIterations();

        return true;
    }
}
