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
import org.noear.solon.ai.agent.team.TeamAgentConfig;
import org.noear.solon.ai.agent.team.TeamTrace;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.chat.tool.FunctionTool;
import org.noear.solon.ai.chat.tool.FunctionToolDesc;
import org.noear.solon.flow.FlowContext;
import org.noear.solon.flow.GraphSpec;
import org.noear.solon.lang.Preview;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.function.Consumer;

/**
 * 合同网协作协议 (Contract Net Protocol)
 *
 * <p>核心特征：引入竞争性招标机制。支持 Supervisor 显式招标工具与 Agent 自主投标工具，配合算法自动打分兜底。</p>
 */

@Preview("3.8.1")
public class ContractNetProtocol extends TeamProtocolBase {
    private static final Logger LOG = LoggerFactory.getLogger(ContractNetProtocol.class);

    private static final String KEY_CONTRACT_STATE = "contract_state_obj";
    private static final String KEY_BIDDING_ROUND = "bidding_round";

    // 工具常量定义
    private static final String TOOL_CALL_BIDS = "__call_for_bids__";
    private static final String TOOL_SUBMIT_BID = "__submit_proposal__";

    private final int maxBiddingRounds = 2;

    public static class ContractState {
        private final Map<String, ONode> bids = new LinkedHashMap<>();
        private String awardedAgent;

        public void addBid(String agentName, ONode bidContent) { bids.put(agentName, bidContent); }
        public void setAwardedAgent(String agentName) { this.awardedAgent = agentName; }
        public boolean hasBids() { return !bids.isEmpty(); }
        public boolean hasAgentBid(String name) { return bids.containsKey(name); }

        @Override
        public String toString() {
            ONode root = new ONode().asObject();
            ONode bidsNode = root.getOrNew("all_bids").asObject();
            bids.forEach(bidsNode::set);
            root.set("awarded_agent", awardedAgent);
            return root.toJson();
        }
    }

    public ContractState getContractState(TeamTrace trace){
      return  (ContractNetProtocol.ContractState) trace.getProtocolContext()
                .computeIfAbsent(ContractNetProtocol.KEY_CONTRACT_STATE, k -> new ContractNetProtocol.ContractState());

    }

    public ContractNetProtocol(TeamAgentConfig config) { super(config); }

    @Override
    public String name() { return "CONTRACT_NET"; }

    @Override
    public void buildGraph(GraphSpec spec) {
        spec.addStart(Agent.ID_START).linkAdd(Agent.ID_SUPERVISOR);

        spec.addExclusive(new SupervisorTask(config)).then(ns -> {
            // 路由控制：优先处理来自工具或决策的招标请求
            ns.linkAdd(Agent.ID_BIDDING, l -> l.when(ctx -> {
                TeamTrace trace = ctx.getAs(config.getTraceKey());
                return Agent.ID_BIDDING.equals(trace.getRoute());
            }));
            linkAgents(ns);
        }).linkAdd(Agent.ID_END);

        spec.addActivity(new ContractNetBiddingTask(config, this)).linkAdd(Agent.ID_SUPERVISOR);
        config.getAgentMap().values().forEach(a -> spec.addActivity(a).linkAdd(Agent.ID_SUPERVISOR));
        spec.addEnd(Agent.ID_END);
    }

    @Override
    public void injectSupervisorTools(FlowContext context, Consumer<FunctionTool> receiver) {
        boolean isZh = Locale.CHINA.getLanguage().equals(config.getLocale().getLanguage());
        FunctionToolDesc tool = new FunctionToolDesc(TOOL_CALL_BIDS);
        if (isZh) {
            tool.title("发起招标").description("向全体专家征集方案。当你无法确定由谁执行任务时使用此工具。")
                    .stringParamAdd("requirement", "任务具体需求");
        } else {
            tool.title("Call for Bids").description("Collect proposals from all experts when the best executor is not obvious.")
                    .stringParamAdd("requirement", "Task requirements");
        }
        tool.doHandle(args -> {
            if (LOG.isDebugEnabled()) {
                LOG.debug("ContractNet: Supervisor initiated bidding tool. Requirement: {}", args.get("requirement"));
            }

            TeamTrace trace = context.getAs(Agent.KEY_CURRENT_TEAM_KEY);
            if(trace != null) trace.setRoute(Agent.ID_BIDDING);
            return isZh ? "已进入招标流程。" : "Bidding phase initiated.";
        });
        receiver.accept(tool);
    }

    @Override
    public void injectAgentTools(FlowContext context, Agent agent, Consumer<FunctionTool> receiver) {
        TeamTrace trace = context.getAs(Agent.KEY_CURRENT_TEAM_KEY);

        if (trace != null && Agent.ID_BIDDING.equals(trace.getRoute())) {
            FunctionToolDesc tool = new FunctionToolDesc(TOOL_SUBMIT_BID).returnDirect(true);
            boolean isZh = Locale.CHINA.getLanguage().equals(config.getLocale().getLanguage());

            if (isZh) {
                tool.title("提交投标").description("提交你的执行计划和信心分。")
                        .intParamAdd("score", "信心分(1-100)")
                        .stringParamAdd("plan", "计划摘要");
            } else {
                tool.title("Submit Bid").description("Submit your execution plan and confidence score.")
                        .intParamAdd("score", "Confidence score(1-100)")
                        .stringParamAdd("plan", "Plan summary");
            }

            tool.doHandle(args -> {
                if (LOG.isDebugEnabled()) {
                    LOG.debug("ContractNet: Agent [{}] submitted a manual bid via tool. Score: {}", agent.name(), args.get("score"));
                }

                ContractState state = getContractState(trace);
                ONode bid = new ONode().asObject();
                bid.set("score", args.get("score"));
                bid.set("plan", args.get("plan"));
                bid.set("auto_bid", false);
                state.addBid(agent.name(), bid);
                return isZh ? "标书已收录。" : "Bid received.";
            });

            receiver.accept(tool);
        }
    }

