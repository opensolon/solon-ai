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
            sb.append("1. **角色职责**：你是协调者而非生产者。对于实质性的内容创作、方案规划或信息检索，必须指派对应的专家 Agent 处理。\n");
            sb.append("2. **任务流转**：当用户提出**新要求或新约束**（如预算、偏好变化）时，应重新指派相关的专家 Agent 产出新方案，严禁自行代劳。\n");
            sb.append("3. **快速响应**：仅当用户进行简单的确认（如“好的”、“谢谢”）或对已有结果进行细微解释说明时，你才可以直接回复并结束。\n");
            sb.append("4. **结束判定**：审核员确认 OK 或专家已完成新需求的完整产出后，输出结束信号。");
        } else {
            sb.append("1. **Role Responsibility**: You are a coordinator, not a producer. For substantive content creation, planning, or retrieval, you MUST assign the corresponding specialist Agent.\n");
            sb.append("2. **Workflow**: When users provide **new requirements or constraints** (e.g., budget or preference changes), re-assign the relevant Agent to generate a new solution; do not generate it yourself.\n");
            sb.append("3. **Direct Response**: You may respond directly and terminate only for simple confirmations (e.g., \"OK\", \"Thanks\") or minor clarifications of existing results.\n");
            sb.append("4. **Termination**: Terminate once the Reviewer provides 'OK' or specialists have fully addressed the new requirements.");
        }
    }
}
