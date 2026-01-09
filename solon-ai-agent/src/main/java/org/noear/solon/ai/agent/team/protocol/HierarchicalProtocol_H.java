package org.noear.solon.ai.agent.team.protocol;

import org.noear.solon.Utils;
import org.noear.solon.ai.agent.Agent;
import org.noear.solon.ai.agent.team.TeamConfig;
import org.noear.solon.ai.agent.team.TeamTrace;
import org.noear.solon.ai.agent.team.task.SupervisorTask;
import org.noear.solon.flow.FlowContext;
import org.noear.solon.flow.GraphSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;
import java.util.Map;

/**
 * 层级化协作协议 (Hierarchical Protocol)
 *
 * <p>采用中心化的层级管理模式，Supervisor 作为顶层管理者，负责所有任务的调度和协调。</p>
 *
 * <p><b>核心机制：</b></p>
 * <ul>
 * <li><b>中心化控制</b>：Supervisor 拥有最高决策权，控制所有任务流转</li>
 * <li><b>专业分工</b>：每个 Agent 专注于自己的专业领域，不越界处理其他任务</li>
 * <li><b>严格流程</b>：遵循严格的请示-汇报流程，确保任务有序执行</li>
 * <li><b>质量把关</b>：Supervisor 负责最终结果的质量审核和交付</li>
 * </ul>
 *
 * @author noear
 * @since 3.8.1
 */
public class HierarchicalProtocol_H extends TeamProtocolBase {
    private static final Logger LOG = LoggerFactory.getLogger(HierarchicalProtocol_H.class);

    // 协议配置
    private boolean enableDirectResponse = true; // 是否允许 Supervisor 直接回复简单问题
    private boolean enableLoadBalancing = false; // 是否启用负载均衡
    private boolean enableExpertSelection = true; // 是否启用专家智能选择
    private int maxParallelTasks = 1; // 最大并行任务数（层级化通常是串行的）
    private boolean strictRoleEnforcement = true; // 是否严格执行角色边界

    // 上下文键
    private static final String KEY_AGENT_USAGE = "agent_usage";
    private static final String KEY_LAST_ASSIGNMENT = "last_assignment";

    public HierarchicalProtocol_H(TeamConfig config) {
        super(config);
    }

    /**
     * 设置是否允许 Supervisor 直接回复简单问题
     */
    public HierarchicalProtocol_H withDirectResponse(boolean enabled) {
        this.enableDirectResponse = enabled;
        return this;
    }

    /**
     * 设置是否启用负载均衡
     */
    public HierarchicalProtocol_H withLoadBalancing(boolean enabled) {
        this.enableLoadBalancing = enabled;
        return this;
    }

    /**
     * 设置是否启用专家智能选择
     */
    public HierarchicalProtocol_H withExpertSelection(boolean enabled) {
        this.enableExpertSelection = enabled;
        return this;
    }

    /**
     * 设置最大并行任务数
     */
    public HierarchicalProtocol_H withMaxParallelTasks(int max) {
        this.maxParallelTasks = Math.max(1, max);
        return this;
    }

    /**
     * 设置是否严格执行角色边界
     */
    public HierarchicalProtocol_H withStrictRoleEnforcement(boolean strict) {
        this.strictRoleEnforcement = strict;
        return this;
    }

    @Override
    public String name() {
        return "HIERARCHICAL";
    }

    @Override
    public void buildGraph(GraphSpec spec) {
        // 层级化结构：所有任务都经过 Supervisor
        spec.addStart(Agent.ID_START).linkAdd(Agent.ID_SUPERVISOR);

        spec.addExclusive(new SupervisorTask(config)).then(ns -> {
            linkAgents(ns);
        }).linkAdd(Agent.ID_END);

        config.getAgentMap().values().forEach(a ->
                spec.addActivity(a).linkAdd(Agent.ID_SUPERVISOR));

        spec.addEnd(Agent.ID_END);
    }

    @Override
    public void prepareSupervisorInstruction(FlowContext context, TeamTrace trace, StringBuilder sb) {
        sb.append("\n=== 层级化管理控制台 ===\n");

        // 团队基本信息
        sb.append("团队规模: ").append(trace.getConfig().getAgentMap().size()).append(" 名专家\n");
        sb.append("当前迭代: ").append(trace.getIterationsCount()).append("\n");
        sb.append("已完成步骤: ").append(trace.getStepCount()).append("\n");

        // 专家使用统计
        Map<String, Integer> agentUsage = getAgentUsage(trace);
        if (!agentUsage.isEmpty()) {
            sb.append("\n专家调用统计:\n");
            agentUsage.forEach((agentName, count) -> {
                sb.append("- ").append(agentName).append(": ").append(count).append(" 次\n");
            });
        }

        // 负载均衡建议（如果启用）
        if (enableLoadBalancing && !agentUsage.isEmpty()) {
            String loadBalanceSuggestion = suggestLoadBalancing(agentUsage);
            if (Utils.isNotEmpty(loadBalanceSuggestion)) {
                sb.append("\n负载均衡建议:\n").append(loadBalanceSuggestion);
            }
        }
    }

