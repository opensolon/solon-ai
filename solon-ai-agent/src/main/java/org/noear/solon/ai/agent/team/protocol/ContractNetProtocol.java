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
import org.noear.solon.ai.agent.team.task.SupervisorTask;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.flow.FlowContext;
import org.noear.solon.flow.GraphSpec;
import org.noear.solon.lang.Preview;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * 合同网协作协议 (Contract Net Protocol) - 状态增强版
 *
 * 特点：
 * 1. 结构化竞标：通过 ContractState 看板展示参数化标书。
 * 2. 轮次治理：自动化管理招标轮次，防止无限循环。
 * 3. 定标追踪：自动记录中标人，确保任务执行的连贯性。
 *
 * @author noear
 * @since 3.8.1
 */
@Preview("3.8.1")
public class ContractNetProtocol extends TeamProtocolBase {
    private static final Logger LOG = LoggerFactory.getLogger(ContractNetProtocol.class);

    private static final String KEY_CONTRACT_STATE = "contract_state_obj";
    private static final String KEY_BIDDING_ROUND = "bidding_round";
    private static final String[] BIDDING_KEYWORDS = {"BIDDING", "招标", "竞标", "CALL_FOR_BIDS"};

    private final int maxBiddingRounds = 2;

    /**
     * 合同状态内部类
     */
    public static class ContractState {
        private final Map<String, ONode> bids = new LinkedHashMap<>();
        private String awardedAgent;

        public void addBid(String agentName, String bidJson) {
            try {
                // 使用 Snack4 加载标书
                ONode node = ONode.ofJson(bidJson);
                bids.put(agentName, node);
            } catch (Exception e) {
                // 如果非 JSON，存为 raw 文本
                bids.put(agentName, new ONode().asObject().set("raw_text", bidJson));
            }
        }

        public void setAwardedAgent(String agentName) { this.awardedAgent = agentName; }

        public boolean hasBids() { return !bids.isEmpty(); }

        @Override
        public String toString() {
            ONode root = new ONode().asObject();
            ONode bidsNode = root.getOrNew("all_bids").asObject();
            bids.forEach(bidsNode::set);
            root.set("awarded_agent", awardedAgent);
            return root.toJson();
        }
    }

    public ContractNetProtocol(TeamConfig config) {
        super(config);
    }

    @Override
    public String name() { return "CONTRACT_NET"; }

    @Override
    public void buildGraph(GraphSpec spec) {
        spec.addStart(Agent.ID_START).linkAdd(Agent.ID_SUPERVISOR);

        // Supervisor 决策分支
        spec.addExclusive(new SupervisorTask(config)).then(ns -> {
            // 1. 招标路由逻辑
            ns.linkAdd(Agent.ID_BIDDING, l -> l.when(ctx -> {
                TeamTrace trace = ctx.getAs(config.getTraceKey());
                return Agent.ID_BIDDING.equals(trace.getRoute());
            }));
            // 2. 绑定常规 Agent 路由
            linkAgents(ns);
        }).linkAdd(Agent.ID_END);

        // 招标任务执行完后回归 Supervisor 汇总标书
        spec.addActivity(new ContractNetBiddingTask(config, this)).linkAdd(Agent.ID_SUPERVISOR);

        // 普通 Agent 执行完后回归 Supervisor 汇报任务
        config.getAgentMap().values().forEach(a ->
                spec.addActivity(a).linkAdd(Agent.ID_SUPERVISOR));

        spec.addEnd(Agent.ID_END);
    }

    @Override
    public void prepareSupervisorInstruction(FlowContext context, TeamTrace trace, StringBuilder sb) {
        ContractState state = (ContractState) trace.getProtocolContext().get(KEY_CONTRACT_STATE);
        boolean isZh = Locale.CHINA.getLanguage().equals(config.getLocale().getLanguage());

        sb.append(isZh ? "\n### 合同网竞标看板 (Bidding State)\n" : "\n### Contract Bidding State\n");
        if (state != null && state.hasBids()) {
            sb.append("```json\n").append(state.toString()).append("\n```\n");
            sb.append(isZh ? "> 指示：请对比各专家标书中的 score 和 plan 进行定标。"
                    : "> Instruction: Compare score and plan in bids to award the contract.");
        } else {
            sb.append(isZh ? "> 尚未发起招标或暂无有效标书。" : "> No bids collected yet.");
        }

        Integer round = (Integer) trace.getProtocolContext().get(KEY_BIDDING_ROUND);
        if (round != null) {
            sb.append("\n").append(isZh ? "当前招标轮次: " : "Current Round: ").append(round);
        }

        sb.append("\n### 准入分析 (Admission Analysis):\n");
        config.getAgentMap().forEach((name, ag) -> {
            String skills = String.join(",", ag.profile().getSkills());
            sb.append("- ").append(name).append(": [").append(skills).append("]\n");
        });
    }

