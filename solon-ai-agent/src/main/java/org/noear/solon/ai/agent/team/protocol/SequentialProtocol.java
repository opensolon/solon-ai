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
    @Override
    public String name() {
        return "SEQUENTIAL";
    }

    @Override
    public void injectInstruction(TeamConfig config, Locale locale, StringBuilder sb) {
        if (Locale.CHINA.getLanguage().equals(locale.getLanguage())) {
            sb.append("- 协作协议：顺序流水线模式。\n");
            sb.append("- 系统将按照成员定义的先后顺序依次指派任务。执行完所有专家后即结束。");
        } else {
            sb.append("- Collaboration Protocol: Sequential Pipeline Mode.\n");
            sb.append("- Tasks are assigned in the predefined order. Ends after all experts have executed.");
        }
    }

    @Override
    public boolean interceptExecute(FlowContext context, TeamTrace trace) throws Exception {
        List<String> agentNames = new ArrayList<>(trace.getConfig().getAgentMap().keySet());
        int nextIndex = trace.getIterationsCount();

        if (nextIndex < agentNames.size()) {
            String nextAgent = agentNames.get(nextIndex);
            trace.setRoute(nextAgent);
            this.updateContext(context, trace, nextAgent);
        } else {
            trace.setRoute(Agent.ID_END);
            trace.setFinalAnswer("Sequential task completed.");
        }
        trace.nextIterations();

        return true;
    }
}
