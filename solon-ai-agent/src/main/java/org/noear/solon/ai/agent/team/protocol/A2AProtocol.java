package org.noear.solon.ai.agent.team.protocol;

import org.noear.solon.Utils;
import org.noear.solon.ai.agent.Agent;
import org.noear.solon.ai.agent.team.TeamConfig;
import org.noear.solon.ai.agent.team.TeamTrace;
import org.noear.solon.ai.agent.team.task.SupervisorTask;
import org.noear.solon.ai.chat.ChatOptions;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.chat.tool.FunctionToolDesc;
import org.noear.solon.flow.FlowContext;
import org.noear.solon.flow.GraphSpec;

import java.util.Locale;
import java.util.stream.Collectors;

/**
 * A2A (Agent-to-Agent) 协议适配
 * 利用 FunctionToolDesc 注入结构化移交工具，实现去中心化协作
 */
public class A2AProtocol extends TeamProtocolBase {
    private static final String TOOL_TRANSFER = "transfer_to";

    public A2AProtocol(TeamConfig config) {
        super(config);
    }

    @Override
    public String name() {
        return "A2A";
    }

    @Override
    public void buildGraph(GraphSpec spec) {
        String firstAgent = config.getAgentMap().keySet().iterator().next();

        // 1. 起始节点
        spec.addStart(Agent.ID_START).linkAdd(firstAgent);

        // 2. 所有专家执行完后统一进入“移交路由器”
        config.getAgentMap().values().forEach(a -> {
            spec.addActivity(a).linkAdd(Agent.ID_SUPERVISOR);
        });

        // 3. 路由分发逻辑（复用 SupervisorTask 的解析能力，但赋予 Router 语义）
        spec.addExclusive(new SupervisorTask(config)).then(ns -> {
            linkAgents(ns, "__" + config.getName());
        }).linkAdd(Agent.ID_END);

        spec.addEnd(Agent.ID_END);
    }

    @Override
    public void injectAgentOptions(Agent agent, ChatOptions options) {
        // 构建除自己外的专家名录
        String expertList = config.getAgentMap().values().stream()
                .filter(a -> !a.name().equals(agent.name()))
                .map(a -> a.name() + "(" + a.description() + ")")
                .collect(Collectors.joining(", "));

        options.toolsAdd(new FunctionToolDesc(TOOL_TRANSFER)
                .title("移交任务")
                .description("当你无法独立完成当前任务，或需要其他专家协助时，调用此工具进行移交。")
                .stringParamAdd("target", "目标专家名称，必选值: [" + expertList + "]")
                .stringParamAdd("memo", "移交备注：说明当前进度和接棒专家需要关注的重点")
                .doHandle(args -> {
                    String target = (String) args.get("target");
                    // 返回一个引导性回执，让 Agent 停止当前推理
                    return "Handover initiated to [" + target + "]. Please terminate your output.";
                }));
    }

    @Override
    public void injectAgentInstruction(Agent agent, Locale locale, StringBuilder sb) {
        boolean isZh = Locale.CHINA.getLanguage().equals(locale.getLanguage());

        if (isZh) {
            sb.append("\n## 协作协议：A2A (去中心化移交)\n");
            sb.append("- **移交协作**：若发现任务超出你的专长，请调用 `").append(TOOL_TRANSFER).append("` 移交给其他专家。\n");
            sb.append("- **终止任务**：若目标已达成，请在回复中包含 '").append(config.getFinishMarker()).append("'。\n");
            sb.append("- **禁止循环**：请勿在无实质进展的情况下将任务移回给前序专家。\n");
        } else {
            sb.append("\n## Protocol: A2A (Decentralized Handoff)\n");
            sb.append("- **Transfer**: Call `").append(TOOL_TRANSFER).append("` to collaborate with other experts.\n");
            sb.append("- **Completion**: Include '").append(config.getFinishMarker()).append("' when the goal is met.\n");
        }
    }

    @Override
    public Prompt prepareAgentPrompt(TeamTrace trace, Agent agent, Prompt originalPrompt, Locale locale) {
        // 尝试从上一个 decision 中提取移交备注注入给下一个接班人
        String lastDecision = trace.getDecision();
        if (Utils.isNotEmpty(lastDecision) && lastDecision.contains(TOOL_TRANSFER)) {
            String prefix = Locale.CHINA.getLanguage().equals(locale.getLanguage()) ? "【接棒提示】： " : "[Handover Hint]: ";
            // 简单逻辑：如果包含备注字样，则作为系统消息注入，帮助接班 Agent 快速定位
            originalPrompt.getMessages().add(0, ChatMessage.ofSystem(prefix + "Previous expert has transferred this task to you via " + TOOL_TRANSFER));
        }
        return originalPrompt;
    }

    @Override
    public boolean interceptSupervisorRouting(FlowContext context, TeamTrace trace, String decision) {
        if (Utils.isEmpty(decision)) {
            return false;
        }

        // 1. 检查结束标识（优先级最高）
        if (decision.toLowerCase().contains(config.getFinishMarker().toLowerCase())) {
            trace.setRoute(Agent.ID_END);
            return true;
        }

        // 2. 精确匹配工具调用意图
        // 针对模型输出格式不一的情况，遍历所有候选专家名进行判定
        for (String agentName : config.getAgentMap().keySet()) {
            // 如果 decision 中同时出现了移交工具名和某个专家名，则判定为移交该专家
            if (decision.contains(TOOL_TRANSFER) && decision.contains(agentName)) {
                trace.setRoute(agentName);
                return true;
            }
        }

        // 3. 兼容文本式移交标记 ("Transfer to [AgentName]")
        String marker = "Transfer to ";
        int lastIndex = decision.lastIndexOf(marker);
        if (lastIndex != -1) {
            String targetPart = decision.substring(lastIndex + marker.length()).trim();
            for (String agentName : config.getAgentMap().keySet()) {
                if (targetPart.startsWith(agentName)) {
                    trace.setRoute(agentName);
                    return true;
                }
            }
        }

        return false;
    }
}