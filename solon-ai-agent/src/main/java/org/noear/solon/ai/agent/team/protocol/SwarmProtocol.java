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
 * 蜂群协作协议 (Swarm Protocol)
 * * <p>核心机制：基于“任务涌现”与“信息素挥发”。Agent 可动态生成子任务并放入共享任务池，
 * 通过信息素（Pheromones）权重实现各成员间的负载均衡。</p>
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
        // 入口：首个 Agent 启动任务
        String firstAgent = config.getAgentMap().keySet().iterator().next();
        spec.addStart(Agent.ID_START).linkAdd(firstAgent);

        // 环形反馈：Agent 完成任务后统一由 Supervisor 决定下一步去向
        config.getAgentMap().values().forEach(a ->
                spec.addActivity(a).linkAdd(Agent.ID_SUPERVISOR));

        spec.addExclusive(new SupervisorTask(config)).then(ns -> {
            linkAgents(ns);
        }).linkAdd(Agent.ID_END);

        spec.addEnd(Agent.ID_END);
    }

    /**
     * 获取或初始化蜂群状态（信息素+任务池）
     */
    private ONode getSwarmState(TeamTrace trace) {
        return (ONode) trace.getProtocolContext().computeIfAbsent(KEY_SWARM_STATE, k -> {
            ONode node = new ONode().asObject();
            node.getOrNew("pheromones"); // 各成员活跃度权重
            node.getOrNew("task_pool");  // 动态涌现的任务池
            return node;
        });
    }

    @Override
    public void injectAgentInstruction(FlowContext context, Agent agent, Locale locale, StringBuilder sb) {
        boolean isZh = Locale.CHINA.getLanguage().equals(locale.getLanguage());
        if (isZh) {
            sb.append("\n- 协作提醒：若需拆解任务，请在回复末尾附加 JSON: `{\"sub_tasks\": [{\"task\": \"描述\", \"agent\": \"建议人\"}]}`");
        } else {
            sb.append("\n- Collaboration: If decomposition is needed, append: `{\"sub_tasks\": [{\"task\": \"...\", \"agent\": \"...\"}]}`");
        }
    }

    @Override
    public boolean shouldSupervisorRoute(FlowContext context, TeamTrace trace, String decision) {
        if (decision.contains(config.getFinishMarker())) {
            if (config.getGraphAdjuster() != null) return true;

            // 防早停：若任务池仍有涌现任务未处理，拦截结束信号
            boolean noAgentParticipated = trace.getSteps().stream()
                    .noneMatch(TeamTrace.TeamStep::isAgent);

            if (noAgentParticipated) {
                LOG.warn("SwarmProtocol: Emergent tasks detected in pool but not yet executed. Blocking finish.");
                return false;
            }
        }
        return true;
    }

    @Override
    public String resolveAgentOutput(TeamTrace trace, Agent agent, String rawContent) {
        if (Utils.isEmpty(rawContent)) return rawContent;

        // 提取并解析 Agent 可能生成的子任务 JSON
        ONode output = sniffJson(rawContent);

        if (output.hasKey("sub_tasks") && output.get("sub_tasks").isArray()) {
            ONode subTasks = output.get("sub_tasks");
            if (subTasks.size() > 0) {
                ONode state = getSwarmState(trace);
                subTasks.getArray().forEach(taskNode -> state.get("task_pool").add(taskNode));

                if (LOG.isDebugEnabled()) {
                    LOG.debug("Swarm: Agent [{}] emerged {} new tasks into the pool.", agent.name(), subTasks.size());
                }

                // 清理历史记录：剥离 JSON 块以保持对话历史的纯净
                return rawContent.replaceAll("\\s*\\{.*\\}\\s*", "\n").trim();
            }
        }
        return rawContent;
    }

    @Override
    public void onAgentEnd(TeamTrace trace, Agent agent) {
        ONode state = getSwarmState(trace);
        ONode pheroNode = state.get("pheromones");

        // 1. 信息素挥发：全员权重小幅下降（冷降）
        pheroNode.getObject().forEach((k, v) -> {
            int val = v.getInt();
            if (val > 0) pheroNode.set(k, val - 1);
        });

        // 2. 局部增强：当前 Agent 增加权重，降低短期内被重复指派的概率（负载均衡）
        int current = pheroNode.get(agent.name()).getInt();
        pheroNode.set(agent.name(), current + 5);
    }

    @Override
    public void onSupervisorRouting(FlowContext context, TeamTrace trace, String nextAgent) {
        ONode state = getSwarmState(trace);
        ONode taskPool = state.get("task_pool");

        // 路由命中后，从动态任务池中清理已分配的任务
        if (taskPool.isArray()) {
            taskPool.getArrayUnsafe().removeIf(n -> {
                String taskDesc = n.isObject() ? n.get("task").getString() : n.getString();
                return nextAgent.equalsIgnoreCase(taskDesc) || (agentMatchesTask(nextAgent, n));
            });
        }
    }

    private boolean agentMatchesTask(String agentName, ONode taskNode) {
        return taskNode.isObject() && agentName.equalsIgnoreCase(taskNode.get("agent").getString());
    }

    /**
     * 向 Supervisor 注入实时状态看板，辅助其做出负载均衡的决策
     */
    @Override
    public void prepareSupervisorInstruction(FlowContext context, TeamTrace trace, StringBuilder sb) {
        ONode state = getSwarmState(trace);
        boolean isZh = Locale.CHINA.getLanguage().equals(config.getLocale().getLanguage());

        sb.append(isZh ? "\n### 蜂群状态看板 (Swarm Intelligence)\n" : "\n### Swarm Intelligence Dashboard\n");
        sb.append("```json\n").append(state.toJson()).append("\n```\n");

        if (isZh) {
            sb.append("> **调度指引**：\n");
            sb.append("> 1. **负载均衡**：优先指派 pheromones 值较低的成员。\n");
            sb.append("> 2. **能力匹配**：确保 task_pool 中的任务描述与 Agent 的 Skills 一致。\n");
        } else {
            sb.append("> **Instruction**:\n");
            sb.append("> 1. **Load Balancing**: Prioritize agents with lower pheromone values.\n");
            sb.append("> 2. **Skill Match**: Ensure emergent tasks in task_pool align with Agent's Skills.");
        }
    }

    @Override
    public void onTeamFinished(FlowContext context, TeamTrace trace) {
        // 团队结束时清理状态，防止内存泄漏
        trace.getProtocolContext().remove(KEY_SWARM_STATE);
        super.onTeamFinished(context, trace);
    }
}