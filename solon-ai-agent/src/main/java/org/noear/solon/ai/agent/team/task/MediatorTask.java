package org.noear.solon.ai.agent.team.task;

import org.noear.solon.ai.agent.Agent;
import org.noear.solon.ai.agent.team.TeamConfig;
import org.noear.solon.ai.agent.team.TeamStrategy;
import org.noear.solon.ai.agent.team.TeamTrace;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.flow.FlowContext;
import org.noear.solon.flow.Node;
import org.noear.solon.flow.TaskComponent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 团队协作调解任务（决策中心）
 * 负责根据策略、历史记录和 Agent 状态决定下一步路由
 */
public class MediatorTask implements TaskComponent {
    private static final Logger LOG = LoggerFactory.getLogger(MediatorTask.class);
    private final TeamConfig config;

    // 策略特定的上下文信息
    private final Map<String, Object> strategyContext = new HashMap<>();

    public MediatorTask(TeamConfig config) {
        this.config = config;
    }

    @Override
    public void run(FlowContext context, Node node) throws Throwable {
        try {
            Prompt prompt = context.getAs(Agent.KEY_PROMPT);
            String traceKey = context.getAs(Agent.KEY_CURRENT_TRACE_KEY);
            TeamTrace trace = context.getAs(traceKey);

            if (trace == null) {
                LOG.error("Team trace not found in context");
                return;
            }

            // 1. 检查终止条件（增强版）
            if (shouldTerminate(trace, context)) {
                trace.setRoute(Agent.ID_END);
                trace.addStep("mediator", "Task terminated by mediator", 0);
                return;
            }

            // 2. 准备协议补充信息
            StringBuilder protocolExt = new StringBuilder();
            prepareProtocolInfo(context, trace, protocolExt);

            // 3. 构建策略特定的上下文
            String strategyContextInfo = prepareStrategyContext(context, trace);

            // 4. 构建 Prompt 并调用模型
            String basePrompt = config.getSystemPrompt(prompt);
            String enhancedPrompt = basePrompt + protocolExt.toString() + strategyContextInfo;

            if (LOG.isDebugEnabled()) {
                LOG.debug("Mediator system prompt:\n{}", enhancedPrompt);
            }

            // 获取模型决策
            String decision;
            try {
                String history = trace.getFormattedHistory();
                if (LOG.isDebugEnabled()) {
                    LOG.debug("Providing history to mediator:\n{}", history);
                }

                decision = config.getChatModel().prompt(Arrays.asList(
                        ChatMessage.ofSystem(enhancedPrompt),
                        ChatMessage.ofUser("Collaboration History:\n" + history +
                                "\n\nCurrent iteration: " + trace.iterationsCount() +
                                "\nPlease decide the next action:")
                )).call().getResultContent().trim();

                if (LOG.isDebugEnabled()) {
                    LOG.debug("Mediator decision: {}", decision);
                }
            } catch (Exception e) {
                LOG.error("Failed to get decision from LLM", e);
                decision = config.getFinishMarker(); // 出错时直接结束
            }

            // 5. 解析决策并设置路由
            parseAndRoute(trace, decision, context);

            // 6. 迭代计数
            trace.nextIterations();

        } catch (Exception e) {
            LOG.error("Mediator task failed", e);
            String traceKey = context.getAs(Agent.KEY_CURRENT_TRACE_KEY);
            if (traceKey != null) {
                TeamTrace trace = context.getAs(traceKey);
                if (trace != null) {
                    trace.setRoute(Agent.ID_END);
                    trace.addStep("mediator", "Mediator failed: " + e.getMessage(), 0);
                }
            }
        }
    }

    /**
     * 增强的终止条件检查
     */
    private boolean shouldTerminate(TeamTrace trace, FlowContext context) {
        // 超过最大迭代次数
        if (trace.iterationsCount() >= config.getMaxTotalIterations()) {
            trace.addStep("system", "Maximum iterations reached (" +
                    config.getMaxTotalIterations() + ")", 0);
            return true;
        }

        // 检测死循环
        if (trace.isLooping()) {
            trace.addStep("system", "Loop detected in team collaboration", 0);
            return true;
        }

        // 检查是否有最终答案
        if (trace.getFinalAnswer() != null && !trace.getFinalAnswer().isEmpty()) {
            return true;
        }

        // 检查是否有任何步骤包含完成标记
        for (TeamTrace.TeamStep step : trace.getSteps()) {
            if (step.getContent().contains(config.getFinishMarker())) {
                return true;
            }
        }

        return false;
    }

    /**
     * 准备协议补充信息
     */
    private void prepareProtocolInfo(FlowContext context, TeamTrace trace, StringBuilder sb) {
        TeamStrategy strategy = config.getStrategy();

        if (strategy == TeamStrategy.CONTRACT_NET) {
            String bids = context.getAs("active_bids");
            if (bids != null) {
                sb.append("\n=== Contract Net Protocol Context ===\n");
                sb.append("Bids have been collected from all candidates.\n");
                sb.append(bids);
                sb.append("\n\nPlease review these proposals and select the best Agent name to award the task.");
                // 清理上下文，避免重复处理
                context.remove("active_bids");
            } else {
                sb.append("\n=== Contract Net Protocol Context ===\n");
                sb.append("No bids collected yet. You may output '").append(Agent.ID_BIDDING)
                        .append("' to start the bidding process.");
            }
        } else if (strategy == TeamStrategy.MARKET_BASED) {
            sb.append("\n=== Market-based Protocol Context ===\n");
            sb.append("Consider efficiency and cost-effectiveness when selecting agents.");
            sb.append("\nCurrent iteration cost: ").append(trace.iterationsCount());
        } else if (strategy == TeamStrategy.BLACKBOARD) {
            sb.append("\n=== Blackboard Protocol Context ===\n");
            sb.append("All agents share the blackboard (history). ");
            sb.append("Select agents that can fill knowledge gaps.");
        } else if (strategy == TeamStrategy.SWARM) {
            sb.append("\n=== Swarm Protocol Context ===\n");
            sb.append("Agents work in a peer-to-peer fashion. ");
            sb.append("Select the next agent based on the previous output.");
        }
    }