    /**
     * 获取专家使用统计
     */
    @SuppressWarnings("unchecked")
    private Map<String, Integer> getAgentUsage(TeamTrace trace) {
        return (Map<String, Integer>) trace.getProtocolContext()
                .computeIfAbsent(KEY_AGENT_USAGE, k -> new java.util.HashMap<>());
    }

    /**
     * 记录专家使用情况
     */
    private void recordAgentUsage(TeamTrace trace, String agentName) {
        Map<String, Integer> usage = getAgentUsage(trace);
        usage.put(agentName, usage.getOrDefault(agentName, 0) + 1);

        trace.getProtocolContext().put(KEY_LAST_ASSIGNMENT, agentName);

        if (LOG.isDebugEnabled()) {
            LOG.debug("Hierarchical Protocol - Agent {} assigned (total: {} times)",
                    agentName, usage.get(agentName));
        }
    }

    /**
     * 建议负载均衡
     */
    private String suggestLoadBalancing(Map<String, Integer> agentUsage) {
        if (agentUsage.size() < 2) {
            return null;
        }

        // 找出使用最频繁和最不频繁的专家
        String mostUsed = null;
        String leastUsed = null;
        int maxUsage = 0;
        int minUsage = Integer.MAX_VALUE;

        for (Map.Entry<String, Integer> entry : agentUsage.entrySet()) {
            if (entry.getValue() > maxUsage) {
                maxUsage = entry.getValue();
                mostUsed = entry.getKey();
            }
            if (entry.getValue() < minUsage) {
                minUsage = entry.getValue();
                leastUsed = entry.getKey();
            }
        }

        if (mostUsed != null && leastUsed != null && maxUsage - minUsage > 2) {
            return "建议: " + mostUsed + " 使用较频繁 (" + maxUsage + "次)，" +
                    leastUsed + " 使用较少 (" + minUsage + "次)，可考虑任务分流。";
        }

        return null;
    }

    @Override
    public void injectSupervisorInstruction(Locale locale, StringBuilder sb) {
        boolean isChinese = Locale.CHINA.getLanguage().equals(locale.getLanguage());

        sb.append("\n## 协作协议：").append(name()).append("\n");

        if (isChinese) {
            sb.append("1. **核心角色**：你是团队管理者，不是执行者。专注于调度和协调，而非内容创作。\n");
            sb.append("2. **专业分工**：");
            if (strictRoleEnforcement) {
                sb.append("严格执行角色边界，确保每个任务由最合适的专家处理。\n");
            } else {
                sb.append("优先指派专家处理，必要时可自己处理简单任务。\n");
            }
            sb.append("3. **任务指派**：");
            if (enableExpertSelection) {
                sb.append("智能选择最适合当前任务的专家，考虑其专业能力和历史表现。\n");
            } else {
                sb.append("根据任务需求指派对应的专家。\n");
            }
            sb.append("4. **响应策略**：");
            if (enableDirectResponse) {
                sb.append("对于简单的确认、感谢或已有结果的解释，可以直接回复。\n");
            } else {
                sb.append("所有任务都应交由专家处理，你只负责调度。\n");
            }
            sb.append("5. **质量把关**：审查专家产出，确保质量达标后才输出结束信号。\n");
            sb.append("6. **流程控制**：");
            if (maxParallelTasks > 1) {
                sb.append("最多可并行处理 ").append(maxParallelTasks).append(" 个任务。\n");
            } else {
                sb.append("串行处理任务，确保每个任务专注完成。");
            }
        } else {
            sb.append("1. **Core Role**: You are the team manager, not an executor. Focus on scheduling and coordination, not content creation.\n");
            sb.append("2. **Specialization**: ");
            if (strictRoleEnforcement) {
                sb.append("Enforce strict role boundaries; ensure each task is handled by the most suitable expert.\n");
            } else {
                sb.append("Assign to experts first, handle simple tasks yourself when necessary.\n");
            }
            sb.append("3. **Task Assignment**: ");
            if (enableExpertSelection) {
                sb.append("Intelligently select the most suitable expert based on expertise and historical performance.\n");
            } else {
                sb.append("Assign tasks to appropriate experts based on requirements.\n");
            }
            sb.append("4. **Response Strategy**: ");
            if (enableDirectResponse) {
                sb.append("Respond directly to simple confirmations, thanks, or explanations of existing results.\n");
            } else {
                sb.append("All tasks should be handled by experts; you only schedule.\n");
            }
            sb.append("5. **Quality Control**: Review expert outputs; ensure quality before issuing completion signal.\n");
            sb.append("6. **Process Control**: ");
            if (maxParallelTasks > 1) {
                sb.append("Handle up to ").append(maxParallelTasks).append(" tasks in parallel.\n");
            } else {
                sb.append("Handle tasks serially to ensure focus on each task.");
            }
        }
    }

