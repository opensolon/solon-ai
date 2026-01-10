package org.noear.solon.ai.agent.team.protocol;

import org.noear.solon.Utils;
import org.noear.solon.ai.agent.Agent;
import org.noear.solon.ai.agent.team.TeamConfig;
import org.noear.solon.ai.agent.team.TeamTrace;
import org.noear.solon.flow.FlowContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * 市场机制协作协议 (Market-Based Protocol)
 *
 * <p>模拟市场经济的协作模式，将 Agent 视为独立服务提供商，通过市场竞争机制实现最优任务分配。</p>
 *
 * <p><b>核心机制：</b></p>
 * <ul>
 * <li><b>服务化视角</b>：每个 Agent 都是独立的服务提供商，提供特定领域的专业服务</li>
 * <li><b>竞争择优</b>：基于专业能力、历史表现、执行效率等维度竞争任务</li>
 * <li><b>成本效益</b>：考虑任务完成的质量、时间、资源消耗等综合成本</li>
 * <li><b>动态定价</b>：根据供需关系和历史表现动态调整专家"价格"</li>
 * </ul>
 *
 * @author noear
 * @since 3.8.1
 */
public class MarketBasedProtocol_H extends HierarchicalProtocol_H {
    private static final Logger LOG = LoggerFactory.getLogger(MarketBasedProtocol_H.class);

    // 协议配置
    private boolean enableCostCalculation = true; // 是否启用成本计算
    private boolean enableDynamicPricing = false; // 是否启用动态定价
    private boolean enableQualityScoring = true; // 是否启用质量评分
    private boolean enableBiddingSimulation = true; // 是否启用竞价模拟
    private boolean autoInitializePerformance = true; // 是否自动初始化专家表现
    private double qualityWeight = 0.6; // 质量权重 (0-1)
    private double efficiencyWeight = 0.3; // 效率权重 (0-1)
    private double costWeight = 0.1; // 成本权重 (0-1)
    private double initialQualityScore = 0.7; // 初始质量得分
    private double initialEfficiencyScore = 0.6; // 初始效率得分
    private double initialCostScore = 0.3; // 初始成本得分（越低越好）

    // 上下文键
    private static final String KEY_AGENT_PERFORMANCE = "agent_performance";
    private static final String KEY_MARKET_HISTORY = "market_history";
    private static final String KEY_TASK_COMPLEXITY = "task_complexity";
    private static final String KEY_LAST_EXECUTION_RESULT = "last_execution_result";

    public MarketBasedProtocol_H(TeamConfig config) {
        super(config);
    }

    /**
     * 设置是否启用成本计算
     */
    public MarketBasedProtocol_H withCostCalculation(boolean enabled) {
        this.enableCostCalculation = enabled;
        return this;
    }

    /**
     * 设置是否启用动态定价
     */
    public MarketBasedProtocol_H withDynamicPricing(boolean enabled) {
        this.enableDynamicPricing = enabled;
        return this;
    }

    /**
     * 设置是否启用质量评分
     */
    public MarketBasedProtocol_H withQualityScoring(boolean enabled) {
        this.enableQualityScoring = enabled;
        return this;
    }

    /**
     * 设置是否启用竞价模拟
     */
    public MarketBasedProtocol_H withBiddingSimulation(boolean enabled) {
        this.enableBiddingSimulation = enabled;
        return this;
    }

    /**
     * 设置是否自动初始化专家表现
     */
    public MarketBasedProtocol_H withAutoPerformanceInit(boolean enabled) {
        this.autoInitializePerformance = enabled;
        return this;
    }

    /**
     * 设置决策权重
     */
    public MarketBasedProtocol_H withDecisionWeights(double qualityWeight, double efficiencyWeight, double costWeight) {
        double total = qualityWeight + efficiencyWeight + costWeight;
        if (total > 0) {
            this.qualityWeight = qualityWeight / total;
            this.efficiencyWeight = efficiencyWeight / total;
            this.costWeight = costWeight / total;
        }
        return this;
    }

    /**
     * 设置初始表现得分
     */
    public MarketBasedProtocol_H withInitialScores(double quality, double efficiency, double cost) {
        this.initialQualityScore = Math.max(0, Math.min(1, quality));
        this.initialEfficiencyScore = Math.max(0, Math.min(1, efficiency));
        this.initialCostScore = Math.max(0, Math.min(1, cost));
        return this;
    }

