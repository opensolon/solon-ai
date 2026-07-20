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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 合同网协作协议 (Contract Net Protocol)
 *
 * <p>核心特征：Supervisor 发起招标 → 算法按能力元数据自动投标（可扩展手动投标工具）→
 * Supervisor 依据看板定标 → 中标 Agent 执行。所有进入招标的入口统一初始化状态并受轮次上限约束。</p>
 */
@Preview("3.8.1")
public class ContractNetProtocol extends TeamProtocolBase {
    public final static String ID_BIDDING = "bidding";

    private static final Logger LOG = LoggerFactory.getLogger(ContractNetProtocol.class);

    private static final String KEY_CONTRACT_STATE = "contract_state_obj";

    private static final String TOOL_CALL_BIDS = "__call_for_bids__";
    private static final String TOOL_SUBMIT_BID = "__submit_proposal__";

    private final int maxBiddingRounds = 2;

    public static class ContractState {
        private final Map<String, ONode> bids = new LinkedHashMap<>();
        private final List<Map<String, ONode>> history = new ArrayList<>();
        private String awardedAgent;
        private String requirement;
        private int rounds = 0;

        public void archiveCurrentRounds() {
            if (!bids.isEmpty()) {
                history.add(new LinkedHashMap<>(bids));
            }
        }

        public void incrementRound() {
            this.rounds++;
        }

        public int getRounds() {
            return this.rounds;
        }

        public void addBid(String agentName, ONode bidContent) {
            if (agentName != null) {
                bids.put(agentName, bidContent);
            }
        }

        public void setAwardedAgent(String agentName) {
            this.awardedAgent = agentName;
        }

        public Map<String, ONode> getBids() {
            return bids;
        }

        public String getAwardedAgent() {
            return awardedAgent;
        }

        public String getRequirement() {
            return requirement;
        }

        public void setRequirement(String requirement) {
            this.requirement = requirement;
        }

        public List<Map<String, ONode>> getHistory() {
            return history;
        }

        public boolean hasBids() {
            return !bids.isEmpty();
        }

        /**
         * 大小写不敏感的投标查询，避免 LLM 输出 Chef/chef 时守卫失效。
         */
        public boolean hasAgentBid(String name) {
            return findBidKey(name) != null;
        }

        public ONode getBid(String name) {
            String key = findBidKey(name);
            return key == null ? null : bids.get(key);
        }

        private String findBidKey(String name) {
            if (name == null) {
                return null;
            }
            if (bids.containsKey(name)) {
                return name;
            }
            for (String key : bids.keySet()) {
                if (key.equalsIgnoreCase(name)) {
                    return key;
                }
            }
            return null;
        }

        @Override
        public String toString() {
            ONode root = new ONode().asObject();
            String phase = (awardedAgent != null) ? "AWARDED" : (bids.isEmpty() ? "WAITING_FOR_BIDS" : "EVALUATING");
            root.set("phase", phase);
            root.set("current_round", this.rounds);
            root.set("history_count", this.history.size());
            root.set("winner", this.awardedAgent == null ? "none" : this.awardedAgent);
            if (Utils.isNotEmpty(this.requirement)) {
                root.set("requirement", this.requirement);
            }

            ONode bidsNode = root.getOrNew("bids_board").asObject();
            bids.forEach((name, content) -> {
                ONode item = bidsNode.getOrNew(name);
                item.set("score", content.get("score"));
                item.set("plan", content.get("plan"));
                boolean isAuto = content.get("auto_bid").getBoolean();
                item.set("source", isAuto ? "Capability_Match" : "Expert_Proposal");
            });

            // 让 Supervisor 可见历史轮次摘要（仅分数，避免看板过长）
            if (!history.isEmpty()) {
                ONode historyNode = root.getOrNew("history_summary").asArray();
                for (int i = 0; i < history.size(); i++) {
                    ONode roundNode = new ONode().asObject();
                    roundNode.set("round", i + 1);
                    ONode scores = roundNode.getOrNew("scores").asObject();
                    history.get(i).forEach((name, content) -> scores.set(name, content.get("score")));
                    historyNode.add(roundNode);
                }
            }
            return root.toJson();
        }
    }

