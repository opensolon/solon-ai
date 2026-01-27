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
import org.noear.solon.ai.agent.team.TeamAgent;
import org.noear.solon.ai.agent.team.TeamAgentConfig;
import org.noear.solon.ai.agent.team.TeamTrace;
import org.noear.solon.ai.agent.team.task.SupervisorTask;
import org.noear.solon.ai.chat.ChatRole;
import org.noear.solon.ai.chat.message.ChatMessage;
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
    public final static String ID_BIDDING = "bidding";

    private static final Logger LOG = LoggerFactory.getLogger(ContractNetProtocol.class);

    private static final String KEY_CONTRACT_STATE = "contract_state_obj";

    // 工具常量定义
    private static final String TOOL_CALL_BIDS = "__call_for_bids__";
    private static final String TOOL_SUBMIT_BID = "__submit_proposal__";

    private final int maxBiddingRounds = 2;

    public static class ContractState {
        private final Map<String, ONode> bids = new LinkedHashMap<>();
        private final List<Map<String, ONode>> history = new ArrayList<>();
        private String awardedAgent;
        private int rounds = 0;

        public void archiveCurrentRounds() {
            if (!bids.isEmpty()) {
                history.add(new LinkedHashMap<>(bids));
            }
        }

        public void incrementRound() { this.rounds++; }
        public int getRounds() { return this.rounds; }

        public void addBid(String agentName, ONode bidContent) { bids.put(agentName, bidContent); }
        public void setAwardedAgent(String agentName) { this.awardedAgent = agentName; }

        // --- 补全 Getter 方法 ---
        public Map<String, ONode> getBids() { return bids; }
        public String getAwardedAgent() { return awardedAgent; }

        public boolean hasBids() { return !bids.isEmpty(); }
        public boolean hasAgentBid(String name) { return bids.containsKey(name); }

        @Override
        public String toString() {
            ONode root = new ONode().asObject();
            String phase = (awardedAgent != null) ? "AWARDED" : (bids.isEmpty() ? "WAITING_FOR_BIDS" : "EVALUATING");
            root.set("phase", phase);
            root.set("current_round", this.rounds); // 关键：让 LLM 知道当前轮次
            root.set("history_count", this.history.size());
            root.set("winner", this.awardedAgent == null ? "none" : this.awardedAgent); // <--- 关键：显式输出 winner

            ONode bidsNode = root.getOrNew("bids_board").asObject();
            bids.forEach((name, content) -> {
                ONode item = bidsNode.getOrNew(name);
                item.set("score", content.get("score"));
                item.set("plan", content.get("plan"));
                // 语义化 source，强调数据来源（Metadata 匹配 vs 主动投标）
                boolean isAuto = content.get("auto_bid").getBoolean();
                item.set("source", isAuto ? "Capability_Match" : "Expert_Proposal");
            });
            return root.toJson();
        }
    }

    public ContractState getContractState(TeamTrace trace){
      return  (ContractNetProtocol.ContractState) trace.getProtocolContext()
                .computeIfAbsent(ContractNetProtocol.KEY_CONTRACT_STATE, k -> new ContractNetProtocol.ContractState());

    }

    public void startNewBidding(TeamTrace trace) {
        ContractState state = getContractState(trace);
        state.archiveCurrentRounds(); // 先存档，再清空
        state.getBids().clear();
        state.setAwardedAgent(null);
        state.incrementRound();
    }

    public ContractNetProtocol(TeamAgentConfig config) { super(config); }

    @Override
    public String name() { return "CONTRACT_NET"; }

    @Override
    public void buildGraph(GraphSpec spec) {
        spec.addStart(Agent.ID_START).linkAdd(TeamAgent.ID_SUPERVISOR);

        spec.addExclusive(new SupervisorTask(config)).then(ns -> {
            // 路由控制：优先处理来自工具或决策的招标请求
            ns.linkAdd(ID_BIDDING, l -> l.when(ctx -> {
                TeamTrace trace = ctx.getAs(config.getTraceKey());
                return ID_BIDDING.equals(trace.getRoute());
            }));
            linkAgents(ns);
        }).linkAdd(Agent.ID_END);

        spec.addActivity(new ContractNetBiddingTask(config, this)).linkAdd(TeamAgent.ID_SUPERVISOR);
        config.getAgentMap().values().forEach(a -> spec.addActivity(a).linkAdd(TeamAgent.ID_SUPERVISOR));
        spec.addEnd(Agent.ID_END);
    }


    @Override
    public void injectAgentTools(FlowContext context, Agent agent, Consumer<FunctionTool> receiver) {
        String traceKey = context.getAs(Agent.KEY_CURRENT_TEAM_TRACE_KEY);
        TeamTrace trace = (traceKey != null) ? context.getAs(traceKey) : null;
        if (trace == null) return;

        if (ID_BIDDING.equals(trace.getRoute())) {
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
    public Prompt prepareAgentPrompt(TeamTrace trace, Agent agent, Prompt originalPrompt, Locale locale) {
        // 1. 调用父类基础处理
        Prompt finalPrompt = super.prepareAgentPrompt(trace, agent, originalPrompt, locale);

        // 2. 【上下文穿透核心】：注入中标方案
        ContractState state = getContractState(trace);
        if (agent.name().equals(state.getAwardedAgent())) {
            ONode bid = state.getBids().get(agent.name());
            if (bid != null) {
                boolean isZh = Locale.CHINA.getLanguage().equals(config.getLocale().getLanguage());
                String plan = bid.get("plan").getString();

                // 构建通知消息
                String awardNotice = isZh
                        ? "\n### 中标执行指令\n> 你已在竞标中胜出。请严格按你提交的计划执行：\n" + plan
                        : "\n### Award Execution Order\n> You won the bid. Execute strictly according to your plan:\n" + plan;

                // 将此指令作为 System 消息追加，确保 Agent 的 ReAct 逻辑以此为准
                return finalPrompt.addMessage(ChatMessage.ofSystem(awardNotice));
            }
        }

        return finalPrompt;
    }

    @Override
    public void injectSupervisorTools(FlowContext context, Consumer<FunctionTool> receiver) {
        String traceKey = context.getAs(Agent.KEY_CURRENT_TEAM_TRACE_KEY);
        TeamTrace trace = (traceKey != null) ? context.getAs(traceKey) : null;
        if (trace == null) return;

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

            startNewBidding(trace);
            trace.setRoute(ID_BIDDING);

            return isZh ? "已进入招标流程。" : "Bidding phase initiated.";
        });

        receiver.accept(tool);
    }


    @Override
    public String resolveSupervisorRoute(FlowContext context, TeamTrace trace, String decision) {
        // 1. 检查当前是否处于“正在招标”路由中
        if (ID_BIDDING.equals(trace.getRoute())) {
            return ID_BIDDING;
        }

        // 2. 处理显式的招标指令 (来自工具或 LLM 文本)
        if (Utils.isNotEmpty(decision)) {
            String upper = decision.toUpperCase();
            if (upper.contains("BIDDING") || upper.contains("招标") || upper.contains("CALL_FOR_BIDS")) {
                ContractState state = getContractState(trace);
                // 如果次数还没用完，允许招标；否则拦截
                if (state.getRounds() < maxBiddingRounds) {
                    return ID_BIDDING;
                } else {
                    LOG.warn("ContractNet: Bidding rounds exhausted. Ignoring bidding request.");
                    return null; // 迫使 Supervisor 留在当前节点思考备选方案
                }
            }
        }

        // 3. 【路由守卫核心】：检查指派合规性
        if (config.getAgentMap().containsKey(decision)) {
            ContractState state = getContractState(trace);
            boolean isZh = Locale.CHINA.getLanguage().equals(config.getLocale().getLanguage());

            // 如果没有标书，或者指派的人根本没投标，强制进入招标环节
            if (state == null || !state.hasAgentBid(decision)) {
                if (LOG.isWarnEnabled()) {
                    LOG.warn("ContractNet: Compliance violation! Supervisor tried to assign '{}' without bidding.", decision);
                }

                // 注入明确的系统指令，防止模型陷入死循环
                String warnMsg = isZh ? "【系统指引】指派无效。必须先调用 " + TOOL_CALL_BIDS + " 获取标书，且只能指派已出现在 bids_board 中的专家。"
                        : "[System] Invalid assignment. You must call " + TOOL_CALL_BIDS + " first and only assign experts listed in bids_board.";
                trace.addRecord(ChatRole.SYSTEM, ID_BIDDING, warnMsg, 0);
                return ID_BIDDING;
            }

            // 记录中标者，用于后续的“上下文穿透”
            state.setAwardedAgent(decision);
            return decision;
        }

        return null;
    }

    @Override
    public void prepareSupervisorInstruction(FlowContext context, TeamTrace trace, StringBuilder sb) {
        ContractState state = getContractState(trace);
        boolean isZh = Locale.CHINA.getLanguage().equals(config.getLocale().getLanguage());

        int remaining = maxBiddingRounds - state.getRounds();

        if (state.getRounds() > 0 && !state.hasBids()) {
            if (remaining > 0) {
                sb.append(isZh ? "\n> [警告] 前一轮招标无人响应。剩余尝试次数: " + remaining
                        : "\n> [Warning] No bids in previous round. Remaining attempts: " + remaining);
            } else {
                // 方案 A 的硬约束：次数用光了
                sb.append(isZh ? "\n> [最终警告] 招标次数已耗尽。请停止招标，直接回复用户：该任务超出了当前团队的能力范围。"
                        : "\n> [Final Warning] No more bidding rounds. Stop bidding and inform the user that the task is out of scope.");
                // 这里可以配合路由守卫，在 resolveSupervisorRoute 里彻底封死 ID_BIDDING 路由
            }
        }

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
            sb.append("- ").append(ag.toMetadata(context).toJson()).append("\n");
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

    /**
     * 算法兜底打分：基于关键词匹配
     */
    protected ONode constructBid(Agent agent, Prompt prompt) {
        String taskDesc = prompt.getUserContent().toLowerCase();

        //如果专家名下没有任何能力描述，给一个较低的保底分，促使 Supervisor 寻找更合适的专家
        int score = (agent.profile() != null && !agent.profile().getCapabilities().isEmpty()) ? 60 : 30;

        if (agent.profile() != null) {
            for (String skill : agent.profile().getCapabilities()) {
                if (taskDesc.contains(skill.toLowerCase())) {
                    score += 15;
                    break;
                }
            }
        }

        String autoPlan = "Auto-matched by capabilities: " + agent.profile().getCapabilities();

        ONode bidNode = new ONode().asObject();
        bidNode.set("score", Math.min(95, score));
        bidNode.set("plan", autoPlan);
        bidNode.set("auto_bid", true);
        return bidNode;
    }

    @Override
    protected boolean isLogicFinished(TeamTrace trace) {
        // 如果记录为空，肯定没结束
        if (trace.getRecords().isEmpty()) return false;

        // 方案 A：极致简单，直接信任 Manager 的 [FINISH] 信号
        // return true;

        // 方案 B：合同网专项逻辑（推荐）
        // 只要看板里出现了 "winner" 关键字，说明已经完成了定标决策，允许结项
        ContractState state = (ContractState) trace.getProtocolContext().get(KEY_CONTRACT_STATE);
        if (state != null && state.getAwardedAgent() != null) {
            return true;
        }

        // 兜底：如果还没定标，就看是否满足基类的基本参与度要求
        return super.isLogicFinished(trace);
    }
}