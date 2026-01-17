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
import org.noear.solon.ai.agent.team.TeamAgent;
import org.noear.solon.ai.agent.team.TeamAgentConfig;
import org.noear.solon.ai.agent.team.TeamTrace;
import org.noear.solon.ai.chat.tool.FunctionTool;
import org.noear.solon.ai.chat.tool.FunctionToolDesc;
import org.noear.solon.flow.FlowContext;
import org.noear.solon.flow.GraphSpec;
import org.noear.solon.lang.Preview;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.function.Consumer;

/**
 * 蜂群协作协议 (Swarm Protocol)
 */
@Preview("3.8.1")
public class SwarmProtocol extends TeamProtocolBase {
    private static final Logger LOG = LoggerFactory.getLogger(SwarmProtocol.class);
    private static final String KEY_SWARM_STATE = "swarm_state_obj";
    private static final String TOOL_EMERGE = "__emerge_tasks__";

    /**
     * 涌现任务实体
     */
    public static class SwarmTask {
        public String task;
        public String agent;

        public SwarmTask(String task, String agent) {
            this.task = task;
            this.agent = agent;
        }
    }

    /**
     * 强类型蜂群状态实体
     */
    public static class SwarmState {
        private final Map<String, Integer> pheromones = new HashMap<>();
        private final List<SwarmTask> taskPool = new ArrayList<>();

        public Map<String, Integer> getPheromones() { return pheromones; }
        public List<SwarmTask> getTaskPool() { return taskPool; }

        public void emerge(String tasks, String agents) {
            if (Utils.isEmpty(tasks)) return;
            String[] taskArr = tasks.split("[,;，；\n]+");
            String[] agentArr = Utils.isNotEmpty(agents) ? agents.split("[,;，；\n]+") : new String[0];

            for (int i = 0; i < taskArr.length; i++) {
                taskPool.add(new SwarmTask(taskArr[i].trim(), (i < agentArr.length) ? agentArr[i].trim() : null));
            }
        }

        public void decayAndReinforce(String currentAgent) {
            // 挥发
            pheromones.replaceAll((k, v) -> Math.max(0, v - 1));
            // 增强
            pheromones.merge(currentAgent, 5, Integer::sum);
        }

        public void consume(String nextAgent) {
            taskPool.removeIf(t -> nextAgent.equalsIgnoreCase(t.agent) || nextAgent.equalsIgnoreCase(t.task));
        }
    }

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
                spec.addActivity(a).linkAdd(TeamAgent.ID_SUPERVISOR));

        spec.addExclusive(new SupervisorTask(config)).then(this::linkAgents).linkAdd(Agent.ID_END);
        spec.addEnd(Agent.ID_END);
    }

    private SwarmState getSwarmState(TeamTrace trace) {
        return (SwarmState) trace.getProtocolContext()
                .computeIfAbsent(KEY_SWARM_STATE, k -> new SwarmState());
    }

    @Override
    public void injectAgentTools(FlowContext context, Agent agent, Consumer<FunctionTool> receiver) {
        TeamTrace trace = context.getAs(Agent.KEY_CURRENT_TEAM_TRACE_KEY);

        if (trace != null) {
            SwarmState state = getSwarmState(trace);
            FunctionToolDesc toolDesc = new FunctionToolDesc(TOOL_EMERGE).returnDirect(true);
            boolean isZh = Locale.CHINA.getLanguage().equals(config.getLocale().getLanguage());

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
                state.emerge((String) args.get("tasks"), (String) args.get("agents"));
                return isZh ? "系统：子任务已加入任务池。" : "System: Tasks added to pool.";
            });

            receiver.accept(toolDesc);
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
            SwarmState state = getSwarmState(trace);
            if (!state.getTaskPool().isEmpty() && trace.getTurnCount() < 5) {
                LOG.warn("Swarm: Finish blocked! Emergent tasks pending in pool.");
                return false;
            }
        }
        return true;
    }

    @Override
    public void onAgentEnd(TeamTrace trace, Agent agent) {
        getSwarmState(trace).decayAndReinforce(agent.name());
    }

    @Override
    public void onSupervisorRouting(FlowContext context, TeamTrace trace, String nextAgent) {
        getSwarmState(trace).consume(nextAgent);
    }

    @Override
    public void injectSupervisorInstruction(Locale locale, StringBuilder sb) {
        super.injectSupervisorInstruction(locale, sb);
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
        SwarmState state = getSwarmState(trace);
        boolean isZh = Locale.CHINA.getLanguage().equals(config.getLocale().getLanguage());

        sb.append(isZh ? "\n### 蜂群状态看板 (Swarm Intelligence)\n" : "\n### Swarm Intelligence Dashboard\n");
        // 保持看板的 JSON 可读性，内部自动序列化
        sb.append("```json\n").append(ONode.serialize(state)).append("\n```\n");

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
}