    public ContractState getContractState(TeamTrace trace) {
        return (ContractState) trace.getProtocolContext()
                .computeIfAbsent(KEY_CONTRACT_STATE, k -> new ContractState());
    }

    /**
     * 开启新一轮招标：归档旧标、清空看板、递增轮次。
     * 不检查轮次上限；需要约束时请用 {@link #tryStartNewBidding(TeamTrace, String)}。
     */
    public void startNewBidding(TeamTrace trace) {
        startNewBidding(trace, null);
    }

    public void startNewBidding(TeamTrace trace, String requirement) {
        ContractState state = getContractState(trace);
        state.archiveCurrentRounds();
        state.getBids().clear();
        state.setAwardedAgent(null);
        if (requirement != null) {
            state.setRequirement(requirement);
        }
        state.incrementRound();
    }

    /**
     * 受 {@link #maxBiddingRounds} 约束的招标入口。成功返回 true。
     */
    public boolean tryStartNewBidding(TeamTrace trace, String requirement) {
        ContractState state = getContractState(trace);
        if (state.getRounds() >= maxBiddingRounds) {
            if (LOG.isWarnEnabled()) {
                LOG.warn("ContractNet: Bidding rounds exhausted (max={}).", maxBiddingRounds);
            }
            return false;
        }
        startNewBidding(trace, requirement);
        return true;
    }

    public ContractNetProtocol(TeamAgentConfig config) {
        super(config);
    }

    @Override
    public String name() {
        return "CONTRACT_NET";
    }

