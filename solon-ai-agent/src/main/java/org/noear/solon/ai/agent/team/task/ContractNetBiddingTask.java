/*
 * Copyright 2017-2025 noear.org and authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.noear.solon.ai.agent.team.task;

import org.noear.solon.ai.agent.Agent;
import org.noear.solon.ai.agent.team.TeamConfig;
import org.noear.solon.ai.agent.team.TeamTrace;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.flow.FlowContext;
import org.noear.solon.flow.NamedTaskComponent;
import org.noear.solon.flow.Node;
import org.noear.solon.lang.Preview;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 合同网协议中的招标阶段任务（负责收集所有候选者的背景信息）
 *
 * @author noear
 * @since 3.8.1
 */
@Preview("3.8")
public class ContractNetBiddingTask implements NamedTaskComponent {
    private static final Logger LOG = LoggerFactory.getLogger(ContractNetBiddingTask.class);
    private final TeamConfig config;

    public ContractNetBiddingTask(TeamConfig config) {
        this.config = config;
    }

    @Override
    public String name() {
        return Agent.ID_BIDDING;
    }

    @Override
    public void run(FlowContext context, Node node) throws Throwable {
        if (LOG.isDebugEnabled()) {
            LOG.debug("TeamAgent [{}] bidding starting...", config.getName());
        }

        try {
            String traceKey = context.getAs(Agent.KEY_CURRENT_TRACE_KEY);
            TeamTrace trace = context.getAs(traceKey);
            Prompt prompt = trace.getPrompt();

            StringBuilder bids = new StringBuilder();
            bids.append("=== Contract Net Bidding Results ===\n\n");

            // 汇总所有 Agent 的能力描述，形成"标书集"
            for (Agent agent : config.getAgentMap().values()) {
                try {
                    // 调用新方法：获取 Agent 的"实时竞标方案"
                    String bidProposal = agent.estimate(context, prompt);

                    bids.append("Candidate: ").append(agent.name()).append("\n");
                    bids.append("Capability: ").append(agent.description()).append("\n");
                    bids.append("Proposal: ").append(bidProposal).append("\n");
                    bids.append("---\n");

                    if (LOG.isDebugEnabled()) {
                        LOG.debug("Collected bid from agent {}: {}", agent.name(), bidProposal);
                    }
                } catch (Exception e) {
                    LOG.warn("Failed to collect bid from agent {}: {}", agent.name(), e.getMessage());
                    bids.append("Candidate: ").append(agent.name()).append("\n");
                    bids.append("Status: Failed to provide bid - ").append(e.getMessage()).append("\n");
                    bids.append("---\n");
                }
            }

            bids.append("\n=== End of Bids ===");

            String bidsContent = bids.toString();
            context.put("active_bids", bidsContent);

            if (LOG.isInfoEnabled()) {
                LOG.info("Collected {} bids for contract net protocol", config.getAgentMap().size());
            }

            // 竞标信息收集完毕，跳回 Router 让调解器做出 AWARD（定标）决策
            trace.setRoute(Agent.ID_SUPERVISOR);
            trace.addStep(Agent.ID_BIDDING, "Bidding phase completed with " +
                    config.getAgentMap().size() + " bids collected", 0);
        } catch (Exception e) {
            LOG.error("Contract net bidding task failed", e);
            // 设置错误状态，避免死循环
            String traceKey = context.getAs(Agent.KEY_CURRENT_TRACE_KEY);
            if (traceKey != null) {
                TeamTrace trace = context.getAs(traceKey);
                if (trace != null) {
                    trace.setRoute(Agent.ID_END);
                    trace.addStep(Agent.ID_SYSTEM, "Bidding failed: " + e.getMessage(), 0);
                }
            }
        }
    }
}