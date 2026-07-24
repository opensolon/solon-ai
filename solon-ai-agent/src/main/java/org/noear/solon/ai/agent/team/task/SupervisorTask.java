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
import org.noear.solon.ai.agent.AgentChunk;
import org.noear.solon.ai.agent.exception.LlmNoReturnException;
import org.noear.solon.ai.agent.util.FeedbackTool;
import org.noear.solon.ai.agent.team.TeamAgent;
import org.noear.solon.ai.agent.team.TeamAgentConfig;
import org.noear.solon.ai.agent.team.TeamInterceptor;
import org.noear.solon.ai.agent.team.TeamTrace;
import org.noear.solon.ai.chat.ChatRequestDesc;
import org.noear.solon.ai.chat.ChatResponse;
import org.noear.solon.ai.chat.ModelOptionsAmend;
import org.noear.solon.ai.chat.ChatRole;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.util.RetryTask;
import org.noear.solon.core.util.Assert;
import org.noear.solon.core.util.RankEntity;
import org.noear.solon.flow.FlowContext;
import org.noear.solon.flow.NamedTaskComponent;
import org.noear.solon.flow.Node;
import org.noear.solon.lang.Preview;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.FluxSink;

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
                if (item.target.isEnabled()) {
                    if (!item.target.shouldSupervisorContinue(trace)) {
                        trace.addRecord(ChatRole.SYSTEM, TeamAgent.ID_SUPERVISOR, "[Skipped] Intercepted by " + item.target.getClass().getSimpleName(), 0);
                        if (TeamAgent.ID_SUPERVISOR.equals(trace.getRoute())) {
                            routeTo(context, trace, Agent.ID_END);
                        }
                        return;
                    }
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

        } catch (Throwable e) {
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

        if (LOG.isDebugEnabled()) {
            LOG.debug("TeamAgent SystemPrompt rendered for agent [{}]:\n{}", trace.getAgentName(), finalSystemPrompt);
        }

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.ofSystem(finalSystemPrompt));
        messages.addAll(trace.getWorkingMemory().getMessages());
        messages.add(ChatMessage.ofUser(userContent.toString()));


        ChatResponse response = callWithRetry(node, trace, messages);
        if (response == null || trace.getSession().isPending()) {
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
            if (item.target.isEnabled()) {
                item.target.onModelEnd(trace, response);
            }
        }

        if (trace.getSession().isPending()) {
            return;
        }

        // 优先从正文提取决策；若模型走 feedback 工具，仅在正文存在「显式指派成员名」时抢路由
        String clearContent = responseMessage.hasContent() ? responseMessage.getResultContent() : "";
        String decision = clearContent == null ? "" : clearContent.trim();

        if (Assert.isNotEmpty(responseMessage.getToolCalls())) {
            for (org.noear.solon.ai.chat.tool.ToolCall call : responseMessage.getToolCalls()) {
                if (FeedbackTool.TOOL_NAME.equals(call.getName())) {
                    Object reasonObj = call.getArguments() == null ? null : call.getArguments().get("reason");
                    String reason = reasonObj == null ? "" : String.valueOf(reasonObj);
                    
                    // 模型常一边写「指派/派 天气专家」一边误调 feedback。
                    // 正文或 reason 中若存在显式指派句式，优先继续协作。
                    String assigned = extractAssignedAgentName(decision);
                    if (Assert.isEmpty(assigned)) {
                        assigned = extractAssignedAgentName(reason);
                    }
                    if (Assert.isNotEmpty(assigned)) {
                        routeTo(context, trace, assigned);
                        if (LOG.isDebugEnabled()) {
                            LOG.debug("TeamAgent [{}] feedback tool co-occurs with explicit agent assign [{}]; prefer routing",
                                    config.getName(), assigned);
                        }
                        return;
                    }
                    
                    // 真正需要外部反馈：挂起
                    decision = FeedbackTool.asSuspendDecision(reason);
                    break;
                }
            }
        }
    
        trace.setLastDecision(decision);
    
        for (RankEntity<TeamInterceptor> item : trace.getOptions().getInterceptors()) {
            if (item.target.isEnabled()) {
                item.target.onSupervisorDecision(trace, decision);
            }
        }
    
        if (trace.getSession().isPending()) {
            return;
        }
    
        commitRoute(trace, decision, context);
    }

    /**
     * 将决策文本解析为物理路由目标。
     *
     * <p>状态机收口：</p>
     * <ul>
     *   <li>FINISH 且 {@code shouldSupervisorRoute=true} → END</li>
     *   <li>FINISH 但被协议拒绝 → 禁止 fall-through 误入 END；尝试回派成员继续协作</li>
     *   <li>无法解析的 decision → 不静默 END；优先回派可执行成员以重新进入循环</li>
     * </ul>
     */
    protected void commitRoute(TeamTrace trace, String decision, FlowContext context) {
        if (FeedbackTool.isSuspend(decision)) {
            ONode oNode = ONode.ofJson(decision);
            String reason = oNode.get("reason").getString();
            
            // 仅当 reason 是「显式指派成员名」时才抢路由；
            // 普通「请用户补充目的地」里顺带提到专家名不算路由。
            String assigned = extractAssignedAgentName(Assert.isEmpty(reason) ? decision : reason);
            if (Assert.isNotEmpty(assigned)) {
                routeTo(context, trace, assigned);
                if (LOG.isDebugEnabled()) {
                    LOG.debug("TeamAgent [{}] feedback payload is explicit assign to [{}]", config.getName(), assigned);
                }
                return;
            }
            
            // 真正需要外部反馈：挂起流程并记录原因
            trace.setFinalAnswer(reason);
            if (trace.getSession() != null) {
                trace.getSession().pending(true, reason);
            }
            trace.setRoute(Agent.ID_END);
            if (trace.getContext() != null) {
                trace.getContext().interrupt();
            }
            return;
        }

        if (Assert.isEmpty(decision)) {
            handleUnresolvableDecision(context, trace, decision, "empty supervisor decision");
            return;
        }

        // 1. 优先尝试协议自定义路由解析（可含 FINISH 阻断时的硬回派）
        String protoRoute = config.getProtocol().resolveSupervisorRoute(context, trace, decision);
        if (Assert.isNotEmpty(protoRoute)) {
            routeTo(context, trace, protoRoute);
            return;
        }

        // 2. FINISH 分支：允许结束则 END；拒绝则必须 return，禁止 fall-through
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

            // FINISH 被协议拒绝：不得误入 END
            if (matchAgentRoute(context, trace, decision)) {
                return;
            }
            String fallback = resolveContinueFallback(trace);
            if (Assert.isNotEmpty(fallback)) {
                trace.addRecord(ChatRole.SYSTEM, TeamAgent.ID_SYSTEM,
                        "[Routing] FINISH rejected by protocol; re-assign to [" + fallback + "]",
                        0);
                routeTo(context, trace, fallback);
                return;
            }

            // 极端：无可回派成员（空团队）才允许 END，且必须留下原因
            LOG.warn("TeamAgent [{}] FINISH rejected but no fallback agent available", config.getName());
            if (Assert.isEmpty(trace.getFinalAnswer())) {
                trace.setFinalAnswer(trace.getLastAgentContent());
            }
            trace.addRecord(ChatRole.SYSTEM, TeamAgent.ID_SYSTEM,
                    "[Terminated] FINISH rejected and no agent available to continue", 0);
            routeTo(context, trace, Agent.ID_END);
            return;
        }

        // 3. 匹配成员 Agent 路由
        if (matchAgentRoute(context, trace, decision)) {
            return;
        }

        // 4. 无法解析：禁止静默 END
        handleUnresolvableDecision(context, trace, decision, "unable to extract agent name");
    }

    /**
     * 解析失败/空决策时的统一处置：优先回派可执行成员以重新进入协作循环，
     * 避免 exclusive 默认边误入 END。仅在确实无法继续时带原因结束。
     */
    protected void handleUnresolvableDecision(FlowContext context, TeamTrace trace, String decision, String reason) {
        if (LOG.isDebugEnabled()) {
            LOG.debug("TeamAgent [{}] {}: [{}]", config.getName(), reason, decision);
        }

        String fallback = resolveContinueFallback(trace);
        if (Assert.isNotEmpty(fallback)
                && trace.getTurnCount() < trace.getOptions().getMaxTurns()) {
            trace.addRecord(ChatRole.SYSTEM, TeamAgent.ID_SYSTEM,
                    "[Routing] " + reason + "; retry via [" + fallback + "]",
                    0);
            routeTo(context, trace, fallback);
            return;
        }

        trace.addRecord(ChatRole.SYSTEM, TeamAgent.ID_SYSTEM,
                "[Terminated] " + reason + (decision == null ? "" : ": " + truncate(decision, 200)),
                0);
        if (Assert.isEmpty(trace.getFinalAnswer())) {
            String last = trace.getLastAgentContent();
            trace.setFinalAnswer(Assert.isEmpty(last) ? ("Supervisor decision unresolved: " + reason) : last);
        }
        routeTo(context, trace, Agent.ID_END);
    }

    /**
     * FINISH 被拒/解析失败时的继续执行回退：
     * 上一位合法专家 → 队伍中第一位专家。
     * 用于在 Supervisor 无自环边时，仍能通过 Agent→Supervisor 边回到决策循环。
     */
    protected String resolveContinueFallback(TeamTrace trace) {
        String last = trace.getLastAgentName();
        if (Assert.isNotEmpty(last) && config.getAgentMap().containsKey(last)) {
            return last;
        }
        if (!config.getAgentMap().isEmpty()) {
            return config.getAgentMap().keySet().iterator().next();
        }
        return null;
    }

    private static String truncate(String text, int max) {
        if (text == null) {
            return "";
        }
        String t = text.trim();
        return t.length() <= max ? t : t.substring(0, max) + "...";
    }

    /**
     * 模糊匹配文本中的 Agent 名称。
     *
     * <p>优先级：</p>
     * <ol>
     *   <li>全文恰为成员名</li>
     *   <li>显式指派句式中的成员名（指派/派发/next/route to ...）</li>
     *   <li>正文中最后出现的成员名（仅 agent name，不做宽泛角色匹配）</li>
     * </ol>
     * <p>注意：不在长文中按角色名（如「天气专家」）做 lastIndexOf 匹配——
     * 主管决策/feedback 文案常复述成员角色，会导致误路由。</p>
     */
    protected boolean matchAgentRoute(FlowContext context, TeamTrace trace, String text) {
        if (Assert.isEmpty(text)) {
            return false;
        }
        
        // 移除 Markdown 格式字符（加粗/斜体的 * 和代码的 `），保留下划线
        String cleanText = text.replaceAll("[\\*\\`]", "").trim();
        if (Assert.isEmpty(cleanText)) {
            return false;
        }
                    
        // 1. 全文恰为成员名
        if (config.getAgentMap().containsKey(cleanText)) {
            routeTo(context, trace, normalizeAgentName(cleanText));
            return true;
        }
            
        // 2. 显式指派句式中的成员名
        String assigned = extractAssignedAgentName(cleanText);
        if (Assert.isNotEmpty(assigned)) {
            routeTo(context, trace, assigned);
            return true;
        }
        
        // 3. 成员名（agent name）最后一次出现
        String byName = findLastAgentName(cleanText);
        if (Assert.isNotEmpty(byName)) {
            routeTo(context, trace, byName);
            return true;
        }
        
        return false;
    }
        
    /**
     * 从「指派/派发/派/next/route」类句式中提取目标 Agent 的规范名。
     * <p>支持成员 name 以及角色 role（仅在显式指派句式中），避免长文复述误命中。</p>
     */
    protected String extractAssignedAgentName(String text) {
        if (Assert.isEmpty(text)) {
            return null;
        }
        String cleanText = text.replaceAll("[\\*\\`]", "").trim();

        // 1) agent name：指派动词 + name（避免中文角色后吞掉整句）
        String assignedByName = null;
        int assignedByNameIdx = -1;
        for (String name : config.getAgentMap().keySet()) {
            Pattern nameAssign = Pattern.compile(
                    "(?is)(?:指派(?:专家)?|派发|先派|再派|派|next(?:\\s+agent)?|route(?:\\s+to)?)\\s*[:：]?\\s*"
                            + Pattern.quote(name) + "(?=[^a-zA-Z0-9_]|$)");
            Matcher nm = nameAssign.matcher(cleanText);
            while (nm.find()) {
                if (nm.start() > assignedByNameIdx) {
                    assignedByNameIdx = nm.start();
                    assignedByName = name;
                }
            }
        }
        if (Assert.isNotEmpty(assignedByName)) {
            return normalizeAgentName(assignedByName);
        }

        // 2) role：指派动词 + role（仅在显式指派句式中）
        String assignedByRole = null;
        int assignedByRoleIdx = -1;
        for (Map.Entry<String, Agent> entry : config.getAgentMap().entrySet()) {
            String role = entry.getValue().role();
            if (Assert.isEmpty(role) || role.length() < 2) {
                continue;
            }
            Pattern roleAssign = Pattern.compile(
                    "(?is)(?:指派(?:专家)?|派发|先派|再派|派|next(?:\\s+agent)?|route(?:\\s+to)?)\\s*[:：]?\\s*"
                            + Pattern.quote(role));
            Matcher rm = roleAssign.matcher(cleanText);
            while (rm.find()) {
                if (rm.start() > assignedByRoleIdx) {
                    assignedByRoleIdx = rm.start();
                    assignedByRole = entry.getKey();
                }
            }
        }
        return assignedByRole;
    }

    /**
     * 将角色称呼解析为 agentMap 规范名（仅用于显式指派上下文）。
     */
    protected String resolveAgentByRoleToken(String token) {
        if (Assert.isEmpty(token) || token.length() < 2) {
            return null;
        }
        String exact = null;
        String partial = null;
        for (Map.Entry<String, Agent> entry : config.getAgentMap().entrySet()) {
            String role = entry.getValue().role();
            if (Assert.isEmpty(role)) {
                continue;
            }
            if (role.equals(token)) {
                exact = entry.getKey();
                break;
            }
            if (role.contains(token) || token.contains(role)) {
                partial = entry.getKey();
            }
        }
        return exact != null ? exact : partial;
    }
    
    protected String findLastAgentName(String cleanText) {
        if (Assert.isEmpty(cleanText)) {
            return null;
        }
        String lastFoundAgent = null;
        int lastIndex = -1;
    
        for (String name : config.getAgentMap().keySet()) {
            Pattern p = Pattern.compile("(?i)(?<=^|[^a-zA-Z0-9_])" + Pattern.quote(name) + "(?=[^a-zA-Z0-9_]|$)");
            Matcher matcher = p.matcher(cleanText);
            while (matcher.find()) {
                if (matcher.start() > lastIndex) {
                    lastIndex = matcher.start();
                    lastFoundAgent = name;
                }
            }
        }
        return lastFoundAgent;
    }
    
    protected String normalizeAgentName(String name) {
        // IgnoreCaseMap 下返回规范 key
        for (String key : config.getAgentMap().keySet()) {
            if (key.equalsIgnoreCase(name)) {
                return key;
            }
        }
        return name;
    }

    /**
     * 带重试机制的模型调用
     */
    protected ChatResponse callWithRetry(Node node, TeamTrace trace, List<ChatMessage> messages) throws InterruptedException {
        ChatRequestDesc req = config.getChatModel().prompt(messages).options(o -> {
            o.agentName(trace.getAgentName());

            // Supervisor 自行解析 tool_calls（尤其是 feedback），避免 returnDirect 抹掉正文中的路由意图
            o.autoToolCall(false);
                        
            if (trace.getOptions().isFeedbackMode()) {
                o.toolAdd(FeedbackTool.getTool(
                        trace.getOptions().getFeedbackDescription(trace),
                        trace.getOptions().getFeedbackReasonDescription(trace)));
            }

            o.toolAdd(trace.getOptions().getTools());
            config.getProtocol().injectSupervisorTools(trace.getContext(), o::toolAdd);
            
            o.toolContextPut(trace.getOptions().getToolContext());
                
            for (RankEntity<TeamInterceptor> item : trace.getOptions().getInterceptors()) {
                //内部已支持启用控制
                o.interceptorAdd(item.index, item.target);
            }
            
            o.optionSet(trace.getOptions().getModelOptions().options());
            // 覆盖 optionSet 可能带回的 autoToolCall=true
            o.autoToolCall(false);
            
            // 从 Agent 级选项复制缓存控制配置
            ModelOptionsAmend<?, ?> agentOptions = trace.getOptions().getModelOptions();
            if (agentOptions.cacheControl() != null) {
                o.cacheControl(agentOptions.cacheControl());
            }
        });

        for (RankEntity<TeamInterceptor> item : trace.getOptions().getInterceptors()) {
            if (item.target.isEnabled()) {
                item.target.onModelStart(trace, req);
            }
        }

        if (trace.getSession().isPending()) {
            return null;
        }

        try {
            return new RetryTask()
                    .maxRetries(trace.getOptions().getMaxRetries())
                    .onRetry((attempt, e) -> {
                        if (attempt == trace.getOptions().getMaxRetries()) {
                            throw new RuntimeException("Supervisor call failed", e);
                        }

                        LOG.warn("Supervisor call failed, retrying ({}/{})...", attempt + 1, trace.getOptions().getMaxRetries());
                    })
                    .callWithRetry(() -> {
                        final ChatResponse response;

                        if (trace.hasStreamSink()) {
                            FluxSink<AgentChunk> sink = trace.getOptions().getStreamSink();

                            if (sink.isCancelled()) {
                                return null;
                            }

                            response = req.stream()
                                    .takeUntil(r -> sink.isCancelled())
                                    .doOnNext(resp -> {
                                        trace.pushAgentChunk(new SupervisorChunk(node, trace, resp));
                                    })
                                    .blockLast();
                        } else {
                            response = req.call();
                        }

                        if (response.isEmpty()) {
                            //触发重试
                            throw new LlmNoReturnException("The LLM did not return");
                        }

                        return response;
                    });
        } catch (Throwable e) {
            if (e instanceof InterruptedException || e.getCause() instanceof InterruptedException) {
                LOG.debug("InterruptedException");
                return null;
            }

            throw e;
        }
    }

    protected void routeTo(FlowContext context, TeamTrace trace, String targetName) {
        trace.setRoute(targetName);
        config.getProtocol().onSupervisorRouting(context, trace, targetName);
        if (LOG.isDebugEnabled()) {
            LOG.debug("TeamAgent [{}] supervisor decision: [{}]", config.getName(), targetName);
        }
    }

    protected void handleError(FlowContext context, Throwable e) {
        LOG.error("TeamAgent [{}] supervisor fatal error", config.getName(), e);
        String traceKey = context.getAs(Agent.KEY_CURRENT_TEAM_TRACE_KEY);
        TeamTrace trace = context.getAs(traceKey);

        if (trace != null) {
            trace.setRoute(Agent.ID_END);
            trace.addRecord(ChatRole.SYSTEM, TeamAgent.ID_SUPERVISOR, "Runtime Error: " + e.getMessage(), 0);
        }
    }
}