    @Override
    public String name() {
        return "MARKET_BASED";
    }

    @Override
    public void prepareSupervisorInstruction(FlowContext context, TeamTrace trace, StringBuilder sb) {
        super.prepareSupervisorInstruction(context, trace, sb);

        // 初始化专家表现数据（如果需要）
        if (autoInitializePerformance && (trace.getStepCount() == 0 || getAgentPerformance(trace).isEmpty())) {
            initializeAgentPerformance(trace);
        }

        // 添加市场特有的信息
        sb.append("\n=== 市场机制控制台 ===\n");

        // 专家市场表现
        Map<String, AgentPerformance> performance = getAgentPerformance(trace);
        if (!performance.isEmpty()) {
            sb.append("\n专家市场表现 (按综合得分排序):\n");

            // 按综合得分排序
            performance.entrySet().stream()
                    .sorted((a, b) -> Double.compare(b.getValue().overallScore(), a.getValue().overallScore()))
                    .forEach(entry -> {
                        String agentName = entry.getKey();
                        AgentPerformance perf = entry.getValue();

                        sb.append("- ").append(agentName).append(": ");
                        sb.append("质量").append(String.format("%.1f", perf.qualityScore() * 100)).append("%");
                        sb.append(", 效率").append(String.format("%.1f", perf.efficiencyScore() * 100)).append("%");
                        sb.append(", 成本").append(String.format("%.1f", perf.costScore() * 100)).append("%");
                        sb.append(", 综合").append(String.format("%.1f", perf.overallScore() * 100)).append("%");

                        // 添加任务完成次数
                        sb.append(" (已完成").append(perf.getTaskCount()).append("次任务)");

                        // 如果是动态定价，显示"价格"信息
                        if (enableDynamicPricing) {
                            double price = perf.calculateDynamicPrice();
                            sb.append(", 当前价格: ").append(String.format("%.2f", price));
                        }

                        sb.append("\n");
                    });
        }

        // 如果启用了竞价模拟，提供市场建议
        if (enableBiddingSimulation) {
            String marketAdvice = generateMarketAdvice(trace);
            if (Utils.isNotEmpty(marketAdvice)) {
                sb.append("\n市场建议:\n").append(marketAdvice);
            }
        }

        // 当前任务复杂度评估
        String complexity = assessTaskComplexity(trace);
        if (Utils.isNotEmpty(complexity)) {
            sb.append("\n任务复杂度评估: ").append(complexity);
        }

        // 如果启用了动态定价，显示定价说明
        if (enableDynamicPricing) {
            sb.append("\n\n动态定价说明：");
            boolean isChinese = Locale.CHINA.getLanguage().equals(trace.getConfig().getLocale());
            if (isChinese) {
                sb.append("专家价格基于历史表现动态调整，表现越好价格越高，需权衡性价比。");
            } else {
                sb.append("Expert prices adjust dynamically based on historical performance; better performance = higher price, balance cost-effectiveness.");
            }
        }
    }

    /**
     * 初始化专家表现数据
     */
    private void initializeAgentPerformance(TeamTrace trace) {
        Map<String, AgentPerformance> performance = getAgentPerformance(trace);

        for (String agentName : trace.getConfig().getAgentMap().keySet()) {
            if (!performance.containsKey(agentName)) {
                AgentPerformance perf = new AgentPerformance();

                // 根据专家描述设置初始得分
                String agentDesc = trace.getConfig().getAgentMap().get(agentName)
                        .descriptionFor(trace.getContext());

                // 根据专家类型调整初始得分
                double quality = adjustInitialScoreByExpertise(agentName, agentDesc, initialQualityScore, "quality");
                double efficiency = adjustInitialScoreByExpertise(agentName, agentDesc, initialEfficiencyScore, "efficiency");
                double cost = adjustInitialScoreByExpertise(agentName, agentDesc, initialCostScore, "cost");

                perf.addRecord(quality, efficiency, cost);
                performance.put(agentName, perf);

                LOG.debug("MarketBased Protocol - Initialized performance for agent {}: Q={}, E={}, C={}",
                        agentName, quality, efficiency, cost);
            }
        }
    }

