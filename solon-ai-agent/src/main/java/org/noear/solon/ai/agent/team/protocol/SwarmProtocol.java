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
import org.noear.solon.ai.agent.team.task.SupervisorTask;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.prompt.Prompt;
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
 *
 * <p>去中心化入口 + 任务涌现池 + 信息素负载感知调度。</p>
 */
@Preview("3.8.1")
public class SwarmProtocol extends TeamProtocolBase {
    private static final Logger LOG = LoggerFactory.getLogger(SwarmProtocol.class);
    private static final String KEY_SWARM_STATE = "swarm_state_obj";
    private static final String TOOL_EMERGE = "__emerge_tasks__";

    /** 信息素挥发步长 */
    private static final int PHEROMONE_DECAY = 1;
    /** 信息素增强步长 */
    private static final int PHEROMONE_REINFORCE = 5;
    /** 任务池上限，防止长会话无限膨胀 */
    private static final int MAX_TASK_POOL_SIZE = 32;

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

        public Map<String, Integer> getPheromones() {
            return pheromones;
        }

        public List<SwarmTask> getTaskPool() {
            return taskPool;
        }

        /**
         * 将涌现任务写入任务池。
         *
         * @param tasks        任务描述，支持 , ; ， ； 换行分隔
         * @param agents       建议专家名，与 tasks 按序对齐
         * @param validAgents  合法专家名集合（用于规范化与过滤）；可为 null
         */
        public synchronized void emerge(String tasks, String agents, Collection<String> validAgents) {
            if (Utils.isEmpty(tasks)) {
                return;
            }

            String[] taskArr = tasks.split("[,;，；\n]+");
            String[] agentArr = Utils.isNotEmpty(agents) ? agents.split("[,;，；\n]+") : new String[0];

            for (int i = 0; i < taskArr.length; i++) {
                if (taskPool.size() >= MAX_TASK_POOL_SIZE) {
                    LOG.warn("Swarm: task pool reached limit {}, drop remaining emergent tasks", MAX_TASK_POOL_SIZE);
                    break;
                }

                String task = taskArr[i] == null ? "" : taskArr[i].trim();
                if (Utils.isEmpty(task)) {
                    continue;
                }

                String agentName = (i < agentArr.length && agentArr[i] != null) ? agentArr[i].trim() : null;
                if (Utils.isEmpty(agentName)) {
                    agentName = null;
                } else {
                    agentName = canonicalAgentName(agentName, validAgents);
                    // 非法专家名降级为空，避免污染调度
                    if (agentName == null && validAgents != null && !validAgents.isEmpty()) {
                        LOG.warn("Swarm: ignore unknown agent suggestion '{}'", agentArr[i].trim());
                    }
                }

                if (containsTask(task, agentName)) {
                    continue;
                }
                taskPool.add(new SwarmTask(task, agentName));
            }
        }

        /**
         * 兼容旧调用：不做专家名校验
         */
        public void emerge(String tasks, String agents) {
            emerge(tasks, agents, null);
        }

        public synchronized void decayAndReinforce(String currentAgent) {
            if (Utils.isEmpty(currentAgent)) {
                return;
            }
            // 挥发
            pheromones.replaceAll((k, v) -> Math.max(0, v - PHEROMONE_DECAY));
            // 增强
            pheromones.merge(currentAgent, PHEROMONE_REINFORCE, Integer::sum);
        }

        /**
         * 仅消费“指派给该 Agent 的第一条任务”，不做任务文本匹配，避免误删。
         */
        public synchronized void consume(String nextAgent) {
            if (Utils.isEmpty(nextAgent)) {
                return;
            }
            for (int i = 0; i < taskPool.size(); i++) {
                SwarmTask t = taskPool.get(i);
                if (nextAgent.equalsIgnoreCase(t.agent)) {
                    taskPool.remove(i);
                    return;
                }
            }
        }

        /**
         * 从任务池挑选下一位执行者：优先低信息素、且尽量避开刚执行完的 Agent。
         * 若任务未指定专家，则在团队中按信息素兜底选择。
         */
        public synchronized String pickNextAgent(Collection<String> agentNames, String lastAgent) {
            if (taskPool.isEmpty() || agentNames == null || agentNames.isEmpty()) {
                return null;
            }

            // 1) 优先：任务池中已标注合法专家的任务
            String bestTagged = null;
            int bestTaggedScore = Integer.MAX_VALUE;
            for (SwarmTask t : taskPool) {
                String canonical = canonicalAgentName(t.agent, agentNames);
                if (canonical == null) {
                    continue;
                }
                int score = scoreAgent(canonical, lastAgent);
                if (score < bestTaggedScore) {
                    bestTaggedScore = score;
                    bestTagged = canonical;
                }
            }
            if (bestTagged != null) {
                return bestTagged;
            }

            // 2) 兜底：池中有未标注专家的任务时，按信息素选负载最低者
            boolean hasUntagged = false;
            for (SwarmTask t : taskPool) {
                if (Utils.isEmpty(t.agent)) {
                    hasUntagged = true;
                    break;
                }
            }
            if (!hasUntagged) {
                return null;
            }

            String best = null;
            int bestScore = Integer.MAX_VALUE;
            for (String name : agentNames) {
                int score = scoreAgent(name, lastAgent);
                if (score < bestScore) {
                    bestScore = score;
                    best = name;
                }
            }
            return best;
        }

