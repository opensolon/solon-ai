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

import java.util.*;
import java.util.stream.Collectors;

/**
 * 蜂群协作协议 (Swarm Protocol)
 *
 * <p>模拟自然界蜂群的集体智能行为，通过简单的个体规则实现复杂的群体协作。</p>
 *
 * <p><b>核心机制：</b></p>
 * <ul>
 * <li><b>集体智能</b>：通过简单个体间的互动涌现出群体智慧</li>
 * <li><b>动态接力</b>：任务在 Agent 间像接力棒一样传递，每个 Agent 基于当前状态决定下一棒</li>
 * <li><b>自组织性</b>：没有中央控制器，每个 Agent 根据局部信息做出决策</li>
 * <li><b>涌现行为</b>：简单规则的组合产生复杂的协作模式</li>
 * </ul>
 *
 * @author noear
 * @since 3.8.1
 */
public class SwarmProtocol extends TeamProtocolBase {
    private static final Logger LOG = LoggerFactory.getLogger(SwarmProtocol.class);

    // 协议配置
    private boolean enableConsensusVoting = false; // 是否启用共识投票
    private boolean enableParallelExecution = false; // 是否允许并行执行
    private boolean enableSwarmIntelligence = true; // 是否启用群体智能
    private boolean enableLoadBalancing = true; // 是否启用负载均衡
    private boolean enableEmergentBehavior = true; // 是否允许涌现行为
    private int maxSwarmSize = 5; // 最大蜂群规模（参与协作的Agent数量）
    private double consensusThreshold = 0.6; // 共识阈值（0-1）

    // 上下文键
    private static final String KEY_AGENT_USAGE = "agent_usage";
    private static final String KEY_SWARM_HISTORY = "swarm_history";
    private static final String KEY_CONSENSUS_DATA = "consensus_data";
    private static final String KEY_SWARM_STATE = "swarm_state";

    public SwarmProtocol(TeamConfig config) {
        super(config);
    }

    /**
     * 设置是否启用共识投票
     */
    public SwarmProtocol withConsensusVoting(boolean enabled) {
        this.enableConsensusVoting = enabled;
        return this;
    }

    /**
     * 设置是否允许并行执行
     */
    public SwarmProtocol withParallelExecution(boolean enabled) {
        this.enableParallelExecution = enabled;
        return this;
    }

    /**
     * 设置是否启用群体智能
     */
    public SwarmProtocol withSwarmIntelligence(boolean enabled) {
        this.enableSwarmIntelligence = enabled;
        return this;
    }

    /**
     * 设置是否启用负载均衡
     */
    public SwarmProtocol withLoadBalancing(boolean enabled) {
        this.enableLoadBalancing = enabled;
        return this;
    }

    /**
     * 设置是否允许涌现行为
     */
    public SwarmProtocol withEmergentBehavior(boolean enabled) {
        this.enableEmergentBehavior = enabled;
        return this;
    }

    /**
     * 设置最大蜂群规模
     */
    public SwarmProtocol withMaxSwarmSize(int size) {
        this.maxSwarmSize = Math.max(2, Math.min(size, 10));
        return this;
    }

    /**
     * 设置共识阈值
     */
    public SwarmProtocol withConsensusThreshold(double threshold) {
        this.consensusThreshold = Math.max(0.5, Math.min(1.0, threshold));
        return this;
    }

    @Override
    public String name() {
        return "SWARM";
    }

    @Override
    public void buildGraph(GraphSpec spec) {
        String firstAgent = config.getAgentMap().keySet().iterator().next();

        spec.addStart(Agent.ID_START).linkAdd(firstAgent);

        config.getAgentMap().values().forEach(a ->
                spec.addActivity(a).linkAdd(Agent.ID_SUPERVISOR));

        spec.addExclusive(new SupervisorTask(config)).then(ns -> {
            linkAgents(ns);
        }).linkAdd(Agent.ID_END);

        spec.addEnd(Agent.ID_END);
    }

