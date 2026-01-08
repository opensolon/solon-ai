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
 * 合同网协议 (Contract Net Protocol) - 招标与竞标收集任务
 * <p>
 * 职责：
 * 1. 遍历团队中所有可选的候选智能体（Candidate Agents）。
 * 2. 通过调用 {@link Agent#estimate} 获取各智能体针对当前任务的“竞标方案”（Proposal）。
 * 3. 汇总所有竞标信息并存入 {@link FlowContext}，供后续决策节点（Supervisor）进行“定标（Awarding）”。
 * </p>
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

    /**
     * 获取任务组件 ID：bidding
     */
    @Override
    public String name() {
        return Agent.ID_BIDDING;
    }

    /**
     * 执行招标逻辑
     *
     * @param context 流程上下文（持有 active_bids 数据）
     * @param node    当前流程节点
     */
    @Override
    public void run(FlowContext context, Node node) throws Throwable {
        if (LOG.isDebugEnabled()) {
            LOG.debug("TeamAgent [{}] bidding phase starting...", config.getName());
        }

        try {
            // 获取当前团队执行的跟踪标识和原始用户请求
            String traceKey = context.getAs(Agent.KEY_CURRENT_TRACE_KEY);
            TeamTrace trace = context.getAs(traceKey);
            Prompt prompt = trace.getPrompt();

            // 构建竞标书汇总文档
            StringBuilder bids = new StringBuilder();
            bids.append("=== Contract Net Bidding Results ===\n\n");

            // 迭代所有候选智能体，收集其对任务的能力评估和执行方案
            for (Agent agent : config.getAgentMap().values()) {
                try {
                    // 获取 Agent 的"实时竞标方案"（通常包含：预估工时、处理逻辑概要、信心值等）
                    String bidProposal = agent.estimate(trace.getSession(), prompt);

                    bids.append("Candidate: ").append(agent.name()).append("\n");
                    bids.append("Capability: ").append(agent.description()).append("\n");
                    bids.append("Proposal: ").append(bidProposal).append("\n");
                    bids.append("---\n");

                    if (LOG.isDebugEnabled()) {
                        LOG.debug("Collected bid from agent {}: {}", agent.name(), bidProposal);
                    }
                } catch (Exception e) {
                    // 容错处理：单智能体异常不中断整体招标流程
                    LOG.warn("Failed to collect bid from agent {}: {}", agent.name(), e.getMessage());
                    bids.append("Candidate: ").append(agent.name()).append("\n");
                    bids.append("Status: Failed to provide bid - ").append(e.getMessage()).append("\n");
                    bids.append("---\n");
                }
            }

            bids.append("\n=== End of Bids ===");

            // 将汇总后的标书内容持久化到上下文，供后续 SupervisorTask 注入 Prompt 决策使用
            String bidsContent = bids.toString();
            context.put("active_bids", bidsContent);

            if (LOG.isInfoEnabled()) {
                LOG.info("Collected {} bids for team [{}]", config.getAgentMap().size(), config.getName());
            }

            // 流程控制：竞标信息收集完毕，重定向至调解器（Supervisor）进行择优定标
            trace.setRoute(Agent.ID_SUPERVISOR);
            trace.addStep(Agent.ID_BIDDING, "Bidding phase completed with " +
                    config.getAgentMap().size() + " bids collected", 0);

        } catch (Exception e) {
            LOG.error("Contract net bidding task encountered a fatal error", e);

            // 异常兜底：确保错误发生时流程能安全终止
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