    /**
     * 根据专家类型调整初始得分
     */
    private double adjustInitialScoreByExpertise(String agentName, String agentDesc,
                                                 double baseScore, String scoreType) {
        if (agentDesc == null) {
            return baseScore;
        }

        String lowerName = agentName.toLowerCase();
        String lowerDesc = agentDesc.toLowerCase();
        double adjustment = 0;

        // 质量得分调整
        if ("quality".equals(scoreType)) {
            if (lowerName.contains("design") || lowerDesc.contains("design") ||
                    lowerName.contains("ui") || lowerDesc.contains("ui") ||
                    lowerName.contains("ux") || lowerDesc.contains("ux")) {
                adjustment += 0.1; // 设计师通常质量要求高
            }
            if (lowerName.contains("review") || lowerDesc.contains("review") ||
                    lowerName.contains("edit") || lowerDesc.contains("edit") ||
                    lowerName.contains("审核") || lowerDesc.contains("审核")) {
                adjustment += 0.15; // 审核者质量要求最高
            }
        }

        // 效率得分调整
        if ("efficiency".equals(scoreType)) {
            if (lowerName.contains("developer") || lowerDesc.contains("developer") ||
                    lowerName.contains("coder") || lowerDesc.contains("coder") ||
                    lowerName.contains("开发") || lowerDesc.contains("开发")) {
                adjustment += 0.1; // 开发者通常效率较高
            }
        }

        // 成本得分调整（成本越低越好，所以调整方向相反）
        if ("cost".equals(scoreType)) {
            if (lowerName.contains("expert") || lowerDesc.contains("expert") ||
                    lowerName.contains("senior") || lowerDesc.contains("senior") ||
                    lowerName.contains("高级") || lowerDesc.contains("高级")) {
                adjustment += 0.1; // 高级专家成本较高
            }
            if (lowerName.contains("junior") || lowerDesc.contains("junior") ||
                    lowerName.contains("assistant") || lowerDesc.contains("assistant") ||
                    lowerName.contains("初级") || lowerDesc.contains("初级")) {
                adjustment -= 0.1; // 初级专家成本较低
            }
        }

        return Math.max(0, Math.min(1, baseScore + adjustment));
    }

    /**
     * 获取专家表现数据
     */
    @SuppressWarnings("unchecked")
    private Map<String, AgentPerformance> getAgentPerformance(TeamTrace trace) {
        return (Map<String, AgentPerformance>) trace.getProtocolContext()
                .computeIfAbsent(KEY_AGENT_PERFORMANCE, k -> new HashMap<>());
    }

    /**
     * 更新专家表现（基于任务执行结果）
     */
    private void updateAgentPerformance(TeamTrace trace, String agentName,
                                        String executionResult, long executionTimeMs) {
        Map<String, AgentPerformance> performance = getAgentPerformance(trace);
        AgentPerformance perf = performance.get(agentName);
        if (perf == null) {
            perf = new AgentPerformance();
            performance.put(agentName, perf);
        }

        // 分析执行结果的质量
        double quality = analyzeExecutionQuality(executionResult);

        // 计算效率得分（执行时间越短效率越高）
        double efficiency = calculateEfficiencyScore(executionTimeMs);

        // 计算成本得分（综合考虑质量和时间）
        double cost = calculateCostScore(quality, executionTimeMs);

        perf.addRecord(quality, efficiency, cost);

        // 保存最后一次执行结果用于后续分析
        trace.getProtocolContext().put(KEY_LAST_EXECUTION_RESULT,
                new ExecutionResult(agentName, quality, efficiency, cost, executionTimeMs));

        LOG.info("MarketBased Protocol - Updated performance for {}: Q={}, E={}, C={}, Time={}ms",
                agentName, quality, efficiency, cost, executionTimeMs);
    }

    /**
     * 分析执行结果的质量
     */
    private double analyzeExecutionQuality(String result) {
        if (Utils.isEmpty(result)) {
            return 0.3; // 空结果质量最低
        }

        double score = 0.5; // 基础分

        // 根据内容特征评估质量
        if (result.contains("<html>") || result.contains("<!DOCTYPE html>")) {
            score += 0.2; // 包含完整HTML结构
        }

        if (result.contains("```") && result.split("```").length >= 3) {
            score += 0.1; // 包含代码块
        }

        if (result.contains("##") || result.contains("###")) {
            score += 0.1; // 结构清晰
        }

        if (result.length() > 500) {
            score += 0.1; // 内容详实
        }

        if (result.contains("FINISH") || result.contains("完成")) {
            score += 0.1; // 明确完成任务
        }

        return Math.max(0, Math.min(1, score));
    }

