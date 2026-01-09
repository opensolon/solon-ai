package org.noear.solon.ai.agent.team.protocol;

import org.noear.solon.ai.agent.Agent;
import org.noear.solon.ai.agent.team.TeamConfig;
import org.noear.solon.ai.agent.team.TeamTrace;
import org.noear.solon.ai.chat.ChatOptions;
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

        // 2. 节点逻辑：执行完后统一进入路由解析器 (Supervisor 节点充当路由器)
        config.getAgentMap().values().forEach(a -> {
            spec.addActivity(a).linkAdd(Agent.ID_SUPERVISOR);
        });

        // 3. 路由分发逻辑
        spec.addExclusive(Agent.ID_SUPERVISOR).then(ns -> {
            linkAgents(ns, "__" + config.getName());
        }).linkAdd(Agent.ID_END);

        spec.addEnd(Agent.ID_END);
    }

    @Override
    public void injectAgentOptions(Agent agent, ChatOptions options) {
        // 构建除自己外的专家名录描述
        String expertList = config.getAgentMap().values().stream()
                .filter(a -> !a.name().equals(agent.name()))
                .map(a -> a.name() + "(" + a.description() + ")")
                .collect(Collectors.joining(", "));

        // 注入结构化移交工具
        options.toolsAdd(new FunctionToolDesc(TOOL_TRANSFER)
                .title("移交任务")
                .description("当你无法独立完成当前任务，或需要其他专家协助时，调用此工具进行移交。")
                .stringParamAdd("target", "目标专家名称，可选值: [" + expertList + "]")
                .stringParamAdd("memo", "移交备注：说明当前进度和需要对方配合的具体事项")
                .doHandle(args -> {
                    // 工具本身不执行业务，仅作为指令载体
                    return "OK";
                }));
    }

    @Override
    public void injectAgentInstruction(Agent agent, Locale locale, StringBuilder sb) {
        boolean isZh = Locale.CHINA.getLanguage().equals(locale.getLanguage());

        if (isZh) {
            sb.append("\n## 协作协议：A2A (去中心化移交)\n");
            sb.append("1. **移交协作**：如果你发现任务需要其他领域专家，请调用 `").append(TOOL_TRANSFER).append("` 工具。\n");
            sb.append("2. **直接回答**：如果你能独立完成，请直接给出最终答案。\n");
            sb.append("3. **终止任务**：如果任务已彻底完成，请在回复中包含 '").append(config.getFinishMarker()).append("'。\n");
        } else {
            sb.append("\n## Protocol: A2A (Decentralized Handoff)\n");
            sb.append("1. **Handoff**: Use the `").append(TOOL_TRANSFER).append("` tool to transfer the task to another expert.\n");
            sb.append("2. **Completion**: If the goal is achieved, include '").append(config.getFinishMarker()).append("' in your response.\n");
        }
    }

    @Override
    public boolean interceptSupervisorRouting(FlowContext context, TeamTrace trace, String decision) {
        if (decision == null || decision.isEmpty()) {
            return false;
        }

        // 1. 检查是否触发了移交工具 (支持结构化提取)
        // 逻辑：在 A2A 模式下，我们优先查找回复中是否存在 "Transfer to [Agent]" 标记
        // 或者通过工具调用的参数来决定路由

        // 兼容性逻辑：查找 Tool Call 意图
        for (String agentName : config.getAgentMap().keySet()) {
            // 如果 decision 包含目标 Agent 名称，且上下文指示了移交意图
            if (decision.contains(TOOL_TRANSFER) && decision.contains(agentName)) {
                trace.setRoute(agentName);
                return true;
            }
        }

        // 2. 降级逻辑：原有的文本匹配
        String marker = "Transfer to ";
        int lastIndex = decision.lastIndexOf(marker);
        if (lastIndex != -1) {
            String targetName = decision.substring(lastIndex + marker.length()).trim();
            for (String agentName : config.getAgentMap().keySet()) {
                if (targetName.startsWith(agentName)) {
                    trace.setRoute(agentName);
                    return true;
                }
            }
        }

        // 3. 检查结束标识
        if (decision.contains(config.getFinishMarker())) {
            trace.setRoute(Agent.ID_END);
            return true;
        }

        return false;
    }
}