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
import org.noear.solon.ai.agent.team.TeamTrace;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.core.util.Assert;
import org.noear.solon.flow.FlowContext;
import org.noear.solon.flow.NamedTaskComponent;
import org.noear.solon.flow.Node;
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
public class SupervisorTask implements NamedTaskComponent {
    private static final Logger LOG = LoggerFactory.getLogger(SupervisorTask.class);
    private final TeamConfig config;

    public SupervisorTask(TeamConfig config) {
        this.config = config;
    }

    @Override
    public String name() {
        return Agent.ID_SUPERVISOR;
    }

    @Override
    public void run(FlowContext context, Node node) throws Throwable {
        if (LOG.isDebugEnabled()) {
            LOG.debug("TeamAgent [{}] supervisor starting...", config.getName());
        }

        try {
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

            if (config.getProtocol().interceptExecute(context, trace)) {
                return;
            }

            runIntelligent(context, trace);

        } catch (Exception e) {
            handleError(context, e);
        }
    }

    private void runIntelligent(FlowContext context, TeamTrace trace) throws Exception {
        StringBuilder protocolExt = new StringBuilder();
        config.getProtocol().prepareInstruction(context, trace, protocolExt);

        String basePrompt = config.getSystemPrompt(trace);
        String enhancedPrompt = basePrompt + protocolExt;

        List<ChatMessage> messages = Arrays.asList(
                ChatMessage.ofSystem(enhancedPrompt),
                ChatMessage.ofUser("Collaboration History:\n" + trace.getFormattedHistory() +
                        "\n\nCurrent iteration: " + trace.getIterationsCount() +
                        "\nPlease decide the next action:")
        );

        String decision = callWithRetry(trace, messages);

        if (decision.contains("Collaboration History:")) {
            decision = decision.split("Collaboration History:")[0].trim();
        }

        if (LOG.isDebugEnabled()) {
            LOG.debug("TeamAgent [{}] supervisor decision: {}", config.getName(), decision);
        }

        parseAndRoute(trace, decision, context);
        trace.nextIterations();
    }

    private String callWithRetry(TeamTrace trace, List<ChatMessage> messages) {
        int maxRetries = config.getMaxRetries();
        for (int i = 0; i < maxRetries; i++) {
            try {
                return config.getChatModel().prompt(messages).options(o -> {
                    if (config.getSupervisorOptions() != null) {
                        config.getSupervisorOptions().accept(o);
                    }
                }).call().getResultContent().trim();
            } catch (Exception e) {
                if (i == maxRetries - 1) {
                    LOG.error("TeamAgent [{}] supervisor failed after {} retries", config.getName(), maxRetries, e);
                    throw new RuntimeException("Failed after " + maxRetries + " retries", e);
                }

                if (LOG.isDebugEnabled()) {
                    LOG.debug("TeamAgent [{}] supervisor call failed (retry: {}): {}", config.getName(), i, e.getMessage());
                }

                try {
                    Thread.sleep(config.getRetryDelayMs() * (i + 1));
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Interrupted during retry", ie);
                }
            }
        }

        throw new RuntimeException("Should not reach here");
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
        if (config.getProtocol().interceptRouting(context, trace, decision)) { //getStrategy() == TeamStrategy.CONTRACT_NET
            return;
        }

        // 优先匹配 Agent 名字。也优先进行路由派发
        if (matchAgentRoute(context, trace, decision)) {
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
    private boolean matchAgentRoute(FlowContext context, TeamTrace trace, String text) {
        // 1.直接查找名字
        Agent agent = config.getAgentMap().get(text);
        if (agent != null) {
            trace.setRoute(agent.name());
            config.getProtocol().onRouting(context, trace, agent.name());
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
                config.getProtocol().onRouting(context, trace, name);
                if (LOG.isDebugEnabled()) {
                    LOG.debug("TeamAgent [{}] supervisor routed to agent: [{}]", config.getName(), name);
                }
                return true;
            }
        }
        return false;
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