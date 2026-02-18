/*
 * Copyright 2017-2025 noear.org and authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package org.noear.solon.ai.agent.team.task;

import org.noear.snack4.ONode;
import org.noear.solon.ai.agent.Agent;
import org.noear.solon.ai.agent.util.FeedbackTool;
import org.noear.solon.ai.agent.team.TeamAgent;
import org.noear.solon.ai.agent.team.TeamAgentConfig;
import org.noear.solon.ai.agent.team.TeamInterceptor;
import org.noear.solon.ai.agent.team.TeamTrace;
import org.noear.solon.ai.chat.ChatRequestDesc;
import org.noear.solon.ai.chat.ChatResponse;
import org.noear.solon.ai.chat.ChatRole;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.core.util.Assert;
import org.noear.solon.core.util.RankEntity;
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
 * 团队协作指挥任务 (Supervisor Task)
 *
 * <p>核心职责：作为协调中枢，基于 LLM 推理结果进行任务分发、成员调度及协作终止判定。</p>
 *
 * @author noear
 * @since 3.8.1
 */
@Preview("3.8.1")
public class SupervisorTask implements NamedTaskComponent {
    protected static final Logger LOG = LoggerFactory.getLogger(SupervisorTask.class);
    protected final TeamAgentConfig config;

    public SupervisorTask(TeamAgentConfig config) {
        this.config = config;
    }

    @Override
    public String name() {
        return TeamAgent.ID_SUPERVISOR;
    }

    @Override
    public void run(FlowContext context, Node node) throws Throwable {
        try {
            String traceKey = context.getAs(Agent.KEY_CURRENT_TEAM_TRACE_KEY);
            TeamTrace trace = context.getAs(traceKey);

            if (trace == null) {
                LOG.error("TeamAgent [{}] supervisor: Team trace not found", config.getName());
                return;
            }

            // 1. 拦截器准入检查
            for (RankEntity<TeamInterceptor> item : trace.getOptions().getInterceptors()) {
                if (!item.target.shouldSupervisorContinue(trace)) {
                    trace.addRecord(ChatRole.SYSTEM, TeamAgent.ID_SUPERVISOR, "[Skipped] Intercepted by " + item.target.getClass().getSimpleName(), 0);
                    if (TeamAgent.ID_SUPERVISOR.equals(trace.getRoute())) {
                        routeTo(context, trace, Agent.ID_END);
                    }
                    return;
                }
            }

            // 2. 协作熔断检查：达到最大迭代次数或已标记结束则退出
            if (Agent.ID_END.equals(trace.getRoute()) ||
                    trace.getTurnCount() >= trace.getOptions().getMaxTurns()) {
                trace.addRecord(ChatRole.SYSTEM, TeamAgent.ID_SYSTEM, "[Terminated] Max turns reached", 0);
                routeTo(context, trace, Agent.ID_END);
                return;
            }

            // 3. 协议逻辑检查：由特定协议决定是否继续执行 Supervisor
            if (!config.getProtocol().shouldSupervisorExecute(context, trace)) {
                if (TeamAgent.ID_SUPERVISOR.equals(trace.getRoute())) {
                    routeTo(context, trace, Agent.ID_END);
                }
                return;
            }

            dispatch(node, context, trace);

        } catch (Exception e) {
            handleError(context, e);
        }
    }

