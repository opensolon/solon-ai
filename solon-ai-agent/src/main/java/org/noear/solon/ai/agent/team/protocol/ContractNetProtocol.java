/*
 * Copyright 2017-2026 noear.org and authors
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
import org.noear.solon.ai.agent.i18n.AgentI18n;
import org.noear.solon.ai.agent.i18n.AgentI18nKeys;
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

    /**
     * 招标工具名标记（不是语言文案；语言相关的招标关键词见资源文件 {@code protocol.contractnet.bidding_keywords}）
     */
    private static final String BIDDING_MARKER = TOOL_CALL_BIDS.toUpperCase(Locale.ROOT);

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

        Locale locale = trace.getOptions().getLocale();

        tool.title(AgentI18n.get(locale, AgentI18nKeys.PROTOCOL_CONTRACTNET_BID_TOOL_TITLE))
                .description(AgentI18n.get(locale, AgentI18nKeys.PROTOCOL_CONTRACTNET_BID_TOOL_DESCRIPTION))
                .intParamAdd("score", AgentI18n.get(locale, AgentI18nKeys.PROTOCOL_CONTRACTNET_BID_TOOL_PARAM_SCORE))
                .stringParamAdd("plan", AgentI18n.get(locale, AgentI18nKeys.PROTOCOL_CONTRACTNET_BID_TOOL_PARAM_PLAN));

        tool.doHandle(args -> {
            int score = normalizeScore(args.get("score"));
            String plan = args.get("plan") == null ? "" : String.valueOf(args.get("plan")).trim();
            if (plan.isEmpty()) {
                return AgentI18n.get(locale, AgentI18nKeys.PROTOCOL_CONTRACTNET_BID_TOOL_PLAN_REQUIRED);
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
            return AgentI18n.get(locale, AgentI18nKeys.PROTOCOL_CONTRACTNET_BID_TOOL_RECEIVED);
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
                Locale effectiveLocale = locale != null ? locale : trace.getOptions().getLocale();
                String plan = bid.get("plan").getString();

                String awardNotice = "\n" + AgentI18n.fmt(effectiveLocale, AgentI18nKeys.PROTOCOL_CONTRACTNET_PROMPT_AWARD, "plan", plan);

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

        Locale locale = trace.getOptions().getLocale();
        FunctionToolDesc tool = new FunctionToolDesc(TOOL_CALL_BIDS);

        tool.title(AgentI18n.get(locale, AgentI18nKeys.PROTOCOL_CONTRACTNET_CALL_TOOL_TITLE))
                .description(AgentI18n.get(locale, AgentI18nKeys.PROTOCOL_CONTRACTNET_CALL_TOOL_DESCRIPTION))
                .stringParamAdd("requirement", AgentI18n.get(locale, AgentI18nKeys.PROTOCOL_CONTRACTNET_CALL_TOOL_PARAM_REQUIREMENT));

        tool.doHandle(args -> {
            String requirement = args.get("requirement") == null ? null : String.valueOf(args.get("requirement")).trim();
            if (Utils.isEmpty(requirement)) {
                requirement = null;
            }

            if (LOG.isDebugEnabled()) {
                LOG.debug("ContractNet: Supervisor initiated bidding tool. Requirement: {}", requirement);
            }

            if (!tryStartNewBidding(trace, requirement)) {
                return AgentI18n.get(locale, AgentI18nKeys.PROTOCOL_CONTRACTNET_CALL_TOOL_EXHAUSTED);
            }

            trace.setRoute(ID_BIDDING);
            return AgentI18n.get(locale, AgentI18nKeys.PROTOCOL_CONTRACTNET_CALL_TOOL_STARTED);
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
                if (LOG.isWarnEnabled()) {
                    LOG.warn("ContractNet: Compliance violation! Supervisor tried to assign '{}' without a bid.", agentName);
                }

                String warnMsg = AgentI18n.fmt(trace.getOptions().getLocale(),
                        AgentI18nKeys.PROTOCOL_CONTRACTNET_ROUTE_INVALID_ASSIGNMENT, "tool", TOOL_CALL_BIDS);
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
                String msg = AgentI18n.get(trace.getOptions().getLocale(), AgentI18nKeys.PROTOCOL_CONTRACTNET_ROUTE_CANNOT_AWARD);
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
        Locale locale = trace.getOptions().getLocale();

        int remaining = Math.max(0, maxBiddingRounds - state.getRounds());

        if (state.getRounds() > 0 && !state.hasBids()) {
            if (remaining > 0) {
                sb.append("\n").append(AgentI18n.fmt(locale, AgentI18nKeys.PROTOCOL_CONTRACTNET_SUPERVISOR_NO_BIDS_WARNING, "remaining", remaining));
            } else {
                sb.append("\n").append(AgentI18n.get(locale, AgentI18nKeys.PROTOCOL_CONTRACTNET_SUPERVISOR_FINAL_WARNING));
            }
        } else if (remaining == 0 && state.hasBids()) {
            sb.append("\n").append(AgentI18n.get(locale, AgentI18nKeys.PROTOCOL_CONTRACTNET_SUPERVISOR_ROUNDS_USED_UP)).append("\n");
        }

        sb.append("\n").append(AgentI18n.get(locale, AgentI18nKeys.PROTOCOL_CONTRACTNET_SUPERVISOR_DASHBOARD_TITLE)).append("\n");
        if (state.hasBids()) {
            sb.append("```json\n").append(ONode.serialize(state)).append("\n```\n");
            sb.append(AgentI18n.fmt(locale, AgentI18nKeys.PROTOCOL_CONTRACTNET_SUPERVISOR_DASHBOARD_TIPS, "remaining", remaining)).append("\n");
        } else {
            sb.append(AgentI18n.fmt(locale, AgentI18nKeys.PROTOCOL_CONTRACTNET_SUPERVISOR_DASHBOARD_WAITING, "tool", TOOL_CALL_BIDS, "remaining", remaining)).append("\n");
        }

        sb.append(AgentI18n.get(locale, AgentI18nKeys.PROTOCOL_CONTRACTNET_SUPERVISOR_PROFILES_TITLE)).append("\n");
        config.getAgentMap().forEach((name, ag) -> {
            sb.append("- ").append(ag.toMetadata(context).toJson()).append("\n");
        });
    }

    @Override
    public void injectSupervisorInstruction(Locale locale, StringBuilder sb) {
        super.injectSupervisorInstruction(locale, sb);
        sb.append("\n").append(AgentI18n.fmt(locale, AgentI18nKeys.PROTOCOL_CONTRACTNET_SUPERVISOR_GUIDELINES, "tool", TOOL_CALL_BIDS));
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
        if (upper.contains(BIDDING_MARKER)) {
            return true;
        }
        //招标关键词来自资源文件（合并所有已知语言；用户新增语言文件后自动生效）
        for (String kw : AgentI18n.itemsOfAllLocales(AgentI18nKeys.PROTOCOL_CONTRACTNET_BIDDING_KEYWORDS)) {
            if (upper.contains(kw.toUpperCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
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
        return AgentI18n.isChinese(locale);
    }
}
