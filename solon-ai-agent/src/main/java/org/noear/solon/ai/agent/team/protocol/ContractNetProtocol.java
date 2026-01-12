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
 * 合同网协作协议 (Contract Net Protocol) - 强化适配版
 *
 * 优化点：
 * 1. 竞标看板增强：不仅展示技能(Skills)，还展示多模态能力(Modes)，防止盲目定标。
 * 2. 标书规范引导：在 Prompt 层面强制 Agent 输出符合 sniffJson 解析的结构化标书。
 * 3. 轮次与状态治理：严格控制招标轮次，确保任务流不陷入死循环。
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

        public void addBid(String agentName, String bidContent, TeamProtocolBase protocol) {
            // 使用基类的 sniffJson 提取 Agent 回复中的 JSON 标书
            ONode node = protocol.sniffJson(bidContent);
            if (node.isObject() && !node.isEmpty()) {
                bids.put(agentName, node);
            } else {
                // 如果提取失败，记录原始摘要
                String summary = bidContent.length() > 100 ? bidContent.substring(0, 100) + "..." : bidContent;
                bids.put(agentName, new ONode().asObject().set("raw_summary", summary));
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

        spec.addExclusive(new SupervisorTask(config)).then(ns -> {
            // 1. 招标路由逻辑：由 Supervisor 决策是否进入招标
            ns.linkAdd(Agent.ID_BIDDING, l -> l.when(ctx -> {
                TeamTrace trace = ctx.getAs(config.getTraceKey());
                return Agent.ID_BIDDING.equals(trace.getRoute());
            }));
            // 2. 绑定常规执行 Agent 路由
            linkAgents(ns);
        }).linkAdd(Agent.ID_END);

        // 招标任务执行完后回归 Supervisor
        spec.addActivity(new ContractNetBiddingTask(config, this)).linkAdd(Agent.ID_SUPERVISOR);

        // 普通专家执行完后回归 Supervisor
        config.getAgentMap().values().forEach(a ->
                spec.addActivity(a).linkAdd(Agent.ID_SUPERVISOR));

        spec.addEnd(Agent.ID_END);
    }

    /**
     * 根据 Agent 档案自动构造标书 (无需 Agent 介入)
     * 优化点：引入权重差异，使 Profile 匹配优先于 Description 匹配
     */
    protected String constructBid(Agent agent, Prompt prompt) {
        // 1. 获取任务描述（用户输入）
        String taskDesc = prompt.getUserContent().toLowerCase();
        AgentProfile profile = agent.profile();

        // 2. 初始分数
        int score = 60; // 基础及格分

        // 3. 维度一：Profile Skills 精准匹配 (权重：高)
        // 命中一个 Skill +15 分，代表该专家有明确的技能认证
        if (profile != null && !profile.getSkills().isEmpty()) {
            for (String skill : profile.getSkills()) {
                if (taskDesc.contains(skill.toLowerCase())) {
                    score += 15;
                    break; // 命中核心技能即可显著提升竞争力
                }
            }
        }

        // 4. 维度二：Description 模糊语义匹配 (权重：中)
        // 如果没有 Profile 或 Profile 未命中，尝试从职责描述中寻找关键词 (+10 分)
        // 此时 score 如果已经是 75(60+15)，命中描述后可达 85；若仅命中描述则为 70
        if (containsAnyKeywords(taskDesc, agent.description())) {
            score += 10;
        }

        // 5. 维度三：模态契合度校验 (权重：惩罚项)
        // 如果任务涉及图片但专家不支持，大幅扣分，防止误指派
        if (profile != null) {
            boolean taskNeedsImage = taskDesc.matches(".*(图|image|ui|界面|看|海报).*");
            boolean agentSupportsImage = profile.getInputModes().contains("image");
            if (taskNeedsImage && !agentSupportsImage) {
                score -= 40;
            }
        }

        // 6. 最终得分约束 (10-95分之间，留出 5 分给 LLM 的主观偏好)
        score = Math.max(10, Math.min(95, score));

        // 7. 构造结构化标书
        ONode bidNode = new ONode().asObject();
        bidNode.set("score", score);
        bidNode.set("plan", "自动方案评估：基于[" + agent.name() + "]的职能描述与技能标签进行自适应匹配。");
        bidNode.set("auto_bid", true);

        return bidNode.toJson();
    }

    /**
     * 辅助方法：简单的关键词交叉验证
     */
    private boolean containsAnyKeywords(String task, String desc) {
        // 简单的逻辑：将描述按逗号/空格拆分，看有没有命中任务文本
        String[] keywords = desc.split("[,，\\s]+");
        for (String kw : keywords) {
            if (kw.length() > 1 && task.contains(kw.toLowerCase())) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void prepareSupervisorInstruction(FlowContext context, TeamTrace trace, StringBuilder sb) {
        ContractState state = (ContractState) trace.getProtocolContext().get(KEY_CONTRACT_STATE);
        boolean isZh = Locale.CHINA.getLanguage().equals(config.getLocale().getLanguage());

        sb.append(isZh ? "\n### 合同网竞标看板 (Contract Dashboard)\n" : "\n### Contract Net Dashboard\n");

        // 展示当前的标书池
        if (state != null && state.hasBids()) {
            sb.append("```json\n").append(state.toString()).append("\n```\n");
            sb.append(isZh ? "> 提示：请评估标书。**除非任务已由专家完成，否则必须指派一名专家名以开始执行。**\n"
                    : "> Tips: Evaluate bids. **You MUST name an agent to execute the task unless it's already finished.**\n");
        } else {
            sb.append(isZh ? "> 状态：尚未发起招标。\n" : "> Status: No bids collected yet.\n");
        }

        // 展示专家能力矩阵（含多模态信息）
        sb.append(isZh ? "#### 专家资质清单 (Expert Profiles):\n" : "#### Expert Profiles:\n");
        config.getAgentMap().forEach((name, ag) -> {
            String skills = String.join(",", ag.profile().getSkills());
            String modes = String.join(",", ag.profile().getInputModes());
            sb.append("- **").append(name).append("**: ")
                    .append(isZh ? "技能[" : "Skills[") .append(skills).append("], ")
                    .append(isZh ? "支持模式[" : "Modes[").append(modes).append("]\n");
        });

        Integer round = (Integer) trace.getProtocolContext().get(KEY_BIDDING_ROUND);
        if (round != null) {
            sb.append("\n> ").append(isZh ? "招标轮次: " : "Bidding Round: ").append(round);
        }
    }

    @Override
    public String resolveSupervisorRoute(FlowContext context, TeamTrace trace, String decision) {
        // A. 显式信号优先（招标阶段）
        if (isBiddingSignal(decision)) {
            Integer round = (Integer) trace.getProtocolContext().getOrDefault(KEY_BIDDING_ROUND, 0);
            if (round < maxBiddingRounds) {
                trace.getProtocolContext().put(KEY_BIDDING_ROUND, round + 1);
                return Agent.ID_BIDDING;
            }
        }

        // B. 隐式保护：如果没有标书，拦截并强制招标
        ContractState state = (ContractState) trace.getProtocolContext().get(KEY_CONTRACT_STATE);
        if (state == null || !state.hasBids()) {
            if (config.getAgentMap().containsKey(decision)) {
                LOG.info("ContractNet - Implicit award detected without bids. Redirecting to BiddingTask first.");
                return Agent.ID_BIDDING;
            }
        }

        // C. 定标保护（关键修复点）：
        // 如果 LLM 输出了专家名字，且该名字在团队成员中，强制返回该名字作为路由目标
        // 这样可以防止 SupervisorTask 误以为任务结束而走向 [end]
        if (config.getAgentMap().containsKey(decision)) {
            LOG.info("ContractNet - Awarding task to: {}", decision);
            return decision;
        }

        return null; // 只有当既不是招标也不是指派专家时，才允许走默认逻辑（如结束）
    }

    @Override
    public void onSupervisorRouting(FlowContext context, TeamTrace trace, String nextAgent) {
        // 定标：如果 Supervisor 决定去向某个具体执行 Agent，则更新中标人状态
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
        // 只有在招标路由(ID_BIDDING)下，才注入竞标规范
        if (Agent.ID_BIDDING.equals(trace.getRoute())) {
            boolean isZh = Locale.CHINA.getLanguage().equals(locale.getLanguage());
            StringBuilder sb = new StringBuilder();

            if (isZh) {
                sb.append("\n### 竞标邀请 (Call for Proposals)\n");
                sb.append("- 请根据你的 **Skills** 和 **Constraints** 评估任务。\n");
                sb.append("- **回复格式**：请务必提供 JSON 格式的标书，包含 `plan`(方案) 和 `score`(信心值1-100)。\n");
                sb.append("- **示例**：`{\"plan\": \"我会用...处理\", \"score\": 90}`\n");
                sb.append("- 如果无法执行，请回复 \"REFUSE\" 并说明理由。");
            } else {
                sb.append("\n### Call for Proposals\n");
                sb.append("- Evaluate based on your **Skills** and **Constraints**.\n");
                sb.append("- **Format**: Provide a JSON bid with `plan` and `score` (1-100).\n");
                sb.append("- **Example**: `{\"plan\": \"I will use...\", \"score\": 90}`\n");
                sb.append("- If unsuitable, reply \"REFUSE\" with reasons.");
            }

            // 将引导语注入 originalPrompt 的消息列表末尾，以获得最强权重
            return originalPrompt.addMessage(sb.toString());
        }

        return originalPrompt;
    }

    @Override
    public void injectSupervisorInstruction(Locale locale, StringBuilder sb) {
        super.injectSupervisorInstruction(locale, sb);
        boolean isZh = Locale.CHINA.getLanguage().equals(locale.getLanguage());
        if (isZh) {
            sb.append("\n### 合同网(CNP)定标守则：\n");
            sb.append("1. **能力匹配**：优先将含图任务授予支持 `image` 模式的专家。\n");
            sb.append("2. **方案择优**：对比标书中的 `plan`，指派方案最详尽、信心值(`score`)最高的专家。\n");
            sb.append("3. **拒绝处理**：若专家回复 `REFUSE`，请在后续指令中更换招标范围。");
        } else {
            sb.append("\n### CNP Awarding Rules:\n");
            sb.append("1. **Modality Check**: Prioritize vision-capable agents for image-based tasks.\n");
            sb.append("2. **Best Bid**: Award the agent with the most detailed `plan` and highest `score`.\n");
            sb.append("3. **Refusal Handling**: If an agent replies `REFUSE`, adjust the next bidding scope.");
        }
    }

    @Override
    public void onTeamFinished(FlowContext context, TeamTrace trace) {
        trace.getProtocolContext().remove(KEY_CONTRACT_STATE);
        trace.getProtocolContext().remove(KEY_BIDDING_ROUND);
        super.onTeamFinished(context, trace);
    }
}