        private int scoreAgent(String agentName, String lastAgent) {
            int p = pheromones.getOrDefault(agentName, 0);
            // 刚执行完的 Agent 额外加权，形成冷却
            if (Utils.isNotEmpty(lastAgent) && agentName.equalsIgnoreCase(lastAgent)) {
                p += PHEROMONE_REINFORCE;
            }
            return p;
        }

        private boolean containsTask(String task, String agent) {
            for (SwarmTask t : taskPool) {
                if (task.equals(t.task)
                        && ((agent == null && t.agent == null)
                        || (agent != null && agent.equalsIgnoreCase(t.agent)))) {
                    return true;
                }
            }
            return false;
        }

        private static String canonicalAgentName(String name, Collection<String> agentNames) {
            if (Utils.isEmpty(name) || agentNames == null) {
                return Utils.isEmpty(name) ? null : name;
            }
            for (String candidate : agentNames) {
                if (candidate != null && candidate.equalsIgnoreCase(name)) {
                    return candidate;
                }
            }
            return null;
        }
    }

    public SwarmProtocol(TeamAgentConfig config) {
        super(config);
    }

    @Override
    public String name() {
        return "SWARM";
    }

    @Override
    public void buildGraph(GraphSpec spec) {
        if (config.getAgentMap() == null || config.getAgentMap().isEmpty()) {
            throw new IllegalStateException("SwarmProtocol requires at least one agent");
        }

        String firstAgent = config.getAgentMap().keySet().iterator().next();
        spec.addStart(Agent.ID_START).linkAdd(firstAgent);

        config.getAgentMap().values().forEach(a ->
                spec.addActivity(a).linkAdd(TeamAgent.ID_SUPERVISOR));

        spec.addExclusive(new SupervisorTask(config)).then(this::linkAgents).linkAdd(Agent.ID_END);
        spec.addEnd(Agent.ID_END);
    }

    /**
     * 获取（或初始化）蜂群状态
     */
    public SwarmState getSwarmState(TeamTrace trace) {
        return (SwarmState) trace.getProtocolContext()
                .computeIfAbsent(KEY_SWARM_STATE, k -> new SwarmState());
    }

    @Override
    public void injectAgentTools(FlowContext context, Agent agent, Consumer<FunctionTool> receiver) {
        TeamTrace trace = TeamTrace.getCurrent(context);
        if (trace == null) {
            return;
        }

        SwarmState state = getSwarmState(trace);
        FunctionToolDesc tool = new FunctionToolDesc(TOOL_EMERGE).returnDirect(true);
        tool.metaPut(Agent.META_AGENT, agent.name());

        boolean isZh = isChinese(config.getLocale());
        Collection<String> validAgents = config.getAgentMap().keySet();

        if (isZh) {
            tool.title("涌现任务").description("当你发现需要拆解子任务或建议其他专家介入时调用。")
                    .stringParamAdd("tasks", "待办任务描述，多个用分号分隔")
                    .stringParamAdd("agents", "建议执行的专家名，多个用分号分隔");
        } else {
            tool.title("Emerge Tasks").description("Call this to decompose tasks or suggest other experts.")
                    .stringParamAdd("tasks", "Task descriptions, separated by semicolons")
                    .stringParamAdd("agents", "Suggested agent names, separated by semicolons");
        }

        tool.doHandle(args -> {
            state.emerge((String) args.get("tasks"), (String) args.get("agents"), validAgents);
            return isZh ? "系统：子任务已加入任务池。" : "System: Tasks added to pool.";
        });

        receiver.accept(tool);
    }

    @Override
    public void injectAgentInstruction(FlowContext context, Agent agent, Locale locale, StringBuilder sb) {
        // 保留基类身份 / 协作历史，再追加蜂群规范
        super.injectAgentInstruction(context, agent, locale, sb);

        boolean isZh = isChinese(locale);
        if (isZh) {
            sb.append("\n## 蜂群协作规范\n");
            sb.append("- **任务涌现**：若当前任务需他人配合或拆解，请调用 `").append(TOOL_EMERGE).append("` 将子任务放入池中。\n");
            sb.append("- **专注性**：仅在发现新任务维度时调用工具，不要重复同步已有任务。\n");
        } else {
            sb.append("\n## Swarm Collaboration\n");
            sb.append("- **Emergence**: Use `").append(TOOL_EMERGE).append("` to add sub-tasks to the pool when delegation or decomposition is needed.\n");
            sb.append("- **Focus**: Only call the tool for new task dimensions; do not re-sync existing tasks.\n");
        }
    }

    /**
     * 向成员注入精简任务池快照，降低重复涌现。
     */
    @Override
    public Prompt prepareAgentPrompt(TeamTrace trace, Agent agent, Prompt originalPrompt, Locale locale) {
        Prompt finalPrompt = super.prepareAgentPrompt(trace, agent, originalPrompt, locale);

        SwarmState state = (SwarmState) trace.getProtocolContext().get(KEY_SWARM_STATE);
        if (state == null || state.getTaskPool().isEmpty()) {
            return finalPrompt;
        }

        boolean isZh = isChinese(locale);
        String info = isZh ? "【蜂群任务池快照】" : "[Swarm Task Pool Snapshot]";
        String snapshot = info + "\n```json\n" + ONode.serialize(state) + "\n```";
        return finalPrompt.addMessage(ChatMessage.ofUser(snapshot));
    }

    /**
     * 兼容文本 JSON 涌现：解析 {@code sub_tasks:[{task,agent},...]} 写入任务池。
     * 工具路径仍是主路径；此逻辑保证历史/集成测试中的 JSON 输出可被识别。
     */
    @Override
    public String resolveAgentOutput(TeamTrace trace, Agent agent, String content) {
        if (Utils.isEmpty(content)) {
            return content;
        }

        ONode json = sniffJson(content);
        if (!json.hasKey("sub_tasks")) {
            return content;
        }
        ONode subTasks = json.get("sub_tasks");
        if (!subTasks.isArray() || subTasks.isEmpty()) {
            return content;
        }

        SwarmState state = getSwarmState(trace);
        Collection<String> validAgents = config.getAgentMap().keySet();
        StringBuilder tasks = new StringBuilder();
        StringBuilder agents = new StringBuilder();

        for (ONode item : subTasks.getArray()) {
            if (item == null || !item.isObject()) {
                continue;
            }
            String task = item.get("task").getString();
            String agentName = item.get("agent").getString();
            if (Utils.isEmpty(task)) {
                continue;
            }
            if (tasks.length() > 0) {
                tasks.append(';');
                agents.append(';');
            }
            tasks.append(task.trim());
            agents.append(Utils.isEmpty(agentName) ? "" : agentName.trim());
        }

        if (tasks.length() > 0) {
            state.emerge(tasks.toString(), agents.toString(), validAgents);
            LOG.info("Swarm: absorbed {} sub_tasks from agent '{}' output", subTasks.getArray().size(),
                    agent != null ? agent.name() : "?");
        }

        return content;
    }

    /**
     * 解析主管路由。
     * <ul>
     *   <li>finish + 任务池非空 + 未达 maxTurns：硬回派池中下一 Agent，避免 commitRoute 在
     *       shouldSupervisorRoute=false 后仍因无法 matchAgent 而误入 END</li>
     *   <li>正常 finish：return null，交由 commitRoute + shouldSupervisorRoute 统一收口</li>
     * </ul>
     */
    @Override
    public String resolveSupervisorRoute(FlowContext context, TeamTrace trace, String decision) {
        if (decision != null && decision.contains(config.getFinishMarker())) {
            SwarmState state = getSwarmState(trace);
            int maxTurns = maxTurnsOf(trace);

            if (!state.getTaskPool().isEmpty() && trace.getTurnCount() < maxTurns) {
                String next = state.pickNextAgent(config.getAgentMap().keySet(), trace.getLastAgentName());
                if (Utils.isNotEmpty(next)) {
                    LOG.warn("Swarm: Finish blocked! Re-routing to pending agent '{}'. turn={}/{}, pool={}",
                            next, trace.getTurnCount(), maxTurns, state.getTaskPool().size());
                    return next;
                }
                // 池中任务均无法解析到合法 Agent：返回 null，由 shouldSupervisorRoute 继续拦截
                LOG.warn("Swarm: Finish blocked by non-empty task pool, but no resolvable agent. turn={}/{}",
                        trace.getTurnCount(), maxTurns);
                return null;
            }

            // 正常 finish：不在此直接 ID_END
            return null;
        }

        return super.resolveSupervisorRoute(context, trace, decision);
    }

    @Override
    public boolean shouldSupervisorRoute(FlowContext context, TeamTrace trace, String decision) {
        if (decision != null && decision.contains(config.getFinishMarker())) {
            SwarmState state = getSwarmState(trace);
            int maxTurns = maxTurnsOf(trace);
            // 使用配置的 maxTurns，不再硬编码
            if (!state.getTaskPool().isEmpty() && trace.getTurnCount() < maxTurns) {
                LOG.warn("Swarm: Finish blocked! Emergent tasks pending in pool. turn={}/{}",
                        trace.getTurnCount(), maxTurns);
                return false;
            }
        }
        // 保留基类 SOP 守卫（末位 Agent 参与等）
        return super.shouldSupervisorRoute(context, trace, decision);
    }

    @Override
    public void onAgentEnd(TeamTrace trace, Agent agent) {
        if (agent != null) {
            getSwarmState(trace).decayAndReinforce(agent.name());
        }
        super.onAgentEnd(trace, agent);
    }

    @Override
    public void onSupervisorRouting(FlowContext context, TeamTrace trace, String nextAgent) {
        // 系统节点不消费任务池
        if (Utils.isNotEmpty(nextAgent)
                && !Agent.ID_END.equals(nextAgent)
                && !Agent.ID_START.equals(nextAgent)
                && !TeamAgent.ID_SUPERVISOR.equals(nextAgent)
                && !TeamAgent.ID_SYSTEM.equals(nextAgent)) {
            getSwarmState(trace).consume(nextAgent);
        }
        super.onSupervisorRouting(context, trace, nextAgent);
    }

    @Override
    public void injectSupervisorInstruction(Locale locale, StringBuilder sb) {
        super.injectSupervisorInstruction(locale, sb);
        boolean isZh = isChinese(locale);

        if (isZh) {
            sb.append("\n### SWARM 决策准则：\n");
            sb.append("1. **任务终止**：若任务完成，输出 `").append(config.getFinishMarker()).append("`。\n");
            sb.append("2. **决策优先**：若未完成，仅输出下一位 Agent 名字。不要解释。\n");
            sb.append("3. **任务池优先**：看板 task_pool 非空时，优先指派其中建议的专家。");
        } else {
            sb.append("\n### SWARM Decision Rules:\n");
            sb.append("1. **Termination**: Output `").append(config.getFinishMarker()).append("` when done.\n");
            sb.append("2. **Routing**: Output only the next Agent name.\n");
            sb.append("3. **Task Pool First**: When task_pool is non-empty, prioritize suggested agents.");
        }
    }

    @Override
    public void prepareSupervisorInstruction(FlowContext context, TeamTrace trace, StringBuilder sb) {
        SwarmState state = getSwarmState(trace);
        boolean isZh = isChinese(config.getLocale());

        sb.append(isZh ? "\n### 蜂群状态看板 (Swarm Intelligence)\n" : "\n### Swarm Intelligence Dashboard\n");
        // 保持看板的 JSON 可读性
        sb.append("```json\n").append(ONode.serialize(state)).append("\n```\n");

        String recommended = state.pickNextAgent(config.getAgentMap().keySet(), trace.getLastAgentName());

        if (isZh) {
            sb.append("> **调度指引**：\n");
            sb.append("> 1. **负载均衡**：优先指派 pheromones 值较低的成员。\n");
            sb.append("> 2. **能力匹配**：确保 task_pool 中的任务描述与 Agent 的 Capabilities 一致。\n");
            sb.append("> 3. **冷却避障**：避免连续指派刚执行完的 Agent。\n");
            if (Utils.isNotEmpty(recommended) && !state.getTaskPool().isEmpty()) {
                sb.append("> 4. **推荐下一执行者**：`").append(recommended).append("`\n");
            }
        } else {
            sb.append("> **Instruction**:\n");
            sb.append("> 1. **Load Balancing**: Prioritize agents with lower pheromone values.\n");
            sb.append("> 2. **Skill Match**: Ensure emergent tasks in task_pool align with Agent's Capabilities.\n");
            sb.append("> 3. **Cool-down**: Avoid re-assigning the agent that just finished.\n");
            if (Utils.isNotEmpty(recommended) && !state.getTaskPool().isEmpty()) {
                sb.append("> 4. **Recommended next**: `").append(recommended).append("`\n");
            }
        }
    }

    private static boolean isChinese(Locale locale) {
        return locale != null && Locale.CHINA.getLanguage().equals(locale.getLanguage());
    }

    private static int maxTurnsOf(TeamTrace trace) {
        if (trace.getOptions() != null) {
            return trace.getOptions().getMaxTurns();
        }
        return 5;
    }
}
