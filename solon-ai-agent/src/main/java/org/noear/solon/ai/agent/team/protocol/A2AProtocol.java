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
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.flow.FlowContext;
import org.noear.solon.flow.GraphSpec;

import java.util.Locale;

/**
 * A2A (Agent-to-Agent) 协议适配
 * 强调 Agent 之间的直接移交，减少 Supervisor 的中心化干预
 */
public class A2AProtocol extends TeamProtocolBase {
    public A2AProtocol(TeamConfig config) {
        super(config);
    }

    @Override
    public String name() {
        return "A2A";
    }

    @Override
    public void buildGraph(GraphSpec spec) {
        String firstAgent = config.getAgentMap().keySet().iterator().next();

        // 1. 起始节点指向第一个 Agent
        spec.addStart(Agent.ID_START).linkAdd(firstAgent);

        // 2. 每个 Agent 执行完后，不再强行回到 Supervisor，而是进入一个“分发器”
        // 或者直接让 Agent 互相连接
        config.getAgentMap().values().forEach(a -> {
            spec.addActivity(a).linkAdd(Agent.ID_SUPERVISOR);
        });

        // 3. 增加一个轻量级的 A2A 分发逻辑，解析 Agent 返回的移交指令
        spec.addExclusive(Agent.ID_SUPERVISOR).then(ns -> {
            linkAgents(ns, "__" + config.getName());
        }).linkAdd(Agent.ID_END);

        spec.addEnd(Agent.ID_END);
    }

    @Override
    public void injectAgentInstruction(Locale locale, StringBuilder sb) {
        if (Locale.CHINA.getLanguage().equals(locale.getLanguage())) {
            sb.append("\n## 协作协议：A2A (去中心化移交)\n");
            sb.append("你正在一个团队中工作。如果你发现当前任务超出了你的能力范围，或者需要其他领域的专家协作，请进行“任务移交”。\n");

            sb.append("\n### 团队专家名录 (你可以移交的对象):\n");
            for (Agent agent : config.getAgentMap().values()) {
                // 打印格式：- 专家名: 职责描述
                sb.append("- ").append(agent.name()).append(": ").append(agent.description()).append("\n");
            }

            sb.append("\n### 移交指令规范:\n");
            sb.append("1. **移交语法**：在回复的最末尾，明确输出 'Transfer to [专家名]'。\n");
            sb.append("2. **示例**：如果你认为需要进行数据分析，且有名为 'Analyst' 的专家，请输出 '...因此，我建议移交任务。Transfer to Analyst'。\n");
            sb.append("3. **终止任务**：如果你已经彻底完成了所有工作，请输出 '").append(config.getFinishMarker()).append("'。\n");
        } else {
            sb.append("\n## Collaboration Protocol: A2A (Decentralized Handoff)\n");
            sb.append("You are part of a team. If the task exceeds your expertise or requires specific knowledge, perform a 'Handoff'.\n");

            sb.append("\n### Team Directory (Available Experts):\n");
            for (Agent agent : config.getAgentMap().values()) {
                sb.append("- ").append(agent.name()).append(": ").append(agent.description()).append("\n");
            }

            sb.append("\n### Handoff Guidelines:\n");
            sb.append("1. **Syntax**: Explicitly output 'Transfer to [Agent Name]' at the very end of your response.\n");
            sb.append("2. **Example**: '...therefore, I am handing off this task. Transfer to Analyst'.\n");
            sb.append("3. **Completion**: If the goal is fully achieved, output '").append(config.getFinishMarker()).append("'.\n");
        }
    }

    @Override
    public Prompt prepareAgentPrompt(TeamTrace trace, Agent agent, Prompt originalPrompt, Locale locale) {
        if (trace == null || trace.getStepCount() == 0) {
            return originalPrompt;
        }

        StringBuilder sb = new StringBuilder();
        if (Locale.CHINA.getLanguage().equals(locale.getLanguage())) {
            sb.append("### 核心任务目标\n").append(originalPrompt.getUserContent()).append("\n\n");
            sb.append("### 当前协作进度\n").append(trace.getFormattedHistory()).append("\n");

            String lastAgent = trace.getSteps().get(trace.getStepCount() - 1).getAgentName();
            sb.append("\n[系统状态]: 任务已由 [").append(lastAgent).append("] 移交给当前专家 [").append(agent.name()).append("]。\n");
            sb.append("请基于上述进度继续执行。如果需要移交，请参考系统指令中的专家名录。");
        } else {
            sb.append("### Primary Objective\n").append(originalPrompt.getUserContent()).append("\n\n");
            sb.append("### Collaboration History\n").append(trace.getFormattedHistory()).append("\n");

            String lastAgent = trace.getSteps().get(trace.getStepCount() - 1).getAgentName();
            sb.append("\n[System]: Task transferred from [").append(lastAgent).append("] to [").append(agent.name()).append("].\n");
            sb.append("Please proceed based on the progress above.");
        }

        return Prompt.of(sb.toString());
    }

    @Override
    public void injectSupervisorInstruction(Locale locale, StringBuilder sb) {
        if (Locale.CHINA.getLanguage().equals(locale.getLanguage())) {
            sb.append("\n## 协作协议：").append(config.getProtocol().name()).append("\n");
            sb.append("1. **直接移交**：你可以通过返回 'Transfer to [Agent Name]' 或使用移交工具来切换专家。\n");
            sb.append("2. **自主权**：当前 Agent 负责决定任务是否完成，或需要哪位专家协作。");
        } else {
            sb.append("\n## Collaboration Protocol: ").append(config.getProtocol().name()).append("\n");
            sb.append("1. **Direct Handoff**: You can transfer control by returning 'Transfer to [Agent Name]' or using handoff tools.\n");
            sb.append("2. **Autonomy**: The current agent decides if the task is done or which expert to call next.");
        }
    }

    @Override
    public boolean interceptSupervisorRouting(FlowContext context, TeamTrace trace, String decision) {
        if (decision == null || decision.isEmpty()) {
            return false;
        }

        // 查找最后一个移交意图，防止回复中多次提到导致冲突
        String marker = "Transfer to ";
        int lastIndex = decision.lastIndexOf(marker);

        if (lastIndex != -1) {
            String targetName = decision.substring(lastIndex + marker.length()).trim();

            // 遍历配置中的 Agent 名进行匹配，确保目标确实存在
            for (String agentName : config.getAgentMap().keySet()) {
                if (targetName.startsWith(agentName)) {
                    trace.setRoute(agentName);
                    return true;
                }
            }
        }

        // 检查结束标识
        if (decision.contains(config.getFinishMarker())) {
            trace.setRoute(Agent.ID_END);
            return true;
        }

        return false;
    }
}