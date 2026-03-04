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
import org.noear.solon.ai.chat.message.UserMessage;
import org.noear.solon.ai.chat.prompt.Prompt;
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
        boolean isZh = Locale.CHINA.getLanguage().equals(config.getLocale().getLanguage());

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

            String currentEffectiveContent = trace.getLastAgentContent();
            stateObj.setTransferRequest(target, state, currentEffectiveContent);

            if (isZh) {
                return "已发起向 [" + target + "] 的接力请求。";
            } else {
                return "Transfer to [" + target + "] requested.";
            }
        });

        if (isZh) {
            tool.title("任务移交")
                    .description("当你无法完成当前任务时，将其移交给更合适的专家。")
                    .stringParamAdd("target", "目标专家名。必选范围: " + expertsNode.toJson())
                    .stringParamAdd("state", "业务状态 JSON。");
        } else {
            tool.title("Transfer")
                    .description("When you are unable to complete the current task, hand it over to a more appropriate expert.")
                    .stringParamAdd("target", "Expert name. Options: " + expertsNode.toJson())
                    .stringParamAdd("state", "Persistent JSON state.");
        }

        receiver.accept(tool);
    }

    @Override
    public void injectAgentInstruction(FlowContext context, Agent agent, Locale locale, StringBuilder sb) {
        boolean isZh = Locale.CHINA.getLanguage().equals(locale.getLanguage());

        // 1. 注入基础协作准则 (System Level)
        if (isZh) {
            sb.append("\n## 专家协作指引\n");
            sb.append("- 必须根据各专家的 [核心能力] 和 [模态] 标签精准选择目标，严禁向不支持相关模态的专家移交任务。\n");
        } else {
            sb.append("\n## Collaboration Guidelines\n");
            sb.append("- You must choose the target precisely based on [Capabilities] and [Modes] tags. Never transfer to an agent that lacks the required modality.\n");
        }

        //---------

        // 2. 提取接力数据断面
        TeamTrace trace = TeamTrace.getCurrent(context);
        if(trace == null){
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

        // 3. 注入当前任务断面 (Session Level - 确保模型感知其处于接力状态)

        if (isZh) {
            sb.append("\n## 当前接力任务断面 (Active Session Context)\n");
            sb.append("> 本部分记录了任务流转至此时的关键快照，请基于此状态继续执行，严禁编造断面之外的信息。\n\n");

            if (!path.isEmpty()) {
                sb.append("- **协作轨迹**: ").append(String.join(" -> ", path)).append("\n");
                sb.append("- **上一步专家**: ").append(path.get(path.size() - 1)).append("\n");
            }

            if (Utils.isNotEmpty(effectiveOutput)) {
                sb.append("- **待处理产出**: ").append(effectiveOutput).append("\n");
            }

            if (Utils.isNotEmpty(state)) {
                sb.append("- **累积业务状态**: ").append(state).append("\n");
            }
        } else {
            sb.append("\n## Active Session Context\n");
            sb.append("> This snapshot records the current state of the task. Continue execution based on this data.\n\n");

            if (!path.isEmpty()) {
                sb.append("- **Collaboration Trail**: ").append(String.join(" -> ", path)).append("\n");
                sb.append("- **Prior Expert**: ").append(path.get(path.size() - 1)).append("\n");
            }

            if (Utils.isNotEmpty(effectiveOutput)) {
                sb.append("- **Pending Output**: ").append(effectiveOutput).append("\n");
            }

            if (Utils.isNotEmpty(state)) {
                sb.append("- **Global State**: ").append(state).append("\n");
            }
        }
    }


    @Override
    public String resolveSupervisorRoute(FlowContext context, TeamTrace trace, String decision) {
        A2AState stateObj = getA2AState(trace);
        String target = stateObj.getLastTempTarget();

        if (Utils.isNotEmpty(target)) {
            Locale locale = config.getLocale();
            boolean isZh = Locale.CHINA.getLanguage().equals(locale.getLanguage());

            // 1. 熔断检查
            if (stateObj.getTransferCount() >= maxTransferRounds) {
                if (isZh) {
                    trace.setFinalAnswer("协作达到最大流转次数，任务中止。");
                } else {
                    trace.setFinalAnswer("Max transfer limit reached.");
                }
                return null;
            }

            Agent targetAgent = config.getAgentMap().get(target);
            if (targetAgent == null || !checkModality(trace, target)) {
                stateObj.confirmTransfer();

                String feedback;
                if (isZh) {
                    feedback = "【系统通知】移交失败：专家 [" + target + "] 不存在或能力不匹配（如无法处理图片）。请重新选择或尝试自行解决。";
                } else {
                    feedback = "[System] Transfer failed: Expert [" + target + "] is invalid or lacks required capabilities. Please re-select or resolve yourself.";
                }

                trace.addRecord(ChatRole.SYSTEM, "Supervisor", feedback, 0);

                List<ChatMessage> messages = new ArrayList<>(trace.getOriginalPrompt().getMessages());
                messages.add(ChatMessage.ofUser(feedback));
                trace.setOriginalPrompt(Prompt.of(messages).attrPut(trace.getOriginalPrompt().attrs()));

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
                .filter(m -> m.getRole() == ChatRole.USER)
                .map(m -> (UserMessage) m)
                .anyMatch(UserMessage::isMultiModal);

        return !isMultiModal || target.profile().getInputModes().stream().anyMatch(m -> !m.equalsIgnoreCase("text"));
    }

    @Override
    public void prepareSupervisorInstruction(FlowContext context, TeamTrace trace, StringBuilder sb) {
        A2AState stateObj = getA2AState(trace);
        boolean isZh = Locale.CHINA.getLanguage().equals(config.getLocale().getLanguage());

        if (stateObj != null) {
            if (isZh) {
                sb.append("\n### A2A 接力看板\n");
                if (Utils.isNotEmpty(stateObj.getGlobalState())) {
                    sb.append("- 全局状态: ").append(stateObj.getGlobalState()).append("\n");
                }
                sb.append("- 接力次数: ").append(stateObj.getTransferCount()).append("\n");
            } else {
                sb.append("\n### A2A Dashboard\n");
                if (Utils.isNotEmpty(stateObj.getGlobalState())) {
                    sb.append("- Global State: ").append(stateObj.getGlobalState()).append("\n");
                }
                sb.append("- Handovers: ").append(stateObj.getTransferCount()).append("\n");
            }
        }
    }

    @Override
    public void injectSupervisorInstruction(Locale locale, StringBuilder sb) {
        super.injectSupervisorInstruction(locale, sb);
        boolean isZh = Locale.CHINA.getLanguage().equals(locale.getLanguage());

        if (isZh) {
            sb.append("\n### A2A 协作准则：\n");
            sb.append("1. **直接交付**：任务完成时请直接输出 `" + config.getFinishMarker() + "`，**禁止**对专家的内容进行二次总结或润色。\n");
            sb.append("2. **流转审计**：若专家间接力流转超过 3 次仍无实质进展，请立即介入并强制收尾。");
        } else {
            sb.append("\n### A2A Collaboration Rules:\n");
            sb.append("1. **Direct Delivery**: Output `" + config.getFinishMarker() + "` directly. **DO NOT** summarize.\n");
            sb.append("2. **Handover Audit**: Force termination if more than 3 handovers occur.");
        }
    }
}