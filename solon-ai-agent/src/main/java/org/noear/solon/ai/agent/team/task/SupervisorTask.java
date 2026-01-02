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
import org.noear.solon.core.util.Assert;
import org.noear.solon.flow.FlowContext;
import org.noear.solon.flow.Node;
import org.noear.solon.flow.TaskComponent;
import org.noear.solon.lang.Preview;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 团队协作调解任务（决策中心）
 * 优化点：路由优先级调整与信号穿透隔离
 *
 * @author noear
 * @since 3.8.1
 */
@Preview("3.8")
public class SupervisorTask implements TaskComponent {
    private static final Logger LOG = LoggerFactory.getLogger(SupervisorTask.class);
    private final TeamConfig config;

    public SupervisorTask(TeamConfig config) {
        this.config = config;
    }

    @Override
    public void run(FlowContext context, Node node) throws Throwable {
        try {
            Prompt prompt = context.getAs(Agent.KEY_PROMPT);
            String traceKey = context.getAs(Agent.KEY_CURRENT_TRACE_KEY);
            TeamTrace trace = context.getAs(traceKey);

            if (trace == null) {
                LOG.error("TeamAgent [{}] supervisor: Team trace not found", config.getName());
                return;
            }

            // 完全由路由状态驱动。不再扫描历史步骤内容，避免子团队的 finishMarker 干扰父团队判定
            if (Agent.ID_END.equals(trace.getRoute()) ||
                    trace.getIterationsCount() >= config.getMaxTotalIterations()) {
                trace.setRoute(Agent.ID_END);
                return;
            }

            // 执行策略
            if (config.getStrategy() == TeamStrategy.SEQUENTIAL) {
                runSequential(context, trace, prompt);
            } else {
                runIntelligent(context, trace, prompt);
            }

        } catch (Exception e) {
            handleError(context, e);
        }
    }

    private void runIntelligent(FlowContext context, TeamTrace trace, Prompt prompt) throws Exception {
        StringBuilder protocolExt = new StringBuilder();
        prepareProtocolInfo(context, trace, protocolExt);
        String strategyContextInfo = prepareStrategyContext(context, trace);

        String basePrompt = config.getSystemPrompt(prompt);
        String enhancedPrompt = basePrompt + protocolExt + strategyContextInfo;

        String decision = config.getChatModel().prompt(Arrays.asList(
                ChatMessage.ofSystem(enhancedPrompt),
                ChatMessage.ofUser("Collaboration History:\n" + trace.getFormattedHistory() +
                        "\n\nCurrent iteration: " + trace.getIterationsCount() +
                        "\nPlease decide the next action:")
        )).call().getResultContent().trim();

        if (LOG.isDebugEnabled()) {
            LOG.debug("TeamAgent [{}] supervisor decision: {}", config.getName(), decision);
        }

        parseAndRoute(trace, decision, context);
        trace.nextIterations();
    }

    /**
     * 解析并路由
     */
    private void parseAndRoute(TeamTrace trace, String decision, FlowContext context) {
        if (Assert.isEmpty(decision)) {
            trace.setRoute(Agent.ID_END);
            return;
        }

        // 策略相关的特殊信号处理（如招标）
        if (config.getStrategy() == TeamStrategy.CONTRACT_NET) {
            if (decision.toUpperCase().contains(Agent.ID_BIDDING.toUpperCase())) {
                trace.setRoute(Agent.ID_BIDDING);
                return;
            }
        }

        // 优先匹配 Agent 名字。也优先进行路由派发
        if (matchAgentRoute(trace, decision)) {
            return;
        }

        // 再使用正则提取 Marker 之后的所有内容作为最终答案
        String finishMarker = config.getFinishMarker();
        String finishRegex = "(?i).*?(\\Q" + finishMarker + "\\E)(.*)";
        Pattern pattern = Pattern.compile(finishRegex, Pattern.DOTALL);
        Matcher matcher = pattern.matcher(decision);

        if (matcher.find()) {
            trace.setRoute(Agent.ID_END);
            String finalAnswer = matcher.group(2).trim();
            if (!finalAnswer.isEmpty()) {
                trace.setFinalAnswer(finalAnswer);
            } else {
                trace.setFinalAnswer("Task completed by " + config.getName());
            }
            return;
        }

        // 兜底处理。无法识别目标时终止，防止死循环
        trace.setRoute(Agent.ID_END);
        LOG.warn("TeamAgent [{}] supervisor could not resolve next agent: {}", config.getName(), decision);
    }