    @Override
    public void prepareSupervisorInstruction(FlowContext context, TeamTrace trace, StringBuilder sb) {
        // 蜂群状态监控
        sb.append("\n=== 蜂群智能监控台 ===\n");

        // 蜂群活跃度
        int swarmSize = getActiveSwarmSize(trace);
        sb.append("蜂群规模: ").append(swarmSize).append(" / ").append(config.getAgentMap().size()).append(" 只工蜂\n");

        // 蜂群多样性
        double diversity = calculateSwarmDiversity(trace);
        sb.append("群体多样性: ").append(String.format("%.1f", diversity * 100)).append("%\n");

        // 蜂群效率
        double efficiency = calculateSwarmEfficiency(trace);
        sb.append("协作效率: ").append(String.format("%.1f", efficiency * 100)).append("%\n");

        // 专家使用情况（群体视角）
        Map<String, Integer> usage = getAgentUsage(trace);
        if (!usage.isEmpty()) {
            sb.append("\n工蜂工作负载:\n");

            // 按使用次数排序
            usage.entrySet().stream()
                    .sorted((a, b) -> b.getValue().compareTo(a.getValue()))
                    .forEach(entry -> {
                        String agentName = entry.getKey();
                        int count = entry.getValue();
                        double loadPercentage = (double) count / trace.getStepCount();

                        sb.append("- ").append(agentName).append(": ");
                        sb.append(count).append(" 次任务");
                        sb.append(" (").append(String.format("%.1f", loadPercentage * 100)).append("%)\n");
                    });
        }

        // 群体智能建议
        if (enableSwarmIntelligence) {
            String swarmAdvice = generateSwarmAdvice(trace);
            if (Utils.isNotEmpty(swarmAdvice)) {
                sb.append("\n蜂群智能建议:\n").append(swarmAdvice);
            }
        }

        // 如果启用了共识投票，显示共识状态
        if (enableConsensusVoting) {
            String consensusStatus = getConsensusStatus(trace);
            if (Utils.isNotEmpty(consensusStatus)) {
                sb.append("\n共识状态: ").append(consensusStatus);
            }
        }

        // 蜂群健康检查
        String healthCheck = performSwarmHealthCheck(trace);
        if (Utils.isNotEmpty(healthCheck)) {
            sb.append("\n蜂群健康检查:\n").append(healthCheck);
        }
    }

    /**
     * 获取活跃的蜂群规模
     */
    private int getActiveSwarmSize(TeamTrace trace) {
        Map<String, Integer> usage = getAgentUsage(trace);
        return usage.size();
    }

    /**
     * 计算群体多样性
     */
    private double calculateSwarmDiversity(TeamTrace trace) {
        Map<String, Integer> usage = getAgentUsage(trace);
        if (usage.isEmpty() || config.getAgentMap().isEmpty()) {
            return 0.0;
        }

        return (double) usage.size() / config.getAgentMap().size();
    }

    /**
     * 计算协作效率
     */
    private double calculateSwarmEfficiency(TeamTrace trace) {
        if (trace.getStepCount() <= 1) {
            return 0.5; // 初始效率
        }

        // 简单的效率计算：任务完成度 / 步骤数
        double progress = (double) trace.getIterationsCount() / trace.getConfig().getMaxTotalIterations();
        double efficiency = progress / trace.getStepCount();

        return Math.max(0, Math.min(1, efficiency * 2)); // 归一化到0-1
    }

    /**
     * 获取专家使用情况
     */
    @SuppressWarnings("unchecked")
    private Map<String, Integer> getAgentUsage(TeamTrace trace) {
        return (Map<String, Integer>) trace.getProtocolContext()
                .computeIfAbsent(KEY_AGENT_USAGE, k -> new HashMap<>());
    }

    /**
     * 生成蜂群智能建议
     */
    private String generateSwarmAdvice(TeamTrace trace) {
        Map<String, Integer> usage = getAgentUsage(trace);
        if (usage.isEmpty()) {
            return "蜂群刚刚启动，让工蜂们开始工作吧！";
        }

        StringBuilder advice = new StringBuilder();

        // 检查是否有过度使用的工蜂
        String overworkedAgent = findOverworkedAgent(usage, trace.getStepCount());
        if (overworkedAgent != null) {
            advice.append("⚠️ ").append(overworkedAgent).append(" 工作负担较重，考虑让其他工蜂分担。\n");
        }

        // 检查是否有闲置的工蜂
        List<String> idleAgents = findIdleAgents(usage);
        if (!idleAgents.isEmpty()) {
            advice.append("💡 未活跃的工蜂: ").append(String.join(", ", idleAgents))
                    .append("，可以考虑调动他们参与协作。\n");
        }

        // 检查是否有重复模式
        if (detectRepetitivePattern(trace)) {
            advice.append("🔄 检测到重复模式，建议改变协作策略或引入新的工蜂。\n");
        }

        // 检查蜂群是否过于集中
        if (usage.size() == 1 && trace.getStepCount() > 3) {
            advice.append("🐝 蜂群过于依赖单一工蜂，建议扩大协作范围。");
        } else if (usage.size() >= maxSwarmSize) {
            advice.append("✅ 蜂群规模健康，保持了良好的多样性。");
        }

        return advice.toString();
    }

