/*
 * Copyright 2017-2025 noear.org and authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.noear.solon.ai.agent.team.protocol;

import org.noear.snack4.ONode;
import org.noear.solon.Utils;
import org.noear.solon.ai.agent.Agent;
import org.noear.solon.ai.agent.team.TeamAgentConfig;
import org.noear.solon.ai.agent.team.TeamTrace;
import org.noear.solon.ai.agent.team.task.SupervisorTask;
import org.noear.solon.flow.FlowContext;
import org.noear.solon.flow.GraphSpec;
import org.noear.solon.lang.Preview;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;

/**
 * 优化版蜂群协作协议 (Swarm Protocol)
 * * 优化点：
 * 1. 强化 JSON 嗅探：利用基类 sniffJson 处理非标准输出。
 * 2. 引入信息素冷降机制：避免单一 Agent 被连续过度使用。
 * 3. 任务池生命周期管理：自动清理已分配任务。
 */
@Preview("3.8.1")
public class SwarmProtocol extends TeamProtocolBase {
    private static final Logger LOG = LoggerFactory.getLogger(SwarmProtocol.class);
    private static final String KEY_SWARM_STATE = "swarm_state_node";

    public SwarmProtocol(TeamAgentConfig config) {
        super(config);
    }

    @Override
    public String name() { return "SWARM"; }

    @Override
    public void buildGraph(GraphSpec spec) {
        // 入口：首个 Agent 执行
        String firstAgent = config.getAgentMap().keySet().iterator().next();
        spec.addStart(Agent.ID_START).linkAdd(firstAgent);

        // 环形反馈：Agent 执行完 -> 交给 Supervisor -> 决定去向（另一个 Agent 或 End）
        config.getAgentMap().values().forEach(a ->
                spec.addActivity(a).linkAdd(Agent.ID_SUPERVISOR));

        spec.addExclusive(new SupervisorTask(config)).then(ns -> {
            linkAgents(ns);
        }).linkAdd(Agent.ID_END);

        spec.addEnd(Agent.ID_END);
    }

    private ONode getSwarmState(TeamTrace trace) {
        return (ONode) trace.getProtocolContext().computeIfAbsent(KEY_SWARM_STATE, k -> {
            ONode node = new ONode().asObject();
            node.getOrNew("pheromones"); // 活跃度 (信息素)
            node.getOrNew("task_pool");  // 涌现任务池
            return node;
        });
    }

    @Override
    public void injectAgentInstruction(Agent agent, Locale locale, StringBuilder sb) {
        boolean isZh = Locale.CHINA.getLanguage().equals(locale.getLanguage());
        if (isZh) {
            sb.append("\n- 协作提醒：若发现当前任务需拆解，请在输出结尾附加 JSON: `{\"sub_tasks\": [{\"task\": \"任务描述\", \"agent\": \"建议执行者\"}]}`");
        } else {
            sb.append("\n- Collaboration: If the task needs decomposition, append JSON at the end: `{\"sub_tasks\": [{\"task\": \"description\", \"agent\": \"target_agent\"}]}`");
        }
    }

    @Override
    public boolean shouldSupervisorRoute(FlowContext context, TeamTrace trace, String decision) {
        if (decision.contains(config.getFinishMarker())) {
            // 自由图模式下，不干预
            if (config.getGraphAdjuster() != null) {
                return true;
            }

            boolean noAgentParticipated = trace.getSteps().stream()
                    .noneMatch(TeamTrace.TeamStep::isAgent);

            if (noAgentParticipated) {
                LOG.warn("SwarmProtocol: Emergent tasks not yet started. Blocking finish.");
                return false;
            }
        }
        return true;
    }

