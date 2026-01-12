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
 * 合同网协议 (Contract Net Protocol) - 招标与竞标收集任务
 *
 * <p>该组件实现了强类型协议感知，直接驱动协议内部状态更新：</p>
 * <ul>
 * <li><b>协议绑定</b>：通过构造函数持有 {@link ContractNetProtocol}。</li>
 * <li><b>状态对齐</b>：直接调用协议内部类的 addBid 方法存储结构化标书。</li>
 * <li><b>路由控制</b>：完成竞标后自动将控制权归还给主管进行定标。</li>
 * </ul>
 *
 * @author noear
 * @since 3.8.1
 */
@Preview("3.8.1")
public class ContractNetBiddingTask implements NamedTaskComponent {
    private static final Logger LOG = LoggerFactory.getLogger(ContractNetBiddingTask.class);

    private final TeamAgentConfig config;
    private final ContractNetProtocol protocol;

    /**
     * 构造函数：实现协议与任务的强耦合绑定
     *
     * @param config   团队配置
     * @param protocol 所属的合同网协议实例
     */
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
            LOG.debug("TeamAgent [{}] entering contract net bidding phase (Protocol: {})",
                    config.getName(), protocol.name());
        }

        try {
            // 获取当前协作轨迹
            String traceKey = context.getAs(Agent.KEY_CURRENT_TRACE_KEY);
            TeamTrace trace = context.getAs(traceKey);

            // 1. 获取（或初始化）协议专有的结构化状态对象
            // 使用硬编码 KEY 确保与 Protocol 类中的 prepareSupervisorInstruction 访问一致
            ContractNetProtocol.ContractState state = (ContractNetProtocol.ContractState) trace.getProtocolContext()
                    .computeIfAbsent("contract_state_obj", k -> new ContractNetProtocol.ContractState());

            int bidCount = 0;

            // 2. 遍历团队成员进行分布式竞标
            for (Agent agent : config.getAgentMap().values()) {
                // 排除主管节点，主管不参与方案竞标
                if (Agent.ID_SUPERVISOR.equals(agent.name())) {
                    continue;
                }

                try {
                    // 调用智能体的估算接口获取标书内容
                    // 标书通常建议为 JSON 格式：{"score":0.9, "plan":"...", "cost":"..."}
                    ONode bidProposal = protocol.constructBid(agent, trace.getPrompt());

                    // 3. 核心改进：直接调用协议状态对象的 addBid 注入数据
                    // 这样 Protocol.toString() 里的看板逻辑能立刻感知到结构化数据
                    state.addBid(agent.name(), bidProposal);

                    bidCount++;
                } catch (Exception e) {
                    // 单个 Agent 竞标失败不阻断整体流程
                    LOG.warn("Agent [{}] failed to provide a bid: {}", agent.name(), e.getMessage());
                }
            }

            // 4. 状态记录与路由跳转
            trace.addStep(ChatRole.SYSTEM, Agent.ID_BIDDING, "Bidding completed. " + bidCount + " proposals collected.", 0);

            // 路由指向主管，由主管依据看板中的标书进行定标决策
            trace.setRoute(Agent.ID_SUPERVISOR);

            if (LOG.isInfoEnabled()) {
                LOG.info("TeamAgent [{}] bidding phase finished. Total bids: {}", config.getName(), bidCount);
            }

        } catch (Exception e) {
            handleFatalError(context, e);
        }
    }

    /**
     * 异常熔断处理：若招标任务本身崩溃，强制结束团队协作
     */
    private void handleFatalError(FlowContext context, Exception e) {
        LOG.error("Critical error during bidding task execution", e);
        String traceKey = context.getAs(Agent.KEY_CURRENT_TRACE_KEY);
        if (traceKey != null) {
            TeamTrace trace = context.getAs(traceKey);
            if (trace != null) {
                trace.setRoute(Agent.ID_END);
                trace.addStep(ChatRole.SYSTEM, Agent.ID_SYSTEM, "Bidding task failed: " + e.getMessage(), 0);
            }
        }
    }
}