    @Override
    public boolean shouldSupervisorRoute(FlowContext context, TeamTrace trace, String decision) {
        // 层级化协议下，Supervisor 应该总是参与路由决策
        return true;
    }

    @Override
    public String resolveSupervisorRoute(FlowContext context, TeamTrace trace, String decision) {
        // 层级化协议可以添加智能专家选择逻辑
        if (enableExpertSelection && Utils.isNotEmpty(decision)) {
            String suggestedAgent = suggestExpertForTask(decision, trace);
            if (suggestedAgent != null && !suggestedAgent.equals(decision)) {
                // 如果智能建议与原始决策不同，记录日志
                LOG.debug("Hierarchical Protocol - Expert selection: {} -> {}", decision, suggestedAgent);
            }
            // 注意：这里不覆盖决策，只是提供建议
        }

        return null; // 保持默认的决策解析
    }

    /**
     * 根据任务内容建议最合适的专家
     */
    private String suggestExpertForTask(String taskDescription, TeamTrace trace) {
        if (Utils.isEmpty(taskDescription)) {
            return null;
        }

        // 简单的关键词匹配逻辑
        String lowerTask = taskDescription.toLowerCase();

        for (Map.Entry<String, Agent> entry : trace.getConfig().getAgentMap().entrySet()) {
            String agentName = entry.getKey().toLowerCase();
            String agentDesc = entry.getValue().descriptionFor(trace.getContext());
            if (agentDesc != null) {
                agentDesc = agentDesc.toLowerCase();
            } else {
                agentDesc = "";
            }

            // 检查任务描述是否包含专家相关的关键词
            if (agentName.contains("design") || agentDesc.contains("design") ||
                    agentName.contains("ui") || agentDesc.contains("ui") ||
                    agentName.contains("ux") || agentDesc.contains("ux") ||
                    agentName.contains("设计")) {

                if (lowerTask.contains("design") || lowerTask.contains("ui") ||
                        lowerTask.contains("ux") || lowerTask.contains("界面") ||
                        lowerTask.contains("视觉") || lowerTask.contains("布局")) {
                    return entry.getKey();
                }
            }

            if (agentName.contains("developer") || agentDesc.contains("developer") ||
                    agentName.contains("code") || agentDesc.contains("code") ||
                    agentName.contains("html") || agentDesc.contains("html") ||
                    agentName.contains("css") || agentDesc.contains("css") ||
                    agentName.contains("开发") || agentDesc.contains("开发")) {

                if (lowerTask.contains("code") || lowerTask.contains("html") ||
                        lowerTask.contains("css") || lowerTask.contains("implement") ||
                        lowerTask.contains("实现") || lowerTask.contains("代码")) {
                    return entry.getKey();
                }
            }

            if (agentName.contains("researcher") || agentDesc.contains("researcher") ||
                    agentName.contains("research") || agentDesc.contains("research") ||
                    agentName.contains("分析") || agentDesc.contains("分析") ||
                    agentName.contains("研究") || agentDesc.contains("研究")) {

                if (lowerTask.contains("research") || lowerTask.contains("analyze") ||
                        lowerTask.contains("study") || lowerTask.contains("分析") ||
                        lowerTask.contains("研究") || lowerTask.contains("调查")) {
                    return entry.getKey();
                }
            }
        }

        return null;
    }

    @Override
    public void onSupervisorRouting(FlowContext context, TeamTrace trace, String nextAgent) {
        // 记录专家使用情况
        if (!Agent.ID_SUPERVISOR.equals(nextAgent) && !Agent.ID_END.equals(nextAgent)) {
            recordAgentUsage(trace, nextAgent);
        }

        if (LOG.isInfoEnabled()) {
            LOG.info("Hierarchical Protocol - Supervisor routing to: {}, iteration: {}, steps: {}",
                    nextAgent, trace.getIterationsCount(), trace.getStepCount());
        }
    }

    @Override
    public void onTeamFinished(FlowContext context, TeamTrace trace) {
        // 清理协议上下文
        trace.getProtocolContext().remove(KEY_AGENT_USAGE);
        trace.getProtocolContext().remove(KEY_LAST_ASSIGNMENT);

        // 输出执行摘要
        if (LOG.isInfoEnabled()) {
            Map<String, Integer> agentUsage = getAgentUsage(trace);
            LOG.info("Hierarchical Protocol - Team finished. Total steps: {}, Iterations: {}, Agent usage: {}",
                    trace.getStepCount(), trace.getIterationsCount(), agentUsage);
        }
    }
}