    /**
     * 计算效率得分
     */
    private double calculateEfficiencyScore(long executionTimeMs) {
        // 执行时间越短，效率越高
        // 假设理想执行时间：10秒=1.0分，60秒=0.5分，120秒=0.0分
        if (executionTimeMs <= 10000) return 1.0;
        if (executionTimeMs >= 120000) return 0.0;

        return 1.0 - ((executionTimeMs - 10000.0) / 110000.0);
    }

    /**
     * 计算成本得分（成本越低得分越低）
     */
    private double calculateCostScore(double quality, long executionTimeMs) {
        // 成本综合考虑质量和时间
        // 高质量但耗时长 = 中等成本
        // 低质量但耗时短 = 中等成本
        // 高质量且耗时短 = 低成本
        // 低质量且耗时长 = 高成本

        double timeCost = executionTimeMs / 60000.0; // 按分钟计算时间成本
        double qualityFactor = 1.0 - quality; // 质量越低，成本因子越高

        double cost = (timeCost * 0.6 + qualityFactor * 0.4) / 2.0;

        return Math.max(0, Math.min(1, cost));
    }

    /**
     * 生成市场建议
     */
    private String generateMarketAdvice(TeamTrace trace) {
        Map<String, AgentPerformance> performance = getAgentPerformance(trace);
        if (performance.isEmpty()) {
            return "暂无历史数据，建议进行市场调研（让多个专家尝试）";
        }

        // 找出综合得分最高的专家
        String bestAgent = null;
        double bestScore = 0;

        // 找出性价比最高的专家（综合考虑得分和成本）
        String bestValueAgent = null;
        double bestValueScore = 0;

        for (Map.Entry<String, AgentPerformance> entry : performance.entrySet()) {
            AgentPerformance perf = entry.getValue();
            double score = perf.overallScore();

            if (score > bestScore) {
                bestScore = score;
                bestAgent = entry.getKey();
            }

            // 价值得分 = 质量得分 / 成本得分（成本越低越好）
            double valueScore = perf.qualityScore() / (perf.costScore() + 0.01);
            if (valueScore > bestValueScore) {
                bestValueScore = valueScore;
                bestValueAgent = entry.getKey();
            }
        }

        StringBuilder advice = new StringBuilder();

        if (bestAgent != null && bestScore > 0.7) {
            advice.append("推荐专家: ").append(bestAgent)
                    .append(" (综合得分: ").append(String.format("%.1f", bestScore * 100)).append("%)\n");
        }

        if (bestValueAgent != null && !bestValueAgent.equals(bestAgent) && bestValueScore > 2.0) {
            advice.append("性价比推荐: ").append(bestValueAgent)
                    .append(" (价值比: ").append(String.format("%.1f", bestValueScore)).append(")\n");
        }

        if (advice.length() == 0) {
            advice.append("市场表现均衡，可根据具体任务需求选择专家");
        }

        return advice.toString();
    }

    /**
     * 评估任务复杂度
     */
    private String assessTaskComplexity(TeamTrace trace) {
        if (trace.getPrompt() == null) {
            return "未知";
        }

        String task = trace.getPrompt().getUserContent();
        if (Utils.isEmpty(task)) {
            return "简单";
        }

        // 简单的复杂度评估
        int wordCount = task.split("\\s+").length;
        boolean hasMultipleRequirements = task.contains("和") || task.contains("并且") ||
                task.contains("同时") || task.contains("both") ||
                task.contains("与") || task.contains("and");
        boolean hasTechnicalTerms = task.contains("代码") || task.contains("设计") ||
                task.contains("分析") || task.contains("实现") ||
                task.contains("develop") || task.contains("implement") ||
                task.contains("create") || task.contains("build");

        if (wordCount > 50 && hasMultipleRequirements && hasTechnicalTerms) {
            trace.getProtocolContext().put(KEY_TASK_COMPLEXITY, "高");
            return "高复杂度（建议选择经验丰富的专家）";
        } else if (wordCount > 20 && (hasMultipleRequirements || hasTechnicalTerms)) {
            trace.getProtocolContext().put(KEY_TASK_COMPLEXITY, "中");
            return "中等复杂度";
        } else {
            trace.getProtocolContext().put(KEY_TASK_COMPLEXITY, "低");
            return "低复杂度（可考虑成本较低的专家）";
        }
    }

