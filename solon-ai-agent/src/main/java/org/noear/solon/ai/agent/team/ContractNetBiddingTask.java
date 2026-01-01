package org.noear.solon.ai.agent.team;

import org.noear.solon.ai.agent.Agent;
import org.noear.solon.flow.FlowContext;
import org.noear.solon.flow.Node;
import org.noear.solon.flow.TaskComponent;

/**
 * 合同网协议中的并行招标阶段
 */
class ContractNetBiddingTask implements TaskComponent {
    private final TeamConfig config;

    public ContractNetBiddingTask(TeamConfig config) { this.config = config; }

    @Override
    public void run(FlowContext context, Node node) throws Throwable {
        StringBuilder bids = new StringBuilder();
        // 这里的逻辑可以进一步扩展：比如调用 agent.call() 的一个轻量级 mode (discovery)
        // 目前简化为将所有 Agent 的描述汇总，作为“潜在标书”供决策
        for (Agent agent : config.getAgentMap().values()) {
            bids.append("- Candidate Agent [").append(agent.name()).append("]: ")
                    .append(agent.description()).append("\n");
        }

        context.put("active_bids", bids.toString());

        // 竞标信息收集完毕，跳回 Router 让 Supervisor 定标
        TeamTrace trace = context.getAs(context.getAs(Agent.KEY_CURRENT_TRACE_KEY));
        trace.setRoute(Agent.ID_ROUTER);
    }
}