    /**
     * 构建 Prompt 并调用模型进行调度决策
     */
    protected void dispatch(Node node, FlowContext context, TeamTrace trace) throws Exception {
        boolean isZh = Locale.CHINA.getLanguage().equals(config.getLocale().getLanguage());

        // 组装系统提示词 (基础模版 + 协议扩展)
        StringBuilder protocolExt = new StringBuilder();
        config.getProtocol().prepareSupervisorInstruction(context, trace, protocolExt);
        String basePrompt = config.getSystemPromptFor(trace, context);
        String finalSystemPrompt = (protocolExt.length() > 0) ? basePrompt + "\n\n" + protocolExt : basePrompt;

        // 组装用户输入 (包含协作历史、当前轮次、候选成员)
        StringBuilder userContent = new StringBuilder();
        config.getProtocol().prepareSupervisorContext(context, trace, userContent);
        int windowSize = trace.getOptions().getRecordWindowSize();

        if (isZh) {
            userContent.append("## 协作进度 (最近 ").append(windowSize).append(" 轮历史)\n").append(trace.getFormattedHistory(windowSize)).append("\n\n");
            userContent.append("---\n");
            userContent.append("当前迭代轮次: ").append(trace.nextTurn()).append("\n");
            userContent.append("指令：请指派下一位执行者。已完成则输出 ").append(config.getFinishMarker());
        } else {
            userContent.append("## Collaboration Progress (Last ").append(windowSize).append(" rounds)\n").append(trace.getFormattedHistory(windowSize)).append("\n\n");
            userContent.append("---\n");
            userContent.append("Current Iteration: ").append(trace.nextTurn()).append("\n");
            userContent.append("Command: Assign next agent or output ").append(config.getFinishMarker()).append(" to finish.");
        }

        // 过滤已参与成员，提示待命专家
        Set<String> participatedAgentNames = trace.getRecords().stream()
                .filter(TeamTrace.TeamRecord::isAgent)
                .map(s -> s.getSource().toLowerCase())
                .collect(Collectors.toSet());

        List<String> remainingAgents = config.getAgentMap().keySet().stream()
                .filter(name -> !participatedAgentNames.contains(name.toLowerCase()))
                .collect(Collectors.toList());

        if (!remainingAgents.isEmpty()) {
            String agentsList = String.join(", ", remainingAgents);
            userContent.append(isZh ? "\n> **待命专家**：[" : "\n> **Pending Experts**: [").append(agentsList).append("].");
        }

        if (LOG.isTraceEnabled()) {
            LOG.trace("TeamAgent SystemPrompt rendered for agent [{}]:\n{}", trace.getAgentName(), finalSystemPrompt);
        }

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.ofSystem(finalSystemPrompt));
        messages.addAll(trace.getWorkingMemory().getMessages());
        messages.add(ChatMessage.ofUser(userContent.toString()));


        ChatResponse response = callWithRetry(node, trace, messages);
        if (trace.isPending()) {
            return;
        }

        final AssistantMessage responseMessage;
        if (response.isStream()) {
            responseMessage = response.getAggregationMessage();
        } else {
            responseMessage = response.getMessage();
        }

        if (response.getUsage() != null) {
            trace.getMetrics().addUsage(response.getUsage());
        }

        for (RankEntity<TeamInterceptor> item : trace.getOptions().getInterceptors()) {
            item.target.onModelEnd(trace, response);
        }
        if (trace.isPending()) {
            return;
        }

        String clearContent = responseMessage.hasContent() ? responseMessage.getResultContent() : "";
        String decision = clearContent.trim();
        trace.setLastDecision(decision);

        for (RankEntity<TeamInterceptor> item : trace.getOptions().getInterceptors()) {
            item.target.onSupervisorDecision(trace, decision);
        }
        if (trace.isPending()) {
            return;
        }