    /**
     * 准备策略特定的上下文信息
     */
    private String prepareStrategyContext(FlowContext context, TeamTrace trace) {
        StringBuilder sb = new StringBuilder();
        TeamStrategy strategy = config.getStrategy();

        if (strategy == TeamStrategy.HIERARCHICAL) {
            sb.append("\n=== Hierarchical Context ===\n");
            sb.append("You are the lead supervisor. You have full authority over task decomposition and assignment.");
            sb.append("\nTotal agents available: ").append(config.getAgentMap().size());
        } else if (strategy == TeamStrategy.SWARM) {
            // 跟踪每个Agent的使用次数
            Map<String, Integer> agentUsage = (Map<String, Integer>) strategyContext.getOrDefault("agent_usage", new HashMap<>());
            if (agentUsage.isEmpty()) {
                config.getAgentMap().keySet().forEach(name -> agentUsage.put(name, 0));
            }

            sb.append("\n=== Swarm Context ===\n");
            sb.append("Agent usage statistics:\n");
            agentUsage.forEach((name, count) -> {
                sb.append("- ").append(name).append(": used ").append(count).append(" times\n");
            });
            sb.append("\nConsider load balancing when selecting agents.");
        }

        return sb.toString();
    }

    /**
     * 增强的决策解析和路由设置
     */
    private void parseAndRoute(TeamTrace trace, String decision, FlowContext context) {
        if (decision == null || decision.trim().isEmpty()) {
            trace.setRoute(Agent.ID_END);
            trace.addStep("mediator", "Empty decision received, terminating", 0);
            return;
        }

        String upperDecision = decision.toUpperCase().trim();
        String finishMarker = config.getFinishMarker().toUpperCase();

        // A. 优先判断结束标识
        if (upperDecision.contains(finishMarker)) {
            trace.setRoute(Agent.ID_END);

            // 提取最终答案
            int finishIndex = decision.toUpperCase().indexOf(finishMarker);
            if (finishIndex >= 0 && finishIndex + finishMarker.length() < decision.length()) {
                String finalAnswer = decision.substring(finishIndex + finishMarker.length()).trim();
                if (!finalAnswer.isEmpty()) {
                    trace.setFinalAnswer(finalAnswer);
                }
            }
            return;
        }

        // B. 合同网协议：识别招标信号
        if (config.getStrategy() == TeamStrategy.CONTRACT_NET) {
            String biddingMarker = Agent.ID_BIDDING.toUpperCase();
            if (upperDecision.contains(biddingMarker)) {
                trace.setRoute(Agent.ID_BIDDING);
                trace.addStep("mediator", "Initiating bidding process", 0);
                return;
            }
        }

        // C. Agent 名称匹配（采用长度降序排列，确保长名优先匹配）
        List<String> agentNames = new ArrayList<>(config.getAgentMap().keySet());
        List<String> sortedNames = agentNames.stream()
                .sorted((a, b) -> Integer.compare(b.length(), a.length()))
                .collect(Collectors.toList());

        for (String name : sortedNames) {
            String upperName = name.toUpperCase();
            // 更严格的匹配：确保匹配的是完整的单词
            if (upperDecision.contains(" " + upperName + " ") ||
                    upperDecision.startsWith(upperName + " ") ||
                    upperDecision.endsWith(" " + upperName) ||
                    upperDecision.equals(upperName)) {
                trace.setRoute(name);

                // 更新策略上下文
                updateStrategyContext(name);

                if (LOG.isDebugEnabled()) {
                    LOG.debug("Selected agent: {}", name);
                }
                return;
            }
        }

        // D. 尝试模糊匹配（去除特殊字符后匹配）
        String cleanDecision = upperDecision.replaceAll("[^A-Z0-9]", " ");
        for (String name : sortedNames) {
            String upperName = name.toUpperCase();
            if (cleanDecision.contains(upperName)) {
                trace.setRoute(name);
                updateStrategyContext(name);
                LOG.debug("Fuzzy matched agent: {}", name);
                return;
            }
        }

        // E. 兜底方案：如果没有匹配到任何 Agent 且模型没有说结束，默认结束以防死循环
        trace.setRoute(Agent.ID_END);
        trace.addStep("mediator", "No valid agent matched from decision: " + decision, 0);
        LOG.warn("No agent matched from decision: {}", decision);
    }

    /**
     * 更新策略上下文信息
     */
    private void updateStrategyContext(String agentName) {
        TeamStrategy strategy = config.getStrategy();

        if (strategy == TeamStrategy.SWARM) {
            Map<String, Integer> agentUsage = (Map<String, Integer>)
                    strategyContext.computeIfAbsent("agent_usage", k -> new HashMap<>());
            agentUsage.put(agentName, agentUsage.getOrDefault(agentName, 0) + 1);
        }
    }
}