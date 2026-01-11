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
import org.noear.solon.Utils;
import org.noear.solon.ai.agent.Agent;
import org.noear.solon.ai.agent.team.TeamConfig;
import org.noear.solon.ai.agent.team.TeamTrace;
import org.noear.solon.ai.agent.team.task.ContractNetBiddingTask;
import org.noear.solon.ai.agent.team.task.SupervisorTask;
import org.noear.solon.flow.FlowContext;
import org.noear.solon.flow.GraphSpec;
import org.noear.solon.lang.Preview;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * 合同网协作协议 (Contract Net Protocol) - 状态增强版
 * * 特点：
 * 1. 引入 ContractState 结构化看板，实现参数化竞标。
 * 2. 自动化轮次管理与承包商追踪。
 * 3. 减少 Supervisor 对非结构化文本的依赖。
 */
@Preview("3.8.1")
public class ContractNetProtocol_H extends TeamProtocolBase {
    private static final Logger LOG = LoggerFactory.getLogger(ContractNetProtocol_H.class);

    private static final String KEY_CONTRACT_STATE = "contract_state_obj";
    private static final String KEY_BIDDING_ROUND = "bidding_round";
    private static final String[] BIDDING_KEYWORDS = {"BIDDING", "招标", "竞标", "CALL_FOR_BIDS"};

    private int maxBiddingRounds = 2;
    private boolean forceInitialBidding = false;

    /**
     * 合同状态内部类：管理所有 Agent 的标书数据
     */
    public static class ContractState {
        private final Map<String, ONode> bids = new LinkedHashMap<>();
        private String awardedAgent;

        public void addBid(String agentName, String bidJson) {
            try {
                bids.put(agentName, ONode.ofJson(bidJson));
            } catch (Exception e) {
                bids.put(agentName, new ONode().set("raw_text", bidJson));
            }
        }

        public void setAwardedAgent(String agentName) { this.awardedAgent = agentName; }

        public boolean hasBids() { return !bids.isEmpty(); }

        @Override
        public String toString() {
            ONode root = new ONode().asObject();
            ONode bidsNode = root.getOrNew("all_bids");
            bids.forEach(bidsNode::set);
            root.set("awarded_agent", awardedAgent);
            return root.toJson();
        }
    }

    public ContractNetProtocol_H(TeamConfig config) {
        super(config);
    }

    @Override
    public String name() { return "CONTRACT_NET"; }

    @Override
    public void buildGraph(GraphSpec spec) {
        spec.addStart(Agent.ID_START).linkAdd(Agent.ID_SUPERVISOR);

        spec.addExclusive(new SupervisorTask(config)).then(ns -> {
            // 路由到招标节点
            ns.linkAdd(Agent.ID_BIDDING, l -> l.when(ctx -> {
                TeamTrace trace = ctx.getAs(config.getTraceKey());
                return Agent.ID_BIDDING.equals(trace.getRoute());
            }));
            linkAgents(ns);
        }).linkAdd(Agent.ID_END);

        spec.addActivity(new ContractNetBiddingTask(config)).linkAdd(Agent.ID_SUPERVISOR);
        config.getAgentMap().values().forEach(a -> spec.addActivity(a).linkAdd(Agent.ID_SUPERVISOR));
        spec.addEnd(Agent.ID_END);
    }

    @Override
    public void prepareSupervisorInstruction(FlowContext context, TeamTrace trace, StringBuilder sb) {
        ContractState state = (ContractState) trace.getProtocolContext().get(KEY_CONTRACT_STATE);
        boolean isZh = Locale.CHINA.getLanguage().equals(config.getLocale().getLanguage());

        // 1. 注入结构化状态：让 Supervisor 看到“参数对齐”后的标书
        sb.append(isZh ? "\n\n### 📄 合同网竞标看板 (Bidding State)\n" : "\n\n### 📄 Contract Bidding State\n");
        if (state != null && state.hasBids()) {
            sb.append("```json\n").append(state.toString()).append("\n```\n");
            sb.append(isZh ? "请根据各专家的能力分(score)和方案(plan)选择最合适的执行者。"
                    : "Select the best executor based on scores and plans.");
        } else {
            sb.append(isZh ? "> 尚未发起招标或暂无标书。" : "> No bids collected yet.");
        }

        // 2. 注入当前轮次信息
        Integer round = (Integer) trace.getProtocolContext().get(KEY_BIDDING_ROUND);
        if (round != null) {
            sb.append("\n").append(isZh ? "当前招标轮次: " : "Current Round: ").append(round);
        }
    }

    @Override
    public String resolveSupervisorRoute(FlowContext context, TeamTrace trace, String decision) {
        // 解析招标信号
        if (isBiddingSignal(decision)) {
            Integer round = (Integer) trace.getProtocolContext().getOrDefault(KEY_BIDDING_ROUND, 0);
            if (round < maxBiddingRounds) {
                trace.getProtocolContext().put(KEY_BIDDING_ROUND, round + 1);
                return Agent.ID_BIDDING;
            }
            LOG.warn("Max bidding rounds reached.");
        }
        return null;
    }

    @Override
    public void onSupervisorRouting(FlowContext context, TeamTrace trace, String nextAgent) {
        // 定标后更新状态
        if (!Agent.ID_BIDDING.equals(nextAgent) && !Agent.ID_SUPERVISOR.equals(nextAgent)) {
            ContractState state = (ContractState) trace.getProtocolContext().get(KEY_CONTRACT_STATE);
            if (state != null) {
                state.setAwardedAgent(nextAgent);
            }
        }
    }

    private boolean isBiddingSignal(String decision) {
        if (Utils.isEmpty(decision)) return false;
        String upper = decision.toUpperCase();
        return Arrays.stream(BIDDING_KEYWORDS).anyMatch(upper::contains);
    }

    @Override
    public void injectSupervisorInstruction(Locale locale, StringBuilder sb) {
        boolean isZh = Locale.CHINA.getLanguage().equals(locale.getLanguage());
        if (isZh) {
            sb.append("\n## 合同网(CNP)执行规范\n");
            sb.append("- **定标原则**：参考 JSON 中的 `score`。若多位专家竞争，选分数最高或方案最详尽者。\n");
            sb.append("- **流标处理**：若无合适标书，可再次回复 `BIDDING` 重新招标。");
        } else {
            sb.append("\n## CNP Execution Rules\n");
            sb.append("- **Awarding**: Refer to `score` in JSON. Pick the highest score or best plan.\n");
            sb.append("- **Re-bidding**: If no bid is suitable, reply `BIDDING` to retry.");
        }
    }

    @Override
    public void onTeamFinished(FlowContext context, TeamTrace trace) {
        trace.getProtocolContext().remove(KEY_CONTRACT_STATE);
        trace.getProtocolContext().remove(KEY_BIDDING_ROUND);
        super.onTeamFinished(context, trace);
    }
}