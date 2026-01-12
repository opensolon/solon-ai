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
import org.noear.solon.ai.agent.AgentProfile;
import org.noear.solon.ai.agent.team.TeamAgentConfig;
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
 * 合同网协作协议 (Contract Net Protocol)
 *
 * <p>核心特征：引入竞争性招标机制。通过自动化的“标书构造器”对候选人进行多维度打分（技能、描述、模态兼容性），
 * 由 Supervisor 作为“招标方”选定最优执行者。</p>
 */
@Preview("3.8.1")
public class ContractNetProtocol extends TeamProtocolBase {
    private static final Logger LOG = LoggerFactory.getLogger(ContractNetProtocol.class);

    private static final String KEY_CONTRACT_STATE = "contract_state_obj";
    private static final String KEY_BIDDING_ROUND = "bidding_round";
    private static final String[] BIDDING_KEYWORDS = {"BIDDING", "招标", "竞标", "CALL_FOR_BIDS"};

    private final int maxBiddingRounds = 2; // 最大招标轮次限制

    /**
     * 合同状态：记录投标池与中标人
     */
    public static class ContractState {
        private final Map<String, ONode> bids = new LinkedHashMap<>();
        private String awardedAgent;

        public void addBid(String agentName, ONode bidContent) { bids.put(agentName, bidContent); }
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

    public ContractNetProtocol(TeamAgentConfig config) {
        super(config);
    }

    @Override
    public String name() { return "CONTRACT_NET"; }

    @Override
    public void buildGraph(GraphSpec spec) {
        spec.addStart(Agent.ID_START).linkAdd(Agent.ID_SUPERVISOR);

        spec.addExclusive(new SupervisorTask(config)).then(ns -> {
            // 招标路由：Supervisor 可决定进入招标阶段
            ns.linkAdd(Agent.ID_BIDDING, l -> l.when(ctx -> {
                TeamTrace trace = ctx.getAs(config.getTraceKey());
                return Agent.ID_BIDDING.equals(trace.getRoute());
            }));
            linkAgents(ns); // 普通执行路由
        }).linkAdd(Agent.ID_END);

        // 招标任务 (BiddingTask) 完成后回归 Supervisor 进行定标
        spec.addActivity(new ContractNetBiddingTask(config, this)).linkAdd(Agent.ID_SUPERVISOR);

        config.getAgentMap().values().forEach(a ->
                spec.addActivity(a).linkAdd(Agent.ID_SUPERVISOR));

        spec.addEnd(Agent.ID_END);
    }

    /**
     * 自动化标书构造器：基于专家档案 (Profile) 进行量化打分
     */
    protected ONode constructBid(Agent agent, Prompt prompt) {
        String taskDesc = prompt.getUserContent().toLowerCase();
        AgentProfile profile = agent.profile();

        int score = 60; // 基础及格分

        // 维度一：精准技能匹配 (High Weight)
        if (profile != null && !profile.getSkills().isEmpty()) {
            for (String skill : profile.getSkills()) {
                if (taskDesc.contains(skill.toLowerCase())) {
                    score += 15;
                    break;
                }
            }
        }

        // 维度二：描述语义匹配 (Medium Weight)
        if (containsAnyKeywords(taskDesc, agent.description())) {
            score += 10;
        }

        // 维度三：模态契合度校验 (Penalty/Protection)
        // 核心防御：若任务需识图但专家不支持，给予大幅扣分，强制降低其竞标优先级
        if (profile != null) {
            boolean taskNeedsImage = taskDesc.matches(".*(图|image|ui|界面|看|海报).*");
            boolean agentSupportsImage = profile.getInputModes().contains("image");
            if (taskNeedsImage && !agentSupportsImage) {
                score -= 40;
                if (LOG.isDebugEnabled()) {
                    LOG.debug("ContractNet: Agent [{}] penalized for lacking vision capability.", agent.name());
                }
            }
        }

        score = Math.max(10, Math.min(95, score));

        ONode bidNode = new ONode().asObject();
        bidNode.set("score", score);
        bidNode.set("plan", "Auto-evaluation based on [" + agent.name() + "]'s profile/skills.");
        bidNode.set("auto_bid", true);

        return bidNode;
    }

    private boolean containsAnyKeywords(String task, String desc) {
        String[] keywords = desc.split("[,，\\s]+");
        for (String kw : keywords) {
            if (kw.length() > 1 && task.contains(kw.toLowerCase())) return true;
        }
        return false;
    }

