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
import org.noear.solon.ai.agent.i18n.AgentI18n;
import org.noear.solon.ai.agent.i18n.AgentI18nKeys;
import org.noear.solon.ai.agent.team.TeamAgent;
import org.noear.solon.ai.agent.team.TeamAgentConfig;
import org.noear.solon.ai.agent.team.TeamTrace;
import org.noear.solon.ai.agent.team.task.SupervisorTask;
import org.noear.solon.ai.chat.ChatRole;
import org.noear.solon.ai.chat.message.UserMessage;
import org.noear.solon.ai.chat.tool.FunctionTool;
import org.noear.solon.ai.chat.tool.FunctionToolDesc;
import org.noear.solon.flow.FlowContext;
import org.noear.solon.flow.GraphSpec;
import org.noear.solon.lang.Preview;

import java.util.*;
import java.util.function.Consumer;

/**
 * A2A (Agent to Agent) 协作协议
 * * <p>核心机制：专家自主接力。Agent 可通过工具主动将任务及其上下文状态移交给另一位专家。</p>
 */
@Preview("3.8.1")
public class A2AProtocol extends TeamProtocolBase {
    private static final String KEY_A2A_STATE = "a2a_state_obj";
    private static final String TOOL_TRANSFER = "__transfer_to__";
    private final int maxTransferRounds = 5;

    public A2AProtocol(TeamAgentConfig config) {
        super(config);
    }

    public static class A2AState {
        private String globalState = "";
        private int transferCount = 0;
        private String lastTempTarget = "";
        private String lastPayload = "";

        public void setTransferRequest(String target, String state, String payload) {
            this.lastTempTarget = target;
            this.lastPayload = payload; // 锁定 payload
            if (Utils.isNotEmpty(state)) {
                this.globalState = state;
            }
        }

        public void confirmTransfer() {
            this.transferCount++;
            this.lastTempTarget = "";
        }

        /** 失败移交：只清理 pending，不消耗额度 */
        public void clearPendingTransfer() {
            this.lastTempTarget = "";
        }

        public String getGlobalState() {
            return globalState;
        }

        public String getLastPayload() {
            return lastPayload;
        }

        public int getTransferCount() {
            return transferCount;
        }

        public String getLastTempTarget() {
            return lastTempTarget;
        }
    }

    public A2AState getA2AState(TeamTrace trace) {
        return (A2AState) trace.getProtocolContext().computeIfAbsent(KEY_A2A_STATE, k -> new A2AState());
    }

    @Override
    public String name() {
        return "A2A";
    }

    @Override
    public void buildGraph(GraphSpec spec) {
        String firstAgent = config.getAgentMap().keySet().iterator().next();
        spec.addStart(Agent.ID_START).linkAdd(firstAgent);

        config.getAgentMap().values().forEach(a ->
                spec.addActivity(a).linkAdd(A2AHandoverTask.ID_HANDOVER));

        spec.addExclusive(new A2AHandoverTask(config, this)).then(ns -> {
            linkAgents(ns);
            ns.linkAdd(Agent.ID_END, l -> l.title("route = end").when(ctx -> {
                TeamTrace trace = ctx.getAs(config.getTraceKey());
                return Agent.ID_END.equalsIgnoreCase(trace.getRoute());
            }));
            ns.linkAdd(TeamAgent.ID_SUPERVISOR);
        });

        spec.addExclusive(new SupervisorTask(config)).then(ns -> {
            linkAgents(ns);
        }).linkAdd(Agent.ID_END);

        spec.addEnd(Agent.ID_END);
    }