    @Override
    public void injectSupervisorInstruction(Locale locale, StringBuilder sb) {
        boolean isChinese = Locale.CHINA.getLanguage().equals(locale.getLanguage());

        sb.append("\n## 协作协议：").append(name()).append("\n");

        if (isChinese) {
            sb.append("1. **市场视角**：将专家视为独立服务商，你扮演客户角色，寻求最佳服务。\n");
            sb.append("2. **专业契合度**：评估专家能力与任务需求的匹配程度。\n");

            if (enableCostCalculation) {
                sb.append("3. **成本效益分析**：考虑执行时间、资源消耗等隐形成本。\n");
            }

            if (enableQualityScoring) {
                sb.append("4. **质量导向**：优先选择历史表现优秀、产出质量高的专家。\n");
            }

            if (enableBiddingSimulation) {
                sb.append("5. **虚拟竞价**：想象专家们正在竞标此任务，选择最具竞争力的方案。\n");
            }

            if (enableDynamicPricing) {
                sb.append("6. **动态定价**：热门专家可能'价格'较高，需权衡性价比。");
            } else {
                sb.append("6. **效能最优**：选择能以最少轮次、最高质量完成任务的专家。");
            }

            // 添加权重信息
            sb.append("\n\n决策权重：质量(").append((int)(qualityWeight * 100)).append("%)");
            sb.append(" + 效率(").append((int)(efficiencyWeight * 100)).append("%)");
            sb.append(" + 成本(").append((int)(costWeight * 100)).append("%)");

            // 添加性能初始化说明
            if (autoInitializePerformance) {
                sb.append("\n\n说明：专家表现已自动初始化，将根据实际执行结果动态更新。");
            }
        } else {
            sb.append("1. **Market Perspective**: Treat experts as independent service providers; you act as a client seeking the best service.\n");
            sb.append("2. **Expertise Fit**: Assess how well each expert's skills match the task requirements.\n");

            if (enableCostCalculation) {
                sb.append("3. **Cost-Benefit Analysis**: Consider hidden costs like execution time and resource consumption.\n");
            }

            if (enableQualityScoring) {
                sb.append("4. **Quality Focus**: Prioritize experts with excellent historical performance and high output quality.\n");
            }

            if (enableBiddingSimulation) {
                sb.append("5. **Virtual Bidding**: Imagine experts are bidding for this task; select the most competitive proposal.\n");
            }

            if (enableDynamicPricing) {
                sb.append("6. **Dynamic Pricing**: Popular experts may have higher 'prices'; balance cost-effectiveness.");
            } else {
                sb.append("6. **Efficiency First**: Select experts who can complete tasks with minimal iterations and highest quality.");
            }

            // Add weight information
            sb.append("\n\nDecision Weights: Quality(").append((int)(qualityWeight * 100)).append("%)");
            sb.append(" + Efficiency(").append((int)(efficiencyWeight * 100)).append("%)");
            sb.append(" + Cost(").append((int)(costWeight * 100)).append("%)");

            // Add performance initialization note
            if (autoInitializePerformance) {
                sb.append("\n\nNote: Expert performance automatically initialized and will update dynamically based on execution results.");
            }
        }
    }

    @Override
    public String resolveSupervisorRoute(FlowContext context, TeamTrace trace, String decision) {
        // 市场协议可以添加智能推荐逻辑
        if (enableBiddingSimulation && Utils.isNotEmpty(decision)) {
            String marketRecommended = marketRecommendation(decision, trace);
            if (marketRecommended != null) {
                LOG.debug("MarketBased Protocol - Market recommendation: {} -> {}", decision, marketRecommended);
                // 可以记录市场决策，但不强制覆盖
            }
        }

        return super.resolveSupervisorRoute(context, trace, decision);
    }

    /**
     * 市场推荐算法
     */
    private String marketRecommendation(String taskDescription, TeamTrace trace) {
        // 基于专家表现和市场机制进行推荐
        Map<String, AgentPerformance> performance = getAgentPerformance(trace);
        if (performance.isEmpty()) {
            return null;
        }

        // 计算每个专家的市场得分
        Map<String, Double> marketScores = new HashMap<>();

        for (Map.Entry<String, AgentPerformance> entry : performance.entrySet()) {
            AgentPerformance perf = entry.getValue();
            double score = perf.marketScore(qualityWeight, efficiencyWeight, costWeight);

            // 如果启用了动态定价，考虑价格因素
            if (enableDynamicPricing) {
                double price = perf.calculateDynamicPrice();
                score = score / (price + 0.1); // 价格越高，调整后的得分越低
            }

            marketScores.put(entry.getKey(), score);
        }

        // 找出得分最高的专家
        return marketScores.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(null);
    }