    /**
     * 查找工作负担过重的工蜂
     */
    private String findOverworkedAgent(Map<String, Integer> usage, int totalSteps) {
        if (totalSteps < 3) {
            return null;
        }

        for (Map.Entry<String, Integer> entry : usage.entrySet()) {
            double loadPercentage = (double) entry.getValue() / totalSteps;
            if (loadPercentage > 0.5) { // 承担超过50%的工作
                return entry.getKey();
            }
        }

        return null;
    }

    /**
     * 查找闲置的工蜂
     */
    private List<String> findIdleAgents(Map<String, Integer> usage) {
        List<String> idleAgents = new ArrayList<>();

        for (String agentName : config.getAgentMap().keySet()) {
            if (!usage.containsKey(agentName) || usage.get(agentName) == 0) {
                idleAgents.add(agentName);
            }
        }

        return idleAgents;
    }

    /**
     * 检测重复模式
     */
    private boolean detectRepetitivePattern(TeamTrace trace) {
        List<TeamTrace.TeamStep> steps = trace.getSteps();
        if (steps.size() < 4) {
            return false;
        }

        // 检查是否有 A->B->A->B 的重复模式
        for (int i = 0; i <= steps.size() - 4; i++) {
            String agent1 = steps.get(i).getAgentName();
            String agent2 = steps.get(i + 1).getAgentName();
            String agent3 = steps.get(i + 2).getAgentName();
            String agent4 = steps.get(i + 3).getAgentName();

            if (agent1.equals(agent3) && agent2.equals(agent4) && !agent1.equals(agent2)) {
                return true;
            }
        }

        return false;
    }