    @Override
    public void buildGraph(GraphSpec spec) {
        spec.addStart(Agent.ID_START).linkAdd(TeamAgent.ID_SUPERVISOR);

        spec.addExclusive(new SupervisorTask(config)).then(ns -> {
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

    /**
     * 手动投标工具：在 route=bidding 时注入。
     * 默认拓扑中 bidding 节点为算法任务，通常走自动投标；此工具供扩展图或未来并行投标阶段使用。
     */
    @Override
    public void injectAgentTools(FlowContext context, Agent agent, Consumer<FunctionTool> receiver) {
        String traceKey = context.getAs(Agent.KEY_CURRENT_TEAM_TRACE_KEY);
        TeamTrace trace = (traceKey != null) ? context.getAs(traceKey) : null;
        if (trace == null) {
            return;
        }

        if (!ID_BIDDING.equals(trace.getRoute())) {
            return;
        }

        FunctionToolDesc tool = new FunctionToolDesc(TOOL_SUBMIT_BID).returnDirect(true);
        tool.metaPut(Agent.META_AGENT, agent.name());

        boolean isZh = isChinese(config.getLocale());

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
            int score = normalizeScore(args.get("score"));
            String plan = args.get("plan") == null ? "" : String.valueOf(args.get("plan")).trim();
            if (plan.isEmpty()) {
                return isZh ? "投标失败：plan 不能为空。" : "Bid rejected: plan is required.";
            }

            if (LOG.isDebugEnabled()) {
                LOG.debug("ContractNet: Agent [{}] submitted a manual bid via tool. Score: {}", agent.name(), score);
            }

            ContractState state = getContractState(trace);
            ONode bid = new ONode().asObject();
            bid.set("score", score);
            bid.set("plan", plan);
            bid.set("auto_bid", false);
            state.addBid(agent.name(), bid);
            return isZh ? "标书已收录。" : "Bid received.";
        });

        receiver.accept(tool);
    }

    /**
     * 注入中标执行指令。基类已 {@link Prompt#copy()}，此处只在副本上追加。
     */
    @Override
    public Prompt prepareAgentPrompt(TeamTrace trace, Agent agent, Prompt originalPrompt, Locale locale) {
        Prompt finalPrompt = super.prepareAgentPrompt(trace, agent, originalPrompt, locale);

        ContractState state = getContractState(trace);
        String awarded = state.getAwardedAgent();
        if (awarded != null && awarded.equalsIgnoreCase(agent.name())) {
            ONode bid = state.getBid(agent.name());
            if (bid != null) {
                boolean isZh = isChinese(locale != null ? locale : config.getLocale());
                String plan = bid.get("plan").getString();

                String awardNotice = isZh
                        ? "\n【中标执行指令】\n> 你已在竞标中胜出。请严格按你提交的计划执行：\n" + plan
                        : "\n [Award Execution Order] \n> You won the bid. Execute strictly according to your plan:\n" + plan;

                finalPrompt.addMessage(ChatMessage.ofUser(awardNotice));
            }
        }

        return finalPrompt;
    }

    @Override
    public void injectSupervisorTools(FlowContext context, Consumer<FunctionTool> receiver) {
        String traceKey = context.getAs(Agent.KEY_CURRENT_TEAM_TRACE_KEY);
        TeamTrace trace = (traceKey != null) ? context.getAs(traceKey) : null;
        if (trace == null) {
            return;
        }

        boolean isZh = isChinese(config.getLocale());
        FunctionToolDesc tool = new FunctionToolDesc(TOOL_CALL_BIDS);

        if (isZh) {
            tool.title("发起招标").description("向全体专家征集方案。当你无法确定由谁执行任务时使用此工具。")
                    .stringParamAdd("requirement", "任务具体需求");
        } else {
            tool.title("Call for Bids").description("Collect proposals from all experts when the best executor is not obvious.")
                    .stringParamAdd("requirement", "Task requirements");
        }

        tool.doHandle(args -> {
            String requirement = args.get("requirement") == null ? null : String.valueOf(args.get("requirement")).trim();
            if (Utils.isEmpty(requirement)) {
                requirement = null;
            }

            if (LOG.isDebugEnabled()) {
                LOG.debug("ContractNet: Supervisor initiated bidding tool. Requirement: {}", requirement);
            }

            if (!tryStartNewBidding(trace, requirement)) {
                return isZh
                        ? "招标次数已耗尽。请根据现有标书定标，或直接回复用户无法完成。"
                        : "Bidding rounds exhausted. Award an existing bid or inform the user.";
            }

            trace.setRoute(ID_BIDDING);
            return isZh ? "已进入招标流程。" : "Bidding phase initiated.";
        });

        receiver.accept(tool);
    }

    @Override
    public String resolveSupervisorRoute(FlowContext context, TeamTrace trace, String decision) {
        // 1. 已在招标路由中：保持
        if (ID_BIDDING.equals(trace.getRoute())) {
            return ID_BIDDING;
        }

        // 2. 显式招标指令（工具名 / 文本）
        if (isExplicitBiddingDecision(decision)) {
            if (tryStartNewBidding(trace, null)) {
                return ID_BIDDING;
            }
            // 轮次耗尽：返回 null，迫使 Supervisor 改用定标或结束
            return null;
        }

        // 3. 指派守卫：在协议内完成 agent 名解析（精确 + 模糊），堵住 matchAgentRoute 旁路
        String agentName = findAgentInDecision(decision);
        if (agentName != null) {
            ContractState state = getContractState(trace);

            if (!state.hasAgentBid(agentName)) {
                boolean isZh = isChinese(config.getLocale());
                if (LOG.isWarnEnabled()) {
                    LOG.warn("ContractNet: Compliance violation! Supervisor tried to assign '{}' without a bid.", agentName);
                }

                String warnMsg = isZh
                        ? "【系统指引】指派无效。必须先调用 " + TOOL_CALL_BIDS + " 获取标书，且只能指派已出现在 bids_board 中的专家。"
                        : "[System] Invalid assignment. You must call " + TOOL_CALL_BIDS + " first and only assign experts listed in bids_board.";
                trace.addRecord(ChatRole.SYSTEM, ID_BIDDING, warnMsg, 0);

                if (tryStartNewBidding(trace, null)) {
                    return ID_BIDDING;
                }
                // 轮次耗尽且无标：不返回 agent 名，避免非法执行
                return null;
            }

            state.setAwardedAgent(agentName);
            return agentName;
        }

        return null;
    }

    /**
     * 最终路由守卫：防止任何路径在未投标时落到 agent 节点。
     */
    @Override
    public void onSupervisorRouting(FlowContext context, TeamTrace trace, String nextAgent) {
        if (Utils.isEmpty(nextAgent)
                || ID_BIDDING.equals(nextAgent)
                || Agent.ID_END.equals(nextAgent)
                || Agent.ID_START.equals(nextAgent)
                || TeamAgent.ID_SUPERVISOR.equals(nextAgent)
                || TeamAgent.ID_SYSTEM.equals(nextAgent)) {
            super.onSupervisorRouting(context, trace, nextAgent);
            return;
        }

        String canonical = canonicalAgentName(nextAgent);
        if (canonical == null) {
            super.onSupervisorRouting(context, trace, nextAgent);
            return;
        }

        ContractState state = getContractState(trace);
        if (!state.hasAgentBid(canonical)) {
            if (LOG.isWarnEnabled()) {
                LOG.warn("ContractNet: onSupervisorRouting blocked unbid assignment to '{}'.", canonical);
            }
            if (tryStartNewBidding(trace, null)) {
                trace.setRoute(ID_BIDDING);
            } else {
                // 无法再招标：终止非法路径
                boolean isZh = isChinese(config.getLocale());
                String msg = isZh
                        ? "无法定标：目标专家无有效标书且招标次数已耗尽。"
                        : "Cannot award: target has no bid and bidding rounds are exhausted.";
                trace.addRecord(ChatRole.SYSTEM, ID_BIDDING, msg, 0);
                trace.setFinalAnswer(msg);
                trace.setRoute(Agent.ID_END);
            }
            super.onSupervisorRouting(context, trace, trace.getRoute());
            return;
        }

        // 统一记录中标者（覆盖模糊路径未 setAwarded 的情况）
        if (state.getAwardedAgent() == null || !canonical.equalsIgnoreCase(state.getAwardedAgent())) {
            state.setAwardedAgent(canonical);
        }
        // 路由名规范化为配置中的 canonical key
        if (!canonical.equals(nextAgent)) {
            trace.setRoute(canonical);
        }

        super.onSupervisorRouting(context, trace, trace.getRoute());
    }

    @Override
    public void prepareSupervisorInstruction(FlowContext context, TeamTrace trace, StringBuilder sb) {
        ContractState state = getContractState(trace);
        boolean isZh = isChinese(config.getLocale());

        int remaining = Math.max(0, maxBiddingRounds - state.getRounds());

        if (state.getRounds() > 0 && !state.hasBids()) {
            if (remaining > 0) {
                sb.append(isZh ? "\n> [警告] 前一轮招标无人响应。剩余尝试次数: " + remaining
                        : "\n> [Warning] No bids in previous round. Remaining attempts: " + remaining);
            } else {
                sb.append(isZh ? "\n> [最终警告] 招标次数已耗尽。请停止招标，直接回复用户：该任务超出了当前团队的能力范围。"
                        : "\n> [Final Warning] No more bidding rounds. Stop bidding and inform the user that the task is out of scope.");
            }
        } else if (remaining == 0 && state.hasBids()) {
            sb.append(isZh ? "\n> [提示] 招标次数已用尽，请基于当前看板完成定标。\n"
                    : "\n> [Note] No more bidding rounds. Award based on the current board.\n");
        }

        sb.append(isZh ? "\n### 合同网竞标看板\n" : "\n### Contract Net Dashboard\n");
        if (state.hasBids()) {
            sb.append("```json\n").append(ONode.serialize(state)).append("\n```\n");
            sb.append(isZh ? "> 提示：请根据各专家的 `score` 和 `plan` 择优指派。剩余招标次数: " + remaining + "\n"
                    : "> Tips: Award the best candidate by `score`/`plan`. Remaining bidding rounds: " + remaining + "\n");
        } else {
            if (isZh) {
                sb.append("> **强制规范**：当前处于“待招标”状态。在进行任何指派前，**必须**先获取专家标书。\n");
                sb.append("> **操作建议**：请调用 `").append(TOOL_CALL_BIDS).append("` 工具发起招标。剩余次数: ").append(remaining).append("\n");
            } else {
                sb.append("> **Mandatory**: Status is 'Waiting'. You **MUST** collect proposals before any assignment.\n");
                sb.append("> **Action**: Please call `").append(TOOL_CALL_BIDS).append("` to initiate bidding. Remaining: ").append(remaining).append("\n");
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
        boolean isZh = isChinese(locale);
        if (isZh) {
            sb.append("\n### CNP 协作规范：\n");
            sb.append("1. **工具招标**：若无法确定最佳人选，优先调用 `").append(TOOL_CALL_BIDS).append("`。\n");
            sb.append("2. **择优录取**：对比看板中的标书分数，直接回复专家名称进行定标。\n");
            sb.append("3. **模态检查**：请确保所选专家的 `Modes` 支持当前任务模态（如识图需具备 `image`）。\n");
            sb.append("4. **禁止旁路**：未出现在 bids_board 中的专家禁止指派。");
        } else {
            sb.append("\n### CNP Collaboration Rules:\n");
            sb.append("1. **Tool Usage**: Call `").append(TOOL_CALL_BIDS).append("` if unsure about assignments.\n");
            sb.append("2. **Decision**: Award the task by naming the best agent based on their bid score.\n");
            sb.append("3. **Constraint**: Match task requirements with agent `Modes` (e.g., `image` for vision tasks).\n");
            sb.append("4. **No Bypass**: Never assign an expert not listed in bids_board.");
        }
    }

    /**
     * 算法兜底打分：基于 requirement（优先）与用户任务，对 role/capabilities 做词边界匹配。
     */
    protected ONode constructBid(Agent agent, Prompt prompt) {
        return constructBid(agent, prompt, null);
    }

    protected ONode constructBid(Agent agent, Prompt prompt, String requirement) {
        String taskDesc = resolveTaskDesc(prompt, requirement);

        int score = 30;
        List<String> matched = new ArrayList<>();

        // role 加权
        String role = agent.role();
        if (Utils.isNotEmpty(role)) {
            for (String token : role.toLowerCase(Locale.ROOT).split("[\\s,，、/|;:]+")) {
                if (token.length() >= 2 && matchesKeyword(taskDesc, token)) {
                    score += 10;
                    matched.add("role:" + token);
                    break;
                }
            }
        }

        Collection<?> capabilities = Collections.emptyList();
        AgentProfile profile = agent.profile();
        if (profile != null) {
            capabilities = profile.getCapabilities();
            if (capabilities == null) {
                capabilities = Collections.emptyList();
            }

            if (!capabilities.isEmpty()) {
                // 有能力画像时抬高保底分
                score = Math.max(score, 60);
            }

            for (Object caps : capabilities) {
                if (caps == null) {
                    continue;
                }
                String cap = String.valueOf(caps).trim();
                if (cap.isEmpty() || "assistant".equalsIgnoreCase(cap)) {
                    continue; // 默认占位能力不参与加分
                }
                if (matchesKeyword(taskDesc, cap)) {
                    score += 15;
                    matched.add(cap);
                }
            }
        }

        String autoPlan;
        if (matched.isEmpty()) {
            autoPlan = "Auto-matched by capabilities: " + capabilities;
        } else {
            autoPlan = "Auto-matched keywords: " + matched + "; capabilities: " + capabilities;
        }

        ONode bidNode = new ONode().asObject();
        bidNode.set("score", Math.min(95, score));
        bidNode.set("plan", autoPlan);
        bidNode.set("auto_bid", true);
        return bidNode;
    }

    /**
     * 定标且中标者已实际执行后，才允许 FINISH。
     */
    @Override
    protected boolean isLogicFinished(TeamTrace trace) {
        if (trace.getRecords().isEmpty()) {
            return false;
        }

        ContractState state = (ContractState) trace.getProtocolContext().get(KEY_CONTRACT_STATE);
        if (state == null || state.getAwardedAgent() == null) {
            return super.isLogicFinished(trace);
        }

        String winner = state.getAwardedAgent();
        return trace.getRecords().stream()
                .anyMatch(r -> r.isAgent() && winner.equalsIgnoreCase(r.getSource()));
    }

    // ----------------- helpers -----------------

    protected boolean isExplicitBiddingDecision(String decision) {
        if (Utils.isEmpty(decision)) {
            return false;
        }
        String upper = decision.toUpperCase(Locale.ROOT);
        return upper.contains("BIDDING")
                || upper.contains("招标")
                || upper.contains("CALL_FOR_BIDS")
                || upper.contains(TOOL_CALL_BIDS.toUpperCase(Locale.ROOT));
    }

    /**
     * 与 {@link SupervisorTask} 模糊匹配对齐：精确/忽略大小写 → 词边界模糊。
     * 始终返回 agentMap 中的规范 key（agentMap 可能是 IgnoreCaseMap）。
     */
    protected String findAgentInDecision(String decision) {
        if (Utils.isEmpty(decision)) {
            return null;
        }

        String cleanText = decision.replaceAll("[\\*\\`]", "").trim();

        // 1. 整段即 agent 名（含大小写差异）→ 规范名
        String exact = canonicalAgentName(cleanText);
        if (exact != null) {
            return exact;
        }

        // 2. 文本中词边界模糊匹配，取最后出现者
        String lastFoundAgent = null;
        int lastIndex = -1;
        for (String name : config.getAgentMap().keySet()) {
            Pattern p = Pattern.compile("(?i)(?<=^|[^a-zA-Z0-9])" + Pattern.quote(name) + "(?=[^a-zA-Z0-9]|$)");
            Matcher m = p.matcher(cleanText);
            while (m.find()) {
                if (m.start() > lastIndex) {
                    lastIndex = m.start();
                    lastFoundAgent = name;
                }
            }
        }
        return lastFoundAgent;
    }

    /**
     * 将任意大小写/别名解析为 agentMap 中登记的规范名称。
     * agentMap 为 IgnoreCaseMap 时 containsKey 为 true 不代表入参本身是规范 key。
     */
    protected String canonicalAgentName(String name) {
        if (Utils.isEmpty(name)) {
            return null;
        }
        for (String key : config.getAgentMap().keySet()) {
            if (key.equalsIgnoreCase(name)) {
                return key;
            }
        }
        return null;
    }

    protected String resolveTaskDesc(Prompt prompt, String requirement) {
        if (Utils.isNotEmpty(requirement)) {
            return requirement.toLowerCase(Locale.ROOT);
        }
        if (prompt != null) {
            String userContent = prompt.getUserContent();
            if (userContent != null) {
                return userContent.toLowerCase(Locale.ROOT);
            }
        }
        return "";
    }

    /**
     * 词边界优先，降低 "java" 误匹配 "javascript" 的概率；非单词关键词回退 contains。
     */
    protected boolean matchesKeyword(String text, String keyword) {
        if (Utils.isEmpty(text) || Utils.isEmpty(keyword)) {
            return false;
        }
        String t = text.toLowerCase(Locale.ROOT);
        String k = keyword.toLowerCase(Locale.ROOT).trim();
        if (k.isEmpty()) {
            return false;
        }
        if (k.matches("[\\p{L}\\p{N}_]+")) {
            Pattern p = Pattern.compile("(?i)(?<=^|[^\\p{L}\\p{N}_])" + Pattern.quote(k) + "(?=[^\\p{L}\\p{N}_]|$)");
            return p.matcher(t).find();
        }
        return t.contains(k);
    }

    protected int normalizeScore(Object scoreObj) {
        int score = 50;
        if (scoreObj instanceof Number) {
            score = ((Number) scoreObj).intValue();
        } else if (scoreObj != null) {
            try {
                score = Integer.parseInt(String.valueOf(scoreObj).trim());
            } catch (Exception ignore) {
                score = 50;
            }
        }
        return Math.max(1, Math.min(100, score));
    }

    protected boolean isChinese(Locale locale) {
        return locale != null && Locale.CHINA.getLanguage().equals(locale.getLanguage());
    }
}