        commitRoute(trace, decision, context);
    }

    /**
     * 将决策文本解析为物理路由目标
     */
    protected void commitRoute(TeamTrace trace, String decision, FlowContext context) {
        if (FeedbackTool.isSuspend(decision)) {
            ONode oNode = ONode.ofJson(decision);
            String reason = oNode.get("reason").getString();
            trace.setFinalAnswer(reason);
            trace.setRoute(Agent.ID_END);
            trace.getContext().interrupt();
            return;
        }

        if (Assert.isEmpty(decision)) {
            routeTo(context, trace, Agent.ID_END);
            return;
        }

        // 1. 优先尝试协议自定义路由解析
        String protoRoute = config.getProtocol().resolveSupervisorRoute(context, trace, decision);
        if (Assert.isNotEmpty(protoRoute)) {
            routeTo(context, trace, protoRoute);
            return;
        }

        // 2. 检查结束标记并提取最终答案
        String finishMarker = config.getFinishMarker();
        if (decision.contains(finishMarker)) {
            if (config.getProtocol().shouldSupervisorRoute(context, trace, decision)) {
                String finishRegex = "(?i).*?\\Q" + finishMarker + "\\E[:\\s]*(.*)";
                Pattern pattern = Pattern.compile(finishRegex, Pattern.DOTALL);
                Matcher matcher = pattern.matcher(decision);

                if (matcher.find()) {
                    String finalAnswer = matcher.group(1).trim();
                    trace.setFinalAnswer(finalAnswer.isEmpty() ? trace.getLastAgentContent() : finalAnswer);
                } else {
                    trace.setFinalAnswer(trace.getLastAgentContent());
                }
                routeTo(context, trace, Agent.ID_END);
                return;
            }
        }

        // 3. 匹配成员 Agent 路由
        if (matchAgentRoute(context, trace, decision)) {
            return;
        }

        if (LOG.isDebugEnabled()) {
            LOG.debug("TeamAgent [{}] unable to extract agent name from LLM response: [{}]", config.getName(), decision);
        }
        routeTo(context, trace, Agent.ID_END);
    }

    /**
     * 模糊匹配文本中的 Agent 名称
     */
    protected boolean matchAgentRoute(FlowContext context, TeamTrace trace, String text) {
        // 移除 Markdown 格式字符（加粗/斜体的 * 和代码的 `），保留下划线，避免agent 名称的合法字符被误删，如agent1_extractor
        String cleanText = text.replaceAll("[\\*\\`]", "").trim();

        if (config.getAgentMap().containsKey(cleanText)) {
            routeTo(context, trace, cleanText);
            return true;
        }

        List<String> agentNames = new ArrayList<>(config.getAgentMap().keySet());
        String lastFoundAgent = null;
        int lastIndex = -1;

        for (String name : agentNames) {
            Pattern p = Pattern.compile("(?i)(?<=^|[^a-zA-Z0-9])" + Pattern.quote(name) + "(?=[^a-zA-Z0-9]|$)");
            Matcher m = p.matcher(cleanText);
            while (m.find()) {
                if (m.start() > lastIndex) {
                    lastIndex = m.start();
                    lastFoundAgent = name;
                }
            }
        }

        if (lastFoundAgent != null) {
            routeTo(context, trace, lastFoundAgent);
            return true;
        }

        return false;
    }

    /**
     * 带重试机制的模型调用
     */
    protected ChatResponse callWithRetry(Node node, TeamTrace trace, List<ChatMessage> messages) {
        ChatRequestDesc req = config.getChatModel().prompt(messages).options(o -> {
            if(trace.getOptions().isFeedbackMode()) {
                o.toolAdd(FeedbackTool.getTool(
                        trace.getOptions().getFeedbackDescription(trace),
                        trace.getOptions().getFeedbackReasonDescription(trace)));
            }

            o.toolAdd(trace.getOptions().getTools());
            config.getProtocol().injectSupervisorTools(trace.getContext(), o::toolAdd);

            o.toolContextPut(trace.getOptions().getToolContext());
            trace.getOptions().getInterceptors().forEach(item -> o.interceptorAdd(item.target));

            o.optionSet(trace.getOptions().getModelOptions().options());
        });

        for(RankEntity<TeamInterceptor> item: trace.getOptions().getInterceptors()){
            item.target.onModelStart(trace, req);
        }
        if(trace.isPending()){
            return null;
        }


        int maxRetries = trace.getOptions().getMaxRetries();
        for (int i = 0; i < maxRetries; i++) {
            try {
                if(trace.getOptions().getStreamSink() == null) {
                    return req.call();
                } else {
                    return req.stream()
                            .doOnNext(resp -> {
                                trace.getOptions().getStreamSink().next(
                                        new SupervisorChunk(node, trace, resp));
                            })
                            .blockLast();
                }
            } catch (Exception e) {
                if (i == maxRetries - 1) throw new RuntimeException("Supervisor call failed", e);
                LOG.warn("Supervisor call failed, retrying ({}/{})...", i + 1, maxRetries);
                try {
                    Thread.sleep(trace.getOptions().getRetryDelayMs() * (i + 1));
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(ie);
                }
            }
        }
        throw new RuntimeException("Unreachable");
    }

    protected void routeTo(FlowContext context, TeamTrace trace, String targetName) {
        trace.setRoute(targetName);
        config.getProtocol().onSupervisorRouting(context, trace, targetName);
        if (LOG.isDebugEnabled()) {
            LOG.debug("TeamAgent [{}] supervisor decision: [{}]", config.getName(), targetName);
        }
    }

    protected void handleError(FlowContext context, Exception e) {
        LOG.error("TeamAgent [{}] supervisor fatal error", config.getName(), e);
        String traceKey = context.getAs(Agent.KEY_CURRENT_TEAM_TRACE_KEY);
        TeamTrace trace = context.getAs(traceKey);

        if (trace != null) {
            trace.setRoute(Agent.ID_END);
            trace.addRecord(ChatRole.SYSTEM, TeamAgent.ID_SUPERVISOR, "Runtime Error: " + e.getMessage(), 0);
        }
    }
}