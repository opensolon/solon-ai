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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 团队协作调解任务（决策中心）
 * 负责根据策略、历史记录和 Agent 状态决定下一步路由
 */
public class MediatorTask implements TaskComponent {
    private final TeamConfig config;

    public MediatorTask(TeamConfig config) {
        this.config = config;
    }

    @Override
    public void run(FlowContext context, Node node) throws Throwable {
        Prompt prompt = context.getAs(Agent.KEY_PROMPT);
        String traceKey = context.getAs(Agent.KEY_CURRENT_TRACE_KEY);
        TeamTrace trace = context.getAs(traceKey);

        // 1. 检查迭代次数上限
        if (trace.iterationsCount() >= config.getMaxTotalIterations()) {
            trace.setRoute(Agent.ID_END);
            return;
        }

        // 2. 准备协议补充信息
        StringBuilder protocolExt = new StringBuilder();
        prepareProtocolInfo(context, protocolExt);

        // 3. 构建 Prompt 并调用模型
        String systemPrompt = config.getSystemPrompt(prompt) + protocolExt.toString();

        // 获取模型决策
        String decision = config.getChatModel().prompt(Arrays.asList(
                ChatMessage.ofSystem(systemPrompt),
                ChatMessage.ofUser("Context History:\n" + trace.getFormattedHistory())
        )).call().getResultContent().trim();

        // 4. 解析决策并设置路由
        parseAndRoute(trace, decision);

        // 5. 迭代计数
        trace.nextIterations();
    }

    /**
     * 根据当前状态准备额外的协议上下文
     */
    private void prepareProtocolInfo(FlowContext context, StringBuilder sb) {
        if (config.getStrategy() == TeamStrategy.CONTRACT_NET) {
            String bids = context.getAs("active_bids");
            if (bids != null) {
                sb.append("\n[System Notice] Bids received from candidates:\n")
                        .append(bids)
                        .append("\nPlease review these proposals and select the best Agent name to award the task.");
                // 读取后即刻从上下文移除，防止重复干扰下一次决策
                context.remove("active_bids");
            } else {
                sb.append("\n[System Notice] No bids collected yet. You may output 'BIDDING' to start the bidding process.");
            }
        }
    }

    /**
     * 解析模型输出，确定路由
     */
    private void parseAndRoute(TeamTrace trace, String decision) {
        String upperDecision = decision.toUpperCase();
        String finishMarker = config.getFinishMarker().toUpperCase();

        // A. 优先判断结束标识
        if (upperDecision.contains(finishMarker)) {
            trace.setRoute(Agent.ID_END);
            return;
        }

        // B. 合同网协议：识别招标信号
        if (config.getStrategy() == TeamStrategy.CONTRACT_NET) {
            if (upperDecision.contains(Agent.ID_BIDDING.toUpperCase())) {
                trace.setRoute(Agent.ID_BIDDING);
                return;
            }
        }

        // C. Agent 名称匹配（采用长度降序排列，确保长名优先匹配，防止 "DataAgent" 匹配到 "Agent"）
        List<String> agentNames = new ArrayList<>(config.getAgentMap().keySet());
        List<String> sortedNames = agentNames.stream()
                .sorted((a, b) -> Integer.compare(b.length(), a.length()))
                .collect(Collectors.toList());

        for (String name : sortedNames) {
            if (upperDecision.contains(name.toUpperCase())) {
                trace.setRoute(name);
                return;
            }
        }

        // D. 兜底方案：如果没有匹配到任何 Agent 且模型没有说结束，默认结束以防死循环
        trace.setRoute(Agent.ID_END);
    }
}