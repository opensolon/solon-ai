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
 *
 * <p>该组件实现了 MAS（多智能体系统）中的招标机制：</p>
 * <ul>
 * <li><b>招标 (Tendering)</b>：将任务需求分发给团队内的所有候选智能体。</li>
 * <li><b>竞标收集 (Bid Collection)</b>：触发各智能体的 {@link Agent#estimate} 方法获取执行方案。</li>
 * <li><b>标书汇总 (Information Aggregation)</b>：构建结构化的竞标报告，存入协议上下文供 {@link SupervisorTask} 决策。</li>
 * </ul>
 * *
 *
 * @author noear
 * @since 3.8.1
 */
@Preview("3.8")
public class ContractNetBiddingTask implements NamedTaskComponent {
    private static final Logger LOG = LoggerFactory.getLogger(ContractNetBiddingTask.class);

    /** 团队配置，用于获取成员名录 */
    private final TeamConfig config;

    /** 存储在协议上下文中的标书键名 */
    public static final String CONTEXT_BIDS_KEY = "active_bids";

    public ContractNetBiddingTask(TeamConfig config) {
        this.config = config;
    }

    /**
     * 组件唯一标识：bidding
     */
    @Override
    public String name() {
        return Agent.ID_BIDDING;
    }

    /**
     * 执行招标流程
     * <p>此过程是同步阻塞的，按序询问每位成员的意向。在复杂任务中，这是决定任务分配质量的关键阶段。</p>
     */
    @Override
    public void run(FlowContext context, Node node) throws Throwable {
        if (LOG.isDebugEnabled()) {
            LOG.debug("TeamAgent [{}] entering contract net bidding phase...", config.getName());
        }

        try {
            // 获取当前协作轨迹及原始任务请求
            String traceKey = context.getAs(Agent.KEY_CURRENT_TRACE_KEY);
            TeamTrace trace = context.getAs(traceKey);
            Prompt prompt = trace.getPrompt();

            // 1. 初始化结构化标书文档（采用 Markdown 增强 LLM 解析率）
            StringBuilder bidsReport = new StringBuilder();
            bidsReport.append("## Candidate Bids for Task Selection\n\n");

            int bidCount = 0;
            // 2. 遍历成员名录进行“能力竞标”
            for (Agent agent : config.getAgentMap().values()) {
                // 排除主管节点自身参与竞标（如果有的话）
                if (Agent.ID_SUPERVISOR.equals(agent.name())) {
                    continue;
                }

                try {
                    // 调用 Agent 的自评接口。
                    // 提示：子类可在此处注入自定义的 Prompt 修饰逻辑
                    String bidProposal = agent.estimate(trace.getSession(), prompt);

                    bidsReport.append("### Agent: ").append(agent.name()).append("\n");
                    bidsReport.append("- **Role Description**: ").append(agent.descriptionFor(trace.getContext())).append("\n");
                    bidsReport.append("- **Technical Proposal**: ").append(bidProposal).append("\n\n");

                    bidCount++;
                } catch (Exception e) {
                    // 容错：单个智能体故障不应阻断整个团队的招标
                    LOG.warn("Agent [{}] failed to provide a bid: {}", agent.name(), e.getMessage());
                    bidsReport.append("### Agent: ").append(agent.name()).append(" (FAILED)\n");
                    bidsReport.append("- **Error**: ").append(e.getMessage()).append("\n\n");
                }
            }

            // 3. 将汇总结果存入 Protocol 专有的 Context 空间，实现状态隔离
            String finalBids = bidsReport.toString();
            trace.getProtocolContext().put(CONTEXT_BIDS_KEY, finalBids);

            if (LOG.isInfoEnabled()) {
                LOG.info("TeamAgent [{}] collected {} bids successfully.", config.getName(), bidCount);
            }

            // 4. 状态流转：竞标结束，将控制权移交给主管（Supervisor）进行“定标（Awarding）”
            trace.setRoute(Agent.ID_SUPERVISOR);
            trace.addStep(Agent.ID_BIDDING, "Bidding completed. " + bidCount + " proposals collected.", 0);

        } catch (Exception e) {
            handleFatalError(context, e);
        }
    }

    /**
     * 异常熔断处理
     */
    private void handleFatalError(FlowContext context, Exception e) {
        LOG.error("Critical error during bidding task", e);
        String traceKey = context.getAs(Agent.KEY_CURRENT_TRACE_KEY);
        if (traceKey != null) {
            TeamTrace trace = context.getAs(traceKey);
            if (trace != null) {
                trace.setRoute(Agent.ID_END); // 故障时终止流程
                trace.addStep(Agent.ID_SYSTEM, "Bidding system failure: " + e.getMessage(), 0);
            }
        }
    }
}