    @Override
    public void prepareSupervisorInstruction(FlowContext context, TeamTrace trace, StringBuilder sb) {
        ContractState state = (ContractState) trace.getProtocolContext().get(KEY_CONTRACT_STATE);
        boolean isZh = Locale.CHINA.getLanguage().equals(config.getLocale().getLanguage());

        sb.append(isZh ? "\n### 合同网竞标看板 (Contract Dashboard)\n" : "\n### Contract Net Dashboard\n");

        if (state != null && state.hasBids()) {
            sb.append("```json\n").append(state.toString()).append("\n```\n");
            sb.append(isZh ? "> 提示：请评估标书并指派专家。除非任务已完成，否则必须选择一名执行者。\n"
                    : "> Tips: Evaluate bids and name an agent to execute.\n");
        } else {
            sb.append(isZh ? "> 状态：待招标。\n" : "> Status: Waiting for bids.\n");
        }

        // 注入专家能力矩阵（含多模态标签）
        sb.append(isZh ? "#### 专家资质清单 (Profiles):\n" : "#### Expert Profiles:\n");
        config.getAgentMap().forEach((name, ag) -> {
            String skills = String.join(",", ag.profile().getSkills());
            String modes = String.join(",", ag.profile().getInputModes());
            sb.append("- **").append(name).append("**: ")
                    .append(isZh ? "技能[" : "Skills[").append(skills).append("], ")
                    .append(isZh ? "模式[" : "Modes[").append(modes).append("]\n");
        });
    }

    @Override
    public String resolveSupervisorRoute(FlowContext context, TeamTrace trace, String decision) {
        // 信号处理：识别招标指令
        if (isBiddingSignal(decision)) {
            Integer round = (Integer) trace.getProtocolContext().getOrDefault(KEY_BIDDING_ROUND, 0);
            if (round < maxBiddingRounds) {
                trace.getProtocolContext().put(KEY_BIDDING_ROUND, round + 1);
                return Agent.ID_BIDDING;
            }
        }

        // 隐式招标保护：若无标书却尝试指派，重定向至招标任务
        ContractState state = (ContractState) trace.getProtocolContext().get(KEY_CONTRACT_STATE);
        if (state == null || !state.hasBids()) {
            if (config.getAgentMap().containsKey(decision)) {
                if (LOG.isDebugEnabled()) {
                    LOG.debug("ContractNet: Implicit award detected without bids. Forcing Bidding phase.");
                }
                return Agent.ID_BIDDING;
            }
        }

        // 定标路由：若决策结果为具体专家，则确认中标
        if (config.getAgentMap().containsKey(decision)) {
            if (LOG.isDebugEnabled()) {
                LOG.debug("ContractNet: Awarding task to Agent [{}].", decision);
            }
            return decision;
        }

        return null;
    }

    @Override
    public void onSupervisorRouting(FlowContext context, TeamTrace trace, String nextAgent) {
        // 更新中标人状态
        if (!Agent.ID_BIDDING.equals(nextAgent) && !Agent.ID_SUPERVISOR.equals(nextAgent) && !Agent.ID_END.equals(nextAgent)) {
            ContractState state = (ContractState) trace.getProtocolContext()
                    .computeIfAbsent(KEY_CONTRACT_STATE, k -> new ContractState());
            state.setAwardedAgent(nextAgent);
        }
    }

    private boolean isBiddingSignal(String decision) {
        if (Utils.isEmpty(decision)) return false;
        String upper = decision.toUpperCase();
        return Arrays.stream(BIDDING_KEYWORDS).anyMatch(upper::contains);
    }

    @Override
    public Prompt prepareAgentPrompt(TeamTrace trace, Agent agent, Prompt originalPrompt, Locale locale) {
        // 仅在招标阶段注入投标规范
        if (Agent.ID_BIDDING.equals(trace.getRoute())) {
            boolean isZh = Locale.CHINA.getLanguage().equals(locale.getLanguage());
            StringBuilder sb = new StringBuilder();
            if (isZh) {
                sb.append("\n### 投标指令 (Proposal Instruction)\n");
                sb.append("- 请反馈 JSON 标书，包含 `plan`(方案摘要) 和 `score`(信心值 1-100)。\n");
                sb.append("- 若无法承接请回复 \"REFUSE\" 并说明理由。");
            } else {
                sb.append("\n### Proposal Instruction\n");
                sb.append("- Reply with a JSON bid containing `plan` and `score` (1-100).\n");
                sb.append("- Reply \"REFUSE\" if unsuitable.");
            }
            return originalPrompt.addMessage(sb.toString());
        }
        return originalPrompt;
    }

    @Override
    public void injectSupervisorInstruction(Locale locale, StringBuilder sb) {
        super.injectSupervisorInstruction(locale, sb);
        boolean isZh = Locale.CHINA.getLanguage().equals(locale.getLanguage());
        if (isZh) {
            sb.append("\n### CNP 定标原则：\n");
            sb.append("1. **模式优先**：识图任务必须指派 `image` 模式专家。\n");
            sb.append("2. **择优录取**：对比标书中的信心值(`score`)，择优定标。");
        } else {
            sb.append("\n### CNP Awarding Rules:\n");
            sb.append("1. **Mode Affinity**: Assign vision-capable agents for image tasks.\n");
            sb.append("2. **Highest Score**: Award the agent with the highest bidding score.");
        }
    }

    @Override
    public void onTeamFinished(FlowContext context, TeamTrace trace) {
        trace.getProtocolContext().remove(KEY_CONTRACT_STATE);
        trace.getProtocolContext().remove(KEY_BIDDING_ROUND);
        super.onTeamFinished(context, trace);
    }
}