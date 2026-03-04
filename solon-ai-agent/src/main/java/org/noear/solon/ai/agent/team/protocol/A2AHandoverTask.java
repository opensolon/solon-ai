package org.noear.solon.ai.agent.team.protocol;

import org.noear.solon.Utils;
import org.noear.solon.ai.agent.Agent;
import org.noear.solon.ai.agent.team.TeamAgent;
import org.noear.solon.ai.agent.team.TeamAgentConfig;
import org.noear.solon.ai.agent.team.TeamProtocol;
import org.noear.solon.ai.agent.team.TeamTrace;
import org.noear.solon.flow.FlowContext;
import org.noear.solon.flow.NamedTaskComponent;
import org.noear.solon.flow.Node;
import org.noear.solon.lang.Preview;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A2A 协议 - 自动接力中转任务 (物理快车道)
 */
@Preview("3.8.1")
public class A2AHandoverTask implements NamedTaskComponent {
    static final Logger LOG = LoggerFactory.getLogger(A2AHandoverTask.class);

    public final static String ID_HANDOVER = "handover";

    private final TeamAgentConfig config;
    private final A2AProtocol protocol;

    public A2AHandoverTask(TeamAgentConfig config, A2AProtocol protocol) {
        this.config = config;
        this.protocol = protocol;
    }

    @Override
    public String name() {
        return ID_HANDOVER;
    }

    @Override
    public void run(FlowContext context, Node node) throws Throwable {
        TeamTrace trace = context.getAs(config.getTraceKey());
        if (trace == null) return;

        A2AProtocol.A2AState state = protocol.getA2AState(trace);
        String target = state.getLastTempTarget();

        // 如果 target 为空，说明：
        // 1. 专家根本没调工具
        // 2. 上一轮失败后已经被 Protocol 清理了
        if (Utils.isEmpty(target)) {
            trace.setRoute(TeamAgent.ID_SUPERVISOR);
            return;
        }

        String nextRoute = protocol.resolveSupervisorRoute(context, trace, null);

        if (Utils.isNotEmpty(nextRoute)) {
            trace.setRoute(nextRoute);
            LOG.debug("Protocol [{}] routing to: {}", protocol.name(), nextRoute);
        } else {
            trace.setRoute(Agent.ID_END);
            LOG.debug("Protocol [{}] routing to: {}", protocol.name(), Agent.ID_END);
        }
    }
}