    @Override
    public String resolveAgentOutput(TeamTrace trace, Agent agent, String rawContent) {
        if (Utils.isEmpty(rawContent)) return rawContent;

        // 1. 使用基类优化的嗅探逻辑
        ONode output = sniffJson(rawContent);

        // 2. 检查子任务涌现 (识别 sub_tasks 数组)
        if (output.hasKey("sub_tasks") && output.get("sub_tasks").isArray()) {
            ONode subTasks = output.get("sub_tasks");
            if (subTasks.size() > 0) {
                ONode state = getSwarmState(trace);
                // 将新任务合并入任务池
                subTasks.getArray().forEach(taskNode -> {
                    state.get("task_pool").add(taskNode);
                });

                LOG.debug("Swarm: [{}] generated {} emergent tasks.", agent.name(), subTasks.size());

                // 3. 剥离 JSON，只返回文本给历史记录，保持上下文整洁
                return rawContent.replaceAll("\\s*\\{.*\\}\\s*", "\n").trim();
            }
        }
        return rawContent;
    }

    @Override
    public void onAgentEnd(TeamTrace trace, Agent agent) {
        ONode state = getSwarmState(trace);
        ONode pheroNode = state.get("pheromones");

        // 1. 信息素挥发逻辑 (Evaporation)
        // 每次有 Agent 完成任务，所有 Agent 的信息素都会小幅下降（冷降）
        pheroNode.getObject().forEach((k, v) -> {
            int val = v.getInt();
            if (val > 0) pheroNode.set(k, val - 1);
        });

        // 2. 当前 Agent 堆积信息素 (增量)
        int current = pheroNode.get(agent.name()).getInt();
        pheroNode.set(agent.name(), current + 5); // 增加显著权重，防止短时间内被再次指派
    }

    @Override
    public void onSupervisorRouting(FlowContext context, TeamTrace trace, String nextAgent) {
        ONode state = getSwarmState(trace);
        ONode taskPool = state.get("task_pool");

        // 路由转向时，尝试从任务池移除已匹配的任务
        if (taskPool.isArray()) {
            taskPool.getArrayUnsafe().removeIf(n -> {
                String taskDesc = n.isObject() ? n.get("task").getString() : n.getString();
                return nextAgent.equalsIgnoreCase(taskDesc) || (agentMatchesTask(nextAgent, n));
            });
        }
    }

    private boolean agentMatchesTask(String agentName, ONode taskNode) {
        // 简单匹配逻辑：任务 JSON 中若指定了 agent 字段则匹配
        return taskNode.isObject() && agentName.equalsIgnoreCase(taskNode.get("agent").getString());
    }

    @Override
    public void prepareSupervisorInstruction(FlowContext context, TeamTrace trace, StringBuilder sb) {
        ONode state = getSwarmState(trace);
        boolean isZh = Locale.CHINA.getLanguage().equals(config.getLocale().getLanguage());

        sb.append(isZh ? "\n### 蜂群实时状态 (Swarm Intelligence)\n" : "\n### Swarm Real-time Intelligence\n");
        sb.append("```json\n").append(state.toJson()).append("\n```\n");

        if (isZh) {
            sb.append("> **决策指引**：\n");
            sb.append("> 1. **负载均衡**：优先分配给 pheromones 分值低的成员。\n");
            sb.append("> 2. **模态适配(重要)**：如果 task_pool 中的任务涉及多模态数据，禁止指派“仅限文本”的成员。\n");
            sb.append("> 3. **能力对齐**：匹配 Agent 的 Skills。");
        } else {
            sb.append("> **Decision Guide**:\n");
            sb.append("> 1. **Load Balancing**: Prioritize low-pheromone members.\n");
            sb.append("> 2. **Modality Match**: DO NOT assign multi-modal tasks to 'Text only' members.\n");
            sb.append("> 3. **Skill Alignment**: Match Agent's Skills.");
        }
    }

    @Override
    public void onTeamFinished(FlowContext context, TeamTrace trace) {
        trace.getProtocolContext().remove(KEY_SWARM_STATE);
        super.onTeamFinished(context, trace);
    }
}