    /**
     * 匹配智能体并路由
     */
    private boolean matchAgentRoute(TeamTrace trace, String text) {
        // 1.直接查找名字
        Agent agent = config.getAgentMap().get(text);
        if (agent != null) {
            trace.setRoute(agent.name());
            updateStrategyContext(trace, agent.name());
            if (LOG.isDebugEnabled()) {
                LOG.debug("TeamAgent [{}] supervisor routed to agent: [{}]", config.getName(), agent.name());
            }
            return true;
        }

        // 2.正则匹配（先短再长）
        List<String> sortedNames = config.getAgentMap().keySet().stream()
                .sorted((a, b) -> b.length() - a.length())
                .collect(Collectors.toList());

        for (String name : sortedNames) {
            // 使用单词边界匹配，提高在复杂句子中的提取精度
            Pattern p = Pattern.compile("\\b" + Pattern.quote(name) + "\\b", Pattern.CASE_INSENSITIVE);
            if (p.matcher(text).find()) {
                trace.setRoute(name);
                updateStrategyContext(trace, name);
                if (LOG.isDebugEnabled()) {
                    LOG.debug("TeamAgent [{}] supervisor routed to agent: [{}]", config.getName(), name);
                }
                return true;
            }
        }
        return false;
    }

    private void runSequential(FlowContext context, TeamTrace trace, Prompt prompt) {
        List<String> agentNames = new ArrayList<>(config.getAgentMap().keySet());
        int nextIndex = trace.getIterationsCount();

        if (nextIndex < agentNames.size()) {
            String nextAgent = agentNames.get(nextIndex);
            trace.setRoute(nextAgent);
            updateStrategyContext(trace, nextAgent);
        } else {
            trace.setRoute(Agent.ID_END);
            trace.setFinalAnswer("Sequential task completed.");
        }
        trace.nextIterations();
    }

    private void updateStrategyContext(TeamTrace trace, String agentName) {
        if (config.getStrategy() == TeamStrategy.SWARM) {
            Map<String, Integer> usage = (Map<String, Integer>) trace.getStrategyContext().computeIfAbsent("agent_usage", k -> new HashMap<>());
            usage.put(agentName, usage.getOrDefault(agentName, 0) + 1);
        }
    }

    private void prepareProtocolInfo(FlowContext context, TeamTrace trace, StringBuilder sb) {
        if (config.getStrategy() == TeamStrategy.CONTRACT_NET) {
            String bids = context.getAs("active_bids");
            if (bids != null) {
                sb.append("\n=== Bids Context ===\n").append(bids);
                context.remove("active_bids");
            }
        }
    }

    private String prepareStrategyContext(FlowContext context, TeamTrace trace) {
        if (config.getStrategy() == TeamStrategy.HIERARCHICAL) {
            return "\n=== Hierarchical Context ===\nTotal agents available: " + config.getAgentMap().size();
        }
        return "";
    }

    private void handleError(FlowContext context, Exception e) {
        LOG.error("TeamAgent [{}] supervisor task failed", config.getName(), e);

        String traceKey = context.getAs(Agent.KEY_CURRENT_TRACE_KEY);
        TeamTrace trace = context.getAs(traceKey);
        if (trace != null) {
            trace.setRoute(Agent.ID_END);
            trace.addStep(Agent.ID_SUPERVISOR, "Error: " + e.getMessage(), 0);
        }
    }
}