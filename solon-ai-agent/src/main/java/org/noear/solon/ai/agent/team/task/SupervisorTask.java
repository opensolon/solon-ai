package org.noear.solon.ai.agent.team.task;

import org.noear.solon.ai.agent.Agent;
import org.noear.solon.ai.agent.team.TeamConfig;
import org.noear.solon.ai.agent.team.TeamInterceptor;
import org.noear.solon.ai.agent.team.TeamTrace;
import org.noear.solon.ai.chat.ChatRequestDesc;
import org.noear.solon.ai.chat.ChatResponse;
import org.noear.solon.ai.chat.ChatRole;
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
 * 团队协作指挥任务 (Supervisor Task) - 3.8.1 精准优化版
 */
@Preview("3.8.1")
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
        try {
            String traceKey = context.getAs(Agent.KEY_CURRENT_TRACE_KEY);
            TeamTrace trace = context.getAs(traceKey);

            if (trace == null) {
                LOG.error("TeamAgent [{}] supervisor: Team trace not found", config.getName());
                return;
            }

            // 1. 生命周期拦截
            for (RankEntity<TeamInterceptor> item : trace.getOptions().getInterceptorList()) {
                if (!item.target.shouldSupervisorContinue(trace)) {
                    trace.addStep(ChatRole.SYSTEM, Agent.ID_SUPERVISOR, "[Skipped] Intercepted by " + item.target.getClass().getSimpleName(), 0);
                    if (Agent.ID_SUPERVISOR.equals(trace.getRoute())) {
                        routeTo(context, trace, Agent.ID_END);
                    }
                    return;
                }
            }

            // 2. 深度熔断
            if (Agent.ID_END.equals(trace.getRoute()) ||
                    trace.getIterationsCount() >= trace.getOptions().getMaxTotalIterations()) {
                trace.addStep(ChatRole.SYSTEM, Agent.ID_SYSTEM, "[Terminated] Max iterations reached", 0);
                routeTo(context, trace, Agent.ID_END);
                return;
            }

            // 3. 协议执行检查
            if (!config.getProtocol().shouldSupervisorExecute(context, trace)) {
                if (Agent.ID_SUPERVISOR.equals(trace.getRoute())) {
                    routeTo(context, trace, Agent.ID_END);
                }
                return;
            }

            dispatch(context, trace);

        } catch (Exception e) {
            handleError(context, e);
        }
    }

    private void dispatch(FlowContext context, TeamTrace trace) throws Exception {
        boolean isZh = Locale.CHINA.getLanguage().equals(config.getLocale().getLanguage());

        StringBuilder protocolExt = new StringBuilder();
        config.getProtocol().prepareSupervisorInstruction(context, trace, protocolExt);

        String basePrompt = config.getTeamSystem(trace, context);
        String finalSystemPrompt = (protocolExt.length() > 0) ? basePrompt + "\n\n" + protocolExt : basePrompt;

        StringBuilder userContent = new StringBuilder();
        config.getProtocol().prepareSupervisorContext(context, trace, userContent);

        // 注入协作历史和指令
        if (isZh) {
            userContent.append("## 协作进度 (最近 5 轮历史)\n").append(trace.getFormattedHistory(5)).append("\n\n");
            userContent.append("---\n");
            userContent.append("当前迭代轮次: ").append(trace.nextIterations()).append("\n");
            userContent.append("指令：请根据专家 Skills 指派下一位执行者。已完成则输出 ").append(config.getFinishMarker()).append("。");
        } else {
            userContent.append("## Collaboration Progress (Last 5 rounds)\n").append(trace.getFormattedHistory(5)).append("\n\n");
            userContent.append("---\n");
            userContent.append("Current Iteration: ").append(trace.nextIterations()).append("\n");
            userContent.append("Command: Assign the next agent based on Skills. Or output ").append(config.getFinishMarker()).append(" to finish.");
        }


        // --- 优化点 1：使用 isAgent 过滤真正的参与者 ---
        Set<String> participatedAgentNames = trace.getSteps().stream()
                .filter(TeamTrace.TeamStep::isAgent)
                .map(s -> s.getSource().toLowerCase())
                .collect(Collectors.toSet());

        List<String> remainingAgents = config.getAgentMap().keySet().stream()
                .filter(name -> !participatedAgentNames.contains(name.toLowerCase()))
                .collect(Collectors.toList());

        if (!remainingAgents.isEmpty()) {
            String agentsList = String.join(", ", remainingAgents);
            if (isZh) {
                userContent.append("\n> **待命专家**：[").append(agentsList).append("]。");
            } else {
                userContent.append("\n> **Pending Experts**: [").append(agentsList).append("].");
            }
        }

        List<ChatMessage> messages = Arrays.asList(
                ChatMessage.ofSystem(finalSystemPrompt),
                ChatMessage.ofUser(userContent.toString())
        );

        ChatResponse response = callWithRetry(trace, messages);

        for (RankEntity<TeamInterceptor> item : trace.getOptions().getInterceptorList()) {
            item.target.onModelEnd(trace, response);
        }

        String decision = response.getResultContent().trim();
        trace.setLastDecision(decision);
        trace.getOptions().getInterceptorList().forEach(item -> item.target.onSupervisorDecision(trace, decision));

        commitRoute(trace, decision, context);
    }

    private void commitRoute(TeamTrace trace, String decision, FlowContext context) {
        if (Assert.isEmpty(decision)) {
            routeTo(context, trace, Agent.ID_END);
            return;
        }

        String protoRoute = config.getProtocol().resolveSupervisorRoute(context, trace, decision);
        if (Assert.isNotEmpty(protoRoute)) {
            routeTo(context, trace, protoRoute);
            return;
        }

        String finishMarker = config.getFinishMarker();
        if (decision.contains(finishMarker)) {
            if (config.getProtocol().shouldSupervisorRoute(context, trace, decision)) {
                // --- 优化点 2：增强提取逻辑，防止标记前后文本干扰 ---
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

        if (matchAgentRoute(context, trace, decision)) {
            return;
        }

        routeTo(context, trace, Agent.ID_END);
    }

    private boolean matchAgentRoute(FlowContext context, TeamTrace trace, String text) {
        // --- 优化点 3：清洗 Markdown 装饰符，提高匹配成功率 ---
        String cleanText = text.replaceAll("[\\*\\_\\`]", "").trim();

        if (config.getAgentMap().containsKey(cleanText)) {
            routeTo(context, trace, cleanText);
            return true;
        }

        List<String> sortedNames = config.getAgentMap().keySet().stream()
                .sorted((a, b) -> b.length() - a.length())
                .collect(Collectors.toList());

        for (String name : sortedNames) {
            // 使用单词边界匹配，防止子串误判
            Pattern p = Pattern.compile("(?i)(?<=^|[^a-zA-Z0-9])" + Pattern.quote(name) + "(?=[^a-zA-Z0-9]|$)");
            if (p.matcher(cleanText).find()) {
                routeTo(context, trace, name);
                return true;
            }
        }
        return false;
    }

    // callWithRetry, routeTo, handleError 保持原样 ...
    private ChatResponse callWithRetry(TeamTrace trace, List<ChatMessage> messages) {
        ChatRequestDesc req = config.getChatModel().prompt(messages).options(o -> {
            if (config.getChatOptions() != null) config.getChatOptions().accept(o);
        });
        trace.getOptions().getInterceptorList().forEach(item -> item.target.onModelStart(trace, req));
        int maxRetries = trace.getOptions().getMaxRetries();
        for (int i = 0; i < maxRetries; i++) {
            try { return req.call(); }
            catch (Exception e) {
                if (i == maxRetries - 1) throw new RuntimeException("Supervisor call failed", e);
                try { Thread.sleep(trace.getOptions().getRetryDelayMs() * (i + 1)); }
                catch (InterruptedException ie) { Thread.currentThread().interrupt(); throw new RuntimeException(ie); }
            }
        }
        throw new RuntimeException("Unreachable");
    }

    private void routeTo(FlowContext context, TeamTrace trace, String targetName) {
        trace.setRoute(targetName);
        config.getProtocol().onSupervisorRouting(context, trace, targetName);
        if (LOG.isDebugEnabled()) { LOG.debug("TeamAgent [{}] routed to: [{}]", config.getName(), targetName); }
    }

    private void handleError(FlowContext context, Exception e) {
        LOG.error("TeamAgent [{}] supervisor error", config.getName(), e);
        String traceKey = context.getAs(Agent.KEY_CURRENT_TRACE_KEY);
        TeamTrace trace = context.getAs(traceKey);
        if (trace != null) {
            trace.setRoute(Agent.ID_END);
            trace.addStep(ChatRole.SYSTEM, Agent.ID_SUPERVISOR, "Runtime Error: " + e.getMessage(), 0);
        }
    }
}