    @Override
    public String resolveSupervisorRoute(FlowContext context, TeamTrace trace, String decision) {
        if (Agent.ID_BIDDING.equals(trace.getRoute())) {
            return Agent.ID_BIDDING;
        }

        if (Utils.isNotEmpty(decision)) {
            String upper = decision.toUpperCase();
            if (upper.contains("BIDDING") || upper.contains("招标") || upper.contains("CALL_FOR_BIDS")) {
                Integer round = (Integer) trace.getProtocolContext().getOrDefault(KEY_BIDDING_ROUND, 0);
                if (round < maxBiddingRounds) {
                    trace.getProtocolContext().put(KEY_BIDDING_ROUND, round + 1);
                    return Agent.ID_BIDDING;
                }
            }
        }

        if (config.getAgentMap().containsKey(decision)) {
            ContractState state = (ContractState) trace.getProtocolContext().get(KEY_CONTRACT_STATE);
            if (state == null || !state.hasBids()) {
                if (LOG.isDebugEnabled()) {
                    LOG.debug("ContractNet: Agent [{}] attempted to assign '{}' without bidding. Forcing bidding phase.",
                            config.getName(), decision);
                }
                return Agent.ID_BIDDING;
            }
            return decision;
        }

        return null;
    }

    @Override
    public void prepareSupervisorInstruction(FlowContext context, TeamTrace trace, StringBuilder sb) {
        ContractState state = (ContractState) trace.getProtocolContext().get(KEY_CONTRACT_STATE);
        boolean isZh = Locale.CHINA.getLanguage().equals(config.getLocale().getLanguage());

        sb.append(isZh ? "\n### 合同网竞标看板\n" : "\n### Contract Net Dashboard\n");
        if (state != null && state.hasBids()) {
            sb.append("```json\n").append(state.toString()).append("\n```\n");
            sb.append(isZh ? "> 提示：请根据各专家的 `score` 和 `plan` 择优指派。 \n"
                    : "> Tips: Award the task to the best candidate based on `score` and `plan`.\n");
        } else {
            if (isZh) {
                sb.append("> **强制规范**：当前处于“待招标”状态。在进行任何指派前，**必须**先获取专家标书。\n");
                sb.append("> **操作建议**：请调用 `").append(TOOL_CALL_BIDS).append("` 工具发起招标，或直接进入招标环节。\n");
            } else {
                sb.append("> **Mandatory**: Status is 'Waiting'. You **MUST** collect proposals before any assignment.\n");
                sb.append("> **Action**: Please call `").append(TOOL_CALL_BIDS).append("` to initiate bidding.\n");
            }
        }

        sb.append(isZh ? "#### 成员资质表:\n" : "#### Agent Profiles:\n");
        config.getAgentMap().forEach((name, ag) -> {
            sb.append("- **").append(name).append("** (")
                    .append(ag.description()).append("): ")
                    .append("Skills").append(ag.profile().getSkills())
                    .append(", Modes").append(ag.profile().getInputModes())
                    .append("\n");
        });
    }

    @Override
    public void injectSupervisorInstruction(Locale locale, StringBuilder sb) {
        super.injectSupervisorInstruction(locale, sb);
        boolean isZh = Locale.CHINA.getLanguage().equals(locale.getLanguage());
        if (isZh) {
            sb.append("\n### CNP 协作规范：\n");
            sb.append("1. **工具招标**：若无法确定最佳人选，优先调用 `").append(TOOL_CALL_BIDS).append("`。\n");
            sb.append("2. **择优录取**：对比看板中的标书分数，直接回复专家名称进行定标。\n");
            sb.append("3. **模态检查**：请确保所选专家的 `Modes` 支持当前任务模态（如识图需具备 `image`）。");
        } else {
            sb.append("\n### CNP Collaboration Rules:\n");
            sb.append("1. **Tool Usage**: Call `").append(TOOL_CALL_BIDS).append("` if unsure about assignments.\n");
            sb.append("2. **Decision**: Award the task by naming the best agent based on their bid score.\n");
            sb.append("3. **Constraint**: Match task requirements with agent `Modes` (e.g., `image` for vision tasks).");
        }
    }

    @Override
    public void onTeamFinished(FlowContext context, TeamTrace trace) {
        // 清理协议状态，防止长上下文干扰
        trace.getProtocolContext().remove(KEY_CONTRACT_STATE);
        trace.getProtocolContext().remove(KEY_BIDDING_ROUND);
        super.onTeamFinished(context, trace);
    }

    /**
     * 算法兜底打分：基于关键词匹配
     */
    protected ONode constructBid(Agent agent, Prompt prompt) {
        String taskDesc = prompt.getUserContent().toLowerCase();
        int score = 60;

        if (agent.profile() != null) {
            for (String skill : agent.profile().getSkills()) {
                if (taskDesc.contains(skill.toLowerCase())) {
                    score += 15;
                    break;
                }
            }
        }

        ONode bidNode = new ONode().asObject();
        bidNode.set("score", Math.min(95, score));
        bidNode.set("plan", "Profile-based auto evaluation.");
        bidNode.set("auto_bid", true);
        return bidNode;
    }
}