    @Override
    public void injectAgentTools(FlowContext context, Agent agent, Consumer<FunctionTool> receiver) {
        String traceKey = context.getAs(Agent.KEY_CURRENT_TEAM_TRACE_KEY);
        TeamTrace trace = (traceKey != null) ? context.getAs(traceKey) : null;
        if (trace == null) return;

        A2AState stateObj = getA2AState(trace);
        Locale locale = trace.getOptions().getLocale();

        ONode expertsNode = new ONode().asArray();
        config.getAgentMap().entrySet().stream()
                .filter(e -> !e.getKey().equals(agent.name()))
                .forEach(e -> {
                    Agent expert = e.getValue();
                    expertsNode.add(expert.toMetadata(context));
                });

        FunctionToolDesc tool = new FunctionToolDesc(TOOL_TRANSFER).returnDirect(true);
        tool.metaPut(Agent.META_AGENT, agent.name());

        tool.doHandle(args -> {
            String target = (String) args.get("target");
            String state = (String) args.get("state");
            // 交接内容由当前专家显式写入 memo。
            // 不可在工具回调里读 getLastAgentContent()：此时当前专家尚未 addRecord。
            String memo = args.get("memo") == null ? "" : String.valueOf(args.get("memo"));

            stateObj.setTransferRequest(target, state, memo);

            return AgentI18n.fmt(locale, AgentI18nKeys.TEAM_A2A_TOOL_RESULT, "target", target);
        });

        tool.title(AgentI18n.get(locale, AgentI18nKeys.TEAM_A2A_TOOL_TITLE))
                .description(AgentI18n.get(locale, AgentI18nKeys.TEAM_A2A_TOOL_DESCRIPTION))
                .stringParamAdd("target", AgentI18n.fmt(locale, AgentI18nKeys.TEAM_A2A_TOOL_PARAM_TARGET, "experts", expertsNode.toJson()))
                .stringParamAdd("state", AgentI18n.get(locale, AgentI18nKeys.TEAM_A2A_TOOL_PARAM_STATE))
                .stringParamAdd("memo", AgentI18n.get(locale, AgentI18nKeys.TEAM_A2A_TOOL_PARAM_MEMO));

        receiver.accept(tool);
    }

    @Override
    public void injectAgentInstruction(FlowContext context, Agent agent, Locale locale, StringBuilder sb) {
        // 1. 基类注入：身份描述 + 协作历史 (最近 5 条)
        super.injectAgentInstruction(context, agent, locale, sb);

        // 2. 注入专家协作准则
        sb.append("\n").append(AgentI18n.get(locale, AgentI18nKeys.TEAM_A2A_AGENT_GUIDE)).append("\n");

        //---------

        // 3. 提取接力数据断面
        TeamTrace trace = TeamTrace.getCurrent(context);
        if (trace == null) {
            return;
        }

        A2AState stateObj = getA2AState(trace);

        // 锁定最后一次有效产出（接力棒内容）
        String effectiveOutput = stateObj.getLastPayload();
        if (Utils.isEmpty(effectiveOutput)) {
            effectiveOutput = trace.getLastAgentContent();
        }

        // 获取全局累积状态
        String state = stateObj.getGlobalState();

        // 提取协作接力轨迹（分析参与过的专家路径）
        List<String> path = new ArrayList<>();
        trace.getRecords().stream()
                .filter(r -> r.isAgent())
                .map(r -> r.getSource())
                .forEach(path::add);

        // 如果没有任何接力历史，说明是首节点，无需注入断面
        if (path.isEmpty() && Utils.isEmpty(effectiveOutput) && Utils.isEmpty(state)) {
            return;
        }

        // 4. 注入当前任务断面 (Session Level - 确保模型感知其处于接力状态)

        sb.append("\n").append(AgentI18n.get(locale, AgentI18nKeys.TEAM_A2A_AGENT_SESSION_TITLE)).append("\n\n");

        if (!path.isEmpty()) {
            sb.append(AgentI18n.fmt(locale, AgentI18nKeys.TEAM_A2A_AGENT_SESSION_TRAIL, "path", String.join(" -> ", path))).append("\n");
            sb.append(AgentI18n.fmt(locale, AgentI18nKeys.TEAM_A2A_AGENT_SESSION_PRIOR, "agent", path.get(path.size() - 1))).append("\n");
        }

        if (Utils.isNotEmpty(effectiveOutput)) {
            sb.append(AgentI18n.fmt(locale, AgentI18nKeys.TEAM_A2A_AGENT_SESSION_PENDING, "output", effectiveOutput)).append("\n");
        }

        if (Utils.isNotEmpty(state)) {
            sb.append(AgentI18n.fmt(locale, AgentI18nKeys.TEAM_A2A_AGENT_SESSION_STATE, "state", state)).append("\n");
        }
    }


