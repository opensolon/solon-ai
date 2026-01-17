/*
 * Copyright 2017-2025 noear.org and authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.noear.solon.ai.agent.team.protocol;

import org.noear.snack4.ONode;
import org.noear.solon.ai.agent.Agent;
import org.noear.solon.ai.agent.team.TeamAgentConfig;
import org.noear.solon.ai.agent.team.TeamTrace;
import org.noear.solon.ai.chat.ChatRole;
import org.noear.solon.flow.FlowContext;
import org.noear.solon.flow.NamedTaskComponent;
import org.noear.solon.flow.Node;
import org.noear.solon.lang.Preview;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 合同网协议 (Contract Net Protocol) - 自动招标任务
 *
 * <p>核心职责：遍历团队成员，优先保留已通过工具提交的标书，对未提交成员调用协议算法进行自动打分补全。</p>
 *
 * @author noear
 * @since 3.8.1
 */
@Preview("3.8.1")
public class ContractNetBiddingTask implements NamedTaskComponent {
    private static final Logger LOG = LoggerFactory.getLogger(ContractNetBiddingTask.class);

    private final TeamAgentConfig config;
    private final ContractNetProtocol protocol;

    public ContractNetBiddingTask(TeamAgentConfig config, ContractNetProtocol protocol) {
        this.config = config;
        this.protocol = protocol;
    }

    @Override
    public String name() {
        return ContractNetProtocol.ID_BIDDING;
    }

    @Override
    public void run(FlowContext context, Node node) throws Throwable {
        if (LOG.isDebugEnabled()) {
            LOG.debug("TeamAgent [{}] entering contract net bidding phase", config.getName());
        }

        try {
            TeamTrace trace = context.getAs(config.getTraceKey());
            if (trace == null) {
                LOG.error("ContractNet: TeamTrace not found in FlowContext");
                return;
            }

            ContractNetProtocol.ContractState state = protocol.getContractState(trace);

            int manualBidCount = 0;
            int autoBidCount = 0;

            // 迭代执行成员竞标逻辑
            for (Agent agent : config.getAgentMap().values()) {
                // 排除主管节点
                if (Agent.ID_SUPERVISOR.equals(agent.name())) {
                    continue;
                }

                // 核心优化：检查该 Agent 是否已经通过工具 (Tool) 主动提交过标书
                if (state.hasAgentBid(agent.name())) {
                    manualBidCount++;
                    if (LOG.isDebugEnabled()) {
                        LOG.debug("ContractNet: Agent [{}] already submitted a manual bid, skipping auto-construct.", agent.name());
                    }
                    continue;
                }

                try {
                    // 调用协议内置的打分/估算逻辑生成保底标书 (算法代投)
                    ONode bidProposal = protocol.constructBid(agent, trace.getPrompt());

                    // 将保底标书持久化到协议状态中
                    state.addBid(agent.name(), bidProposal);

                    if (LOG.isTraceEnabled()) {
                        LOG.trace("Agent [{}] auto-submitted a bid: {}", agent.name(), bidProposal.toJson());
                    }

                    autoBidCount++;
                } catch (Exception e) {
                    LOG.warn("Agent [{}] auto-bidding failed: {}", agent.name(), e.getMessage());
                }
            }

            // 记录轨迹并重定向至主管进行“定标”决策
            String summary = String.format("Bidding finished. %d bids collected (%d manual, %d auto).",
                    (manualBidCount + autoBidCount), manualBidCount, autoBidCount);

            trace.addRecord(ChatRole.SYSTEM, ContractNetProtocol.ID_BIDDING, summary, 0);

            // 归还路由控制权给 Supervisor
            trace.setRoute(Agent.ID_SUPERVISOR);

            if (LOG.isDebugEnabled()) {
                LOG.debug("TeamAgent [{}] bidding finalized. {}", config.getName(), summary);
            }

        } catch (Exception e) {
            handleFatalError(context, e);
        }
    }

    /**
     * 异常熔断：招标环节核心异常时，强行终止团队任务
     */
    private void handleFatalError(FlowContext context, Exception e) {
        LOG.error("ContractNet bidding task fatal error", e);
        TeamTrace trace = context.getAs(config.getTraceKey());
        if (trace != null) {
            trace.setRoute(Agent.ID_END);
            trace.addRecord(ChatRole.SYSTEM, Agent.ID_SYSTEM, "Bidding interrupted: " + e.getMessage(), 0);
        }
    }
}