    @Override
    public String resolveSupervisorRoute(FlowContext context, TeamTrace trace, String decision) {
        if (isBiddingSignal(decision)) {
            Integer round = (Integer) trace.getProtocolContext().getOrDefault(KEY_BIDDING_ROUND, 0);
            if (round < maxBiddingRounds) {
                trace.getProtocolContext().put(KEY_BIDDING_ROUND, round + 1);
                return Agent.ID_BIDDING;
            }
            LOG.warn("ContractNet - Max bidding rounds reached.");
        }
        return null;
    }

    @Override
    public void onSupervisorRouting(FlowContext context, TeamTrace trace, String nextAgent) {
        // 定标逻辑：如果转向了一个具体的 Agent，则将其标记为中标人
        if (!Agent.ID_BIDDING.equals(nextAgent) && !Agent.ID_SUPERVISOR.equals(nextAgent) && !Agent.ID_END.equals(nextAgent)) {
            ContractState state = (ContractState) trace.getProtocolContext()
                    .computeIfAbsent(KEY_CONTRACT_STATE, k -> new ContractState());
            state.setAwardedAgent(nextAgent);
            LOG.debug("ContractNet - Awarded to: {}", nextAgent);
        }
    }

    private boolean isBiddingSignal(String decision) {
        if (Utils.isEmpty(decision)) return false;
        String upper = decision.toUpperCase();
        return Arrays.stream(BIDDING_KEYWORDS).anyMatch(upper::contains);
    }

    @Override
    public Prompt prepareAgentPrompt(TeamTrace trace, Agent agent, Prompt originalPrompt, Locale locale) {
        // 只有当路由目标指向 ID_BIDDING 时，才注入竞标引导语
        if (Agent.ID_BIDDING.equals(trace.getRoute())) {
            boolean isZh = Locale.CHINA.getLanguage().equals(locale.getLanguage());
            StringBuilder sb = new StringBuilder();

            if (isZh) {
                sb.append("### 竞标要求 (Bidding Requirements)\n");
                sb.append("- 请提供你的执行方案(plan)和预估评分(score: 1-100)。\n");
                sb.append("- **资质核验**：请务必核对你的“擅长技能(Skills)”是否匹配需求。\n");
                sb.append("- **弃标原则**：如果任务超出你的技能范围或违反“行为约束(Constraints)”，请回复“REFUSE”并说明理由。\n");
            } else {
                sb.append("### Bidding Requirements\n");
                sb.append("- Provide your plan and estimated score (1-100).\n");
                sb.append("- **Skills Check**: Ensure the task matches your 'Skills'.\n");
                sb.append("- **Refusal**: If it exceeds your skills or violates 'Constraints', reply 'REFUSE' with a reason.\n");
            }
            sb.append("\n---\n");

            // 1. 创建一个新的 Prompt 实例
            Prompt newPrompt = new Prompt();
            // 2. 首先添加引导语（作为 User 消息插入头部）
            newPrompt.addMessage(sb.toString());
            // 3. 将原始 Prompt 里的所有消息追加到后面
            if (originalPrompt != null && originalPrompt.getMessages() != null) {
                newPrompt.addMessage(originalPrompt.getMessages());
            }

            return newPrompt;
        }

        return originalPrompt;
    }

    @Override
    public void injectSupervisorInstruction(Locale locale, StringBuilder sb) {
        super.injectSupervisorInstruction(locale, sb);
        boolean isZh = Locale.CHINA.getLanguage().equals(locale.getLanguage());
        if (isZh) {
            sb.append("\n### 合同网定标守则：\n");
            sb.append("1. **资质核验**：定标前请务必核对中标人的“擅长技能(Skills)”与任务的匹配度。\n");
            sb.append("2. **约束合规**：严禁将任务授予标书中承诺了“行为约束(Constraints)”禁止事项的专家。\n");
            sb.append("3. **方案择优**：在资质合格前提下，优先选择方案(plan)详尽且得分(score)高的标书。");
        } else {
            sb.append("\n### CNP Awarding Rules:\n");
            sb.append("1. **Qualification Check**: Verify the winner's 'Skills' match the task requirements.\n");
            sb.append("2. **Constraint Compliance**: Ensure the proposed plan does not violate the winner's 'Constraints'.\n");
            sb.append("3. **Best Proposal**: Prefer bids with higher scores and detailed plans among qualified candidates.");
        }
    }

    @Override
    public String resolveAgentOutput(TeamTrace trace, Agent agent, String rawContent) {
        // ContractNet 的主要执行者是中标后的 Agent。
        // 这里可以检查其输出是否符合“任务完成”的预期
        return rawContent;
    }

    @Override
    public void onTeamFinished(FlowContext context, TeamTrace trace) {
        trace.getProtocolContext().remove(KEY_CONTRACT_STATE);
        trace.getProtocolContext().remove(KEY_BIDDING_ROUND);
        super.onTeamFinished(context, trace);
    }
}