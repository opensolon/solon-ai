/*
 * Copyright 2017-2025 noear.org and authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
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
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 团队协作调解任务（决策中心）
 * 负责根据策略、历史记录和 Agent 状态决定下一步路由
 *
 * @author noear
 * @since 3.8.1
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
                trace.addStep(Agent.ID_MEDIATOR, "Task terminated by mediator", 0);
                return;
            }

            // 2. 如果是顺序执行（不走 llm）
            if (config.getStrategy() == TeamStrategy.SEQUENTIAL) {
                List<String> agentNames = new ArrayList<>(config.getAgentMap().keySet());
                int nextIndex = trace.getIterationsCount();

                if (nextIndex < agentNames.size()) {
                    String nextAgent = agentNames.get(nextIndex);
                    selectAgent(trace, nextAgent);
                    trace.addStep(Agent.ID_MEDIATOR, "Sequential routing to: " + nextAgent, 0);
                } else {
                    trace.setRoute(Agent.ID_END);
                    trace.setFinalAnswer("Sequential task completed.");
                    trace.addStep(Agent.ID_MEDIATOR, "All sequential steps finished.", 0);
                }
                trace.nextIterations();
            } else {
                // 3. 准备协议补充信息
                StringBuilder protocolExt = new StringBuilder();
                prepareProtocolInfo(context, trace, protocolExt);

                // 4. 构建策略特定的上下文
                String strategyContextInfo = prepareStrategyContext(context, trace);

                // 5. 构建 Prompt 并调用模型
                String basePrompt = config.getSystemPrompt(prompt);
                String enhancedPrompt = basePrompt + protocolExt + strategyContextInfo;

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
                                    "\n\nCurrent iteration: " + trace.getIterationsCount() +
                                    "\nPlease decide the next action:")
                    )).call().getResultContent().trim();

                    if (LOG.isDebugEnabled()) {
                        LOG.debug("Mediator decision: {}", decision);
                    }
                } catch (Exception e) {
                    LOG.error("Failed to get decision from LLM", e);
                    decision = config.getFinishMarker(); // 出错时直接结束
                }

                // 6. 解析决策并设置路由
                parseAndRoute(trace, decision, context);

                // 7. 迭代计数
                trace.nextIterations();
            }

        } catch (Exception e) {
            LOG.error("Mediator task failed", e);
            String traceKey = context.getAs(Agent.KEY_CURRENT_TRACE_KEY);
            if (traceKey != null) {
                TeamTrace trace = context.getAs(traceKey);
                if (trace != null) {
                    trace.setRoute(Agent.ID_END);
                    trace.addStep(Agent.ID_MEDIATOR, "Mediator failed: " + e.getMessage(), 0);
                }
            }
        }
    }

    /**
     * 增强的终止条件检查
     */
    private boolean shouldTerminate(TeamTrace trace, FlowContext context) {
        // 超过最大迭代次数
        if (trace.getIterationsCount() >= config.getMaxTotalIterations()) {
            trace.addStep(Agent.ID_SYSTEM, "Maximum iterations reached (" +
                    config.getMaxTotalIterations() + ")", 0);
            return true;
        }

        // 检测死循环
        if (trace.isLooping()) {
            trace.addStep(Agent.ID_SYSTEM, "Loop detected in team collaboration", 0);
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
            sb.append("\nCurrent iteration cost: ").append(trace.getIterationsCount());
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
            trace.addStep(Agent.ID_MEDIATOR, "Empty decision received, terminating", 0);
            return;
        }

        String originalDecision = decision.trim();
        String upperDecision = originalDecision.toUpperCase();
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
                trace.addStep(Agent.ID_MEDIATOR, "Initiating bidding process", 0);
                return;
            }
        }

        // C. Agent 名称匹配 - 修复版本
        List<String> agentNames = new ArrayList<>(config.getAgentMap().keySet());

        // 策略1：精确匹配整个文本（最高优先级）
        for (String name : agentNames) {
            if (originalDecision.equalsIgnoreCase(name)) {
                selectAgent(trace, name);
                return;
            }
        }

        // 策略2：提取第一个"单词"进行匹配
        String firstPart = extractFirstMeaningfulPart(originalDecision);
        if (firstPart != null) {
            for (String name : agentNames) {
                if (firstPart.equalsIgnoreCase(name)) {
                    selectAgent(trace, name);
                    return;
                }
            }
        }

        // 策略3：改进的单词边界匹配
        // 按长度降序，长名优先
        List<String> sortedNames = agentNames.stream()
                .sorted((a, b) -> Integer.compare(b.length(), a.length()))
                .collect(Collectors.toList());

        // 清理文本：移除标点，统一空格
        String cleanText = originalDecision
                .replaceAll("[\\p{P}\\p{S}]", " ")  // 标点符号变空格
                .replaceAll("\\s+", " ")            // 多个空格变一个
                .toUpperCase()
                .trim();

        for (String name : sortedNames) {
            String upperName = name.toUpperCase();

            // 检查是否作为独立单词出现
            String[] words = cleanText.split(" ");
            for (String word : words) {
                if (word.equals(upperName)) {
                    selectAgent(trace, name);
                    return;
                }
            }

            // 正则单词边界匹配（考虑中英文边界）
            Pattern pattern = Pattern.compile(
                    "(^|\\s|[\\p{P}\\p{S}])" +
                            Pattern.quote(upperName) +
                            "($|\\s|[\\p{P}\\p{S}])",
                    Pattern.CASE_INSENSITIVE
            );

            if (pattern.matcher(" " + originalDecision + " ").find()) {
                selectAgent(trace, name);
                return;
            }
        }

        // D. 兜底方案
        trace.setRoute(Agent.ID_END);
        trace.addStep(Agent.ID_MEDIATOR, "No valid agent matched from decision: " + originalDecision, 0);
        LOG.warn("No agent matched from decision: {}", originalDecision);
    }

    /**
     * 提取第一个有意义的片段（可能是Agent名称）
     */
    private String extractFirstMeaningfulPart(String text) {
        if (text == null || text.trim().isEmpty()) {
            return null;
        }

        // 移除常见的前缀文本
        String[] prefixes = {
                "分析当前进度", "分析进度", "下一步行动", "下一步",
                "需要", "指派", "请", "应当", "建议"
        };

        String processed = text.trim();

        // 移除中文冒号、逗号后的内容
        processed = processed.replaceFirst("[:：]\\s*", " ");
        processed = processed.replaceFirst("[，,]\\s*", " ");

        // 按空格或标点分割，取第一部分
        String[] parts = processed.split("[\\s\\p{P}\\p{S}]+", 2);
        if (parts.length > 0) {
            String first = parts[0].trim();

            // 检查是否只是分析词汇
            for (String prefix : prefixes) {
                if (first.equalsIgnoreCase(prefix)) {
                    // 如果是分析词汇，尝试取第二部分
                    if (parts.length > 1) {
                        String[] subParts = parts[1].split("[\\s\\p{P}\\p{S}]+", 2);
                        if (subParts.length > 0) {
                            return subParts[0].trim();
                        }
                    }
                    return null;
                }
            }

            // 不是分析词汇，直接返回
            return first;
        }

        return null;
    }

    /**
     * 选择Agent并更新上下文
     */
    private void selectAgent(TeamTrace trace, String agentName) {
        trace.setRoute(agentName);
        updateStrategyContext(agentName);

        if (LOG.isDebugEnabled()) {
            LOG.debug("Selected agent: {}", agentName);
        }
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