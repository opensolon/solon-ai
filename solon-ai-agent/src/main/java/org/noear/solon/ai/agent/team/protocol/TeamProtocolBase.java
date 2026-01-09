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
import org.noear.solon.ai.agent.team.TeamProtocol;
import org.noear.solon.ai.agent.team.TeamTrace;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.flow.NodeSpec;

import java.util.Locale;

/**
 *
 * @author noear
 * @since 3.8.1
 */
public abstract class TeamProtocolBase implements TeamProtocol {
    protected final TeamConfig config;

    public TeamProtocolBase(TeamConfig config) {
        this.config = config;
    }

    protected void linkAgents(NodeSpec ns) {
        for (String agentName : config.getAgentMap().keySet()) {
            ns.linkAdd(agentName, l -> l.title("route = " + agentName).when(ctx -> {
                TeamTrace trace = ctx.getAs(config.getTraceKey());
                return agentName.equalsIgnoreCase(trace.getRoute());
            }));
        }
    }

    @Override
    public Prompt prepareAgentPrompt(TeamTrace trace, Agent agent, Prompt originalPrompt, Locale locale) {
        if (trace != null && trace.getStepCount() > 0) {
            String fullHistory = trace.getFormattedHistory();
            String newContent = "Current Task: " + originalPrompt.getUserContent() +
                    "\n\nCollaboration Progress so far:\n" + fullHistory +
                    "\n\nPlease continue based on the progress above.";
            return Prompt.of(newContent);
        }

        return originalPrompt;
    }

    @Override
    public void injectSupervisorInstruction(Locale locale, StringBuilder sb) {
        if (Locale.CHINA.getLanguage().equals(locale.getLanguage())) {
            sb.append("\n## 协作协议：").append(config.getProtocol().name()).append("\n");
            sb.append("- 作为团队主管，请根据任务需求和成员能力做出决策。");
        } else {
            sb.append("\n## Collaboration Protocol: ").append(config.getProtocol().name()).append("\n");
            sb.append("- As team supervisor, make decisions based on task requirements and member capabilities.");
        }
    }
}
