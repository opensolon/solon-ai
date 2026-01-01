package org.noear.solon.ai.agent.team.task;

import org.noear.solon.ai.agent.Agent;
import org.noear.solon.ai.agent.team.TeamConfig;
import org.noear.solon.ai.agent.team.TeamTrace;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.flow.FlowContext;
import org.noear.solon.flow.Node;
import org.noear.solon.flow.TaskComponent;

/**
 * 合同网协议中的招标阶段任务（负责收集所有候选者的背景信息）
 */
public class ContractNetBiddingTask implements TaskComponent {
    private final TeamConfig config;

    public ContractNetBiddingTask(TeamConfig config) { this.config = config; }

    @Override
    public void run(FlowContext context, Node node) throws Throwable {
        Prompt prompt = context.getAs(Agent.KEY_PROMPT);
        StringBuilder bids = new StringBuilder();
        // 汇总所有 Agent 的能力描述，形成“标书集”
        for (Agent agent : config.getAgentMap().values()) {
            // 调用新方法：获取 Agent 的“实时竞标方案”
            String bidProposal = agent.estimate(context, prompt);

            bids.append("- Candidate [").append(agent.name()).append("]:\n")
                    .append("  Proposal: ").append(bidProposal).append("\n");
        }

        context.put("active_bids", bids.toString());

        // 竞标信息收集完毕，跳回 Router 让调解器做出 AWARD（定标）决策
        TeamTrace trace = context.getAs(context.getAs(Agent.KEY_CURRENT_TRACE_KEY));
        trace.setRoute(Agent.ID_ROUTER);
    }
}