    /**
     * 获取共识状态
     */
    private String getConsensusStatus(TeamTrace trace) {
        if (!enableConsensusVoting) {
            return null;
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> consensusData = (Map<String, Object>) trace.getProtocolContext()
                .get(KEY_CONSENSUS_DATA);

        if (consensusData == null || consensusData.isEmpty()) {
            return "尚未建立共识";
        }

        // 计算共识强度
        int totalVotes = 0;
        int agreedVotes = 0;

        for (Object value : consensusData.values()) {
            if (value instanceof Integer) {
                totalVotes++;
                if ((Integer) value > 0) {
                    agreedVotes++;
                }
            }
        }

        if (totalVotes == 0) {
            return "等待投票";
        }

        double consensusLevel = (double) agreedVotes / totalVotes;
        if (consensusLevel >= consensusThreshold) {
            return String.format("✅ 达成共识 (%.0f%%)", consensusLevel * 100);
        } else {
            return String.format("🔄 共识建设中 (%.0f%%)", consensusLevel * 100);
        }
    }

    /**
     * 执行蜂群健康检查
     */
    private String performSwarmHealthCheck(TeamTrace trace) {
        StringBuilder health = new StringBuilder();

        // 检查步骤数量
        if (trace.getStepCount() > trace.getConfig().getMaxTotalIterations() * 0.8) {
            health.append("⚠️ 步骤数量接近上限，考虑收敛结论。\n");
        }

        // 检查是否有停滞
        if (isSwarmStagnating(trace)) {
            health.append("⚠️ 蜂群可能陷入停滞，建议改变策略。\n");
        }

        // 检查协作进展
        if (trace.getStepCount() > 0 && trace.getLastAgentContent().length() < 50) {
            health.append("⚠️ 最近产出内容较少，可能需要更多协作。\n");
        }

        if (health.length() == 0) {
            health.append("✅ 蜂群健康状况良好");
        }

        return health.toString();
    }

    /**
     * 检查蜂群是否停滞
     */
    private boolean isSwarmStagnating(TeamTrace trace) {
        if (trace.getStepCount() < 3) {
            return false;
        }

        // 检查最近几步是否有进展
        List<TeamTrace.TeamStep> steps = trace.getSteps();
        int recentSteps = Math.min(3, steps.size());

        int emptyOrShortCount = 0;
        for (int i = steps.size() - recentSteps; i < steps.size(); i++) {
            String content = steps.get(i).getContent();
            if (content == null || content.length() < 30) {
                emptyOrShortCount++;
            }
        }

        return emptyOrShortCount >= recentSteps;
    }

    @Override
    public void injectSupervisorInstruction(Locale locale, StringBuilder sb) {
        boolean isChinese = Locale.CHINA.getLanguage().equals(locale.getLanguage());

        sb.append("\n## 协作协议：").append(name()).append("\n");

        if (isChinese) {
            sb.append("1. **蜂群思维**：你不是指挥官，而是蜂群的一员。基于群体状态做出决策。\n");
            sb.append("2. **动态接力**：任务像接力棒一样传递，根据当前状态选择下一棒的合适人选。\n");
            sb.append("3. **集体智能**：通过简单个体互动产生群体智慧，关注整体协作模式而非单个决策。\n");

            if (enableLoadBalancing) {
                sb.append("4. **负载均衡**：避免让某些工蜂过度工作，保持群体健康的工作分配。\n");
            }

            if (enableSwarmIntelligence) {
                sb.append("5. **涌现行为**：允许简单的个体行为组合产生复杂的协作模式。\n");
            }

            if (enableConsensusVoting) {
                sb.append("6. **共识机制**：重要决策可以通过群体投票达成共识。\n");
            }

            if (enableParallelExecution) {
                sb.append("7. **并行协作**：允许多个工蜂同时处理不同子任务。");
            } else {
                sb.append("7. **接力协作**：保持串行接力，确保专注和连贯性。");
            }

            // 添加蜂群规模信息
            sb.append("\n\n蜂群规模: ").append(config.getAgentMap().size()).append(" 只工蜂");
            if (maxSwarmSize < config.getAgentMap().size()) {
                sb.append(" (活跃最多 ").append(maxSwarmSize).append(" 只)");
            }
        } else {
            sb.append("1. **Swarm Mindset**: You are not a commander but part of the swarm. Make decisions based on collective state.\n");
            sb.append("2. **Dynamic Relay**: Tasks pass like a relay baton; choose the next appropriate agent based on current state.\n");
            sb.append("3. **Collective Intelligence**: Generate group wisdom through simple individual interactions; focus on overall collaboration patterns.\n");

            if (enableLoadBalancing) {
                sb.append("4. **Load Balancing**: Avoid overworking certain agents; maintain healthy work distribution.\n");
            }

            if (enableSwarmIntelligence) {
                sb.append("5. **Emergent Behavior**: Allow simple individual behaviors to combine into complex collaboration patterns.\n");
            }

            if (enableConsensusVoting) {
                sb.append("6. **Consensus Mechanism**: Important decisions can be made through group voting.\n");
            }

            if (enableParallelExecution) {
                sb.append("7. **Parallel Collaboration**: Allow multiple agents to handle different subtasks simultaneously.");
            } else {
                sb.append("7. **Relay Collaboration**: Maintain serial relay to ensure focus and continuity.");
            }

            // Add swarm size information
            sb.append("\n\nSwarm Size: ").append(config.getAgentMap().size()).append(" worker bees");
            if (maxSwarmSize < config.getAgentMap().size()) {
                sb.append(" (active max ").append(maxSwarmSize).append(")");
            }
        }
    }

    @Override
    public void onSupervisorRouting(FlowContext context, TeamTrace trace, String nextAgent) {
        // 更新专家使用情况
        Map<String, Integer> usage = getAgentUsage(trace);
        usage.put(nextAgent, usage.getOrDefault(nextAgent, 0) + 1);

        // 记录蜂群历史
        recordSwarmHistory(trace, nextAgent);

        // 如果启用了负载均衡，检查是否需要调整
        if (enableLoadBalancing) {
            checkAndAdjustLoadBalancing(trace, nextAgent);
        }

        LOG.info("Swarm Protocol - Agent {} selected (used {} times total)",
                nextAgent, usage.get(nextAgent));
    }

    /**
     * 记录蜂群历史
     */
    @SuppressWarnings("unchecked")
    private void recordSwarmHistory(TeamTrace trace, String agent) {
        List<String> history = (List<String>) trace.getProtocolContext()
                .computeIfAbsent(KEY_SWARM_HISTORY, k -> new ArrayList<>());

        history.add(agent);

        // 只保留最近的20次选择
        if (history.size() > 20) {
            trace.getProtocolContext().put(KEY_SWARM_HISTORY,
                    new ArrayList<>(history.subList(history.size() - 20, history.size())));
        }
    }

    /**
     * 检查和调整负载均衡
     */
    private void checkAndAdjustLoadBalancing(TeamTrace trace, String selectedAgent) {
        Map<String, Integer> usage = getAgentUsage(trace);
        if (usage.size() < 2 || trace.getStepCount() < 3) {
            return;
        }

        // 检查是否有明显的负载不均衡
        int maxUsage = Collections.max(usage.values());
        int minUsage = Collections.min(usage.values());

        if (maxUsage - minUsage > 2) {
            // 负载不均衡，记录建议
            String overworked = usage.entrySet().stream()
                    .max(Map.Entry.comparingByValue())
                    .map(Map.Entry::getKey)
                    .orElse(null);

            String underworked = usage.entrySet().stream()
                    .min(Map.Entry.comparingByValue())
                    .map(Map.Entry::getKey)
                    .orElse(null);

            if (overworked != null && underworked != null && !overworked.equals(underworked)) {
                LOG.debug("Swarm Protocol - Load imbalance detected: {} ({}), {} ({})",
                        overworked, maxUsage, underworked, minUsage);
            }
        }
    }

    @Override
    public String resolveSupervisorRoute(FlowContext context, TeamTrace trace, String decision) {
        // 蜂群协议可以添加智能推荐逻辑
        if (enableSwarmIntelligence && Utils.isNotEmpty(decision)) {
            String swarmRecommended = swarmRecommendation(trace, decision);
            if (swarmRecommended != null && !swarmRecommended.equals(decision)) {
                LOG.debug("Swarm Protocol - Swarm intelligence suggests: {} -> {}",
                        decision, swarmRecommended);
                // 记录群体智能建议，但不强制覆盖
                trace.getProtocolContext().put("swarm_suggestion", swarmRecommended);
            }
        }

        return null; // 保持默认的决策解析
    }

    /**
     * 蜂群智能推荐算法
     */
    private String swarmRecommendation(TeamTrace trace, String currentDecision) {
        Map<String, Integer> usage = getAgentUsage(trace);
        if (usage.isEmpty()) {
            return null;
        }

        // 优先推荐使用较少的Agent（负载均衡）
        String leastUsedAgent = usage.entrySet().stream()
                .min(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(null);

        // 但也考虑任务相关性
        if (leastUsedAgent != null) {
            // 检查这个Agent是否适合当前任务
            boolean isSuitable = isAgentSuitableForCurrentTask(trace, leastUsedAgent);
            if (isSuitable && !leastUsedAgent.equals(currentDecision)) {
                return leastUsedAgent;
            }
        }

        return null;
    }

    /**
     * 检查Agent是否适合当前任务
     */
    private boolean isAgentSuitableForCurrentTask(TeamTrace trace, String agentName) {
        Agent agent = trace.getConfig().getAgentMap().get(agentName);
        if (agent == null) {
            return false;
        }

        // 简单的适合性检查：基于Agent描述
        String agentDesc = agent.descriptionFor(trace.getContext());
        if (agentDesc == null) {
            return true; // 没有描述，假定适合
        }

        // 检查最近的任务内容
        if (trace.getSteps().isEmpty()) {
            return true;
        }

        TeamTrace.TeamStep lastStep = trace.getSteps().get(trace.getStepCount() - 1);
        String lastContent = lastStep.getContent();

        if (Utils.isEmpty(lastContent)) {
            return true;
        }

        // 简单的关键词匹配
        String lowerDesc = agentDesc.toLowerCase();
        String lowerContent = lastContent.toLowerCase();

        if (lowerDesc.contains("design") || lowerDesc.contains("ui") || lowerDesc.contains("ux")) {
            return lowerContent.contains("design") || lowerContent.contains("ui") ||
                    lowerContent.contains("ux") || lowerContent.contains("界面");
        }

        if (lowerDesc.contains("developer") || lowerDesc.contains("code")) {
            return lowerContent.contains("code") || lowerContent.contains("html") ||
                    lowerContent.contains("css") || lowerContent.contains("实现");
        }

        return true; // 默认认为适合
    }

    @Override
    public void onTeamFinished(FlowContext context, TeamTrace trace) {
        super.onTeamFinished(context, trace);

        // 清理蜂群特定的上下文
        trace.getProtocolContext().remove(KEY_SWARM_HISTORY);
        trace.getProtocolContext().remove(KEY_CONSENSUS_DATA);
        trace.getProtocolContext().remove(KEY_SWARM_STATE);
        trace.getProtocolContext().remove("swarm_suggestion");

        if (LOG.isInfoEnabled()) {
            Map<String, Integer> usage = getAgentUsage(trace);
            LOG.info("Swarm Protocol - Swarm dispersed. Total steps: {}, Active agents: {}",
                    trace.getStepCount(), usage.size());
        }
    }
}