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
import org.noear.solon.ai.agent.team.TeamConfig;
import org.noear.solon.ai.agent.team.TeamTrace;
import org.noear.solon.ai.agent.team.task.SupervisorTask;
import org.noear.solon.flow.FlowContext;
import org.noear.solon.flow.GraphSpec;
import org.noear.solon.lang.Preview;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;

/**
 * 蜂群协作协议 (Swarm Protocol) - 基于 Snack4 状态管理
 *
 * <p>特点：
 * 1. 自动维护“信息素” (Pheromone)：通过 Agent 的活跃度控制路由倾向。
 * 2. 任务涌现 (Emergent Tasks)：Agent 输出的 JSON 会自动转化为后续待办任务。
 * 3. 动态负载平衡：防止特定 Agent 陷入过度循环。</p>
 */
@Preview("3.8.1")
public class SwarmProtocol_H extends TeamProtocolBase {
    private static final Logger LOG = LoggerFactory.getLogger(SwarmProtocol_H.class);
    private static final String KEY_SWARM_STATE = "swarm_state_node";

    public SwarmProtocol_H(TeamConfig config) {
        super(config);
    }

    @Override
    public String name() {
        return "SWARM";
    }

    // --- 阶段一：构建期 (拓扑修正) ---

    @Override
    public void buildGraph(GraphSpec spec) {
        // 蜂群拓扑：Start -> 第一个 Agent -> Supervisor <-> 其他 Agents
        String firstAgent = config.getAgentMap().keySet().iterator().next();

        spec.addStart(Agent.ID_START).linkAdd(firstAgent);

        // 所有 Agent 执行完后，统一交还给 Supervisor (分发中心)
        config.getAgentMap().values().forEach(a ->
                spec.addActivity(a).linkAdd(Agent.ID_SUPERVISOR));

        // Supervisor 决策逻辑
        spec.addExclusive(new SupervisorTask(config)).then(ns -> {
            linkAgents(ns); // 使用基类的 linkAgents 绑定 trace.getRoute()
        }).linkAdd(Agent.ID_END);

        spec.addEnd(Agent.ID_END);
    }

    // --- 阶段二：状态维护 (基于 Snack4 v4) ---

    private ONode getSwarmState(TeamTrace trace) {
        return (ONode) trace.getProtocolContext().computeIfAbsent(KEY_SWARM_STATE, k -> {
            ONode node = new ONode().asObject();
            node.getOrNew("pheromones"); // 活跃度图
            node.getOrNew("task_pool");  // 涌现任务池
            return node;
        });
    }

    @Override
    public void onAgentEnd(TeamTrace trace, Agent agent) {
        ONode state = getSwarmState(trace);
        String content = trace.getLastAgentContent();

        // 1. 更新信息素 (活跃度)
        int count = state.select("$.pheromones." + agent.name()).getInt();
        state.get("pheromones").set(agent.name(), count + 1);

        // 2. 提取涌现任务 (如果 Agent 回复中包含 JSON 格式的 sub_tasks)
        if (Utils.isNotEmpty(content) && (content.contains("[") || content.contains("{"))) {
            try {
                ONode output = ONode.ofJson(content);
                if (output.hasKey("sub_tasks")) {
                    state.get("task_pool").addAll(output.get("sub_tasks").getArray());
                }
            } catch (Exception ignored) {
                // 非 JSON 输出不处理
            }
        }
    }

    // --- 阶段三：主管决策逻辑 ---

    @Override
    public void prepareSupervisorInstruction(FlowContext context, TeamTrace trace, StringBuilder sb) {
        ONode state = getSwarmState(trace);
        boolean isZh = Locale.CHINA.getLanguage().equals(config.getLocale().getLanguage());

        sb.append(isZh ? "\n### 🐝 蜂群环境看板 (Swarm Dashboard)\n" : "\n### 🐝 Swarm Dashboard\n");

        // 打印信息素和任务池情况
        sb.append("```json\n")
                .append(state.toJson())
                .append("\n```\n");

        if (isZh) {
            sb.append("> 指示：请检查 `task_pool` 中的待办事项。如果某个 Agent 的 `pheromones` 值过高，说明其可能陷入死循环，请尝试指派其他专家。");
        } else {
            sb.append("> Instructions: Check `task_pool` for pending items. If an agent's `pheromones` value is too high, it may be stuck; try dispatching another expert.");
        }
    }

    @Override
    public String resolveSupervisorRoute(FlowContext context, TeamTrace trace, String decision) {
        // 蜂群协议通常不需要强制干预路由，保持默认的语义解析
        return null;
    }

    @Override
    public void onSupervisorRouting(FlowContext context, TeamTrace trace, String nextAgent) {
        // 路由转向时，如果发现是处理了池中的任务，可以考虑清理（可选）
        ONode state = getSwarmState(trace);
        if (state.get("task_pool").isArray()) {
            state.get("task_pool").getArray().removeIf(n -> n.getString().equalsIgnoreCase(nextAgent));
        }

        LOG.debug("Swarm Protocol - Routing to: {}", nextAgent);
    }

    @Override
    public void onTeamFinished(FlowContext context, TeamTrace trace) {
        // 终态清理
        trace.getProtocolContext().remove(KEY_SWARM_STATE);
        super.onTeamFinished(context, trace);
    }

    @Override
    public void injectSupervisorInstruction(Locale locale, StringBuilder sb) {
        super.injectSupervisorInstruction(locale, sb);

        boolean isZh = Locale.CHINA.getLanguage().equals(locale.getLanguage());
        if (isZh) {
            sb.append("\n- 你目前处于“蜂群模式”。通过观察环境状态（JSON 看板）来决定任务接力。");
            sb.append("\n- 关注集体进展，平衡成员负载。");
        } else {
            sb.append("\n- You are in 'Swarm Mode'. Observe environment state (JSON dashboard) to decide task relays.");
            sb.append("\n- Focus on collective progress and balance member load.");
        }
    }
}