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
import org.noear.solon.ai.chat.tool.FunctionTool;
import org.noear.solon.ai.chat.tool.FunctionToolDesc;
import org.noear.solon.flow.FlowContext;
import org.noear.solon.flow.GraphSpec;
import org.noear.solon.lang.Preview;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;
import java.util.function.Consumer;

/**
 * 蜂群协作协议 (Swarm Protocol)
 * * <p>核心机制：基于“任务涌现”与“信息素挥发”。Agent 可动态生成子任务并放入共享任务池，
 * 通过信息素（Pheromones）权重实现各成员间的负载均衡。</p>
 */
@Preview("3.8.1")
public class SwarmProtocol extends TeamProtocolBase {
    private static final Logger LOG = LoggerFactory.getLogger(SwarmProtocol.class);
    private static final String KEY_SWARM_STATE = "swarm_state_node";
    private static final String TOOL_EMERGE = "__emerge_tasks__";

    public SwarmProtocol(TeamAgentConfig config) {
        super(config);
    }

    @Override
    public String name() { return "SWARM"; }

    @Override
    public void buildGraph(GraphSpec spec) {
        String firstAgent = config.getAgentMap().keySet().iterator().next();
        spec.addStart(Agent.ID_START).linkAdd(firstAgent);

        config.getAgentMap().values().forEach(a ->
                spec.addActivity(a).linkAdd(Agent.ID_SUPERVISOR));

        spec.addExclusive(new SupervisorTask(config)).then(this::linkAgents).linkAdd(Agent.ID_END);
        spec.addEnd(Agent.ID_END);
    }

    private ONode getSwarmState(TeamTrace trace) {
        return (ONode) trace.getProtocolContext().computeIfAbsent(KEY_SWARM_STATE, k -> {
            ONode node = new ONode().asObject();
            node.getOrNew("pheromones");
            node.getOrNew("task_pool").asArray();
            return node;
        });
    }

    /**
     * 注入涌现工具，让 Agent 能够动态拆解任务
     */
    @Override
    public void injectAgentTools(FlowContext context, Agent agent, Consumer<FunctionTool> receiver) {
        boolean isZh = Locale.CHINA.getLanguage().equals(config.getLocale().getLanguage());
        FunctionToolDesc toolDesc = new FunctionToolDesc(TOOL_EMERGE);

        if (isZh) {
            toolDesc.title("涌现任务").description("当你发现需要拆解子任务或建议其他专家介入时调用。")
                    .stringParamAdd("tasks", "待办任务描述，多个用分号分隔")
                    .stringParamAdd("agents", "建议执行的专家名，多个用分号分隔");
        } else {
            toolDesc.title("Emerge Tasks").description("Call this to decompose tasks or suggest other experts.")
                    .stringParamAdd("tasks", "Task descriptions, separated by semicolons")
                    .stringParamAdd("agents", "Suggested agent names, separated by semicolons");
        }

        toolDesc.doHandle(args -> {
            TeamTrace trace = context.getAs(Agent.KEY_CURRENT_TEAM_KEY);
            if (trace != null) {
                ONode state = getSwarmState(trace);
                String tasks = (String) args.get("tasks");
                String agents = (String) args.get("agents");
                parseToPool(state.get("task_pool"), tasks, agents);
            }
            return isZh ? "系统：子任务已加入任务池，Supervisor 将据此调度。" : "System: Tasks added to pool.";
        });
        receiver.accept(toolDesc);
    }

    private void parseToPool(ONode taskPool, String tasks, String agents) {
        if (Utils.isEmpty(tasks)) return;
        String[] taskArr = tasks.split("[,;，；\n]+");
        String[] agentArr = Utils.isNotEmpty(agents) ? agents.split("[,;，；\n]+") : new String[0];

        for (int i = 0; i < taskArr.length; i++) {
            ONode t = new ONode().asObject();
            t.set("task", taskArr[i].trim());
            if (i < agentArr.length) t.set("agent", agentArr[i].trim());
            taskPool.add(t);
        }
    }

    @Override
    public void injectAgentInstruction(FlowContext context, Agent agent, Locale locale, StringBuilder sb) {
        boolean isZh = Locale.CHINA.getLanguage().equals(locale.getLanguage());
        if (isZh) {
            sb.append("\n## 蜂群协作规范\n");
            sb.append("- **任务涌现**：若当前任务需他人配合或拆解，请调用 `").append(TOOL_EMERGE).append("` 将子任务放入池中。\n");
            sb.append("- **专注性**：仅在发现新任务维度时调用工具，不要重复同步已有任务。\n");
        } else {
            sb.append("\n## Swarm Collaboration\n");
            sb.append("- **Emergence**: Use `").append(TOOL_EMERGE).append("` to add sub-tasks to the pool when delegation or decomposition is needed.\n");
        }
    }

    @Override
    public boolean shouldSupervisorRoute(FlowContext context, TeamTrace trace, String decision) {
        if (decision.contains(config.getFinishMarker())) {
            ONode state = getSwarmState(trace);
            // 如果任务池还有任务，拦截结束信号（防早停）
            if (state.get("task_pool").size() > 0 && trace.getIterationsCount() < 5) {
                LOG.warn("Swarm: Finish blocked! Emergent tasks pending in pool.");
                return false;
            }
        }
        return true;
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
        if (taskPool.isArray()) {
            taskPool.getArrayUnsafe().removeIf(n ->
                    nextAgent.equalsIgnoreCase(n.get("agent").getString()) ||
                            nextAgent.equalsIgnoreCase(n.get("task").getString())
            );
        }
    }

    @Override
    public void injectSupervisorInstruction(Locale locale, StringBuilder sb) {
        super.injectSupervisorInstruction(locale, sb); // 保留基类逻辑
        boolean isZh = Locale.CHINA.getLanguage().equals(locale.getLanguage());

        if (isZh) {
            sb.append("\n### SWARM 决策准则：\n");
            sb.append("1. **任务终止**：若任务完成，输出 `[" + config.getFinishMarker() + "]`。\n");
            sb.append("2. **决策优先**：若未完成，仅输出下一位 Agent 名字。不要解释。");
        } else {
            sb.append("\n### SWARM Decision Rules:\n");
            sb.append("1. **Termination**: Output `[" + config.getFinishMarker() + "]` when done.\n");
            sb.append("2. **Routing**: Output only the next Agent name.");
        }
    }

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
        trace.getProtocolContext().remove(KEY_SWARM_STATE);
        super.onTeamFinished(context, trace);
    }
}