    @Override
    public String resolveSupervisorRoute(FlowContext context, TeamTrace trace, String decision) {
        A2AState stateObj = getA2AState(trace);
        String target = stateObj.getLastTempTarget();

        if (Utils.isNotEmpty(target)) {
            Locale locale = trace.getOptions().getLocale();

            // 1. 熔断检查
            if (stateObj.getTransferCount() >= maxTransferRounds) {
                trace.setFinalAnswer(AgentI18n.get(locale, AgentI18nKeys.TEAM_A2A_ROUTE_MAX_TRANSFER));
                return null;
            }

            Agent targetAgent = config.getAgentMap().get(target);
            if (targetAgent == null || !checkModality(trace, target)) {
                // 失败移交不计入 transferCount
                stateObj.clearPendingTransfer();

                String feedback = AgentI18n.fmt(locale, AgentI18nKeys.TEAM_A2A_ROUTE_TRANSFER_FAILED, "target", target);

                // 失败反馈只记协作历史，不改写 originalPrompt（避免污染任务原始输入）
                trace.addRecord(ChatRole.SYSTEM, "Supervisor", feedback, 0);

                return trace.getLastAgentName();
            }

            // 3. 校验通过
            stateObj.confirmTransfer();
            return target;
        }

        return super.resolveSupervisorRoute(context, trace, decision);
    }

    private boolean checkModality(TeamTrace trace, String targetName) {
        Agent target = config.getAgentMap().get(targetName);
        if (target == null) return false;

        boolean isMultiModal = trace.getOriginalPrompt().getMessages().stream()
                .filter(UserMessage.class::isInstance)
                .map(UserMessage.class::cast)
                .anyMatch(UserMessage::isMultiModal);

        // profile 为空时，多模态任务直接不通过，避免 NPE
        if (target.profile() == null) {
            return !isMultiModal;
        }
        if (target.profile().getInputModes() == null) {
            return !isMultiModal;
        }

        return !isMultiModal || target.profile().getInputModes().stream().anyMatch(m -> !m.equalsIgnoreCase("text"));
    }

    @Override
    public void prepareSupervisorInstruction(FlowContext context, TeamTrace trace, StringBuilder sb) {
        A2AState stateObj = getA2AState(trace);
        Locale locale = trace.getOptions().getLocale();

        if (stateObj != null) {
            sb.append("\n").append(AgentI18n.get(locale, AgentI18nKeys.TEAM_A2A_DASHBOARD_TITLE)).append("\n");
            if (Utils.isNotEmpty(stateObj.getGlobalState())) {
                sb.append(AgentI18n.fmt(locale, AgentI18nKeys.TEAM_A2A_DASHBOARD_STATE, "state", stateObj.getGlobalState())).append("\n");
            }
            sb.append(AgentI18n.fmt(locale, AgentI18nKeys.TEAM_A2A_DASHBOARD_COUNT, "count", stateObj.getTransferCount())).append("\n");
        }
    }

    @Override
    public void injectSupervisorInstruction(Locale locale, StringBuilder sb) {
        super.injectSupervisorInstruction(locale, sb);
        sb.append("\n").append(AgentI18n.fmt(locale, AgentI18nKeys.TEAM_A2A_SUPERVISOR_RULES, "finishMarker", config.getFinishMarker()));
    }
}