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
 * <p>核心职责：遍历团队成员，调用协议算法生成初始标书，并结构化存储于协议上下文，供主管定标。</p>
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
        return Agent.ID_BIDDING;
    }

    @Override
    public void run(FlowContext context, Node node) throws Throwable {
        if (LOG.isDebugEnabled()) {
            LOG.debug("TeamAgent [{}] entering contract net bidding phase", config.getName());
        }

        try {
            String traceKey = context.getAs(Agent.KEY_CURRENT_TRACE_KEY);
            TeamTrace trace = context.getAs(traceKey);

            // 初始化协议私有状态（看板数据源）
            ContractNetProtocol.ContractState state = (ContractNetProtocol.ContractState) trace.getProtocolContext()
                    .computeIfAbsent("contract_state_obj", k -> new ContractNetProtocol.ContractState());

            int bidCount = 0;

            // 迭代执行成员竞标逻辑
            for (Agent agent : config.getAgentMap().values()) {
                // 排除主管节点
                if (Agent.ID_SUPERVISOR.equals(agent.name())) {
                    continue;
                }

                try {
                    // 调用协议内置的打分/估算逻辑生成结构化标书
                    ONode bidProposal = protocol.constructBid(agent, trace.getPrompt());

                    // 将标书持久化到协议状态中
                    state.addBid(agent.name(), bidProposal);

                    if (LOG.isTraceEnabled()) {
                        LOG.trace("Agent [{}] submitted a bid: {}", agent.name(), bidProposal.toJson());
                    }

                    bidCount++;
                } catch (Exception e) {
                    LOG.warn("Agent [{}] bidding failed: {}", agent.name(), e.getMessage());
                }
            }

            // 记录轨迹并重定向至主管进行“定标”决策
            trace.addStep(ChatRole.SYSTEM, Agent.ID_BIDDING, "Bidding finished. " + bidCount + " proposals collected.", 0);
            trace.setRoute(Agent.ID_SUPERVISOR);

            if (LOG.isDebugEnabled()) {
                LOG.debug("TeamAgent [{}] bidding finalized. Bids count: {}", config.getName(), bidCount);
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
        String traceKey = context.getAs(Agent.KEY_CURRENT_TRACE_KEY);
        if (traceKey != null) {
            TeamTrace trace = context.getAs(traceKey);
            if (trace != null) {
                trace.setRoute(Agent.ID_END);
                trace.addStep(ChatRole.SYSTEM, Agent.ID_SYSTEM, "Bidding interrupted: " + e.getMessage(), 0);
            }
        }
    }
}