    @Override
    public void onSupervisorRouting(FlowContext context, TeamTrace trace, String nextAgent) {
        super.onSupervisorRouting(context, trace, nextAgent);

        // 记录市场交易
        recordMarketTransaction(trace, nextAgent);
    }

    /**
     * 记录市场交易
     */
    @SuppressWarnings("unchecked")
    private void recordMarketTransaction(TeamTrace trace, String agent) {
        if (Agent.ID_SUPERVISOR.equals(agent) || Agent.ID_END.equals(agent)) {
            return;
        }

        List<String> history = (List<String>) trace.getProtocolContext()
                .computeIfAbsent(KEY_MARKET_HISTORY, k -> new ArrayList<>());

        history.add(agent);

        if (LOG.isDebugEnabled()) {
            LOG.debug("MarketBased Protocol - Market transaction recorded: {}", agent);
        }
    }

    @Override
    public void onAgentEnd(TeamTrace trace, Agent agent) {
        // 当Agent执行结束时，更新其表现数据
        TeamTrace.TeamStep lastStep = trace.getSteps().isEmpty() ? null :
                trace.getSteps().get(trace.getStepCount() - 1);

        if (lastStep != null && agent.name().equals(lastStep.getAgentName())) {
            // 获取执行结果和执行时间
            String result = lastStep.getContent();
            long executionTime = lastStep.getDuration();

            // 更新专家表现
            updateAgentPerformance(trace, agent.name(), result, executionTime);
        }
    }

    @Override
    public void onTeamFinished(FlowContext context, TeamTrace trace) {
        super.onTeamFinished(context, trace);

        // 清理市场特定的上下文
        trace.getProtocolContext().remove(KEY_MARKET_HISTORY);
        trace.getProtocolContext().remove(KEY_TASK_COMPLEXITY);
        trace.getProtocolContext().remove(KEY_LAST_EXECUTION_RESULT);

        if (LOG.isInfoEnabled()) {
            Map<String, AgentPerformance> performance = getAgentPerformance(trace);
            LOG.info("MarketBased Protocol - Market closed. Total transactions: {}, Active agents: {}",
                    trace.getStepCount(), performance.size());
        }
    }

    /**
     * 专家表现记录类
     */
    private static class AgentPerformance {
        private int totalTasks = 0;
        private double totalQuality = 0;
        private double totalEfficiency = 0;
        private double totalCost = 0;

        void addRecord(double quality, double efficiency, double cost) {
            totalTasks++;
            totalQuality += quality;
            totalEfficiency += efficiency;
            totalCost += cost;
        }

        int getTaskCount() {
            return totalTasks;
        }

        double qualityScore() {
            return totalTasks == 0 ? 0.5 : totalQuality / totalTasks;
        }

        double efficiencyScore() {
            return totalTasks == 0 ? 0.5 : totalEfficiency / totalTasks;
        }

        double costScore() {
            return totalTasks == 0 ? 0.5 : totalCost / totalTasks;
        }

        double overallScore() {
            return (qualityScore() + efficiencyScore() + (1 - costScore())) / 3;
        }

        double marketScore(double qualityWeight, double efficiencyWeight, double costWeight) {
            return qualityScore() * qualityWeight +
                    efficiencyScore() * efficiencyWeight +
                    (1 - costScore()) * costWeight;
        }

        /**
         * 计算动态价格（表现越好价格越高）
         */
        double calculateDynamicPrice() {
            double basePrice = 1.0;
            double performanceBonus = overallScore() * 0.5; // 最高增加50%
            double scarcityFactor = Math.min(1.0, totalTasks * 0.1); // 任务越多越稀缺

            return basePrice + performanceBonus + scarcityFactor;
        }
    }

    /**
     * 执行结果记录类
     */
    private static class ExecutionResult {
        final String agentName;
        final double quality;
        final double efficiency;
        final double cost;
        final long executionTimeMs;

        ExecutionResult(String agentName, double quality, double efficiency,
                        double cost, long executionTimeMs) {
            this.agentName = agentName;
            this.quality = quality;
            this.efficiency = efficiency;
            this.cost = cost;
            this.executionTimeMs = executionTimeMs;
        }
    }
}